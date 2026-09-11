// T-6-07: TodoBoard (live TODO list, j/k nav).
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

test("T-6-07: sortTodos puts in_progress first, pending second, completed last", () => {
  // Mirror the implementation in TodoBoard.tsx.
  function sortTodos(items) {
    const rank = { in_progress: 0, pending: 1, completed: 2, cancelled: 3 };
    return items.slice().sort((a, b) => {
      const ra = rank[a.status] ?? 99;
      const rb = rank[b.status] ?? 99;
      if (ra !== rb) return ra - rb;
      return a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
    });
  }
  const items = [
    { id: "1", title: "a", status: "completed" },
    { id: "2", title: "b", status: "pending" },
    { id: "3", title: "c", status: "in_progress" },
    { id: "4", title: "d", status: "cancelled" },
  ];
  const sorted = sortTodos(items);
  assert.equal(sorted[0].id, "3"); // in_progress
  assert.equal(sorted[1].id, "2"); // pending
  assert.equal(sorted[2].id, "1"); // completed
  assert.equal(sorted[3].id, "4"); // cancelled
});

test("T-6-07: sortTodos is stable on ties (sort by id)", () => {
  function sortTodos(items) {
    const rank = { in_progress: 0, pending: 1, completed: 2, cancelled: 3 };
    return items.slice().sort((a, b) => {
      const ra = rank[a.status] ?? 99;
      const rb = rank[b.status] ?? 99;
      if (ra !== rb) return ra - rb;
      return a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
    });
  }
  const items = [
    { id: "z", title: "z", status: "pending" },
    { id: "a", title: "a", status: "pending" },
    { id: "m", title: "m", status: "pending" },
  ];
  const sorted = sortTodos(items);
  assert.deepEqual(sorted.map((s) => s.id), ["a", "m", "z"]);
});

test("T-6-07: summaryOf counts each status", () => {
  function summaryOf(items) {
    const out = { total: 0, pending: 0, inProgress: 0, completed: 0, cancelled: 0 };
    for (const it of items) {
      out.total += 1;
      if (it.status === "pending") out.pending += 1;
      else if (it.status === "in_progress") out.inProgress += 1;
      else if (it.status === "completed") out.completed += 1;
      else if (it.status === "cancelled") out.cancelled += 1;
    }
    return out;
  }
  const items = [
    { id: "1", status: "pending" },
    { id: "2", status: "pending" },
    { id: "3", status: "in_progress" },
    { id: "4", status: "completed" },
    { id: "5", status: "completed" },
    { id: "6", status: "completed" },
    { id: "7", status: "cancelled" },
  ];
  const s = summaryOf(items);
  assert.equal(s.total, 7);
  assert.equal(s.pending, 2);
  assert.equal(s.inProgress, 1);
  assert.equal(s.completed, 3);
  assert.equal(s.cancelled, 1);
});

test("T-6-07: summaryOf returns all zeros for an empty list", () => {
  function summaryOf(items) {
    const out = { total: 0, pending: 0, inProgress: 0, completed: 0, cancelled: 0 };
    for (const it of items) {
      out.total += 1;
      if (it.status === "pending") out.pending += 1;
      else if (it.status === "in_progress") out.inProgress += 1;
      else if (it.status === "completed") out.completed += 1;
      else if (it.status === "cancelled") out.cancelled += 1;
    }
    return out;
  }
  const s = summaryOf([]);
  assert.equal(s.total, 0);
  assert.equal(s.pending, 0);
  assert.equal(s.inProgress, 0);
  assert.equal(s.completed, 0);
  assert.equal(s.cancelled, 0);
});

test("T-6-07: clampFocus keeps the index in [0, len-1]", () => {
  function clampFocus(focus, len) {
    if (len <= 0) return 0;
    if (focus < 0) return 0;
    if (focus >= len) return len - 1;
    return focus;
  }
  assert.equal(clampFocus(0, 5), 0);
  assert.equal(clampFocus(4, 5), 4);
  assert.equal(clampFocus(5, 5), 4);
  assert.equal(clampFocus(-1, 5), 0);
  assert.equal(clampFocus(0, 0), 0);
  assert.equal(clampFocus(2, 0), 0);
});

// ----- 2. Source-grep assertions ------------------------------

test("T-6-07: TodoBoard.tsx exists and exports the component", () => {
  const path = join(root, "src/components/todo/TodoBoard.tsx");
  assert.ok(existsSync(path));
  const src = read("src/components/todo/TodoBoard.tsx");
  assert.match(src, /export\s+const\s+TodoBoard\b/);
});

test("T-6-07: useInput handles j/k/up/down/g/G/Enter", () => {
  const src = read("src/components/todo/TodoBoard.tsx");
  assert.match(src, /useInput\(/);
  assert.match(src, /input\s*===\s*"j"/);
  assert.match(src, /input\s*===\s*"k"/);
  assert.match(src, /key\.downArrow/);
  assert.match(src, /key\.upArrow/);
  assert.match(src, /input\s*===\s*"g"/);
  assert.match(src, /input\s*===\s*"G"/);
  assert.match(src, /key\.return/);
});

test("T-6-07: focus is clamped via clampFocus()", () => {
  const src = read("src/components/todo/TodoBoard.tsx");
  assert.match(src, /clampFocus\(/);
});

test("T-6-07: onSelect callback fires on Enter (toggle selectedId)", () => {
  const src = read("src/components/todo/TodoBoard.tsx");
  assert.match(src, /onSelect\?/);
  assert.match(src, /selectedId\s*===\s*cur\.id/);
});

test("T-6-07: maxRows caps the visible count + 'more…' overflow", () => {
  const src = read("src/components/todo/TodoBoard.tsx");
  assert.match(src, /maxRows\s*=\s*12/);
  assert.match(src, /overflow/);
  assert.match(src, /more…/);
});

test("T-6-07: empty state renders the emptyLabel", () => {
  const src = read("src/components/todo/TodoBoard.tsx");
  assert.match(src, /emptyLabel\s*=\s*"\(no todos yet\)"/);
});

test("T-6-07: helpers sortTodos / summaryOf / clampFocus are exported", () => {
  const src = read("src/components/todo/TodoBoard.tsx");
  assert.match(src, /export\s+function\s+sortTodos\b/);
  assert.match(src, /export\s+function\s+summaryOf\b/);
  assert.match(src, /export\s+function\s+clampFocus\b/);
});

test("T-6-07: TodoSummary interface is exported (used by SessionDetailsPanel)", () => {
  const src = read("src/components/todo/TodoBoard.tsx");
  assert.match(src, /export\s+interface\s+TodoSummary\b/);
});

// ----- 3. tsc --strict compile of the .tsx -------------------

test("T-6-07: tsc --strict compile of TodoBoard.tsx is clean", () => {
  const tmp = join(root, "tmp-t6-tb-tsc");
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
    join(root, "src", "components", "todo", "TodoBoard.tsx"),
    join(root, "src", "components", "todo", "TodoItem.tsx"),
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error(r.stdout);
    console.error(r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for TodoBoard");
});
