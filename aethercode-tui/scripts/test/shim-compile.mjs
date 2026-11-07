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

// R388d — the shim-compile helper.
//
// ## The bug this exists to fix
//
// Fifteen of the TUI's source-pin gates compile a small "shim"
// module so they can import a pure helper and RUN it. The shim is
// a one-line re-export, and it has to sit NEXT TO the module it
// re-exports (the import path is relative).
//
// So each of those gates did this:
//
//     const shimPath = join(root, "src", "components", "chat", "_t6_13_helpers.ts");
//     writeFileSync(shimPath, shim, "utf-8");
//     ...compile shimPath with --rootDir join(root, "src")...
//
// `shimPath` points into `src/` — a TRACKED directory. The gate
// OVERWRITES a tracked source file with a generated one-liner that
// has no licence header, and never restores it.
//
// The symptom was that every run of the TUI suite silently deleted
// the MIT header from seven tracked files, and `git status` showed
// them as modified. Two people (me, twice) "fixed" it by running
// `git checkout -- <files>` — and it came back on the next suite
// run, because the CAUSE is the suite itself.
//
// The `aethercode-compact/tmp-r194-e2e` directory was the same
// shape: `r194-end-to-end.test.mjs` compiles aethercode-compact's
// TypeScript into that path, deletes it first, and that path is
// TRACKED in git. So the suite deleted 13 tracked files on every
// run.
//
// ## The rule
//
// ★ A TEST MUST NEVER WRITE INTO A CHECKED-IN DIRECTORY. ★
//
// Everything a gate generates goes to a TEMP directory. The temp
// directory MIRRORS the source layout, so the relative import in
// the shim still resolves, and `--rootDir` points at the mirror.
//
// ## Why a MIRROR and not just "the temp dir"
//
// The shim says `from "./SummaryFooter.js"`. If the shim lives in
// the temp dir alone, that relative path resolves to nothing. So
// the helper copies the real source tree into the mirror first, and
// the shim is written INTO the mirror. The compile then produces
// the same output layout the gate expects
// (`<tmp>/out/components/chat/_t6_13_helpers.js`).
//
// ## The judgement this file replaces
//
// Before this helper, each of the fifteen gates hand-rolled its
// own compile block, and the ones that differed were the ones that
// got it wrong. One copy of the compile step, used by all of them.

import { cpSync, existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join, relative, sep } from "node:path";

/**
 * The TypeScript flags every shim-compile uses.
 *
 * <p>One definition: the fifteen gates had thirteen variants, and a
 * flag that one of them forgot is a compile error in ONE gate and
 * not in the others — which is how a suite ends up with fifteen
 * subtly different compilers.
 */
export const SHIM_TSC_ARGS = [
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
];

/**
 * Compile a generated shim, and return the compiled module's path.
 *
 * @param {object} o
 * @param {string} o.root    the TUI package root (the one with `src/`)
 * @param {string} o.tmp     a scratch directory (deleted and recreated)
 * @param {string} o.shimRel the shim's path RELATIVE to `src/`, e.g.
 *                            `components/chat/_t6_13_helpers.ts`
 * @param {string} o.body    the shim's source text
 * @param {string[]} [o.extraArgs] extra tsc flags
 * @returns {string} the absolute path of the COMPILED module
 */
export function compileShim(o) {
  const { root, tmp, shimRel, body } = o;

  if (existsSync(tmp)) {
    spawnSync(
      process.platform === "win32" ? "cmd" : "rm",
      process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp],
      { stdio: "ignore" },
    );
  }
  mkdirSync(tmp, { recursive: true });

  // The MIRROR: a copy of `src/` under the temp dir, so the shim's
  // relative import still resolves. Nothing is written back into
  // the checked-in tree.
  const mirrorSrc = join(tmp, "src");
  cpSync(join(root, "src"), mirrorSrc, { recursive: true });

  const shimPath = join(mirrorSrc, shimRel);
  mkdirSync(dirname(shimPath), { recursive: true });
  writeFileSync(shimPath, body, "utf-8");

  const outDir = join(tmp, "out");
  const tscBin = join(
    root,
    "node_modules",
    ".bin",
    process.platform === "win32" ? "tsc.cmd" : "tsc",
  );
  const args = [
    "--outDir",
    outDir,
    ...SHIM_TSC_ARGS,
    ...(o.extraArgs ?? []),
    "--rootDir",
    mirrorSrc,
    shimPath,
  ];
  const r = spawnSync(
    `"${tscBin}"`,
    args.map((a) => `"${a}"`),
    {
      encoding: "utf-8",
      shell: true,
    },
  );
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
    process.exit(1);
  }

  // The compiled module sits where the old hand-rolled compiles put
  // it: `<tmp>/out/<shimRel minus the .ts>`.
  const compiled = join(outDir, shimRel.replace(/\.ts$/, ".js"));
  if (!existsSync(compiled)) {
    console.error(`compileShim: expected ${compiled} to exist`);
    process.exit(1);
  }
  return compiled;
}

/**
 * Import a compiled module by absolute path.
 *
 * @param {string} compiledPath
 * @returns {Promise<any>}
 */
export function importCompiled(compiledPath) {
  return import("file:///" + compiledPath.replace(/\\/g, "/"));
}

/**
 * The path of a module RELATIVE to a root, with forward slashes.
 *
 * @param {string} root
 * @param {string} abs
 * @returns {string}
 */
export function relFrom(root, abs) {
  return relative(root, abs).split(sep).join("/");
}

/**
 * The MIT header every first-party source file carries.
 *
 * <p>Read from a real file rather than written out here, so this
 * helper can never drift from the project's own header.
 */
export function licenceHeader(root) {
  const headerFile = join(root, "src", "components", "CwdSwitcher.tsx");
  const src = readFileSync(headerFile, "utf-8");
  const end = src.indexOf("\n\n");
  return end === -1 ? "" : src.slice(0, end);
}
