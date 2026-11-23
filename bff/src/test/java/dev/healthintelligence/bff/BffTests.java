package dev.healthintelligence.bff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** ADR 0014: no session, no API; tokens are relayed, never exposed; unsafe calls need the CSRF token. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class BffTests {

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    /** Stand-in for core-api: records what it receives, answers like the real one would. */
    record Received(String method, String path, Map<String, List<String>> headers, String body) {
    }

    static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
    static final HttpServer CORE_API = start();

    static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                RECEIVED.add(new Received(exchange.getRequestMethod(),
                        exchange.getRequestURI().toString(), Map.copyOf(exchange.getRequestHeaders()), body));
                byte[] answer = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.getResponseHeaders().add("ETag", "\"3\"");
                exchange.getResponseHeaders().add("Retry-After", "30");
                exchange.getResponseHeaders().add("X-Internal", "never-forwarded");
                exchange.sendResponseHeaders(exchange.getRequestMethod().equals("POST") ? 201 : 200, answer.length);
                exchange.getResponseBody().write(answer);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("hip.core-api.base-url", () -> "http://127.0.0.1:" + CORE_API.getAddress().getPort());
        registry.add("hip.oidc.client-secret", () -> "test-secret");
        registry.add("hip.oidc.issuer", () -> "http://idp.test/realms/hip");
        registry.add("hip.oidc.public-base", () -> "http://idp.test");
        registry.add("hip.oidc.internal-base", () -> "http://idp.internal");
    }

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void clear() {
        RECEIVED.clear();
    }

    @Test
    void withoutASessionTheApiAnswers401AndNothingIsRelayed() throws Exception {
        mvc.perform(get("/api/v1/patients/me").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"));
        mvc.perform(get("/bff/user")).andExpect(status().isUnauthorized());

        assertThat(RECEIVED).isEmpty();
    }

    @Test
    void aSignedInRequestIsRelayedWithABearerTokenAndNoCookies() throws Exception {
        mvc.perform(get("/api/v1/laboratory-reports?limit=5").with(oidcLogin())
                        .header("Accept", "application/json")
                        .header("If-None-Match", "\"2\"")
                        .header("Cookie", "__Host-hip-session=abc; XSRF-TOKEN=xyz"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""))
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(header().doesNotExist("X-Internal"))
                .andExpect(jsonPath("$.ok").value(true));

        Received received = RECEIVED.getFirst();
        assertThat(received.method()).isEqualTo("GET");
        assertThat(received.path()).isEqualTo("/api/v1/laboratory-reports?limit=5");
        assertThat(received.headers().get("Authorization")).containsExactly("Bearer access-token");
        assertThat(received.headers().get("If-none-match")).containsExactly("\"2\"");
        assertThat(received.headers()).doesNotContainKey("Cookie");
    }

    @Test
    void unsafeMethodsNeedTheCsrfTokenAndKeepTheirBody() throws Exception {
        String question = "{\"content\":\"Como está minha glicose?\"}";

        mvc.perform(post("/api/v1/ai/conversations/c-1/messages").with(oidcLogin())
                        .contentType(MediaType.APPLICATION_JSON).content(question))
                .andExpect(status().isForbidden());
        assertThat(RECEIVED).isEmpty();

        mvc.perform(post("/api/v1/ai/conversations/c-1/messages").with(oidcLogin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(question)
                        .header("Idempotency-Key", "0193a1f0-0000-7000-8000-000000000001"))
                .andExpect(status().isCreated());

        Received received = RECEIVED.getFirst();
        assertThat(received.body()).isEqualTo(question);
        assertThat(received.headers().get("Idempotency-key")).containsExactly("0193a1f0-0000-7000-8000-000000000001");
        assertThat(received.headers().get("Content-type").getFirst()).startsWith("application/json");
    }

    @Test
    void theSpaLearnsWhoIsSignedInButNeverSeesAToken() throws Exception {
        OidcIdToken idToken = OidcIdToken.withTokenValue("id-token").claim("sub", "u-1")
                .claim("email", "patient.a@example.test").claim("name", "Ana Sintética").build();
        MvcResult result = mvc.perform(get("/bff/user")
                        .with(oidcLogin().oidcUser(new DefaultOidcUser(List.of(), idToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("patient.a@example.test"))
                .andExpect(jsonPath("$.name").value("Ana Sintética"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("id-token").doesNotContain("access-token");
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
    }

    @Test
    void signInStartsAuthorizationCodeWithPkceAndAnHttpOnlySessionCookie() throws Exception {
        MvcResult result = mvc.perform(get("/oauth2/authorization/keycloak"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        assertThat(location).startsWith("http://idp.test/realms/hip/protocol/openid-connect/auth").contains("response_type=code")
                .contains("client_id=hip-bff").contains("code_challenge=").contains("code_challenge_method=S256");
        String cookie = String.join(";", result.getResponse().getHeaders("Set-Cookie"));
        assertThat(cookie).contains("__Host-hip-session=").contains("HttpOnly").contains("Secure")
                .contains("SameSite=Lax").contains("Path=/");
    }

    @Test
    void createAccountAsksKeycloakForItsRegistrationPage() throws Exception {
        String plain = mvc.perform(get("/oauth2/authorization/keycloak")).andReturn().getResponse().getHeader("Location");
        String create = mvc.perform(get("/oauth2/authorization/keycloak?prompt=create")).andReturn().getResponse().getHeader("Location");

        assertThat(plain).doesNotContain("prompt=");
        assertThat(create).contains("prompt=create").contains("code_challenge_method=S256");
    }

    @Test
    void logoutEndsTheKeycloakSessionToo() throws Exception {
        OidcIdToken idToken = OidcIdToken.withTokenValue("id-token-value").claim("sub", "u-1").build();

        MvcResult result = mvc.perform(post("/logout").with(csrf())
                        .with(oidcLogin().oidcUser(new DefaultOidcUser(List.of(), idToken))))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat(result.getResponse().getHeader("Location")).startsWith("http://idp.test/realms/hip/protocol/openid-connect/logout")
                .contains("id_token_hint=id-token-value").contains("post_logout_redirect_uri=http://localhost/");
    }

    @Test
    void theSpaLogsOutWithTheCsrfHeaderAndGetsTheKeycloakUrl() throws Exception {
        OidcIdToken idToken = OidcIdToken.withTokenValue("id-token-value").claim("sub", "u-1").build();

        mvc.perform(post("/logout").accept(MediaType.APPLICATION_JSON)
                        .with(oidcLogin().oidcUser(new DefaultOidcUser(List.of(), idToken))))
                .andExpect(status().isForbidden());
        // As the browser does: the token comes in a readable cookie and goes back in a header.
        jakarta.servlet.http.Cookie xsrf = mvc.perform(get("/bff/user")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrf).isNotNull();
        assertThat(xsrf.isHttpOnly()).isFalse();
        mvc.perform(post("/logout").accept(MediaType.APPLICATION_JSON).cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
                        .with(oidcLogin().oidcUser(new DefaultOidcUser(List.of(), idToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redirectUrl").value(org.hamcrest.Matchers.startsWith(
                        "http://idp.test/realms/hip/protocol/openid-connect/logout?client_id=hip-bff")))
                .andExpect(jsonPath("$.redirectUrl").value(org.hamcrest.Matchers.containsString("id_token_hint=id-token-value")));
    }

    @Test
    void nothingElseIsServed() throws Exception {
        mvc.perform(get("/actuator/env").with(oidcLogin())).andExpect(status().isForbidden());
        mvc.perform(get("/admin").with(oidcLogin())).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
