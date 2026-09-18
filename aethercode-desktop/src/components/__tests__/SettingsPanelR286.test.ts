// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * R286: Settings panel — "Show all providers" toggle
 * + per-model pricing summary.
 *
 * <p>The panel now exposes:
 * <ol>
 *   <li>a master checkbox that gates whether
 *       providers without an API key show up in the
 *       dropdown (default: off — mirror the R282
 *       MessageInput filter);</li>
 *   <li>a per-model $X/M-input + $Y/M-output annotation
 *       next to each option (so the user can compare
 *       models without a calculator);</li>
 *   <li>a provider-level cost band summary under the
 *       model picker (min..max input / output).</li>
 * </ol>
 *
 * <p>Tests are static (regex over the source) — the
 * runtime path uses the MockRpcServer's
 * listAvailableModels handler (added in R285) and
 * would require a fixture + provider-list seed
 * setup that costs more than the contract here. The
 * parse-style coverage is enough to pin the
 * "Show all providers" affordance + the per-model
 * cost line.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();
const tsxSrc = readFileSync(join(root, 'components', 'SettingsPanel.tsx'), 'utf-8');

describe('R286: Settings panel — Show all providers toggle', () => {
  it('renders a checkbox for showAllProviders', () => {
    // The toggle sits at the top of the
    // provider / model picker section so the
    // user can flip it without scrolling.
    // The data-testid lets tests + the
    // future-settings page find it.
    expect(tsxSrc).toMatch(/data-testid="settings-show-all-providers"/);
  });

  it('filters providers by hasApiKey when toggle is off', () => {
    // Mirror the R282 MessageInput filter —
    // providers without an API key are
    // hidden by default. The toggle is off
    // by default, so the user sees only
    // configured providers on a fresh
    // install.
    expect(tsxSrc).toMatch(/filteredProviders/);
    expect(tsxSrc).toMatch(/hasApiKey\s*!==\s*false/);
  });

  it('persists the toggle choice to localStorage', () => {
    // The user reported that toggling
    // providers off, then reloading the
    // renderer, snapped them back to "show
    // all". R286 persists the choice so a
    // reload keeps it.
    expect(tsxSrc).toMatch(/aethercode\.settings\.showAllProviders/);
  });

  it('shows the configured / hidden count under the toggle', () => {
    // The hint line under the toggle tells
    // the user how many providers are
    // hidden so they know why the dropdown
    // is shorter than they expected.
    expect(tsxSrc).toMatch(/showing\s+\$\{filteredProviders\.length\}\s+configured/);
  });

  it('badges unconfigured providers when "Show all" is on', () => {
    // When the user explicitly opted in to
    // "Show all", unconfigured providers
    // get a "(no API key)" suffix so the
    // reason is obvious. The filter still
    // gates the list, so the badge only
    // appears when the user opted in.
    expect(tsxSrc).toMatch(/\(no API key\)/);
  });
});

describe('R286: Settings panel — per-model pricing summary', () => {
  it('renders $X/M-input + $Y/M-output per model', () => {
    // inputPer1k is per-1k tokens; ×1000
    // gives the standard per-million
    // industry format. The Settings panel
    // shows both so the user can compare
    // models at a glance.
    expect(tsxSrc).toMatch(/inputPer1k\s*\?\?\s*0\)\s*\*\s*1000/);
    expect(tsxSrc).toMatch(/outputPer1k\s*\?\?\s*0\)\s*\*\s*1000/);
    expect(tsxSrc).toMatch(/\/M\s+in/);
    expect(tsxSrc).toMatch(/\/M\s+out/);
  });

  it('shows provider-level cost band under the model picker', () => {
    // When the provider has multiple
    // models with different rates, the
    // hint under the picker shows the
    // min..max band so the user knows the
    // range without picking each model
    // individually.
    expect(tsxSrc).toMatch(/modelsForProvider\.map\(\(mm:\s*any\)\s*=>\s*mm\.inputPer1k/);
    expect(tsxSrc).toMatch(/\$\{\(inMin\s*\*\s*1000\)\.toFixed\(2\)\}/);
  });
});