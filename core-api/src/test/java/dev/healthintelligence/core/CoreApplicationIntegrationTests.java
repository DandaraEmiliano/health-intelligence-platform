package dev.healthintelligence.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import dev.healthintelligence.core.support.IntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

/** Platform acceptance criteria from Phase 3: probes, auth, CORS, telemetry, database roles. */
class CoreApplicationIntegrationTests extends IntegrationTest {

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
    void apiResponsesCarrySecurityHeaders() throws Exception {
        HttpResponse<String> response = get("/api/v1/patients/me");

        assertThat(response.headers().firstValue("Content-Security-Policy")).hasValueSatisfying(
                value -> assertThat(value).contains("default-src 'none'").contains("frame-ancestors 'none'"));
        assertThat(response.headers().firstValue("Referrer-Policy")).hasValue("no-referrer");
        assertThat(response.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(response.headers().firstValue("X-Frame-Options")).hasValue("DENY");
        assertThat(get("/actuator/health/readiness").headers().firstValue("Content-Security-Policy")).isEmpty();
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
        get("/actuator/health/readiness"); // ensure at least one recorded HTTP request

        HttpResponse<String> response = get("/actuator/prometheus");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("jvm_memory_used_bytes")
                .contains("service=\"core-api\"")
                .contains("http_server_requests_seconds_bucket");
    }

    @Test
    void tracingIsActive(@Autowired Tracer tracer) {
        Span span = tracer.nextSpan().name("synthetic-test-span").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            assertThat(tracer.currentSpan()).isNotNull();
            assertThat(span.context().traceId()).matches("[0-9a-f]{32}");
        } finally {
            span.end();
        }
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

}
