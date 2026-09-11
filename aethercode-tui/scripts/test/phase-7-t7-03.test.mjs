// T-7-03: TranscriptEnricher (TUI auto-summary middleware).
//
// 5 tests:
//   1. extractSummary returns the body of `## Summary`.
//   2. hasSummaryBlock is true when the block is present
//      and non-empty.
//   3. enrichmentRequired detects missing summaries and
//      ignores non-assistant turns.
//   4. isSummaryInjectedEvent + summaryFromEvent are
//      the right pure helpers for the event stream.
//   5. buildAutoSummaryPrompt is the spec's verbatim text.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const tscBin = join(
  root, "node_modules", ".bin",
  process.platform === "win32" ? "tsc.cmd" : "tsc"
);
const read = (rel) => readFileSync(join(root, rel), "utf-8");

// ----- 1. Build a tiny shim so we can import the helpers --------

const tmp = join(root, "tmp-t7-03-tsc");
if (existsSync(tmp)) {
  spawnSync(process.platform === "win32" ? "cmd" : "rm",
    process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp],
    { shell: process.platform === "win32" });
}
mkdirSync(tmp, { recursive: true });
const shimPath = join(root, "src", "components", "chat", "_t7_03_shim.ts");
const shim = `export {
  extractSummary,
  hasSummaryBlock,
  enrichmentRequired,
  buildAutoSummaryPrompt,
  isSummaryInjectedEvent,
  summaryFromEvent,
} from "./TranscriptEnricher.js";\n`;
writeFileSync(shimPath, shim, "utf-8");

const r = spawnSync(`"${tscBin}"`, [
  "--outDir", tmp, "--target", "ES2022", "--module", "ES2022",
  "--moduleResolution", "bundler", "--jsx", "react",
  "--esModuleInterop", "true", "--skipLibCheck", "true",
  "--strict", "true",
  "--rootDir", join(root, "src"),
  join(root, "src", "components", "chat", "TranscriptEnricher.ts"),
], { encoding: "utf-8", shell: true });
if (r.status !== 0) {
  console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  process.exit(1);
}

const mod = await import(pathToFileURL(join(tmp, "components", "chat", "TranscriptEnricher.js")).href);
const {
  extractSummary,
  hasSummaryBlock,
  enrichmentRequired,
  buildAutoSummaryPrompt,
  isSummaryInjectedEvent,
  summaryFromEvent,
} = mod;

// ----- 2. Source-grep: the spec-mandated constants ---------------

test("T-7-03: TranscriptEnricher.ts exists and exports the helpers", () => {
  const path = join(root, "src/components/chat/TranscriptEnricher.ts");
  assert.ok(existsSync(path));
  const src = read("src/components/chat/TranscriptEnricher.ts");
  assert.match(src, /export\s+function\s+extractSummary\b/);
  assert.match(src, /export\s+function\s+hasSummaryBlock\b/);
  assert.match(src, /export\s+function\s+enrichmentRequired\b/);
  assert.match(src, /export\s+function\s+buildAutoSummaryPrompt\b/);
  assert.match(src, /export\s+function\s+isSummaryInjectedEvent\b/);
  assert.match(src, /export\s+function\s+summaryFromEvent\b/);
});

test("T-7-03: tsc --strict compile of TranscriptEnricher.ts is clean", () => {
  // The shim compile above already proved this; this
  // explicit test makes the intent obvious in the report.
  assert.ok(existsSync(join(tmp, "components", "chat", "TranscriptEnricher.js")));
});

// ----- 3. Pure-helper tests -------------------------------------

test("T-7-03: extractSummary returns the body of `## Summary` block", () => {
  const text = `Hello there.\n\n## Summary\nWired up the RPC client.\nConnected to supervisor.\n\n## Next steps\nWait for the daemon.\n`;
  assert.equal(extractSummary(text), "Wired up the RPC client.\nConnected to supervisor.");
  // No block.
  assert.equal(extractSummary("just prose"), null);
  // Empty block.
  assert.equal(extractSummary("## Summary\n\n## Next\nstuff"), null);
  // Heading on its own line.
  assert.equal(extractSummary("## Summary\nDone."), "Done.");
});

test("T-7-03: hasSummaryBlock is true iff the block is present and non-empty", () => {
  assert.equal(hasSummaryBlock("hi"), false);
  assert.equal(hasSummaryBlock("## Summary\n"), false);
  assert.equal(hasSummaryBlock("## Summary\nDid the thing."), true);
  assert.equal(hasSummaryBlock("## Summary\n\n## Next\nstuff"), false);
});

test("T-7-03: enrichmentRequired detects missing summaries + ignores non-assistant turns", () => {
  const messages = [
    { role: "user", text: "do thing" },
    { role: "assistant", text: "Doing the thing.\n\n## Summary\nDone." },
    { role: "user", text: "more" },
    { role: "assistant", text: "Did more." }, // missing summary
  ];
  assert.equal(enrichmentRequired(messages, 1), false); // has summary
  assert.equal(enrichmentRequired(messages, 3), true);  // missing
  // Last index out of range.
  assert.equal(enrichmentRequired(messages, -1), false);
  assert.equal(enrichmentRequired(messages, 99), false);
  // Non-assistant last → no enrichment.
  const noAssist = [
    { role: "user", text: "hi" },
    { role: "user", text: "there" },
  ];
  assert.equal(enrichmentRequired(noAssist, 1), false);
});

test("T-7-03: isSummaryInjectedEvent + summaryFromEvent are the right shape for the event stream", () => {
  assert.equal(isSummaryInjectedEvent({ kind: "summary_injected", params: {} }), true);
  assert.equal(isSummaryInjectedEvent({ kind: "tool_call" }), false);
  assert.equal(isSummaryInjectedEvent(null), false);
  assert.equal(isSummaryInjectedEvent(undefined), false);
  // summaryFromEvent
  assert.equal(summaryFromEvent({ params: { summary: "  hi  " } }), "hi");
  assert.equal(summaryFromEvent({ params: { summary: "" } }), null);
  assert.equal(summaryFromEvent({ params: {} }), null);
  assert.equal(summaryFromEvent({}), null);
});

test("T-7-03: buildAutoSummaryPrompt is the spec's verbatim text", () => {
  const messages = [
    { role: "user", text: "u1" },
    { role: "assistant", text: "a1" },
    { role: "user", text: "u2" },
    { role: "assistant", text: "a2" },
    { role: "user", text: "u3" },
    { role: "assistant", text: "a3" },
    { role: "user", text: "u4" },
    { role: "assistant", text: "a4" },
  ];
  const prompt = buildAutoSummaryPrompt(messages, messages.length - 1);
  // Per spec §12.3 the text is:
  //   "Summarize the last N messages in 1-3 lines,
  //    naming what was done and what is intended next."
  assert.match(prompt, /Summarize the last \d+ messages/);
  assert.match(prompt, /1-3 lines/);
  assert.match(prompt, /naming what was done/);
  assert.match(prompt, /what is intended next/);
});
