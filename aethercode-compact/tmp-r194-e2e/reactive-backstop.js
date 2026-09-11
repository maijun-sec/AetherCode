/**
 * Reactive backstop for `prompt_too_long` API errors (T-150 → T-152).
 *
 * Per `design.md §2.6` and `spec.md §2.5`:
 *
 * - When the LLM call rejects with a `prompt_too_long` API error, run
 *   a one-shot Layer 1 + Layer 3 combo, rebuild the prompt with the
 *   shrunken history, and retry the LLM call once.
 * - If the retry also fails, surface a structured error to the caller
 *   so the TUI can display it; the circuit breaker is notified so
 *   repeated failures trip auto-compaction.
 *
 * The backstop is intentionally small — it knows nothing about the
 * pipeline; the caller composes it with a `CompactPipeline` instance
 * via `llmCallWithBackstop`.
 */
import { buildPrompt } from "./layer3.js";
import { microCompact } from "./layer1.js";
/** Match patterns for the `prompt_too_long` API error. */
const PROMPT_TOO_LONG_PATTERNS = [
    /prompt[_ ]is?[ _]too[ _]long/i,
    /prompt[ _]too[ _]long/i,
    /maximum[ _]context[ _]length/i,
    /context[ _]length[ _]exceeded/i,
    /string[ _]too[ _]long/i,
    /input[ _]length[ _]exceeded/i,
];
/** Return the error message from an unknown thrown value. */
function errorMessage(err) {
    if (err instanceof Error) {
        return err.message;
    }
    if (typeof err === "string") {
        return err;
    }
    try {
        return JSON.stringify(err);
    }
    catch {
        return "unknown error";
    }
}
/**
 * Detect a `prompt_too_long` API error in a thrown value. Recognised
 * by message substring match (T-150). The match is intentionally
 * permissive — a well-formed provider error message is the common
 * case, but a JSON-stringified error is also handled.
 */
export function isPromptTooLong(err) {
    const message = errorMessage(err);
    for (const pattern of PROMPT_TOO_LONG_PATTERNS) {
        if (pattern.test(message)) {
            return true;
        }
    }
    return false;
}
/**
 * Wrap an LLM `complete` call with a reactive backstop: if the call
 * rejects with `prompt_too_long`, run Layer 1 to shrink the history,
 * rebuild the prompt, and retry the LLM call once. If the retry also
 * fails, throw `ReactiveBackstopExhaustedError` (T-152).
 *
 * The "Layer 1 + Layer 3" combo in `design.md §2.6` is interpreted
 * here as: Layer 1 = `microCompact` (zero-LLM body clear), Layer 3 =
 * the original LLM call (this function's `complete` argument) issued
 * again with the shrunken history. The backstop never issues a fresh
 * compact-summary LLM call of its own; the caller owns the prompt and
 * the retry. This keeps the retry count to one (T-151).
 */
export async function llmCallWithBackstop(args, options) {
    const now = options.now ?? Date.now;
    const attempts = [];
    const { input, callOptions, llm } = args;
    // Build the original prompt from the input.
    const prompt = buildPrompt(input.history, {
        promptTemplate: options.pipelineOptions.layer3PromptTemplate,
    });
    // First attempt.
    const firstStart = now();
    try {
        const text = await llm.complete(prompt, callOptions);
        return { text, model: callOptions.model ?? "", attempts };
    }
    catch (err) {
        if (!isPromptTooLong(err)) {
            throw err;
        }
        attempts.push({
            attempt: 0,
            trigger: "prompt_too_long",
            succeeded: false,
            elapsedMs: now() - firstStart,
            compactResult: null,
            error: errorMessage(err),
        });
    }
    // Backstop: shrink the history via Layer 1, rebuild the prompt,
    // and retry the same LLM call once. The shrink is recorded as
    // the attempt's `compactResult` so the TUI / tests can inspect it.
    const backstopStart = now();
    const shrunk = buildShrunkCompactInput(input, options.pipelineOptions, {
        now: options.now,
    });
    const retryPrompt = buildPrompt(shrunk.history, {
        promptTemplate: options.pipelineOptions.layer3PromptTemplate,
    });
    const shrunkCompactResult = {
        layer: 1,
        beforeTokens: 0,
        afterTokens: shrunk.inputTokens ?? 0,
        history: [...shrunk.history],
        toolResults: [...shrunk.toolResults],
        cacheReference: null,
        elapsedMs: now() - backstopStart,
    };
    const retryStart = now();
    try {
        const text = await llm.complete(retryPrompt, callOptions);
        if (options.circuitBreaker && options.sessionId) {
            options.circuitBreaker.recordSuccess(options.sessionId);
        }
        attempts.push({
            attempt: 1,
            trigger: "prompt_too_long",
            succeeded: true,
            elapsedMs: now() - retryStart,
            compactResult: shrunkCompactResult,
        });
        return { text, model: callOptions.model ?? "", attempts };
    }
    catch (err) {
        const attempt = {
            attempt: 1,
            trigger: "prompt_too_long",
            succeeded: false,
            elapsedMs: now() - retryStart,
            compactResult: shrunkCompactResult,
            error: errorMessage(err),
        };
        attempts.push(attempt);
        if (options.circuitBreaker && options.sessionId) {
            options.circuitBreaker.recordFailure(options.sessionId, "backstop_retry_exhausted");
        }
        throw new ReactiveBackstopExhaustedError(attempts);
    }
}
/** Error thrown when the backstop retry also fails. */
export class ReactiveBackstopExhaustedError extends Error {
    attempts;
    constructor(attempts) {
        const last = attempts[attempts.length - 1];
        const msg = last?.error ?? "backstop retry failed";
        super(`Reactive backstop exhausted: ${msg}`);
        this.name = "ReactiveBackstopExhaustedError";
        this.attempts = attempts;
    }
}
/**
 * Build a `CompactInput` whose history has been shrunk by Layer 1.
 * Used by pipeline drivers that want to issue a retry after a
 * `prompt_too_long` error without re-running the full pipeline.
 */
export function buildShrunkCompactInput(input, pipelineOptions, options = {}) {
    const layer1Opts = {
        clearThresholdBytes: pipelineOptions.layer1ClearThresholdBytes,
        cacheWarmWindowMs: pipelineOptions.cacheWarmWindowMs,
        now: options.now,
    };
    const layer1 = microCompact(input, layer1Opts);
    return {
        ...input,
        history: layer1.history,
        toolResults: layer1.toolResults,
        inputTokens: layer1.afterTokens,
    };
}
/**
 * Run a one-shot Layer 1+3 combo. Returns the Layer 1 result when
 * the LLM client is omitted (matches `CompactPipeline.compact`
 * semantics) or the Layer 3 result on success. The `circuitBreaker`
 * is notified on failure so the persistent warning can be displayed.
 */
export async function reactiveBackstopPass(input, pipelineOptions, options = {}) {
    const now = options.now ?? Date.now;
    const start = now();
    const layer1 = microCompact(input, {
        clearThresholdBytes: pipelineOptions.layer1ClearThresholdBytes,
        cacheWarmWindowMs: pipelineOptions.cacheWarmWindowMs,
        now: options.now,
    });
    if (!options.llm) {
        return { ...layer1, elapsedMs: now() - start };
    }
    try {
        // Defer to a dynamic import to avoid a cycle on `pipeline.ts`
        // (which itself imports from this module via the breaker).
        const { llmCompact } = await import("./layer3.js");
        const result = await llmCompact({
            ...input,
            history: layer1.history,
            toolResults: layer1.toolResults,
            inputTokens: layer1.afterTokens,
        }, options.llm, {
            maxOutputTokens: pipelineOptions.layer3MaxOutputTokens,
            promptTemplate: pipelineOptions.layer3PromptTemplate,
            recentKeep: pipelineOptions.layer3RecentKeep,
            now: options.now,
        });
        if (options.circuitBreaker && options.sessionId) {
            options.circuitBreaker.recordSuccess(options.sessionId);
        }
        return result;
    }
    catch (err) {
        if (options.circuitBreaker && options.sessionId) {
            options.circuitBreaker.recordFailure(options.sessionId, isPromptTooLong(err) ? "backstop_retry_exhausted" : "llm_error");
        }
        throw err;
    }
}
