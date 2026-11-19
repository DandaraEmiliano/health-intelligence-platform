package dev.healthintelligence.core.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import dev.healthintelligence.core.support.IntegrationTest;
import dev.healthintelligence.core.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

class IdentityApiTests extends IntegrationTest {

    @Autowired
    TestTokens.Issuer tokens;

    @Autowired
    JsonMapper json;

    @Test
    void firstRequestProvisionsOneAccountEvenUnderConcurrency() throws Exception {
        String subject = UUID.randomUUID().toString();
        String token = tokens.patient(subject, "first@example.test");
        Callable<Integer> call = () -> api().get("/api/v1/consents", token).statusCode();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Integer> statuses = executor.invokeAll(List.of(call, call, call, call, call)).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }).toList();
            assertThat(statuses).containsOnly(200);
        }

        assertThat(accountRow(subject)).isEqualTo("first@example.test|ACTIVE|PATIENT|1");
    }

    @Test
    void emailChangesAtTheIdentityProviderAreSynced() throws Exception {
        String subject = UUID.randomUUID().toString();
        api().get("/api/v1/consents", tokens.patient(subject, "old@example.test"));

        api().get("/api/v1/consents", tokens.patient(subject, "new@example.test"));

        assertThat(accountRow(subject)).startsWith("new@example.test|");
    }

    @Test
    void tokenWithoutEmailIsForbidden() {
        HttpResponse<String> response = api().get("/api/v1/consents", tokens.withoutEmail());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(json.readTree(response.body()).get("code").stringValue()).isEqualTo("ACCOUNT_CLAIMS_MISSING");
    }

    @Test
    void callerWithoutThePatientRoleIsForbiddenWithAProblemBody() {
        HttpResponse<String> response = api().get("/api/v1/patients/me", tokens.withRoles(List.of("ADMIN")));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(json.readTree(response.body()).get("code").stringValue()).isEqualTo("FORBIDDEN");
    }

    @Test
    void tokenForAnotherAudienceIsRejectedWithAProblemBody() {
        HttpResponse<String> response = api().get("/api/v1/patients/me", tokens.forAudience("ai-service"));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(
                value -> assertThat(value).startsWith("Bearer"));
        assertThat(json.readTree(response.body()).get("code").stringValue()).isEqualTo("UNAUTHORIZED");
    }

    private static String accountRow(String subject) throws Exception {
        try (Connection connection = connect("core_owner", CORE_OWNER_PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("""
                     select u.email, u.status, string_agg(r.role, ',') as roles,
                            (select count(*) from core.user_account where idp_subject = '%1$s') as accounts
                     from core.user_account u left join core.user_role r on r.user_id = u.id
                     where u.idp_subject = '%1$s' group by u.id""".formatted(subject))) {
            assertThat(rs.next()).isTrue();
            return rs.getString("email") + "|" + rs.getString("status") + "|" + rs.getString("roles") + "|"
                    + rs.getInt("accounts");
        }
    }
}
