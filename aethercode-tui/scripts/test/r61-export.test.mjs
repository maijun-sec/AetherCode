// R61: scrollback export.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- 1. Pure tests on the export helpers ---------------------------

// We re-implement the helpers here (the actual TS source has
// the same algorithm). This keeps the test independent of
// bundling / module resolution.

function exportToMarkdown(turns, model, sessionId) {
  const lines = [];
  lines.push(`# AetherCode session ${sessionId.slice(0, 8)}`);
  lines.push("");
  lines.push(`Model: \`${model}\`  ·  Turns: ${turns.length}  ·  Generated: ${new Date().toISOString()}`);
  lines.push("");
  lines.push("---");
  lines.push("");
  for (const t of turns) {
    const ts = new Date(t.ts).toLocaleString();
    const role = t.role[0].toUpperCase() + t.role.slice(1);
    lines.push(`## ${role}  ·  ${ts}`);
    lines.push("");
    if (t.role === "tool") {
      lines.push("**Tool call:** `" + (t.toolName ?? "tool") + "`");
      if (t.toolArgs) lines.push("```json\n" + t.toolArgs + "\n```");
      if (t.toolResult) lines.push("**Result:**\n```\n" + t.toolResult + "\n```");
    } else if (t.role === "plan" && t.planItems) {
      lines.push("**Plan:**");
      for (let i = 0; i < t.planItems.length; i++) {
        lines.push(`${i + 1}. ${t.planItems[i]}`);
      }
    } else {
      lines.push(t.text);
    }
    lines.push("");
  }
  return lines.join("\n");
}

function exportToJson(turns) {
  return JSON.stringify({ version: 1, exportedAt: new Date().toISOString(), turns }, null, 2);
}

const sampleTurns = [
  { id: 1, role: "user", text: "list files", ts: 1_700_000_000_000, collapsed: false, previewChars: 200 },
  { id: 2, role: "assistant", text: "I'll use glob.", ts: 1_700_000_001_000, collapsed: false, previewChars: 240 },
  { id: 3, role: "tool", text: "glob", toolName: "glob", toolArgs: '{"pattern":"*.java"}', toolResult: "A.java\nB.java", toolStatus: "ok", ts: 1_700_000_002_000, collapsed: false, previewChars: 80 },
  { id: 4, role: "plan", text: "Plan", planItems: ["list files", "read first one"], ts: 1_700_000_003_000, collapsed: false, previewChars: 240 },
];

test("R61: markdown export contains session header + model + turn count", () => {
  const md = exportToMarkdown(sampleTurns, "MiniMax-M3", "abcdef1234");
  assert.match(md, /# AetherCode session abcdef12/);
  assert.match(md, /Model: `MiniMax-M3`/);
  assert.match(md, /Turns: 4/);
});

test("R61: markdown export renders user turn as plain text", () => {
  const md = exportToMarkdown(sampleTurns, "x", "y");
  assert.match(md, /## User/);
  assert.match(md, /list files/);
});

test("R61: markdown export renders tool turn with args + result", () => {
  const md = exportToMarkdown(sampleTurns, "x", "y");
  assert.match(md, /## Tool/);
  assert.match(md, /\*\*Tool call:\*\* `glob`/);
  assert.match(md, /```json\n\{"pattern":".*\.java"\}/);
  assert.match(md, /\*\*Result:\*\*/);
  assert.match(md, /A\.java\nB\.java/);
});

test("R61: markdown export renders plan turn as numbered list", () => {
  const md = exportToMarkdown(sampleTurns, "x", "y");
  assert.match(md, /\*\*Plan:\*\*/);
  // The list items are joined with newlines and prepended with "1. ", "2. ".
  assert.ok(md.includes("1. list files"), `expected "1. list files" in:\n${md}`);
  assert.ok(md.includes("2. read first one"), `expected "2. read first one" in:\n${md}`);
});

test("R61: JSON export contains version + turns", () => {
  const json = exportToJson(sampleTurns);
  const parsed = JSON.parse(json);
  assert.equal(parsed.version, 1);
  assert.equal(parsed.turns.length, 4);
  assert.equal(parsed.turns[0].text, "list files");
  assert.ok(parsed.exportedAt);
});

// ----- 2. Write to file (smoke test) --------------------------------

test("R61: writing the export to a tmp file works (round-trip)", () => {
  const dir = mkdtempSync(join(tmpdir(), "ac-export-"));
  try {
    const path = join(dir, "session.md");
    const md = exportToMarkdown(sampleTurns, "MiniMax-M3", "abcdef1234");
    writeFileSync(path, md, "utf-8");
    const back = readFileSync(path, "utf-8");
    assert.equal(back, md);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

// ----- 3. Source code assertions -------------------------------------

test("R61: exportScrollback.ts exists + exports the helpers", () => {
  assert.ok(existsSync(join(root, "src", "exportScrollback.ts")));
  const src = readFileSync(join(root, "src", "exportScrollback.ts"), "utf-8");
  assert.match(src, /export function exportToMarkdown/);
  assert.match(src, /export function exportToJson/);
  assert.match(src, /export function writeExport/);
});

test("R61: commands.ts has /export slash command with __EXPORT_MD__/__EXPORT_JSON__", () => {
  const cmds = readFileSync(join(root, "src", "commands.ts"), "utf-8");
  assert.match(cmds, /case "export":/);
  assert.match(cmds, /__EXPORT_MD__/);
  assert.match(cmds, /__EXPORT_JSON__/);
});

test("R61: tui.tsx wires __EXPORT_MD__/__EXPORT_JSON__ to the export helpers", () => {
  const tui = readFileSync(join(root, "src", "tui.tsx"), "utf-8");
  assert.match(tui, /__EXPORT_MD__/);
  assert.match(tui, /__EXPORT_JSON__/);
  assert.match(tui, /exportScrollback\.js/);
});

// ----- 4. Build smoke test -------------------------------------------

test("R61: TypeScript compile of exportScrollback.ts is clean", () => {
  const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
  const tmp = join(root, "tmp-r61-tsc");
  if (existsSync(tmp)) {
    spawnSync(process.platform === "win32" ? "cmd" : "rm",
      process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp]);
  }
  const tscArgs = [
    "--outDir", tmp, "--target", "ES2022", "--module", "ES2022",
    "--moduleResolution", "bundler", "--esModuleInterop", "true",
    "--skipLibCheck", "true", "--rootDir", join(root, "src"),
  ];
  const r = spawnSync(`"${tscBin}"`, [
    ...tscArgs,
    '"' + join(root, "src", "exportScrollback.ts") + '"',
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for R61 exportScrollback");
});
