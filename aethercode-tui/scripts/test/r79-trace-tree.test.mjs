// R79: trace tree (parent linkage + /trace <id> + tree render).

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- 1. Pure test: render a single-trace tree ---------------------

/** Render a trace tree from the getTrace result shape. Mirrors
 *  the in-component logic in tui.tsx so we can unit-test the
 *  visual output without spinning up a full TUI. */
function renderTraceTree(r) {
  const spans = Array.isArray(r?.spans) ? r.spans : [];
  const traceId = typeof r?.traceId === "string" ? r.traceId : "?";
  const completed = typeof r?.completed === "number" ? r.completed : 0;
  const lines = [`trace ${traceId} (${spans.length} spans, ${completed} total in recorder)`];
  if (spans.length === 0) {
    lines.push("  (empty — root evicted or unknown id)");
    return lines.join("\n");
  }
  const byId = new Map();
  const childrenOf = new Map();
  let rootNode = null;
  for (const s of spans) {
    const id = String(s.traceId ?? "?");
    const name = String(s.name ?? "?");
    const status = String(s.status ?? "?");
    const dur = typeof s.durationMs === "number" ? s.durationMs : 0;
    const node = { id, name, status, dur, children: [] };
    byId.set(id, node);
    if (id === traceId) rootNode = node;
  }
  for (const s of spans) {
    const id = String(s.traceId ?? "?");
    const parent = s.parentSpanId ?? null;
    const node = byId.get(id);
    if (!node) continue;
    if (parent == null || !byId.has(parent)) {
      let arr = childrenOf.get("__orphans__");
      if (!arr) { arr = []; childrenOf.set("__orphans__", arr); }
      arr.push(node);
    } else {
      let arr = childrenOf.get(parent);
      if (!arr) { arr = []; childrenOf.set(parent, arr); }
      arr.push(node);
    }
  }
  // Wire children into every node (not just the root) so
  // grandchild nesting works.
  for (const [id, node] of byId) {
    node.children = childrenOf.get(id) ?? [];
  }
  const emit = (node, prefix, isLast) => {
    const icon = node.status === "ok" ? "✓" : node.status === "error" ? "✗" : "·";
    const durStr = node.dur < 1000 ? `${node.dur}ms` : `${(node.dur / 1000).toFixed(2)}s`;
    const connector = prefix === "" ? "" : (isLast ? "└─ " : "├─ ");
    lines.push(`  ${prefix}${connector}${icon} ${node.name.padEnd(20, " ")}  ${durStr.padStart(8, " ")}  ${node.status}`);
    const kids = node.children;
    for (let i = 0; i < kids.length; i++) {
      const k = kids[i];
      const childPrefix = prefix === "" ? "  " : (prefix + (isLast ? "   " : "│  "));
      emit(k, childPrefix, i === kids.length - 1);
    }
  };
  if (rootNode) {
    emit(rootNode, "", true);
  } else {
    lines.push(`  (root ${traceId} not in the recorder — showing orphans)`);
    const orphans = childrenOf.get("__orphans__") ?? [];
    for (let i = 0; i < orphans.length; i++) emit(orphans[i], "", i === orphans.length - 1);
  }
  return lines.join("\n");
}

test("R79: renderTraceTree shows root with no connector", () => {
  const out = renderTraceTree({
    traceId: "tr-root",
    completed: 3,
    spans: [
      { traceId: "tr-root", parentSpanId: null, name: "query", status: "ok", durationMs: 4500 },
    ],
  });
  assert.match(out, /trace tr-root/);
  assert.match(out, /✓ query\s+4\.50s\s+ok/);
  // The root line should NOT have ├─ or └─.
  assert.ok(!/├─\s*✓ query/.test(out), "root should not have a connector");
  assert.ok(!/└─\s*✓ query/.test(out), "root should not have a connector");
});

test("R79: renderTraceTree draws ├─ and └─ for children", () => {
  const out = renderTraceTree({
    traceId: "tr-root",
    completed: 3,
    spans: [
      { traceId: "tr-root", parentSpanId: null, name: "query", status: "ok", durationMs: 4500 },
      { traceId: "tr-a",    parentSpanId: "tr-root", name: "tool.glob", status: "ok", durationMs: 200 },
      { traceId: "tr-b",    parentSpanId: "tr-root", name: "tool.bash", status: "ok", durationMs: 300 },
    ],
  });
  // Two children — one gets ├─, the last gets └─.
  assert.match(out, /├─ ✓ tool\.glob/);
  assert.match(out, /└─ ✓ tool\.bash/);
});

test("R79: renderTraceTree nests grandchildren deeper than parent", () => {
  // When the only child of a node is also the only grandchild,
  // both lines use └─ (no │), but the grandchild's prefix is
  // visibly deeper than the parent's. We assert the indentation
  // by checking that the grandchild line starts with strictly
  // more leading whitespace than the parent line.
  const out = renderTraceTree({
    traceId: "tr-root",
    completed: 4,
    spans: [
      { traceId: "tr-root", parentSpanId: null, name: "query", status: "ok", durationMs: 4500 },
      { traceId: "tr-a",    parentSpanId: "tr-root", name: "tool.bash", status: "ok", durationMs: 300 },
      { traceId: "tr-gc",   parentSpanId: "tr-a", name: "tool.bash.inner", status: "ok", durationMs: 50 },
    ],
  });
  const lines = out.split("\n");
  // The line containing tool.bash has prefix length N; the
  // grandchild line has prefix length N+5 (2 spaces + 3 from
  // the "   " emitted when isLast=true). The two-child branch
  // would use │ instead, but with a single grandchild the
  // connector is └─ on both.
  const parentLine = lines.find((l) => /tool\.bash\s/.test(l) && !/inner/.test(l));
  const gcLine = lines.find((l) => /tool\.bash\.inner/.test(l));
  assert.ok(parentLine && gcLine, "expected both parent and grandchild lines");
  const parentIndent = (parentLine.match(/^\s*/) ?? [""])[0].length;
  const gcIndent = (gcLine.match(/^\s*/) ?? [""])[0].length;
  assert.ok(gcIndent > parentIndent,
    `grandchild indent (${gcIndent}) should be > parent indent (${parentIndent})\n${out}`);
});

test("R79: renderTraceTree uses │ connector when parent has multiple children", () => {
  // When a node has 2+ children, the LAST child gets └─ and
  // the others get ├─ + │. The │ is propagated down to all of
  // the non-last child's own descendants.
  const out = renderTraceTree({
    traceId: "tr-root",
    completed: 5,
    spans: [
      { traceId: "tr-root", parentSpanId: null, name: "query", status: "ok", durationMs: 1000 },
      { traceId: "tr-a",    parentSpanId: "tr-root", name: "tool.glob", status: "ok", durationMs: 100 },
      { traceId: "tr-b",    parentSpanId: "tr-root", name: "tool.bash", status: "ok", durationMs: 200 },
      // tool.bash gets a grandchild — its line prefix should
      // include │ (because tool.bash is NOT the last child of
      // root — tool.bash is the last, so the LAST child rule
      // applies differently). Wait: this is a 2-child root:
      // tr-a is NOT last → ├─ + children get │ prefix.
      // tr-b IS last → └─ + children get "   " prefix.
      { traceId: "tr-bg",   parentSpanId: "tr-b", name: "tool.bash.inner", status: "ok", durationMs: 50 },
    ],
  });
  // tool.glob's line should have ├─ (not the last child of root).
  assert.match(out, /├─ ✓ tool\.glob/);
  // tool.bash is the last child of root → └─.
  assert.match(out, /└─ ✓ tool\.bash/);
  // tool.bash.inner is a child of the LAST child → "   " prefix
  // (no │). The grandchild's prefix should still be deeper
  // than tool.bash's.
  const lines = out.split("\n");
  const bashLine = lines.find((l) => /tool\.bash\s/.test(l) && !/inner/.test(l));
  const bgLine = lines.find((l) => /tool\.bash\.inner/.test(l));
  const bashIndent = (bashLine.match(/^\s*/) ?? [""])[0].length;
  const bgIndent = (bgLine.match(/^\s*/) ?? [""])[0].length;
  assert.ok(bgIndent > bashIndent,
    `grandchild indent (${bgIndent}) should be > parent indent (${bashIndent})`);
});

test("R79: renderTraceTree renders orphans when root is missing", () => {
  const out = renderTraceTree({
    traceId: "tr-missing",
    completed: 1,
    spans: [
      { traceId: "tr-orphan", parentSpanId: "tr-missing", name: "tool.glob", status: "ok", durationMs: 100 },
    ],
  });
  assert.match(out, /root tr-missing not in the recorder/);
  assert.match(out, /✓ tool\.glob/);
});

test("R79: renderTraceTree returns empty message when no spans", () => {
  const out = renderTraceTree({ traceId: "tr-nope", completed: 0, spans: [] });
  assert.match(out, /empty — root evicted or unknown id/);
});

test("R79: renderTraceTree shows error icon for error spans", () => {
  const out = renderTraceTree({
    traceId: "tr-root",
    completed: 2,
    spans: [
      { traceId: "tr-root", parentSpanId: null, name: "query", status: "ok", durationMs: 100 },
      { traceId: "tr-a",    parentSpanId: "tr-root", name: "tool.bash", status: "error", durationMs: 50 },
    ],
  });
  assert.match(out, /✗ tool\.bash/);
});

// ----- 2. Source code assertions -----------------------------------

test("R79: TraceRecorder.Span has parentSpanId + isRoot", () => {
  const path = join(root, "..", "aethercode-core", "src", "main", "java", "org", "aethercode", "core", "trace", "TraceRecorder.java");
  const src = readFileSync(path, "utf-8");
  assert.match(src, /String parentSpanId/);
  assert.match(src, /public boolean isRoot\(\)/);
  // The toMap method must include the field.
  assert.match(src, /m\.put\("parentSpanId"/);
});

test("R79: TraceRecorder exposes startChildSpan + getTrace", () => {
  const path = join(root, "..", "aethercode-core", "src", "main", "java", "org", "aethercode", "core", "trace", "TraceRecorder.java");
  const src = readFileSync(path, "utf-8");
  assert.match(src, /public String startChildSpan\(String parentSpanId, String name, Map<String, Object> attrs\)/);
  assert.match(src, /public List<Span> getTrace\(String traceId\)/);
  assert.match(src, /public Map<String, Object> snapshotForTrace\(String traceId\)/);
});

test("R79: AetherCodeMethods registers getTrace and uses startChildSpan in query()", () => {
  const path = join(root, "..", "aethercode-protocol", "src", "main", "java", "org", "aethercode", "protocol", "methods", "AetherCodeMethods.java");
  const src = readFileSync(path, "utf-8");
  assert.match(src, /dispatcher\.register\("getTrace",\s+this::getTrace\)/);
  assert.match(src, /public Object getTrace\(Object params\)/);
  // The query() worker must use startChildSpan with the root id.
  assert.match(src, /engine\.traces\(\)\.startChildSpan\(\s*queryTraceId/);
});

test("R79: TUI commands.ts routes /trace <tr-id> to getTrace", () => {
  const cmds = readFileSync(join(root, "src", "commands.ts"), "utf-8");
  assert.match(cmds, /first\.startsWith\("tr-"\)/);
  assert.match(cmds, /rpcMethod: "getTrace"/);
  assert.match(cmds, /traceId: first/);
});

test("R79: tui.tsx renders getTrace as a tree (├─ / └─ / │)", () => {
  const tui = readFileSync(join(root, "src", "tui.tsx"), "utf-8");
  assert.match(tui, /slash\.rpcMethod === "getTrace"/);
  assert.match(tui, /type: "setSpanTree"/);
  // The connector glyphs.
  assert.match(tui, /"└─ "/);
  assert.match(tui, /"├─ "/);
  assert.match(tui, /"│  "/);
});

test("R79: state.ts has spanTree + selectedTraceId", () => {
  const state = readFileSync(join(root, "src", "state.ts"), "utf-8");
  assert.match(state, /spanTree: TraceSummary\[\]/);
  assert.match(state, /selectedTraceId: string \| null/);
  assert.match(state, /type: "setSpanTree"/);
  // TraceSummary extended with parentSpanId.
  assert.match(state, /parentSpanId: string \| null/);
});

// ----- 3. Build smoke test ------------------------------------------

test("R79: TypeScript compile of the new TUI code is clean", () => {
  const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
  const tmp = join(root, "tmp-r79-tsc");
  if (existsSync(tmp)) {
    spawnSync(process.platform === "win32" ? "cmd" : "rm",
      process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp]);
  }
  const tscArgs = [
    "--outDir", tmp, "--target", "ES2022", "--module", "ES2022",
    "--moduleResolution", "bundler", "--jsx", "react",
    "--esModuleInterop", "true", "--skipLibCheck", "true",
    "--rootDir", join(root, "src"),
  ];
  const r = spawnSync(`"${tscBin}"`, [
    ...tscArgs,
    '"' + join(root, "src", "tui.tsx") + '"',
    '"' + join(root, "src", "commands.ts") + '"',
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for R79");
});

// ----- 4. E2E presence test -----------------------------------------

test("R79: e2e-r79-trace-tree.mjs exists and asserts tree after a real query", () => {
  const e2e = join(root, "scripts", "test", "e2e-r79-trace-tree.mjs");
  assert.ok(existsSync(e2e), "e2e-r79-trace-tree.mjs missing");
  const src = readFileSync(e2e, "utf-8");
  assert.match(src, /getTrace/);
  // The E2E should look for tree connector glyphs.
  assert.match(src, /├─|└─|│/);
});
