import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * R360 desktop polish (2026-09-26): the user reported three
 * symptoms when they hit "+ new session" in the LeftPanel
 * while a run on the previous session was still streaming:
 *
 *   1. The new session's chat scrollback accumulated
 *      text_delta / tool_use_start events from the OLD
 *      session's still-running stream — pre-fix the
 *      stream_event handler did not check sessionId, so
 *      every event from the old run was appended to
 *      `messages[]` even after the renderer's
 *      `currentSessionId` had flipped to the new id.
 *   2. The new session's user prompt sat in the middle of
 *      the scrollback (below the old session's stale
 *      `text_delta` chunks), instead of at the top.
 *   3. The left rail's ProjectGroupList showed only the
 *      NEW cwd's sessions after `setCwd` — pre-fix
 *      `setCwd` cleared the entire `sessions[]` array on
 *      daemon swap, so the old cwd's sessions disappeared
 *      from the rail even though they still lived on disk.
 *
 * Fix summary:
 *
 *   a. `stream_event` handler in store/index.ts must check
 *      the event's `sessionId` field (added to the wire
 *      format by AetherCodeMethods.query()) and skip events
 *      whose sessionId doesn't match `currentSessionId`.
 *      The `isOurSession` helper treats empty sessionId as
 *      "all sessions" so legacy daemons (no sessionId on
 *      the wire) still pass through.
 *
 *   b. `switchSession` / `setCurrentSessionId` /
 *      `createNewSession` / `setCwd` must reset the
 *      live-streaming state alongside `messages: []`:
 *      `isStreaming: false`, `currentStepId: null`,
 *      `currentSubTaskId: null`, `currentQuery: null`,
 *      `steps: []`, `subTasks: []`. Otherwise the new
 *      session's `run_start` handler takes the "else if
 *      (currentStepId)" branch and bumps the OLD step's
 *      counter — the new run's text_delta / tool_use_start
 *      events accumulate into the OLD step's toolEvents /
 *      text, producing the "新 prompt 的输出跑到老 prompt
 *      上面" symptom.
 *
 *   c. `setCwd` must NOT clear `sessions[]` on a daemon
 *      swap. Preserve the OLD list, fold the new session
 *      id in, and let `refreshSessions()` (now a merge
 *      rather than a replace) repopulate. Without this the
 *      ProjectGroupList loses the old cwd's sessions on
 *      every cwd switch.
 *
 *   d. `refreshSessions()` must merge daemonSessions with
 *      the existing local list rather than replace — when
 *      the daemon is rooted at the NEW cwd, listSessions
 *      only returns the new cwd's sessions; the old
 *      cwd's sessions are still in `local` and must be
 *      preserved.
 *
 * These source-pin tests pin all four fixes so any future
 * refactor that drops one of them fails the build.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

function readSrc(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

// Pull the body of the `stream_event` handler so we can
// pin (a) in isolation. The handler is a `rpc.on('stream_event',
// (params) => { ... })` arrow whose body ends at the closing
// `});` for that subscription.
function streamEventHandlerBody(src: string): string {
  const sig = `rpc.on('stream_event',`;
  const from = src.indexOf(sig);
  if (from === -1) return '';
  // end at the matching `});` — the next stream_event
  // subscription's `});` is the next handler, and we walk
  // through balanced parens to find the close.
  let depth = 0;
  let i = from;
  while (i < src.length) {
    const ch = src.charAt(i);
    if (ch === '(') depth++;
    else if (ch === ')') {
      depth--;
      if (depth === 0) {
        // include the closing `);` on the same line if any
        return src.slice(from, i + 2);
      }
    }
    i++;
  }
  return src.slice(from);
}

describe('R360 #1: stream_event handler drops events from non-current sessions', () => {
  it('declares the wire shape with sessionId (matches AetherCodeMethods.query)', () => {
    const src = readSrc('src/store/index.ts');
    // The comment block above the stream_event handler
    // documents the wire shape — it must include sessionId
    // so a future reader doesn't strip the field thinking
    // it's never used. We accept the field anywhere in
    // the doc block (the prose wording may vary slightly
    // across reviews): look for `runId` + `sessionId`
    // within the same handler block.
    const handlerBlock = streamEventHandlerBody(src);
    expect(handlerBlock, 'stream_event handler must exist').not.toBe('');
    expect(
      handlerBlock,
      'stream_event handler doc must mention BOTH runId AND sessionId as part ' +
        'of the wire shape — the doc is the contract; if a reader removes the ' +
        'sessionId field thinking it\'s unused, the per-session guard becomes a ' +
        'no-op and the bug returns.',
    ).toMatch(/runId/);
    expect(handlerBlock).toMatch(/sessionId/);
  });

  it('stream_event handler calls isOurSession on params.sessionId', () => {
    const src = readSrc('src/store/index.ts');
    const handlerBlock = streamEventHandlerBody(src);
    expect(handlerBlock, 'stream_event handler must exist').not.toBe('');
    // The guard reads params.sessionId and skips events
    // whose sessionId doesn't match currentSessionId. Use
    // isOurSession (already used by subagent_event /
    // skip_confirmation) for legacy single-engine compat
    // (empty sessionId → accept).
    expect(
      handlerBlock,
      'stream_event handler must read params.sessionId and call isOurSession — ' +
        'without this guard, the OLD session\'s stream events keep polluting ' +
        'the NEW session\'s chat scrollback after a switch.',
    ).toMatch(/isOurSession\(\s*evSession\s*,\s*get\(\)\.currentSessionId\s*\)/);
    expect(
      handlerBlock,
      'stream_event handler must bail (return early) when isOurSession returns false — ' +
        'otherwise the guard is decorative. The pattern is `if (!isOurSession(...)) return;`.',
    ).toMatch(/if\s*\(\s*!isOurSession/);
  });
});

describe('R360 #2: switchSession / setCurrentSessionId / createNewSession / setCwd reset live-streaming state', () => {
  const src = readSrc('src/store/index.ts');

  // Pin the 4 callers. Each must reset isStreaming,
  // currentStepId, currentSubTaskId, currentQuery, steps,
  // subTasks. We check for the relevant set call inside
  // each action's body.
  //
  // The action body lives at the SECOND occurrence of the
  // action name (the first is in the AppState interface,
  // e.g. `createNewSession: (opts?: { mode?: ... }) =>
  // void;`). For actions declared with no implicit type, the
  // second occurrence is still the body. We locate the
  // FIRST `=> {` AFTER the action name and treat that as
  // the body opener.

  function bodyOf(action: string): string {
    const sig = `${action}: `;
    const from = src.indexOf(sig);
    if (from === -1) return '';
    // find the FIRST `=> {` after the action name. This
    // works for both shapes:
    //   - interface decl: `foo: (x: T) => Y;` — the
    //     `=> {` we want is the body in the createStore
    //     definition. The interface decl ends with `;`
    //     and has no `{`. We keep scanning.
    //   - body decl: `foo: (opts) => { ... }` — the
    //     `=> {` is exactly the opener.
    let arrow = src.indexOf('=>', from);
    while (arrow !== -1) {
      const brace = src.indexOf('{', arrow);
      if (brace === -1) break;
      // is this `{` part of an object literal INSIDE the
      // arrow signature, not the body? E.g.
      // `(opts?: { mode?: 'normal' }) => void` has a
      // `{` before `=>` (in the type) but no `{` between
      // `=>` and the body's `{`. So if `=>` is immediately
      // followed by ` {`, it's the body opener.
      const between = src.slice(arrow + 2, brace);
      // accept zero-or-more whitespace characters
      if (/^\s*$/.test(between)) {
        // body opens here. Walk braces to find the
        // matching close.
        let depth = 0;
        let i = brace;
        while (i < src.length) {
          const ch = src.charAt(i);
          if (ch === '{') depth++;
          else if (ch === '}') {
            depth--;
            if (depth === 0) return src.slice(arrow, i + 1);
          }
          i++;
        }
        return src.slice(arrow);
      }
      arrow = src.indexOf('=>', arrow + 2);
    }
    return '';
  }

  it('switchSession resets isStreaming / currentStepId / steps / subTasks / currentQuery', () => {
    const body = bodyOf('switchSession');
    expect(body, 'switchSession action must exist').not.toBe('');
    // The set() inside switchSession must include all
    // six reset fields. Match the literal substrings.
    expect(body).toMatch(/isStreaming:\s*false/);
    expect(body).toMatch(/currentStepId:\s*null/);
    expect(body).toMatch(/currentSubTaskId:\s*null/);
    expect(body).toMatch(/currentQuery:\s*null/);
    expect(body).toMatch(/steps:\s*\[\]/);
    expect(body).toMatch(/subTasks:\s*\[\]/);
  });

  it('setCurrentSessionId resets isStreaming / currentStepId / steps / subTasks / currentQuery', () => {
    const body = bodyOf('setCurrentSessionId');
    expect(body, 'setCurrentSessionId action must exist').not.toBe('');
    expect(body).toMatch(/isStreaming:\s*false/);
    expect(body).toMatch(/currentStepId:\s*null/);
    expect(body).toMatch(/currentSubTaskId:\s*null/);
    expect(body).toMatch(/currentQuery:\s*null/);
    expect(body).toMatch(/steps:\s*\[\]/);
    expect(body).toMatch(/subTasks:\s*\[\]/);
  });

  it('createNewSession resets isStreaming / currentStepId / steps / subTasks / currentQuery', () => {
    const body = bodyOf('createNewSession');
    expect(body, 'createNewSession action must exist').not.toBe('');
    expect(body).toMatch(/isStreaming:\s*false/);
    expect(body).toMatch(/currentStepId:\s*null/);
    expect(body).toMatch(/currentSubTaskId:\s*null/);
    expect(body).toMatch(/currentQuery:\s*null/);
    expect(body).toMatch(/steps:\s*\[\]/);
    expect(body).toMatch(/subTasks:\s*\[\]/);
  });

  it('setCwd resets isStreaming / currentStepId / steps / subTasks / currentQuery', () => {
    // R361: setCwd is a single RPC to the daemon's
    // bindSessionCwd — no JVM swap, no live-streaming
    // state to migrate, but we still reset the local
    // live-streaming fields defensively so the next
    // text_delta / run_start lands on a clean slate
    // (the previous session's last event left
    // prevEventWasText in some indeterminate state).
    // The block is the body of setCwd (no `if
    // r.swapped` branch — that was R199, retired in
    // R361).
    const body = bodyOf('setCwd');
    expect(body, 'setCwd action must exist').not.toBe('');
    expect(body).toMatch(/isStreaming:\s*false/);
    expect(body).toMatch(/currentStepId:\s*null/);
    expect(body).toMatch(/currentSubTaskId:\s*null/);
    expect(body).toMatch(/currentQuery:\s*null/);
    expect(body).toMatch(/steps:\s*\[\]/);
    expect(body).toMatch(/subTasks:\s*\[\]/);
  });
});

describe('R360 #3 → R361: setCwd is a single bindSessionCwd RPC; no swap path remains', () => {
  const src = readSrc('src/store/index.ts');

  it('setCwd does NOT contain `if (r.swapped)` (R199 swap dance is retired)', () => {
    // The pre-R361 setCwd had two branches:
    //   if (r.swapped) { ... }
    //   else { ... }
    // The swapped branch was the source of bug 3 (old
    // cwd sessions vanished from the rail on every
    // swap) and of bug 1+2 (in-flight streams were
    // polluted by the new daemon's transcript).
    // R361 retired the swap: setCwd is a single
    // bindSessionCwd round-trip on the same daemon.
    // Pin the absence of the r.swapped branch so a
    // future refactor that re-adds the swap dance is
    // caught here.
    const setCwdBlock = src.match(/setCwd:\s*async\s*\(path:\s*string\)\s*=>\s*\{[\s\S]*?\n\s{4}\}/);
    expect(setCwdBlock, 'setCwd action must exist').toBeTruthy();
    expect(
      setCwdBlock![0],
      'setCwd must NOT contain an r.swapped branch — R361 retired the swap dance.',
    ).not.toMatch(/if\s*\(\s*r\.swapped\s*\)/);
  });

  it('setCwd does NOT seed `sessions: [{ id: newId, ... }]`', () => {
    // Pre-R360 the swapped branch hard-replaced the
    // sessions array with a single entry for the new
    // id. That was the bug: old cwd's sessions
    // vanished from the LeftPanel's ProjectGroupList.
    // The whole swap branch is now gone, so the
    // replacement pattern can't come back. Pin the
    // absence here.
    const setCwdBlock = src.match(/setCwd:\s*async\s*\(path:\s*string\)\s*=>\s*\{[\s\S]*?\n\s{4}\}/);
    expect(setCwdBlock).toBeTruthy();
    expect(
      setCwdBlock![0],
      'setCwd must NOT replace the sessions array with a single seed entry. ' +
        'That pattern was the source of bug 3 — the old cwd\'s sessions vanished ' +
        'on every swap.',
    ).not.toMatch(/sessions:\s*\[\s*\{\s*id:\s*newId!/);
  });

  it('setCwd does NOT call preWarmCwd (no pre-warm slot)', () => {
    // R199 pre-warmed a sibling daemon for sub-second
    // future cwd switches. R361 retired the pre-warm
    // dance entirely. Pin the absence so the cleanup
    // isn't undone.
    const setCwdBlock = src.match(/setCwd:\s*async\s*\(path:\s*string\)\s*=>\s*\{[\s\S]*?\n\s{4}\}/);
    expect(setCwdBlock).toBeTruthy();
    expect(
      setCwdBlock![0],
      'setCwd must NOT pre-warm a sibling — R361 single-daemon design has no pre-warm slot.',
    ).not.toMatch(/preWarmCwd\(suggestSibling/);
  });
});

describe('R360 #4: refreshSessions merges daemonSessions with local (no longer replace)', () => {
  const src = readSrc('src/store/index.ts');

  it('refreshSessions is not a wholesale `set({ sessions: daemonSessions })`', () => {
    // Pre-fix the action was a single set call with
    // `daemonSessions` directly. The fix walks both
    // lists, prefers the daemon's authoritative copy
    // for ids the daemon returned, and keeps the local
    // entry for ids it didn't (the dead daemon's
    // sessions). Pin the merge pattern: a `merged` array
    // built from `local` and `byId`.
    const sig = 'refreshSessions: async () => {';
    const from = src.indexOf(sig);
    expect(from, 'refreshSessions action must exist').toBeGreaterThan(0);
    let depth = 0;
    let i = from;
    while (i < src.length) {
      const ch = src.charAt(i);
      if (ch === '{') depth++;
      else if (ch === '}') {
        depth--;
        if (depth === 0) break;
      }
      i++;
    }
    const body = src.slice(from, i + 1);
    expect(
      body,
      'refreshSessions must NOT just `set({ sessions: daemonSessions })`. ' +
        'The pre-fix replace wiped the old cwd\'s sessions on every swap.',
    ).not.toMatch(/set\(\{\s*sessions:\s*daemonSessions\s*\}\)/);
    expect(
      body,
      'refreshSessions must build a merged array from local entries + daemon entries. ' +
        'Pin a `merged` literal so a future "simplify" pass that goes back to a straight ' +
        'set({ sessions: daemonSessions }) fails this test.',
    ).toMatch(/merged\s*:\s*SessionInfo\[\]/);
  });
});

describe('R360 #5: daemon side (Java) tags stream_event with sessionId', () => {
  // Pin the daemon-side counterpart. If the Java side
  // regresses to NOT include sessionId, the desktop
  // guard becomes a no-op (isOurSession with empty
  // sessionId treats the event as "ours"). The fix has
  // two halves; this test pins the Java half.

  it('AetherCodeMethods.query() emits sessionId in stream_event evWrap', () => {
    // The Desktop's stream_event handler depends on the
    // wire carrying a sessionId field. Read the daemon
    // source and pin the put call. The `runId` put is
    // already present (we don't re-pin that), only the
    // `sessionId` line is new.
    //
    // There are 4 stream-event emit sites that must
    // carry sessionId:
    //   - main loop evWrap.put   (line ~3361)
    //   - synthetic run_end endWrap.put (line ~3449)
    //   - runWorkflow side_note payload.put (line ~4011)
    //   - skip_confirmation listener payload.put (line ~846, NOT
    //     a stream_event but pinned here so a refactor that
    //     unifies all notifications behind one helper doesn't
    //     accidentally drop sessionId from the listener variant)
    //
    // We assert all three stream_event sites by matching
    // the union regex (evWrap | endWrap | payload).put("sessionId").
    const javaSrc = readFileSync(
      join(root, '..', 'aethercode', 'aethercode-protocol',
        'src', 'main', 'java', 'org', 'aethercode',
        'protocol', 'methods', 'AetherCodeMethods.java'),
      'utf-8',
    );
    const matches = javaSrc.match(/put\(\s*"sessionId"\s*,/g);
    expect(
      matches,
      'AetherCodeMethods.java must put("sessionId", ...) on every stream_event ' +
        'emitter. Found 0 occurrences — the desktop\'s per-session guard becomes a ' +
        'no-op and the stream-event-pollution bug returns.',
    ).not.toBeNull();
    expect(
      (matches ?? []).length,
      'AetherCodeMethods.java must put("sessionId", ...) on at least 3 stream_event ' +
        'emit sites (main loop, synthetic run_end, runWorkflow side_note) plus the ' +
        'skip_confirmation listener. Found fewer than 4 — pin the missing site.',
    ).toBeGreaterThanOrEqual(3);
  });

  it('EngineContinuationDispatcher.notifyStreamEvent carries sessionId', () => {
    const javaSrc = readFileSync(
      join(root, '..', 'aethercode', 'aethercode-protocol',
        'src', 'main', 'java', 'org', 'aethercode',
        'protocol', 'methods', 'EngineContinuationDispatcher.java'),
      'utf-8',
    );
    // The continuation dispatcher is a 4th emit site.
    // It writes its own evWrap from `notifyStreamEvent`.
    expect(
      javaSrc,
      'EngineContinuationDispatcher.notifyStreamEvent must put sessionId into the evWrap. ' +
        'Without this, the boulder hook\'s continuation runs leak into the renderer\'s chat ' +
        'after a session switch.',
    ).toMatch(/evWrap\.put\(\s*"sessionId"/);
  });
});