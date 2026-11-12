package dev.healthintelligence.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Boots the whole application against a real PostgreSQL + pgvector, bootstrapped with the same
 * init script as Docker Compose, and checks the Phase 3 acceptance criteria.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CoreApplicationIntegrationTests {

    private static final String CORE_OWNER_PASSWORD = "test-core-owner";
    private static final String CORE_APP_PASSWORD = "test-core-app";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg16").asCompatibleSubstituteFor("postgres"))
            .withEnv("CORE_OWNER_PASSWORD", CORE_OWNER_PASSWORD)
            .withEnv("CORE_APP_PASSWORD", CORE_APP_PASSWORD)
            .withEnv("AI_OWNER_PASSWORD", "test-ai-owner")
            .withEnv("AI_APP_PASSWORD", "test-ai-app")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("../infra/docker/postgres/init/01-roles-and-schemas.sh", 0755),
                    "/docker-entrypoint-initdb.d/01-roles-and-schemas.sh");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "core_app");
        registry.add("spring.datasource.password", () -> CORE_APP_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "core_owner");
        registry.add("spring.flyway.password", () -> CORE_OWNER_PASSWORD);
        // The JWT decoder resolves the issuer lazily; no identity provider is needed to boot.
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "http://localhost:1/realms/hip");
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void readinessProbeIsUp() throws Exception {
        HttpResponse<String> response = get("/actuator/health/readiness");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void livenessProbeIsUp() throws Exception {
        assertThat(get("/actuator/health/liveness").statusCode()).isEqualTo(200);
    }

    @Test
    void apiRequiresAuthentication() throws Exception {
        HttpResponse<String> response = get("/api/v1/patients/me");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(
                value -> assertThat(value).startsWith("Bearer"));
    }

    @Test
    void corsPreflightAllowsTheWebClientOrigin() throws Exception {
        HttpResponse<String> response = preflight("http://localhost:5173");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).hasValue("http://localhost:5173");
        assertThat(response.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
    }

    @Test
    void corsPreflightRejectsUnknownOrigins() throws Exception {
        HttpResponse<String> response = preflight("https://evil.example");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void prometheusMetricsAreExposed() throws Exception {
        HttpResponse<String> response = get("/actuator/prometheus");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("jvm_memory_used_bytes");
    }

    @Test
    void flywayAppliedV1AsSchemaOwner() throws SQLException {
        try (Connection connection = connect("core_owner", CORE_OWNER_PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "select version, success, installed_by from core.flyway_schema_history where version = '1'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBoolean("success")).isTrue();
            assertThat(rs.getString("installed_by")).isEqualTo("core_owner");
        }
    }

    @Test
    void appRoleCanReadAndWriteClinicalTables() throws SQLException {
        try (Connection connection = connect("core_app", CORE_APP_PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("select count(*) from core.patient")) {
            assertThat(rs.next()).isTrue();
        }
    }

    @Test
    void appRoleCannotRewriteAuditTrail() throws SQLException {
        try (Connection connection = connect("core_app", CORE_APP_PASSWORD);
             Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate("update core.audit_event set outcome = 'SUCCESS'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> statement.executeUpdate("delete from core.consent_record"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
        }
    }

    @Test
    void appRoleCannotReadAiSchema() throws SQLException {
        try (Connection connection = connect("core_app", CORE_APP_PASSWORD);
             Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeQuery("select 1 from ai.document_chunk"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied for schema ai");
            try (ResultSet rs = statement.executeQuery("select has_schema_privilege('ai', 'USAGE')")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1)).isFalse();
            }
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> preflight(String origin) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/patients/me"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization")
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Connection connect(String user, String password) throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), user, password);
    }
}
