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

// R78 E2E: prove the getTraces RPC reflects real engine activity.
//
// We spawn the TUI in line mode, send a real query (the model
// makes at least one tool call), then send /trace. The recent
// traces list in the snapshot should contain at least one
// completed span.

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
child.stdout.on("data", (chunk) => {
  out += chunk.toString();
});
child.stderr.on("data", () => {
  /* ignore daemon stderr */
});

child.on("exit", (code) => {
  // The line-mode TUI dumps the raw JSON: `getTraces → {...}`.
  // We parse it and assert the count fields reflect a real run.
  const m = out.match(/getTraces → (\{.*\})/);
  if (!m) {
    console.log("✗ R78: no getTraces output found");
    console.log("--- full output ---");
    console.log(out);
    process.exit(1);
  }
  let snap;
  try {
    snap = JSON.parse(m[1]);
  } catch (e) {
    console.log("✗ R78: failed to parse getTraces JSON: " + e.message);
    console.log("--- raw output ---");
    console.log(m[1]);
    process.exit(1);
  }
  const traces = Array.isArray(snap.traces) ? snap.traces : [];
  const inFlight = typeof snap.inFlight === "number" ? snap.inFlight : 0;
  const completed = typeof snap.completed === "number" ? snap.completed : 0;
  console.log(`=== traces after a real query ===`);
  console.log(`  traces=${traces.length}  completed=${completed}  inFlight=${inFlight}`);
  // We expect at least one completed root span (the "query" span
  // for the user prompt). Tool spans may or may not appear depending
  // on whether the model invoked a tool.
  if (completed >= 1 && traces.length >= 1) {
    console.log("✓ R78: getTraces correctly tracks completed spans");
    process.exit(0);
  }
  console.log(
    `✗ R78: expected completed>=1 and traces.length>=1; got completed=${completed}, traces.length=${traces.length}`,
  );
  process.exit(1);
});

// Wait for the daemon to be ready, then send query + trace.
setTimeout(() => {
  child.stdin.write("list 3 files in this directory using glob\n");
  // Wait for the query to finish (a few seconds), then send /trace.
  setTimeout(() => {
    child.stdin.write("/trace\n");
    // Keep stdin open briefly so the line mode has time to print
    // the traces block.
    setTimeout(() => {
      try {
        child.stdin.end();
      } catch {
        /* ignore */
      }
    }, 2000);
  }, 8000);
}, 5000);

setTimeout(() => {
  console.log("[e2e] hard timeout — forcing exit");
  try {
    child.kill();
  } catch {
    /* ignore */
  }
  process.exit(1);
}, 60000);
