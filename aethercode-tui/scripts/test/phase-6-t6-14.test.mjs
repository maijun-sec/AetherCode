// T-6-14 (Phase 6.2): Scrollback collapsed-by-default +
// SummaryFooter integration.
//
// 6 tests:
//   1. file exists + Scrollback imports SummaryFooter
//   2. Scrollback: assistant turn renders SummaryFooter below the body
//   3. Scrollback: tool / plan / diff cards start collapsed (effectiveCollapsed)
//   4. Scrollback: SummaryFooter is ALWAYS visible (not gated by effectiveCollapsed)
//   5. Scrollback: renderStopBanner still present (no regression)
//   6. tsc: Scrollback.tsx compiles cleanly

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

const scrollbackPath = join(root, "src", "components", "Scrollback.tsx");
const summaryPath   = join(root, "src", "components", "chat", "SummaryFooter.tsx");
const toolCardPath  = join(root, "src", "components", "ToolCard.tsx");
const planListPath  = join(root, "src", "components", "PlanList.tsx");

// ----- 1. File exists + imports SummaryFooter --------------------------

test("T-6-14: Scrollback.tsx imports SummaryFooter from ./chat/SummaryFooter.js", () => {
  assert.ok(existsSync(scrollbackPath));
  assert.ok(existsSync(summaryPath));
  const src = readFileSync(scrollbackPath, "utf-8");
  // The integration import.
  assert.match(src, /import \{[^}]*SummaryFooter[^}]*\} from "\.\/chat\/SummaryFooter\.js";/);
  // The splitAssistantMessage import.
  assert.match(src, /splitAssistantMessage/);
  // The Summary type import (for explicit reference).
  assert.match(src, /SummaryFooter,\s*\n\s*splitAssistantMessage,\s*\n\s*type Summary/);
});

// ----- 2. Scrollback: assistant turn renders SummaryFooter below body --

test("T-6-14: assistant turn renders SummaryFooter below the body", () => {
  const src = readFileSync(scrollbackPath, "utf-8");
  // The body is rendered via Markdown, then a SummaryFooter
  // mounted with `marginTop={1}` so it sits below.
  assert.match(src, /<Markdown text=\{lead\} \/>/);
  assert.match(src, /<SummaryFooter summary=\{summary\} \/>/);
  // The marginTop={1} wrapper around the SummaryFooter.
  assert.match(src, /<Box marginTop=\{1\}>\s*\n\s*<SummaryFooter/);
});

// ----- 3. Scrollback: tool / plan collapsed form exists ----------------

test("T-6-14: tool + plan turns render a collapsed form when effectiveCollapsed is true", () => {
  const src = readFileSync(scrollbackPath, "utf-8");
  // Tool case: ToolCard receives `collapsed={effectiveCollapsed}`.
  assert.match(src, /case "tool":/);
  assert.match(src, /<ToolCard[\s\S]*collapsed=\{effectiveCollapsed\}/);
  // Plan case: collapsed form renders a one-line summary.
  assert.match(src, /case "plan":/);
  assert.match(src, /effectiveCollapsed\) \{[\s\S]*?Tab to expand/);
});

// ----- 4. Scrollback: SummaryFooter is ALWAYS visible -----------------

test("T-6-14: SummaryFooter is always visible — not gated by effectiveCollapsed", () => {
  const src = readFileSync(scrollbackPath, "utf-8");
  // In the collapsed branch, the SummaryFooter is rendered
  // without a `visible={false}` guard. In the expanded branch,
  // it's also always there.
  // The default `visible` prop is true (see SummaryFooter
  // interface), so even an omitted `visible` keeps it shown.
  // We assert: every <SummaryFooter ...> in the file passes
  // either no `visible` prop, or an explicit `visible` set
  // such that the rendered card is visible.
  const matches = src.match(/<SummaryFooter\b[^>]*\/>/g) || [];
  assert.ok(matches.length >= 2, "expected at least 2 SummaryFooter mounts (collapsed + expanded)");
  for (const m of matches) {
    // If a `visible` prop is set, it must be true (literal `true`
    // or `{true}` — we accept both). If not set, default is true.
    if (m.includes("visible=")) {
      assert.match(m, /visible=\{(?:true|true\}|true)\}/);
    }
  }
});

// ----- 5. Scrollback: renderStopBanner still present (no regression) --

test("T-6-14: existing renderStopBanner is unchanged (no regression)", () => {
  const src = readFileSync(scrollbackPath, "utf-8");
  assert.match(src, /renderStopBanner/);
  assert.match(src, /Run stopped — loop detected/);
  assert.match(src, /Run stopped — max turns reached/);
  assert.match(src, /Run stopped — error/);
  // The permission card is still in place.
  assert.match(src, /PermissionCard/);
});

// ----- 6. tsc: Scrollback.tsx + new components compile cleanly --------

test("T-6-14: TypeScript compile of Scrollback.tsx + new components is clean", () => {
  const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
  const args = [
    "--outDir", join(root, "tmp-t6-14-tsc"), "--target", "ES2022", "--module", "ES2022",
    "--moduleResolution", "bundler", "--jsx", "react",
    "--esModuleInterop", "true", "--skipLibCheck", "true",
    "--rootDir", join(root, "src"),
  ];
  const tmp = join(root, "tmp-t6-14-tsc");
  if (existsSync(tmp)) {
    spawnSync(process.platform === "win32" ? "cmd" : "rm",
      process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp]);
  }
  const r = spawnSync(`"${tscBin}"`, [
    ...args,
    '"' + scrollbackPath + '"',
    '"' + summaryPath + '"',
    '"' + toolCardPath + '"',
    '"' + planListPath + '"',
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for T-6-14 Scrollback");
});
