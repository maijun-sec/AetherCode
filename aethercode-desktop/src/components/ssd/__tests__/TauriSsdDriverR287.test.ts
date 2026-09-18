// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';

/**
 * R287: TauriSsdDriver unit tests.
 *
 * <p>The driver wraps `@tauri-apps/plugin-shell`'s
 * {@link Command} — we don't try to spawn a real
 * JVM in jsdom (the plugin's Command.spawn()
 * calls `__TAURI_INTERNALS__.invoke`, which is
 * undefined outside the Tauri runtime). Instead
 * we exercise:
 * <ol>
 *   <li>{@link safeParseSsdEvent} — the JSONL
 *       parser the driver uses on every stdout
 *       line. Pure function, fully testable.</li>
 *   <li>The driver's module shape — imports
 *       clean in jsdom so the panel's "demo
 *       driver" path still renders even when
 *       the Tauri runtime isn't initialised.</li>
 * </ol>
 *
 * <p>This is the same approach the R281
 * {@link MockSsdDriver} tests use — the panel
 * doesn't know which driver is wired, so the
 * behaviour the panel sees (the event dispatch
 * + the JSONL parsing) is what matters.
 */
import { safeParseSsdEvent, TauriSsdDriver } from '../tauriSsdDriver';

describe('R287: safeParseSsdEvent', () => {
  it('parses phase-list', () => {
    const ev = safeParseSsdEvent(
      '{"kind":"phase-list","feature":"foo","phases":[{"id":"spec","order":1,"title":"Spec"}]}',
    );
    expect(ev).not.toBeNull();
    expect(ev!.kind).toBe('phase-list');
  });

  it('parses phase-draft with all fields', () => {
    const ev = safeParseSsdEvent(
      '{"kind":"phase-draft","phase":"spec","path":"/tmp/spec.md","bytes":42,"preview":"# Spec"}',
    );
    expect(ev).not.toBeNull();
    expect(ev!.kind).toBe('phase-draft');
    if (ev!.kind === 'phase-draft') {
      expect(ev!.path).toBe('/tmp/spec.md');
      expect(ev!.preview).toBe('# Spec');
      expect(ev!.bytes).toBe(42);
    }
  });

  it('parses phase-accepted with revisionCount', () => {
    const ev = safeParseSsdEvent(
      '{"kind":"phase-accepted","phase":"spec","revisionCount":2}',
    );
    expect(ev).not.toBeNull();
    if (ev!.kind === 'phase-accepted') {
      expect(ev!.revisionCount).toBe(2);
    }
  });

  it('parses complete + abort + error + log', () => {
    for (const line of [
      '{"kind":"complete","feature":"foo","results":[]}',
      '{"kind":"abort","reason":"user quit"}',
      '{"kind":"error","message":"java not found"}',
      '{"kind":"log","level":"info","message":"starting spec phase"}',
    ]) {
      const ev = safeParseSsdEvent(line);
      expect(ev).not.toBeNull();
      expect(ev!.kind).not.toBe('phase-list');
    }
  });

  it('returns null for malformed JSON', () => {
    expect(safeParseSsdEvent('this is not json')).toBeNull();
    expect(safeParseSsdEvent('')).toBeNull();
  });

  it('returns null for unknown kind', () => {
    // The daemon's own InteractiveRepl drops
    // unknown kinds silently; the driver does
    // the same so a forward-compat daemon doesn't
    // crash the panel.
    expect(
      safeParseSsdEvent('{"kind":"some-future-kind","foo":"bar"}'),
    ).toBeNull();
  });

  it('returns null when kind is missing', () => {
    expect(safeParseSsdEvent('{"foo":"bar"}')).toBeNull();
  });

  it('tolerates trailing whitespace and newlines', () => {
    // The Tauri shell plugin strips them on its
    // end, but the daemon emits lines with
    // optional trailing \r\n. Be tolerant.
    expect(
      safeParseSsdEvent('  {"kind":"phase-list","feature":"x","phases":[]}\n'),
    ).not.toBeNull();
  });
});

describe('R287: TauriSsdDriver module shape', () => {
  it('module imports cleanly in jsdom', async () => {
    // The driver imports `@tauri-apps/plugin-shell`
    // at module load. In jsdom the plugin's
    // runtime isn't initialised; we verify the
    // module-level imports don't throw so the
    // panel can render the "demo driver" path
    // even when the shell plugin isn't wired.
    const mod = await import('../tauriSsdDriver');
    expect(typeof mod.safeParseSsdEvent).toBe('function');
    expect(typeof mod.TauriSsdDriver).toBe('function');
  });

  it('constructor accepts the Options contract', () => {
    // Just constructing one with a stub
    // {@code readDraft} should NOT throw at
    // module load — start() is what actually
    // talks to the Tauri runtime. The driver
    // itself doesn't initialise the Command
    // until start(), so a static constructor
    // call is safe.
    const driver = new TauriSsdDriver({
      jarPath: '/tmp/fake.jar',
      feature: 'test',
      intent: 'unit test',
      cwd: '/tmp',
      readDraft: async () => 'mock draft body',
    });
    expect(typeof driver.start).toBe('function');
    expect(typeof driver.stop).toBe('function');
    expect(typeof driver.sendCommand).toBe('function');
    expect(typeof driver.fetchDraft).toBe('function');
    expect(typeof driver.onEvent).toBe('function');
  });

  it('fetchDraft uses the injected readDraft helper', async () => {
    const driver = new TauriSsdDriver({
      jarPath: '/tmp/fake.jar',
      feature: 'test',
      intent: 'unit test',
      cwd: '/tmp',
      readDraft: async (p: string) => `body for ${p}`,
    });
    const body = await driver.fetchDraft('/tmp/spec.md');
    expect(body).toBe('body for /tmp/spec.md');
  });

  it('onEvent returns a teardown function that unsubscribes', () => {
    const driver = new TauriSsdDriver({
      jarPath: '/tmp/fake.jar',
      feature: 'test',
      intent: 'unit test',
      cwd: '/tmp',
    });
    const handler = (): void => { /* noop */ };
    const off = driver.onEvent(handler);
    expect(typeof off).toBe('function');
    // teardown is idempotent
    off();
    off();
  });

  it('sendCommand is a no-op when stopped (no child handle)', () => {
    const driver = new TauriSsdDriver({
      jarPath: '/tmp/fake.jar',
      feature: 'test',
      intent: 'unit test',
      cwd: '/tmp',
    });
    // No start() → no child handle → sendCommand
    // silently drops. The panel relies on this
    // when it calls stop() before the spawn
    // resolves.
    expect(() => driver.sendCommand({ action: 'accept' })).not.toThrow();
    expect(() => driver.sendCommand({ action: 'quit' })).not.toThrow();
  });
});