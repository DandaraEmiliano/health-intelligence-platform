package dev.healthintelligence.core.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Base for tests that boot the whole application against a real PostgreSQL + pgvector, bootstrapped
 * with the same init script as Docker Compose. One container and one Spring context are shared by
 * every subclass; tests isolate their data by using fresh users.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestTokens.class)
public abstract class IntegrationTest {

    protected static final String CORE_OWNER_PASSWORD = "test-core-owner";
    protected static final String CORE_APP_PASSWORD = "test-core-app";

    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg16").asCompatibleSubstituteFor("postgres"))
            .withEnv("CORE_OWNER_PASSWORD", CORE_OWNER_PASSWORD)
            .withEnv("CORE_APP_PASSWORD", CORE_APP_PASSWORD)
            .withEnv("AI_OWNER_PASSWORD", "test-ai-owner")
            .withEnv("AI_APP_PASSWORD", "test-ai-app")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("../infra/docker/postgres/init/01-roles-and-schemas.sh", 0755),
                    "/docker-entrypoint-initdb.d/01-roles-and-schemas.sh");

    static {
        POSTGRES.start(); // stopped by Testcontainers' reaper when the JVM exits
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "core_app");
        registry.add("spring.datasource.password", () -> CORE_APP_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "core_owner");
        registry.add("spring.flyway.password", () -> CORE_OWNER_PASSWORD);
        // Tokens are verified by TestTokens' key; no identity provider is needed.
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestTokens.ISSUER);
    }

    @Value("${local.server.port}")
    protected int port;

    protected Api api() {
        return new Api(port);
    }

    protected static Connection connect(String user, String password) throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), user, password);
    }
}
