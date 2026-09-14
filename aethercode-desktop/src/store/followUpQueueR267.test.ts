import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * R267 desktop polish (2026-09-14): queue follow-up
 * prompts so the user can keep typing while the previous
 * run is in flight. The user explicitly asked for option
 * (B) from the round-plan: current run completes
 * automatically starts the queued prompt; the user can
 * ALSO cancel the current run (which also drops the
 * queue — "cancel means stop everything").
 *
 * <p>Source-pin tests for the four pieces:
 * <ol>
 *   <li>{@code pendingFollowUp} field in AppState</li>
 *   <li>{@code cancelPendingFollowUp} action</li>
 *   <li>{@code sendMessage} queues when isStreaming</li>
 *   <li>{@code run_end} auto-promotes the queue</li>
 * </ol>
 * A regression in any of these would re-introduce the
 * "I clicked Enter and nothing happened" bug, AND would
 * fail silently — there's no smoke test that catches a
 * missing set() in a stream_event handler.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

function readSrc(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('R267: follow-up prompt queue', () => {
  it('AppState declares the pendingFollowUp field', () => {
    const src = readSrc('src/store/index.ts');
    // The single-slot queue field — null when nothing is
    // queued, the user's prompt text otherwise. Without
    // this field the run_end handler has nowhere to read
    // the queued text from.
    expect(
      src,
      'AppState must declare pendingFollowUp: string | null',
    ).toMatch(/pendingFollowUp:\s*string\s*\|\s*null/);
    expect(
      src,
      'initial state must seed pendingFollowUp to null',
    ).toMatch(/pendingFollowUp:\s*null/);
  });

  it('store exposes cancelPendingFollowUp action', () => {
    const src = readSrc('src/store/index.ts');
    // The cancel button in the UI calls this. Without
    // the action the user can only cancel the whole
    // run (which also drops the queue), not just the
    // queue — and the user explicitly asked for that
    // distinction ("我会自己 cancel 前一个, 再提交后一个").
    expect(
      src,
      'AppState interface must declare cancelPendingFollowUp',
    ).toMatch(/cancelPendingFollowUp:\s*\(\)\s*=>\s*void/);
    expect(
      src,
      'cancelPendingFollowUp implementation must set pendingFollowUp to null',
    ).toMatch(/cancelPendingFollowUp:\s*\(\)\s*=>\s*set\(\{\s*pendingFollowUp:\s*null\s*\}\)/);
  });

  it('cancelQuery also drops the queued follow-up', () => {
    const src = readSrc('src/store/index.ts');
    // The user said: "我会自己 cancel 前一个, 再提交后一个"
    // — when they hit the cancel button (which calls
    // cancelQuery), they want a clean slate, NOT a
    // surprise queued prompt firing the moment the
    // daemon tears down. cancelQuery therefore clears
    // pendingFollowUp in the same set() as isStreaming.
    expect(
      src,
      'cancelQuery must clear pendingFollowUp alongside isStreaming',
    ).toMatch(/cancelQuery:[^{]*\{[\s\S]*?pendingFollowUp:\s*null/);
  });

  it('sendMessage queues to pendingFollowUp when isStreaming', () => {
    const src = readSrc('src/store/index.ts');
    // The queue path is reached BEFORE the early-return
    // for isStreaming (otherwise the prompt is silently
    // dropped — the bug we just fixed). The sendMessage
    // handler must:
    //   1. early-return only for empty input
    //   2. push to pendingFollowUp if isStreaming
    //   3. clear currentInput so the user can keep typing
    expect(
      src,
      'sendMessage must NOT have the legacy "if (!input || get().isStreaming) return" guard',
    ).not.toMatch(/if\s*\(!input\s*\|\|\s*get\(\)\.isStreaming\)\s*return/);
    expect(
      src,
      'sendMessage must route to pendingFollowUp when isStreaming',
    ).toMatch(/get\(\)\.isStreaming[\s\S]{0,200}pendingFollowUp:\s*input/);
    expect(
      src,
      'sendMessage must clear currentInput when queueing',
    ).toMatch(/get\(\)\.isStreaming[\s\S]{0,300}currentInput:\s*''/);
  });

  it('run_end auto-promotes pendingFollowUp to a new run', () => {
    const src = readSrc('src/store/index.ts');
    // When the current run finishes and the user has a
    // queued follow-up, the store auto-starts it. The
    // implementation is "set currentInput from queue,
    // call sendMessage" rather than duplicating the
    // query/lazy-create-session logic.
    expect(
      src,
      'run_end handler must check pendingFollowUp after closing the current step',
    ).toMatch(/run_end[\s\S]*?pendingFollowUp[\s\S]{0,500}get\(\)\.sendMessage/);
  });

  it('MessageInput renders the queued follow-up pill', () => {
    const src = readSrc('src/components/MessageInput.tsx');
    // The user needs visual confirmation that their
    // next prompt is queued, otherwise they'd assume
    // it was lost. The pill shows above the input box
    // with a ✕ cancel button.
    expect(
      src,
      'MessageInput must destructure pendingFollowUp + cancelPendingFollowUp',
    ).toMatch(/pendingFollowUp[\s\S]{0,40}cancelPendingFollowUp/);
    expect(
      src,
      'MessageInput must render the follow-up pill conditionally',
    ).toMatch(/pendingFollowUp\s*&&\s*\([\s\S]*?followup-pill/);
    expect(
      src,
      'MessageInput pill must have a cancel button calling cancelPendingFollowUp',
    ).toMatch(/followup-pill-cancel[\s\S]{0,200}onClick=\{cancelPendingFollowUp\}/);
  });
});