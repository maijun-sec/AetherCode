// tests for the favourite + recently-used
// feature in RpcCommandPalette.
//
// Pure-render test pattern (prior round
// precedent): the test suite pins the
// implementation in the source file via
// readFileSync + toContain. This is the
// fastest path and the @testing-library/react
// dependency is intentionally absent from
// the project — rendering the React tree
// for these assertions would be slower and
// more brittle than the regex pin.
//
// What's covered:
//  - localStorage key names + cap
//  - silent quota error handling
//  - state shape (favs / recents arrays)
//  - toggleFav / bumpRecent helpers
//  - three-section layout (pinned / recent / all)
//  - star button: ★ pinned vs ☆ unpinned
//  - row variants: fav / recent accent borders
//  - execute() success → bump recents
//  - execute() failure → don't bump recents
//  - pinned wins over recent (no double-listing)

import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';

const SRC = 'D:/work/workspace/idea/engine/AetherCode/aethercode-desktop/src/components/RpcCommandPalette.tsx';
const CSS = 'D:/work/workspace/idea/engine/AetherCode/aethercode-desktop/src/components/RpcCommandPalette.css';

const tsx = readFileSync(SRC, 'utf8');
const css = readFileSync(CSS, 'utf8');

describe('R127 localStorage helpers', () => {
  it('declares FAV_KEY + RECENT_KEY + RECENT_MAX', () => {
    expect(tsx).toContain("const FAV_KEY = 'aethercode.rpcFavorites'");
    expect(tsx).toContain("const RECENT_KEY = 'aethercode.rpcRecent'");
    expect(tsx).toContain('const RECENT_MAX = 12');
  });
  it('readFavs filters non-strings + swallows JSON errors', () => {
    expect(tsx).toContain('function readFavs(): string[]');
    // The "filter" line is the type guard.
    expect(tsx).toMatch(/typeof x === 'string'/);
    // The catch block returns [] on JSON.parse error.
    expect(tsx).toMatch(/catch \{ return \[\]; \}/);
  });
  it('writeFavs / writeRecents silently swallow quota errors', () => {
    expect(tsx).toContain('catch { /* localStorage quota / disabled — silent */ }');
  });
  it('writeRecents slices to RECENT_MAX', () => {
    expect(tsx).toContain('arr.slice(0, RECENT_MAX)');
  });
});

describe('R127 state shape', () => {
  it('declares favs + recents state', () => {
    expect(tsx).toContain('const [favs, setFavs] = useState<string[]>(() => readFavs())');
    expect(tsx).toContain('const [recents, setRecents] = useState<string[]>(() => readRecents())');
  });
  it('toggleFav helper adds / removes from list', () => {
    expect(tsx).toContain('const toggleFav = (name: string) => {');
    expect(tsx).toContain("cur.includes(name)");
    expect(tsx).toContain('cur.filter((n) => n !== name)');
    expect(tsx).toContain('[...cur, name]');
  });
});

describe('R127 execute() bumps recents on success only', () => {
  it('success path writes to RECENT_KEY', () => {
    // The setResult({ ok: true, body, atMs }) line is
    // immediately followed by the recent-bump
    // block.
    const okBlock = tsx.match(
      /setResult\(\{ ok: true, body, atMs \}\);[\s\S]{0,800}?writeRecents\(next\)/
    );
    expect(okBlock).toBeTruthy();
  });
  it('failure path does NOT touch recents', () => {
    // The catch block is for errors.
    // It must NOT include writeRecents.
    const catchBlock = tsx.match(
      /\} catch \(e\) \{[\s\S]{0,400}?\} finally/
    );
    expect(catchBlock).toBeTruthy();
    expect(catchBlock![0]).not.toContain('writeRecents');
  });
  it('dedupes the bumped name (filter removes existing entry)', () => {
    expect(tsx).toContain('cur.filter((n) => n !== selected)');
  });
});

describe('R127 three-section layout', () => {
  it('renders a "★ pinned" section header', () => {
    expect(tsx).toContain('★ pinned');
  });
  it('renders a "🕒 recent" section header', () => {
    expect(tsx).toContain('🕒 recent');
  });
  it('renders an "all" section header for the rest', () => {
    // The "all" label is a JSX text child,
    // not a quoted string. The relevant
    // line is `> all <span ...>`. We
    // match the bare `all` text inside
    // the section header instead of a
    // string literal.
    expect(tsx).toMatch(/>\s*all\s*<span/);
  });
  it('filters pinned out of the "all" section', () => {
    expect(tsx).toContain('const special = new Set([...favs, ...recents])');
    expect(tsx).toContain('filtered.filter((m) => !special.has(m))');
  });
  it('pinned wins over recent (no double-listing)', () => {
    expect(tsx).toContain('if (!recents.includes(m) || favs.includes(m)) return null;');
  });
});

describe('R127 star button', () => {
  it('uses ★ for pinned + ☆ for unpinned', () => {
    expect(tsx).toContain('className="rpc-palette-fav-btn is-pinned"');
    expect(tsx).toContain('className="rpc-palette-fav-btn is-unpinned"');
    // The star glyphs are JSX text
    // children (the `> ★ <` is the
    // button's content). The regex is
    // intentionally lenient about
    // whitespace so it matches the
    // indented JSX layout.
    expect(tsx).toMatch(/>\s*★\s*</);
    expect(tsx).toMatch(/>\s*☆\s*</);
  });
  it('stopPropagation so click does not also select the row', () => {
    // Both star-button onClick handlers must
    // call e.stopPropagation().
    const starClicks = tsx.match(
      /onClick=\{\(e\) => \{ e\.stopPropagation\(\); toggleFav\(m\); \}\}/g
    );
    expect(starClicks).toBeTruthy();
    expect(starClicks!.length).toBeGreaterThanOrEqual(2);
  });
});

describe('R127 CSS', () => {
  it('section header style', () => {
    expect(css).toContain('.rpc-palette-section-header');
    expect(css).toContain('.rpc-palette-section-count');
  });
  it('fav button styles (pinned + unpinned)', () => {
    expect(css).toContain('.rpc-palette-fav-btn');
    expect(css).toContain('.rpc-palette-fav-btn.is-pinned');
    expect(css).toContain('.rpc-palette-fav-btn.is-unpinned');
  });
  it('row variants with left-border accent', () => {
    expect(css).toContain('.rpc-palette-item-fav');
    expect(css).toContain('.rpc-palette-item-recent');
  });
});
