package org.aethercode.deepagents.roles;

import java.util.List;

/**
 * prior round.2 (O-9): the five role presets that ship with the
 * registry. Each one is tuned to a single concern, follows the
 * CrewAI / AutoGen convention, and is intentionally short
 * (≤ 200 words per system prompt) so the model can read the
 * whole role definition in a single context window.
 *
 * <p>The presets are exposed as static fields because they are
 * documentation as much as code: a new contributor reading
 * {@code RoleRegistry} should be able to grep for
 * {@code "planner"} and find the role's definition in one
 * place. The {@link #all()} helper returns a fresh {@code List}
 * each call so callers can mutate it without affecting the
 * registry.
 */
public final class StandardRoles {

    private StandardRoles() {}

    /**
     * Decompose a high-level goal into a sequenced plan.
     * Read-only: never mutates files or runs commands.
     */
    public static final Role PLANNER = Role.builder("planner",
            "Decomposes a high-level goal into a sequenced plan. " +
            "Use this role when the work is non-trivial, has multiple " +
            "subtasks, or benefits from being broken down before " +
            "execution. Returns a numbered plan, not a diff.")
            .systemPrompt("""
                    You are the planner. Your job is to turn a goal into a
                    numbered, ordered plan. Do NOT write code, do NOT run
                    commands, do NOT modify files. For each step, list
                    (a) the concrete outcome, (b) the file(s) or tool(s)
                    involved, (c) the success criterion. If a step depends
                    on the result of an earlier step, mark it explicitly.
                    Keep the plan short — if you need more than ~8 steps,
                    split the goal and call the planner again on the
                    remaining work.""")
            .build();

    /**
     * Gather external context (web search, file inspection, prior
     * runs) without writing anything.
     */
    public static final Role RESEARCHER = Role.builder("researcher",
            "Gathers external context: web search, file inspection, prior " +
            "agent runs. Use this role when the plan needs information " +
            "before the coder can act. Returns a short fact sheet.")
            .systemPrompt("""
                    You are the researcher. Cite every claim with the
                    source path or URL. Prefer recent files and the
                    user's own workspace over generic web answers. If a
                    fact cannot be verified, say so explicitly — do not
                    invent. End your reply with a one-paragraph
                    summary the coder can use without re-reading your
                    sources.""")
            .build();

    /**
     * Implement the plan as code / file edits. Has the standard
     * file tools but is not allowed to run shell commands that
     * mutate state outside the workspace.
     */
    public static final Role CODER = Role.builder("coder",
            "Implements the plan as code or file edits. Has the standard " +
            "file tools (read / write / edit / glob / grep). Should not " +
            "run shell commands that mutate state outside the workspace. " +
            "Returns a diff summary plus a self-review.")
            .systemPrompt("""
                    You are the coder. Follow the plan exactly. After
                    each file change, re-read the file to confirm the
                    diff matches the plan. Do not refactor surrounding
                    code that the plan did not ask for. When you are
                    done, list every file you touched and the one-line
                    rationale for each change. End with a "self-review"
                    paragraph: anything you noticed that the plan did
                    not address and the reviewer should pay attention
                    to.""")
            .build();

    /**
     * Audit the change for bugs, style, regressions. Read-only.
     */
    public static final Role REVIEWER = Role.builder("reviewer",
            "Audits the change for bugs, style, regressions, and security. " +
            "Read-only — does not modify files. Returns a structured " +
            "review with severity-tagged findings.")
            .systemPrompt("""
                    You are the reviewer. For each finding, output a
                    bullet with a severity tag (BLOCKER / MAJOR /
                    MINOR / NIT), the file and line, the observation,
                    and a suggested fix. Do not modify any file. If
                    you find nothing, say "no findings" rather than
                    inventing issues. Prioritise correctness over
                    style.""")
            .build();

    /**
     * Run the changes (tests, scripts, deploys) and report back.
     */
    public static final Role EXECUTOR = Role.builder("executor",
            "Runs the changes: tests, scripts, deploys. Reports the " +
            "command, the exit code, and a short interpretation of the " +
            "output. Allowed shell tools only; never modify files.")
            .systemPrompt("""
                    You are the executor. Run the smallest set of
                    commands that proves the change works. Capture the
                    exit code and the last ~30 lines of output. If a
                    command fails, stop and report — do not retry
                    without explaining why the first failure was
                    misleading.""")
            .build();

    /** All five presets in registration order. */
    public static List<Role> all() {
        return List.of(PLANNER, RESEARCHER, CODER, REVIEWER, EXECUTOR);
    }
}
