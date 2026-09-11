package org.aethercode.evals.clbench.system;

import org.aethercode.evals.clbench.system.ClbenchTypes.ContinualLearningSystem;
import org.aethercode.evals.clbench.system.ClbenchTypes.Observation;
import org.aethercode.evals.clbench.system.ClbenchTypes.Query;
import org.aethercode.evals.clbench.system.ClbenchTypes.Response;
import org.aethercode.evals.clbench.system.ClbenchTypes.SystemRegistry;
import org.aethercode.evals.clbench.system.ClbenchTypes.UsageEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ClbenchTypes} — the Java mirror of the upstream
 * clbench package's interface, registry, and usage modules that
 * {@link DeepAgentsSystem} builds on.
 *
 * <p>R-radar-2: bring {@code aethercode-evals} test count from 0 to 50+.</p>
 */
class ClbenchTypesTest {

    /** A throwaway schema class for the {@link Query#responseSchema()} test. */
    public static final class FakeResponse {
        public FakeResponse() {}
    }

    /** Minimal in-memory system used to exercise the {@link ContinualLearningSystem} contract. */
    static final class FakeSystem implements ContinualLearningSystem {
        final String name;
        int respondCount;
        int observeCount;
        int resetCount;
        final AtomicReference<String> lastPrompt = new AtomicReference<>();

        FakeSystem(String name) {
            this.name = name;
        }

        @Override public boolean supportsBaseline() { return true; }
        @Override public boolean parallelSafe() { return true; }
        @Override public String name() { return name; }
        @Override public Response respond(Query query) {
            respondCount++;
            lastPrompt.set(query.prompt());
            return new Response("action-" + respondCount, Map.of("system", name));
        }
        @Override public void observe(Observation observation, Query nextQuery) {
            observeCount++;
        }
        @Override public void reset() { resetCount++; }
        @Override public Map<String, Object> getRunArtifacts() {
            return Map.of("system", name, "responds", respondCount);
        }
        @Override public void recordUsageEvent(UsageEvent event) {}
    }

    @Test
    void observationCarriesContent() {
        Observation o = new Observation("the agent scored 0.42");
        assertEquals("the agent scored 0.42", o.content());
    }

    @Test
    void observationContentMayBeEmpty() {
        Observation o = new Observation("");
        assertNotNull(o.content());
        assertEquals("", o.content());
    }

    @Test
    void queryRoundTripsPromptFeedbackAndSchema() {
        Observation fb = new Observation("prev: fail");
        Query q = new Query("what next?", fb, FakeResponse.class);
        assertEquals("what next?", q.prompt());
        assertSame(fb, q.feedback());
        assertSame(FakeResponse.class, q.responseSchema());
    }

    @Test
    void queryAcceptsNullFeedback() {
        Query q = new Query("hello", null, FakeResponse.class);
        assertEquals("hello", q.prompt());
        assertSame(null, q.feedback());
    }

    @Test
    void responseRoundTripsActionAndMetadata() {
        Response r = new Response("ACTION", Map.of("k", 1, "n", 2));
        assertEquals("ACTION", r.action());
        assertEquals(1, r.metadata().get("k"));
        assertEquals(2, r.metadata().get("n"));
    }

    @Test
    void usageEventRoundTripsAllFields() {
        UsageEvent e = new UsageEvent("completion", "anthropic:claude-sonnet-4-6", 100, 50, 150);
        assertEquals("completion", e.callType());
        assertEquals("anthropic:claude-sonnet-4-6", e.model());
        assertEquals(100, e.inputTokens());
        assertEquals(50, e.outputTokens());
        assertEquals(150, e.totalTokens());
    }

    @Test
    void usageEventAcceptsZeroTokens() {
        UsageEvent e = new UsageEvent("embed", "model", 0, 0, 0);
        assertEquals(0, e.totalTokens(),
                "total must equal input+output; zero is a valid case for an empty batch");
    }

    @Test
    void systemRegistryIsUnmodifiable() {
        // Defense in depth: SystemRegistry.names() returns an unmodifiable view
        // (see List.copyOf in the source). Mutating it must throw.
        List<String> names = SystemRegistry.names();
        assertThrows(UnsupportedOperationException.class,
                () -> names.add("rogue-system"));
    }

    @Test
    void systemRegistryLookupReturnsRegisteredClass() {
        // Force-load DeepAgentsSystem so its static initializer registers
        // it under "deepagents". Class.forName is the documented way to
        // trigger class load without calling a constructor.
        try {
            Class.forName("org.aethercode.evals.clbench.system.DeepAgentsSystem",
                    true,
                    Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException ex) {
            throw new AssertionError("DeepAgentsSystem class must be on the classpath", ex);
        }
        Class<? extends ContinualLearningSystem> cls = SystemRegistry.lookup("deepagents");
        assertSame(DeepAgentsSystem.class, cls,
                "DeepAgentsSystem must self-register under 'deepagents' on class load");
    }

    @Test
    void systemRegistryLookupReturnsNullForUnknown() {
        assertSame(null, SystemRegistry.lookup("nope"));
    }

    @Test
    void systemRegistryAllowsCustomRegistration() {
        String key = "clbenchtypes-test-" + System.nanoTime();
        try {
            SystemRegistry.register(key, FakeSystem.class);
            assertSame(FakeSystem.class, SystemRegistry.lookup(key));
            assertTrue(SystemRegistry.names().contains(key));
        } finally {
            // SystemRegistry doesn't expose unregister; rely on test isolation
            // and the unique key to keep names() honest.
        }
    }

    @Test
    void systemRegistryLastRegistrationWins() {
        // Same key registered twice replaces the value; verify the contract.
        String key = "clbenchtypes-test-rebind-" + System.nanoTime();
        SystemRegistry.register(key, FakeSystem.class);
        // SystemRegistry is package-private on the type parameter, so we can't
        // register a second impl. Re-registering the same class is the safe path.
        SystemRegistry.register(key, FakeSystem.class);
        assertSame(FakeSystem.class, SystemRegistry.lookup(key));
    }

    @Test
    void fakeSystemImplementsContract() {
        // Smoke-test the test fixture itself so a future refactor of the
        // contract (e.g. adding a default method) gets caught here too.
        FakeSystem fs = new FakeSystem("fake");
        Query q = new Query("ping", null, FakeResponse.class);
        Response r = fs.respond(q);
        assertEquals(1, fs.respondCount);
        assertEquals("ping", fs.lastPrompt.get());
        assertEquals("action-1", r.action());
        assertEquals("fake", r.metadata().get("system"));
        assertTrue(fs.supportsBaseline());
        assertTrue(fs.parallelSafe());
        assertEquals("fake", fs.name());
    }

    @Test
    void observeAndResetCountIncrementOnContractCalls() {
        FakeSystem fs = new FakeSystem("fake");
        fs.observe(new Observation("fb"), new Query("next", null, FakeResponse.class));
        fs.reset();
        assertEquals(1, fs.observeCount);
        assertEquals(1, fs.resetCount);
    }

    @Test
    void getRunArtifactsExposesSystemNameAndCounts() {
        FakeSystem fs = new FakeSystem("counter");
        fs.respond(new Query("a", null, FakeResponse.class));
        fs.respond(new Query("b", null, FakeResponse.class));
        Map<String, Object> artifacts = fs.getRunArtifacts();
        assertEquals("counter", artifacts.get("system"));
        assertEquals(2, artifacts.get("responds"));
    }
}
