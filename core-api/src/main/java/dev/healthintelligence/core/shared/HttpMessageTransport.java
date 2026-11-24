package dev.healthintelligence.core.shared;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Outbox messages as {@code POST {base-url}/internal/v1/queues/{destination}} with the internal
 * token, for deployments without a queue service (the Render profile). The consumer answers 202
 * once it has taken the message; anything else leaves it in the outbox. The timeout is long because
 * a free instance may be waking up.
 */
@Component
@ConditionalOnProperty(name = "hip.messaging.transport", havingValue = "http")
class HttpMessageTransport implements MessageTransport {

    private final HttpClient http;
    private final String baseUrl;
    private final String token;
    private final Duration timeout;

    HttpMessageTransport(@Value("${hip.messaging.http.base-url}") String baseUrl,
                         @Value("${hip.internal.token:}") String token,
                         @Value("${hip.messaging.http.timeout:120s}") Duration timeout) {
        if (token.length() < 32) {
            throw new IllegalStateException("hip.internal.token must have at least 32 characters");
        }
        // HTTP/1.1: the JDK client otherwise asks for an h2c upgrade, which uvicorn rejects with 400.
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build();
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.token = token;
        this.timeout = timeout;
    }

    @Override
    public void send(String destination, String eventType, String messageId, String payload) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/v1/queues/" + destination))
                .timeout(timeout)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("X-Event-Type", eventType)
                .header("X-Message-Id", messageId)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<Void> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("delivery failed: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("delivery interrupted", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("delivery refused with status " + response.statusCode());
        }
    }
}
