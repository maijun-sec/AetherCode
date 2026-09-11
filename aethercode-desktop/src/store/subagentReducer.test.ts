// tests for the pure subagent reducer + formatters.
//
// Mirrors the TUI's r91d-subagent.test.mjs. Pure functions,
// no React, no Tauri — just the reducer logic. The store's
// `rpc.on('subagent_event', ...)` subscription feeds events
// into `reduceSubagent`; the toast / status bar read the
// result.

import { describe, test, expect } from 'vitest';
import {
  INITIAL_SUBAGENT,
  MAX_SUBAGENT_JOBS,
  reduceSubagent,
  formatSubagentStatus,
  isTerminalStatus,
  isOurSession,
  dismissTerminal,
  type SubagentStatus,
} from './subagentReducer';

// --- 1. INITIAL defaults ------------------------------------------------

describe('INITIAL_SUBAGENT', () => {
  test('empty status, 0 running, no jobs, no pending terminal', () => {
    expect(INITIAL_SUBAGENT.status).toBe('');
    expect(INITIAL_SUBAGENT.running).toBe(0);
    expect(INITIAL_SUBAGENT.jobs).toEqual({});
    expect(INITIAL_SUBAGENT.lastTerminal).toBeNull();
  });
});

// --- 2. RUNNING transition ----------------------------------------------

describe('reduceSubagent RUNNING', () => {
  test('bumps running count + sets status to "[id] running"', () => {
    const s1 = reduceSubagent(INITIAL_SUBAGENT, {
      jobId: 'sag-1', role: 'explore', status: 'RUNNING',
      elapsedMs: 0, atMs: 1000, summary: '',
    });
    expect(s1.running).toBe(1);
    expect(s1.status).toBe('[sag-1] running');
    expect(s1.jobs['sag-1']?.status).toBe('RUNNING');
    expect(s1.jobs['sag-1']?.startedAtMs).toBe(1000);
    expect(s1.lastTerminal).toBeNull();
  });
});

// --- 3. COMPLETED transition -------------------------------------------

describe('reduceSubagent COMPLETED', () => {
  test('drops running count + sets done with elapsed', () => {
    const r = reduceSubagent(INITIAL_SUBAGENT, {
      jobId: 'sag-1', role: 'explore', status: 'RUNNING',
      elapsedMs: 0, atMs: 1000, summary: '',
    });
    const d = reduceSubagent(r, {
      jobId: 'sag-1', role: 'explore', status: 'COMPLETED',
      elapsedMs: 1400, atMs: 2400, summary: 'ok',
    });
    expect(d.running).toBe(0);
    expect(d.status).toBe('[sag-1] done 1.4s');
    expect(d.jobs['sag-1']?.status).toBe('COMPLETED');
    expect(d.jobs['sag-1']?.endedAtMs).toBe(2400);
    expect(d.lastTerminal?.status).toBe('COMPLETED');
    expect(d.lastTerminal?.summary).toBe('ok');
  });

  test('zero elapsed → "[id] done" (no decimal)', () => {
    const r = reduceSubagent(INITIAL_SUBAGENT, {
      jobId: 'q', role: 'r', status: 'RUNNING',
      elapsedMs: 0, atMs: 1, summary: '',
    });
    const d = reduceSubagent(r, {
      jobId: 'q', role: 'r', status: 'COMPLETED',
      elapsedMs: 0, atMs: 2, summary: '',
    });
    expect(d.status).toBe('[q] done');
  });

  test('long elapsed → rounded seconds', () => {
    const r = reduceSubagent(INITIAL_SUBAGENT, {
      jobId: 'q', role: 'r', status: 'RUNNING',
      elapsedMs: 0, atMs: 1, summary: '',
    });
    const d = reduceSubagent(r, {
      jobId: 'q', role: 'r', status: 'COMPLETED',
      elapsedMs: 12_500, atMs: 2, summary: '',
    });
    expect(d.status).toBe('[q] done 13s');
  });
});

// --- 4. FAILED transition -----------------------------------------------

describe('reduceSubagent FAILED', () => {
  test('drops running count + sets failed + populates lastTerminal', () => {
    const r = reduceSubagent(INITIAL_SUBAGENT, {
      jobId: 'sag-2', role: 'explore', status: 'RUNNING',
      elapsedMs: 0, atMs: 1000, summary: '',
    });
    const f = reduceSubagent(r, {
      jobId: 'sag-2', role: 'explore', status: 'FAILED',
      elapsedMs: 800, atMs: 1800, summary: 'boom',
    });
    expect(f.running).toBe(0);
    expect(f.status).toBe('[sag-2] failed');
    expect(f.lastTerminal?.status).toBe('FAILED');
    expect(f.lastTerminal?.summary).toBe('boom');
  });
});

// --- 5. CANCELLED transition -------------------------------------------

describe('reduceSubagent CANCELLED', () => {
  test('drops running count + sets cancelled', () => {
    const r = reduceSubagent(INITIAL_SUBAGENT, {
      jobId: 'sag-3', role: 'r', status: 'RUNNING',
      elapsedMs: 0, atMs: 1000, summary: '',
    });
    const c = reduceSubagent(r, {
      jobId: 'sag-3', role: 'r', status: 'CANCELLED',
      elapsedMs: 0, atMs: 2000, summary: '',
    });
    expect(c.running).toBe(0);
    expect(c.status).toBe('[sag-3] cancelled');
    expect(c.lastTerminal?.status).toBe('CANCELLED');
  });
});

// --- 6. Lifecycle: count 0 → 1 → 2 → 1 → 0 ------------------------------

describe('lifecycle', () => {
  test('count stays consistent across multiple jobs', () => {
    let s = INITIAL_SUBAGENT;
    s = reduceSubagent(s, { jobId: 'a', role: 'r', status: 'RUNNING',   elapsedMs: 0,    atMs: 1, summary: '' });
    expect(s.running).toBe(1);
    s = reduceSubagent(s, { jobId: 'b', role: 'r', status: 'RUNNING',   elapsedMs: 0,    atMs: 2, summary: '' });
    expect(s.running).toBe(2);
    s = reduceSubagent(s, { jobId: 'a', role: 'r', status: 'COMPLETED', elapsedMs: 800,  atMs: 3, summary: '' });
    expect(s.running).toBe(1);
    // Most recent event wins the status label.
    expect(s.status).toMatch(/^\[a\]/);
    s = reduceSubagent(s, { jobId: 'b', role: 'r', status: 'FAILED',    elapsedMs: 1500, atMs: 4, summary: 'x' });
    expect(s.running).toBe(0);
    expect(s.status).toBe('[b] failed');
  });

  test('duplicate terminal does not drive running below 0', () => {
    let s = INITIAL_SUBAGENT;
    s = reduceSubagent(s, { jobId: 'x', role: 'r', status: 'RUNNING',   elapsedMs: 0, atMs: 1, summary: '' });
    s = reduceSubagent(s, { jobId: 'x', role: 'r', status: 'COMPLETED', elapsedMs: 0, atMs: 2, summary: '' });
    s = reduceSubagent(s, { jobId: 'x', role: 'r', status: 'COMPLETED', elapsedMs: 0, atMs: 3, summary: '' });
    expect(s.running).toBe(0);
  });

  test('startedAtMs preserved across transitions', () => {
    let s = INITIAL_SUBAGENT;
    s = reduceSubagent(s, { jobId: 'a', role: 'r', status: 'RUNNING', elapsedMs: 0, atMs: 1000, summary: '' });
    s = reduceSubagent(s, { jobId: 'a', role: 'r', status: 'COMPLETED', elapsedMs: 500, atMs: 1500, summary: '' });
    expect(s.jobs['a']?.startedAtMs).toBe(1000);
    expect(s.jobs['a']?.endedAtMs).toBe(1500);
  });
});

// --- 7. LRU eviction ----------------------------------------------------

describe('LRU eviction', () => {
  test('drops oldest terminal when at cap and a new job arrives', () => {
    let s = INITIAL_SUBAGENT;
    // Fill the cap with terminal jobs.
    for (let i = 0; i < MAX_SUBAGENT_JOBS; i++) {
      s = reduceSubagent(s, { jobId: `j${i}`, role: 'r', status: 'RUNNING', elapsedMs: 0, atMs: i, summary: '' });
      s = reduceSubagent(s, { jobId: `j${i}`, role: 'r', status: 'COMPLETED', elapsedMs: 100, atMs: i + 100, summary: '' });
    }
    expect(Object.keys(s.jobs).length).toBe(MAX_SUBAGENT_JOBS);
    // 33rd job evicts the oldest terminal.
    s = reduceSubagent(s, { jobId: 'fresh', role: 'r', status: 'RUNNING', elapsedMs: 0, atMs: 99999, summary: '' });
    expect(Object.keys(s.jobs).length).toBe(MAX_SUBAGENT_JOBS);
    expect(s.jobs['j0']).toBeUndefined();
    expect(s.jobs['fresh']).toBeDefined();
  });

  test('never evicts an in-flight job (soft cap when all running)', () => {
    let s = INITIAL_SUBAGENT;
    // Fill the cap with in-flight jobs.
    for (let i = 0; i < MAX_SUBAGENT_JOBS; i++) {
      s = reduceSubagent(s, { jobId: `r${i}`, role: 'r', status: 'RUNNING', elapsedMs: 0, atMs: i, summary: '' });
    }
    expect(s.running).toBe(MAX_SUBAGENT_JOBS);
    // 33rd in-flight job exceeds the soft cap (no terminal
    // jobs to evict). The reducer keeps growing.
    s = reduceSubagent(s, { jobId: 'r99', role: 'r', status: 'RUNNING', elapsedMs: 0, atMs: 999, summary: '' });
    expect(Object.keys(s.jobs).length).toBe(MAX_SUBAGENT_JOBS + 1);
  });
});

// --- 8. formatSubagentStatus helper -------------------------------------

describe('formatSubagentStatus', () => {
  test('running', () => {
    expect(formatSubagentStatus('sag-1', 'RUNNING', 0)).toBe('[sag-1] running');
  });
  test('completed with elapsed', () => {
    expect(formatSubagentStatus('sag-1', 'COMPLETED', 1500)).toBe('[sag-1] done 1.5s');
  });
  test('completed with zero elapsed', () => {
    expect(formatSubagentStatus('sag-1', 'COMPLETED', 0)).toBe('[sag-1] done');
  });
  test('failed', () => {
    expect(formatSubagentStatus('sag-1', 'FAILED', 0)).toBe('[sag-1] failed');
  });
  test('cancelled', () => {
    expect(formatSubagentStatus('sag-1', 'CANCELLED', 0)).toBe('[sag-1] cancelled');
  });
  test('empty jobId falls back to "?"', () => {
    expect(formatSubagentStatus('', 'RUNNING', 0)).toBe('[?] running');
  });
  test('unknown status lowercased', () => {
    expect(formatSubagentStatus('x', 'PAUSED' as SubagentStatus, 0)).toBe('[x] paused');
  });
});

// --- 9. isTerminalStatus -----------------------------------------------

describe('isTerminalStatus', () => {
  test('true for COMPLETED / FAILED / CANCELLED', () => {
    expect(isTerminalStatus('COMPLETED')).toBe(true);
    expect(isTerminalStatus('FAILED')).toBe(true);
    expect(isTerminalStatus('CANCELLED')).toBe(true);
  });
  test('false for RUNNING', () => {
    expect(isTerminalStatus('RUNNING')).toBe(false);
  });
});

// --- 10. dismissTerminal ------------------------------------------------

describe('dismissTerminal', () => {
  test('clears lastTerminal without touching other fields', () => {
    let s = INITIAL_SUBAGENT;
    s = reduceSubagent(s, { jobId: 'a', role: 'r', status: 'RUNNING', elapsedMs: 0, atMs: 1, summary: '' });
    s = reduceSubagent(s, { jobId: 'a', role: 'r', status: 'FAILED', elapsedMs: 0, atMs: 2, summary: 'x' });
    expect(s.lastTerminal).not.toBeNull();
    const d = dismissTerminal(s);
    expect(d.lastTerminal).toBeNull();
    expect(d.running).toBe(s.running);
    expect(d.jobs).toEqual(s.jobs);
    expect(d.status).toBe(s.status);
  });
});

// --- 11. prior round: per-session attribution -------------------------------

describe('isOurSession', () => {
  test('empty event sessionId is always accepted (single-session sentinel)', () => {
    // legacy-D daemons ship no sessionId. The desktop
    // must not silently drop those events.
    expect(isOurSession('', 'current-1')).toBe(true);
    expect(isOurSession('', null)).toBe(true);
    expect(isOurSession('', '')).toBe(true);
  });
  test('undefined event sessionId is treated as empty', () => {
    expect(isOurSession(undefined, 'current-1')).toBe(true);
    expect(isOurSession(undefined, null)).toBe(true);
  });
  test('matching event sessionId is accepted', () => {
    expect(isOurSession('sess-A', 'sess-A')).toBe(true);
  });
  test('non-matching event sessionId is rejected', () => {
    // Multi-session daemon case: another session's
    // subagent must NOT bleed into our UI.
    expect(isOurSession('sess-B', 'sess-A')).toBe(false);
  });
  test('null current session + non-empty event: accepted (pre-init)', () => {
    // Before getState returns, the desktop doesn't know
    // its own session. Accept the event so the user
    // still sees it when the session loads.
    expect(isOurSession('sess-A', null)).toBe(true);
    expect(isOurSession('sess-A', '')).toBe(true);
  });
});

// =============================================================================
// streaming partial result
// =============================================================================

describe('reduceSubagent 对应历史 round partial result', () => {
  const baseEvent = {
    jobId: 'sag-1',
    role: 'explore',
    elapsedMs: 100,
    atMs: 1_000,
    summary: 'running',
  };

  test('RUNNING with partialResult stores it on the job', () => {
    const after = reduceSubagent(INITIAL_SUBAGENT, {
      ...baseEvent,
      status: 'RUNNING',
      partialResult: 'first chunk of streaming output',
    });
    expect(after.jobs['sag-1']?.partialResult).toBe('first chunk of streaming output');
  });

  test('subsequent RUNNING events replace the partial', () => {
    let state = reduceSubagent(INITIAL_SUBAGENT, {
      ...baseEvent,
      atMs: 1_000,
      status: 'RUNNING',
      partialResult: 'first chunk',
    });
    state = reduceSubagent(state, {
      ...baseEvent,
      atMs: 1_500,
      status: 'RUNNING',
      partialResult: 'second chunk',
    });
    expect(state.jobs['sag-1']?.partialResult).toBe('second chunk');
  });

  test('COMPLETED clears the partial slot', () => {
    let state = reduceSubagent(INITIAL_SUBAGENT, {
      ...baseEvent,
      status: 'RUNNING',
      partialResult: 'in progress',
    });
    state = reduceSubagent(state, {
      ...baseEvent,
      atMs: 2_000,
      status: 'COMPLETED',
      resultText: 'final result',
    });
    expect(state.jobs['sag-1']?.partialResult).toBeUndefined();
  });

  test('FAILED clears the partial slot', () => {
    let state = reduceSubagent(INITIAL_SUBAGENT, {
      ...baseEvent,
      status: 'RUNNING',
      partialResult: 'in progress',
    });
    state = reduceSubagent(state, {
      ...baseEvent,
      atMs: 2_000,
      status: 'FAILED',
    });
    expect(state.jobs['sag-1']?.partialResult).toBeUndefined();
  });

  test('CANCELLED clears the partial slot', () => {
    let state = reduceSubagent(INITIAL_SUBAGENT, {
      ...baseEvent,
      status: 'RUNNING',
      partialResult: 'in progress',
    });
    state = reduceSubagent(state, {
      ...baseEvent,
      atMs: 2_000,
      status: 'CANCELLED',
    });
    expect(state.jobs['sag-1']?.partialResult).toBeUndefined();
  });

  test('missing partialResult is treated as no change', () => {
    // A RUNNING event without a partialResult field
    // should not blow away the existing preview.
    let state = reduceSubagent(INITIAL_SUBAGENT, {
      ...baseEvent,
      atMs: 1_000,
      status: 'RUNNING',
      partialResult: 'initial',
    });
    state = reduceSubagent(state, {
      ...baseEvent,
      atMs: 1_500,
      status: 'RUNNING',
      // no partialResult
    });
    expect(state.jobs['sag-1']?.partialResult).toBe('initial');
  });
});
