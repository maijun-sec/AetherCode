import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * R267 desktop polish (2026-09-14): the user reported
 * "all thinking at top, all tools at bottom" — the chat
 * transcript always rendered as one big thinking block
 * followed by all tool calls, regardless of how many
 * model-thinks-between-tools cycles actually happened.
 *
 * <p>Root cause: a single ChatStep accumulates ALL
 * text_delta + ALL tool_use_start events for the entire
 * run, so {@code MessageList.buildBlocks} emits exactly
 * one [think][tool][tool]... group per sub-task.
 *
 * <p>Fix: the store tracks a module-private
 * {@code pendingStepBoundary} flag, set on every
 * {@code tool_result} event. The next {@code text_delta}
 * after a tool_result closes the current step and opens
 * a new one, producing the natural
 * "[think][tool][think][tool]..." render the user expects.
 *
 * <p>These source-pin tests catch any future refactor
 * that drops one of the four pieces:
 * <ol>
 *   <li>module-scope {@code pendingStepBoundary} flag declaration</li>
 *   <li>flag SET on tool_result</li>
 *   <li>flag CONSUMED on text_delta (split step when set)</li>
 *   <li>flag CLEARED on run_start (no carry-over from prior run)</li>
 * </ol>
 * A regression in any of these would re-introduce the
 * "stacked thinking" bug even if the chat still looks
 * fine in a smoke test.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

function readSrc(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('R267: step boundary on tool→text transition', () => {
  it('desktop store declares the pendingStepBoundary module flag', () => {
    const src = readSrc('src/store/index.ts');
    // The flag is the linchpin of the fix — without it the
    // text_delta handler has no way to know "was the last
    // event a tool_result?". A future refactor that moves
    // the state somewhere else (e.g. inside the store) MUST
    // keep this source-pin so the tests stay meaningful.
    expect(
      src,
      'store must declare a pendingStepBoundary module flag — ' +
        'without it the text_delta handler cannot detect the ' +
        'tool→text boundary and the user-visible bug returns',
    ).toMatch(/let\s+pendingStepBoundary\s*:\s*boolean\s*=\s*false/);
  });

  it('tool_result handler must set the pendingStepBoundary flag', () => {
    const src = readSrc('src/store/index.ts');
    // The tool_result case in the stream_event switch must
    // set pendingStepBoundary = true BEFORE the set() call
    // (so the next text_delta sees it). Search for the
    // exact pattern, not just the flag name — the bug
    // we'd regress to is forgetting the assignment, which
    // is silent in TypeScript.
    const m = src.match(/case\s+['"]tool_result['"]\s*:\s*\{[\s\S]*?pendingStepBoundary\s*=\s*true/);
    expect(
      m,
      'tool_result handler must assign pendingStepBoundary = true — ' +
        'without this the text_delta handler has no signal to split on',
    ).not.toBeNull();
  });

  it('text_delta handler must split step when the flag is set', () => {
    const src = readSrc('src/store/index.ts');
    // The split logic: when splitStep (captured BEFORE the
    // set call so we can clear the flag) is true AND a
    // current step exists, close the old step (done=true)
    // and push a new step carrying the new text. Without
    // this block, buildBlocks keeps emitting one think
    // + many tools per sub-task and the "stacked thinking"
    // bug returns.
    expect(
      src,
      'text_delta handler must capture the pendingStepBoundary flag ' +
        'into a local splitStep variable before the set() call',
    ).toMatch(/const\s+splitStep\s*=\s*pendingStepBoundary/);
    expect(
      src,
      'text_delta handler must clear pendingStepBoundary after consuming it',
    ).toMatch(/pendingStepBoundary\s*=\s*false/);
    expect(
      src,
      'text_delta handler must mark the old step done=true when splitting',
    ).toMatch(/done:\s*true[,\s]+endedAt:\s*Date\.now\(\)/);
    expect(
      src,
      'text_delta handler must open a new step with the captured text as its first content',
    ).toMatch(/text,?\s*toolEvents:\s*\[\][,\s]+counters:/);
  });

  it('run_start handler must clear the pendingStepBoundary flag', () => {
    const src = readSrc('src/store/index.ts');
    // A tool_result that arrived mid-stream (e.g. the
    // daemon delivered the last event after the user
    // started typing the next prompt) would leave the
    // flag set across runs. Without the run_start reset,
    // the first text_delta of run #2 would trigger a
    // phantom step split.
    const m = src.match(/case\s+['"]run_start['"]\s*:\s*\{[\s\S]*?pendingStepBoundary\s*=\s*false/);
    expect(
      m,
      'run_start handler must reset pendingStepBoundary to false — ' +
        'otherwise a stale flag from the previous run would split ' +
        'the first step of every new run',
    ).not.toBeNull();
  });
});