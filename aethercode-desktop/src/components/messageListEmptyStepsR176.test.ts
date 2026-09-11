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
 * <p>The fix gates the placeholder on
 * `isStreaming` so it only shows while a query
 * is actually in flight. R196: the renderer now
 * builds a unified timeline (`messages` + `steps`
 * + `subTasks`), and the placeholder shows only
 * when `timeline.length === 0` AND `isStreaming`.
 * The intent is the same as prior round: never show
 * the placeholder unless a query is in flight.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

describe('prior round: 等待模型响应… (waiting for model response) placeholder is gated on isStreaming', () => {
  const src = readFileSync(join(root, 'src', 'components', 'MessageList.tsx'), 'utf-8');

  it('the placeholder is wrapped in isStreaming && timeline.length === 0 (R196 unified timeline)', () => {
    // prior round original pin was `isStreaming && subTasks.length === 0
    // && preambleSteps.length === 0`. R196 collapsed those two
    // length checks into one (`timeline.length === 0`) because
    // the timeline is now the single source of truth for "do
    // we have any agent content yet". The placeholder still
    // requires `isStreaming` so a session restore never shows
    // the stuck-placeholder. We pin the new shape.
    const block = src.match(
      /isStreaming\s*&&\s*timeline\.length === 0[\s\S]*?message-list-empty-steps[\s\S]*?等待模型响应…/,
    );
    expect(block).toBeTruthy();
    expect(block![0]).toContain('等待模型响应…');
  });

  it('does NOT have a top-level {timeline.length === 0 && ... 等待模型响应…} block (regression guard)', () => {
    // Belt-and-suspenders: the pre-fix pattern was a
    // standalone {subTasks.length === 0 && preambleSteps.length === 0 && ...
    // 等待模型响应… (waiting for model response)} block with no isStreaming gate. The new
    // pattern uses `timeline.length === 0`. A regression that
    // drops the isStreaming gate will fail this test.
    const standalonePattern =
      /\{\s*timeline\.length === 0\s*&&[^}]*?message-list-empty-steps/;
    expect(src).not.toMatch(standalonePattern);
  });
});
