import { describe, it, expect } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * ToolsPanel ↻ refresh + last-refresh meta + empty-state copy.
 *
 * Source-only assertions — the panel itself is a React
 * component that would need a DOM harness to render, which
 * the existing Desktop tests don't do. We verify the wiring
 * (file presence, store hook-up, header buttons, meta line)
 * so any future cleanup that drops the refresh affordance
 * is caught.
 *
 * The "user reported tools empty" bug from R114 is exactly
 * the kind of regression this test guards against: without
 * the ↻ button, an empty pool leaves the user with no way
 * to recover from a failed initialize() RPC.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

describe('对应历史 round: ToolsPanel refresh affordance', () => {
  it('ToolsPanel.tsx exists', () => {
    const path = join(root, 'src', 'components', 'ToolsPanel.tsx');
    expect(existsSync(path)).toBe(true);
  });

  it('ToolsPanel.tsx pulls refreshTools + toolsRefreshedAt from the store', () => {
    const src = readFileSync(join(root, 'src', 'components', 'ToolsPanel.tsx'), 'utf-8');
    expect(src).toMatch(/const\s*\{[^}]*refreshTools[^}]*\}\s*=\s*useStore\(\)/s);
    expect(src).toMatch(/const\s*\{[^}]*toolsRefreshedAt[^}]*\}\s*=\s*useStore\(\)/s);
  });

  it('ToolsPanel.tsx header includes a refresh button', () => {
    const src = readFileSync(join(root, 'src', 'components', 'ToolsPanel.tsx'), 'utf-8');
    expect(src).toContain('tools-panel-refresh');
    expect(src).toMatch(/onClick\s*=\s*\{\s*\(\)\s*=>\s*void\s+onRefresh\(\)\s*\}/);
  });

  it('ToolsPanel.tsx renders last-refresh meta line', () => {
    const src = readFileSync(join(root, 'src', 'components', 'ToolsPanel.tsx'), 'utf-8');
    expect(src).toContain('tools-panel-meta');
    expect(src).toContain('最后刷新');
  });

  it('ToolsPanel.tsx empty state is informative (not "daemon starting…")', () => {
    const src = readFileSync(join(root, 'src', 'components', 'ToolsPanel.tsx'), 'utf-8');
    expect(src).toContain('tools-panel-empty-empty');
    expect(src).toContain('点 ↻ 重试');
    // The legacy-A user-facing string "daemon is starting up…"
    // should NOT appear as a JSX literal — only inside the
    // prior round comment that explains the change. The test
    // strips comments before checking, so a comment-only
    // mention is fine but a leftover literal in the JSX
    // would still fail.
    const stripped = src
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/\/\/.*$/gm, '');
    expect(stripped).not.toMatch(/daemon is starting up/);
  });

  it('ToolsPanel.tsx auto-refreshes on mount when the pool is empty', () => {
    const src = readFileSync(join(root, 'src', 'components', 'ToolsPanel.tsx'), 'utf-8');
    expect(src).toMatch(/tools\.length\s*===\s*0\s*&&\s*toolActions\.length\s*===\s*0/);
    expect(src).toMatch(/void\s+refreshTools\(\)/);
  });

  it('ToolsPanel.css defines a refresh button with hover + spin animation', () => {
    const css = readFileSync(join(root, 'src', 'components', 'ToolsPanel.css'), 'utf-8');
    expect(css).toContain('.tools-panel-refresh');
    expect(css).toContain('.tools-panel-refresh.is-refreshing');
    expect(css).toMatch(/@keyframes\s+tools-panel-spin/);
  });

  it('ToolsPanel.css adds a meta line + empty-state block', () => {
    const css = readFileSync(join(root, 'src', 'components', 'ToolsPanel.css'), 'utf-8');
    expect(css).toContain('.tools-panel-meta');
    expect(css).toContain('.tools-panel-meta-time');
    expect(css).toContain('.tools-panel-empty-empty');
  });
});

describe('对应历史 round: store has refreshTools + toolsRefreshedAt', () => {
  const storeSrc = readFileSync(join(root, 'src', 'store', 'index.ts'), 'utf-8');

  it('declares refreshTools in the AppState interface', () => {
    expect(storeSrc).toMatch(/refreshTools:\s*\(\)\s*=>\s*Promise<void>/);
  });

  it('declares toolsRefreshedAt on the AppState interface', () => {
    expect(storeSrc).toMatch(/toolsRefreshedAt:\s*number/);
  });

  it('initial state seeds toolsRefreshedAt: 0', () => {
    expect(storeSrc).toMatch(/toolsRefreshedAt:\s*0/);
  });

  it('refreshTools action exists in the implementation', () => {
    expect(storeSrc).toMatch(/refreshTools:\s*async\s*\(\)\s*=>\s*\{/);
  });

  it('refreshTools calls both listTools and listToolActions', () => {
    const block = storeSrc.match(/refreshTools:\s*async\s*\(\)\s*=>\s*\{[\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toContain('rpc.listTools()');
    expect(block![0]).toContain('rpc.listToolActions()');
  });

  it('refreshTools preserves prior values on failure (no blanking)', () => {
    const block = storeSrc.match(/refreshTools:\s*async\s*\(\)\s*=>\s*\{[\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    // The implementation must only set() when a non-null
    // response arrives, NOT unconditionally blank the store.
    // The patch object + Object.keys(patch).length > 0 guard
    // is the signal here.
    expect(block![0]).toMatch(/Object\.keys\(patch\)\.length\s*>\s*0/);
  });

  it('refreshTools stamps Date.now() on success', () => {
    const block = storeSrc.match(/refreshTools:\s*async\s*\(\)\s*=>\s*\{[\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toContain('toolsRefreshedAt: Date.now()');
  });

  it('initialize() seeds toolsRefreshedAt from the boot snapshot', () => {
    // The legacy-A initialize() block assigns tools; the
    // prior round line should also seed toolsRefreshedAt so the
    // panel doesn't say "从未拉取" right after launch.
    const initBlock = storeSrc.match(/set\(\{[\s\S]*?tools:\s*tools\.tools\s*\?\?\s*\[\][\s\S]*?\}\);/);
    expect(initBlock).toBeTruthy();
    expect(initBlock![0]).toContain('toolsRefreshedAt: Date.now()');
  });

  it('initialize() schedules a periodic tools refresh', () => {
    expect(storeSrc).toContain('toolsTimer');
    expect(storeSrc).toMatch(/toolsTimer\s*=\s*window\.setInterval/);
    expect(storeSrc).toMatch(/30_000/);
  });
});

describe('对应历史 round: MessageInput Tools popup is refresh-aware', () => {
  const src = readFileSync(join(root, 'src', 'components', 'MessageInput.tsx'), 'utf-8');

  it('MessageInput pulls refreshTools from the store', () => {
    expect(src).toMatch(/refreshTools\s*,/);
  });

  it('MessageInput removed the tools.length === 0 disabled gate', () => {
    // legacy-A, the button was `disabled={tools.length === 0}`,
    // which made the empty case unreachable. The prior round version
    // removed that gate; the test asserts the absence in the
    // JSX (after stripping comments — the explanatory comment
    // that quotes the legacy-A behaviour is fine).
    const stripped = src
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/\/\/.*$/gm, '');
    expect(stripped).not.toMatch(/disabled=\{tools\.length\s*===\s*0\}/);
  });

  it('MessageInput popup has a refresh button + title', () => {
    expect(src).toContain('tools-popup-refresh');
    expect(src).toContain('↻');
  });

  it('MessageInput popup empty state tells the user to click ↻', () => {
    expect(src).toContain('tools-popup-empty');
    expect(src).toContain('点击 ↻ 重试');
  });

  it('MessageInput auto-refreshes when the popup opens with empty data', () => {
    expect(src).toMatch(/showTools\s*&&\s*tools\.length\s*===\s*0/);
    expect(src).toMatch(/void\s+refreshTools\(\)/);
  });
});
