import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * store wiring for the daemon-side
 * auto-approve-low-risk short-circuit.
 *
 * <p>Three pieces of state on the AppState:
 * <ol>
 *   <li>{@code autoApproveLowRisk} — the flag
 *       (default true; flipped by
 *       {@code setAutoApproveLowRisk}).</li>
 *   <li>{@code autoApprovedCount} — the cumulative
 *       count, mirrored from the daemon's
 *       notification payload (or from the
 *       setAutoApproveLowRisk RPC return).</li>
 *   <li>{@code recentAutoApproved} — a rolling
 *       buffer of the 10 most-recent
 *       auto-approved tool calls. Drives the
 *       StatusBar tooltip and the RpcDiagnosticsPanel
 *       filter.</li>
 * </ol>
 *
 * <p>These tests pin the wiring so a refactor that
 * drops the listener, the cap, or the optimistic
 * update gets caught.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

describe('R120: AppState declares the three R120 fields', () => {
  const storeSrc = readFileSync(join(root, 'src', 'store', 'index.ts'), 'utf-8');

  it('autoApproveLowRisk is on the AppState interface', () => {
    // Field name must match across the
    // interface, the initial state, the
    // listener, and the action — a typo in
    // any one breaks the badge.
    expect(storeSrc).toMatch(/autoApproveLowRisk:\s*boolean/);
  });

  it('autoApprovedCount is on the AppState interface', () => {
    expect(storeSrc).toMatch(/autoApprovedCount:\s*number/);
  });

  it('recentAutoApproved is on the AppState interface (capped array)', () => {
    expect(storeSrc).toMatch(/recentAutoApproved:\s*\{[^}]*tool[^}]*\}\[\]/);
  });

  it('initial state seeds all three R120 fields', () => {
    // Default flag = true (R87's "read-only never
    // asks" backward compat). Count starts at 0.
    // Recent list is empty.
    expect(storeSrc).toMatch(/autoApproveLowRisk:\s*true/);
    expect(storeSrc).toMatch(/autoApprovedCount:\s*0/);
    expect(storeSrc).toMatch(/recentAutoApproved:\s*\[\]/);
  });
});

describe('R120: setAutoApproveLowRisk action', () => {
  const storeSrc = readFileSync(join(root, 'src', 'store', 'index.ts'), 'utf-8');

  it('declares setAutoApproveLowRisk on AppState', () => {
    expect(storeSrc).toMatch(/setAutoApproveLowRisk:\s*\(enabled:\s*boolean\)\s*=>\s*Promise<void>/);
  });

  it('action calls rpc.setAutoApproveLowRisk with the new flag', () => {
    const block = storeSrc.match(/setAutoApproveLowRisk:\s*async\s*\(enabled\)\s*=>\s*\{[\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toContain('await rpc.setAutoApproveLowRisk({ enabled })');
  });

  it('action mirrors the daemon value on success (no stale local state)', () => {
    // The daemon is the source of truth. The
    // action must NOT apply an optimistic update
    // before the RPC resolves; instead it
    // sets { autoApproveLowRisk: r.enabled,
    // autoApprovedCount: r.autoApprovedCount }
    // from the response.
    const block = storeSrc.match(/setAutoApproveLowRisk:\s*async\s*\(enabled\)\s*=>\s*\{[\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toMatch(/autoApproveLowRisk:\s*!!r\.enabled/);
    expect(block![0]).toContain('autoApprovedCount');
  });

  it('action is wrapped in try/catch and does not throw on RPC failure', () => {
    // A failed setAutoApproveLowRisk RPC should
    // log + return; the next refreshEngineState
    // tick will resync. The action must NOT
    // re-throw or the StatusBar click would
    // surface a console error.
    const block = storeSrc.match(/setAutoApproveLowRisk:\s*async\s*\(enabled\)\s*=>\s*\{[\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toMatch(/catch\s*\(/);
    expect(block![0]).toContain("console.warn('setAutoApproveLowRisk failed:'");
  });
});

describe('R120: permission_auto_approved notification listener', () => {
  const storeSrc = readFileSync(join(root, 'src', 'store', 'index.ts'), 'utf-8');

  it('registers a listener for permission_auto_approved', () => {
    // The listener is the path through which
    // the daemon tells the renderer "I just
    // auto-allowed this". Without it, the
    // StatusBar count would never increment.
    expect(storeSrc).toMatch(/rpc\.on\(\s*['"]permission_auto_approved['"]/);
  });

  it('listener prefers the daemon-supplied autoApprovedCount when present', () => {
    // The notification payload carries the
    // post-increment count from the daemon.
    // The listener must prefer that value
    // (so multi-client scenarios stay in
    // sync) and fall back to +1 only when
    // the daemon didn't ship one.
    const block = storeSrc.match(/rpc\.on\(\s*['"]permission_auto_approved['"][\s\S]*?^\s{2}\}\);/m);
    expect(block).toBeTruthy();
    expect(block![0]).toMatch(/typeof p\.autoApprovedCount === 'number'[\s\S]*?\? p\.autoApprovedCount[\s\S]*?:\s*s\.autoApprovedCount\s*\+\s*1/);
  });

  it('listener drops notifications without a tool name', () => {
    // A malformed notification (no tool) is
    // dropped silently. The guard prevents
    // an empty-string from polluting
    // recentAutoApproved.
    const block = storeSrc.match(/rpc\.on\(\s*['"]permission_auto_approved['"][\s\S]*?^\s{2}\}\);/m);
    expect(block).toBeTruthy();
    expect(block![0]).toMatch(/if\s*\(!p\?\.tool\)\s*return/);
  });

  it('listener caps recentAutoApproved at 10 entries', () => {
    // The cap is what keeps the StatusBar
    // tooltip from blowing up after a long
    // session of grep-spam. .slice(0, 10) is
    // the literal the test pins.
    const block = storeSrc.match(/rpc\.on\(\s*['"]permission_auto_approved['"][\s\S]*?^\s{2}\}\);/m);
    expect(block).toBeTruthy();
    expect(block![0]).toContain('.slice(0, 10)');
  });

  it('listener records tool + atMs on each entry', () => {
    const block = storeSrc.match(/rpc\.on\(\s*['"]permission_auto_approved['"][\s\S]*?^\s{2}\}\);/m);
    expect(block).toBeTruthy();
    expect(block![0]).toMatch(/tool:\s*p\.tool/);
    expect(block![0]).toMatch(/atMs:\s*p\.atMs\s*\?\?\s*Date\.now\(\)/);
  });

  it('listener inserts new entries at the head (most-recent first)', () => {
    // The StatusBar tooltip shows the most
    // recent first. Insert at index 0 so
    // .slice(0, 5) returns the latest 5 in
    // time order.
    const block = storeSrc.match(/rpc\.on\(\s*['"]permission_auto_approved['"][\s\S]*?^\s{2}\}\);/m);
    expect(block).toBeTruthy();
    // The literal pattern: [{ tool, atMs }, ...s.recentAutoApproved]
    expect(block![0]).toMatch(/\{\s*tool:\s*p\.tool!\s*,\s*atMs:[^}]+\},\s*\.\.\.\s*s\.recentAutoApproved/);
  });
});
