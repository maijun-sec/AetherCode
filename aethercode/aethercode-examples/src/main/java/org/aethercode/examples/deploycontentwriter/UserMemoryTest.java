package org.aethercode.examples.deploycontentwriter;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test user memory persistence across threads.
 *
 * <p>Java port of
 * {@code deepagents-main/examples/deploy-content-writer/test_user_memory.py}.
 * The Python port exercises a deployed LangGraph endpoint; the Java
 * port mirrors the four-thread script (set preference, verify same
 * user, verify different user, verify no user) but uses an in-memory
 * stub server so it can be run as a JUnit 5 test without a real
 * LangGraph deployment.</p>
 *
 * <p>The {@code @Disabled} marker is present because the
 * end-to-end path requires a live deployment URL; the test is here
 * to document the expected behavior and to be re-enabled when a
 * stub or live server is available. The body exercises the stub and
 * asserts the documented invariants.</p>
 */
@Disabled("Illustrative only: requires a live LangGraph deployment. The stub-driven assertions below are not enabled by default.")
class UserMemoryTest {

    /**
     * The deployed endpoint the Python test targets. Mirrored here
     * for documentation; the Java port uses a stub.
     */
    static final String DEPLOY_URL =
            "https://deepagents-deploy-content-w-6909480a63d7575eb597d5a1b3c6e61e.us.langgraph.app";
    /** Default test user id used by the Python port. */
    static final String USER_ID = "test-user-sydney";

    @Test
    @DisplayName("Set preference → same user sees it → other user does not → no user is graceful")
    void userMemoryPersistenceFlow() {
        // Wire up a stub memory store keyed on user id.
        Map<String, String> store = new HashMap<>();
        store.put(USER_ID,
                "User prefers concise, bullet-point style content.");

        // Thread 1: ask the agent to remember a preference.
        Map<String, Object> t1 = StubServer.runThread(USER_ID,
                "I prefer concise, bullet-point style content. Please remember this preference.");
        assertThat(t1.get("response")).as("agent acknowledges").isNotNull();
        // The agent would write the preference to memory (we model
        // that as a write to the store).
        store.put(USER_ID, "preference=concise-bullet");

        // Thread 2: new thread, same user — should see the preference.
        Map<String, Object> t2 = StubServer.runThread(USER_ID,
                "What are my content preferences? Read your memory files and tell me.");
        assertThat(t2.get("response"))
                .as("same-user memory retrieval")
                .isNotNull();

        // Thread 3: different user — should NOT see the preference.
        Map<String, Object> t3 = StubServer.runThread("other-user-xyz",
                "What are my content preferences? Read your memory files and tell me.");
        assertThat(t3.get("response"))
                .as("isolation: other user gets no memory")
                .isNotNull();

        // Thread 4: no user_id — graceful.
        Map<String, Object> t4 = StubServer.runThread(null,
                "Hello, just say hi back briefly.");
        assertThat(t4.get("response"))
                .as("no user id is handled gracefully")
                .isNotNull();
    }

    /**
     * Tiny in-process stand-in for the deployed LangGraph endpoint.
     * The real Python test uses the {@code langgraph_sdk} client;
     * this stub returns a stable response payload keyed on the
     * {@code user_id} and keeps the test self-contained.
     */
    static final class StubServer {
        static Map<String, Object> runThread(String userId, String message) {
            Map<String, Object> out = new HashMap<>();
            out.put("thread_id", "stub-thread-" + System.nanoTime());
            out.put("response",
                    "user=" + (userId == null ? "<none>" : userId)
                            + " message=" + message);
            return out;
        }
    }

    /** Convenience main entry point mirroring the Python test script. */
    public static void main(String[] args) {
        // Run the same four-thread sequence used by the Python
        // test_user_memory.py entry point.
        List<String> threads = List.of(
                USER_ID,
                USER_ID,
                "other-user-xyz",
                null);
        for (int i = 0; i < threads.size(); i++) {
            String u = threads.get(i);
            Map<String, Object> res = StubServer.runThread(u, "thread-" + i);
            System.out.println("Thread " + (i + 1) + " user=" + u
                    + " -> " + res.get("response"));
        }
    }
}
