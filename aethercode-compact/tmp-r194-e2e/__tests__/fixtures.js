/** Standard test model: 200K window, 8K output. */
export const TEST_MODEL = {
    name: "test-model",
    maxTokens: 200_000,
    maxOutputTokens: 8_000,
};
const EMPTY_MEMORY = {
    source: "cache",
    totalTokens: 0,
    truncated: false,
    entries: [],
};
/** Make a user message. */
export function userMsg(id, content, ts = 0) {
    return { id, role: "user", content, ts };
}
/** Make an assistant message. */
export function assistantMsg(id, content, ts = 0) {
    return { id, role: "assistant", content, ts };
}
/** Make a tool message (in history). */
export function toolMsg(id, toolName, body, toolCallId, ts = 0) {
    return {
        id,
        role: "tool",
        toolName,
        content: body,
        toolCallId: toolCallId ?? `${id}-call`,
        ts,
    };
}
/** Make a tool result (in the toolResults array). */
export function toolResult(id, toolName, body, toolCallId, ts = 0) {
    return {
        id,
        toolName,
        body,
        toolCallId: toolCallId ?? `${id}-call`,
        ts,
    };
}
/** Build a minimal CompactInput. */
export function makeInput(overrides = {}) {
    return {
        model: TEST_MODEL,
        globalMemory: EMPTY_MEMORY,
        projectMemory: EMPTY_MEMORY,
        sessionMemory: EMPTY_MEMORY,
        history: [],
        toolResults: [],
        cacheStatus: "cool",
        ...overrides,
    };
}
/** A valid 8-segment summary, for mock LLM responses. */
export const VALID_SUMMARY = {
    userTaskIntent: "ship aethercode-compact v0.1.0",
    projectContext: "aethercode-compact module under AetherCode root",
    approachTaken: "TypeScript strict + vitest + zod; 4-shot prompt with 8-segment schema",
    bugsAndFailures: "none yet",
    toolOutputsRetained: "'tsc -p tsconfig.json' clean; 'vitest run' 0 failures",
    decisionsAndTradeoffs: "TypeScript over Java for the JS-side runtime; zod over ajv for DX",
    openTodos: "Layer 2 supervisor integration (Phase 4)",
    nextStepPlan: "land PR and wire into aethercode-tui",
};
/**
 * A mock LLM client that returns a fixed response (raw or pre-validated
 * JSON). For more elaborate tests, pass a `respond` function.
 */
export function mockLlm(response = VALID_SUMMARY) {
    return {
        complete: async (prompt) => {
            void prompt;
            if (typeof response === "string") {
                return response;
            }
            return JSON.stringify(response);
        },
    };
}
/** A mock LLM client that wraps the response in markdown fences. */
export function fencedMockLlm() {
    return {
        complete: async () => "```json\n" + JSON.stringify(VALID_SUMMARY) + "\n```",
    };
}
/** A mock LLM client that wraps the response in <draft>...</draft><final>...</final>. */
export function draftFinalMockLlm() {
    return {
        complete: async () => `<draft>{"userTaskIntent":"wrong"}</draft><final>${JSON.stringify(VALID_SUMMARY)}</final>`,
    };
}
