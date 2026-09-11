import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * runtime loop-detector threshold tweak +
 * Settings panel sliders.
 *
 * The "loop 检测太敏感" complaint (R114 follow-up)
 * hinged on the fact that legacy the only way to
 * change the loop detector's window / threshold
 * was to re-spawn the JVM. R115 adds a
 * `setLoopDetectorThresholds` RPC + a Settings
 * panel section that nudges it live.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

describe('R115: Java backend — setLoopDetectorThresholds RPC', () => {
  const javaSrc = readFileSync(
    join(root, '..', 'aethercode', 'aethercode-protocol', 'src', 'main', 'java', 'org', 'aethercode', 'protocol', 'methods', 'AetherCodeMethods.java'),
    'utf-8',
  );

  it('registers setLoopDetectorThresholds in the dispatcher', () => {
    expect(javaSrc).toMatch(/dispatcher\.register\(\s*['"]setLoopDetectorThresholds['"]\s*,/);
  });

  it('public method setLoopDetectorThresholds exists', () => {
    expect(javaSrc).toMatch(/public\s+Object\s+setLoopDetectorThresholds\s*\(\s*Object\s+params\s*\)/);
  });

  it('validates window >= threshold (rejects inverse pairs)', () => {
    // The method should return ok=false with a
    // reason if the user picks a threshold larger
    // than the window. This is a guard against
    // misconfigured sliders.
    const block = javaSrc.match(/public\s+Object\s+setLoopDetectorThresholds\s*\(\s*Object\s+params\s*\)\s*\{[\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toMatch(/window\s*<\s*threshold/);
    expect(block![0]).toMatch(/window must be >= threshold/);
  });

  it('routes through target.queryEngine().setLoopDetector', () => {
    // The implementation must delegate to
    // QueryEngine.setLoopDetector so the runtime
    // volatile fields are updated (and the next
    // query() reads them).
    const block = javaSrc.match(/public\s+Object\s+setLoopDetectorThresholds\s*\(\s*Object\s+params\s*\)\s*\{[\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toMatch(/target\.queryEngine\(\)\.setLoopDetector\(/);
  });

  it('returns disabled=true when window or threshold is 0', () => {
    const block = javaSrc.match(/public\s+Object\s+setLoopDetectorThresholds\s*\(\s*Object\s+params\s*\)\s*\{[\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toContain('disabled');
  });
});

describe('R115: HTTP/WS dispatcher — setLoopDetectorThresholds case', () => {
  const javaSrc = readFileSync(
    join(root, '..', 'aethercode', 'aethercode-protocol', 'src', 'main', 'java', 'org', 'aethercode', 'protocol', 'http', 'HttpJsonRpcServer.java'),
    'utf-8',
  );

  it('lists setLoopDetectorThresholds in /api/methods', () => {
    expect(javaSrc).toMatch(/['"]setLoopDetectorThresholds['"]/);
  });

  it('has a case in the dispatch switch', () => {
    expect(javaSrc).toMatch(/case\s+['"]setLoopDetectorThresholds['"]\s*->\s*methods\.setLoopDetectorThresholds\(/);
  });
});

describe('R115: renderer — rpc.setLoopDetectorThresholds', () => {
  const tsSrc = readFileSync(join(root, 'src', 'lib', 'methods.ts'), 'utf-8');

  it('declares setLoopDetectorThresholds on the rpc client', () => {
    expect(tsSrc).toMatch(/setLoopDetectorThresholds\(opts:\s*\{[\s\S]*?window:\s*number;[\s\S]*?threshold:\s*number/);
  });

  it('returns disabled / window / threshold on success', () => {
    const block = tsSrc.match(/setLoopDetectorThresholds\(opts:[^)]*\)\s*:\s*Promise<\{[^}]*\}>/);
    expect(block).toBeTruthy();
    expect(block![0]).toContain('disabled');
    expect(block![0]).toContain('window');
    expect(block![0]).toContain('threshold');
  });
});

describe('R115: renderer — store action', () => {
  const storeSrc = readFileSync(join(root, 'src', 'store', 'index.ts'), 'utf-8');

  it('declares setLoopDetectorThresholds in the AppState interface', () => {
    expect(storeSrc).toMatch(/setLoopDetectorThresholds:\s*\(\s*opts:\s*\{[\s\S]*?\}\s*\)\s*=>\s*Promise</);
  });

  it('implementation calls rpc.setLoopDetectorThresholds', () => {
    const block = storeSrc.match(/setLoopDetectorThresholds:\s*async\s*\([\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toContain('rpc.setLoopDetectorThresholds(opts)');
  });

  it('kicks refreshEngineState() after a successful push', () => {
    // R118 invariant: any state-changing RPC
    // kicks a refresh so the canonical state
    // lands within ~50ms (the round-trip time) +
    // the 15s tick.
    const block = storeSrc.match(/setLoopDetectorThresholds:\s*async\s*\([\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toMatch(/void\s+get\(\)\.refreshEngineState\(\)/);
  });

  it('returns the daemon error on failure (no silent swallow)', () => {
    // prior round lesson applied: a failure path must
    // surface the reason to the UI, not just
    // console.warn.
    const block = storeSrc.match(/setLoopDetectorThresholds:\s*async\s*\([\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toMatch(/r\.error/);
    expect(block![0]).toMatch(/return\s*\{\s*ok:\s*false\s*,\s*error/);
  });
});

describe('R115: renderer — Settings panel sliders', () => {
  const tsxSrc = readFileSync(join(root, 'src', 'components', 'SettingsPanel.tsx'), 'utf-8');
  const cssSrc = readFileSync(join(root, 'src', 'components', 'SettingsPanel.css'), 'utf-8');

  it('pulls setLoopDetectorThresholds from the store', () => {
    expect(tsxSrc).toMatch(/setLoopDetectorThresholds\s*[,}\s]/);
  });

  it('renders a Loop detection section', () => {
    expect(tsxSrc).toContain('Loop detection');
  });

  it('has a master enable checkbox', () => {
    // The user can flip the detector off without
    // touching the sliders — the off state maps
    // to (window=0, threshold=0).
    expect(tsxSrc).toMatch(/type="checkbox"[\s\S]*?loopEnabled/);
  });

  it('has two range sliders (window + threshold)', () => {
    expect(tsxSrc).toMatch(/type="range"[\s\S]*?setLoopWindow/);
    expect(tsxSrc).toMatch(/type="range"[\s\S]*?setLoopThreshold/);
  });

  it('debounces the slider → RPC push (250ms)', () => {
    expect(tsxSrc).toMatch(/loopTimerRef\.current\s*=\s*window\.setTimeout\(/);
    expect(tsxSrc).toMatch(/250/);
  });

  it('re-syncs from engineState when the daemon changes the values', () => {
    // The "bind daemon → UI" half of the
    // bidirectional sync — a periodic refresh
    // or a switchProvider side-effect updates
    // the sliders without a remount.
    expect(tsxSrc).toMatch(/engineState\?\.loopWindow[\s\S]*?setLoopWindow\(w\)/);
  });

  it('constrains threshold <= window', () => {
    // The user dragging the window past the current
    // threshold would otherwise make the threshold
    // snap-clamp; the implementation clamps on
    // change.
    expect(tsxSrc).toMatch(/Math\.min\(cur,\s*w\)/);
  });

  it('shows a status pill (pushing / idle / error)', () => {
    expect(tsxSrc).toMatch(/loopStatus === 'pushing'/);
    expect(tsxSrc).toMatch(/loopStatus === 'idle'/);
    expect(tsxSrc).toMatch(/loopStatus === 'error'/);
  });

  it('CSS adds a field-group variant for the slider section', () => {
    expect(cssSrc).toContain('.settings-field-group');
    expect(cssSrc).toContain('.settings-field-inline');
    expect(cssSrc).toContain('.settings-slider-label');
  });
});
