package dev.healthintelligence.core.laboratory;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.function.Supplier;

import dev.healthintelligence.core.support.IntegrationTest;
import dev.healthintelligence.core.support.Pdfs;
import dev.healthintelligence.core.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class LaboratoryIngestionTests extends IntegrationTest {

    /** A beta-layout fixture: multiple ranges, lab flags, thousands separators, two pages. */
    static final String FIXTURE = "../synthetic-data/fixtures/lab-reports/SP-001-2022-03-16-beta.expected.json";

    @Autowired TestTokens.Issuer tokens;
    @Autowired JsonMapper json;
    @Autowired SqsClient sqs;

    String token;
    String patientId;
    String documentId;
    String jobId;

    @BeforeEach
    void documentWaitingForExtraction() throws Exception {
        token = tokens.newPatient();
        api().postJson("/api/v1/consents", token, "{\"type\":\"AI_PROCESSING\",\"granted\":true,\"documentVersion\":\"ai-2026.1\"}");
        patientId = json.readTree(api().postJson("/api/v1/patients", token, "{\"fullName\":\"Ana\",\"birthDate\":\"1988-04-12\"}").body())
                .get("id").stringValue();
        documentId = json.readTree(api().upload("/api/v1/documents", token, "laudo.pdf", Pdfs.synthetic()).body()).get("id").stringValue();
        jobId = single("select id::text from core.extraction_job where document_id = ?::uuid", documentId);
    }

    ObjectNode result(String status) throws Exception {
        ObjectNode message = json.createObjectNode();
        message.put("schemaVersion", "1.0");
        message.put("messageId", UUID.randomUUID().toString());
        message.put("jobId", jobId);
        message.put("documentId", documentId);
        message.put("patientId", patientId);
        message.put("extractorVersion", "1.0.0");
        message.put("status", status);
        message.put("completedAt", "2026-11-19T12:00:00Z");
        if (status.equals("SUCCEEDED")) {
            message.set("report", json.readTree(Files.readString(Path.of(FIXTURE))).get("expected").get("report"));
        }
        return message;
    }

    void publish(JsonNode message) {
        String url = sqs.getQueueUrl(r -> r.queueName("document-extraction-results")).queueUrl();
        sqs.sendMessage(r -> r.queueUrl(url).messageBody(message.toString()));
    }

    @Test
    void aSuccessfulExtractionBecomesAReportPendingReview() throws Exception {
        publish(result("SUCCEEDED"));

        JsonNode list = await(() -> json.readTree(api().get("/api/v1/laboratory-reports", token).body()),
                page -> page.get("items").size() == 1);
        JsonNode summary = list.get("items").get(0);
        assertThat(summary.get("documentId").stringValue()).isEqualTo(documentId);
        assertThat(summary.get("status").stringValue()).isEqualTo("PENDING_REVIEW");
        assertThat(summary.get("collectedAt").stringValue()).isEqualTo("2022-03-16");
        int expectedResults = json.readTree(Files.readString(Path.of(FIXTURE))).get("expected").get("report").get("results").size();
        assertThat(summary.get("resultCount").intValue()).isEqualTo(expectedResults);
        assertThat(summary.get("pendingCount").intValue()).isEqualTo(expectedResults);

        HttpResponse<String> detailResponse = api().get("/api/v1/laboratory-reports/" + summary.get("id").stringValue(), token);
        assertThat(detailResponse.headers().firstValue("ETag")).hasValue("\"0\"");
        JsonNode detail = json.readTree(detailResponse.body());
        JsonNode first = detail.get("results").get(0);
        assertThat(first.get("rawName").stringValue()).isEqualTo("COLESTEROL TOTAL");
        assertThat(first.get("measurement").get("valueNumeric").stringValue()).isEqualTo("206");
        assertThat(first.get("referenceRange").get("high").stringValue()).isEqualTo("190");
        assertThat(first.get("flag").get("value").stringValue()).isEqualTo("HIGH");
        assertThat(first.get("flag").get("source").stringValue()).isEqualTo("LABORATORY");
        assertThat(first.get("source").get("excerpt").stringValue()).isEqualTo("Resultado: 206 mg/dL  [ALTO]");
        assertThat(first.get("reviewStatus").stringValue()).isEqualTo("PENDING_REVIEW");

        JsonNode multiple = findByWarning(detail, "MULTIPLE_RANGES");
        assertThat(multiple.get("referenceRange").get("low").isNull()).isTrue();
        assertThat(multiple.has("flag") && !multiple.get("flag").isNull()).isFalse();

        assertThat(json.readTree(api().get("/api/v1/documents/" + documentId, token).body()).get("status").stringValue())
                .isEqualTo("PROCESSED");
        assertThat(single("select status from core.extraction_job where id = ?::uuid", jobId)).isEqualTo("SUCCEEDED");
        assertThat(single("select extracted_snapshot->>'rawName' from core.laboratory_result where sequence = 1 and patient_id = ?::uuid", patientId))
                .isEqualTo("COLESTEROL TOTAL");
    }

    @Test
    void aRedeliveredResultIsStoredOnce() throws Exception {
        ObjectNode message = result("SUCCEEDED");
        publish(message);
        publish(message);

        await(() -> json.readTree(api().get("/api/v1/laboratory-reports", token).body()), page -> page.get("items").size() == 1);
        Thread.sleep(1_000); // several polls later
        assertThat(single("select count(*)::text from core.laboratory_report where document_id = ?::uuid", documentId)).isEqualTo("1");
    }

    @Test
    void failedAndScannedResultsUpdateTheDocument() throws Exception {
        ObjectNode failed = result("FAILED");
        failed.set("failure", json.readTree("{\"code\":\"PDF_CORRUPTED\",\"retryable\":false}"));
        publish(failed);

        JsonNode document = await(() -> json.readTree(api().get("/api/v1/documents/" + documentId, token).body()),
                d -> d.get("status").stringValue().equals("FAILED"));
        assertThat(document.get("failureCode").stringValue()).isEqualTo("PDF_CORRUPTED");
        assertThat(json.readTree(api().get("/api/v1/laboratory-reports", token).body()).get("items").isEmpty()).isTrue();
    }

    @Test
    void aResultNamingAnotherPatientOrBreakingTheContractIsIgnored() throws Exception {
        ObjectNode wrongPatient = result("SUCCEEDED");
        wrongPatient.put("patientId", UUID.randomUUID().toString());
        ObjectNode invalid = result("SUCCEEDED");
        invalid.remove("report");
        publish(wrongPatient);
        publish(invalid);

        Thread.sleep(1_500);
        assertThat(single("select count(*)::text from core.laboratory_report where document_id = ?::uuid", documentId)).isEqualTo("0");
        assertThat(single("select status from core.extraction_job where id = ?::uuid", jobId)).isEqualTo("REQUESTED");

        publish(result("UNSUPPORTED_SCANNED"));
        await(() -> json.readTree(api().get("/api/v1/documents/" + documentId, token).body()),
                d -> d.get("status").stringValue().equals("UNSUPPORTED_SCANNED"));
    }

    @Test
    void anotherPatientCannotSeeTheReport() throws Exception {
        publish(result("SUCCEEDED"));
        JsonNode list = await(() -> json.readTree(api().get("/api/v1/laboratory-reports", token).body()), p -> p.get("items").size() == 1);
        String reportId = list.get("items").get(0).get("id").stringValue();
        String other = tokens.newPatient();
        api().postJson("/api/v1/patients", other, "{\"fullName\":\"Bia\",\"birthDate\":\"1991-01-01\"}");

        assertThat(api().get("/api/v1/laboratory-reports/" + reportId, other).statusCode()).isEqualTo(404);
        assertThat(json.readTree(api().get("/api/v1/laboratory-reports", other).body()).get("items").isEmpty()).isTrue();
    }

    @Test
    void computedFlagsFollowTheBoundsAndNeverCensoredValues() {
        var big = (java.util.function.Function<String, java.math.BigDecimal>) java.math.BigDecimal::new;
        assertThat(ExtractionResultIngestion.computedFlag("NUMERIC", big.apply("206"), null, null, big.apply("190"))).isEqualTo("HIGH");
        assertThat(ExtractionResultIngestion.computedFlag("NUMERIC", big.apply("60"), null, big.apply("70"), big.apply("99"))).isEqualTo("LOW");
        assertThat(ExtractionResultIngestion.computedFlag("NUMERIC", big.apply("80"), null, big.apply("70"), big.apply("99"))).isEqualTo("IN_RANGE");
        assertThat(ExtractionResultIngestion.computedFlag("NUMERIC", big.apply("0.5"), "<", null, big.apply("1"))).isNull();
        assertThat(ExtractionResultIngestion.computedFlag("TEXT", null, null, null, null)).isNull();
    }

    private static JsonNode findByWarning(JsonNode detail, String code) {
        for (JsonNode result : detail.get("results")) {
            for (JsonNode warning : result.get("warnings")) {
                if (code.equals(warning.get("code").stringValue())) {
                    return result;
                }
            }
        }
        throw new AssertionError("no result with " + code);
    }

    private static <T> T await(Supplier<T> read, java.util.function.Predicate<T> done) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        T value = read.get();
        while (!done.test(value)) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("condition not met: " + value);
            }
            Thread.sleep(200);
            value = read.get();
        }
        return value;
    }

    private static String single(String sql, String parameter) throws Exception {
        try (Connection connection = connect("core_owner", CORE_OWNER_PASSWORD);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}
