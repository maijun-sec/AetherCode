package org.aethercode.core.runtime.llm.profiles;

import org.aethercode.core.runtime.llm.HarnessProfile;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Built-in OpenAI Codex harness profile.
 *
 * <p>Java-native port of
 * {@code deepagents.profiles.harness._openai_codex}. Registers a
 * {@link HarnessProfile} for each OpenAI Codex model spec with a
 * behavior-shaping {@code systemPromptSuffix} that aligns Deep
 * Agents' runtime defaults with how Codex was trained to operate
 * &mdash; autonomous senior engineer demeanor, bias to action,
 * parallel tool use, and TODO hygiene.</p>
 *
 * <p>The suffix is appended last to each assembled prompt, so it
 * layers cleanly on top of caller and profile content for the
 * main agent and each subagent's own authored base prompt.</p>
 *
 * <p>Per-model keys (not the {@code "openai"} prefix) keep the
 * default behavior of non-Codex OpenAI models unchanged.</p>
 */
public final class OpenAiCodexProfile {
    private OpenAiCodexProfile() {}

    /** Model specs that receive the Codex harness profile. */
    public static final List<String> CODEX_MODEL_SPECS = List.of(
            "openai:gpt-5.1-codex",
            "openai:gpt-5.2-codex",
            "openai:gpt-5.3-codex");

    public static final String SYSTEM_PROMPT_SUFFIX = ""
            + "## Codex-Specific Behavior\n"
            + "\n"
            + "- You are an autonomous senior engineer. Once given a direction, proactively gather context, plan, implement, and verify without waiting for additional prompts at each step.\n"
            + "- Persist until the task is fully handled end-to-end within the current turn whenever feasible. Do not stop at analysis or partial fixes; carry changes through implementation, verification, and a clear explanation of outcomes.\n"
            + "- Bias to action: default to implementing with reasonable assumptions. Do not end your turn with clarifications unless truly blocked.\n"
            + "- Do not communicate an upfront plan or status preamble before acting. Just act.\n"
            + "\n"
            + "## Parallel Tool Use\n"
            + "\n"
            + "- Before any tool call, decide ALL files and resources you will need.\n"
            + "- Batch reads, searches, and other independent operations into parallel tool calls instead of issuing them one at a time.\n"
            + "- Only make sequential calls when you truly cannot determine the next step without seeing a prior result.\n"
            + "\n"
            + "## Plan Hygiene\n"
            + "\n"
            + "- Before finishing, reconcile every TODO or plan item created via write_todos. Mark each as done, blocked (with a one-sentence reason), or cancelled. Do not finish with pending items.";

    /**
     * Build a fresh Codex behavioral Object list for each
     * assembled agent stack.
     *
     * <p>Includes {@link Object} (the {@code write_todos}
     * tool) since the Codex system prompt references reconciling
     * TODO/plan items via {@code write_todos}; the SDK no longer
     * provides it by default. A fresh instance per stack avoids
     * sharing state across stacks.</p>
     */
    public static List<Object> buildExtraMiddleware() {
        return List.of(new Object());
    }

    /**
     * Supplier variant for the
     * {@link HarnessProfile#extraMiddleware()} factory slot. Each
     * call returns a fresh list.
     */
    public static Supplier<List<Object>> extraMiddlewareSupplier() {
        return OpenAiCodexProfile::buildExtraMiddleware;
    }

    /** Register the built-in Codex harness profile for each Codex spec. */
    public static void register() {
        HarnessProfile profile = new HarnessProfile(
                null, SYSTEM_PROMPT_SUFFIX,
                Map.of(), Set.of(), Set.of(),
                buildExtraMiddleware(),
                null);
        for (String spec : CODEX_MODEL_SPECS) {
            HarnessProfile.registerHarnessProfile(spec, profile);
        }
    }
}
