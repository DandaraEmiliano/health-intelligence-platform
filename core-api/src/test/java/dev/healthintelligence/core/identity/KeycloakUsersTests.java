package dev.healthintelligence.core.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Talks to a local stand-in for Keycloak's token endpoint and Admin REST API. */
class KeycloakUsersTests {

    HttpServer server;
    final List<String> requests = new CopyOnWriteArrayList<>();
    volatile int deleteStatus = 204;
    volatile int tokenStatus = 200;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/realms/hip/protocol/openid-connect/token", exchange -> {
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add("TOKEN " + form);
            respond(exchange, tokenStatus, "{\"access_token\":\"admin-token\"}");
        });
        server.createContext("/admin/realms/hip/users/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " "
                    + exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, deleteStatus, "");
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    KeycloakUsers client() {
        return new KeycloakUsers("http://127.0.0.1:" + server.getAddress().getPort(), "hip", "core-api-admin", "s3cr3t");
    }

    @Test
    void deletesTheUserWithAClientCredentialsToken() {
        client().delete("b4f27910-2d88-429e-bd7a-2abdfe1670cf");

        assertThat(requests).hasSize(2);
        assertThat(requests.get(0)).contains("grant_type=client_credentials", "client_id=core-api-admin", "client_secret=s3cr3t");
        assertThat(requests.get(1)).isEqualTo("DELETE /admin/realms/hip/users/b4f27910-2d88-429e-bd7a-2abdfe1670cf Bearer admin-token");
    }

    @Test
    void aUserThatIsAlreadyGoneCountsAsDeleted() {
        deleteStatus = 404;

        client().delete("b4f27910-2d88-429e-bd7a-2abdfe1670cf");
    }

    @Test
    void rejectedCredentialsFailSoThePurgeIsRetried() {
        tokenStatus = 401;

        assertThatThrownBy(() -> client().delete("b4f27910-2d88-429e-bd7a-2abdfe1670cf"))
                .hasMessageContaining("credentials rejected");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
