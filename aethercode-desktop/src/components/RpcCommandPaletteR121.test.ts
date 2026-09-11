import { describe, it, expect } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * raw-RPC command palette. The companion
 * to the prior round RpcDiagnosticsPanel: the diagnostic
 * panel is read-only (you watch what the daemon
 * sees), the command palette is write (you make
 * the daemon do something the UI doesn't have a
 * button for).
 *
 * <p>These tests pin the wiring — hotkey, store
 * action, fetch URL shape, RPC call path, JSON
 * validation surface — so a future refactor that
 * drops any one gets caught.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

/** Extract the body of the {@code onKey} handler
 *  from the palette source. The handler is
 *  indented at 2 spaces, so the regex anchors on
 *  the line that starts with "  };" (2 spaces
 *  + closing brace + semicolon) at column 0 of
 *  the matched line. This is more robust than a
 *  whitespace-strict match against a multi-line
 *  block that often has the closing brace at a
 *  different column than expected. */
function extractOnKey(src: string): string | null {
  // Slice from "const onKey" to the first "};"
  // at the start of a line. The regex is
  // intentionally not whitespace-strict on the
  // closing brace.
  const start = src.indexOf('const onKey');
  if (start < 0) return null;
  const tail = src.slice(start);
  const m = tail.match(/^  \};$/m);
  if (!m || m.index === undefined) return null;
  return tail.slice(0, m.index + m[0].length);
}

describe('R121: RpcCommandPalette component + CSS exist', () => {
  it('RpcCommandPalette.tsx exists', () => {
    expect(existsSync(join(root, 'src', 'components', 'RpcCommandPalette.tsx'))).toBe(true);
  });

  it('RpcCommandPalette.css exists', () => {
    expect(existsSync(join(root, 'src', 'components', 'RpcCommandPalette.css'))).toBe(true);
  });
});

describe('R121: RpcCommandPalette wiring', () => {
  const tsxSrc = readFileSync(join(root, 'src', 'components', 'RpcCommandPalette.tsx'), 'utf-8');

  it('exports the named RpcCommandPalette function', () => {
    expect(tsxSrc).toMatch(/export function RpcCommandPalette\(/);
  });

  it('takes an onClose prop (same shape as the R88 CommandPalette)', () => {
    // The parent component controls visibility; the
    // palette owns nothing about its own mount. Same
    // contract as the R88 CommandPalette so the
    // App.tsx render block is symmetric.
    expect(tsxSrc).toMatch(/onClose:\s*\(\)\s*=>\s*void/);
  });

  it('pulls rpcMethods + loadRpcMethods from the store', () => {
    // The list is lazy-loaded by the store action;
    // the component just reads + triggers on open.
    // Mirrors the prior round refreshTools pattern
    // (component pulls + displays; store owns the
    // fetch + cache).
    expect(tsxSrc).toContain('rpcMethods');
    expect(tsxSrc).toContain('loadRpcMethods');
  });

  it('imports the rpc singleton from lib/methods', () => {
    // The actual call goes through AetherCodeRpc.call
    // (R116's instrumentation sees it via the
    // rpc.onRpcEvent subscription). The alternative —
    // using fetch() directly — would bypass the
    // diagnostic panel's recentRpcEvents log.
    expect(tsxSrc).toMatch(/import\s*\{[^}]*\brpc\b[^}]*\}\s*from\s*['"]\.\.\/lib\/methods['"]/);
  });

  it('calls rpc.call(selected, parsed) on execute', () => {
    // The execute path:
    //   1. JSON.parse(paramsText)
    //   2. await rpc.call(method, params)
    //   3. setResult({ ok, body, atMs })
    // The test pins the literal call shape so a
    // refactor that bypasses rpc.call (e.g. direct
    // fetch to /ws) gets caught — that would silently
    // drop the call from the R116 diagnostic panel.
    expect(tsxSrc).toContain('await rpc.call(selected, parsed');
  });

  it('handles execute failure (sets ok: false)', () => {
    // Errors land in the result pane with a red
    // border; the user can read the JSON-RPC error
    // body without losing the palette state.
    expect(tsxSrc).toMatch(/catch\s*\(e\)\s*\{[\s\S]*?setResult\(\{ ok: false, body: e/);
  });

  it('validates params JSON inline (sets paramsError)', () => {
    // The textarea has a useEffect that parses on
    // every change; a parse failure sets paramsError
    // which gates the Execute button. The error
    // message is the SyntaxError.message so the
    // user can find the typo.
    expect(tsxSrc).toMatch(/JSON\.parse\(paramsText\)/);
    expect(tsxSrc).toContain('setParamsError((e as Error).message)');
  });

  it('Enter in the search input fires execute; Enter in the textarea does not', () => {
    // The user types in the textarea to write
    // multi-line JSON; Enter there means a
    // newline, not "fire RPC". The handler checks
    // whether the event target is a textarea
    // and bails in that case.
    const keyHandler = extractOnKey(tsxSrc);
    expect(keyHandler).toBeTruthy();
    expect(keyHandler).toContain("e.target instanceof HTMLTextAreaElement");
  });

  it('Escape closes the palette; if a result is showing, Escape clears it first', () => {
    // The R119 lesson applied: a state-changing
    // panel shouldn't lose its state on a single
    // Esc. The user gets two Esc presses: one to
    // dismiss the result, one to close the
    // palette. This matches SubagentToast's
    // persistent-then-dismissed UX.
    const keyHandler = extractOnKey(tsxSrc);
    expect(keyHandler).toBeTruthy();
    expect(keyHandler).toMatch(/if\s*\(result\)\s*\{\s*setResult\(null\);\s*return;\s*\}/);
  });

  it('filters methods by case-insensitive substring', () => {
    // "setM" should match "setModel" + "setMemory" etc.
    // The filter is .toLowerCase().includes(q) — the
    // simplest reasonable thing. R124 renamed the
    // loop variable from `m` to `name` (the loop
    // now also consults rpcMethodInfos) but the
    // method name filter is still substring
    // case-insensitive.
    expect(tsxSrc).toMatch(/\.toLowerCase\(\)\.includes\(q\)/);
  });

  it('renders a result pane with ok / err variants', () => {
    // Two className branches: is-ok (green left
    // border) and is-err (red left border). The
    // body is JSON.stringify'd with 2-space indent
    // so it's readable inline.
    expect(tsxSrc).toContain("`rpc-palette-result ${result.ok ? 'is-ok' : 'is-err'}`");
    expect(tsxSrc).toContain('JSON.stringify(result.body, null, 2)');
  });

  it('disables Execute button while executing or when params JSON is invalid', () => {
    // Two guards: executing state (no double-fire
    // from a slow network) and paramsError (no
    // malformed JSON to the daemon). The button's
    // text also reflects the executing state so
    // the user has feedback.
    expect(tsxSrc).toMatch(/disabled=\{!!paramsError \|\| executing\}/);
    expect(tsxSrc).toContain("executing ? '执行中…' :");
  });

  it('lazy-loads the method list on mount (one-shot fetch)', () => {
    // The useEffect calls loadRpcMethods() on
    // mount. The store's "already have it" guard
    // short-circuits subsequent opens. The test
    // pins the literal `loadRpcMethods` call site
    // — a refactor that drops the useEffect
    // would leave the palette empty until the
    // user clicks a ↻ button.
    expect(tsxSrc).toMatch(/useEffect\(\(\)\s*=>\s*\{\s*void loadRpcMethods\(\);\s*\},\s*\[loadRpcMethods\]\)/);
  });
});

describe('R121: RpcCommandPalette CSS for params + result', () => {
  const cssSrc = readFileSync(join(root, 'src', 'components', 'RpcCommandPalette.css'), 'utf-8');

  it('defines a .rpc-palette base style', () => {
    expect(cssSrc).toContain('.rpc-palette');
  });

  it('defines .has-error for the invalid-JSON state', () => {
    // Red border + soft red background tint so
    // the user sees the parse failure
    // immediately, not after they click Execute.
    expect(cssSrc).toContain('.rpc-palette-params-input.has-error');
  });

  it('defines .is-ok and .is-err for the result pane', () => {
    // The result pane's left border signals
    // success vs failure at a glance, even
    // before the user reads the body.
    expect(cssSrc).toContain('.rpc-palette-result.is-ok');
    expect(cssSrc).toContain('.rpc-palette-result.is-err');
  });

  it('defines a .rpc-palette-execute button with disabled state', () => {
    // The button has an active variant
    // (accent colour) and a disabled variant
    // (muted). The disabled state is the same
    // grey as the border, so a user who can't
    // tell why Execute is greyed out can hover
    // for a cursor:not-allowed hint.
    expect(cssSrc).toContain('.rpc-palette-execute');
    expect(cssSrc).toContain('.rpc-palette-execute:disabled');
    expect(cssSrc).toContain('cursor: not-allowed');
  });
});

describe('R121: App.tsx wires Ctrl/Cmd+Shift+K', () => {
  const appSrc = readFileSync(join(root, 'src', 'App.tsx'), 'utf-8');

  it('imports RpcCommandPalette', () => {
    expect(appSrc).toMatch(/import\s*\{\s*RpcCommandPalette\s*\}\s*from\s*['"]\.\/components\/RpcCommandPalette['"]/);
  });

  it('declares showRpcPalette state', () => {
    expect(appSrc).toMatch(/const\s*\[showRpcPalette,\s*setShowRpcPalette\]\s*=\s*useState\(false\)/);
  });

  it('hotkey is Ctrl/Cmd+Shift+K (R88 K + Shift, not the bare K)', () => {
    // R88 reserved Ctrl/Cmd+K for the session
    // palette. R121 uses Shift+K to keep the
    // common chord intact.
    const keyHandler = appSrc.match(/handler[\s\S]*?window\.addEventListener\('keydown'[\s\S]*?window\.removeEventListener\('keydown'[\s\S]*?\}\s*,\s*\[/);
    expect(keyHandler).toBeTruthy();
    // The R121 branch must check both shiftKey and
    // key === 'k' / 'K'. Order matters in the
    // source (R111's Shift+P must come first so
    // it doesn't catch P+K).
    const r121Branch = appSrc.match(/\(e\.key === ['"]k['"] \|\| e\.key === ['"]K['"]\) && e\.shiftKey/);
    expect(r121Branch).toBeTruthy();
  });

  it('renders the palette when showRpcPalette is true', () => {
    expect(appSrc).toContain('{showRpcPalette && <RpcCommandPalette onClose={() => setShowRpcPalette(false)} />}');
  });
});

describe('R121: store exposes loadRpcMethods + rpcMethods', () => {
  const storeSrc = readFileSync(join(root, 'src', 'store', 'index.ts'), 'utf-8');

  it('declares rpcMethods on AppState', () => {
    expect(storeSrc).toMatch(/rpcMethods:\s*string\[\]/);
  });

  it('declares loadRpcMethods on AppState', () => {
    expect(storeSrc).toMatch(/loadRpcMethods:\s*\(\)\s*=>\s*Promise<void>/);
  });

  it('initial state seeds rpcMethods as an empty array', () => {
    // The list is lazy-loaded on palette open, so
    // the initial value is empty. The first open
    // triggers the fetch.
    expect(storeSrc).toMatch(/rpcMethods:\s*\[\]/);
  });

  it('loadRpcMethods action does a single GET /api/methods (idempotent guard)', () => {
    // The action is best-effort and
    // idempotent: a re-open of the palette
    // doesn't re-fetch.
    const block = storeSrc.match(/loadRpcMethods:\s*async\s*\(\)\s*=>\s*\{[\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toContain('if (get().rpcMethods.length > 0) return;');
    expect(block![0]).toContain("fetch(`${info.httpUrl}/api/methods`");
  });

  it('action filters the response to strings + dedupes + sorts', () => {
    // Defensive: a future /api/methods that
    // returns mixed types or unsorted names
    // shouldn't break the palette's filter UX.
    const block = storeSrc.match(/loadRpcMethods:\s*async\s*\(\)\s*=>\s*\{[\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toMatch(/typeof m === 'string'/);
    expect(block![0]).toContain('[...new Set(');
    expect(block![0]).toContain('.sort()');
  });
});
