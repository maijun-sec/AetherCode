package org.aethercode.code.client.launch;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Server launcher and liveness check.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.client.launch.server} module. Provides
 * start / stop / health-check helpers for the local agent
 * server.</p>
 */
public final class ServerLauncher {
    private static final Logger LOGGER = Logger.getLogger(ServerLauncher.class.getName());

    private ServerLauncher() {}

    /**
     * Server launch configuration.
     */
    public record LaunchConfig(
            String name,
            Path logFile,
            int port,
            List<String> command,
            Map<String, String> environment) {
    }

    /**
     * Health-check result.
     */
    public record HealthCheck(boolean ok, int statusCode, String body) {
    }

    /**
     * Start a server asynchronously.
     */
    public static CompletableFuture<ServerManager.ServerState> start(LaunchConfig config) {
        if (config == null) return CompletableFuture.failedFuture(
                new IllegalArgumentException("config must not be null"));
        if (config.command() == null || config.command().isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("config.command must be non-empty"));
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(config.command())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(
                            config.logFile() != null
                                    ? config.logFile().toFile()
                                    : Path.of(System.getProperty("user.home"),
                                            ".deepagents", "server.log").toFile()));
            if (config.environment() != null) {
                builder.environment().putAll(config.environment());
            }
            Process process = builder.start();
            long pid = process.pid();
            int port = config.port() > 0 ? config.port() : ServerManager.findFreePort();
            ServerManager.ServerState state = new ServerManager.ServerState(
                    config.name(), pid, port, config.logFile());
            ServerManager.record(state);
            return CompletableFuture.completedFuture(state);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Stop a server by name.
     */
    public static boolean stop(String name) {
        ServerManager.ServerState state = ServerManager.lookup(name);
        if (state == null) return false;
        try {
            ProcessHandle.of(state.pid()).ifPresent(ProcessHandle::destroy);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not stop " + name, e);
        }
        ServerManager.forget(name);
        return true;
    }

    /**
     * Perform an HTTP health check on the running server.
     */
    public static HealthCheck check(String name, Duration timeout) {
        ServerManager.ServerState state = ServerManager.lookup(name);
        if (state == null) return new HealthCheck(false, 0, "no recorded state");
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + state.port() + "/health"))
                    .timeout(timeout != null ? timeout : Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());
            return new HealthCheck(response.statusCode() / 100 == 2,
                    response.statusCode(), response.body());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new HealthCheck(false, 0, e.getMessage());
        }
    }

    /** Internal helper to handle {@code Process.of(long)} across JDK versions. */
    /**
     * Internal helper for graceful process lookup.
     */
    private static final class OptionalProcess {
        static java.util.Optional<ProcessHandle> of(long pid) {
            return ProcessHandle.of(pid);
        }
    }
}
