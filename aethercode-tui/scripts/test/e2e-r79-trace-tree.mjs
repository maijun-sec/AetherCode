// R79 E2E: prove the getTrace RPC returns a proper tree (root +
// at least one tool child) for a real query.
//
// Step 1: send a real query that triggers a tool call.
// Step 2: wait for it to finish, then send /trace to list the
//         recent spans.
// Step 3: parse stdout, find a query root's traceId, and issue
//         /trace <rootId> to fetch the tree.
// Step 4: assert the tree has the root + at least one child,
//         with the correct parent linkage.

import { spawn } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const acTui = join(root, "dist", "ac-tui.js");
const jar = join(root, "..", "dist", "aethercode-0.2.1.jar");

const child = spawn(process.execPath, [acTui, "--line", "--jar", jar], {
  stdio: ["pipe", "pipe", "pipe"],
  cwd: join(root, ".."),
  env: { ...process.env, JAVA_TOOL_OPTIONS: "" },
});

let out = "";
child.stdout.on("data", (chunk) => { out += chunk.toString(); });
child.stderr.on("data", () => { /* ignore daemon stderr */ });

let stage = "init";
let listSent = false;
let treeSent = false;

function fail(msg, code = 1) {
  console.log(`✗ R79: ${msg}`);
  console.log("--- full output ---");
  console.log(out);
  try { child.kill(); } catch { /* ignore */ }
  process.exit(code);
}

function tryAdvance() {
  if (stage === "init") {
    // Step 1: trigger a real query.
    console.log("[stage] sending initial query");
    child.stdin.write("list 3 files in this directory using glob\n");
    stage = "waiting-query";
  } else if (stage === "waiting-query" && /\(reason: stop\)|stop\)/.test(out)) {
    // Step 2: send /trace to list recent spans.
    if (!listSent) {
      listSent = true;
      console.log("[stage] query done; sending /trace");
      child.stdin.write("/trace\n");
      stage = "waiting-list";
    }
  } else if (stage === "waiting-list") {
    // Look for the getTraces JSON line.
    const m = out.match(/getTraces → (\{[\s\S]*?\})\n/);
    if (m) {
      let snap;
      try { snap = JSON.parse(m[1]); } catch (e) {
        return fail("failed to parse getTraces JSON: " + e.message);
      }
      const traces = Array.isArray(snap.traces) ? snap.traces : [];
      if (traces.length === 0) return fail("no traces in getTraces output");
      // The most recent root span is our query root.
      const rootSpan = traces.find((s) => s.parentSpanId == null);
      if (!rootSpan) return fail("no root span (parentSpanId all set?)");
      const rootId = rootSpan.traceId;
      console.log(`[stage] list done; root = ${rootId}; sending /trace ${rootId}`);
      child.stdin.write(`/trace ${rootId}\n`);
      stage = "waiting-tree";
    }
  } else if (stage === "waiting-tree") {
    // Look for the getTrace JSON line.
    const m = out.match(/getTrace → (\{[\s\S]*?\})\n/);
    if (m && !treeSent) {
      treeSent = true;
      let tree;
      try { tree = JSON.parse(m[1]); } catch (e) {
        return fail("failed to parse getTrace JSON: " + e.message);
      }
      const spans = Array.isArray(tree.spans) ? tree.spans : [];
      console.log(`[stage] tree: ${spans.length} spans`);
      for (const s of spans) {
        console.log(`    ${s.parentSpanId ? "└─" : "  "} ${s.name} (${s.status}, ${s.durationMs}ms, parent=${s.parentSpanId ?? "null"})`);
      }
      if (spans.length < 2) {
        return fail(`expected >= 2 spans, got ${spans.length}`);
      }
      const first = spans[0];
      if (first.parentSpanId !== null) {
        return fail(`expected first span parentSpanId=null, got ${first.parentSpanId}`);
      }
      const hasChild = spans.some((s) => s.parentSpanId === first.traceId);
      if (!hasChild) {
        return fail("no span has parentSpanId equal to the root");
      }
      console.log("✓ R79: getTrace returns a proper tree (root + child + correct parent linkage)");
      try { child.kill(); } catch { /* ignore */ }
      process.exit(0);
    }
  }
}

// Poll the buffer every 200ms.
const id = setInterval(() => {
  try { tryAdvance(); } catch (e) { fail("exception: " + e.message); }
}, 200);

setTimeout(() => {
  clearInterval(id);
  fail("hard timeout — never reached final stage");
}, 60000);
