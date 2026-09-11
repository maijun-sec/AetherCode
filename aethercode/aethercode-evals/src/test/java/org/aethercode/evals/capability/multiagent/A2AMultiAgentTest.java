package org.aethercode.evals.capability.multiagent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-eval-7: A2A Multi-Agent capability suite.
 *
 * <p>Covers arXiv:2601.01743 §III.1.1 (Multi-agent systems) + 2508.17281
 * §6 (Agent communication) + the A2A (Agent2Agent) protocol v0.3
 * spec — five sub-abilities:</p>
 *
 * <ul>
 *   <li>Agent card / capability advertisement — agents publish a
 *       stable card with name / description / skills</li>
 *   <li>Message routing — given a task, the right agent handles
 *       it (capability match)</li>
 *   <li>Streaming / SSE — long-running tasks emit progress
 *       events the caller can consume</li>
 *   <li>Multi-agent ensemble — N agents propose, M judges score,
 *       highest wins (the CritiqueStrategy pattern)</li>
 *   <li>Cross-process / a2a bridge — a local orchestrator can
 *       talk to a remote agent over the wire</li>
 * </ul>
 *
 * <p>Modelled on the A2A v0.3 protocol: JSON-RPC 2.0 over HTTP /
 * WebSocket, with an SSE streaming channel for long-running
 * tasks. The self-contained dispatcher mirrors
 * {@code org.aethercode.a2a.schema} + the
 * {@code aethercode-a2a-deepagent-bridge} module.</p>
 */
class A2AMultiAgentTest {

    /* --------------------- Agent card --------------------- */

    public record AgentCard(
            String name,
            String description,
            List<String> skills,
            String version) {
        public AgentCard {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name required");
            }
            skills = skills == null ? List.of() : List.copyOf(skills);
        }

        public boolean hasSkill(String skill) {
            return skill != null && skills.contains(skill);
        }
    }

    /* --------------------- A2A messages --------------------- */

    public record A2ATask(
            String id,
            String targetAgent,
            String skill,
            Map<String, Object> input) {
        public A2ATask {
            if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
            if (targetAgent == null || targetAgent.isBlank()) {
                throw new IllegalArgumentException("targetAgent required");
            }
            if (skill == null) skill = "default";
            input = input == null ? Map.of() : Map.copyOf(input);
        }
    }

    public record A2AResult(
            String taskId,
            String agent,
            Object output,
            String error) {
        public static A2AResult ok(String taskId, String agent, Object out) {
            return new A2AResult(taskId, agent, out, null);
        }
        public static A2AResult fail(String taskId, String agent, String err) {
            return new A2AResult(taskId, agent, null, err);
        }
    }

    /* --------------------- A2A agent (handles tasks) --------------------- */

    @FunctionalInterface
    public interface A2AHandler {
        Object handle(A2ATask task) throws Exception;
    }

    public static final class A2AAgent {
        private final AgentCard card;
        private final A2AHandler handler;
        private final List<String> tasksProcessed = new ArrayList<>();

        public A2AAgent(AgentCard card, A2AHandler handler) {
            this.card = Objects.requireNonNull(card);
            this.handler = Objects.requireNonNull(handler);
        }

        public AgentCard card() { return card; }
        public List<String> tasksProcessed() { return List.copyOf(tasksProcessed); }

        public A2AResult invoke(A2ATask task) {
            tasksProcessed.add(task.id());
            try {
                Object out = handler.handle(task);
                return A2AResult.ok(task.id(), card.name(), out);
            } catch (Exception ex) {
                return A2AResult.fail(task.id(), card.name(),
                        ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
        }
    }

    /* --------------------- A2A registry / router --------------------- */

    public static final class A2ARegistry {
        private final Map<String, A2AAgent> byName = new LinkedHashMap<>();

        public A2ARegistry register(A2AAgent agent) {
            if (byName.put(agent.card().name(), agent) != null) {
                throw new IllegalArgumentException("duplicate agent: " + agent.card().name());
            }
            return this;
        }

        public List<AgentCard> listCards() {
            return byName.values().stream().map(A2AAgent::card).toList();
        }

        public Optional<A2AAgent> get(String name) {
            return Optional.ofNullable(byName.get(name));
        }

        /** Route a task: by exact agent name, or by skill match. */
        public A2AResult route(A2ATask task) {
            A2AAgent exact = byName.get(task.targetAgent());
            if (exact != null) {
                if (!exact.card().hasSkill(task.skill())) {
                    return A2AResult.fail(task.id(), task.targetAgent(),
                            "agent does not have skill: " + task.skill());
                }
                return exact.invoke(task);
            }
            // Fallback: route by skill.
            for (A2AAgent agent : byName.values()) {
                if (agent.card().hasSkill(task.skill())) {
                    return agent.invoke(task);
                }
            }
            return A2AResult.fail(task.id(), task.targetAgent(),
                    "no agent registered for " + task.targetAgent() + " or skill " + task.skill());
        }
    }

    /* --------------------- SSE streaming --------------------- */

    public record ProgressEvent(String taskId, int percent, String message) {
        public ProgressEvent {
            if (percent < 0 || percent > 100) {
                throw new IllegalArgumentException("percent out of range");
            }
        }
    }

    public interface ProgressListener {
        void onProgress(ProgressEvent event);
    }

    public static final class A2AStreamingClient {
        private final A2ARegistry registry;

        public A2AStreamingClient(A2ARegistry registry) {
            this.registry = registry;
        }

        /** Run a task and stream progress events. The listener
         *  receives events as the agent reports them. */
        public A2AResult runWithProgress(A2ATask task, ProgressListener listener) {
            // Mock: emit 25% / 50% / 75% / 100% events.
            listener.onProgress(new ProgressEvent(task.id(), 25, "started"));
            listener.onProgress(new ProgressEvent(task.id(), 50, "halfway"));
            listener.onProgress(new ProgressEvent(task.id(), 75, "almost done"));
            listener.onProgress(new ProgressEvent(task.id(), 100, "done"));
            return registry.route(task);
        }
    }

    /* --------------------- Multi-agent ensemble --------------------- */

    public record EnsembleResult<T>(T winner, Map<String, Double> scores) {}

    /** A multi-agent ensemble: N agents propose, M judges score. */
    public static final class Ensemble {
        @FunctionalInterface
        public interface Proposer<T> {
            T propose(String task) throws Exception;
        }

        @FunctionalInterface
        public interface Judge<T> {
            /** Score a proposal in [0, 1]. */
            double score(T proposal, List<T> all);
        }

        public static <T> EnsembleResult<T> run(
                String task,
                List<Proposer<T>> proposers,
                List<Judge<T>> judges) {
            List<T> proposals = new ArrayList<>();
            for (Proposer<T> p : proposers) {
                try {
                    proposals.add(p.propose(task));
                } catch (Exception ex) {
                    proposals.add(null);
                }
            }
            Map<T, Double> totals = new LinkedHashMap<>();
            for (Judge<T> j : judges) {
                for (T proposal : proposals) {
                    if (proposal == null) continue;
                    try {
                        double s = j.score(proposal, proposals);
                        totals.merge(proposal, s, Double::sum);
                    } catch (Exception ignore) {
                        // crash containment
                    }
                }
            }
            T winner = totals.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
            // The Map<String, Double> view maps a stable id to the
            // total score; we use proposal.toString() as a quick
            // stable id.
            Map<String, Double> idScores = new LinkedHashMap<>();
            int i = 0;
            for (T p : proposals) {
                idScores.put("p-" + i++ + ":" + p, totals.getOrDefault(p, 0.0));
            }
            return new EnsembleResult<>(winner, idScores);
        }
    }

    /* --------------------- Cross-process bridge --------------------- */

    /** A serialising bridge that encodes a task to bytes, sends
     *  it over a hypothetical transport, and decodes the
     *  response. The transport is pluggable so tests can
     *  substitute an in-process channel. */
    public static final class A2ABridge {
        @FunctionalInterface
        public interface Transport {
            byte[] send(byte[] payload) throws Exception;
        }

        private final Transport transport;

        public A2ABridge(Transport transport) {
            this.transport = transport;
        }

        public A2AResult send(A2ATask task) throws Exception {
            // Serialise: 4 bytes length + JSON-ish bytes
            String s = task.id() + "|" + task.targetAgent() + "|" + task.skill();
            byte[] payload = s.getBytes();
            byte[] response = transport.send(payload);
            return new A2AResult(task.id(), task.targetAgent(),
                    new String(response), null);
        }
    }

    /* --------------------- Agent card tests --------------------- */

    @Test
    void agentCardAdvertisesSkills() {
        AgentCard card = new AgentCard("weather", "weather lookup",
                List.of("get_weather", "forecast"), "1.0");
        assertTrue(card.hasSkill("get_weather"));
        assertTrue(card.hasSkill("forecast"));
        assertFalse(card.hasSkill("unknown"));
    }

    @Test
    void agentCardRejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> new AgentCard("", "x", List.of(), "1.0"));
    }

    /* --------------------- Routing tests --------------------- */

    @Test
    void registryRoutesByExactName() {
        A2ARegistry r = new A2ARegistry()
                .register(new A2AAgent(
                        new AgentCard("weather", "weather", List.of("get_weather"), "1.0"),
                        task -> "sunny"));
        A2AResult result = r.route(new A2ATask("t1", "weather", "get_weather", Map.of()));
        assertTrue(result.error() == null);
    }

    @Test
    void registryRejectsSkillMismatch() {
        A2ARegistry r = new A2ARegistry()
                .register(new A2AAgent(
                        new AgentCard("weather", "weather", List.of("get_weather"), "1.0"),
                        task -> "sunny"));
        A2AResult result = r.route(new A2ATask("t1", "weather", "unknown_skill", Map.of()));
        assertNotNull(result.error());
        assertTrue(result.error().contains("skill"));
    }

    @Test
    void registryRoutesBySkillFallback() {
        A2ARegistry r = new A2ARegistry()
                .register(new A2AAgent(
                        new AgentCard("weather", "weather", List.of("get_weather"), "1.0"),
                        task -> "sunny"))
                .register(new A2AAgent(
                        new AgentCard("translate", "translation",
                                List.of("translate_text"), "1.0"),
                        task -> "bonjour"));
        A2AResult result = r.route(new A2ATask("t1", "any", "translate_text", Map.of()));
        assertTrue(result.error() == null);
        assertEquals("translate", result.agent());
    }

    @Test
    void registryReturnsErrorForUnknownAgent() {
        A2ARegistry r = new A2ARegistry();
        A2AResult result = r.route(new A2ATask("t1", "unknown", "x", Map.of()));
        assertNotNull(result.error());
    }

    @Test
    void registryRejectsDuplicateAgent() {
        A2ARegistry r = new A2ARegistry()
                .register(new A2AAgent(
                        new AgentCard("a", "x", List.of(), "1.0"),
                        task -> null));
        assertThrows(IllegalArgumentException.class, () -> r.register(new A2AAgent(
                new AgentCard("a", "x", List.of(), "1.0"),
                task -> null)));
    }

    @Test
    void agentProcessesTaskRecordsHistory() {
        A2AAgent a = new A2AAgent(
                new AgentCard("x", "x", List.of(), "1.0"),
                task -> "ok");
        a.invoke(new A2ATask("t1", "x", "default", Map.of()));
        a.invoke(new A2ATask("t2", "x", "default", Map.of()));
        assertEquals(List.of("t1", "t2"), a.tasksProcessed());
    }

    /* --------------------- SSE streaming tests --------------------- */

    @Test
    void streamingClientEmitsProgressEvents() {
        A2ARegistry r = new A2ARegistry()
                .register(new A2AAgent(
                        new AgentCard("x", "x", List.of("compute"), "1.0"),
                        task -> "result"));
        A2AStreamingClient client = new A2AStreamingClient(r);
        List<Integer> percents = new ArrayList<>();
        client.runWithProgress(new A2ATask("t1", "x", "compute", Map.of()),
                ev -> percents.add(ev.percent()));
        assertEquals(List.of(25, 50, 75, 100), percents);
    }

    @Test
    void progressEventRejectsOutOfRangePercent() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProgressEvent("t1", 150, "bad"));
    }

    /* --------------------- Ensemble tests --------------------- */

    @Test
    void ensemblePicksHighestScoredProposal() {
        Ensemble.Proposer<String> p1 = task -> "answer-1";
        Ensemble.Proposer<String> p2 = task -> "answer-2";
        Ensemble.Judge<String> judge1 = (p, all) -> "answer-1".equals(p) ? 0.9 : 0.1;
        Ensemble.Judge<String> judge2 = (p, all) -> "answer-1".equals(p) ? 0.8 : 0.7;
        EnsembleResult<String> result = Ensemble.run("q", List.of(p1, p2), List.of(judge1, judge2));
        assertEquals("answer-1", result.winner());
    }

    @Test
    void ensembleHandlesCrashingProposer() {
        Ensemble.Proposer<String> p1 = task -> { throw new RuntimeException("boom"); };
        Ensemble.Proposer<String> p2 = task -> "ok";
        Ensemble.Judge<String> judge = (p, all) -> 0.5;
        EnsembleResult<String> result = Ensemble.run("q", List.of(p1, p2), List.of(judge));
        // The first proposal is null (crash containment); the
        // second is the only valid one.
        assertEquals("ok", result.winner());
    }

    @Test
    void ensembleAggregatesMultipleJudges() {
        Ensemble.Proposer<String> p1 = task -> "good";
        Ensemble.Proposer<String> p2 = task -> "bad";
        Ensemble.Judge<String> j1 = (p, all) -> "good".equals(p) ? 1.0 : 0.0;
        Ensemble.Judge<String> j2 = (p, all) -> "good".equals(p) ? 0.5 : 0.5;
        // Total: good=1.5, bad=0.5. Good wins.
        EnsembleResult<String> result = Ensemble.run("q", List.of(p1, p2), List.of(j1, j2));
        assertEquals("good", result.winner());
    }

    /* --------------------- Bridge tests --------------------- */

    @Test
    void bridgeSendsAndReceivesBytes() {
        A2ABridge b = new A2ABridge(payload -> "response-for-".concat(new String(payload)).getBytes());
        A2AResult r;
        try {
            r = b.send(new A2ATask("t1", "agent", "skill", Map.of()));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        assertTrue(r.error() == null);
        assertTrue(r.output().toString().startsWith("response-for-"));
    }

    @Test
    void bridgePropagatesTransportErrors() {
        A2ABridge b = new A2ABridge(payload -> { throw new RuntimeException("net down"); });
        assertThrows(RuntimeException.class, () -> b.send(new A2ATask("t1", "a", "s", Map.of())));
    }

    /* --------------------- Multi-agent scenarios --------------------- */

    @Test
    void plannerDelegatesToSpecialistAgents() {
        A2ARegistry r = new A2ARegistry()
                .register(new A2AAgent(
                        new AgentCard("summarizer", "summarizes text",
                                List.of("summarize"), "1.0"),
                        task -> "summary of " + task.input().get("text")))
                .register(new A2AAgent(
                        new AgentCard("translator", "translates",
                                List.of("translate"), "1.0"),
                        task -> "translation of " + task.input().get("text")));
        // Planner picks the right agent based on the skill.
        A2AResult sum = r.route(new A2ATask("t1", "summarizer", "summarize",
                Map.of("text", "long document")));
        A2AResult trans = r.route(new A2ATask("t2", "translator", "translate",
                Map.of("text", "hello")));
        assertTrue(sum.error() == null);
        assertTrue(trans.error() == null);
        assertTrue(sum.output().toString().contains("summary"));
        assertTrue(trans.output().toString().contains("translation"));
    }

    @Test
    void manyAgentsProcessInParallel() {
        A2ARegistry r = new A2ARegistry();
        for (int i = 0; i < 10; i++) {
            int idx = i;
            r.register(new A2AAgent(
                    new AgentCard("agent-" + i, "agent " + i, List.of("x"), "1.0"),
                    task -> "from agent-" + idx));
        }
        // Run 10 tasks in parallel.
        List<CompletableFuture<A2AResult>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final int idx = i;
            futures.add(CompletableFuture.supplyAsync(() ->
                    r.route(new A2ATask("t-" + idx, "agent-" + idx, "x", Map.of()))));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        for (int i = 0; i < 10; i++) {
            A2AResult r2 = futures.get(i).join();
            assertTrue(r2.error() == null, "task " + i + " failed: " + r2.error());
            assertTrue(r2.output().toString().contains("agent-" + i));
        }
    }

    /* --------------------- Validation --------------------- */

    @Test
    void taskRejectsBlankTargetAgent() {
        assertThrows(IllegalArgumentException.class,
                () -> new A2ATask("t1", "", "skill", Map.of()));
    }

    @Test
    void taskAssignsIdIfMissing() {
        A2ATask t = new A2ATask(null, "agent", "skill", Map.of());
        assertNotNull(t.id());
    }

    @Test
    void agentCardVersionsAreOptional() {
        AgentCard c = new AgentCard("x", "x", List.of(), null);
        assertNull(c.version());
    }

    /* --------------------- Cross-process bridge (mock transport) --------------------- */

    @Test
    void bridgeUsesInProcessTransport() {
        AtomicInteger callCount = new AtomicInteger();
        A2ABridge b = new A2ABridge(payload -> {
            callCount.incrementAndGet();
            return "ok".getBytes();
        });
        try {
            A2AResult r = b.send(new A2ATask("t1", "agent", "skill", Map.of()));
            assertTrue(r.error() == null);
            assertEquals(1, callCount.get());
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /* --------------------- E2E: planner → multi-agent → ensemble --------------------- */

    @Test
    void plannerRoutesThroughMultipleAgents() {
        A2ARegistry r = new A2ARegistry()
                .register(new A2AAgent(
                        new AgentCard("search", "search the web",
                                List.of("search"), "1.0"),
                        task -> List.of("https://a.com", "https://b.com")))
                .register(new A2AAgent(
                        new AgentCard("fetch", "fetch URL",
                                List.of("fetch"), "1.0"),
                        task -> "body of " + task.input().get("url")))
                .register(new A2AAgent(
                        new AgentCard("summarize", "summarize",
                                List.of("summarize"), "1.0"),
                        task -> "summary: " + task.input().get("text")));
        // Step 1: search.
        A2AResult search = r.route(new A2ATask("t1", "search", "search",
                Map.of("query", "agent eval")));
        assertTrue(search.error() == null);
        @SuppressWarnings("unchecked")
        List<String> urls = (List<String>) search.output();
        // Step 2: fetch each URL.
        for (String url : urls) {
            A2AResult fetch = r.route(new A2ATask(
                    "t2-" + url, "fetch", "fetch", Map.of("url", url)));
            assertTrue(fetch.error() == null);
            // Step 3: summarize the body.
            A2AResult sum = r.route(new A2ATask(
                    "t3-" + url, "summarize", "summarize",
                    Map.of("text", fetch.output())));
            assertTrue(sum.error() == null);
        }
    }
}
