// T-6-13 (Phase 6.2): SummaryFooter — extract `## Summary`
// block from an assistant message; always-visible card.
//
// 6 tests:
//   1. file exists + exports the public API
//   2. pure: extractSummary finds `## Summary` block + trims
//   3. pure: extractSummary returns null for missing block
//   4. pure: splitAssistantMessage returns { lead, summary }
//   5. pure: extractSummary flags <auto-summary> tag
//   6. source: SummaryFooter renders 'auto-summary pending' when null

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- 1. File exists + public API -------------------------------------

test("T-6-13: SummaryFooter.tsx exists and exports the footer + extract helpers", () => {
  const path = join(root, "src", "components", "chat", "SummaryFooter.tsx");
  assert.ok(existsSync(path), `expected ${path}`);
  const src = readFileSync(path, "utf-8");
  assert.match(src, /export const SummaryFooter/);
  assert.match(src, /export default SummaryFooter/);
  // Pure-helper exports.
  assert.match(src, /export function extractSummary/);
  assert.match(src, /export function splitAssistantMessage/);
  // Type export.
  assert.match(src, /export interface Summary\b/);
});

// ----- 2. Pure: extractSummary finds the block -------------------------

const tmp = join(root, "tmp-t6-13-tsc");
const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
if (existsSync(tmp)) {
  spawnSync(process.platform === "win32" ? "cmd" : "rm",
    process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp]);
}
mkdirSync(tmp, { recursive: true });
const shimPath = join(root, "src", "components", "chat", "_t6_13_helpers.ts");
const shim = [
  `export { extractSummary, splitAssistantMessage } from "./SummaryFooter.js";`,
  "",
].join("\n");
writeFileSync(shimPath, shim, "utf-8");

const tscArgs = [
  "--outDir", join(tmp, "out"), "--target", "ES2022", "--module", "ES2022",
  "--moduleResolution", "bundler", "--jsx", "react",
  "--esModuleInterop", "true", "--skipLibCheck", "true",
  "--rootDir", join(root, "src"),
];
{
  const r = spawnSync(`"${tscBin}"`, [...tscArgs, `"${shimPath}"`], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
    process.exit(1);
  }
}
const helpers = await import(
  "file:///" + join(tmp, "out", "components", "chat", "_t6_13_helpers.js").replace(/\\/g, "/")
);
const { extractSummary, splitAssistantMessage } = helpers;

test("T-6-13: extractSummary finds the `## Summary` block and trims it", () => {
  const text = "Here is my answer.\n\nSome more text.\n\n## Summary\nDid X, going to Y.\n";
  const s = extractSummary(text);
  assert.ok(s, "expected to find a summary block");
  assert.equal(s.text, "Did X, going to Y.");
  assert.equal(s.autoInjected, false);
});

test("T-6-13: extractSummary returns null when the block is missing", () => {
  assert.equal(extractSummary("just a message without a summary block"), null);
  assert.equal(extractSummary(""), null);
  assert.equal(extractSummary("## Not Summary\nbody"), null);
  // The block has to have body content.
  assert.equal(extractSummary("## Summary\n"), null);
  assert.equal(extractSummary("## Summary\n   \n"), null);
});

// ----- 3. Pure: splitAssistantMessage ---------------------------------

test("T-6-13: splitAssistantMessage returns { lead, summary }", () => {
  const text = "Intro line.\nMore.\n\n## Summary\nDid X.";
  const { lead, summary } = splitAssistantMessage(text);
  assert.ok(lead.startsWith("Intro line."));
  assert.ok(lead.includes("More."));
  assert.ok(summary);
  assert.equal(summary.text, "Did X.");
  // No block → lead is the whole text, summary null.
  const r2 = splitAssistantMessage("no block here");
  assert.equal(r2.lead, "no block here");
  assert.equal(r2.summary, null);
});

// ----- 4. Pure: extractSummary flags <auto-summary> tag ----------------

test("T-6-13: extractSummary flags <auto-summary> tag and strips it from the body", () => {
  const text = "## Summary\n<auto-summary>\nAuto-injected summary text.";
  const s = extractSummary(text);
  assert.ok(s);
  assert.equal(s.autoInjected, true);
  assert.equal(s.text, "Auto-injected summary text.");
});

// ----- 5. Source: SummaryFooter renders fallback -----------------------

test("T-6-13: SummaryFooter.tsx renders 'auto-summary pending' badge when summary is null", () => {
  const src = readFileSync(join(root, "src", "components", "chat", "SummaryFooter.tsx"), "utf-8");
  assert.match(src, /auto-summary pending/);
  // 3 visual states.
  assert.match(src, /autoInjected/);
  // Always visible by default (the visible prop defaults to true).
  assert.match(src, /visible\?/);
});

// ----- 6. tsc: SummaryFooter.tsx compiles cleanly ---------------------

test("T-6-13: TypeScript compile of SummaryFooter.tsx is clean", () => {
  const r = spawnSync(`"${tscBin}"`, [
    ...tscArgs,
    '"' + join(root, "src", "components", "chat", "SummaryFooter.tsx") + '"',
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for T-6-13 SummaryFooter");
});
