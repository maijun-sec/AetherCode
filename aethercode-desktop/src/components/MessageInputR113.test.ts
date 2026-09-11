import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * tests for the model dropdown fix.
 *
 * legacy the dropdown hard-coded 3 claude models, hiding
 * every other provider's models (minmax, glm, qwen,
 * deepseek, gemini). The user reported "no minmax" in the
 * dropdown despite the daemon's bundled default being
 * minmax. R113 wires the dropdown to availableProviders
 * (populated by the daemon's listProviders RPC).
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

function read(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('R113: Model dropdown from listProviders', () => {
  it('MessageInput.tsx no longer hardcodes 3 claude models', () => {
    const src = read('src/components/MessageInput.tsx');
    expect(src).not.toMatch(/const\s+MODELS\s*=\s*\[/);
  });

  it('MessageInput.tsx wires availableProviders + refreshProviders from the store', () => {
    const src = read('src/components/MessageInput.tsx');
    expect(src).toMatch(/availableProviders/);
    expect(src).toMatch(/refreshProviders/);
  });

  it('MessageInput.tsx calls refreshProviders on connect', () => {
    const src = read('src/components/MessageInput.tsx');
    expect(src).toMatch(/useEffect/);
    expect(src).toMatch(/refreshProviders/);
  });

  it('MessageInput.tsx renders the dropdown from modelEntries (flattened providers)', () => {
    const src = read('src/components/MessageInput.tsx');
    expect(src).toMatch(/modelEntries/);
    expect(src).toMatch(/modelEntries\.map/);
  });

  it('MessageInput.tsx splits the value on slash and calls switchProvider when provider changes', () => {
    const src = read('src/components/MessageInput.tsx');
    expect(src).toMatch(/switchProvider/);
    expect(src).toMatch(/newProvider !== currentProvider/);
  });

  it('MessageInput.tsx imports ProviderInfo type', () => {
    const src = read('src/components/MessageInput.tsx');
    expect(src).toMatch(/import\s+type\s+\{\s*ProviderInfo\s*\}\s+from\s+['"]\.\.\/lib\/methods['"]/);
  });

  it('store types availableProviders as ProviderInfo[] (not any[])', () => {
    const src = read('src/store/index.ts');
    expect(src).toMatch(/availableProviders:\s*import\(['"]\.\.\/lib\/methods['"]\)\.ProviderInfo\[\]/);
  });

  it('store no longer hardcodes claude-sonnet-4-5 as default model', () => {
    const src = read('src/store/index.ts');
    // Three hardcoded places were fixed in R113.
    const matches = src.match(/['"]claude-sonnet-4-5['"]/g) ?? [];
    expect(matches.length).toBe(0);
  });

  it('store reads model from getState (no hardcoded fallback)', () => {
    const src = read('src/store/index.ts');
    expect(src).toMatch(/state\?\.model\s*\?\?\s*['"]['"]/);
  });
});
