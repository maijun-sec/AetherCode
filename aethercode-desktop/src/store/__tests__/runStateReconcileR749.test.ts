// Copyright (c) 2026 maijun

// Licensed under the MIT License.
// See the LICENSE file in the project root for the full license text.

// @vitest-environment jsdom
import { describe, it, expect } from 'vitest';

// R749 (2026-10-21) — "stuck showing Running" after a daemon restart.
//
// Reported symptom: the desktop header showed "Running" for 7
// minutes on a session claiming 704 messages, while `jstack` on the
// daemon showed zero engine threads and the daemon log showed no
// query had been processed since it booted. The run had died with
// the previous daemon process, but the renderer never found out.
//
// WHY IT HAPPENED. The renderer inferred "is a run in flight?" from
// one signal only: "have I seen a chunk recently". That inference is
// wrong after a reconnect, because a freshly-established WS
// connection to a healthy daemon is silent-but-perfectly-fine —
// every liveness signal reads green while `isStreaming` stays stuck
// true from the dead run. The daemon's `daemon.disconnected`
// notification that would have cleared it is only emitted when the
// WS ping fails or the read errors; a supervisor that re-establishes
// the connection in between means that notification never arrives.
//
// THE FIX. Stop inferring. The engine's `inFlight` run registry is
// authoritative — `query()` registers the run before any event
// flows and the `finally` removes it on every exit path — so
// `getState().isRunning` answers "is this run actually executing"
// with no heuristics. The renderer reconciles against it on every
// reconnect and on every periodic refresh.
//
// These are source-pin guards: they assert the reconciliation is
// wired into the real code paths, so a refactor that drops it fails
// loudly rather than silently regressing to the guess-based
// behaviour.

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

// R748/1079: derive the package root from this file's own location,
// never from the ambient cwd — unreliable under jsdom multi-suite runs.
const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

const storeSrc = readFileSync(join(root, 'src', 'store', 'index.ts'), 'utf8');
const methodsSrc = readFileSync(join(root, 'src', 'lib', 'methods.ts'), 'utf8');

describe('R749 — the engine exposes an authoritative run registry', () => {
  it('EngineState declares isRunning / liveRunIds', () => {
    // The renderer must be able to read the authoritative answer.
    // Without the typed field the reconciliation below has nothing
    // to reconcile against and TypeScript would silently pass
    // `undefined` (which we treat as "unknown", not "false").
    expect(methodsSrc).toMatch(/isRunning\?:\s*boolean/);
    expect(methodsSrc).toMatch(/liveRunIds\?:\s*string\[\]/);
  });
});

describe('R749 — the renderer reconciles instead of inferring', () => {
  it('initialize() (the reconnect path) reconciles against state.isRunning', () => {
    // This is the exact path the bug travelled: disconnect ->
    // scheduleReconnect -> initialize(). The reconciliation has to
    // live HERE, not only in the 15s periodic timer, or the user
    // stares at a phantom "Running" for up to 15 seconds after every
    // reconnect.
    expect(storeSrc).toMatch(
      /state\?\.isRunning\s*===\s*false\s*&&\s*get\(\)\.isStreaming/
    );
  });

  it('refreshEngineState() (the periodic path) reconciles too', () => {
    // Belt and braces: if the reconnect path is ever bypassed (e.g.
    // a cwd swap that reconnects without re-running initialize),
    // the 15s tick still catches the desync.
    expect(storeSrc).toMatch(
      /r\.isRunning\s*===\s*false\s*&&\s*get\(\)\.isStreaming/
    );
  });

  it('reconciliation clears isStreaming AND the run-scoped UI state', () => {
    // Clearing only `isStreaming` would leave `currentQuery`,
    // `currentStepId` and `currentSubTaskId` pointing at a run that
    // no longer exists — the Plan panel would keep showing
    // "1 running" forever, which is half the user's complaint in
    // the screenshot.
    expect(storeSrc).toMatch(
      /isStreaming:\s*false[\s\S]{0,220}currentStepId:\s*null[\s\S]{0,120}currentSubTaskId:\s*null[\s\S]{0,120}currentQuery:\s*null/
    );
  });

  it('reconciliation surfaces a retry affordance rather than failing silently', () => {
    // The user has to be able to recover. A phantom run that just
    // vanishes would strand whatever they were doing.
    expect(storeSrc).toMatch(/lastFailedStopReason:\s*'daemon_lost_run'/);
    expect(storeSrc).toMatch(/lastFailedPrompt:/);
    expect(storeSrc).toMatch(/任务已中断/);
  });

  it('does NOT flip isStreaming when the engine reports a run IS in flight', () => {
    // The converse must stay a no-op. A run that starts between our
    // snapshot and our last chunk legitimately reports isRunning=
    // false locally; treating that as a desync would kill live runs.
    // The guard is `=== false` (explicit), never a truthiness test.
    const reconciles = storeSrc.match(
      /isRunning\s*===\s*false\s*&&\s*get\(\)\.isStreaming/g
    ) ?? [];
    expect(reconciles.length).toBeGreaterThanOrEqual(2);
    expect(storeSrc).not.toMatch(/if\s*\(\s*!?\s*state\??\.isRunning\s*\)/);
  });
});

describe('R749 — the stream-stale watchdog is not blind before the first chunk', () => {
  it('falls back to the run start time when no chunk has arrived yet', () => {
    // Pre-R749 the watchdog bailed on `lastChunkTs <= 0`, which is
    // exactly the state a run is in between "isStreaming = true" and
    // its first chunk. A run killed in that window was unwatchable.
    expect(storeSrc).toMatch(/lastActivity\s*=\s*s\.lastChunkTs\s*>\s*0\s*\?\s*s\.lastChunkTs\s*:\s*startedAt/);
    expect(storeSrc).toMatch(/const\s+startedAt\s*=\s*s\.currentQuery\?\.startedAt\s*\?\?\s*0/);
  });

  it('the stale comparison uses lastActivity, not the raw lastChunkTs', () => {
    expect(storeSrc).toMatch(/Date\.now\(\)\s*-\s*lastActivity\s*>\s*STREAM_STALE_MS/);
  });
});