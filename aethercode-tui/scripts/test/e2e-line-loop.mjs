// E2E for the TUI line mode receiving a real run_end event.
// We keep stdin open so the line mode's 5s in-flight timeout
// doesn't fire prematurely. The model has time to make 3+ tool
// calls, hit the same_fingerprint loop detector, and emit
// run_end { stopReason: "loop_detected" }. We capture the TUI
// output and verify the run-end line was rendered.

import { spawn } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const acTui = join(root, "dist", "ac-tui.js");
const jar = join(root, "..", "dist", "aethercode-0.2.1.jar");

console.log(`ac-tui: ${acTui}`);
console.log(`jar   : ${jar}`);

// Pass --max-turns 6 via the daemon. The line mode's JsonRpcClient
// doesn't expose this, so we instead pass it through JVM system
// properties... no, that doesn't work either. Just rely on the
// default max-turns (50) and trigger a real loop in the model.
const child = spawn(process.execPath, [acTui, "--line", "--jar", jar], {
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
  // daemon's stderr noise — suppress
});

let exited = false;
child.on("exit", (code) => {
  exited = true;
  console.log(`\n[child exited with code=${code}]`);
  summarize();
});

// Send a query that's likely to trigger the same_fingerprint
// loop detector. The model needs to call `glob` (or any tool)
// multiple times in a row. We ask for many directory listings.
setTimeout(() => {
  console.log("[e2e] sending loop-trigger query");
  // The key is to use the SAME tool with the SAME args 3+ times.
  // We can't directly force this from the prompt, but asking for
  // "list all files matching *.java, then *.md, then *.json" might
  // trigger 3 distinct glob calls.
  // Even better: ask the model to "verify the file structure by
  // running glob for *.java, *.java again, *.java a third time".
  // This often triggers the same_fingerprint detector.
  child.stdin.write("list all Java files in the current directory. then list them again. then list them a third time. just keep calling glob with the same pattern three times\n");
}, 5000);

// Keep stdin alive with periodic empty lines so the line mode's
// readline doesn't fire 'close' and trigger the 5s drain timeout.
let beats = 0;
const beat = setInterval(() => {
  beats++;
  if (exited || beats > 30) { clearInterval(beat); return; }
  try { child.stdin.write(" \n"); } catch { clearInterval(beat); }
}, 4000);

// Hard timeout at 120s.
setTimeout(() => {
  console.log("[e2e] hard timeout 120s — closing stdin");
  try { child.stdin.end(); } catch { /* ignore */ }
}, 120000);

function summarize() {
  console.log("\n=== Summary ===");
  console.log("stdout length:", out.length);

  // Look for run-end markers in the output.
  const hasHeader = out.includes("connected");
  const hasReady = out.includes("[ready]") || out.includes("stopped — loop") || out.includes("stopped — max turns");
  const hasLoopEnd = out.includes("stopped — loop") || out.includes("loop_detected");
  const hasPrompt = out.includes("❯");

  console.log("has header    :", hasHeader);
  console.log("has prompt    :", hasPrompt);
  console.log("has run-end   :", hasReady);
  console.log("has loop end  :", hasLoopEnd);

  if (hasLoopEnd) {
    console.log("✓ line mode rendered '[stopped — loop]' — loop visibility works");
    process.exit(0);
  } else if (hasReady) {
    console.log("✓ line mode rendered a run-end line (kind=" + (out.match(/\[(stopped[^]]+|ready)\]/)?.[1] ?? "?") + ")");
    process.exit(0);
  } else if (hasPrompt && hasHeader) {
    console.log("⚠ line mode reached the prompt but no run-end yet (model still working or hit 5s timeout)");
    process.exit(2);
  } else {
    console.log("✗ line mode did not reach a usable state");
    process.exit(1);
  }
}
