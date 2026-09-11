package org.aethercode.workflows;

import java.util.Map;

/**
 * Pluggable bridge between the workflow engine and whatever
 * spawns sessions (the supervisor in production, a fake in
 * tests). The engine never depends on aethercode-tasks directly
 * — it talks to a {@code SessionSpawner}. This keeps the engine
 * unit-testable and lets Java-B's T-2-10 / T-2-11 work ship a
 * supervisor-backed implementation while the engine code in this
 * module stays unchanged.
 */
@FunctionalInterface
public interface SessionSpawner {

    /**
     * Spawn a session with the given prompt, cwd, model, and
     * optional config blob (limits + workflow metadata). Returns
     * a {@link SessionRef} whose id uniquely identifies the row.
     *
     * @param prompt  the final, substituted prompt to send to the
     *                LLM (system + user messages, joined with a
     *                double newline)
     * @param cwd     the working directory the session runs in
     * @param model   the model id (e.g. {@code "claude-opus-4-1"});
     *                may be null to use the supervisor's default
     * @param config  optional per-session config (limits map,
     *                workflow name, etc.)
     */
    SessionRef spawn(String prompt, String cwd, String model,
                     Map<String, Object> config);
}
