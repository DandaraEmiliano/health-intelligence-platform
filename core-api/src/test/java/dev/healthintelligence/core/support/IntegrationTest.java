package dev.healthintelligence.core.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
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

    protected static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withCommand("redis-server", "--save", "", "--appendonly", "no")
            .withExposedPorts(6379);

    protected static final GenericContainer<?> S3 = new GenericContainer<>(DockerImageName.parse("adobe/s3mock:5.2.3"))
            .withEnv("COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS", "hip-documents-local")
            .withExposedPorts(9090)
            // An HTTP probe can hang: Docker's proxy accepts the connection before Tomcat is up.
            .waitingFor(Wait.forLogMessage(".*Started S3MockApplication.*", 1).withStartupTimeout(java.time.Duration.ofSeconds(90)));

    protected static final GenericContainer<?> SQS = new GenericContainer<>(DockerImageName.parse("softwaremill/elasticmq-native:1.7.1"))
            .withCopyFileToContainer(MountableFile.forHostPath("../infra/docker/elasticmq/elasticmq.conf"), "/opt/elasticmq.conf")
            .withExposedPorts(9324)
            .waitingFor(Wait.forListeningPort());

    static {
        // Stopped by Testcontainers' reaper when the JVM exits.
        Startables.deepStart(POSTGRES, REDIS, S3, SQS).join();
        // The emulators accept any credentials; the SDK's default chain reads these.
        System.setProperty("aws.accessKeyId", "test");
        System.setProperty("aws.secretAccessKey", "test");
    }

    public static String s3Endpoint() {
        return "http://" + S3.getHost() + ":" + S3.getMappedPort(9090);
    }

    public static String sqsEndpoint() {
        return "http://" + SQS.getHost() + ":" + SQS.getMappedPort(9324);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "core_app");
        registry.add("spring.datasource.password", () -> CORE_APP_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "core_owner");
        registry.add("spring.flyway.password", () -> CORE_OWNER_PASSWORD);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("hip.aws.s3-endpoint", IntegrationTest::s3Endpoint);
        registry.add("hip.aws.sqs-endpoint", IntegrationTest::sqsEndpoint);
        registry.add("hip.outbox.relay.fixed-delay", () -> "200ms");
        registry.add("hip.extraction.results.poll-interval", () -> "200ms");
        // Tests run the purge themselves, with no delay, against a recording identity provider.
        registry.add("hip.identity.admin.enabled", () -> "false");
        registry.add("hip.accounts.purge.after", () -> "0s");
        registry.add("hip.accounts.purge.interval", () -> "1h");
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
