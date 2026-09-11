package org.aethercode.code.client;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Non-interactive (headless) entry point for the agent.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.client.non_interactive} module. The Java
 * port is a façade: it accepts a {@link Request} and returns a
 * {@link Result}, with the agent loop owned by a host runtime.</p>
 *
 * <p>The Python module is large (~90KB) and includes the full
 * command-line parsing, agent loop, output rendering, and remote
 * server hand-off. The Java port decomposes that into
 * {@code cli} (parser), {@code loop} (iteration), and
 * {@code output} (rendering) helpers, exposed through the
 * {@link Builder} for hosts that want a quick non-interactive
 * run.</p>
 */
public final class NonInteractiveClient {
    private final Request request;
    private final AgentLoop agentLoop;
    private final EventSink sink;

    private NonInteractiveClient(Request request, AgentLoop agentLoop, EventSink sink) {
        this.request = request;
        this.agentLoop = agentLoop != null ? agentLoop : StubAgentLoop.INSTANCE;
        this.sink = sink != null ? sink : EventSink.NOOP;
    }

    /**
     * Run the agent.
     */
    public CompletableFuture<Result> run() {
        if (request == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("request is required"));
        }
        if (request.remote() && request.remoteUrl() != null) {
            return runRemote();
        }
        return agentLoop.run(request, sink);
    }

    /**
     * Inputs to a non-interactive run.
     */
    public record Request(
            String prompt,
            String model,
            String workingDirectory,
            List<String> skills,
            Map<String, Object> environment,
            Path outputFile,
            boolean streamEvents,
            boolean remote,
            String remoteUrl) {
    }

    /**
     * Result of a non-interactive run.
     */
    public record Result(
            String finalMessage,
            List<Event> events,
            int exitCode,
            String error) {

        public boolean ok() { return exitCode == 0; }
    }

    /**
     * A single event emitted during the run.
     */
    public record Event(String type, Map<String, Object> data) {
    }

    /**
     * Builder for a non-interactive client.
     */
    public static final class Builder {
        private Request request;
        private AgentLoop agentLoop;
        private EventSink sink;

        public Builder request(Request request) { this.request = request; return this; }
        public Builder agentLoop(AgentLoop loop) { this.agentLoop = loop; return this; }
        public Builder eventSink(EventSink sink) { this.sink = sink; return this; }

        public NonInteractiveClient build() {
            return new NonInteractiveClient(request, agentLoop, sink);
        }
    }

    /** Agent loop contract owned by the host runtime. */
    public interface AgentLoop {
        /**
         * Run a single prompt through the agent. The returned
         * events stream to the sink as they are produced.
         */
        CompletableFuture<Result> run(Request request, EventSink sink);
    }

    /** Event sink. */
    public interface EventSink {
        /** Called once per event; may be called concurrently. */
        void onEvent(Event event);

        /** Terminal completion notification. */
        void onComplete(Result result);

        /** Default no-op sink. */
        EventSink NOOP = new EventSink() {
            @Override public void onEvent(Event event) { }
            @Override public void onComplete(Result result) { }
        };
    }

    private CompletableFuture<Result> runRemote() {
        try (RemoteClient client = RemoteClient.create(
                request.remoteUrl(), null, java.time.Duration.ofSeconds(15))) {
            Map<String, Object> body = Map.of(
                    "prompt", request.prompt(),
                    "model", request.model() != null ? request.model() : "");
            String response = client.post("/v1/run", body);
            return CompletableFuture.completedFuture(new Result(
                    response, List.of(), 0, null));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return CompletableFuture.completedFuture(new Result(
                    null, List.of(), 1, e.getMessage()));
        }
    }

    /** Convenience static entry point. */
    public static Result runOnce(Request request) {
        try {
            return new Builder().request(request).build().run().get();
        } catch (Exception e) {
            return new Result(null, List.of(), 1, e.getMessage());
        }
    }

    /**
     * Default in-process loop that just echoes the prompt. Hosts are
     * expected to provide a real {@link AgentLoop}; the stub exists
     * so the class is self-contained.
     */
    private static final class StubAgentLoop implements AgentLoop {
        static final StubAgentLoop INSTANCE = new StubAgentLoop();
        @Override
        public CompletableFuture<Result> run(Request request, EventSink sink) {
            sink.onEvent(new Event("user", Map.of("text", request.prompt())));
            String response = "[stub] " + request.prompt();
            sink.onEvent(new Event("assistant", Map.of("text", response)));
            Result result = new Result(response, List.of(), 0, null);
            sink.onComplete(result);
            return CompletableFuture.completedFuture(result);
        }
    }
}
