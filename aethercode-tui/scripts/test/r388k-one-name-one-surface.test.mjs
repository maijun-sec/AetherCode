// Copyright (c) 2026 maijun
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

// R388k — ONE name, ONE surface.
//
// ## The bug this gate exists to hold
//
// The command catalog had a genuine duplicate: bare `/theme` opens the
// theme picker, and `/theme-pick` opens THE SAME picker — two names for
// one surface, and NEITHER was declared as an alias.
//
// The audit that found it did NOT read the names. It EXECUTED every
// command through the real `routeSlash` and grouped by the sentinel /
// RPC actually emitted. Reading the names would have said "theme" and
// "theme-pick" are different things, because they LOOK different.
//
// ## ★ Why "read the names" is not enough ★
//
// The catalogue also contains pairs that LOOK like duplicates and are
// NOT:
//   * `/continue` and `/pause` — same RPC (`setContinuationStopped`),
//     OPPOSITE payloads (`stopped: false` / `stopped: true`). They are
//     two sides of one toggle, and the benchmark set keeps them apart
//     for exactly that reason.
//   * `/sdd-approve` and `/sdd-skip` — same sentinel
//     (`__SDD_ADVANCE__`), different `action` payloads.
//
// So the rule cannot be "same RPC ⇒ duplicate" either. It is:
// **two commands that produce the SAME EFFECT are duplicates unless one
// is declared as an alias.** Same effect = same method AND same params.
//
// ## The benchmark
//
//   * Cursor — "Slash commands carry explicit aliases rather than
//     duplicate entries": `/run-everything` ↔ `/auto-run`,
//     `/clear` ↔ `/new`/`/new-chat`/`/newchat`.
//   * OpenCode — one registry; "Later sources replace earlier commands
//     with the same name."
//   * Aider — aliases are LABELLED as aliases ("`/edit` is documented as
//     'Alias for /editor'"). And Aider's own docs STILL carry the bug
//     (both `/edit` and `/editor` listed with different descriptions),
//     which is why this has to be a gate and not a convention.
//
// → **Lesson 1226 — a gate that pins two copies to EACH OTHER is not a
// gate; ask how MANY there are, not whether they agree.**

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { codeOf } from "./strip-comments.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

const read = (rel) => readFileSync(join(root, rel), "utf8");
const readCode = (rel) => codeOf(read(rel));

/* ------------------------------------------------------------------ *
 * Load the REAL router and the REAL catalog.
 *
 * The gate must not re-implement the routing — a second router is a
 * second truth, and it is exactly what drifts (R380's whole subject).
 * So `commands.ts` is compiled and imported, and the real `routeSlash`
 * is called.
 * ------------------------------------------------------------------ */
const loadCommands = async () => {
  const { build } = await import("esbuild");
  const { mkdtempSync, rmSync } = await import("node:fs");
  const { tmpdir } = await import("node:os");
  const dir = mkdtempSync(join(tmpdir(), "r388k-cmd-"));
  const outfile = join(dir, "commands.mjs");
  try {
    await build({
      entryPoints: [join(root, "src", "commands.ts")],
      bundle: true,
      outfile,
      platform: "node",
      format: "esm",
      loader: { ".ts": "ts" },
    });
    return await import(pathToFileURL(outfile).href);
  } finally {
    try {
      rmSync(outfile, { force: true });
    } catch {
      /* best effort */
    }
  }
};

/**
 * The EFFECT of a command: what it actually does.
 *
 * Two commands are duplicates when they produce the same effect — the
 * same method with the same params, or the same local sentinel with
 * the same params. A sentinel with DIFFERENT params (`__SDD_ADVANCE__`
 * with `action: approve` vs `action: skip`) is NOT a duplicate.
 */
function effectOf(route) {
  if (!route) return null;
  if (route.rpcMethod) {
    return { kind: "rpc", method: route.rpcMethod, params: JSON.stringify(route.rpcParams ?? {}) };
  }
  if (route.local) {
    return { kind: "local", method: route.local, params: JSON.stringify(route.rpcParams ?? {}) };
  }
  return null;
}

test("R388k: no two non-alias commands produce the same effect", async () => {
  const cmd = await loadCommands();
  const catalog = cmd.SLASH_COMMANDS_DETAILED;
  assert.ok(Array.isArray(catalog) && catalog.length > 0, "the catalog did not load (R388k)");

  // The alias map: name → the command it stands for.
  const aliasOf = new Map();
  for (const c of catalog) {
    if (c.aliasOf) aliasOf.set(c.name, c.aliasOf);
  }

  // Group the PRIMARY commands by the effect they produce.
  const byEffect = new Map();
  for (const c of catalog) {
    // An alias is a deliberate second name for a surface that already
    // has one — that is the whole point of an alias, and it is
    // EXCLUDED from this check by definition.
    if (c.aliasOf) continue;
    const route = cmd.routeSlash(`/${c.name}`);
    const eff = effectOf(route);
    if (!eff) continue;
    // A usage/error string is not an effect.
    if (eff.kind === "local" && eff.method.startsWith("usage:")) continue;
    if (eff.kind === "local" && eff.method.startsWith("unknown slash command")) continue;
    const key = `${eff.kind}|${eff.method}|${eff.params}`;
    const list = byEffect.get(key) ?? [];
    list.push(c.name);
    byEffect.set(key, list);
  }

  const collisions = [];
  for (const [key, names] of byEffect) {
    if (names.length > 1) collisions.push({ key, names });
  }

  assert.deepEqual(
    collisions,
    [],
    "two commands produce the same effect and neither is an alias — " +
      collisions.map((c) => `${c.names.join(" + ")} → ${c.key}`).join("; ") +
      " (R388k)",
  );
});

test("R388k: every declared alias points at a command that exists", async () => {
  const cmd = await loadCommands();
  const catalog = cmd.SLASH_COMMANDS_DETAILED;
  const names = new Set(catalog.map((c) => c.name));
  for (const c of catalog) {
    if (!c.aliasOf) continue;
    assert.ok(
      names.has(c.aliasOf),
      `/${c.name} is declared an alias of /${c.aliasOf}, which is not in the catalog (R388k)`,
    );
    assert.notEqual(c.name, c.aliasOf, `/${c.name} is declared an alias of itself (R388k)`);
  }
});

test("R388k: the duplicate the audit found is declared as an alias", () => {
  const code = readCode("src/commands.ts");
  // Bare `/theme` opens the picker and `/theme-pick` opens the SAME
  // picker. One of them has to be the alias.
  const entry = /name:\s*"theme-pick"[\s\S]{0,240}?aliasOf:\s*"theme"/.test(code);
  assert.ok(
    entry,
    "/theme-pick is not declared an alias of /theme — two names for one surface (R388k)",
  );
});

test("R388k: the router still reaches every alias", () => {
  const code = readCode("src/commands.ts");
  // An alias that has no route is a dead name: the user types it and
  // gets "unknown slash command".
  for (const alias of [
    "theme-pick",
    "quit",
    "q",
    "todo",
    "ctx",
    "token",
    "grants",
    "resume",
    "kill",
  ]) {
    assert.match(
      code,
      new RegExp(`case\\s+"${alias.replace(/[?]/g, "\\?")}"`),
      `/${alias} is an alias but the router has no case for it — the name is dead (R388k)`,
    );
  }
});

test("R388k: /continue and /pause stay distinct (same RPC, opposite effect)", async () => {
  const cmd = await loadCommands();
  const cont = cmd.routeSlash("/continue");
  const pause = cmd.routeSlash("/pause");
  assert.equal(cont.rpcMethod, "setContinuationStopped");
  assert.equal(pause.rpcMethod, "setContinuationStopped");
  // Same method, OPPOSITE params — two sides of one toggle, not a
  // duplicate. If these ever converge, the user loses a way to say
  // "keep going".
  assert.notEqual(
    JSON.stringify(cont.rpcParams),
    JSON.stringify(pause.rpcParams),
    "/continue and /pause now do the same thing — one of them is redundant (R388k)",
  );
});
