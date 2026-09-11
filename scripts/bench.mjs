#!/usr/bin/env node
/**
 * AetherCode unified benchmark entry point.
 *
 * Runs both the TUI frame budget (T-505) and the LLM
 * round-trip (T-506) benchmarks in sequence. Exits
 * non-zero if either fails its p99 budget.
 *
 * Usage: node scripts/bench.mjs
 */

import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

function runScript(name) {
  console.log("");
  console.log("=".repeat(60));
  console.log(`Running ${name}`);
  console.log("=".repeat(60));
  const r = spawnSync(process.execPath, [path.join(__dirname, name)], {
    stdio: "inherit",
  });
  if (r.status !== 0) {
    console.error(`${name} failed with exit code ${r.status}`);
    return r.status;
  }
  return 0;
}

const a = runScript("bench-tui.mjs");
const b = runScript("bench-llm.mjs");

if (a !== 0 || b !== 0) {
  console.error("");
  console.error("Bench summary: FAIL");
  process.exit(1);
}
console.log("");
console.log("Bench summary: PASS");
