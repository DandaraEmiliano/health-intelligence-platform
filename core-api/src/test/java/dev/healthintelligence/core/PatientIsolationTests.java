package dev.healthintelligence.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import dev.healthintelligence.core.support.IntegrationTest;
import dev.healthintelligence.core.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR 0011 authorization suite: patient B can never read, list, modify or infer patient A's data.
 * Every new patient-facing endpoint adds its case here. It blocks merges (core-api workflow).
 */
class PatientIsolationTests extends IntegrationTest {

    @Autowired
    TestTokens.Issuer tokens;

    @Autowired
    JsonMapper json;

    String patientA;
    String patientB;
    String patientAId;

    @BeforeEach
    void twoPatients() {
        patientA = tokens.newPatient();
        patientB = tokens.newPatient();
        HttpResponse<String> created = api().postJson("/api/v1/patients", patientA, """
                {"fullName":"Paciente A","birthDate":"1980-05-05","heightCm":180}""");
        assertThat(created.statusCode()).isEqualTo(201);
        patientAId = json.readTree(created.body()).get("id").stringValue();
        api().postJson("/api/v1/consents", patientA, """
                {"type":"AI_PROCESSING","granted":true,"documentVersion":"ai-2026.1"}""");
    }

    @Test
    void profileOfAnotherPatientIsNotVisible() {
        HttpResponse<String> response = api().get("/api/v1/patients/me", patientB);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).doesNotContain(patientAId).doesNotContain("Paciente A");
    }

    @Test
    void anotherPatientCannotModifyTheProfileEvenWithItsETag() {
        HttpResponse<String> response = api().mergePatch("/api/v1/patients/me", patientB, "\"0\"",
                "{\"fullName\":\"Invasor\"}");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json.readTree(api().get("/api/v1/patients/me", patientA).body()).get("fullName").stringValue())
                .isEqualTo("Paciente A");
    }

    @Test
    void eachPatientSeesOnlyTheirOwnProfile() {
        api().postJson("/api/v1/patients", patientB, """
                {"fullName":"Paciente B","birthDate":"1995-09-09"}""");

        assertThat(json.readTree(api().get("/api/v1/patients/me", patientA).body()).get("fullName").stringValue())
                .isEqualTo("Paciente A");
        assertThat(json.readTree(api().get("/api/v1/patients/me", patientB).body()).get("fullName").stringValue())
                .isEqualTo("Paciente B");
    }

    @Test
    void consentsOfAnotherPatientAreNotVisible() {
        HttpResponse<String> response = api().get("/api/v1/consents", patientB);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).get("items").isEmpty()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "allergies:{\"substance\":\"Penicilina\",\"allergyType\":\"MEDICATION\"}",
            "medications:{\"name\":\"Metformina\"}",
            "conditions:{\"description\":\"Asma\",\"status\":\"ACTIVE\"}"})
    void healthRecordsOfAnotherPatientCannotBeReadChangedOrDeleted(String kindAndBody) {
        String collection = "/api/v1/patients/me/" + kindAndBody.substring(0, kindAndBody.indexOf(':'));
        String body = kindAndBody.substring(kindAndBody.indexOf(':') + 1);
        String recordOfA = json.readTree(api().postJson(collection, patientA, body).body()).get("id").stringValue();
        api().postJson("/api/v1/patients", patientB, "{\"fullName\":\"Paciente B\",\"birthDate\":\"1995-09-09\"}");

        assertThat(api().get(collection + "/" + recordOfA, patientB).statusCode()).isEqualTo(404);
        assertThat(api().putJson(collection + "/" + recordOfA, patientB, "\"0\"", body).statusCode()).isEqualTo(404);
        assertThat(api().delete(collection + "/" + recordOfA, patientB).statusCode()).isEqualTo(404);
        HttpResponse<String> listOfB = api().get(collection, patientB);
        assertThat(listOfB.statusCode()).isEqualTo(200);
        assertThat(json.readTree(listOfB.body()).get("items").isEmpty()).isTrue();

        HttpResponse<String> stillThere = api().get(collection + "/" + recordOfA, patientA);
        assertThat(stillThere.statusCode()).isEqualTo(200);
        assertThat(stillThere.headers().firstValue("ETag")).hasValue("\"0\"");
    }

    @Test
    void documentsOfAnotherPatientCannotBeSeenOpenedOrDeleted() {
        String documentOfA = json.readTree(api().upload("/api/v1/documents", patientA, "a.pdf",
                dev.healthintelligence.core.support.Pdfs.synthetic()).body()).get("id").stringValue();
        api().postJson("/api/v1/patients", patientB, "{\"fullName\":\"Paciente B\",\"birthDate\":\"1995-09-09\"}");

        assertThat(api().get("/api/v1/documents/" + documentOfA, patientB).statusCode()).isEqualTo(404);
        assertThat(api().get("/api/v1/documents/" + documentOfA + "/content", patientB, "application/json").statusCode()).isEqualTo(404);
        assertThat(api().delete("/api/v1/documents/" + documentOfA, patientB).statusCode()).isEqualTo(404);
        assertThat(json.readTree(api().get("/api/v1/documents", patientB).body()).get("items").isEmpty()).isTrue();
        assertThat(api().get("/api/v1/documents/" + documentOfA, patientA).statusCode()).isEqualTo(200);
    }

    @Test
    void conversationsOfAnotherPatientCannotBeReadAskedOrDeleted() {
        dev.healthintelligence.core.support.FakeAiService.INSTANCE.reset();
        String conversationOfA = json.readTree(api().postJson("/api/v1/ai/conversations", patientA, "{}").body())
                .get("id").stringValue();
        assertThat(api().postJson("/api/v1/ai/conversations/" + conversationOfA + "/messages", patientA,
                "{\"content\":\"Pergunta da paciente A\"}").statusCode()).isEqualTo(201);
        api().postJson("/api/v1/patients", patientB, "{\"fullName\":\"Paciente B\",\"birthDate\":\"1995-09-09\"}");
        api().postJson("/api/v1/consents", patientB, """
                {"type":"AI_PROCESSING","granted":true,"documentVersion":"ai-2026.1"}""");

        HttpResponse<String> read = api().get("/api/v1/ai/conversations/" + conversationOfA, patientB);
        HttpResponse<String> asked = api().postJson("/api/v1/ai/conversations/" + conversationOfA + "/messages", patientB,
                "{\"content\":\"Oi\"}");

        assertThat(read.statusCode()).isEqualTo(404);
        assertThat(read.body()).doesNotContain("Pergunta da paciente A");
        assertThat(asked.statusCode()).isEqualTo(404);
        assertThat(api().delete("/api/v1/ai/conversations/" + conversationOfA, patientB).statusCode()).isEqualTo(404);
        assertThat(json.readTree(api().get("/api/v1/ai/conversations", patientB).body()).get("items").isEmpty()).isTrue();
        // Only A's question reached ai-service, scoped to A.
        assertThat(dev.healthintelligence.core.support.FakeAiService.INSTANCE.requests()).hasSize(1);
        assertThat(dev.healthintelligence.core.support.FakeAiService.INSTANCE.requests().getFirst().get("patientId")
                .stringValue()).isEqualTo(patientAId);
        JsonNode stillThere = json.readTree(api().get("/api/v1/ai/conversations/" + conversationOfA, patientA).body());
        assertThat(stillThere.get("messages").size()).isEqualTo(2);
    }

    @Test
    void patientIdInTheRequestBodyIsRejected() {
        HttpResponse<String> response = api().postJson("/api/v1/patients", patientB, """
                {"fullName":"B","birthDate":"1995-09-09","id":"%s"}""".formatted(patientAId));

        assertThat(response.statusCode()).isEqualTo(400);
    }
}
