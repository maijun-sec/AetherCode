// Copyright (c) 2026 maijun

// Licensed under the MIT License.
// See the LICENSE file in the project root for the full license text.

// R49: categorized help overlay.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { loadTsModule } from "./load-ts-module.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// R378: the shortcut rows moved out of the overlay and into the ONE
// registry (`src/keybindings.ts`), because the overlay, the welcome
// screen and `--help` were three hand-written copies of the same list
// and they had already contradicted each other AND the key handler.
//
// The PROPERTIES below are unchanged. Only the file they are read from
// moved — and a pin that follows its data is still a pin; a pin that
// is deleted is not.
const kbMod = await loadTsModule(root, "src/keybindings.ts");
const registryChords = new Set(kbMod.registryChords());

test("R49: HelpOverlay.tsx has 4 sections (session/display/editing/slash)", () => {
  const src = readFileSync(join(root, "src", "components", "HelpOverlay.tsx"), "utf-8");
  assert.match(src, /SESSION_SHORTCUTS/);
  assert.match(src, /DISPLAY_SHORTCUTS/);
  assert.match(src, /EDITING_SHORTCUTS/);
  assert.match(src, /SLASH_GROUPS/);
});

test("R49: the help mentions the new R37-R48 shortcuts", () => {
  // R378: the rows live in the registry now. The property is the same
  // one R49 was protecting: the help still offers these chords.
  for (const chord of ["ctrl+b", "ctrl+f", "ctrl+p", "tab"]) {
    assert.ok(
      registryChords.has(chord),
      `the help no longer offers ${chord} — R49 pinned this chord and it must not go`,
    );
  }
});

test("R49: the help's command list includes the new commands", () => {
  // R378: the overlay's own list is now DERIVED (the panel commands),
  // and the complete catalogue is one keystroke away in `/help`. The
  // property R49 protected — "the user can find /theme, /layout and
  // /budget" — is therefore checked where those commands are listed
  // now, plus the overlay's duty to point at `/help`.
  const commands = readFileSync(join(root, "src", "commands.ts"), "utf-8");
  for (const name of ["theme", "layout", "budget"]) {
    assert.match(
      commands,
      new RegExp(`"${name}"`),
      `/${name} is no longer listed by the help — R49 pinned this command`,
    );
  }
  const overlay = readFileSync(join(root, "src", "components", "HelpOverlay.tsx"), "utf-8");
  assert.match(overlay, /\/help/, "the overlay must point the user at /help for the full list");
});

test("R49: HelpOverlay keeps the R31 contract (double border + brand color)", () => {
  const src = readFileSync(join(root, "src", "components", "HelpOverlay.tsx"), "utf-8");
  assert.match(src, /borderStyle="double"/);
  assert.match(src, /borderColor=\{t\.brand\}/);
});

// ----- Build smoke test ---------------------------------------------

test("R49: TypeScript compile of HelpOverlay.tsx is clean", () => {
  const tscBin = join(
    root,
    "node_modules",
    ".bin",
    process.platform === "win32" ? "tsc.cmd" : "tsc",
  );
  const tmp = join(root, "tmp-r49-tsc");
  if (existsSync(tmp)) {
    spawnSync(
      process.platform === "win32" ? "cmd" : "rm",
      process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp],
    );
  }
  const tscArgs = [
    "--outDir",
    tmp,
    "--target",
    "ES2022",
    "--module",
    "ES2022",
    "--moduleResolution",
    "bundler",
    "--jsx",
    "react",
    "--esModuleInterop",
    "true",
    "--skipLibCheck",
    "true",
    "--rootDir",
    join(root, "src"),
  ];
  const r = spawnSync(
    `"${tscBin}"`,
    [...tscArgs, '"' + join(root, "src", "components", "HelpOverlay.tsx") + '"'],
    { encoding: "utf-8", shell: true },
  );
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for R49 HelpOverlay");
});
