package org.aethercode.code.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Remote agent HTTP client.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.client.remote_client} module. The Java
 * port uses {@link java.net.http.HttpClient} for synchronous and
 * asynchronous requests to a remote agent endpoint.</p>
 */
public final class RemoteClient implements AutoCloseable {
    private final URI baseUri;
    private final HttpClient http;
    private final String authHeader;

    private RemoteClient(URI baseUri, HttpClient http, String authHeader) {
        this.baseUri = baseUri;
        this.http = http;
        this.authHeader = authHeader;
    }

    /**
     * Build a remote client.
     */
    public static RemoteClient create(String baseUrl, String bearerToken, Duration timeout) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        URI uri = URI.create(baseUrl);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(timeout != null ? timeout : Duration.ofSeconds(15))
                .build();
        return new RemoteClient(uri, client, bearerToken);
    }

    /**
     * Send a synchronous POST to a named endpoint.
     */
    public String post(String path, Map<String, Object> body) throws IOException, InterruptedException {
        HttpRequest request = buildRequest(path, body);
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("POST " + path + " failed: " + response.statusCode() + " "
                    + response.body());
        }
        return response.body();
    }

    /**
     * Asynchronous variant of {@link #post(String, Map)}.
     */
    public CompletableFuture<String> postAsync(String path, Map<String, Object> body) {
        HttpRequest request = buildRequest(path, body);
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() / 100 != 2) {
                        throw new RuntimeException("POST " + path + " failed: "
                                + response.statusCode() + " " + response.body());
                    }
                    return response.body();
                });
    }

    /**
     * Send a synchronous GET.
     */
    public String get(String path) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .header("Accept", "application/json");
        if (authHeader != null) builder.header("Authorization", authHeader);
        HttpResponse<String> response = http.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("GET " + path + " failed: " + response.statusCode());
        }
        return response.body();
    }

    private HttpRequest buildRequest(String path, Map<String, Object> body) {
        String json = body == null ? "{}" : MiniJson.writeObject(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        if (authHeader != null) builder.header("Authorization", authHeader);
        return builder.build();
    }

    @Override
    public void close() { /* HttpClient has no close */ }
}
