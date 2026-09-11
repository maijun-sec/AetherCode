// R77 E2E: prove the metrics RPC reflects real engine activity.
//
// We spawn the TUI in line mode, send a real query (the model
// makes at least one tool call), then send /metrics. The
// counters in the snapshot should be > 0.

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

child.on("exit", (code) => {
  // Pull the metrics JSON line from the output.
  const m = out.match(/getMetrics → (\{.*?\})/);
  if (m) {
    const snap = JSON.parse(m[1]);
    console.log("=== metrics after a real query ===");
    for (const k of Object.keys(snap)) {
      console.log(`  ${k.padEnd(20, " ")}  ${snap[k]}`);
    }
    // We expect: turnsStarted >= 1, turnsCompleted >= 1, toolCalls >= 1.
    if (snap.turnsStarted >= 1 && snap.toolCalls >= 1 && snap.turnsCompleted >= 1) {
      console.log("✓ R77: metrics counters correctly track engine activity");
      process.exit(0);
    } else {
      console.log(`✗ R77: expected turnsStarted>=1, toolCalls>=1, turnsCompleted>=1; got turnsStarted=${snap.turnsStarted}, toolCalls=${snap.toolCalls}, turnsCompleted=${snap.turnsCompleted}`);
      process.exit(1);
    }
  } else {
    console.log("✗ R77: no getMetrics output found");
    console.log("--- full output ---");
    console.log(out);
    process.exit(1);
  }
});

// Wait for the daemon to be ready, then send query + metrics.
setTimeout(() => {
  child.stdin.write("list 3 files in this directory using glob\n");
  // Wait for the query to finish (a few seconds), then send /metrics.
  setTimeout(() => {
    child.stdin.write("/metrics\n");
    // Keep stdin open briefly so the line mode has time to print
    // the metrics line.
    setTimeout(() => {
      try { child.stdin.end(); } catch { /* ignore */ }
    }, 2000);
  }, 8000);
}, 5000);

setTimeout(() => {
  console.log("[e2e] hard timeout — forcing exit");
  try { child.kill(); } catch { /* ignore */ }
  process.exit(1);
}, 60000);
