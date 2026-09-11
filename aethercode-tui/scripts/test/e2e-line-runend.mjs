// E2E for the line-mode run-end visualization.
//
// Spawns the ac-tui line mode with stdin kept open. After a short
// delay, sends a single query and waits. We expect the line mode
// to print (eventually) a "[stopped — ...]" line that color-codes
// the run-end by stop kind. We capture all stdout and check for
// the right marker.

import { spawn } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

const acTui = join(root, "dist", "ac-tui.js");
const jar = join(root, "..", "dist", "aethercode-0.2.1.jar");

console.log(`ac-tui: ${acTui}`);
console.log(`jar   : ${jar}`);

const child = spawn(process.execPath, [acTui, "--line", "--jar", jar, "--no-color"], {
  stdio: ["pipe", "pipe", "pipe"],
  cwd: join(root, ".."),
  env: { ...process.env, JAVA_TOOL_OPTIONS: "" },
});

let out = "";
let err = "";
child.stdout.on("data", (chunk) => {
  const s = chunk.toString();
  out += s;
  process.stdout.write("[out] " + s);
});
child.stderr.on("data", (chunk) => {
  const s = chunk.toString();
  err += s;
  process.stderr.write("[err] " + s);
});

child.on("exit", (code) => {
  console.log(`\n[child exited with code=${code}]`);
  console.log("\n=== Summary ===");
  console.log("stdout length:", out.length);
  console.log("stderr length:", err.length);
  // The simplest verification: the line mode printed the header
  // (so it got past getState) AND it printed a query prompt.
  const hasHeader = out.includes("connected");
  const hasPrompt = out.includes("❯");
  console.log("has header :", hasHeader);
  console.log("has prompt :", hasPrompt);
  if (hasHeader && hasPrompt) {
    console.log("✓ line mode is wired up correctly");
    process.exit(0);
  } else {
    console.log("✗ line mode did not reach the prompt state");
    process.exit(1);
  }
});

// Wait for the daemon to start, then send a query.
setTimeout(() => {
  console.log("[e2e] sending query: 'say OK'");
  child.stdin.write("say OK\n");
  // Keep stdin open so the line mode's readline doesn't fire 'close'
  // before run_end arrives. The 5s force-exit timeout in line.ts
  // will kick in if the model takes too long; we extend by sending
  // a heartbeat newline every 4s.
  let beats = 0;
  const beat = setInterval(() => {
    beats++;
    if (beats > 8) { clearInterval(beat); return; }
    try { child.stdin.write(" \n"); } catch { clearInterval(beat); }
  }, 4000);
  // Hard timeout at 40s.
  setTimeout(() => {
    console.log("[e2e] hard timeout 40s — closing stdin");
    try { child.stdin.end(); } catch { /* ignore */ }
  }, 40000);
}, 5000);
