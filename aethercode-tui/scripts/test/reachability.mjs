// R381 — REACHABILITY, measured.
//
// ## Why this file exists
//
// R379 claimed "nine reachable components still hand-write a key hint"
// and listed them. The list was WRONG:
//
//   - `NotificationSettings` is imported by NotificationCenter — but
//     only for its toggle REGISTRY (`WARNING_TOGGLES`). The COMPONENT
//     is never rendered.
//   - `NotificationDetail` is imported by nobody at all.
//
// A component nobody can open cannot mislead anybody. Listing it as a
// "reachable component with a hand-written hint" sent the next round
// after work that no user could ever see.
//
// Lesson 1214 — "reachable" is a MEASUREMENT, not a claim. Prose
// about reachability drifts exactly like a hand-written key table does.
//
// ## What "reachable" means here
//
// Two conditions, and BOTH are needed:
//
//   1. IMPORTED — the file is in the import graph of an entry point
//      (`src/tui.tsx` for Ink, `src/line.ts` for the line renderer).
//   2. RENDERED — a reachable file uses it as a JSX tag, resolved to
//      its SOURCE.
//
// The second condition has a subtlety that a tag-name match gets
// wrong: `Welcome.tsx` renders a `<Pill>` but defines its OWN local
// `Pill`, so `components/Pill.tsx` is dead even though a `<Pill>` tag
// appears in a reachable file. The tag is therefore resolved through
// the importing file's bindings — the same rule the compiler applies.
//
// ## The entry points
//
// `src/tui.tsx` (the Ink app) and `src/line.ts` (the cmd.exe / non-TTY
// line renderer). The line renderer draws framed TEXT, not React
// components, so in practice the reachable React surface is the Ink
// one — but the graph is built from both, so a component that only
// the line renderer imports would still be found.

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { blankNonCode } from "./ts-scan.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));

/** The TUI package root (the directory that holds `src/`). */
export const TUI_ROOT = path.resolve(path.join(HERE, "..", ".."));

/** The files the graph is rooted at. */
export const ENTRY_POINTS = ["tui.tsx", "line.ts"];

function walk(dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (/\.tsx?$/.test(e.name)) out.push(p);
  }
  return out;
}

/**
 * The imports of a file: `{ spec, names }` per import statement.
 *
 * <p>Only the NAMED and DEFAULT bindings are collected. A namespace
 * import (`import * as X`) does not say which component is rendered,
 * and the JSX scan below is what catches those.
 */
function importsOf(src) {
  const out = [];
  const re = /import\s+(?:type\s+)?([^;]*?)\s*from\s*["']([^"']+)["']/g;
  for (const m of src.matchAll(re)) {
    const clause = m[1];
    const spec = m[2];
    const names = [];
    const def = clause.match(/^([A-Za-z_$][\w$]*)\s*(?:,|$)/);
    if (def && !clause.trimStart().startsWith("{") && !clause.trimStart().startsWith("*")) {
      names.push(def[1]);
    }
    const braced = clause.match(/\{([^}]*)\}/);
    if (braced) {
      for (const part of braced[1].split(",")) {
        const t = part.trim();
        if (!t) continue;
        const alias = t.split(/\s+as\s+/);
        names.push((alias[1] ?? alias[0]).trim());
      }
    }
    out.push({ spec, names });
  }
  return out;
}

/** Resolve an import specifier to a file inside `src/`. */
function resolveSpec(fromFile, spec, srcRoot) {
  if (!spec.startsWith(".")) return null;
  const base = path.resolve(path.dirname(fromFile), spec.replace(/\.js$/, ""));
  for (const ext of [".ts", ".tsx"]) {
    if (fs.existsSync(base + ext)) return base + ext;
  }
  if (fs.existsSync(base) && fs.statSync(base).isFile()) return base;
  for (const ext of ["/index.tsx", "/index.ts"]) {
    if (fs.existsSync(base + ext)) return base + ext;
  }
  return null;
}

/** The component names a file renders as JSX tags. */
function renderedTags(src) {
  const code = blankNonCode(src);
  const names = new Set();
  for (const m of code.matchAll(/<([A-Z][\w$]*)\b/g)) names.add(m[1]);
  return names;
}

/** The components a file EXPORTS. */
export function exportedComponents(src) {
  const names = new Set();
  for (const m of src.matchAll(/export\s+(?:const|function|class)\s+([A-Z][\w$]*)/g)) {
    names.add(m[1]);
  }
  for (const m of src.matchAll(/export\s+default\s+([A-Z][\w$]*)/g)) names.add(m[1]);
  return names;
}

/** The names a file defines ITSELF (a local component shadows an import). */
function localDefinitions(src) {
  const names = new Set();
  for (const m of src.matchAll(/(?:^|\n)\s*(?:const|function|class)\s+([A-Z][\w$]*)/g)) {
    names.add(m[1]);
  }
  return names;
}

/**
 * The reachability analysis of the TUI's `src/` tree.
 *
 * @param {string} [root] the TUI package root (defaults to this one)
 */
export function analyzeReachability(root = TUI_ROOT) {
  const srcRoot = path.join(root, "src");
  const files = walk(srcRoot);
  const rel = (f) => path.relative(srcRoot, f).replace(/\\/g, "/");

  const sources = new Map();
  for (const f of files) sources.set(f, fs.readFileSync(f, "utf-8"));

  const imports = new Map();
  for (const f of files) imports.set(f, importsOf(sources.get(f)));

  /** file -> the local file each imported name came from. */
  const bindings = new Map();
  /** file -> the local files it imports at all. */
  const edges = new Map();
  for (const f of files) {
    const map = new Map();
    const set = new Set();
    for (const { spec, names } of imports.get(f)) {
      const to = resolveSpec(f, spec, srcRoot);
      if (!to) continue; // a package import (react, ink, …)
      set.add(to);
      for (const n of names) map.set(n, to);
    }
    bindings.set(f, map);
    edges.set(f, set);
  }

  // --- the import closure of the entry points ---
  const reachable = new Set();
  const queue = ENTRY_POINTS.map((e) => path.join(srcRoot, e)).filter((f) => sources.has(f));
  for (const f of queue) reachable.add(f);
  while (queue.length > 0) {
    const f = queue.shift();
    for (const to of edges.get(f) ?? []) {
      if (!reachable.has(to)) {
        reachable.add(to);
        queue.push(to);
      }
    }
  }

  // --- who renders whom ---
  const renderedBy = new Map();
  for (const f of reachable) {
    const map = bindings.get(f);
    const own = localDefinitions(sources.get(f));
    for (const tag of renderedTags(sources.get(f))) {
      const target = map.get(tag) ?? (own.has(tag) ? f : null);
      if (!target) continue;
      if (!renderedBy.has(target)) renderedBy.set(target, []);
      renderedBy.get(target).push(rel(f));
    }
  }

  const components = [];
  for (const f of files) {
    if (!f.endsWith(".tsx")) continue;
    const exported = [...exportedComponents(sources.get(f))];
    if (exported.length === 0) continue;
    const renderedIn = [...new Set(renderedBy.get(f) ?? [])];
    components.push({
      file: f,
      rel: rel(f),
      exported,
      imported: reachable.has(f),
      rendered: renderedIn.length > 0,
      renderedIn,
    });
  }

  return {
    root,
    srcRoot,
    sources,
    reachable,
    components,
    /** The components a user can actually open. */
    reachableComponents: components.filter((c) => c.rendered),
    /** Imported, but never rendered — dead UI. */
    deadComponents: components.filter((c) => c.imported && !c.rendered),
    /** Never imported at all. */
    orphanedComponents: components.filter((c) => !c.imported),
  };
}
