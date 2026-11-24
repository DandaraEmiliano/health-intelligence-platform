package dev.healthintelligence.core.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.healthintelligence.core.support.IntegrationTest;
import dev.healthintelligence.core.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** The built-in sign-in of the Render profile (hip.auth.mode=local): ADR 0014's guarantees without a BFF. */
class LocalSignInTests extends IntegrationTest {

    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String PASSWORD = "uma senha longa 123";

    @DynamicPropertySource
    static void localSignIn(DynamicPropertyRegistry registry) {
        registry.add("hip.auth.mode", () -> "local");
    }

    @Autowired JsonMapper json;
    @Autowired LocalIdentityProviderUsers identityProvider;
    @Autowired TestTokens.Issuer tokens;

    /** A browser: keeps cookies (even Secure ones, over plain HTTP in tests) and echoes the CSRF token. */
    final class Browser {
        final Map<String, String> cookies = new LinkedHashMap<>();
        final List<String> setCookies = new java.util.ArrayList<>();

        HttpResponse<String> send(String method, String path, String body, boolean csrf) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Accept", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            if (body != null) {
                request.header("Content-Type", "application/json");
            }
            if (!cookies.isEmpty()) {
                request.header("Cookie", String.join("; ", cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList()));
            }
            if (csrf && cookies.containsKey("XSRF-TOKEN")) {
                request.header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"));
            }
            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
            for (String header : response.headers().allValues("Set-Cookie")) {
                setCookies.add(header);
                String pair = header.split(";", 2)[0];
                String name = pair.substring(0, pair.indexOf('='));
                String value = pair.substring(pair.indexOf('=') + 1);
                if (value.isEmpty() || header.contains("Max-Age=0")) {
                    cookies.remove(name);
                } else {
                    cookies.put(name, value);
                }
            }
            return response;
        }

        HttpResponse<String> get(String path) throws Exception {
            return send("GET", path, null, false);
        }

        HttpResponse<String> post(String path, String body) throws Exception {
            return send("POST", path, body, true);
        }
    }

    static String email() {
        return "paciente." + UUID.randomUUID() + "@example.test";
    }

    static String credentials(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    @Test
    void anAccountIsCreatedSignedInAndUsesTheApiWithTheSessionCookieAndCsrf() throws Exception {
        Browser browser = new Browser();
        assertThat(browser.get("/bff/user").statusCode()).isEqualTo(401);
        assertThat(browser.cookies).containsKey("XSRF-TOKEN");
        String email = email();

        // Without the CSRF header, nothing that changes state goes through.
        assertThat(browser.send("POST", "/bff/register", credentials(email, PASSWORD), false).statusCode()).isEqualTo(403);
        HttpResponse<String> created = browser.post("/bff/register", credentials(email.toUpperCase(), PASSWORD));
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(json.readTree(created.body()).get("email").stringValue()).isEqualTo(email);
        assertThat(created.body()).doesNotContain(PASSWORD);

        String session = browser.setCookies.stream().filter(c -> c.startsWith("__Host-hip-session=")).findFirst().orElseThrow();
        assertThat(session).contains("HttpOnly").contains("Secure").contains("SameSite=Lax").contains("Path=/");
        assertThat(json.readTree(browser.get("/bff/user").body()).get("email").stringValue()).isEqualTo(email);

        assertThat(browser.send("POST", "/api/v1/consents",
                "{\"type\":\"TERMS_OF_USE\",\"granted\":true,\"documentVersion\":\"terms-2026.1\"}", false).statusCode()).isEqualTo(403);
        assertThat(browser.post("/api/v1/consents",
                "{\"type\":\"TERMS_OF_USE\",\"granted\":true,\"documentVersion\":\"terms-2026.1\"}").statusCode()).isEqualTo(201);
        assertThat(browser.get("/api/v1/consents").statusCode()).isEqualTo(200);
        assertThat(single("select idp_subject from core.user_account where email = ?", email)).startsWith("local:");

        HttpResponse<String> logout = browser.post("/logout", null);
        assertThat(logout.statusCode()).isEqualTo(200);
        assertThat(json.readTree(logout.body()).get("redirectUrl").stringValue()).isEqualTo("/");
        assertThat(browser.get("/bff/user").statusCode()).isEqualTo(401);
        assertThat(browser.get("/api/v1/consents").statusCode()).isEqualTo(401);
    }

    @Test
    void signInChecksThePasswordAndSaysTheSameForAnUnknownEmail() throws Exception {
        String email = email();
        Browser first = new Browser();
        first.get("/bff/user");
        first.post("/bff/register", credentials(email, PASSWORD));

        Browser browser = new Browser();
        browser.get("/bff/user");
        HttpResponse<String> wrong = browser.post("/bff/login", credentials(email, "outra senha qualquer"));
        HttpResponse<String> unknown = browser.post("/bff/login", credentials(email(), PASSWORD));
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(unknown.statusCode()).isEqualTo(401);
        assertThat(json.readTree(wrong.body()).get("code").stringValue()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(json.readTree(unknown.body()).get("code").stringValue()).isEqualTo("INVALID_CREDENTIALS");

        String before = browser.cookies.get("__Host-hip-session");
        assertThat(browser.post("/bff/login", credentials(email, PASSWORD)).statusCode()).isEqualTo(200);
        assertThat(browser.cookies.get("__Host-hip-session")).isNotNull().isNotEqualTo(before);
        assertThat(browser.get("/bff/user").statusCode()).isEqualTo(200);
    }

    @Test
    void afterFiveFailuresEvenTheRightPasswordWaits() throws Exception {
        String email = email();
        Browser browser = new Browser();
        browser.get("/bff/user");
        browser.post("/bff/register", credentials(email, PASSWORD));
        browser.post("/logout", null);
        browser.get("/bff/user");

        for (int i = 0; i < SignInThrottle.MAX_FAILURES; i++) {
            assertThat(browser.post("/bff/login", credentials(email, "senha errada " + i)).statusCode()).isEqualTo(401);
        }
        HttpResponse<String> limited = browser.post("/bff/login", credentials(email, PASSWORD));
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow())).isBetween(1L, 900L);
    }

    @Test
    void registrationRulesAndOneAccountPerEmail() throws Exception {
        Browser browser = new Browser();
        browser.get("/bff/user");
        String email = email();

        assertThat(browser.post("/bff/register", credentials(email, "curta")).statusCode()).isEqualTo(400);
        assertThat(browser.post("/bff/register", credentials("sem-arroba", PASSWORD)).statusCode()).isEqualTo(400);
        String local = email.substring(0, email.indexOf('@'));
        assertThat(browser.post("/bff/register", credentials(email, local + "123")).statusCode()).isEqualTo(400);
        assertThat(browser.post("/bff/register", credentials(email, PASSWORD)).statusCode()).isEqualTo(201);
        Browser other = new Browser();
        other.get("/bff/user");
        HttpResponse<String> taken = other.post("/bff/register", credentials(email, PASSWORD + "x"));
        assertThat(taken.statusCode()).isEqualTo(409);
        assertThat(json.readTree(taken.body()).get("code").stringValue()).isEqualTo("EMAIL_ALREADY_REGISTERED");
    }

    @Test
    void aDeletedAccountCannotSignInAgain() throws Exception {
        String email = email();
        Browser browser = new Browser();
        browser.get("/bff/user");
        browser.post("/bff/register", credentials(email, PASSWORD));
        browser.get("/api/v1/consents"); // provisions the account
        String subject = single("select idp_subject from core.user_account where email = ?", email);

        identityProvider.delete(subject);
        identityProvider.delete(subject); // idempotent

        Browser again = new Browser();
        again.get("/bff/user");
        assertThat(again.post("/bff/login", credentials(email, PASSWORD)).statusCode()).isEqualTo(401);
    }

    @Test
    void bearerTokensAndInternalPathsOpenNothing() throws Exception {
        String token = tokens.newPatient();
        assertThat(api().get("/api/v1/consents", token).statusCode()).isEqualTo(401);
        Browser browser = new Browser();
        assertThat(browser.get("/internal/v1/document-objects/" + UUID.randomUUID()).statusCode()).isIn(401, 403);
        assertThat(browser.get("/actuator/env").statusCode()).isIn(401, 403);
        assertThat(browser.get("/actuator/health").statusCode()).isEqualTo(200);
    }

    private static String single(String sql, String parameter) throws Exception {
        try (var connection = connect("core_owner", CORE_OWNER_PASSWORD); var statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }
}
