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

// R46: Welcome v2.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { loadTsModule } from "./load-ts-module.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// R378: the welcome screen's shortcut grid is now the registry's
// `welcome` entries, because the grid used to be a hand-written copy
// of a truth — and it had already drifted ("Ctrl-T — theme picker",
// while the handler binds Ctrl-T to re-open the welcome screen).
//
// The PROPERTY is unchanged; the data moved. A pin that follows its
// data is still a pin.
const kbMod = await loadTsModule(root, "src/keybindings.ts");
const registryChords = new Set(kbMod.registryChords());

test("R46: the welcome shows a shortcuts grid with at least 5 entries", () => {
  const src = readFileSync(join(root, "src", "components", "Welcome.tsx"), "utf-8");
  assert.match(src, /const SHORTCUTS/);
  // R378: the rows come from the one registry, so the grid cannot
  // disagree with the key handler.
  assert.match(src, /keybindings\.js/);
  const entries = kbMod.welcomeBindings().length;
  assert.ok(entries >= 5, `expected >=5 welcome shortcuts, got ${entries}`);
});

test("R46: Welcome.tsx shows the model, session, cwd chips", () => {
  const src = readFileSync(join(root, "src", "components", "Welcome.tsx"), "utf-8");
  assert.match(src, /icon\.model/);
  assert.match(src, /icon\.id/);
  assert.match(src, /icon\.path/);
});

test("R46: the welcome mentions the key shortcuts (Tab, Ctrl-B, Ctrl-F, Ctrl-?)", () => {
  // R378: the labels live in the registry now — the pin follows them.
  for (const chord of ["tab", "ctrl+b", "ctrl+f", "ctrl+?"]) {
    assert.ok(
      registryChords.has(chord),
      `the welcome no longer mentions ${chord} — R46 pinned this chord`,
    );
  }
});

test("R46: Welcome.tsx still has the round border + brand color (R31 contract preserved)", () => {
  const src = readFileSync(join(root, "src", "components", "Welcome.tsx"), "utf-8");
  assert.match(src, /borderStyle="round"/);
  assert.match(src, /borderColor=\{t\.brand\}/);
});

// ----- Build smoke test ---------------------------------------------

test("R46: TypeScript compile of Welcome.tsx is clean", () => {
  const tscBin = join(
    root,
    "node_modules",
    ".bin",
    process.platform === "win32" ? "tsc.cmd" : "tsc",
  );
  const tmp = join(root, "tmp-r46-tsc");
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
    [...tscArgs, '"' + join(root, "src", "components", "Welcome.tsx") + '"'],
    { encoding: "utf-8", shell: true },
  );
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for R46 Welcome");
});
