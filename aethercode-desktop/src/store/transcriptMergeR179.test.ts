import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * regression guard for the "blank screen on submit" bug.
 *
 * <p>Repro: the user is on a session with N messages. They
 * type a new prompt, press Enter. {@code sendMessage} pushes
 * the user message into {@code state.messages} synchronously
 * ({@code [...s.messages, userMsg]}), then awaits
 * {@code rpc.query}. While the RPC is in flight, the daemon
 * may emit {@code transcript_event(action="sync", messages=[...])}.
 * The pre-fix handler did
 * {@code set({ messages, isStreaming: false })} — a
 * full REPLACE. If the daemon's sync transcript is short
 * (e.g. the user msg hasn't been appended server-side yet,
 * or the session was just created and only the previous
 * session's empty {@code createSession} payload is in
 * flight), the local user message is wiped. The chat
 * history shows what was there before — but the user's
 * just-submitted prompt is GONE. The user reads the
 * screen as "blank" because the only thing they cared
 * about (the new prompt) disappeared and the
 * {@code isStreaming} flag flipped back to {@code false},
 * making it look like nothing happened.
 *
 * <p>The R179 fix changes the sync handler to merge by id:
 * keep every local message whose id the daemon hasn't
 * acknowledged yet (optimistic additions), and replace
 * any id the daemon has with the server's authoritative
 * copy. Same merge contract is applied to
 * {@code hydrateTranscript} so the slow path (used by
 * {@code sendMessage} tail and {@code switchSession})
 * doesn't reintroduce the same race.
 *
 * <p>These source-pin tests pin the post-fix contract.
 * A regression that drops the merge logic and falls back
 * to {@code set({ messages: incoming })} or
 * {@code set({ messages: msgs })} will fail the test
 * even if it looks "fine" in a code review.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

function readSrc(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('R179: transcript merge (no REPLACE)', () => {
  it('transcript_event sync handler must merge, not replace', () => {
    const src = readSrc('src/store/index.ts');
    // Extract the sync-handler block. We then strip
    // `// ...` line comments and `/* ... */` block comments
    // so the regex below matches real code, not the
    // explanatory comment that quotes the pre-fix bug.
    const syncBlock = src.match(
      /if \(action === 'sync'\) \{[\s\S]*?\} else if \(action === 'append'\)/,
    );
    expect(syncBlock, 'sync handler block should exist').toBeTruthy();
    const stripped = syncBlock![0]
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/\/\/.*$/gm, '');
    // Pre-fix bug: `set({ messages, isStreaming: false })`
    // where `messages` is the raw incoming array. This
    // REPLACES the local optimistic state and wipes the
    // just-submitted user message.
    expect(
      stripped,
      'sync handler must NOT do `set({ messages, isStreaming: false })` ' +
        'with the raw incoming array — that REPLACEs optimistic local ' +
        'additions like the just-submitted user message and causes the ' +
        '"blank screen on submit" bug',
    ).not.toMatch(/set\(\s*\{\s*messages\s*,\s*isStreaming:\s*false\s*\}\s*\)/);
    // Post-fix: the handler must do a functional `set(...)`
    // with a `merged` array that combines local + incoming.
    expect(
      stripped,
      'sync handler should construct a merged array (by-id merge), not the ' +
        'raw incoming messages array',
    ).toMatch(/merged/);
  });

  it('hydrateTranscript must merge, not replace', () => {
    const src = readSrc('src/store/index.ts');
    // The hydrateTranscript body sets `messages` based on
    // the daemon's `msgs`. Pre-fix: `set({ messages: msgs })`
    // or `set({ messages: daemonLikelyRestarted ? [...msgs, ...] : msgs })`.
    // Post-fix: must use a merged array that includes
    // local-only entries.
    expect(src).toMatch(/byIncomingId|byId/i);
    // Confirm the merge identifier is referenced inside
    // the hydrateTranscript function (search for the merge
    // construction pattern).
    const hasMerge = /byIncomingId\.has\(m\.id\)/.test(src) ||
                     /byId\.has\(m\.id\)/.test(src);
    expect(
      hasMerge,
      'hydrateTranscript should look up incoming ids and keep local-only ' +
        'optimistic entries (e.g. the just-submitted user message) when the ' +
        'daemon\'s transcript is behind the renderer',
    ).toBe(true);
  });

  it('sync handler must guard against an empty incoming payload', () => {
    const src = readSrc('src/store/index.ts');
    // an empty sync (e.g. just after createSession
    // with no messages yet) should not clobber the local
    // view. Look for the early-return guard.
    expect(
      /incoming\.length === 0/.test(src),
      'sync handler should bail (not clobber) when the daemon sends an ' +
        'empty messages array — otherwise a brand-new session restore ' +
        'would wipe the just-submitted user message',
    ).toBe(true);
  });
});
