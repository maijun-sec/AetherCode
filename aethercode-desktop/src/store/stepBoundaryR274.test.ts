import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * R274 desktop polish (2026-09-16): R273 had
 * "every text_delta always splits a step" which was wrong —
 * the LLM streams a single think block as N small text_delta
 * chunks, and the "always split" rule collapsed each chunk into
 * its own step, rendering as a stack of one-line "思考 · xxx"
 * details cards the user called "现在的展示方式是在搞笑吗".
 *
 * <p>R274 introduces `prevEventWasText` instead of R267's
 * `pendingStepBoundary`. The new rule:
 * <ul>
 *   <li>consecutive text_deltas accumulate into the current step's
 *       text (same think phase)</li>
 *   <li>the first text_delta AFTER a tool_use_start / tool_result /
 *       run_start-from-tool triggers a step split (new think phase)</li>
 *   <li>run_start resets `prevEventWasText = true` so the very
 *       first text_delta of a run accumulates into the empty step
 *       run_start created</li>
 * </ul>
 *
 * <p>R274 strictly subsumes R267 (tool_result→text_delta split)
 * AND extends it (tool_use_start→text_delta split, which R267
 * missed when models emit text between parallel tool_use_starts).
 *
 * <p>These source-pin tests catch any future refactor that drops
 * one of the pieces:
 * <ol>
 *   <li>the text_delta case must check `prevEventWasText` and split
 *       only when false (NOT always split, that's the R273 bug)</li>
 *   <li>the text_delta case must accumulate (not split) when
 *       prevEventWasText is true</li>
 *   <li>tool_use_start and tool_result handlers must flip
 *       prevEventWasText to false so the next text_delta is a
 *       new think phase</li>
 *   <li>run_start must reset prevEventWasText to true so the
 *       first text_delta of a run accumulates into the empty
 *       step run_start created</li>
 * </ol>
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

function readSrc(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('R274: text_delta splits only when prev event was a tool', () => {
  it('declares a `let prevEventWasText: boolean = true` module flag', () => {
    const src = readSrc('src/store/index.ts');
    expect(
      src,
      'store must declare a prevEventWasText module flag — ' +
        'it tracks whether the previous stream_event was a text_delta ' +
        'so the next text_delta can decide between accumulate (same ' +
        'think phase) and split (new think phase).',
    ).toMatch(/let\s+prevEventWasText\s*:\s*boolean\s*=\s*true/);
  });

  it('text_delta handler splits only when `!prevEventWasText`', () => {
    // R274: the text_delta case checks `if (currentStepId && !prevEventWasText)`
    // and splits; otherwise accumulates. This is the fix for the
    // R273 regression where every chunk became its own one-line
    // "思考" details card.
    const src = readSrc('src/store/index.ts');
    const tdStart = src.indexOf("case 'text_delta'");
    expect(tdStart, 'text_delta case must exist').toBeGreaterThan(0);
    const tdBody = src.slice(tdStart, src.indexOf("case 'tool_use_start'", tdStart));
    expect(
      tdBody,
      'text_delta must gate split on `!prevEventWasText` — R273 ' +
        'split on every chunk and the user called it "搞笑" because ' +
        'each stream chunk became its own one-line "思考" card.',
    ).toMatch(/if\s*\(\s*currentStepId\s*&&\s*!prevEventWasText\s*\)/);
    expect(
      tdBody,
      'text_delta must accumulate (NOT split) when prevEventWasText is true — ' +
        'consecutive stream chunks of the same think phase belong in the same step.',
    ).toMatch(/st\.text\s*\+\s*text/);
    expect(
      tdBody,
      'text_delta must set prevEventWasText = true after consuming (so the ' +
        'next chunk in the same think phase also accumulates).',
    ).toMatch(/prevEventWasText\s*=\s*true/);
  });

  it('tool_use_start handler must set prevEventWasText = false', () => {
    // A tool_use_start breaks the current think phase. The next
    // text_delta opens a new step. Without this, R274's text_delta
    // gate (above) would let the next text_delta accumulate into
    // the previous step, which is wrong (the model just finished
    // thinking and is starting a new phase).
    const src = readSrc('src/store/index.ts');
    const m = src.match(/case\s+['"]tool_use_start['"]\s*:\s*\{[\s\S]*?prevEventWasText\s*=\s*false/);
    expect(
      m,
      'tool_use_start handler must set prevEventWasText = false — ' +
        'a tool boundary is the start of a new think phase, so the ' +
        'next text_delta must split, not accumulate.',
    ).not.toBeNull();
  });

  it('tool_result handler must set prevEventWasText = false', () => {
    // A tool_result completes a tool_use. The model now sees the
    // tool output and may start a new think phase. Same logic as
    // tool_use_start: the next text_delta is a new think phase.
    const src = readSrc('src/store/index.ts');
    const m = src.match(/case\s+['"]tool_result['"]\s*:\s*\{[\s\S]*?prevEventWasText\s*=\s*false/);
    expect(
      m,
      'tool_result handler must set prevEventWasText = false — ' +
        'after seeing a tool result, the model starts a new think ' +
        'phase, so the next text_delta must split, not accumulate.',
    ).not.toBeNull();
  });

  it('run_start handler must reset prevEventWasText to true', () => {
    // A new run always opens with an empty step (run_start creates
    // it). The first text_delta of the run must accumulate into
    // that empty step, NOT split off a new one. Without this reset,
    // the previous run's last tool_event could carry a stale
    // prevEventWasText=false across runs and make the first text
    // of run #2 split off an empty step.
    const src = readSrc('src/store/index.ts');
    const m = src.match(/case\s+['"]run_start['"]\s*:\s*\{[\s\S]*?prevEventWasText\s*=\s*true/);
    expect(
      m,
      'run_start handler must reset prevEventWasText = true — ' +
        'otherwise the first text_delta of run #2 could split off ' +
        'a phantom step from stale state.',
    ).not.toBeNull();
  });

  it('no R273 "always split" pattern remains', () => {
    // The R273 bug was an `if (currentStepId) { split }` with no
    // gate on `prevEventWasText`. R274 must gate on `!prevEventWasText`.
    // If a future refactor reintroduces the unconditional split, the
    // chunked-think regression returns.
    const src = readSrc('src/store/index.ts');
    const tdStart = src.indexOf("case 'text_delta'");
    const tdBody = src.slice(tdStart, src.indexOf("case 'tool_use_start'", tdStart));
    expect(
      tdBody,
      'text_delta must NOT unconditionally split when currentStepId is set — ' +
        'that was R273 and produced one "思考" card per stream chunk.',
    ).not.toMatch(/if\s*\(\s*currentStepId\s*\)\s*\{\s*\/\/.*R273/s);
    // Stronger check: the gate must reference prevEventWasText
    expect(tdBody).toMatch(/prevEventWasText/);
  });
});