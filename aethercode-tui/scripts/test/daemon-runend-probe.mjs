// E2E proof: the daemon really does emit a run_end with a stop
// reason when forced to stop. We spawn the daemon with --max-turns 1
// so the very first multi-step query hits the turn cap. The daemon
// then sends a `run_end` notification with stopReason="max_iterations",
// which is the exact event the TUI listens for. We capture the raw
// JSON-RPC traffic and verify the shape.

import { spawn } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { createInterface } from "node:readline";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const jar = join(root, "..", "dist", "aethercode-0.2.1.jar");

console.log(`jar: ${jar}`);

// Spawn the daemon with --max-turns 1 so it stops after one turn.
const child = spawn("java", ["-Xmx1g", "-jar", jar, "--daemon", "--max-turns", "1"], {
  stdio: ["pipe", "pipe", "pipe"],
  cwd: join(root, ".."),
  env: { ...process.env, JAVA_TOOL_OPTIONS: "" },
  windowsHide: true,
});

let nextId = 1;
let runEndCaptured = null;
let streamEventsSeen = [];
let respondedToGetState = false;

const rl = createInterface({ input: child.stdout, crlfDelay: Infinity });
rl.on("line", (line) => {
  const trimmed = line.trim();
  if (trimmed.length === 0) return;
  let msg;
  try { msg = JSON.parse(trimmed); } catch { return; }

  // Notification (no id) — record stream_event for run_end.
  if (!("id" in msg) || msg.id === null || msg.id === undefined) {
    if (msg.method === "stream_event") {
      const ev = (msg.params?.event) ?? {};
      streamEventsSeen.push(ev.type);
      if (ev.type === "run_end") {
        runEndCaptured = ev;
        console.log(`\n[e2e] run_end captured: stopReason=${ev.stopReason}`);
        // Give the daemon a moment to settle, then exit.
        setTimeout(() => {
          child.stdin.end();
          child.kill();
          summarize();
        }, 200);
      }
    }
    return;
  }

  // Response to our request.
  if (msg.id === 1 && !respondedToGetState) {
    respondedToGetState = true;
    const r = JSON.stringify(msg.result);
    console.log(`[e2e] getState → ${r.slice(0, 200)}${r.length > 200 ? "..." : ""}`);
    // Fire off a query that will need multiple turns to complete.
    const id = nextId++;
    child.stdin.write(JSON.stringify({
      jsonrpc: "2.0", id, method: "query",
      params: { prompt: "list the contents of the current directory, then list the contents of the parent directory, then list the contents of the user's home directory — three directory listings in total" },
    }) + "\n");
    console.log(`[e2e] sent query (id=${id})`);
  }
});

child.stderr.on("data", (chunk) => {
  // Daemon logs go to stderr; ignore by default but capture for debug.
  const s = chunk.toString();
  if (s.includes("ERROR") || s.includes("WARN")) {
    process.stderr.write("[daemon-err] " + s);
  }
});

child.on("exit", (code) => {
  console.log(`[e2e] daemon exited with code=${code}`);
  summarize();
});

setTimeout(() => {
  // Send getState after 1s to give the daemon a moment to come up.
  child.stdin.write(JSON.stringify({ jsonrpc: "2.0", id: 1, method: "getState", params: {} }) + "\n");
  console.log(`[e2e] sent getState`);
}, 1000);

// Hard timeout at 60s.
setTimeout(() => {
  console.log("[e2e] hard timeout 60s — forcing exit");
  try { child.kill(); } catch { /* ignore */ }
  summarize();
}, 60000);

function summarize() {
  console.log("\n=== Summary ===");
  console.log("stream events seen:", streamEventsSeen.join(" → "));
  if (runEndCaptured) {
    console.log("run_end.stopReason:", runEndCaptured.stopReason);
    console.log("run_end.usage     :", JSON.stringify(runEndCaptured.usage));
  }
  // The test is considered a pass if the daemon emitted a run_end
  // with a non-null stopReason.
  const ok = runEndCaptured !== null && typeof runEndCaptured.stopReason === "string";
  console.log(ok ? "✓ run-end pipeline works (daemon emitted run_end)" : "✗ no run_end captured");
  process.exit(ok ? 0 : 1);
}
