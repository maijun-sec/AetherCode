import { describe, it, expect } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * RPC diagnostics panel.
 *
 * The R114 investigation relied on direct `ws`
 * probes; an in-app panel would let the user
 * self-diagnose the next "tools 显示为空" without
 * external help. The R116 round ships a modal that
 * shows the last 50 RPC round-trips (method,
 * params, duration, status, timestamp) with a
 * filter row and an expandable payload.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

describe('R116: rpc.onRpcEvent subscription', () => {
  const tsSrc = readFileSync(join(root, 'src', 'lib', 'methods.ts'), 'utf-8');

  it('declares the RpcEvent type', () => {
    expect(tsSrc).toMatch(/export\s+interface\s+RpcEvent\s*\{/);
    expect(tsSrc).toMatch(/durationMs:\s*number/);
    expect(tsSrc).toMatch(/success:\s*boolean/);
    expect(tsSrc).toMatch(/method:\s*string/);
  });

  it('AetherCodeRpc has an onRpcEvent(handler) → unsubscribe() method', () => {
    expect(tsSrc).toMatch(/onRpcEvent\(handler:\s*\(e:\s*RpcEvent\)\s*=>\s*void\)/);
    expect(tsSrc).toMatch(/return\s*\(\)\s*=>\s*\{\s*this\.rpcEventHandlers\.delete\(handler\)/);
  });

  it('AetherCodeRpc has a private emitRpcEvent helper', () => {
    expect(tsSrc).toMatch(/private\s+emitRpcEvent\(e:\s*RpcEvent\)/);
  });

  it('call() records start time, params snapshot, duration, and status', () => {
    // The rpc.call method should be wrapped to emit
    // an RpcEvent on both success and failure paths.
    // The body must include:
    //  - performance.now() start
    //  - JSON-clone of params (for human display)
    //  - duration measurement
    //  - success / error propagation
    const block = tsSrc.match(/async\s+call<T\s*=\s*unknown>\s*\(\s*method:\s*string,\s*params\?:\s*unknown\s*\)\s*:\s*Promise<T>\s*\{[\s\S]*?^\s{2}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toMatch(/performance\.now\(\)/);
    expect(block![0]).toMatch(/JSON\.parse\(JSON\.stringify\(params\)\)/);
    expect(block![0]).toMatch(/emitRpcEvent/);
    expect(block![0]).toMatch(/success:\s*true/);
    expect(block![0]).toMatch(/success:\s*false/);
  });

  it('call() emits on the failure path (rethrow the error after)', () => {
    // The catch block must emitRpcEvent with
    // success: false AND the error message, then
    // re-throw so the caller still gets the
    // rejected promise.
    const block = tsSrc.match(/async\s+call<T\s*=\s*unknown>\s*\(\s*method:\s*string,\s*params\?:\s*unknown\s*\)\s*:\s*Promise<T>\s*\{[\s\S]*?^\s{2}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toMatch(/throw\s+e;/);
    expect(block![0]).toMatch(/error:\s*e\?\.message/);
  });
});

describe('R116: store — recentRpcEvents field + recordRpcEvent', () => {
  const storeSrc = readFileSync(join(root, 'src', 'store', 'index.ts'), 'utf-8');

  it('declares recentRpcEvents: RpcEvent[] on the AppState interface', () => {
    expect(storeSrc).toMatch(/recentRpcEvents:\s*RpcEvent\[\]/);
  });

  it('initial state seeds recentRpcEvents: []', () => {
    expect(storeSrc).toMatch(/recentRpcEvents:\s*\[\]/);
  });

  it('declares recordRpcEvent + clearRpcEvents actions', () => {
    expect(storeSrc).toMatch(/recordRpcEvent:\s*\(e:\s*RpcEvent\)\s*=>\s*void/);
    expect(storeSrc).toMatch(/clearRpcEvents:\s*\(\)\s*=>\s*void/);
  });

  it('recordRpcEvent caps the buffer at 50 (slice(0, 50))', () => {
    // The cap is a magic number; the test pins it so
    // a future bump has a conscious code change.
    const block = storeSrc.match(/recordRpcEvent:\s*\(e\)\s*=>\s*\{[\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toMatch(/\.slice\(0,\s*50\)/);
  });

  it('recordRpcEvent prepends (newest-first ordering)', () => {
    const block = storeSrc.match(/recordRpcEvent:\s*\(e\)\s*=>\s*\{[\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toMatch(/\[e,\s*\.\.\.s\.recentRpcEvents\]/);
  });

  it('initialize() subscribes to rpc.onRpcEvent (with re-init unsubscribe)', () => {
    // The subscription must:
    //  1. Unsubscribe the previous listener (if any)
    //     to avoid double-counting on reconnect
    //  2. Install a new listener that calls
    //     get().recordRpcEvent
    const initBlock = storeSrc.match(/if\s*\(rpcEventUnsubscribe\)\s*rpcEventUnsubscribe\(\);[\s\S]*?rpcEventUnsubscribe\s*=\s*rpc\.onRpcEvent/);
    expect(initBlock).toBeTruthy();
  });
});

describe('R116: RpcDiagnosticsPanel', () => {
  const tsxSrc = readFileSync(join(root, 'src', 'components', 'RpcDiagnosticsPanel.tsx'), 'utf-8');
  const cssSrc = readFileSync(join(root, 'src', 'components', 'RpcDiagnosticsPanel.css'), 'utf-8');
  const appSrc = readFileSync(join(root, 'src', 'App.tsx'), 'utf-8');

  it('RpcDiagnosticsPanel.tsx exists', () => {
    expect(existsSync(join(root, 'src', 'components', 'RpcDiagnosticsPanel.tsx'))).toBe(true);
  });

  it('RpcDiagnosticsPanel.css exists', () => {
    expect(existsSync(join(root, 'src', 'components', 'RpcDiagnosticsPanel.css'))).toBe(true);
  });

  it('reads recentRpcEvents + clearRpcEvents from the store', () => {
    expect(tsxSrc).toMatch(/recentRpcEvents/);
    expect(tsxSrc).toMatch(/clearRpcEvents/);
  });

  it('renders a status filter (all / ok / err)', () => {
    expect(tsxSrc).toContain('StatusFilter');
    expect(tsxSrc).toMatch(/rpc-diag-status-ok/);
    expect(tsxSrc).toMatch(/rpc-diag-status-err/);
  });

  it('renders a method-name filter input', () => {
    expect(tsxSrc).toContain('rpc-diag-filter');
    expect(tsxSrc).toMatch(/Filter by method name/);
  });

  it('has an Esc hotkey to close', () => {
    expect(tsxSrc).toMatch(/e\.key\s*===\s*['"]Escape['"]/);
  });

  it('expands a row to show params + error (click handler)', () => {
    expect(tsxSrc).toMatch(/onClick=\{onToggle\}/);
    expect(tsxSrc).toMatch(/formatJson/);
  });

  it('shows a Clear button that calls clearRpcEvents', () => {
    expect(tsxSrc).toMatch(/onClick=\{\(\) => clearRpcEvents\(\)\}/);
  });

  it('CSS defines the overlay / panel / row / status-toggle styles', () => {
    expect(cssSrc).toContain('.rpc-diag-overlay');
    expect(cssSrc).toContain('.rpc-diag-panel');
    expect(cssSrc).toContain('.rpc-diag-row');
    expect(cssSrc).toContain('.rpc-diag-status-toggle');
    expect(cssSrc).toContain('.rpc-diag-row-err');
  });

  it('App.tsx imports RpcDiagnosticsPanel', () => {
    expect(appSrc).toMatch(/import\s*\{\s*RpcDiagnosticsPanel\s*\}\s+from\s+['"]\.\/components\/RpcDiagnosticsPanel['"]/);
  });

  it('App.tsx renders <RpcDiagnosticsPanel> when showRpcDiag is true', () => {
    expect(appSrc).toMatch(/showRpcDiag\s*&&\s*<RpcDiagnosticsPanel/);
  });

  it('App.tsx has a Ctrl/Cmd+` hotkey (e.code === Backquote)', () => {
    // e.code (not e.key) so the chord works on
    // non-US layouts (AZERTY etc.).
    expect(appSrc).toMatch(/e\.code\s*===\s*['"]Backquote['"]/);
    expect(appSrc).toMatch(/setShowRpcDiag\(\s*\(v\)\s*=>\s*!v\s*\)/);
  });
});
