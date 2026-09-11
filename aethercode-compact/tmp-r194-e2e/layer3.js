/**
 * Layer 3 — Full LLM-driven compact (8-segment structured summary).
 *
 * Per `design.md §2.4` and `spec.md §2.3`:
 *
 * - The LLM is sent a 4-shot prompt asking for an 8-segment JSON object
 *   (field names and order are fixed).
 * - The response is validated against `compactSummarySchema` (Zod).
 * - The validated summary is attached to the returned `CompactResult` as
 *   `summary`. The actual history replacement (T-135) is layered on top.
 *
 * Round 2 (T-132–T-136) extends the prompt + response handling:
 * - The LLM is asked to emit a 2-step `<draft>...</draft>` then
 *   `<final>...</final>` (anti-drift). `extractJsonFromResponse` drops
 *   the draft and keeps the final.
 * - The prompt explicitly asks the model to **quote original phrases**
 *   verbatim — the anchor phrases the next turn depends on.
 * - `llmCompact` now replaces the history with the summary + the last
 *   `layer3RecentKeep` user messages, instead of returning the input
 *   history untouched.
 * - `enforceSegmentCap` post-trims any segment that slipped past the
 *   ~1K-token ceiling (defense in depth on top of the Zod cap).
 */
import { compactSummarySchema, MAX_SEGMENT_CHARS, SEGMENT_FIELD_NAMES } from "./schema.js";
import { estimateInputTokens, estimateMessageTokens } from "./tokens.js";
/** The default 4-shot prompt template.
 *
 *  The template uses `{history}` and `{schema}` placeholders. The four
 *  shots demonstrate the expected shape on progressively-novel contexts.
 *  Shot text is kept terse to save input budget.
 *
 *  The template asks for a 2-step `<draft>...</draft>` then
 *  `<final>...</final>` (T-132) and reminds the model to quote original
 *  phrases verbatim (T-134). The draft is dropped at injection by
 *  `extractJsonFromResponse` (T-133).
 */
export const DEFAULT_4_SHOT_PROMPT = `You are a conversation summarizer for a long-running AI coding agent.

Your output MUST be a single JSON object with exactly 8 fields, in this
exact order, each capped at ~1K tokens. Quote original phrases verbatim
when referencing specific facts (anchors).

Two-step generation (anti-drift):
  <draft>...</draft>   - working notes, can be rough
  <final>...</final>   - the final 8-segment JSON object
Only the <final> JSON is used; the draft is dropped at injection.

Schema:
{schema}

Rules:
- Output JSON only. No prose, no markdown fences, no commentary.
- Every field is a non-empty string. Do not use null or objects.
- If a section has nothing to report, write "none" — do not omit the field.
- Quote original phrases verbatim (T-134). Exact error messages, file
  paths, function names, and command strings are anchors the next turn
  relies on. Prefer the exact source string over a paraphrase.
- Keep each segment under ~1K tokens. If a section would overflow,
  abbreviate the oldest facts first; never drop the schema field.
- The total response (draft + final) must fit within the model's output
  budget.

--- BEGIN SHOT 1 (small task) ---
History:
USER: list the files in /tmp
TOOL: a.ts b.ts c.ts
ASSISTANT: a.ts, b.ts, c.ts
---
Output:
<draft>user wanted /tmp listing; tool returned three files; assistant listed them</draft>
<final>{"userTaskIntent":"list files in /tmp","projectContext":"/tmp: 3 files","approachTaken":"glob; listed 3 .ts files","bugsAndFailures":"none","toolOutputsRetained":"a.ts, b.ts, c.ts","decisionsAndTradeoffs":"returned plain list","openTodos":"none","nextStepPlan":"await follow-up"}</final>

--- BEGIN SHOT 2 (build failure) ---
History:
USER: npm run build
TOOL: error TS2304: cannot find name 'foo' at src/bar.ts:12
ASSISTANT: import { foo } from "./baz"
TOOL: error TS2307: cannot find module "./baz"
ASSISTANT: rename baz to baz.ts
TOOL: build ok
---
Output:
<draft>build failed with TS2304 then TS2307; fixed by adding .ts extension; tests not yet run</draft>
<final>{"userTaskIntent":"fix build","projectContext":"src/bar.ts imports missing module","approachTaken":"trace import path; add .ts extension","bugsAndFailures":"TS2304 then TS2307 — both fixed by extension","toolOutputsRetained":"'cannot find name foo' at src/bar.ts:12","decisionsAndTradeoffs":"chose .ts extension over barrel file","openTodos":"none","nextStepPlan":"continue with npm test"}</final>

--- BEGIN SHOT 3 (refactor across 3 files) ---
History:
USER: extract cache helpers from App.tsx into cache.ts
ASSISTANT: created src/cache.ts, updated App.tsx imports
TOOL: tests pass
USER: also extract logger
ASSISTANT: created src/logger.ts; updated imports
TOOL: tests pass
USER: commit
ASSISTANT: git add -A && git commit -m "extract cache + logger"
TOOL: 1 file changed, 80 insertions(+), 30 deletions(-)
---
Output:
<draft>split App.tsx into cache.ts then logger.ts; one commit, tests passed both times; PR not opened</draft>
<final>{"userTaskIntent":"split App.tsx into cache.ts + logger.ts","projectContext":"App.tsx was monolithic; helpers extracted","approachTaken":"incremental extraction: cache first, then logger; run tests after each; commit at end","bugsAndFailures":"none","toolOutputsRetained":"'1 file changed, 80 insertions(+), 30 deletions(-)'","decisionsAndTradeoffs":"kept extraction in one commit for atomic review","openTodos":"open PR","nextStepPlan":"push branch and open PR"}</final>

--- BEGIN SHOT 4 (long debugging session) ---
History:
[40 turns of attempts to fix a flaky integration test]
---
Output:
<draft>race in fixture teardown; event loop not closed; chose explicit lock over pytest-asyncio mode=auto; need to revert conftest.py refactor and rerun 5x</draft>
<final>{"userTaskIntent":"fix intermittent failure in test_integration.py::test_payment_retry","projectContext":"payments service uses retry; tests mock time.sleep","approachTaken":"1) added retry counter 2) mocked time.sleep 3) discovered race in test fixture 4) introduced event loop lock","bugsAndFailures":"race in fixture teardown — EventLoop not closed before next test","toolOutputsRetained":"'RuntimeError: Event loop is closed' at test_integration.py:142","decisionsAndTradeoffs":"chose explicit lock over pytest-asyncio mode='auto' for clarity","openTodos":"revert unrelated refactor in conftest.py","nextStepPlan":"rerun full suite 5x to confirm stability"}</final>

--- BEGIN HISTORY TO COMPRESS ---
{history}
--- END HISTORY ---

Output the draft, then the final, now:`;
/** Build the system portion of the prompt — the schema reminder. */
export function buildSchemaReminder() {
    const lines = [
        "{",
        `  "userTaskIntent":       string,  // 1. User task intent`,
        `  "projectContext":       string,  // 2. Project context (files, modules)`,
        `  "approachTaken":        string,  // 3. Approach tried, what worked`,
        `  "bugsAndFailures":      string,  // 4. Bugs/failures and their fixes`,
        `  "toolOutputsRetained":  string,  // 5. Tool outputs we want to keep`,
        `  "decisionsAndTradeoffs":string,  // 6. Decisions + trade-offs`,
        `  "openTodos":            string,  // 7. Pending todos`,
        `  "nextStepPlan":         string   // 8. Next-step plan`,
        "}",
    ];
    return lines.join("\n");
}
/** Serialize a list of messages into a single string for the prompt. */
export function renderHistoryForPrompt(history) {
    const out = [];
    for (const m of history) {
        const tag = m.toolName ? `TOOL(${m.toolName})` : m.role.toUpperCase();
        out.push(`${tag}: ${m.content}`);
    }
    return out.join("\n");
}
/** Build the full prompt that is sent to the LLM. */
export function buildPrompt(history, options = {}) {
    const template = options.promptTemplate ?? DEFAULT_4_SHOT_PROMPT;
    return template
        .replace("{schema}", buildSchemaReminder())
        .replace("{history}", renderHistoryForPrompt(history));
}
/**
 * Extract a JSON object from a model response. The model is supposed to
 * output raw JSON; if it wraps the JSON in markdown fences, those are
 * stripped. If the model emits a `<draft>...</draft>` followed by
 * `<final>...</final>` (T-132 — anti-drift trick), the draft is dropped
 * and the final is returned.
 */
export function extractJsonFromResponse(text) {
    // Drop <draft>...</draft> if present; keep <final>...</final>.
    const finalMatch = /<final>([\s\S]*?)<\/final>/.exec(text);
    if (finalMatch && finalMatch[1]) {
        return finalMatch[1].trim();
    }
    // Strip markdown code fences.
    const fence = /```(?:json)?\s*([\s\S]*?)```/.exec(text);
    if (fence && fence[1]) {
        return fence[1].trim();
    }
    // Last resort: take the first {...} block.
    const firstBrace = text.indexOf("{");
    const lastBrace = text.lastIndexOf("}");
    if (firstBrace >= 0 && lastBrace > firstBrace) {
        return text.slice(firstBrace, lastBrace + 1);
    }
    return text.trim();
}
/** Validate a raw JSON string against the 8-segment schema. */
export function parseAndValidate(raw) {
    const json = extractJsonFromResponse(raw);
    const parsed = JSON.parse(json);
    return compactSummarySchema.parse(parsed);
}
/** Convert the Zod schema into a plain object — used for snapshot tests. */
export function describeSchema() {
    return {
        name: "CompactSummary",
        fields: [...SEGMENT_FIELD_NAMES],
    };
}
/**
 * Per-segment 1K-token cap enforcement (T-136).
 *
 * Trims any segment string that exceeds `MAX_SEGMENT_CHARS` to the cap
 * and appends a `…(truncated)` marker so the reader knows content was
 * dropped. The Zod schema already enforces the cap at parse time, so
 * this is a defense-in-depth pass for callers that re-summarize or
 * accept summary payloads from outside the validated path.
 */
export function enforceSegmentCap(summary) {
    const out = { ...summary };
    let trimmed = false;
    for (const key of SEGMENT_FIELD_NAMES) {
        const v = out[key];
        if (v.length > MAX_SEGMENT_CHARS) {
            out[key] = v.slice(0, MAX_SEGMENT_CHARS - "…(truncated)".length) + "…(truncated)";
            trimmed = true;
        }
    }
    return trimmed ? out : summary;
}
/**
 * Build a single `summary` role message that embeds the 8-segment JSON
 * plus a human-readable header. The id is deterministic so two passes
 * over the same history produce a comparable id (used by tests).
 */
function buildSummaryMessage(summary, ts) {
    const body = JSON.stringify(summary, null, 2);
    return {
        id: `summary:${ts}`,
        role: "summary",
        content: `[summary] 8-segment compaction\n${body}`,
        ts,
    };
}
/**
 * Replace the entire history (except the last `keep` user messages)
 * with a single `summary` role message containing the 8-segment
 * payload (T-135). Messages after the (N)th-from-last user message —
 * including any trailing assistant / tool messages — are kept verbatim
 * so the next turn can resolve `tool_use` ↔ `tool_result` pairs that
 * straddle the boundary.
 *
 * If `keep <= 0` the entire history is replaced. If the history
 * contains fewer than `keep` user messages, only the summary is
 * returned.
 */
export function replaceHistoryKeepingRecent(history, summary, keep, options = {}) {
    const safeKeep = Math.max(0, Math.floor(keep));
    const now = options.now ?? Date.now;
    const ts = now();
    if (safeKeep === 0) {
        return [buildSummaryMessage(summary, ts)];
    }
    // Find the index of the (N)th-from-last user message. Everything
    // from that index onward is kept; everything before is replaced.
    const userIndexes = [];
    for (let i = 0; i < history.length; i++) {
        const m = history[i];
        if (m && m.role === "user") {
            userIndexes.push(i);
        }
    }
    if (userIndexes.length <= safeKeep) {
        // Not enough user messages to keep anything — replace everything.
        return [buildSummaryMessage(summary, ts)];
    }
    const cutoff = userIndexes[userIndexes.length - safeKeep];
    if (typeof cutoff !== "number") {
        return [buildSummaryMessage(summary, ts)];
    }
    const head = [];
    for (let i = 0; i < cutoff; i++) {
        const m = history[i];
        if (m) {
            head.push({ ...m });
        }
    }
    const tail = [];
    for (let i = cutoff; i < history.length; i++) {
        const m = history[i];
        if (m) {
            tail.push({ ...m });
        }
    }
    return [buildSummaryMessage(summary, ts), ...tail];
}
/**
 * Run the Layer 3 LLM compact pass. The result has `layer: 3`, includes
 * the validated 8-segment summary, and the history is replaced with the
 * summary + the trailing `options.recentKeep` user messages
 * (T-135). Pass `options.skipHistoryReplace` to opt out and get the
 * raw input history back (used by tests that focus on the summary
 * shape).
 */
export async function llmCompact(input, llm, options) {
    const start = options.now ? options.now() : Date.now();
    const beforeTokens = typeof input.inputTokens === "number"
        ? input.inputTokens
        : estimateInputTokens(input.history, input.toolResults);
    const prompt = buildPrompt(input.history, { promptTemplate: options.promptTemplate });
    const raw = await llm.complete(prompt, {
        maxTokens: options.maxOutputTokens,
        model: input.model.name,
    });
    const parsed = parseAndValidate(raw);
    // Defense in depth: the Zod cap already bounds each segment to
    // `MAX_SEGMENT_CHARS`, but `enforceSegmentCap` re-asserts the
    // ceiling and trims-with-marker if anything slipped through.
    const summary = enforceSegmentCap(parsed);
    // Lightweight estimation of the summary's token footprint for accounting.
    let summaryTokens = 0;
    for (const key of SEGMENT_FIELD_NAMES) {
        summaryTokens += estimateMessageTokens({
            id: `summary:${key}`,
            role: "summary",
            content: summary[key],
            ts: 0,
        });
    }
    const newHistory = options.skipHistoryReplace
        ? [...input.history]
        : replaceHistoryKeepingRecent(input.history, summary, options.recentKeep, {
            now: options.now,
        });
    const end = options.now ? options.now() : Date.now();
    return {
        layer: 3,
        beforeTokens,
        afterTokens: summaryTokens,
        history: newHistory,
        toolResults: [...input.toolResults],
        cacheReference: null,
        elapsedMs: end - start,
        summary,
    };
}
/**
 * Static assertion that the 8-segment schema is well-formed.
 * Exposed so the pipeline can fail-fast at construction time if a
 * future edit accidentally drops a field.
 */
export function assertSchemaShape() {
    const sample = {
        userTaskIntent: "x",
        projectContext: "x",
        approachTaken: "x",
        bugsAndFailures: "x",
        toolOutputsRetained: "x",
        decisionsAndTradeoffs: "x",
        openTodos: "x",
        nextStepPlan: "x",
    };
    compactSummarySchema.parse(sample);
}
export { compactSummarySchema };
