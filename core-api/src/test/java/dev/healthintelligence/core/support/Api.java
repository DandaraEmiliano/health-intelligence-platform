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

    public HttpResponse<String> putJson(String path, String token, String ifMatch, String json) {
        HttpRequest.Builder builder = builder(path, token).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json));
        if (ifMatch != null) {
            builder.header("If-Match", ifMatch);
        }
        return send(builder);
    }

    /** multipart/form-data with one part named {@code file}. Extra headers come in name/value pairs. */
    public HttpResponse<String> upload(String path, String token, String filename, byte[] content, String... headers) {
        String boundary = "----hip" + java.util.UUID.randomUUID();
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: application/pdf\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        body.writeBytes(head);
        body.writeBytes(content);
        body.writeBytes(tail);
        HttpRequest.Builder builder = builder(path, token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return send(builder);
    }

    public HttpResponse<String> get(String path, String token, String accept) {
        return send(builder(path, token).header("Accept", accept).GET());
    }

    public HttpResponse<String> delete(String path, String token) {
        return send(builder(path, token).DELETE());
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
