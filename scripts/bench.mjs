#!/usr/bin/env node
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
