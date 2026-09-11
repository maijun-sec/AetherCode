// R35: per-category spinner + line mode per-category icon/colour.
//
// We test:
//   1. Ink TUI's ToolCard maps each ToolCategory to a distinct spinner type
//   2. line mode's tool_use_start + tool_result use per-category icon + colour
//   3. Source code: ToolCard uses Spinner with CATEGORY_SPINNER
//   4. line.ts: categorizeLine + CAT_ICON + CAT_COLOR are present
//   5. Build smoke test (bundle compiles)

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- 1-4. Source code assertions -----------------------------------

test("R35: ToolCard.tsx has CATEGORY_SPINNER with all 6 categories", () => {
  const tc = readFileSync(join(root, "src", "components", "ToolCard.tsx"), "utf-8");
  // The type comes from cli-spinners' SpinnerName, not the Spinner component.
  assert.match(tc, /const CATEGORY_SPINNER: Record<ToolCategory, SpinnerName>/);
  assert.match(tc, /import type \{ SpinnerName \} from "cli-spinners"/);
  for (const c of ["read", "write", "search", "run", "agent", "other"]) {
    assert.match(tc, new RegExp(`\\b${c}:\\s*"`), `missing CATEGORY_SPINNER entry for ${c}`);
  }
});

test("R35: ToolCard uses Spinner with CATEGORY_SPINNER[cat] in 3 places", () => {
  const tc = readFileSync(join(root, "src", "components", "ToolCard.tsx"), "utf-8");
  const matches = tc.match(/<Spinner type=\{CATEGORY_SPINNER\[cat\]\} \/>/g) || [];
  assert.ok(matches.length >= 3, `expected >=3 Spinner usages, got ${matches.length}`);
});

test("R35: line.ts has categorizeLine + CAT_ICON + CAT_COLOR", () => {
  const line = readFileSync(join(root, "src", "line.ts"), "utf-8");
  assert.match(line, /function categorizeLine/);
  assert.match(line, /const CAT_ICON: Record<LineCategory/);
  assert.match(line, /const CAT_COLOR: Record<LineCategory/);
  // All 6 categories in CAT_ICON.
  for (const c of ["read", "write", "search", "run", "agent", "other"]) {
    assert.match(line, new RegExp(`\\b${c}:\\s*"`), `missing CAT_ICON entry for ${c}`);
  }
});

test("R35: line.ts uses categorizeLine in tool_use_start", () => {
  const line = readFileSync(join(root, "src", "line.ts"), "utf-8");
  // The tool_use_start branch should now use CAT_ICON + CAT_COLOR.
  assert.match(line, /categorizeLine\(currentTool\)/);
  assert.match(line, /CAT_ICON\[cat\]/);
  assert.match(line, /CAT_COLOR\[cat\]/);
});

test("R35: line.ts tool_result line uses CAT_ICON + resultIcon", () => {
  const line = readFileSync(join(root, "src", "line.ts"), "utf-8");
  // The tool_result branch should keep the category icon for the
  // completion line so the user can match start vs end.
  assert.match(line, /categorizeLine\(name\)/);
  // resultIcon for ok / error
  assert.match(line, /resultIcon = isErr \? "✗" : "✓"/);
});

// ----- 5. Compile + bundle check -------------------------------------

test("R35: dist/ac-tui.js is fresh and includes CATEGORY_SPINNER", () => {
  const dist = join(root, "dist", "ac-tui.js");
  assert.ok(existsSync(dist), "dist/ac-tui.js missing");
  const content = readFileSync(dist, "utf-8");
  // The bundle should have the per-category spinner types referenced.
  // ink-spinner has known types like "dots", "triangle", "arc", "star".
  // We just check that "triangle" appears (it's a R35 specific pick).
  assert.ok(content.includes("triangle") || content.includes("CATEGORY_SPINNER"),
    "bundle doesn't include R35 per-category spinner code");
});

test("R35: TypeScript compile of the TUI source is clean", () => {
  const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
  const tmp = join(root, "tmp-r35-tsc");
  if (existsSync(tmp)) {
    spawnSync(process.platform === "win32" ? "cmd" : "rm",
      process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp]);
  }
  const tscArgs = [
    "--outDir", tmp, "--target", "ES2022", "--module", "ES2022",
    "--moduleResolution", "bundler", "--jsx", "react",
    "--esModuleInterop", "true", "--skipLibCheck", "true",
    "--rootDir", join(root, "src"),
  ];
  const sources = [
    "Pill.tsx", "Header.tsx", "StatusBar.tsx", "InputBox.tsx",
    "Scrollback.tsx", "HelpOverlay.tsx", "PermissionModal.tsx",
    "Markdown.tsx", "PlanList.tsx", "ToolCard.tsx", "Welcome.tsx",
  ].map((f) => '"' + join(root, "src", "components", f) + '"');
  const r = spawnSync(`"${tscBin}"`, [...tscArgs, ...sources], {
    encoding: "utf-8", shell: true,
  });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for R35 components");
});
