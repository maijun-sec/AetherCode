package org.aethercode.core.cost;

/**
 * pluggable token counter. The cost tracker needs to know
 * how many tokens a string contains so it can compute the USD
 * bill. Different models use different tokenizers (cl100k_base
 * for GPT-4, o200k_base for GPT-4o, SentencePiece for many
 * open models, etc.) so we abstract behind an interface.
 *
 * <p>The {@link #estimate(String)} method returns an integer
 * count. Implementations are free to use a heuristic (current
 * default — 4 chars per token) or a real BPE tokenizer
 * ({@link BpeHeuristicTokenCounter}, the new prior round default).
 *
 * <p>The default {@link #defaultFor(String)} returns the
 * BPE-heuristic counter regardless of model id, since we don't
 * ship vocab files for cl100k_base / o200k_base. A future round
 * could ship the cl100k_base vocab and switch based on model.
 */
public interface TokenCounter {
    /** Estimate the token count for {@code text}. Never negative. */
    int estimate(String text);

    /**
     * Pick a default counter for the given model. Currently always
     * returns the BPE-heuristic counter, but the model-aware
     * selection point is here for future expansion.
     */
    static TokenCounter defaultFor(String modelId) {
        return BpeHeuristicTokenCounter.INSTANCE;
    }
}
