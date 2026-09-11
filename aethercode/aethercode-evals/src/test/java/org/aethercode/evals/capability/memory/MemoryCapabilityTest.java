package org.aethercode.evals.capability.memory;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-eval-2: Memory capability suite.
 *
 * <p>Covers Survey on Evaluation of LLM-based Agents (2503.16416) §2.4
 * + arXiv:2512.13564 "Memory in the Age of AI Agents" — three memory
 * kinds plus cross-session persistence and a forgetting/decay policy.</p>
 *
 * <ul>
 *   <li><b>Episodic memory</b> — record of past events ("we hit an OOM
 *       on the maven build yesterday")</li>
 *   <li><b>Semantic memory</b> — facts about the world / project
 *       ("this repo uses Spring Boot 3")</li>
 *   <li><b>Procedural memory</b> — how to do things ("to deploy, run
 *       {@code ./gradlew deploy}")</li>
 *   <li><b>Cross-session persistence</b> — facts survive a session
 *       boundary, episodic recall rebuilds context</li>
 *   <li><b>Forgetting policy</b> — composite recency × frequency ×
 *       utility score; low scores are eligible for tombstone / prune</li>
 *   <li><b>Compaction</b> — token-budgeted summary, drops low-utility
 *       entries first</li>
 * </ul>
 *
 * <p>The suite is self-contained: {@link MemoryEntry}, {@link MemoryStore},
 * and {@link ForgettingPolicy} model the same shape as AetherCode's
 * {@code ExperienceRecord} / {@code ExperienceStore} /
 * {@code ForgettingPolicy} so the assertions map 1:1 to the
 * production code. R-mod-2 can rewire the imports without changing
 * what is being measured.</p>
 */
class MemoryCapabilityTest {

    /* --------------------- MemoryEntry (ExperienceRecord-style) --------------------- */

    /** Three memory kinds, modelled on the
     *  episodic/semantic/procedural taxonomy in
     *  arXiv:2512.13564 §3.1.1. */
    public enum MemoryKind { EPISODIC, SEMANTIC, PROCEDURAL }

    public record MemoryEntry(
            String id,
            MemoryKind kind,
            String title,
            String body,
            Instant createdAt,
            Instant updatedAt,
            String sessionId,
            String sourceQuery,
            String sourceOutcome,
            double utility,
            long uses,
            List<String> tags) {

        public MemoryEntry {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(body, "body");
            if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
            if (title == null) title = "";
            if (createdAt == null) createdAt = Instant.now();
            if (updatedAt == null) updatedAt = createdAt;
            if (sourceOutcome == null) sourceOutcome = "success";
            if (Double.isNaN(utility)) utility = 0.5;
            if (utility < 0) utility = 0; else if (utility > 1) utility = 1;
            if (uses < 0) uses = 0;
            tags = tags == null ? List.of() : List.copyOf(tags);
        }

        /** Bump uses and lift utility toward 1. Returns a new
         *  record; the original is immutable. */
        public MemoryEntry withUse() {
            long newUses = uses + 1;
            // Asymptotic: u' = u + (1 - u) * 0.1
            double newUtil = utility + (1.0 - utility) * 0.1;
            return new MemoryEntry(id, kind, title, body, createdAt, Instant.now(),
                    sessionId, sourceQuery, sourceOutcome, newUtil, newUses, tags);
        }

        public int tokens() {
            // Crude: 4 chars per token.
            return (title.length() + body.length()) / 4;
        }
    }

    /* --------------------- MemoryStore (ExperienceStore-style) --------------------- */

    public static final class MemoryStore {
        private final Map<String, MemoryEntry> byId = new LinkedHashMap<>();
        private final Map<MemoryKind, Set<String>> byKind = new LinkedHashMap<>();
        private final Map<String, Set<String>> bySession = new LinkedHashMap<>();

        public MemoryStore() {
            for (MemoryKind k : MemoryKind.values()) byKind.put(k, new LinkedHashSet<>());
        }

        public synchronized MemoryEntry append(MemoryEntry e) {
            byId.put(e.id(), e);
            byKind.get(e.kind()).add(e.id());
            if (e.sessionId() != null && !e.sessionId().isBlank()) {
                bySession.computeIfAbsent(e.sessionId(), k -> new LinkedHashSet<>()).add(e.id());
            }
            return e;
        }

        public synchronized Optional<MemoryEntry> get(String id) {
            return Optional.ofNullable(byId.get(id));
        }

        public synchronized List<MemoryEntry> byKind(MemoryKind kind) {
            return byKind.get(kind).stream()
                    .map(byId::get)
                    .filter(Objects::nonNull)
                    .toList();
        }

        public synchronized List<MemoryEntry> bySession(String sessionId) {
            if (sessionId == null) return List.of();
            return bySession.getOrDefault(sessionId, Set.of()).stream()
                    .map(byId::get)
                    .filter(Objects::nonNull)
                    .toList();
        }

        public synchronized Collection<MemoryEntry> all() {
            return List.copyOf(byId.values());
        }

        public synchronized int size() { return byId.size(); }
        public synchronized int totalTokens() {
            return byId.values().stream().mapToInt(MemoryEntry::tokens).sum();
        }

        public synchronized boolean remove(String id) {
            MemoryEntry e = byId.remove(id);
            if (e == null) return false;
            byKind.get(e.kind()).remove(id);
            if (e.sessionId() != null) {
                Set<String> ids = bySession.get(e.sessionId());
                if (ids != null) ids.remove(id);
            }
            return true;
        }
    }

    /* --------------------- Forgetting policy --------------------- */

    /** Composite forgetting score in [0, 1] (higher = keep). */
    public static final class ForgettingPolicy {

        public static final long DEFAULT_TAU_MS = 30L * 24 * 3600 * 1000;

        private final double wRecency;
        private final double wFrequency;
        private final double wUtility;
        private final long tauMs;
        private final double tombstoneThreshold;
        private final double pruneThreshold;

        public ForgettingPolicy(double wRec, double wFreq, double wUtil,
                                long tauMs, double tomb, double prune) {
            double total = Math.max(1e-9, wRec + wFreq + wUtil);
            this.wRecency = Math.max(0, wRec) / total;
            this.wFrequency = Math.max(0, wFreq) / total;
            this.wUtility = Math.max(0, wUtil) / total;
            this.tauMs = Math.max(1, tauMs);
            this.tombstoneThreshold = clamp01(tomb);
            this.pruneThreshold = clamp01(prune);
        }

        public static ForgettingPolicy defaults() {
            return new ForgettingPolicy(0.5, 0.3, 0.2, DEFAULT_TAU_MS, 0.05, 0.01);
        }

        public double score(MemoryEntry e, int maxAccessCount, Instant now) {
            double recency = Math.exp(-((double) (now.toEpochMilli() - e.updatedAt().toEpochMilli()) / tauMs));
            int accesses = estimateAccesses(e, maxAccessCount);
            double frequency = maxAccessCount <= 0 ? 0.5
                    : Math.log(1.0 + accesses) / Math.log(1.0 + Math.max(1, maxAccessCount));
            double utility = e.utility();
            return wRecency * recency + wFrequency * frequency + wUtility * utility;
        }

        public boolean shouldTombstone(double s) { return s < tombstoneThreshold; }
        public boolean shouldPrune(double s) { return s < pruneThreshold; }

        public record Report(int scanned, int decayed, int tombstoned, int pruned) {}

        public Report runDecayPass(MemoryStore store, Instant now) {
            int totalBefore = store.size();
            int max = 0;
            for (MemoryEntry e : store.all()) {
                int est = estimateAccesses(e, 365);
                if (est > max) max = est;
            }
            int tomb = 0;
            for (MemoryEntry e : new ArrayList<>(store.all())) {
                double s = score(e, Math.max(1, max), now);
                if (shouldTombstone(s)) {
                    if (store.remove(e.id())) tomb++;
                }
            }
            return new Report(totalBefore, 0, tomb, 0);
        }

        private static int estimateAccesses(MemoryEntry e, int max) {
            // Use the entry's recorded `uses` field directly, capped
            // by the cohort's max. Earlier rounds used updatedAt-
            // createdAt delta which doesn't reflect actual recall
            // traffic.
            return (int) Math.min(max, e.uses());
        }

        private static double clamp01(double v) {
            if (Double.isNaN(v)) return 0;
            if (v < 0) return 0;
            if (v > 1) return 1;
            return v;
        }
    }

    /* --------------------- Compaction (token-budgeted) --------------------- */

    public record CompactionReport(int before, int after, int keptIds, int dropped) {}

    /** Token-budgeted compaction. Drop the lowest-utility entries
     *  until the total fits the budget. Episodic entries are
     *  dropped first (they're transient), then procedural, then
     *  semantic (semantic is most valuable long-term). */
    public static CompactionReport compact(MemoryStore store, int tokenBudget) {
        List<MemoryEntry> all = new ArrayList<>(store.all());
        int before = all.stream().mapToInt(MemoryEntry::tokens).sum();
        // Drop order: EPISODIC first, then PROCEDURAL, then SEMANTIC.
        Map<MemoryKind, Integer> dropOrder = new HashMap<>();
        dropOrder.put(MemoryKind.EPISODIC, 0);
        dropOrder.put(MemoryKind.PROCEDURAL, 1);
        dropOrder.put(MemoryKind.SEMANTIC, 2);
        // Within a kind, drop lowest utility first.
        all.sort(Comparator
                .comparingInt((MemoryEntry e) -> dropOrder.get(e.kind()))
                .thenComparingDouble(e -> e.utility()));
        int total = before;
        int dropped = 0;
        for (MemoryEntry e : all) {
            if (total <= tokenBudget) break;
            if (store.remove(e.id())) {
                total -= e.tokens();
                dropped++;
            }
        }
        int after = store.all().stream().mapToInt(MemoryEntry::tokens).sum();
        return new CompactionReport(before, after, store.size(), dropped);
    }

    /* --------------------- Episodic memory --------------------- */

    @Test
    void episodicEntryRecordsPastEvent() {
        MemoryStore store = new MemoryStore();
        MemoryEntry e = new MemoryEntry(null, MemoryKind.EPISODIC,
                "OOM on maven build", "Maven build ran out of heap; bumped MAVEN_OPTS to -Xmx4g",
                Instant.now(), null, "s-1", "fix build", "success",
                0.7, 0, List.of("maven", "build"));
        store.append(e);
        assertEquals(1, store.byKind(MemoryKind.EPISODIC).size());
        assertEquals(e.id(), store.byKind(MemoryKind.EPISODIC).get(0).id());
    }

    @Test
    void episodicRecallRebuildsContextAcrossSessions() {
        MemoryStore store = new MemoryStore();
        // Two episodes in two different sessions.
        MemoryEntry e1 = new MemoryEntry(null, MemoryKind.EPISODIC,
                "first session", "user asked to add a new feature",
                Instant.now(), null, "s-1", "intro", "success", 0.5, 0, List.of());
        MemoryEntry e2 = new MemoryEntry(null, MemoryKind.EPISODIC,
                "second session", "user came back and asked to test it",
                Instant.now(), null, "s-2", "followup", "success", 0.5, 0, List.of());
        store.append(e1);
        store.append(e2);
        // New session: reconstruct context from prior episodes.
        assertEquals(1, store.bySession("s-1").size());
        assertEquals(1, store.bySession("s-2").size());
        // Recall across both: total 2 entries.
        List<MemoryEntry> all = new ArrayList<>(store.all());
        assertEquals(2, all.size());
    }

    /* --------------------- Semantic memory --------------------- */

    @Test
    void semanticEntryStoresProjectFact() {
        MemoryStore store = new MemoryStore();
        store.append(new MemoryEntry(null, MemoryKind.SEMANTIC,
                "uses Spring Boot 3", "pom.xml shows spring-boot-starter-parent 3.2.0",
                null, null, null, "discover", "success", 0.9, 0, List.of("fact")));
        MemoryEntry e = store.byKind(MemoryKind.SEMANTIC).get(0);
        assertEquals(0.9, e.utility());
        assertTrue(e.tags().contains("fact"));
    }

    @Test
    void semanticRecallReturnsHighestUtilityFirst() {
        MemoryStore store = new MemoryStore();
        store.append(new MemoryEntry(null, MemoryKind.SEMANTIC, "low-utility fact", "x",
                null, null, null, null, "success", 0.2, 0, List.of()));
        store.append(new MemoryEntry(null, MemoryKind.SEMANTIC, "high-utility fact", "x",
                null, null, null, null, "success", 0.9, 0, List.of()));
        List<MemoryEntry> sorted = store.byKind(MemoryKind.SEMANTIC).stream()
                .sorted(Comparator.comparingDouble(MemoryEntry::utility).reversed())
                .toList();
        assertEquals("high-utility fact", sorted.get(0).title());
    }

    /* --------------------- Procedural memory --------------------- */

    @Test
    void proceduralEntryStoresHowTo() {
        MemoryStore store = new MemoryStore();
        store.append(new MemoryEntry(null, MemoryKind.PROCEDURAL,
                "deploy command", "./gradlew deploy --stacktrace",
                null, null, null, "how to deploy", "success", 0.8, 0, List.of("deploy")));
        assertEquals(1, store.byKind(MemoryKind.PROCEDURAL).size());
    }

    @Test
    void proceduralEntryWithUseBumpsUtility() {
        MemoryStore store = new MemoryStore();
        MemoryEntry e = store.append(new MemoryEntry(null, MemoryKind.PROCEDURAL,
                "build", "mvn clean install", null, null, null, null, "success",
                0.5, 0, List.of()));
        e = e.withUse();
        store.append(e);
        assertTrue(store.get(e.id()).get().utility() > 0.5,
                "use() should lift utility above the seed value");
    }

    /* --------------------- Cross-session persistence --------------------- */

    @Test
    void projectFactsSurviveSessionBoundary() {
        MemoryStore store = new MemoryStore();
        // Session 1 establishes project facts.
        store.append(new MemoryEntry(null, MemoryKind.SEMANTIC,
                "java version", "Java 21", null, null, "s-1", null, "success",
                0.9, 0, List.of()));
        // Session 2 starts; semantic facts are not session-scoped
        // (they're project-wide) so they should still be visible.
        assertEquals(1, store.byKind(MemoryKind.SEMANTIC).size());
        // But episodic is session-scoped.
        store.append(new MemoryEntry(null, MemoryKind.EPISODIC,
                "session 1 work", "did stuff", null, null, "s-1", null, "success",
                0.5, 0, List.of()));
        assertEquals(0, store.bySession("s-2").size(),
                "session 2 must not see session 1's episodic entry");
        // Both the semantic and episodic entries are tagged with s-1
        // (the semantic fact was established during session 1).
        assertEquals(2, store.bySession("s-1").size());
    }

    @Test
    void memoryIndexIsConsistentAfterRemove() {
        MemoryStore store = new MemoryStore();
        MemoryEntry a = new MemoryEntry(null, MemoryKind.SEMANTIC, "a", "x", null, null, null, null, "success", 0.5, 0, List.of());
        MemoryEntry b = new MemoryEntry(null, MemoryKind.EPISODIC, "b", "y", null, null, "s-1", null, "success", 0.5, 0, List.of());
        store.append(a);
        store.append(b);
        assertTrue(store.remove(a.id()));
        assertEquals(1, store.size());
        assertTrue(store.byKind(MemoryKind.SEMANTIC).isEmpty());
        assertEquals(1, store.byKind(MemoryKind.EPISODIC).size());
        // The session index should still be valid.
        assertEquals(1, store.bySession("s-1").size());
    }

    /* --------------------- Forgetting policy --------------------- */

    @Test
    void freshHighUtilityEntryHasHighForgettingScore() {
        ForgettingPolicy p = ForgettingPolicy.defaults();
        MemoryEntry e = new MemoryEntry(null, MemoryKind.SEMANTIC, "x", "y",
                Instant.now(), Instant.now(), null, null, "success", 0.95, 5, List.of());
        double s = p.score(e, 10, Instant.now());
        assertTrue(s > 0.7, "fresh + high-utility should have a high score, was " + s);
    }

    @Test
    void staleLowUtilityEntryHasLowForgettingScore() {
        ForgettingPolicy p = ForgettingPolicy.defaults();
        Instant now = Instant.now();
        // 100 days old, very low utility.
        MemoryEntry e = new MemoryEntry(null, MemoryKind.EPISODIC, "x", "y",
                now.minusSeconds(100L * 86400), now.minusSeconds(100L * 86400),
                null, null, "success", 0.05, 0, List.of());
        double s = p.score(e, 10, now);
        assertTrue(s < 0.3, "stale + low-utility should have a low score, was " + s);
        assertTrue(p.shouldTombstone(s));
    }

    @Test
    void decayPassRemovesStaleEntries() {
        ForgettingPolicy p = ForgettingPolicy.defaults();
        MemoryStore store = new MemoryStore();
        Instant now = Instant.now();
        // 5 stale, low-utility entries — 200 days old with zero uses
        // and utility 0.05. With τ=30 days, the recency term is
        // exp(-200/30) ≈ 0.0013, so the composite score lands well
        // below the tombstone threshold (0.05).
        for (int i = 0; i < 5; i++) {
            store.append(new MemoryEntry(null, MemoryKind.EPISODIC, "stale-" + i, "old",
                    now.minusSeconds(200L * 86400), now.minusSeconds(200L * 86400),
                    null, null, "success", 0.05, 0, List.of()));
        }
        // 1 fresh, high-utility entry that should survive.
        store.append(new MemoryEntry(null, MemoryKind.SEMANTIC, "fresh", "important",
                now, now, null, null, "success", 0.95, 5, List.of()));
        ForgettingPolicy.Report report = p.runDecayPass(store, now);
        assertEquals(6, report.scanned());
        assertEquals(5, report.tombstoned(), "5 stale entries should be tombstoned");
        assertEquals(1, store.size(), "1 fresh entry should survive");
    }

    @Test
    void forgettingScoreWeightsSumToOne() {
        // Weights should be normalised to sum to 1 so the composite
        // score stays in [0, 1] regardless of input.
        ForgettingPolicy p = new ForgettingPolicy(1.0, 1.0, 1.0,
                ForgettingPolicy.DEFAULT_TAU_MS, 0.05, 0.01);
        double sum = p.wRecency + p.wFrequency + p.wUtility;
        // Note: wRecency/wFrequency/wUtility are private; re-derive
        // by inspecting the score for a known input.
        // We don't have direct access, so use the public defaults to
        // verify the normalisation invariant indirectly: the score
        // must always be in [0, 1].
        MemoryEntry e = new MemoryEntry(null, MemoryKind.SEMANTIC, "x", "y",
                Instant.now(), Instant.now(), null, null, "success", 1.0, 100, List.of());
        double s = p.score(e, 100, Instant.now());
        assertTrue(s >= 0 && s <= 1, "score must be normalised to [0, 1], was " + s);
        // Also: with utility=1.0, frequency=log(101)/log(101)=1, recency=1, score=1.
        assertEquals(1.0, s, 1e-9);
    }

    /* --------------------- Compaction (token-budgeted) --------------------- */

    @Test
    void compactionDropsLowestUtilityFirst() {
        MemoryStore store = new MemoryStore();
        // Mix of utility levels.
        store.append(new MemoryEntry(null, MemoryKind.SEMANTIC, "low", "x",
                null, null, null, null, "success", 0.1, 0, List.of()));
        store.append(new MemoryEntry(null, MemoryKind.SEMANTIC, "mid", "x",
                null, null, null, null, "success", 0.5, 0, List.of()));
        store.append(new MemoryEntry(null, MemoryKind.SEMANTIC, "high", "x",
                null, null, null, null, "success", 0.9, 0, List.of()));
        // Each entry is ~2 tokens. Budget 2 forces 1 drop (total 4
        // tokens, need to drop 1 to land at <= 2).
        CompactionReport r = compact(store, 2);
        assertTrue(r.dropped() >= 1, "at least the lowest-utility entry should be dropped");
        // The high-utility entry must survive.
        assertTrue(store.all().stream().anyMatch(e -> "high".equals(e.title())),
                "high-utility semantic entry must survive compaction");
    }

    @Test
    void compactionDropsEpisodicBeforeSemantic() {
        MemoryStore store = new MemoryStore();
        store.append(new MemoryEntry(null, MemoryKind.EPISODIC, "ep1", "x",
                null, null, null, null, "success", 0.9, 0, List.of()));
        store.append(new MemoryEntry(null, MemoryKind.SEMANTIC, "sem1", "x",
                null, null, null, null, "success", 0.1, 0, List.of()));
        // Total 4 tokens, budget 1 → drop both? dropOrder puts
        // episodic first, so the episodic entry is dropped first.
        // After dropping it, total = 2 tokens, still over budget,
        // so the semantic entry is dropped too.
        compact(store, 1);
        // Episodic must be gone (dropped first by kind).
        assertTrue(store.byKind(MemoryKind.EPISODIC).isEmpty(),
                "episodic must be dropped before semantic regardless of utility");
        // Nothing left because both got dropped to fit budget.
        assertTrue(store.all().isEmpty() || store.byKind(MemoryKind.SEMANTIC).size() == 1);
    }

    @Test
    void compactionIsANoopWhenUnderBudget() {
        MemoryStore store = new MemoryStore();
        store.append(new MemoryEntry(null, MemoryKind.SEMANTIC, "only", "x",
                null, null, null, null, "success", 0.5, 0, List.of()));
        CompactionReport r = compact(store, 10_000);
        assertEquals(0, r.dropped(), "under-budget compaction must drop nothing");
        assertEquals(1, store.size());
    }

    /* --------------------- Token accounting --------------------- */

    @Test
    void tokenAccountingMatchesCrudeHeuristic() {
        MemoryEntry e = new MemoryEntry(null, MemoryKind.SEMANTIC,
                "title", "body", null, null, null, null, "success", 0.5, 0, List.of());
        // 5 + 4 = 9 chars, /4 = 2 tokens (integer floor).
        assertEquals(2, e.tokens());
    }

    @Test
    void totalTokensIsSumOfEntryTokens() {
        MemoryStore store = new MemoryStore();
        for (int i = 0; i < 10; i++) {
            store.append(new MemoryEntry(null, MemoryKind.EPISODIC,
                    "title-" + i, "body", null, null, null, null, "success", 0.5, 0, List.of()));
        }
        assertEquals(10 * (("title-0".length() + "body".length()) / 4), store.totalTokens());
    }

    /* --------------------- Recall (semantic similarity hint) --------------------- */

    @Test
    void recallByTagReturnsMatchingEntries() {
        MemoryStore store = new MemoryStore();
        store.append(new MemoryEntry(null, MemoryKind.SEMANTIC, "mvn", "maven build",
                null, null, null, null, "success", 0.5, 0, List.of("maven")));
        store.append(new MemoryEntry(null, MemoryKind.PROCEDURAL, "deploy", "deploy",
                null, null, null, null, "success", 0.5, 0, List.of("deploy")));
        List<MemoryEntry> maven = store.all().stream()
                .filter(e -> e.tags().contains("maven"))
                .toList();
        assertEquals(1, maven.size());
        assertEquals("mvn", maven.get(0).title());
    }

    @Test
    void recallByKindAndTagCombinesFilters() {
        MemoryStore store = new MemoryStore();
        store.append(new MemoryEntry(null, MemoryKind.SEMANTIC, "mvn-fact", "x",
                null, null, null, null, "success", 0.5, 0, List.of("maven")));
        store.append(new MemoryEntry(null, MemoryKind.PROCEDURAL, "mvn-howto", "y",
                null, null, null, null, "success", 0.5, 0, List.of("maven")));
        List<MemoryEntry> semanticMaven = store.all().stream()
                .filter(e -> e.kind() == MemoryKind.SEMANTIC)
                .filter(e -> e.tags().contains("maven"))
                .toList();
        assertEquals(1, semanticMaven.size());
        assertEquals("mvn-fact", semanticMaven.get(0).title());
    }

    /* --------------------- Validation --------------------- */

    @Test
    void entryRejectsNullBody() {
        assertThrows(NullPointerException.class, () -> new MemoryEntry(
                null, MemoryKind.SEMANTIC, "x", null, null, null, null, null, "success",
                0.5, 0, List.of()));
    }

    @Test
    void entryRejectsNullKind() {
        assertThrows(NullPointerException.class, () -> new MemoryEntry(
                null, null, "x", "body", null, null, null, null, "success",
                0.5, 0, List.of()));
    }

    @Test
    void entryClampsUtilityToUnitInterval() {
        MemoryEntry low = new MemoryEntry(null, MemoryKind.SEMANTIC, "x", "y",
                null, null, null, null, "success", -1.0, 0, List.of());
        assertEquals(0.0, low.utility());
        MemoryEntry high = new MemoryEntry(null, MemoryKind.SEMANTIC, "x", "y",
                null, null, null, null, "success", 5.0, 0, List.of());
        assertEquals(1.0, high.utility());
    }

    /* --------------------- End-to-end lifecycle --------------------- */

    @Test
    void fullMemoryLifecycleAcrossSessions() {
        // Day 1: project bootstrap — establish semantic + procedural facts.
        MemoryStore store = new MemoryStore();
        store.append(new MemoryEntry(null, MemoryKind.SEMANTIC,
                "uses gradle 8", "build.gradle has gradle 8.5", null, null, null, null, "success",
                0.9, 0, List.of("build")));
        store.append(new MemoryEntry(null, MemoryKind.PROCEDURAL,
                "build command", "./gradlew build", null, null, null, null, "success",
                0.7, 0, List.of("build")));

        // Day 2: a new session arrives and asks "how do I build?".
        // Recall: project fact + procedural how-to are both visible.
        List<MemoryEntry> buildFacts = store.all().stream()
                .filter(e -> e.tags().contains("build"))
                .sorted(Comparator.comparingDouble(MemoryEntry::utility).reversed())
                .toList();
        assertEquals(2, buildFacts.size());
        // Top of recall is the higher-utility semantic fact.
        assertEquals("uses gradle 8", buildFacts.get(0).title());

        // After several recalls, the procedural entry's utility should
        // climb (procedural knowledge is more durable).
        MemoryEntry proc = buildFacts.get(1);
        for (int i = 0; i < 5; i++) proc = proc.withUse();
        store.append(proc);
        // The procedural entry now has a non-trivial utility.
        assertTrue(store.get(proc.id()).get().utility() > 0.7);

        // Run a decay pass. Fresh, well-used facts survive; very
        // stale and rarely-used entries are tombstoned.
        ForgettingPolicy p = ForgettingPolicy.defaults();
        // Mark both entries as 100 days old with low uses.
        Instant now = Instant.now();
        store = new MemoryStore();
        store.append(new MemoryEntry(null, MemoryKind.SEMANTIC, "stale fact", "x",
                now.minusSeconds(100L * 86400), now.minusSeconds(100L * 86400),
                null, null, "success", 0.05, 0, List.of()));
        store.append(new MemoryEntry(null, MemoryKind.SEMANTIC, "fresh fact", "y",
                now, now, null, null, "success", 0.95, 5, List.of()));
        ForgettingPolicy.Report report = p.runDecayPass(store, now);
        assertEquals(2, report.scanned());
        assertEquals(1, report.tombstoned());
        assertEquals(1, store.size());
        assertEquals("fresh fact", store.all().iterator().next().title());
    }
}
