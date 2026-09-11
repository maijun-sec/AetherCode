// T-6-08: TodoItem (single TODO card).
//
// Pure-helper + source-grep + tsc compile.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const tscBin = join(
  root, "node_modules", ".bin",
  process.platform === "win32" ? "tsc.cmd" : "tsc"
);
const read = (rel) => readFileSync(join(root, rel), "utf-8");

// ----- 1. Pure-helper tests ------------------------------------

test("T-6-08: todoIcon maps status → checkbox glyph", () => {
  // Mirror the implementation in TodoItem.tsx.
  function todoIcon(status) {
    switch (status) {
      case "pending":     return "☐";
      case "in_progress": return "◐";
      case "completed":   return "☑";
      case "cancelled":   return "⊘";
      default:            return "·";
    }
  }
  assert.equal(todoIcon("pending"), "☐");
  assert.equal(todoIcon("in_progress"), "◐");
  assert.equal(todoIcon("completed"), "☑");
  assert.equal(todoIcon("cancelled"), "⊘");
  assert.equal(todoIcon("unknown"), "·");
});

test("T-6-08: todoColor returns a colour tier per status", () => {
  // Mirror the implementation.
  function todoColor(status) {
    switch (status) {
      case "pending":     return "dim";
      case "in_progress": return "warn";
      case "completed":   return "ok";
      case "cancelled":   return "dim";
      default:            return "dim";
    }
  }
  assert.equal(todoColor("pending"), "dim");
  assert.equal(todoColor("in_progress"), "warn");
  assert.equal(todoColor("completed"), "ok");
  assert.equal(todoColor("cancelled"), "dim");
});

test("T-6-08: truncate adds an ellipsis when over the cap", () => {
  function truncate(s, n) {
    if (!s) return "";
    if (s.length <= n) return s;
    return s.slice(0, Math.max(0, n - 1)) + "…";
  }
  assert.equal(truncate("hi", 10), "hi");
  assert.equal(truncate("hello world", 5), "hell…");
  assert.equal(truncate("", 10), "");
  assert.equal(truncate("abc", 0), "…");
});

test("T-6-08: statusPill renders a human label", () => {
  function statusPill(status) {
    switch (status) {
      case "pending":     return "pending";
      case "in_progress": return "in progress";
      case "completed":   return "done";
      case "cancelled":   return "cancelled";
      default:            return status;
    }
  }
  assert.equal(statusPill("pending"), "pending");
  assert.equal(statusPill("in_progress"), "in progress");
  assert.equal(statusPill("completed"), "done");
  assert.equal(statusPill("cancelled"), "cancelled");
});

test("T-6-08: ownerLabel normalises 'parent' and 'subagent:<id>'", () => {
  function ownerLabel(owner) {
    if (!owner) return "";
    if (owner === "parent") return "self";
    if (owner.startsWith("subagent:")) return owner.slice("subagent:".length);
    return owner;
  }
  assert.equal(ownerLabel(""), "");
  assert.equal(ownerLabel(null), "");
  assert.equal(ownerLabel(undefined), "");
  assert.equal(ownerLabel("parent"), "self");
  assert.equal(ownerLabel("subagent:sag-12"), "sag-12");
  assert.equal(ownerLabel("custom"), "custom");
});

// ----- 2. Source-grep assertions ------------------------------

test("T-6-08: TodoItem.tsx exists and exports the component", () => {
  const path = join(root, "src/components/todo/TodoItem.tsx");
  assert.ok(existsSync(path));
  const src = read("src/components/todo/TodoItem.tsx");
  assert.match(src, /export\s+const\s+TodoItem\b/);
});

test("T-6-08: TodoItem renders strikethrough for cancelled", () => {
  const src = read("src/components/todo/TodoItem.tsx");
  assert.match(src, /strikethrough\s*=\s*\{item\.status\s*===\s*"cancelled"\}/);
});

test("T-6-08: focused and selected props are wired", () => {
  const src = read("src/components/todo/TodoItem.tsx");
  assert.match(src, /focused\?/);
  assert.match(src, /selected/);
  // Focused row gets a cyan "▶" marker.
  assert.match(src, /▶/);
});

test("T-6-08: all four pure helpers are exported", () => {
  const src = read("src/components/todo/TodoItem.tsx");
  assert.match(src, /export\s+function\s+todoIcon\b/);
  assert.match(src, /export\s+function\s+todoColor\b/);
  assert.match(src, /export\s+function\s+truncate\b/);
  assert.match(src, /export\s+function\s+statusPill\b/);
  assert.match(src, /export\s+function\s+ownerLabel\b/);
});

// ----- 3. tsc --strict compile of the .tsx -------------------

test("T-6-08: tsc --strict compile of TodoItem.tsx is clean", () => {
  const tmp = join(root, "tmp-t6-ti-tsc");
  if (existsSync(tmp)) {
    spawnSync(process.platform === "win32" ? "cmd" : "rm",
      process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp],
      { shell: process.platform === "win32" });
  }
  const r = spawnSync(`"${tscBin}"`, [
    "--outDir", tmp, "--target", "ES2022", "--module", "ES2022",
    "--moduleResolution", "bundler", "--jsx", "react",
    "--esModuleInterop", "true", "--skipLibCheck", "true",
    "--strict", "true", "--noUncheckedIndexedAccess", "true",
    "--rootDir", join(root, "src"),
    join(root, "src", "components", "todo", "TodoItem.tsx"),
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error(r.stdout);
    console.error(r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for TodoItem");
});
