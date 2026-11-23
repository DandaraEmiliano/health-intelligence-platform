package dev.healthintelligence.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import dev.healthintelligence.core.laboratory.ReportTestSupport;
import dev.healthintelligence.core.support.FakeAiService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** LGPD art. 18, II and V: the account holder gets everything, and only their own data. */
class DataExportTests extends ReportTestSupport {

    @Test
    void exportsEverySectionOfTheOwnerOnly() throws Exception {
        api().postJson("/api/v1/patients/me/allergies", token, """
                {"substance":"Dipirona","allergyType":"MEDICATION","severity":"MODERATE"}""");
        String reportId = ingest(result("SUCCEEDED"));
        FakeAiService.INSTANCE.reset();
        String conversation = json.readTree(api().postJson("/api/v1/ai/conversations", token, "{}").body()).get("id").stringValue();
        api().postJson("/api/v1/ai/conversations/" + conversation + "/messages", token, "{\"content\":\"Pergunta sintética\"}");
        String other = tokens.newPatient();
        api().postJson("/api/v1/patients", other, "{\"fullName\":\"Outra Pessoa\",\"birthDate\":\"1990-01-01\"}");
        api().postJson("/api/v1/patients/me/allergies", other, """
                {"substance":"Amendoim","allergyType":"FOOD","severity":"SEVERE"}""");

        HttpResponse<String> response = api().get("/api/v1/accounts/me/export", token);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Disposition").orElseThrow()).startsWith("attachment").contains("meus-dados-");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        JsonNode export = json.readTree(response.body());
        assertThat(export.get("schemaVersion").stringValue()).isEqualTo("1.0");
        JsonNode data = export.get("data");
        assertThat(data.propertyNames()).containsExactlyInAnyOrder("account", "assistantConversations", "consents",
                "documents", "healthRecords", "laboratoryReports", "notifications", "profile");
        assertThat(data.get("profile").get("id").stringValue()).isEqualTo(patientId);
        assertThat(data.get("profile").get("fullName").stringValue()).isEqualTo("Ana");
        assertThat(data.get("consents").get(0).get("type").stringValue()).isEqualTo("AI_PROCESSING");
        assertThat(data.get("healthRecords").get("allergies").get(0).get("substance").stringValue()).isEqualTo("Dipirona");
        assertThat(data.get("documents").get(0).get("id").stringValue()).isEqualTo(documentId);
        JsonNode report = data.get("laboratoryReports").get(0);
        assertThat(report.get("id").stringValue()).isEqualTo(reportId);
        assertThat(report.get("results").get(0).get("reviewStatus").stringValue()).isEqualTo("PENDING_REVIEW");
        assertThat(data.get("assistantConversations").get(0).get("messages").size()).isEqualTo(2);
        assertThat(data.get("notifications").get(0).get("type").stringValue()).isEqualTo("REVIEW_PENDING");
        assertThat(response.body()).doesNotContain("Amendoim").doesNotContain("Outra Pessoa");
        assertThat(single("select count(*)::text from core.audit_event where action = 'ACCOUNT_DATA_EXPORTED' and patient_id = ?::uuid",
                patientId)).isEqualTo("1");
    }

    @Test
    void anAccountWithoutAProfileGetsEmptySections() {
        String fresh = tokens.newPatient();

        JsonNode data = json.readTree(api().get("/api/v1/accounts/me/export", fresh).body()).get("data");

        assertThat(data.get("account").get("email").stringValue()).endsWith("@example.test");
        assertThat(data.get("profile").isNull()).isTrue();
        assertThat(data.get("laboratoryReports").isEmpty()).isTrue();
        assertThat(data.get("healthRecords").get("allergies").isEmpty()).isTrue();
    }
}
