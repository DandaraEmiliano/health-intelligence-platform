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

/** A patient with a document whose extraction job is waiting for a result. */
abstract class ReportTestSupport extends IntegrationTest {

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

    static JsonNode findByWarning(JsonNode detail, String code) {
        for (JsonNode result : detail.get("results")) {
            for (JsonNode warning : result.get("warnings")) {
                if (code.equals(warning.get("code").stringValue())) {
                    return result;
                }
            }
        }
        throw new AssertionError("no result with " + code);
    }

    static <T> T await(Supplier<T> read, java.util.function.Predicate<T> done) throws InterruptedException {
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

    static String single(String sql, String parameter) throws Exception {
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
