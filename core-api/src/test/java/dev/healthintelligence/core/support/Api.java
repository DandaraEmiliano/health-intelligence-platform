package dev.healthintelligence.core.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Minimal HTTP client against the running application. */
public final class Api {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final int port;

    Api(int port) {
        this.port = port;
    }

    public HttpResponse<String> get(String path, String token) {
        return send(builder(path, token).GET());
    }

    public HttpResponse<String> postJson(String path, String token, String json) {
        return send(builder(path, token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    public HttpResponse<String> mergePatch(String path, String token, String ifMatch, String json) {
        HttpRequest.Builder builder = builder(path, token).header("Content-Type", "application/merge-patch+json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json));
        if (ifMatch != null) {
            builder.header("If-Match", ifMatch);
        }
        return send(builder);
    }

    private HttpRequest.Builder builder(String path, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("User-Agent", "core-api-tests");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    private static HttpResponse<String> send(HttpRequest.Builder builder) {
        try {
            return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
