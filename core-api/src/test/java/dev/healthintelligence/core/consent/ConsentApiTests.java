package dev.healthintelligence.core.consent;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import dev.healthintelligence.core.support.IntegrationTest;
import dev.healthintelligence.core.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ConsentApiTests extends IntegrationTest {

    static final String CONSENTS = "/api/v1/consents";

    @Autowired
    TestTokens.Issuer tokens;

    @Autowired
    JsonMapper json;

    @Test
    void newUserHasNoConsents() {
        HttpResponse<String> response = api().get(CONSENTS, tokens.newPatient());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).get("items").isEmpty()).isTrue();
    }

    @Test
    void consentsCanBeGivenBeforeTheProfileExistsAndTheLatestRecordWins() {
        String token = tokens.newPatient();

        HttpResponse<String> granted = api().postJson(CONSENTS, token, """
                {"type":"AI_PROCESSING","granted":true,"documentVersion":"ai-2026.1"}""");
        api().postJson(CONSENTS, token, """
                {"type":"TERMS_OF_USE","granted":true,"documentVersion":"2026.1"}""");
        api().postJson(CONSENTS, token, """
                {"type":"AI_PROCESSING","granted":false,"documentVersion":"ai-2026.1"}""");

        assertThat(granted.statusCode()).isEqualTo(201);
        JsonNode state = json.readTree(granted.body());
        assertThat(state.get("type").stringValue()).isEqualTo("AI_PROCESSING");
        assertThat(state.get("granted").booleanValue()).isTrue();
        assertThat(state.get("recordedAt").stringValue()).endsWith("Z");

        JsonNode items = json.readTree(api().get(CONSENTS, token).body()).get("items");
        assertThat(items.valueStream().map(i -> i.get("type").stringValue() + "=" + i.get("granted").booleanValue()))
                .containsExactly("TERMS_OF_USE=true", "AI_PROCESSING=false");
    }

    @Test
    void invalidConsentRequestsAreRejected() {
        String token = tokens.newPatient();

        HttpResponse<String> missing = api().postJson(CONSENTS, token, "{\"type\":\"AI_PROCESSING\"}");
        HttpResponse<String> unknownType = api().postJson(CONSENTS, token, """
                {"type":"MARKETING","granted":true,"documentVersion":"1"}""");

        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(json.readTree(missing.body()).get("errors").valueStream().map(e -> e.get("field").stringValue()))
                .containsExactlyInAnyOrder("granted", "documentVersion");
        assertThat(unknownType.statusCode()).isEqualTo(400);
    }

    @Test
    void everyConsentChangeIsRecordedWithItsOriginAndAudited() throws Exception {
        String subject = java.util.UUID.randomUUID().toString();
        String token = tokens.patient(subject, subject + "@example.test");
        api().postJson(CONSENTS, token, """
                {"type":"PRIVACY_POLICY","granted":true,"documentVersion":"2026.1"}""");

        try (Connection connection = connect("core_owner", CORE_OWNER_PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("""
                     select c.ip_address::text as ip, c.user_agent,
                            (select count(*) from core.audit_event a
                              where a.actor_user_id = u.id and a.action = 'CONSENT_GRANTED') as audits
                     from core.consent_record c join core.user_account u on u.id = c.user_id
                     where u.idp_subject = '%s'""".formatted(subject))) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("ip")).isNotBlank();
            assertThat(rs.getString("user_agent")).isEqualTo("core-api-tests");
            assertThat(rs.getInt("audits")).isEqualTo(1);
        }
    }
}
