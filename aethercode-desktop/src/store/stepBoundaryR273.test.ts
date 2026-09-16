import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * R273 desktop polish (2026-09-16): the user reported
 * "all thinking at top, all tools at bottom" — the chat
 * transcript rendered as one big thinking block followed
 * by all tool calls, regardless of how many
 * model-thinks-between-tools cycles actually happened.
 *
 * <p>R267 was the first attempt: a `pendingStepBoundary`
 * module flag, set on every tool_result and consumed by
 * the next text_delta. That only worked when the model
 * interleaved a single text chunk between each tool call;
 * a long think followed by many parallel tool calls still
 * collapsed into one big think + many tools underneath.
 *
 * <p>R273 fix: every text_delta ALWAYS opens a fresh step
 * carrying the new text. Tools arriving after this
 * text_delta and before the next text_delta land in this
 * new step's toolEvents, so buildBlocks emits the natural
 * alternating "[think][tool][think][tool]…" pattern in
 * the order the LLM actually produced them.
 *
 * <p>These source-pin tests catch any future refactor that
 * drops one of the pieces:
 * <ol>
 *   <li>the text_delta case must always close the previous
 *       step (marking it done) regardless of any flag state</li>
 *   <li>the text_delta case must open a new step with the
 *       captured text as its first content</li>
 *   <li>no module-scope `pendingStepBoundary` flag remains
 *       (the R267 flag is dead code — its two setters
 *       (tool_result + run_start) and its text_delta consumer
 *       were all removed). A future refactor that re-introduces
 *       the flag without restoring the round-trip semantics
 *       would create the old "all thinking at top" bug.</li>
 * </ol>
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

function readSrc(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('R273: text_delta always opens a fresh step', () => {
  it('text_delta handler must always close the previous step (no flag gating)', () => {
    const src = readSrc('src/store/index.ts');
    // Inside the text_delta case, when there's a current step,
    // we always close it (done=true, endedAt: Date.now()) and
    // push a new one. There must be no `if (splitStep …)`
    // dependency on the R267 module flag — R273 strictly
    // subsumes it by always splitting.
    // Locate the text_delta case body (until the next `case …` or `break;`).
    const tdStart = src.indexOf("case 'text_delta'");
    expect(tdStart, 'text_delta case must exist').toBeGreaterThan(0);
    const tdBody = src.slice(tdStart, src.indexOf("case 'tool_use_start'", tdStart));
    expect(
      tdBody,
      'text_delta handler must mark the old step done=true when splitting',
    ).toMatch(/done:\s*true[,\s]+endedAt:\s*Date\.now\(\)/);
    expect(
      tdBody,
      'text_delta handler must open a new step with the captured text as its first content',
    ).toMatch(/text,?\s*toolEvents:\s*\[\][,\s]+counters:/);
    expect(
      tdBody,
      'text_delta handler must NOT depend on a `pendingStepBoundary` flag (R267 ' +
        'subsumed) — if this matches, the old tool→text-only split was reinstated ' +
        'and the long-think-with-many-tools bug returns',
    ).not.toMatch(/splitStep\s*=\s*pendingStepBoundary/);
  });

  it('no module-scope pendingStepBoundary flag declaration', () => {
    // R267's `let pendingStepBoundary: boolean = false;` was the
    // linchpin of the first attempt; R273 subsumed it and removed
    // the declaration. Re-introducing the flag without restoring
    // the round-trip semantics (set on tool_result + reset on
    // run_start + consume on text_delta) would silently bring back
    // the "stacked thinking" bug because then the text_delta
    // handler would skip the split on long think blocks.
    const src = readSrc('src/store/index.ts');
    const initMatch = src.match(/^\s*let\s+pendingStepBoundary\s*:\s*boolean\s*=\s*false\s*;?\s*$/m);
    expect(
      initMatch,
      'R267 pendingStepBoundary module flag must NOT be redeclared — ' +
        'R273 subsumes it by always splitting. Leaving the flag in ' +
        'place would re-introduce the "all thinking at top, all ' +
        'tools at bottom" rendering bug.',
    ).toBeNull();
  });

  it('no `pendingStepBoundary = true` assignment in tool_result handler', () => {
    // R267 set the flag on tool_result so the next text_delta
    // could trigger a split. R273 doesn't need it: text_delta
    // always splits. If this assignment returns, R267's
    // behaviour is back and the long-think bug returns.
    const src = readSrc('src/store/index.ts');
    expect(
      src,
      'tool_result handler must NOT set a pendingStepBoundary flag — ' +
        'R273 always splits and the flag is dead.',
    ).not.toMatch(/pendingStepBoundary\s*=\s*true/);
  });

  it('no `pendingStepBoundary = false` reset in run_start handler', () => {
    // R267 cleared the flag at run_start so a stale flag from
    // a previous run couldn't trigger a phantom split on the
    // first text_delta of run #2. R273 doesn't need it: the
    // split is unconditional, so any stale state is harmless.
    const src = readSrc('src/store/index.ts');
    expect(
      src,
      'run_start handler must NOT reset a pendingStepBoundary flag — ' +
        'the flag is dead in R273.',
    ).not.toMatch(/pendingStepBoundary\s*=\s*false/);
  });
});
