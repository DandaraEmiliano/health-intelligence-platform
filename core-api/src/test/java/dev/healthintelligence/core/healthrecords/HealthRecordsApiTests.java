package dev.healthintelligence.core.healthrecords;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import dev.healthintelligence.core.support.IntegrationTest;
import dev.healthintelligence.core.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class HealthRecordsApiTests extends IntegrationTest {

    static final String BASE = "/api/v1/patients/me/";

    @Autowired
    TestTokens.Issuer tokens;

    @Autowired
    JsonMapper json;

    String token;

    /** Collection, a valid body, a valid replacement, and a field the replacement changes. */
    static Stream<Arguments> kinds() {
        return Stream.of(
                Arguments.of("allergies",
                        "{\"substance\":\"Penicilina\",\"allergyType\":\"MEDICATION\",\"severity\":\"SEVERE\",\"notes\":\" urticaria \"}",
                        "{\"substance\":\"Amendoim\",\"allergyType\":\"FOOD\"}", "substance", "Amendoim"),
                Arguments.of("medications",
                        "{\"name\":\"Metformina\",\"dosageText\":\"500 mg\",\"frequencyText\":\"2x ao dia\",\"startedOn\":\"2024-01-10\"}",
                        "{\"name\":\"Metformina XR\",\"endedOn\":\"2025-02-01\"}", "name", "Metformina XR"),
                Arguments.of("conditions",
                        "{\"description\":\"Hipotireoidismo\",\"status\":\"ACTIVE\",\"onsetDateApprox\":\"2019-06-01\"}",
                        "{\"description\":\"Hipotireoidismo\",\"status\":\"RESOLVED\"}", "status", "RESOLVED"));
    }

    @BeforeEach
    void patientWithProfile() {
        token = tokens.newPatient();
        assertThat(api().postJson("/api/v1/patients", token, "{\"fullName\":\"Ana\",\"birthDate\":\"1990-01-01\"}")
                .statusCode()).isEqualTo(201);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("kinds")
    void fullLifecycle(String collection, String body, String replacement, String field, String expected) {
        HttpResponse<String> created = api().postJson(BASE + collection, token, body);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.headers().firstValue("ETag")).hasValue("\"0\"");
        JsonNode record = json.readTree(created.body());
        String id = record.get("id").stringValue();
        assertThat(created.headers().firstValue("Location")).hasValue(BASE + collection + "/" + id);
        assertThat(record.get("createdAt").stringValue()).endsWith("Z");

        HttpResponse<String> read = api().get(BASE + collection + "/" + id, token);
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(json.readTree(read.body())).isEqualTo(record);

        HttpResponse<String> replaced = api().putJson(BASE + collection + "/" + id, token, "\"0\"", replacement);
        assertThat(replaced.statusCode()).isEqualTo(200);
        assertThat(replaced.headers().firstValue("ETag")).hasValue("\"1\"");
        JsonNode after = json.readTree(replaced.body());
        assertThat(after.get(field).stringValue()).isEqualTo(expected);
        assertThat(after.get("id").stringValue()).isEqualTo(id);
        assertThat(after.get("createdAt")).isEqualTo(record.get("createdAt"));

        JsonNode items = json.readTree(api().get(BASE + collection, token).body()).get("items");
        assertThat(items.size()).isEqualTo(1);
        assertThat(items.get(0)).isEqualTo(after);

        assertThat(api().delete(BASE + collection + "/" + id, token).statusCode()).isEqualTo(204);
        assertThat(api().get(BASE + collection + "/" + id, token).statusCode()).isEqualTo(404);
        assertThat(api().delete(BASE + collection + "/" + id, token).statusCode()).isEqualTo(404);
        assertThat(json.readTree(api().get(BASE + collection, token).body()).get("items").isEmpty()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("kinds")
    void replaceRequiresAMatchingIfMatch(String collection, String body, String replacement) {
        String id = json.readTree(api().postJson(BASE + collection, token, body).body()).get("id").stringValue();

        assertThat(api().putJson(BASE + collection + "/" + id, token, null, replacement).statusCode()).isEqualTo(428);
        assertThat(api().putJson(BASE + collection + "/" + id, token, "\"7\"", replacement).statusCode()).isEqualTo(412);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("kinds")
    void requireTheProfileFirst(String collection, String body) {
        String withoutProfile = tokens.newPatient();

        HttpResponse<String> list = api().get(BASE + collection, withoutProfile);
        HttpResponse<String> create = api().postJson(BASE + collection, withoutProfile, body);

        assertThat(list.statusCode()).isEqualTo(404);
        assertThat(json.readTree(list.body()).get("code").stringValue()).isEqualTo("PATIENT_NOT_FOUND");
        assertThat(create.statusCode()).isEqualTo(404);
    }

    @Test
    void listIsOrderedByCreationAndOptionalTextIsTrimmed() {
        api().postJson(BASE + "allergies", token, "{\"substance\":\" Dipirona \",\"allergyType\":\"MEDICATION\",\"notes\":\"   \"}");
        api().postJson(BASE + "allergies", token, "{\"substance\":\"Poeira\",\"allergyType\":\"ENVIRONMENTAL\"}");

        JsonNode items = json.readTree(api().get(BASE + "allergies", token).body()).get("items");

        assertThat(items.valueStream().map(i -> i.get("substance").stringValue())).containsExactly("Dipirona", "Poeira");
        assertThat(items.get(0).has("notes")).isFalse();
        assertThat(items.get(0).has("severity")).isFalse();
    }

    @Test
    void invalidRecordsListEveryProblem() {
        assertThat(fields(api().postJson(BASE + "allergies", token, "{\"substance\":\"\"}")))
                .containsExactlyInAnyOrder("substance:REQUIRED", "allergyType:REQUIRED");
        assertThat(fields(api().postJson(BASE + "medications", token,
                "{\"name\":\"X\",\"startedOn\":\"2024-05-01\",\"endedOn\":\"2024-04-01\"}")))
                .containsExactly("endedOn:INVALID_PERIOD");
        assertThat(fields(api().postJson(BASE + "medications", token, "{\"name\":\"X\",\"startedOn\":\"2999-01-01\"}")))
                .containsExactly("startedOn:FUTURE_DATE_NOT_ALLOWED");
        // Shape rules (Bean Validation) are reported together first; date rules run once the shape is valid.
        assertThat(fields(api().postJson(BASE + "conditions", token,
                "{\"description\":\"" + "a".repeat(501) + "\",\"onsetDateApprox\":\"1850-01-01\"}")))
                .containsExactlyInAnyOrder("description:INVALID_LENGTH", "status:REQUIRED");
        assertThat(fields(api().postJson(BASE + "conditions", token,
                "{\"description\":\"Asma\",\"status\":\"ACTIVE\",\"onsetDateApprox\":\"1850-01-01\"}")))
                .containsExactly("onsetDateApprox:OUT_OF_RANGE");
        assertThat(api().postJson(BASE + "conditions", token, "{\"description\":\"X\",\"status\":\"CURED\"}").statusCode())
                .isEqualTo(400);
    }

    @Test
    void lifecycleIsAuditedWithoutClinicalValues() throws Exception {
        JsonNode created = json.readTree(api().postJson(BASE + "medications", token,
                "{\"name\":\"Losartana\",\"dosageText\":\"50 mg\"}").body());
        String id = created.get("id").stringValue();
        api().putJson(BASE + "medications/" + id, token, "\"0\"", "{\"name\":\"Losartana\",\"dosageText\":\"100 mg\"}");
        api().delete(BASE + "medications/" + id, token);

        List<String> rows = new ArrayList<>();
        try (Connection connection = connect("core_owner", CORE_OWNER_PASSWORD);
             PreparedStatement statement = connection.prepareStatement("""
                     select action, actor_user_id is not null as has_actor, patient_id is not null as has_patient,
                            metadata::text as metadata
                     from core.audit_event where resource_id = ?::uuid order by occurred_at, id""")) {
            statement.setString(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows.add(rs.getString("action") + "|" + rs.getBoolean("has_actor") + "|"
                            + rs.getBoolean("has_patient") + "|" + rs.getString("metadata"));
                }
            }
        }
        assertThat(rows).containsExactly(
                "MEDICATION_CREATED|true|true|{}", "MEDICATION_UPDATED|true|true|{}", "MEDICATION_DELETED|true|true|{}");
    }

    private List<String> fields(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(400);
        return json.readTree(response.body()).get("errors").valueStream()
                .map(e -> e.get("field").stringValue() + ":" + e.get("code").stringValue()).toList();
    }
}
