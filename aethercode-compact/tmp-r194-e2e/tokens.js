/**
 * Token estimation helpers.
 *
 * We use a simple character-based heuristic (~4 chars per token) that is
 * fast, deterministic, and good enough for budget decisions. A real token
 * counter (e.g. tiktoken) can be plugged in via `PipelineOptions` later.
 */
const CHARS_PER_TOKEN = 4;
/** Estimate the token count of an arbitrary string. */
export function estimateTokens(input) {
    if (input.length === 0) {
        return 0;
    }
    return Math.ceil(input.length / CHARS_PER_TOKEN);
}
/** Estimate the token count of a single message. */
export function estimateMessageTokens(message) {
    if (typeof message.tokens === "number" && message.tokens >= 0) {
        return message.tokens;
    }
    let total = estimateTokens(message.content);
    if (message.toolName) {
        total += estimateTokens(message.toolName);
    }
    return total;
}
/** Estimate the token count of a single tool result. */
export function estimateToolResultTokens(result) {
    if (typeof result.tokens === "number" && result.tokens >= 0) {
        return result.tokens;
    }
    return estimateTokens(result.body) + estimateTokens(result.toolName);
}
/** Estimate the total prompt token count from history + tool results. */
export function estimateInputTokens(history, toolResults) {
    let total = 0;
    for (const m of history) {
        total += estimateMessageTokens(m);
    }
    for (const r of toolResults) {
        total += estimateToolResultTokens(r);
    }
    return total;
}
