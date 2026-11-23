package dev.healthintelligence.bff;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Relays {@code /api/**} to core-api unchanged, adding only {@code Authorization: Bearer} (and the
 * client address for the audit trail). Bodies are streamed both ways, so uploads and downloads are
 * not buffered. core-api still authorizes every request itself (ADR 0011).
 */
@RestController
class ApiProxy {

    private static final Logger log = LoggerFactory.getLogger(ApiProxy.class);

    /** Request headers core-api needs; everything else (cookies above all) stays here. */
    static final List<String> REQUEST_HEADERS = List.of("Accept", "Accept-Language", "Content-Type", "If-Match",
            "If-None-Match", "Idempotency-Key", "User-Agent");

    /** Response headers the SPA reads (ADR 0009). */
    static final List<String> RESPONSE_HEADERS = List.of("Content-Type", "ETag", "Location", "Retry-After",
            "Cache-Control", "Content-Disposition", "Idempotent-Replayed");

    static final Set<String> BODYLESS = Set.of("GET", "HEAD", "DELETE", "OPTIONS");

    private final OAuth2AuthorizedClientManager clients;
    private final HttpClient http;
    private final URI coreApi;
    private final Duration timeout;

    ApiProxy(OAuth2AuthorizedClientManager clients,
             @Value("${hip.core-api.base-url}") URI coreApi,
             @Value("${hip.core-api.timeout}") Duration timeout) {
        this.clients = clients;
        this.coreApi = coreApi;
        this.timeout = timeout;
        // HTTP/1.1: the JDK client otherwise asks for an h2c upgrade on plain HTTP.
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @RequestMapping("/api/**")
    void relay(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException, InterruptedException {
        String token = accessToken(request, response, authentication);
        if (token == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        String query = request.getQueryString();
        URI target = coreApi.resolve(request.getRequestURI() + (query == null ? "" : "?" + query));

        HttpRequest.Builder outbound = HttpRequest.newBuilder(target).timeout(timeout)
                .header("Authorization", "Bearer " + token)
                .header("X-Forwarded-For", request.getRemoteAddr());
        for (String name : REQUEST_HEADERS) {
            String value = request.getHeader(name);
            if (value != null) {
                outbound.header(name, value);
            }
        }
        HttpRequest.BodyPublisher body = BODYLESS.contains(method)
                ? HttpRequest.BodyPublishers.noBody()
                : request.getContentLengthLong() >= 0
                        ? HttpRequest.BodyPublishers.fromPublisher(
                                HttpRequest.BodyPublishers.ofInputStream(() -> stream(request)), request.getContentLengthLong())
                        : HttpRequest.BodyPublishers.ofInputStream(() -> stream(request));
        outbound.method(method, body);

        HttpResponse<InputStream> upstream;
        try {
            upstream = http.send(outbound.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            log.warn("core-api unreachable: {}", e.getClass().getSimpleName());
            response.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
            return;
        }
        response.setStatus(upstream.statusCode());
        for (String name : RESPONSE_HEADERS) {
            upstream.headers().firstValue(name).ifPresent(value -> response.setHeader(name, value));
        }
        try (InputStream in = upstream.body(); OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
        }
    }

    /** A fresh access token from the session, refreshed when needed; null when the session can no longer get one. */
    private String accessToken(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        try {
            OAuth2AuthorizedClient client = clients.authorize(OAuth2AuthorizeRequest
                    .withClientRegistrationId(SecurityConfiguration.REGISTRATION)
                    .principal(authentication)
                    .attribute(HttpServletRequest.class.getName(), request)
                    .attribute(HttpServletResponse.class.getName(), response)
                    .build());
            return client == null ? null : client.getAccessToken().getTokenValue();
        } catch (ClientAuthorizationException expired) {
            // Refresh token expired or revoked at Keycloak: the user signs in again.
            request.getSession().invalidate();
            return null;
        }
    }

    private static InputStream stream(HttpServletRequest request) {
        try {
            return request.getInputStream();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
