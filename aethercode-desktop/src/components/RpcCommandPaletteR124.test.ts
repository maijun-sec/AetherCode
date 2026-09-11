import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * tag-based filtering in the prior round
 * RpcCommandPalette. The daemon's
 * {@code /api/methods} endpoint returns
 * {@code { methods: [{name, tags[]}], tags: [...] }}
 * instead of the legacy flat-name list. The
 * palette renders a chip bar (one chip per
 * tag the daemon knows about) and uses an
 * AND-semantics filter — clicking "engine"
 * and "write" narrows the list to engine-write
 * RPCs only.
 *
 * <p>Tests pin: the TS-side type, the store's
 * parse (handle both new tagged shape AND the
 * legacy flat-name shape for back-compat),
 * the palette's tag chip bar wiring, the
 * AND-semantics filter, and the chip-clear
 * shortcut.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

describe('R124: RpcMethodInfo type + tag list', () => {
  const methodsSrc = readFileSync(join(root, 'src', 'lib', 'methods.ts'), 'utf-8');

  it('exports the RpcMethodInfo interface', () => {
    expect(methodsSrc).toMatch(/export interface RpcMethodInfo\s*\{/);
    expect(methodsSrc).toMatch(/name:\s*string/);
    expect(methodsSrc).toMatch(/tags:\s*string\[\]/);
  });

  it('declares the 14-tag RPC_TAGS constant in sync with Java METHOD_TAGS', () => {
    // The Java side emits a fixed set of 14
    // tag strings (R124 design). The TS
    // constant mirrors them so a future
    // refactor that adds a tag on one side
    // without the other gets caught at the
    // next tag-counting render.
    expect(methodsSrc).toContain("export const RPC_TAGS");
    for (const tag of [
      'read', 'write', 'engine', 'session',
      'permission', 'loop', 'tools', 'workflow',
      'memory', 'task', 'skill', 'agent', 'project',
      'diagnostic',
    ]) {
      expect(methodsSrc).toContain(`'${tag}'`);
    }
  });
});

describe('R124: store parses the tagged /api/methods shape', () => {
  const storeSrc = readFileSync(join(root, 'src', 'store', 'index.ts'), 'utf-8');

  it('declares rpcMethodInfos on AppState', () => {
    expect(storeSrc).toMatch(/rpcMethodInfos:\s*RpcMethodInfo\[\]/);
  });

  it('seeds rpcMethodInfos as an empty array in the initial state', () => {
    // The list is lazy-loaded by
    // loadRpcMethods; the first open of
    // the palette triggers the fetch.
    expect(storeSrc).toMatch(/rpcMethodInfos:\s*\[\]/);
  });

  it('imports RpcMethodInfo alongside RpcEvent', () => {
    // One import line covers both — the
    // R124 type joins the existing R116
    // RpcEvent import.
    expect(storeSrc).toMatch(
      /import\s+type\s*\{\s*RpcEvent,\s*RpcMethodInfo\s*\}\s+from\s*['"]\.\.\/lib\/methods['"]/,
    );
  });

  it('loadRpcMethods accepts the new tagged shape', () => {
    // The action iterates body.methods and
    // recognises both `{name, tags}` objects
    // (new shape) and bare strings (legacy
    // flat-name shape). The dual shape
    // acceptance is what makes a 历史
    // daemon still work — the renderer gets
    // the names, the tag bar stays empty.
    const block = storeSrc.match(/loadRpcMethods:\s*async\s*\(\)\s*=>\s*\{[\s\S]*?^\s{6}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toContain("typeof m === 'string'");
    expect(block![0]).toContain("(m as { name?: unknown }).name === 'string'");
    expect(block![0]).toContain("set({ rpcMethods: uniqueNames, rpcMethodInfos: uniqueTagged })");
  });

  it('loadRpcMethods dedupes + sorts both lists', () => {
    // A future R-N might add the same RPC
    // twice; the action must not duplicate
    // the entry. Sorting keeps the palette's
    // list stable across daemon restarts.
    const block = storeSrc.match(/loadRpcMethods:\s*async\s*\(\)\s*=>\s*\{[\s\S]*?^\s{6}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toContain('[...new Set(names)].sort()');
    expect(block![0]).toMatch(/localeCompare/);
  });
});

describe('R124: RpcCommandPalette tag chip bar', () => {
  const tsxSrc = readFileSync(join(root, 'src', 'components', 'RpcCommandPalette.tsx'), 'utf-8');

  it('reads rpcMethodInfos from the store (for the chip bar)', () => {
    // The R121 baseline pulled only
    // rpcMethods; R124 adds the tagged
    // counterpart. Without this destructure
    // the chip bar would never render.
    const region = tsxSrc.match(/const\s*\{[^}]*\}\s*=\s*useStore\(\);/);
    expect(region).toBeTruthy();
    expect(region![0]).toContain('rpcMethodInfos');
  });

  it('declares an activeTags state (Set of strings)', () => {
    // The AND-semantics filter is backed by
    // a Set so the render path is O(1) per
    // method (a method matches when every
    // active tag is in its tags array).
    expect(tsxSrc).toMatch(/activeTags[\s\S]*?useState<Set<string>>\(new Set\(\)\)/);
  });

  it('derives tagCounts from rpcMethodInfos (one entry per unique tag)', () => {
    // The chip label shows
    // "<tag> <count>", so the user knows
    // how many methods the filter would
    // reveal. The map is built from the
    // RpcMethodInfo entries — not from a
    // static list — so a daemon that adds
    // a new tag appears in the bar without
    // a renderer change.
    expect(tsxSrc).toContain('const tagCounts = useMemo');
    expect(tsxSrc).toMatch(/for \(const info of rpcMethodInfos\)/);
    expect(tsxSrc).toContain('m.set(tag, (m.get(tag) ?? 0) + 1)');
  });

  it('renders the tag chip bar when tagCounts is non-empty', () => {
    // The bar is hidden for 历史
    // daemons (the set is empty). The
    // condition is `tagCounts.size > 0`,
    // not `rpcMethodInfos.length > 0`,
    // so an empty-tags RpcMethodInfo
    // entry (defensive) also hides the
    // bar rather than rendering a useless
    // "0" chip.
    expect(tsxSrc).toMatch(/\{tagCounts\.size > 0 && \(/);
  });

  it('renders one chip per tag with its count', () => {
    expect(tsxSrc).toContain('Array.from(tagCounts.entries()).map');
    expect(tsxSrc).toContain('rpc-palette-tag-chip');
    expect(tsxSrc).toContain('rpc-palette-tag-count');
  });

  it('clicking a chip toggles it in the activeTags set', () => {
    // The toggle adds the tag if absent,
    // removes it if present. The clone-
    // mutate-return dance is the React
    // pattern for Set state — directly
    // mutating the existing Set would not
    // trigger a re-render.
    const chip = tsxSrc.match(/className=\{`rpc-palette-tag-chip\${\s*active\s*\?\s*' is-active'\s*:\s*''}`\}[\s\S]*?\}/);
    expect(chip).toBeTruthy();
    expect(chip![0]).toContain('setActiveTags((cur) => {');
    expect(chip![0]).toContain('if (next.has(tag)) next.delete(tag);');
    expect(chip![0]).toContain('else next.add(tag);');
  });

  it('shows a clear button only when at least one chip is active', () => {
    // A "clear" pill that never has
    // anything to clear is dead weight.
    // The conditional render keeps the
    // bar tidy.
    expect(tsxSrc).toContain('activeTags.size > 0 && (');
    expect(tsxSrc).toContain('className="rpc-palette-tag-clear"');
  });

  it('clear button resets the activeTags set', () => {
    expect(tsxSrc).toMatch(/onClick=\{\(\)\s*=>\s*setActiveTags\(new Set\(\)\)\}/);
  });
});

describe('R124: filter combines substring + tag (AND semantics)', () => {
  const tsxSrc = readFileSync(join(root, 'src', 'components', 'RpcCommandPalette.tsx'), 'utf-8');

  it('filtered memo returns rpcMethods unchanged when both filters are empty', () => {
    // Empty query + empty activeTags is the
    // "show all" path. The literal shape
    // pins that the renderer's default
    // state isn't accidentally narrowing.
    const block = tsxSrc.match(/const filtered = useMemo\([\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toMatch(/if \(q && !name\.toLowerCase\(\)\.includes\(q\)\)/);
  });

  it('active-tag filter requires ALL active tags to be present (AND)', () => {
    // A method with tags ["engine", "write"]
    // passes a filter of {engine}, a filter
    // of {write}, and a filter of {engine,
    // write}. It does NOT pass a filter of
    // {engine, permission} (missing
    // permission). The .find + .includes
    // shape is the AND check.
    const block = tsxSrc.match(/const filtered = useMemo\([\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toContain("if (activeTags.size > 0)");
    expect(block![0]).toContain("const info = rpcMethodInfos.find((i) => i.name === name)");
    expect(block![0]).toMatch(/for \(const t of activeTags\)[\s\S]*?if \(!info\.tags\.includes\(t\)\)/);
  });

  it('methods without an RpcMethodInfo entry are excluded when any tag is active', () => {
    // A 历史 daemon (or a future RPC the
    // daemon added but the renderer
    // hasn't tagged yet) would otherwise
    // leak through. The .find() returns
    // undefined and the method is dropped.
    const block = tsxSrc.match(/const filtered = useMemo\([\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toMatch(/if \(!info\) return false/);
  });

  it('active row resets when activeTags changes (not just query)', () => {
    // Without this, clicking a chip while
    // the active row is at idx 4 (e.g. a
    // session write RPC) would leave the
    // selection pointing at a now-filtered-
    // out method. Resetting to 0 keeps the
    // highlight on the first match.
    expect(tsxSrc).toMatch(/useEffect\(\(\)\s*=>\s*\{\s*setActiveIdx\(0\);\s*\},\s*\[query, activeTags\]\)/);
  });
});

describe('R124: CSS for the tag chip bar', () => {
  const cssSrc = readFileSync(join(root, 'src', 'components', 'RpcCommandPalette.css'), 'utf-8');

  it('defines a .rpc-palette-tags container (flex-wrap row)', () => {
    expect(cssSrc).toContain('.rpc-palette-tags');
    expect(cssSrc).toMatch(/\.rpc-palette-tags\s*\{[\s\S]*?display:\s*flex/);
    expect(cssSrc).toMatch(/\.rpc-palette-tags\s*\{[\s\S]*?flex-wrap:\s*wrap/);
  });

  it('defines a chip base style with rounded corners (pill)', () => {
    expect(cssSrc).toContain('.rpc-palette-tag-chip');
    expect(cssSrc).toMatch(/\.rpc-palette-tag-chip\s*\{[\s\S]*?border-radius:\s*12px/);
  });

  it('defines the .is-active state (accent-coloured background)', () => {
    // Active chips use the accent colour
    // so the user can see at a glance
    // which filters are on. The colour
    // is the same as the Execute button
    // — both are "primary" affordances.
    expect(cssSrc).toContain('.rpc-palette-tag-chip.is-active');
    expect(cssSrc).toMatch(/\.rpc-palette-tag-chip\.is-active\s*\{[\s\S]*?background:\s*var\(--accent/);
  });

  it('defines a count badge style inside the chip', () => {
    expect(cssSrc).toContain('.rpc-palette-tag-count');
  });

  it('defines a clear button (dashed border)', () => {
    // The clear button uses a dashed
    // border to differentiate it from
    // the active-chip solid border.
    // Visually: a "soft" affordance.
    expect(cssSrc).toContain('.rpc-palette-tag-clear');
    expect(cssSrc).toMatch(/\.rpc-palette-tag-clear\s*\{[\s\S]*?border-style:\s*solid/);
  });
});
