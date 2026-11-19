package dev.healthintelligence.core.documents;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.healthintelligence.core.support.IntegrationTest;
import dev.healthintelligence.core.support.Pdfs;
import dev.healthintelligence.core.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class DocumentsApiTests extends IntegrationTest {

    static final String DOCUMENTS = "/api/v1/documents";

    @Autowired TestTokens.Issuer tokens;
    @Autowired JsonMapper json;
    @Autowired S3Client s3;
    @Autowired SqsClient sqs;

    String token;
    String patientId;

    @BeforeEach
    void patientWithProfile() {
        token = tokens.newPatient();
        HttpResponse<String> created = api().postJson("/api/v1/patients", token, "{\"fullName\":\"Ana\",\"birthDate\":\"1990-01-01\"}");
        patientId = json.readTree(created.body()).get("id").stringValue();
    }

    void grantAi(boolean granted) {
        api().postJson("/api/v1/consents", token,
                "{\"type\":\"AI_PROCESSING\",\"granted\":" + granted + ",\"documentVersion\":\"ai-2026.1\"}");
    }

    @Test
    void uploadWithAiConsentStoresThePdfAndPublishesAContractValidExtractionRequest() throws Exception {
        grantAi(true);
        byte[] pdf = Pdfs.synthetic();

        HttpResponse<String> response = api().upload(DOCUMENTS, token, "hemograma nov.pdf", pdf);

        assertThat(response.statusCode()).isEqualTo(202);
        JsonNode document = json.readTree(response.body());
        String id = document.get("id").stringValue();
        assertThat(response.headers().firstValue("Location")).hasValue(DOCUMENTS + "/" + id);
        assertThat(document.get("status").stringValue()).isEqualTo("PROCESSING");
        assertThat(document.get("originalFilename").stringValue()).isEqualTo("hemograma nov.pdf");
        assertThat(document.get("sizeBytes").longValue()).isEqualTo(pdf.length);

        ResponseBytes<GetObjectResponse> stored = s3.getObjectAsBytes(r -> r.bucket("hip-documents-local").key("documents/" + id));
        assertThat(stored.asByteArray()).isEqualTo(pdf);

        JsonNode message = awaitExtractionRequest(id);
        assertThat(contractSchema().validate(message)).isEmpty();
        assertThat(message.get("patientId").stringValue()).isEqualTo(patientId);
        assertThat(message.get("document").get("objectKey").stringValue()).isEqualTo("documents/" + id);
        assertThat(message.get("document").get("sha256").stringValue()).isEqualTo(sha256(pdf));
        assertThat(message.get("extractorVersion").stringValue()).isEqualTo("1.0.0");

        // Published exactly once: the row is marked, and later relay runs send nothing more.
        Thread.sleep(1_000);
        assertThat(count("select count(*) from core.outbox_event where aggregate_id = ?::uuid and published_at is not null and attempts = 1", id))
                .isEqualTo(1);
        assertThat(receiveFor(id, Duration.ofSeconds(1))).isEmpty();
    }

    @Test
    void uploadWithoutAiConsentIsStoredButNotSentForExtraction() throws Exception {
        grantAi(false);

        HttpResponse<String> response = api().upload(DOCUMENTS, token, "laudo.pdf", Pdfs.synthetic());

        assertThat(response.statusCode()).isEqualTo(202);
        String id = json.readTree(response.body()).get("id").stringValue();
        assertThat(json.readTree(response.body()).get("status").stringValue()).isEqualTo("UPLOADED");
        assertThat(count("select count(*) from core.outbox_event where aggregate_id = ?::uuid", id)).isZero();
        assertThat(count("select count(*) from core.extraction_job where document_id = ?::uuid", id)).isZero();
    }

    @Test
    void rejectsWhatIsNotAReadablePdfWithClearCodes() {
        assertThat(code(api().upload(DOCUMENTS, token, "foto.jpg", new byte[] {(byte) 0xFF, (byte) 0xD8, 1, 2}))).isEqualTo("415 UNSUPPORTED_DOCUMENT");
        assertThat(code(api().upload(DOCUMENTS, token, "senha.pdf", Pdfs.encrypted()))).isEqualTo("415 DOCUMENT_ENCRYPTED");
        assertThat(code(api().upload(DOCUMENTS, token, "vazio.pdf", new byte[0]))).isEqualTo("400 VALIDATION_FAILED");
        byte[] big = new byte[10 * 1024 * 1024 + 1];
        System.arraycopy("%PDF-1.4".getBytes(), 0, big, 0, 8);
        assertThat(code(api().upload(DOCUMENTS, token, "grande.pdf", big))).isEqualTo("413 DOCUMENT_TOO_LARGE");
        assertThat(json.readTree(api().get(DOCUMENTS, token).body()).get("items").isEmpty()).isTrue();
    }

    @Test
    void sameFileTwiceIsADuplicateUntilTheFirstIsDeleted() {
        byte[] pdf = Pdfs.synthetic();
        String id = json.readTree(api().upload(DOCUMENTS, token, "a.pdf", pdf).body()).get("id").stringValue();

        assertThat(code(api().upload(DOCUMENTS, token, "copia.pdf", pdf))).isEqualTo("409 DUPLICATE_DOCUMENT");

        assertThat(api().delete(DOCUMENTS + "/" + id, token).statusCode()).isEqualTo(204);
        assertThat(api().upload(DOCUMENTS, token, "a.pdf", pdf).statusCode()).isEqualTo(202);
    }

    @Test
    void retryWithTheSameIdempotencyKeyReplaysTheFirstResponse() {
        String key = UUID.randomUUID().toString();
        byte[] pdf = Pdfs.synthetic();

        HttpResponse<String> first = api().upload(DOCUMENTS, token, "a.pdf", pdf, "Idempotency-Key", key);
        HttpResponse<String> retry = api().upload(DOCUMENTS, token, "a.pdf", pdf, "Idempotency-Key", key);
        HttpResponse<String> otherFile = api().upload(DOCUMENTS, token, "b.pdf", Pdfs.synthetic(), "Idempotency-Key", key);
        HttpResponse<String> badKey = api().upload(DOCUMENTS, token, "c.pdf", Pdfs.synthetic(), "Idempotency-Key", "abc");

        assertThat(first.statusCode()).isEqualTo(202);
        assertThat(retry.statusCode()).isEqualTo(202);
        assertThat(retry.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(json.readTree(retry.body())).isEqualTo(json.readTree(first.body()));
        assertThat(code(otherFile)).isEqualTo("422 IDEMPOTENCY_KEY_REUSED");
        assertThat(code(badKey)).isEqualTo("400 VALIDATION_FAILED");
        assertThat(json.readTree(api().get(DOCUMENTS, token).body()).get("items").size()).isEqualTo(1);
    }

    @Test
    void failedUploadReleasesTheIdempotencyKey() {
        String key = UUID.randomUUID().toString();
        byte[] pdf = Pdfs.synthetic();

        assertThat(api().upload(DOCUMENTS, token, "x.jpg", new byte[] {1, 2, 3}, "Idempotency-Key", key).statusCode()).isEqualTo(415);
        assertThat(api().upload(DOCUMENTS, token, "x.pdf", pdf, "Idempotency-Key", key).statusCode()).isEqualTo(202);
    }

    @Test
    void listIsNewestFirstWithCursorPaginationAndStatusFilter() {
        grantAi(false);
        String oldest = json.readTree(api().upload(DOCUMENTS, token, "1.pdf", Pdfs.synthetic()).body()).get("id").stringValue();
        api().upload(DOCUMENTS, token, "2.pdf", Pdfs.synthetic());
        grantAi(true);
        api().upload(DOCUMENTS, token, "3.pdf", Pdfs.synthetic());

        JsonNode first = json.readTree(api().get(DOCUMENTS + "?limit=2", token).body());
        assertThat(names(first)).containsExactly("3.pdf", "2.pdf");
        String cursor = first.get("nextCursor").stringValue();
        JsonNode second = json.readTree(api().get(DOCUMENTS + "?limit=2&cursor=" + cursor, token).body());
        assertThat(names(second)).containsExactly("1.pdf");
        assertThat(second.get("nextCursor").isNull()).isTrue();
        assertThat(second.get("items").get(0).get("id").stringValue()).isEqualTo(oldest);

        assertThat(names(json.readTree(api().get(DOCUMENTS + "?status=PROCESSING", token).body()))).containsExactly("3.pdf");
        assertThat(code(api().get(DOCUMENTS + "?limit=101", token))).isEqualTo("400 VALIDATION_FAILED");
        assertThat(code(api().get(DOCUMENTS + "?cursor=lixo", token))).isEqualTo("400 VALIDATION_FAILED");
    }

    @Test
    void contentIsAShortLivedAuditedLink() throws Exception {
        byte[] pdf = Pdfs.synthetic();
        String id = json.readTree(api().upload(DOCUMENTS, token, "laudo.pdf", pdf).body()).get("id").stringValue();

        HttpResponse<String> asJson = api().get(DOCUMENTS + "/" + id + "/content", token, "application/json");
        HttpResponse<String> redirect = api().get(DOCUMENTS + "/" + id + "/content", token);

        assertThat(asJson.statusCode()).isEqualTo(200);
        assertThat(asJson.headers().firstValue("Cache-Control")).hasValue("no-store");
        JsonNode link = json.readTree(asJson.body());
        Instant expiresAt = Instant.parse(link.get("expiresAt").stringValue());
        assertThat(Duration.between(Instant.now(), expiresAt)).isBetween(Duration.ofSeconds(50), Duration.ofSeconds(61));
        HttpResponse<byte[]> download = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(link.get("url").stringValue())).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(pdf);

        assertThat(redirect.statusCode()).isEqualTo(302);
        assertThat(redirect.headers().firstValue("Location")).hasValueSatisfying(url -> assertThat(url).contains("documents/" + id));
        assertThat(count("select count(*) from core.audit_event where resource_id = ?::uuid and action = 'DOCUMENT_CONTENT_ACCESSED'", id))
                .isEqualTo(2);
    }

    @Test
    void deleteHidesTheDocumentAndRemovesTheFile() throws Exception {
        String id = json.readTree(api().upload(DOCUMENTS, token, "laudo.pdf", Pdfs.synthetic()).body()).get("id").stringValue();

        assertThat(api().delete(DOCUMENTS + "/" + id, token).statusCode()).isEqualTo(204);

        assertThat(code(api().get(DOCUMENTS + "/" + id, token))).isEqualTo("404 DOCUMENT_NOT_FOUND");
        assertThat(code(api().delete(DOCUMENTS + "/" + id, token))).isEqualTo("404 DOCUMENT_NOT_FOUND");
        assertThat(json.readTree(api().get(DOCUMENTS, token).body()).get("items").isEmpty()).isTrue();
        try {
            s3.headObject(r -> r.bucket("hip-documents-local").key("documents/" + id));
            throw new AssertionError("object still stored");
        } catch (NoSuchKeyException expected) {
            // removed after commit
        } catch (software.amazon.awssdk.services.s3.model.S3Exception notFound) {
            assertThat(notFound.statusCode()).isEqualTo(404);
        }
    }

    @Test
    void documentsNeedTheProfileFirst() {
        String withoutProfile = tokens.newPatient();

        assertThat(code(api().upload(DOCUMENTS, withoutProfile, "a.pdf", Pdfs.synthetic()))).isEqualTo("404 PATIENT_NOT_FOUND");
    }

    private JsonNode awaitExtractionRequest(String documentId) {
        return receiveFor(documentId, Duration.ofSeconds(10))
                .orElseThrow(() -> new AssertionError("No extraction request published for " + documentId));
    }

    /** Receives and deletes the first message about the document within the timeout. */
    private java.util.Optional<JsonNode> receiveFor(String documentId, Duration timeout) {
        String queueUrl = sqs.getQueueUrl(r -> r.queueName("document-extraction-requests")).queueUrl();
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            List<Message> messages = sqs.receiveMessage(r -> r.queueUrl(queueUrl).maxNumberOfMessages(10).waitTimeSeconds(1)
                    .messageAttributeNames("All")).messages();
            for (Message message : messages) {
                JsonNode body = json.readTree(message.body());
                if (documentId.equals(body.path("documentId").stringValue(null))) {
                    assertThat(message.messageAttributes().get("eventType").stringValue()).isEqualTo("DocumentExtractionRequested");
                    sqs.deleteMessage(r -> r.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
                    return java.util.Optional.of(body);
                }
            }
        }
        return java.util.Optional.empty();
    }

    private Schema contractSchema() throws Exception {
        JsonNode schema = json.readTree(Files.readString(Path.of("../contracts/events/document-extraction-request.v1.schema.json")));
        return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(schema);
    }

    private String code(HttpResponse<String> response) {
        return response.statusCode() + " " + json.readTree(response.body()).get("code").stringValue();
    }

    private static List<String> names(JsonNode page) {
        return page.get("items").valueStream().map(item -> item.get("originalFilename").stringValue()).toList();
    }

    private static long count(String sql, String id) throws Exception {
        try (Connection connection = connect("core_owner", CORE_OWNER_PASSWORD);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
