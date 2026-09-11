/**
 * Layer 1 — Microcompact.
 *
 * Per `design.md §2.2` and `spec.md §2.2`:
 *
 * 1. Find every tool message whose tool name is in `READ_CLASS_TOOL_NAMES`.
 * 2. If the body is over `T` bytes (default 4 KiB), replace the body with
 *    the literal `[Old tool result content cleared]`.
 * 3. Keep `TodoWrite` / `TodoRead` outputs intact.
 * 4. If the cache is warm (last assistant < `cacheWarmWindowMs` ago), do
 *    NOT modify the local message — return a `cacheReference` directive
 *    so the API server can drop the body server-side and preserve the
 *    prefix cache.
 *
 * This module is the building block the `CompactPipeline` uses. It can
 * also be called directly for pre-flight checks.
 */
import { CLEARED_PLACEHOLDER, READ_CLASS_TOOL_NAMES, } from "./types.js";
import { estimateInputTokens } from "./tokens.js";
/** Decide whether the local cache is "warm" given the input. */
export function isCacheWarm(input, cacheWarmWindowMs, now = Date.now) {
    if (input.cacheStatus === "warm") {
        return true;
    }
    if (input.cacheStatus === "cool") {
        return false;
    }
    if (typeof input.lastAssistantTs !== "number") {
        return false;
    }
    return now() - input.lastAssistantTs < cacheWarmWindowMs;
}
/** Test if a tool name is in the read-class set. */
export function isReadClassTool(toolName) {
    if (!toolName) {
        return false;
    }
    return READ_CLASS_TOOL_NAMES.has(toolName);
}
/** Test if a tool result's body is over the threshold. */
export function shouldClearBody(body, thresholdBytes) {
    // UTF-16 code units are close enough to bytes for the 4 KiB threshold;
    // a multi-byte character will still trigger the clear if its expanded
    // size exceeds the limit. For accuracy, callers can pre-compute byte
    // size via `Buffer.byteLength(body, "utf8")`.
    return body.length > thresholdBytes;
}
/**
 * Return a shallow-cloned message with the body replaced by the placeholder.
 * Idempotent: already-cleared messages pass through unchanged.
 */
function clearMessageBody(message) {
    if (message.cleared === true || message.content === CLEARED_PLACEHOLDER) {
        return message;
    }
    return {
        ...message,
        content: CLEARED_PLACEHOLDER,
        cleared: true,
        tokens: 0,
    };
}
/** Return a shallow-cloned tool result with the body cleared. */
function clearToolResultBody(result) {
    if (result.cleared === true || result.body === CLEARED_PLACEHOLDER) {
        return result;
    }
    return {
        ...result,
        body: CLEARED_PLACEHOLDER,
        cleared: true,
        tokens: 0,
    };
}
/**
 * Collect the tool-call ids whose bodies were replaced. Both the message-id
 * and the toolCallId are returned so callers can build a `cache_reference`
 * directive.
 */
function collectClearedIds(messages) {
    const out = [];
    for (const m of messages) {
        if (m.role === "tool" && m.content === CLEARED_PLACEHOLDER) {
            out.push(m.toolCallId ?? m.id);
        }
    }
    return out;
}
/**
 * Build a `cache_reference` directive from the warmed tool messages.
 * The format mirrors the API-server convention; concrete transport is
 * the caller's responsibility (T-113 will wire it into the LLM call).
 */
export function buildCacheReference(messages) {
    const ids = collectClearedIds(messages);
    if (ids.length === 0) {
        return "";
    }
    return `cache_reference:drop=${ids.join(",")}`;
}
/**
 * Run the Layer 1 microcompact pass.
 *
 * Returns a `CompactResult` with `layer: 1`. If the cache is warm, the
 * local `history` and `toolResults` are returned unchanged and
 * `cacheReference` is set to a directive string the API server can act on.
 * Otherwise, large read-class tool bodies are replaced with the
 * placeholder.
 */
export function microCompact(input, options) {
    const start = options.now ? options.now() : Date.now();
    const beforeTokens = typeof input.inputTokens === "number"
        ? input.inputTokens
        : estimateInputTokens(input.history, input.toolResults);
    const warm = isCacheWarm(input, options.cacheWarmWindowMs, options.now);
    if (warm) {
        // Cache-warm branch: do not modify local state. Emit a
        // `cache_reference` directive listing every read-class tool result
        // id so the API server can drop the bodies server-side and we keep
        // the prefix cache.
        const warmIds = collectWarmReadClassIds(input.history, input.toolResults);
        const cacheReference = warmIds.length > 0 ? `cache_reference:drop=${warmIds.join(",")}` : null;
        return {
            layer: 1,
            beforeTokens,
            afterTokens: beforeTokens,
            history: [...input.history],
            toolResults: [...input.toolResults],
            cacheReference,
            elapsedMs: (options.now ? options.now() : Date.now()) - start,
            clearedToolResultIds: warmIds.length > 0 ? warmIds : undefined,
        };
    }
    // Cool / unknown cache: edit locally.
    const newHistory = [];
    const clearedIds = [];
    for (const m of input.history) {
        if (m.role === "tool" && isReadClassTool(m.toolName) && shouldClearBody(m.content, options.clearThresholdBytes)) {
            newHistory.push(clearMessageBody(m));
            clearedIds.push(m.toolCallId ?? m.id);
        }
        else {
            newHistory.push({ ...m });
        }
    }
    const newToolResults = [];
    for (const r of input.toolResults) {
        if (isReadClassTool(r.toolName) && shouldClearBody(r.body, options.clearThresholdBytes)) {
            newToolResults.push(clearToolResultBody(r));
            clearedIds.push(r.id);
        }
        else {
            newToolResults.push({ ...r });
        }
    }
    const afterTokens = estimateInputTokens(newHistory, newToolResults);
    const end = options.now ? options.now() : Date.now();
    return {
        layer: 1,
        beforeTokens,
        afterTokens,
        history: newHistory,
        toolResults: newToolResults,
        cacheReference: null,
        elapsedMs: end - start,
        clearedToolResultIds: clearedIds.length > 0 ? clearedIds : undefined,
    };
}
/**
 * Collect the ids of every read-class tool message / result. Used by the
 * warm-cache branch to build a `cache_reference` directive — the API
 * server decides which bodies to actually drop.
 */
function collectWarmReadClassIds(history, results) {
    const ids = [];
    for (const m of history) {
        if (m.role === "tool" && isReadClassTool(m.toolName)) {
            ids.push(m.toolCallId ?? m.id);
        }
    }
    for (const r of results) {
        if (isReadClassTool(r.toolName)) {
            ids.push(r.toolCallId || r.id);
        }
    }
    return ids;
}
