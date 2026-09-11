// R49: categorized help overlay.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

test("R49: HelpOverlay.tsx has 4 sections (session/display/editing/slash)", () => {
  const src = readFileSync(join(root, "src", "components", "HelpOverlay.tsx"), "utf-8");
  assert.match(src, /SESSION_SHORTCUTS/);
  assert.match(src, /DISPLAY_SHORTCUTS/);
  assert.match(src, /EDITING_SHORTCUTS/);
  assert.match(src, /SLASH_GROUPS/);
});

test("R49: HelpOverlay mentions the new R37-R48 shortcuts", () => {
  const src = readFileSync(join(root, "src", "components", "HelpOverlay.tsx"), "utf-8");
  // Each new round's shortcut should appear in the help.
  assert.match(src, /Ctrl-B/);
  assert.match(src, /Ctrl-F/);
  assert.match(src, /Ctrl-P/);
  assert.match(src, /Tab/);
});

test("R49: HelpOverlay slash-command groups include the new commands", () => {
  const src = readFileSync(join(root, "src", "components", "HelpOverlay.tsx"), "utf-8");
  assert.match(src, /\/theme/);
  assert.match(src, /\/layout/);
  assert.match(src, /\/budget/);
});

test("R49: HelpOverlay keeps the R31 contract (double border + brand color)", () => {
  const src = readFileSync(join(root, "src", "components", "HelpOverlay.tsx"), "utf-8");
  assert.match(src, /borderStyle="double"/);
  assert.match(src, /borderColor=\{t\.brand\}/);
});

// ----- Build smoke test ---------------------------------------------

test("R49: TypeScript compile of HelpOverlay.tsx is clean", () => {
  const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
  const tmp = join(root, "tmp-r49-tsc");
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
  const r = spawnSync(`"${tscBin}"`, [
    ...tscArgs,
    '"' + join(root, "src", "components", "HelpOverlay.tsx") + '"',
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for R49 HelpOverlay");
});
