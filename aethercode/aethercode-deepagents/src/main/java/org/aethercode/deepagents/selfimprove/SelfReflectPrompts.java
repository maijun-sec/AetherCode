package org.aethercode.deepagents.selfimprove;

/**
 * R241.2 (O-3): the system-prompt template the reflector
 * sends to the chat model. Kept here (not inside
 * {@link SelfReflectMiddleware}) so it can be swapped by
 * the host application without rewriting the middleware.
 */
public final class SelfReflectPrompts {

    private SelfReflectPrompts() {}

    /**
     * Default reflection prompt. Mirrors the canonical
     * <em>Reflexion</em> pattern (Shinn et al., 2023, paper
     * 4 §11.1): ask the model to verbalise a single
     * "what went wrong + what to do next time" pair, and
     * return it in a fixed key:value shape so the parser
     * can ingest it without an extra LLM call.
     */
    public static final String DEFAULT_SYSTEM_PROMPT = """
            You are the self-reflector for a coding agent.

            Given a tool-call failure, you produce ONE short
            reflection that the agent will read on its next
            attempt at the same kind of task. Your output is
            parsed by a regex (key: value), so every field
            must be on its own line and may not wrap.

            Required output shape (one line per key, exactly):
              error_pattern: <one line describing the failure pattern>
              fix_strategy: <one line describing what to do next time>
              example: <short transcript excerpt, may be empty>

            Be specific. "Make sure the file exists" is bad.
            "Run git status before file_edit on a path under
            a git worktree, because the cwd-aware path
            resolver will reject paths outside the worktree"
            is good.

            Do not include other keys. Do not write prose.
            Do not start with 'I' or 'The agent' — start with
            the action ("Run", "Check", "Set", "Avoid", ...).""";
}
