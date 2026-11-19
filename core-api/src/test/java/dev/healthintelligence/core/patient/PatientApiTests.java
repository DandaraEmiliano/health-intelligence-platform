package dev.healthintelligence.core.patient;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import dev.healthintelligence.core.support.IntegrationTest;
import dev.healthintelligence.core.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PatientApiTests extends IntegrationTest {

    static final String PATIENTS = "/api/v1/patients";
    static final String ME = "/api/v1/patients/me";
    static final String VALID = """
            {"fullName":"Ana Sintetica","birthDate":"1990-04-12","biologicalSex":"FEMALE","heightCm":165.5}""";

    @Autowired
    TestTokens.Issuer tokens;

    @Autowired
    JsonMapper json;

    @Test
    void onboardingCreatesTheProfileAndReturnsItWithAnETag() {
        String token = tokens.newPatient();

        HttpResponse<String> created = api().postJson(PATIENTS, token, VALID);

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.headers().firstValue("Location")).hasValue(ME);
        assertThat(created.headers().firstValue("ETag")).hasValue("\"0\"");
        JsonNode body = json.readTree(created.body());
        assertThat(body.get("id").stringValue()).matches("[0-9a-f-]{36}");
        assertThat(body.get("fullName").stringValue()).isEqualTo("Ana Sintetica");
        assertThat(body.get("birthDate").stringValue()).isEqualTo("1990-04-12");
        assertThat(body.get("biologicalSex").stringValue()).isEqualTo("FEMALE");
        assertThat(body.get("heightCm").decimalValue()).isEqualByComparingTo("165.5");
        assertThat(body.get("createdAt").stringValue()).endsWith("Z");
        assertThat(body.get("updatedAt").stringValue()).endsWith("Z");

        HttpResponse<String> read = api().get(ME, token);
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(read.headers().firstValue("ETag")).hasValue("\"0\"");
        assertThat(json.readTree(read.body())).isEqualTo(body);
    }

    @Test
    void optionalFieldsAreOmittedWhenAbsent() {
        String token = tokens.newPatient();

        HttpResponse<String> created = api().postJson(PATIENTS, token, """
                {"fullName":"Bia","birthDate":"2001-01-01"}""");

        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode body = json.readTree(created.body());
        assertThat(body.has("biologicalSex")).isFalse();
        assertThat(body.has("heightCm")).isFalse();
    }

    @Test
    void secondProfileForTheSameUserIsAConflict() {
        String token = tokens.newPatient();
        api().postJson(PATIENTS, token, VALID);

        HttpResponse<String> again = api().postJson(PATIENTS, token, VALID);

        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(problemCode(again)).isEqualTo("PATIENT_ALREADY_EXISTS");
    }

    @Test
    void invalidProfileListsEveryInvalidFieldWithoutEchoingValues() {
        HttpResponse<String> response = api().postJson(PATIENTS, tokens.newPatient(), """
                {"fullName":"   ","birthDate":"2999-01-01","heightCm":500}""");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        JsonNode problem = json.readTree(response.body());
        assertThat(problem.get("code").stringValue()).isEqualTo("VALIDATION_FAILED");
        assertThat(problem.get("correlationId").stringValue()).isNotBlank();
        assertThat(problem.get("errors").valueStream().map(e -> e.get("field").stringValue() + ":" + e.get("code").stringValue()))
                .containsExactlyInAnyOrder("fullName:REQUIRED", "birthDate:PAST_DATE_REQUIRED", "heightCm:OUT_OF_RANGE");
        assertThat(response.body()).doesNotContain("2999").doesNotContain("500");
    }

    @Test
    void unknownPropertiesAndBadEnumsAreRejected() {
        String token = tokens.newPatient();

        HttpResponse<String> unknown = api().postJson(PATIENTS, token, """
                {"fullName":"Ana","birthDate":"1990-04-12","patientId":"00000000-0000-0000-0000-000000000000"}""");
        HttpResponse<String> badEnum = api().postJson(PATIENTS, token, """
                {"fullName":"Ana","birthDate":"1990-04-12","biologicalSex":"OTHER"}""");

        assertThat(unknown.statusCode()).isEqualTo(400);
        assertThat(problemCode(unknown)).isEqualTo("MALFORMED_REQUEST");
        assertThat(badEnum.statusCode()).isEqualTo(400);
        assertThat(problemCode(badEnum)).isEqualTo("MALFORMED_REQUEST");
    }

    @Test
    void profileOfUserWithoutOneIsNotFound() {
        HttpResponse<String> response = api().get(ME, tokens.newPatient());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(problemCode(response)).isEqualTo("PATIENT_NOT_FOUND");
    }

    @Test
    void mergePatchUpdatesOnlyTheGivenFieldsAndBumpsTheETag() {
        String token = tokens.newPatient();
        api().postJson(PATIENTS, token, VALID);

        HttpResponse<String> patched = api().mergePatch(ME, token, "\"0\"", """
                {"fullName":"Ana Atualizada","heightCm":null}""");

        assertThat(patched.statusCode()).isEqualTo(200);
        assertThat(patched.headers().firstValue("ETag")).hasValue("\"1\"");
        JsonNode body = json.readTree(patched.body());
        assertThat(body.get("fullName").stringValue()).isEqualTo("Ana Atualizada");
        assertThat(body.has("heightCm")).isFalse();
        assertThat(body.get("birthDate").stringValue()).isEqualTo("1990-04-12");
        assertThat(body.get("biologicalSex").stringValue()).isEqualTo("FEMALE");
        assertThat(json.readTree(api().get(ME, token).body())).isEqualTo(body);
    }

    @Test
    void updateRequiresAMatchingIfMatch() {
        String token = tokens.newPatient();
        api().postJson(PATIENTS, token, VALID);
        api().mergePatch(ME, token, "\"0\"", "{\"fullName\":\"Primeira\"}");

        HttpResponse<String> missing = api().mergePatch(ME, token, null, "{\"fullName\":\"X\"}");
        HttpResponse<String> stale = api().mergePatch(ME, token, "\"0\"", "{\"fullName\":\"X\"}");

        assertThat(missing.statusCode()).isEqualTo(428);
        assertThat(problemCode(missing)).isEqualTo("PRECONDITION_REQUIRED");
        assertThat(stale.statusCode()).isEqualTo(412);
        assertThat(problemCode(stale)).isEqualTo("PRECONDITION_FAILED");
        assertThat(json.readTree(api().get(ME, token).body()).get("fullName").stringValue()).isEqualTo("Primeira");
    }

    @Test
    void invalidPatchesAreRejected() {
        String token = tokens.newPatient();
        api().postJson(PATIENTS, token, VALID);

        assertThat(problemFields(api().mergePatch(ME, token, "\"0\"", "{}"))).containsExactly(":INVALID_FORMAT");
        assertThat(problemFields(api().mergePatch(ME, token, "\"0\"", "{\"birthDate\":null,\"id\":\"x\"}")))
                .containsExactlyInAnyOrder("birthDate:REQUIRED", "id:UNKNOWN_FIELD");
        assertThat(problemFields(api().mergePatch(ME, token, "\"0\"", "{\"heightCm\":\"tall\"}")))
                .containsExactly("heightCm:INVALID_TYPE");
        assertThat(problemFields(api().mergePatch(ME, token, "\"0\"", "{\"heightCm\":170.25}")))
                .containsExactly("heightCm:INVALID_PRECISION");
        assertThat(problemFields(api().mergePatch(ME, token, "\"0\"", "{\"biologicalSex\":\"OTHER\"}")))
                .containsExactly("biologicalSex:INVALID_VALUE");
    }

    @Test
    void createAndUpdateAreAuditedWithoutClinicalValues() throws Exception {
        String token = tokens.newPatient();
        String patientId = json.readTree(api().postJson(PATIENTS, token, VALID).body()).get("id").stringValue();
        api().mergePatch(ME, token, "\"0\"", "{\"fullName\":\"Outro Nome\",\"heightCm\":170}");

        try (Connection connection = connect("core_owner", CORE_OWNER_PASSWORD);
             PreparedStatement statement = connection.prepareStatement("""
                     select action, outcome, actor_type, metadata::text, user_agent, correlation_id
                     from core.audit_event where patient_id = ?::uuid order by occurred_at, id""")) {
            statement.setString(1, patientId);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("action")).isEqualTo("PATIENT_PROFILE_CREATED");
                assertThat(rs.getString("outcome")).isEqualTo("SUCCESS");
                assertThat(rs.getString("actor_type")).isEqualTo("USER");
                assertThat(rs.getString("user_agent")).isEqualTo("core-api-tests");
                assertThat(rs.getString("correlation_id")).isNotBlank();
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("action")).isEqualTo("PATIENT_PROFILE_UPDATED");
                assertThat(rs.getString(4)).isEqualTo("{\"fields\": \"fullName,heightCm\"}");
                assertThat(rs.next()).isFalse();
            }
        }
    }

    private String problemCode(HttpResponse<String> response) {
        return json.readTree(response.body()).get("code").stringValue();
    }

    private java.util.List<String> problemFields(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(400);
        return json.readTree(response.body()).get("errors").valueStream()
                .map(e -> e.get("field").stringValue() + ":" + e.get("code").stringValue()).toList();
    }
}
