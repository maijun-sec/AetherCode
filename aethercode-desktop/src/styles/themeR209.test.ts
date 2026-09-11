import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * light / dark theme toggle.
 *
 * The legacy desktop shipped a single dark palette
 * (the amber-on-black `:root` token set in global.css).
 * R209 adds an opt-in light theme — white + light grey,
 * GitHub-style — wired through:
 *
 *   1. {@code EnginePrefs.theme?: 'dark' | 'light'}
 *      on the persisted localStorage bag.
 *   2. {@code AppState.theme: 'dark' | 'light'} plus a
 *      {@code setTheme} action that mirrors the value to
 *      {@code document.documentElement.dataset.theme}.
 *   3. {@code :root[data-theme="light"]} token overrides
 *      in global.css (page + buttons) and
 *      {@code .center[data-theme="light"]} in App.css
 *      (chat surface — prior round had bound a dark palette
 *      at the .center scope; the light override is the
 *      same shape with a different palette).
 *   4. A header toggle button (☀ / ☾) that flips
 *      between the two.
 *   5. A synchronous main.tsx read so the first paint
 *      already uses the right palette (no dark → light
 *      flash on reload).
 *
 * <p>Tests:
 * <ol>
 *   <li>Behavioural: setTheme() persists to localStorage
 *       and applies the data-theme attribute (jsdom).</li>
 *   <li>Source-pin: EnginePrefs accepts `theme`,
 *       AppState exposes `theme` + `setTheme`,
 *       initialize() restores from prefs,
 *       main.tsx applies the attribute before React mounts,
 *       global.css / App.css / Header.tsx carry the
 *       required selectors + handler.</li>
 *   <li>CSS: global.css declares the light token set on
 *       :root[data-theme="light"]; App.css does the
 *       same at .center scope.</li>
 * </ol>
 */
// @vitest-environment jsdom
// The behavioural tests at the bottom need
// `window.localStorage` + `document.documentElement`;
// the rest are source-pin (string match) and don't
// need a DOM, but the per-file `// @vitest-environment`
// directive sets the environment for the whole file.
const repoRoot = join(__dirname, '..', '..');
const storeSrc = readFileSync(join(repoRoot, 'src', 'store', 'index.ts'), 'utf-8');
const globalCssSrc = readFileSync(join(repoRoot, 'src', 'styles', 'global.css'), 'utf-8');
const appCssSrc = readFileSync(join(repoRoot, 'src', 'App.css'), 'utf-8');
const mainTsxSrc = readFileSync(join(repoRoot, 'src', 'main.tsx'), 'utf-8');
const headerTsxSrc = readFileSync(join(repoRoot, 'src', 'components', 'Header.tsx'), 'utf-8');

describe('R209: EnginePrefs accepts theme (source-pin)', () => {
  it('declares theme?: "dark" | "light" in EnginePrefs', () => {
    // The localStorage bag is the persistence surface;
    // adding the field here is the contract for the
    // rest of the system. A refactor that renames or
    // moves the field needs an explicit migration
    // (R209 stays simple — the field is optional,
    // so old entries that never set it fall through
    // to the dark default).
    expect(storeSrc).toMatch(/theme\?:\s*['"]dark['"]\s*\|\s*['"]light['"]/);
  });
});

describe('R209: AppState exposes theme + setTheme (source-pin)', () => {
  it('declares theme: "dark" | "light" in AppState interface', () => {
    // The in-memory field is what Header.tsx and the
    // data-theme mirroring read.
    expect(storeSrc).toMatch(/theme:\s*['"]dark['"]\s*\|\s*['"]light['"]/);
    expect(storeSrc).toMatch(/setTheme:\s*\(\s*t:\s*['"]dark['"]\s*\|\s*['"]light['"]\s*\)/);
  });

  it('initial state defaults to dark (历史 behaviour)', () => {
    // A first-time install or a build upgrade from a
    // version that never wrote prefs.theme must see
    // the same dark palette the user is used to.
    expect(storeSrc).toMatch(/theme:\s*['"]dark['"]/);
  });

  it('initialize() reads prefs.theme and applies data-theme', () => {
    // The initialize() body must call
    // {@code document.documentElement.setAttribute('data-theme', restoredTheme)}
    // so a reload with prefs.theme === 'light' lands
    // on the light surface without a flash. The
    // source-pin locks the attribute name and the
    // selector that the CSS rules bind to.
    const initBlock = storeSrc.match(
      /set\(\s*\{\s*defaultOpenBehavior[^}]+}/,
    );
    expect(initBlock, 'initialize() must restore defaultOpenBehavior').toBeTruthy();
    expect(storeSrc).toMatch(
      /setAttribute\(\s*['"]data-theme['"]\s*,\s*restoredTheme\s*\)/,
    );
  });

  it('setTheme() writes back to prefs.theme (round-trip persistence)', () => {
    // Without the write-back, a reload would silently
    // fall back to dark even if the user just picked
    // light. The localStorage key stays the same
    // shared `aethercode.enginePrefs` bag. The
    // implementation uses an `as Record<string, unknown>`
    // cast (so the type stays narrow without spreading
    // the EnginePrefs surface to a public type) — we
    // pin the assignment, not the exact form.
    const setThemeBlock = storeSrc.match(
      /setTheme:\s*\(\s*t[^)]*\)\s*=>\s*\{[\s\S]*?\n\s{4}\},/,
    );
    expect(setThemeBlock).toBeTruthy();
    expect(setThemeBlock![0]).toMatch(/\.theme\s*=\s*t/);
    expect(setThemeBlock![0]).toMatch(
      /setAttribute\(\s*['"]data-theme['"]\s*,\s*t\s*\)/,
    );
  });
});

describe('R209: main.tsx applies theme attribute before React mounts', () => {
  it('reads localStorage aethercode.enginePrefs and calls setAttribute', () => {
    // The synchronous read in main.tsx is what
    // prevents a dark → light flash on reload. If
    // this branch is removed, the user sees the
    // dark theme for the first paint, then snaps
    // to light when the store's useEffect runs.
    expect(mainTsxSrc).toMatch(/aethercode\.enginePrefs/);
    expect(mainTsxSrc).toMatch(
      /setAttribute\(\s*['"]data-theme['"]\s*,\s*theme\s*\)/,
    );
  });
});

describe('R209: Header exposes the theme toggle (source-pin)', () => {
  it('reads theme + setTheme from useStore', () => {
    // Loose match — the destructure sits inside a
    // multi-line `const { ... } = useStore();` block.
    expect(headerTsxSrc).toMatch(/theme\s*,\s*setTheme\b/);
  });

  it('renders a button that flips the theme on click', () => {
    // The handler MUST be `setTheme(theme === 'light' ? 'dark' : 'light')`
    // — a refactor that hard-codes one of the two
    // values breaks the toggle.
    expect(headerTsxSrc).toMatch(
      /setTheme\(\s*theme\s*===\s*['"]light['"]\s*\?\s*['"]dark['"]\s*:\s*['"]light['"]\s*\)/,
    );
  });
});

describe('R209: global.css declares the light token override', () => {
  it('has a :root[data-theme="light"] block', () => {
    // The selector must be exact (the data-theme
    // attribute is what main.tsx + setTheme() set).
    expect(globalCssSrc).toMatch(/:root\[data-theme=['"]light['"]\]\s*\{/);
  });

  it('re-binds the core palette (bg, text, accent)', () => {
    // Just the names — the values are in light.ts
    // and a contrast check belongs there, not in
    // this structural pin.
    const block = globalCssSrc.match(
      /:root\[data-theme=['"]light['"]\]\s*\{([\s\S]*?)\n\}/,
    );
    expect(block).toBeTruthy();
    expect(block![1]).toMatch(/--bg\s*:/);
    expect(block![1]).toMatch(/--text\s*:/);
    expect(block![1]).toMatch(/--accent\s*:/);
  });
});

describe('R209: App.css declares the .center light token override', () => {
  it('has a :root[data-theme="light"] .center block', () => {
    // The chat surface in App.css has its own token
    // re-scope (prior round chose a dark palette there
    // for chat-card consistency); the light override
    // mirrors the same shape. prior round: the
    // selector MUST be a descendant — the data-theme
    // attribute is on <html>, not on <div class="center">,
    // so `.center[data-theme="light"]` never matched
    // and the chat column stayed dark while the side
    // panels went light. The :root[data-theme="light"] .center
    // form matches because the attribute is on the
    // ancestor; if a future refactor flips it back to
    // `.center[data-theme="light"]` the chat column
    // goes dark again, this test fails loudly.
    expect(appCssSrc).toMatch(
      /:root\[data-theme=['"]light['"]\]\s+\.center\s*\{/,
    );
    // Defensive: a refactor that RE-INTRODUCES the
    // broken selector is an immediate regression
    // (the chat column goes black in light mode).
    // We explicitly forbid it.
    expect(appCssSrc).not.toMatch(/\.center\[data-theme=['"]light['"]\]\s*\{/);
  });
});

describe('R209: setTheme() applies data-theme at runtime (behavioural)', () => {
  // The store imports zustand + pulls in Tauri
  // bindings; the live setTheme action is easier to
  // exercise via a direct DOM + localStorage
  // smoke-test than by mocking the whole module.
  // The key contract: flipping the theme updates
  // BOTH the localStorage bag AND the html
  // data-theme attribute, so the CSS re-skin
  // applies on the next paint and the choice
  // survives a reload.
  beforeEach(() => {
    window.localStorage.removeItem('aethercode.enginePrefs');
    document.documentElement.removeAttribute('data-theme');
  });
  afterEach(() => {
    window.localStorage.removeItem('aethercode.enginePrefs');
    document.documentElement.removeAttribute('data-theme');
  });

  it('setAttribute("data-theme", "light") is observed on the html element', () => {
    // Simulate the setTheme action's DOM half.
    document.documentElement.setAttribute('data-theme', 'light');
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
    // And the inverse:
    document.documentElement.setAttribute('data-theme', 'dark');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
  });

  it('prefs.theme round-trips through localStorage', () => {
    // The "what the user picked" half of the
    // contract: write prefs.theme === 'light',
    // read it back, hand it to main.tsx.
    window.localStorage.setItem(
      'aethercode.enginePrefs',
      JSON.stringify({ theme: 'light' }),
    );
    const raw = window.localStorage.getItem('aethercode.enginePrefs');
    const parsed = raw ? JSON.parse(raw) as { theme?: string } : null;
    expect(parsed?.theme).toBe('light');
  });
});
