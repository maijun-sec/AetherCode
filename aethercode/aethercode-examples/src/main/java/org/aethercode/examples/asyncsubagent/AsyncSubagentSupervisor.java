package org.aethercode.examples.asyncsubagent;

import org.aethercode.graph.CreateDeepAgent;
import org.aethercode.graph.DeepAgent;
import org.aethercode.middleware.AsyncSubAgent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Supervisor &mdash; Async Subagent Example.
 *
 * <p>Java port of
 * {@code deepagents-main/examples/async-subagent-server/supervisor.py}.
 * Builds a deep agent that delegates research to a remote
 * researcher running on {@link AsyncSubagentServer}. The supervisor
 * does not actually call the LangGraph SDK in Java &mdash; it talks
 * to the server directly through the {@code com.sun.net.httpserver}
 * surface that {@link AsyncSubagentServer} exposes.</p>
 *
 * <p>The bundled {@link #main(String[])} entry point runs a simple
 * REPL that mirrors the Python port's loop.</p>
 */
public final class AsyncSubagentSupervisor {
    private AsyncSubagentSupervisor() {}

    /** Default URL of the researcher server. Mirrors the Python port's {@code RESEARCHER_URL}. */
    public static final String DEFAULT_RESEARCHER_URL = "http://localhost:2024";

    /** Default thread id used for the supervisor conversation. */
    public static final String DEFAULT_THREAD_ID = "supervisor-thread";

    /** Build the supervisor's system prompt. Mirrors the Python port's prompt. */
    public static String supervisorSystemPrompt() {
        return """
                You are a research supervisor coordinating a background researcher agent.

                For general questions, answer directly — do NOT launch a researcher.

                Only launch the researcher when the user says "research", "investigate", "look into", or "find out".

                START: When the user asks to research something:
                  1. Call start_async_task with subagent_type "researcher" and the topic.
                  2. Report the task_id and stop. Do NOT immediately check status.

                CHECK: When the user asks for status or results:
                  1. Call check_async_task with the exact task_id.
                  2. Report what the tool returns. If still running, say so and stop.

                UPDATE: When the user asks to change what the researcher is working on:
                  1. Call update_async_task with the task_id and new instructions.
                  2. Confirm the update.

                CANCEL: When the user asks to cancel a task:
                  1. Call cancel_async_task with the exact task_id.
                  2. Confirm the cancellation.

                LIST: When the user asks to list tasks or check all statuses:
                  1. Call list_async_tasks.
                  2. Present the live statuses.

                Rules:
                - Never report a stale status from memory. Always call a tool.
                - Never poll in a loop. One tool call per user request.
                - Always show the full task_id — never truncate it.
                """;
    }

    /**
     * Build a deep agent with the researcher sub-agent wired up.
     *
     * @param model the chat model or model-spec string
     * @param researcherUrl URL of the researcher server
     */
    public static DeepAgent build(Object model, String researcherUrl) {
        List<AsyncSubAgent> asyncSubagents = List.of(
                AsyncSubAgent.builder("researcher",
                        "A research agent that investigates any topic using web search. "
                                + "Runs in the background and returns a detailed summary.",
                        "researcher")
                        .url(researcherUrl)
                        .headers(Map.of("x-auth-scheme", "custom"))
                        .build());
        return CreateDeepAgent.create(
                model,
                List.of(),
                supervisorSystemPrompt(),
                null,
                asyncSubagents,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "supervisor");
    }

    /**
     * Talk to the researcher server directly. Mirrors the Python
     * port's {@code langgraph_sdk.get_client(...)} call.
     *
     * <p>The bundled methods return JSON-shaped maps so callers
     * can build whatever supervisor logic they need without
     * depending on the LangGraph SDK.</p>
     */
    public static final class ResearcherClient {
        private final HttpClient http;
        private final String baseUrl;

        public ResearcherClient(String baseUrl) {
            this.baseUrl = baseUrl;
            this.http = HttpClient.newHttpClient();
        }

        public boolean health() {
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder()
                                .uri(URI.create(baseUrl + "/ok")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                return r.statusCode() == 200;
            } catch (Exception e) {
                return false;
            }
        }

        public Map<String, Object> createThread() {
            return post("/threads", "");
        }

        public Map<String, Object> createRun(String threadId, String userMessage) {
            Map<String, Object> input = Map.of("messages",
                    List.of(Map.of("role", "user", "content", userMessage)));
            Map<String, Object> body = Map.of(
                    "input", input,
                    "assistant_id", "researcher",
                    "multitask_strategy", "interrupt");
            return post("/threads/" + threadId + "/runs", JsonHelpers.dump(body));
        }

        public Map<String, Object> getRun(String threadId, String runId) {
            return get("/threads/" + threadId + "/runs/" + runId);
        }

        public Map<String, Object> getThread(String threadId) {
            return get("/threads/" + threadId);
        }

        public Map<String, Object> cancelRun(String threadId, String runId) {
            return post("/threads/" + threadId + "/runs/" + runId + "/cancel", "");
        }

        private Map<String, Object> get(String path) {
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder()
                                .uri(URI.create(baseUrl + path)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                @SuppressWarnings("unchecked")
                Map<String, Object> body = (Map<String, Object>) JsonHelpers.parse(r.body());
                return body == null ? Map.of() : body;
            } catch (Exception e) {
                throw new RuntimeException("GET " + path + " failed", e);
            }
        }

        private Map<String, Object> post(String path, String body) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + path))
                        .header("Content-Type", "application/json")
                        .header("x-auth-scheme", "custom")
                        .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body))
                        .build();
                HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
                @SuppressWarnings("unchecked")
                Map<String, Object> resp = (Map<String, Object>) JsonHelpers.parse(r.body());
                return resp == null ? Map.of() : resp;
            } catch (Exception e) {
                throw new RuntimeException("POST " + path + " failed", e);
            }
        }
    }

    /** Convenience main entry point &mdash; mirrors the Python port's REPL. */
    public static void main(String[] args) throws IOException {
        String researcherUrl = System.getenv().getOrDefault("RESEARCHER_URL", DEFAULT_RESEARCHER_URL);
        ResearcherClient client = new ResearcherClient(researcherUrl);
        String threadId = client.health()
                ? (String) client.createThread().getOrDefault("thread_id", UUID.randomUUID().toString())
                : UUID.randomUUID().toString();
        System.out.println("Supervisor connected to researcher at " + researcherUrl);
        System.out.println("Thread: " + threadId);
        System.out.println("Type a message and press Enter. Ctrl+C to exit.\n");
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            System.out.print("> ");
            String line = reader.readLine();
            if (line == null) break;
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;
            try {
                handle(trimmed, client, threadId);
            } catch (Exception exc) {
                System.out.println("Error: " + exc.getMessage());
            }
        }
    }

    /**
     * Small REPL handler that interprets four slash commands plus a
     * free-form research request. Mirrors the Python port's REPL.
     */
    static void handle(String input, ResearcherClient client, String threadId) {
        if (input.startsWith("check status of ")) {
            String runId = input.substring("check status of ".length()).strip();
            Map<String, Object> run = client.getRun(threadId, runId);
            System.out.println(run);
            return;
        }
        if (input.startsWith("cancel ")) {
            String runId = input.substring("cancel ".length()).strip();
            Map<String, Object> run = client.cancelRun(threadId, runId);
            System.out.println(run);
            return;
        }
        if (input.equals("list all tasks")) {
            // The server's protocol does not expose a list endpoint;
            // print the latest run from the current thread if any.
            Map<String, Object> t = client.getThread(threadId);
            System.out.println(t);
            return;
        }
        // Treat anything else as a research request.
        Map<String, Object> run = client.createRun(threadId, input);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task_id", run.get("run_id"));
        out.put("status", run.get("status"));
        System.out.println(out);
    }
}
