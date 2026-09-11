package org.aethercode.runtime.message;

import java.util.Optional;

/**
 * Token usage information attached to an {@link AIMessage}.
 *
 * <p>Mirror of langchain's <code>usage_metadata</code>: input, output,
 * and total token counts. Total is computed if not provided.</p>
 */
public record Usage(
        Integer inputTokens,
        Integer outputTokens,
        Integer totalTokens
) {
    public Usage {
        if (totalTokens == null && inputTokens != null && outputTokens != null) {
            totalTokens = inputTokens + outputTokens;
        }
    }

    public Optional<Integer> inputTokensOpt()  { return Optional.ofNullable(inputTokens); }
    public Optional<Integer> outputTokensOpt() { return Optional.ofNullable(outputTokens); }
    public Optional<Integer> totalTokensOpt()  { return Optional.ofNullable(totalTokens); }

    public static Usage of(int input, int output) {
        return new Usage(input, output, input + output);
    }
}
