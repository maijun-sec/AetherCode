// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { safeParseSsdEvent } from '../ssd/tauriSsdDriver';

/**
 * R297: wire-format compatibility between the daemon
 * InteractiveRepl (emits `event` field) and the renderer
 * (discriminator is `kind`). Without remapping every line
 * from the daemon subprocess is silently dropped by
 * `safeParseSsdEvent`, symptom: SDD chips stay at `idle`
 * while the daemon runs end-to-end ("flash past" with
 * no chip flipping). Tests pin both formats so any
 * future regression surfaces immediately.
 */
describe('safeParseSsdEvent R297: kind ↔ event wire format', () => {
  it('parses lines using `kind` (MockSsdDriver fixture format)', () => {
    const ev = safeParseSsdEvent(JSON.stringify({
      kind: 'phase-list',
      feature: 'foo',
      phases: [{ id: 'constitution', order: 0, title: 'Constitution' }],
    }));
    expect(ev).not.toBeNull();
    expect(ev!.kind).toBe('phase-list');
  });

  it('parses lines using `event` (daemon InteractiveRepl format)', () => {
    // This is the format the daemon's emitPhaseList writes:
    // {"event": "phase-list", "feature": "...", "phases": [...]}
    const ev = safeParseSsdEvent(JSON.stringify({
      event: 'phase-list',
      feature: 'foo',
      phases: [{ id: 'constitution', order: 0, title: 'Constitution' }],
    }));
    expect(ev).not.toBeNull();
    expect(ev!.kind).toBe('phase-list');
  });

  it('normalises daemon `event: phase-start` to renderer `kind: phase-start`', () => {
    const ev = safeParseSsdEvent(JSON.stringify({
      event: 'phase-start',
      phase: 'specify', order: 1, title: 'Specify',
    }));
    expect(ev).not.toBeNull();
    expect(ev!.kind).toBe('phase-start');
  });

  it('normalises daemon `event: phase-draft` and preserves path/preview', () => {
    const ev = safeParseSsdEvent(JSON.stringify({
      event: 'phase-draft',
      phase: 'specify',
      path: 'D:/tmp/foo/.aethercode/sdd/foo/spec.md',
      bytes: 1234,
      preview: '# spec\nbody',
    }));
    expect(ev).not.toBeNull();
    expect(ev!.kind).toBe('phase-draft');
    if (ev!.kind === 'phase-draft') {
      expect(ev.path).toBe('D:/tmp/foo/.aethercode/sdd/foo/spec.md');
      expect(ev.preview).toBe('# spec\nbody');
    }
  });

  it('normalises daemon `event: complete`', () => {
    const ev = safeParseSsdEvent(JSON.stringify({
      event: 'complete',
      results: [],
    }));
    expect(ev).not.toBeNull();
    expect(ev!.kind).toBe('complete');
  });

  it('returns null for unknown event kinds', () => {
    expect(safeParseSsdEvent(JSON.stringify({ event: 'mystery-event' }))).toBeNull();
    expect(safeParseSsdEvent(JSON.stringify({ kind: 'mystery-kind' }))).toBeNull();
  });

  it('returns null for malformed JSON', () => {
    expect(safeParseSsdEvent('not json')).toBeNull();
    expect(safeParseSsdEvent('')).toBeNull();
  });

  it('returns null when neither `kind` nor `event` is present', () => {
    expect(safeParseSsdEvent(JSON.stringify({ foo: 'bar' }))).toBeNull();
  });

  it('`kind` wins when both `kind` and `event` are present', () => {
    // Defensive: if a future migration leaves a line with
    // both keys (e.g. transitional daemon build), the
    // renderer-native `kind` field is authoritative.
    const ev = safeParseSsdEvent(JSON.stringify({
      kind: 'phase-start',
      event: 'phase-draft', // contradictory
      phase: 'specify',
    }));
    expect(ev).not.toBeNull();
    expect(ev!.kind).toBe('phase-start');
  });

  it('handles all daemon event kinds end-to-end (sanity sweep)', () => {
    // Regression net: every event type the daemon emits
    // must round-trip through the parser. If a new event
    // kind is added on the daemon side and the switch
    // table isn't updated, this test will fail.
    const events = [
      'phase-list', 'phase-start', 'phase-draft',
      'phase-revising', 'phase-accepted', 'phase-skipped',
      'phase-error', 'clarify-question', 'analysis',
      'converge-check', 'complete', 'abort', 'error', 'log',
    ];
    for (const k of events) {
      const ev = safeParseSsdEvent(JSON.stringify({ event: k }));
      expect(ev, `event=${k} should parse`).not.toBeNull();
      expect(ev!.kind).toBe(k);
    }
  });
});