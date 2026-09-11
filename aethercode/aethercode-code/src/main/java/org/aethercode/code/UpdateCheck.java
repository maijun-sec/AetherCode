package org.aethercode.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Update check against the upstream registry.
 *
 * <p>Java-native port of the Python {@code deepagents_code.update_check}
 * module. The Java port uses {@link java.net.http.HttpClient} for the
 * PyPI JSON request; the full port also writes the latest-version cache
 * to {@code ~/.deepagents/.state/latest_version.json}.</p>
 */
public final class UpdateCheck {
    private UpdateCheck() {}

    private static final Logger LOG = LoggerFactory.getLogger(UpdateCheck.class);

    /** Latest-version cache path. */
    public static Path latestVersionCachePath() {
        return ModelConfig.DEFAULT_STATE_DIR.resolve("latest_version.json");
    }

    /** Update-state path. */
    public static Path updateStatePath() {
        return ModelConfig.DEFAULT_STATE_DIR.resolve("update_state.json");
    }

    /** HttpClient used for PyPI requests. */
    public static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * Fetch the latest version string from the PyPI JSON endpoint.
     */
    public static CompletionStage<String> getLatestVersion(boolean bypassCache) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(Version.PYPI_URL))
                        .header("User-Agent", Version.USER_AGENT)
                        .header("Accept", "application/json")
                        .GET()
                        .build();
                HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    LOG.debug("PyPI returned {} for {}", resp.statusCode(), Version.PYPI_URL);
                    return null;
                }
                // Minimal JSON extraction; the full port uses a parser.
                String body = resp.body();
                int idx = body.indexOf("\"info\"");
                if (idx < 0) return null;
                int ver = body.indexOf("\"version\"", idx);
                if (ver < 0) return null;
                int colon = body.indexOf(':', ver);
                if (colon < 0) return null;
                int q1 = body.indexOf('"', colon);
                if (q1 < 0) return null;
                int q2 = body.indexOf('"', q1 + 1);
                if (q2 < 0) return null;
                return body.substring(q1 + 1, q2);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                LOG.debug("Update check failed: {}", e.toString());
                return null;
            }
        });
    }

    /** Whether an update is available. */
    public static boolean isUpdateAvailable(String latest) {
        if (latest == null || latest.isEmpty()) return false;
        return !latest.equals(Version.VERSION);
    }

    /** Write the latest-version cache. */
    public static void writeLatestVersionCache(String latest) {
        try {
            Path p = latestVersionCachePath();
            Files.createDirectories(p.getParent());
            Files.writeString(p, "{\"latest\":\"" + latest + "\"}", StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            LOG.debug("Could not write latest-version cache: {}", e.toString());
        }
    }
}
