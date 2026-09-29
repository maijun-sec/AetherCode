package org.aethercode.core.eval;

/**
 * Minimal LLM completion interface used by the eval module's
 * {@link JudgeClient}.
 *
 * <p>This is intentionally a separate interface from the real
 * {@code org.aethercode.core.llm.ChatClient} because:
 * <ul>
 *   <li>The judge only needs a one-shot string-in/string-out call,
 *       not the streaming protocol ChatClient provides.</li>
 *   <li>Mock test fixtures can implement this trivially without
 *       pulling in the full LLM stack.</li>
 *   <li>Production code bridges via a tiny adapter that reads
 *       from a stream and joins the text blocks.</li>
 * </ul>
 *
 * <p>Implementations MUST be thread-safe; the harness may invoke
 * the judge from multiple worker threads.
 */
public interface LlmClient {

    /**
     * Run a one-shot completion and return the joined text.
     *
     * @param prompt   the full prompt (system + user concatenated)
     * @param maxTokens soft cap on output length; implementations
     *                 should truncate gracefully if hit
     * @return the model's text response; never null
     */
    String complete(String prompt, int maxTokens);
}