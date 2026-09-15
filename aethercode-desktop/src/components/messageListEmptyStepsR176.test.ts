import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * regression guard for the
 * "等待模型响应…" (waiting for model response) stuck-placeholder bug.
 *
 * <p>The pre-fix MessageList.tsx showed the
 * "等待模型响应…" (waiting for model response) text whenever
 * `subTasks.length === 0 && preambleSteps.length === 0`.
 * On a session restore (e.g. a user opens the
 * app after a daemon restart, or switches to a
 * session that finished yesterday), the user's
 * message is in `messages` but the sub-task
 * structure isn't restored — so the placeholder
 * showed even though no query was in flight.
 * The user reported the chat "stuck on waiting
 * for the model to respond" when the model
 * wasn't actually running.
 *
 * <p>R196 fixed this by gating the placeholder on
 * `isStreaming`. R269 (2026-09-15) replaced the
 * plain "等待模型响应…" inline placeholder with the
 * <StreamingIndicator> component (two variants:
 * `empty` and `footer`). The intent is the same:
 * only render the streaming placeholder while a
 * query is actually in flight, never on a
 * session restore.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

describe('R269: streaming indicator placeholder is gated on isStreaming (replaces R176 等待模型响应… inline block)', () => {
  const src = readFileSync(join(root, 'src', 'components', 'MessageList.tsx'), 'utf-8');

  it('the empty-state branch renders <StreamingIndicator variant="empty"> when isStreaming && timeline.length === 0', () => {
    // the empty-state ternary now uses StreamingIndicator
    // when isStreaming is true. Pre-fix: a plain <div> with
    // 等待模型响应… text. R269: a proper component with
    // pulse animation + colour-coded kind.
    expect(src).toMatch(/timeline\.length === 0\s*\?\s*\(\s*isStreaming\s*\?[\s\S]*?<StreamingIndicator[\s\S]*?variant="empty"[\s\S]*?forceWhenEmpty/);
  });

  it('the populated branch renders <StreamingIndicator variant="footer"> when isStreaming', () => {
    // the footer variant pins above MessageInput so the user
    // always sees "the engine is alive" between the first
    // event and run_end.
    expect(src).toMatch(/\{isStreaming\s*&&\s*<StreamingIndicator[\s\S]*?variant="footer"[\s\S]*?\/\}/);
  });

  it('does NOT have a top-level {timeline.length === 0 && ... 等待模型响应…} block (R176 regression guard)', () => {
    // Belt-and-suspenders: the pre-fix pattern was a
    // standalone {subTasks.length === 0 && preambleSteps.length === 0 && ...
    // 等待模型响应… (waiting for model response)} block with no isStreaming gate.
    // The R176 test pinned the inline div with that text. R269 removed
    // it entirely; we keep the negative-pin so any regression that
    // re-introduces the inline placeholder will fail this test.
    const inlinePlaceholder = /message-list-empty-steps[\s\S]*?等待模型响应…/;
    expect(src).not.toMatch(inlinePlaceholder);
  });
});