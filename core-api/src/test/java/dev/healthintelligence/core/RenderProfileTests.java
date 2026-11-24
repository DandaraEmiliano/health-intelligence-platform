package dev.healthintelligence.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import dev.healthintelligence.core.support.FakeAiService;
import dev.healthintelligence.core.support.IntegrationTest;
import dev.healthintelligence.core.support.Pdfs;
import dev.healthintelligence.core.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Render profile's transport: PDFs in the database behind signed links, extraction requests
 * POSTed to ai-service and results POSTed back, all under the shared internal token.
 */
class RenderProfileTests extends IntegrationTest {

    static final String SECRET = "internal-token-for-tests-0123456789abcdef";
    static final String FIXTURE = "../synthetic-data/fixtures/lab-reports/SP-001-2022-03-16-beta.expected.json";
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void renderProfile(DynamicPropertyRegistry registry) {
        registry.add("hip.documents.store", () -> "database");
        registry.add("hip.documents.link-secret", () -> "link-secret-for-tests-0123456789abcdef");
        registry.add("hip.public-base-url", () -> "http://public.test");
        registry.add("hip.messaging.transport", () -> "http");
        registry.add("hip.messaging.http.base-url", FakeAiService.INSTANCE::baseUrl);
        registry.add("hip.internal.token", () -> SECRET);
    }

    @Autowired TestTokens.Issuer tokens;
    @Autowired JsonMapper json;

    @Test
    void anUploadTravelsToAiServiceAndItsResultComesBack() throws Exception {
        String token = tokens.newPatient();
        api().postJson("/api/v1/consents", token, "{\"type\":\"AI_PROCESSING\",\"granted\":true,\"documentVersion\":\"ai-2026.1\"}");
        String patientId = json.readTree(api().postJson("/api/v1/patients", token,
                "{\"fullName\":\"Ana\",\"birthDate\":\"1988-04-12\"}").body()).get("id").stringValue();
        byte[] pdf = Pdfs.synthetic();
        String documentId = json.readTree(api().upload("/api/v1/documents", token, "laudo.pdf", pdf).body())
                .get("id").stringValue();

        FakeAiService.Delivery delivery = await(documentId);
        assertThat(delivery.authorization()).isEqualTo("Bearer " + SECRET);
        assertThat(delivery.eventType()).isEqualTo("DocumentExtractionRequested");
        JsonNode document = delivery.body().get("document");
        assertThat(document.get("bucket").stringValue()).isEqualTo("database");

        // ai-service reads the PDF by key, with the token and only with it.
        String object = document.get("objectKey").stringValue().substring("documents/".length());
        assertThat(internalGet("/internal/v1/document-objects/" + object, SECRET).body()).isEqualTo(pdf);
        assertThat(internalGet("/internal/v1/document-objects/" + object, null).statusCode()).isEqualTo(401);
        assertThat(internalGet("/internal/v1/document-objects/" + object, SECRET + "x").statusCode()).isEqualTo(401);
        assertThat(internalGet("/internal/v1/document-objects/" + UUID.randomUUID(), SECRET).statusCode()).isEqualTo(404);
        // A patient token opens nothing internal.
        assertThat(internalGet("/internal/v1/document-objects/" + object, token).statusCode()).isEqualTo(401);

        ObjectNode result = json.createObjectNode();
        result.put("schemaVersion", "1.0");
        result.put("messageId", UUID.randomUUID().toString());
        result.put("jobId", delivery.body().get("jobId").stringValue());
        result.put("documentId", documentId);
        result.put("patientId", patientId);
        result.put("extractorVersion", "1.0.0");
        result.put("status", "SUCCEEDED");
        result.put("completedAt", "2026-11-19T12:00:00Z");
        result.set("report", json.readTree(Files.readString(Path.of(FIXTURE))).get("expected").get("report"));

        assertThat(internalPost("{\"not\":\"a result\"}", SECRET).statusCode()).isEqualTo(400);
        assertThat(internalPost(result.toString(), null).statusCode()).isEqualTo(401);
        assertThat(internalPost(result.toString(), SECRET).statusCode()).isEqualTo(202);
        assertThat(internalPost(result.toString(), SECRET).statusCode()).isEqualTo(202); // redelivery is harmless

        JsonNode reports = json.readTree(api().get("/api/v1/laboratory-reports", token).body()).get("items");
        assertThat(reports.size()).isEqualTo(1);
        assertThat(reports.get(0).get("documentId").stringValue()).isEqualTo(documentId);
    }

    @Test
    void theDownloadLinkIsSignedAndShortLived() throws Exception {
        String token = tokens.newPatient();
        api().postJson("/api/v1/patients", token, "{\"fullName\":\"Ana\",\"birthDate\":\"1988-04-12\"}");
        byte[] original = Pdfs.synthetic();
        String documentId = json.readTree(api().upload("/api/v1/documents", token, "Laudo março.pdf", original).body())
                .get("id").stringValue();

        String url = json.readTree(api().get("/api/v1/documents/" + documentId + "/content", token, "application/json").body())
                .get("url").stringValue();
        assertThat(url).startsWith("http://public.test/api/v1/document-content/");
        String local = url.replace("http://public.test", "http://localhost:" + port);

        HttpResponse<byte[]> pdf = HTTP.send(HttpRequest.newBuilder(URI.create(local)).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(pdf.statusCode()).isEqualTo(200);
        assertThat(pdf.body()).isEqualTo(original);
        assertThat(pdf.headers().firstValue("Content-Type").orElseThrow()).isEqualTo("application/pdf");
        assertThat(pdf.headers().firstValue("Content-Disposition").orElseThrow()).startsWith("inline;");
        assertThat(pdf.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");

        assertThat(status(local.replaceAll("signature=[^&]+", "signature=forged"))).isEqualTo(404);
        assertThat(status(local.replaceAll("expires=\\d+", "expires=4102444800"))).isEqualTo(404);
        assertThat(status(local.replaceAll("expires=\\d+", "expires=1"))).isEqualTo(404);

        // Once the document is deleted the link opens nothing.
        api().delete("/api/v1/documents/" + documentId, token);
        assertThat(status(local)).isEqualTo(404);
    }

    private FakeAiService.Delivery await(String documentId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            for (FakeAiService.Delivery delivery : FakeAiService.INSTANCE.extractionRequests()) {
                if (delivery.body().get("documentId").stringValue().equals(documentId)) {
                    return delivery;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("extraction request never delivered");
    }

    private int status(String url) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private HttpResponse<byte[]> internalGet(String path, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<String> internalPost(String body, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/internal/v1/queues/document-extraction-results"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
