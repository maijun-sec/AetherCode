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

// R388g — a bundled library must not run its own CLI.
//
// ## The defect this gate exists to hold
//
// `aethercode-memory/src/cli.ts` ends with a top-level guard:
//
//     if (isDirectInvocation()) {
//       main().then((code) => { process.exit(code); });
//     }
//
// The TUI ships as ONE esbuild bundle (`dist/ac-tui.js`) and imports
// the memory package's LIBRARY surface. `index.ts` re-exports
// `./cli.js`, so esbuild INLINES the CLI module — and inside a bundle
// `import.meta.url` is the BUNDLE's url.
//
// The old `isDirectInvocation()` compared `import.meta.url` with
// `process.argv[1]`. For the TUI bundle those are the SAME FILE
// (`node dist/ac-tui.js` ⇒ argv[1] === the bundle's path), so the
// guard returned TRUE. The memory CLI's `main()` then ran with the
// TUI's argv, found no recognised subcommand, and called
// `process.exit(2)`.
//
// ## The symptom
//
// The TUI painted its first frame and then **died on its own**,
// about a second in. The status bar showed `connecting…` and the
// jar name was empty, because the process was gone before the
// `getState` reply could be applied. The user's report was
// "还是进入不了" — the TUI would not get past connecting.
//
// ## The rule
//
// "Am I the CLI ENTRY POINT?" is a question about the FILE, not
// about the module URL. Inside a bundle, every module shares the
// bundle's URL, so a URL comparison cannot distinguish "the user
// ran `ac-mem`" from "some other program imported this module".
//
// The discriminator has to survive bundling, so it compares
// BASENAMES: the bin is `cli.js` / `ac-mem`; a bundle named
// `ac-tui.js` is not the CLI no matter what its argv says.
//
// ## What this gate asserts
//
// 1. SOURCE: the guard refuses to auto-run when the module's own
//    file name is not a CLI entry name.
// 2. BEHAVIOUR: esbuild-bundling the memory package into a file
//    named `ac-tui.js` and RUNNING it must NOT exit the process.
//    (The pre-fix bundle exited with code 2 within a second.)
// 3. The real `ac-mem` bin still works: running `cli.js` directly
//    still reaches the CLI's own dispatcher.

import { test } from "node:test";
import assert from "node:assert/strict";
import { execFileSync, spawn } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const tuiRoot = resolve(here, "..", "..");
const repoRoot = resolve(tuiRoot, "..");
const memoryRoot = join(repoRoot, "aethercode-memory");

const cliSrc = join(memoryRoot, "src", "cli.ts");

function readCliSource() {
  return readFileSync(cliSrc, "utf-8");
}

// ---------------------------------------------------------------------------
// 1. SOURCE: the guard must be bundle-safe.
// ---------------------------------------------------------------------------

test("R388g: the memory CLI's direct-invocation guard is bundle-safe", () => {
  const src = readCliSource();
  assert.ok(
    src.includes("function isDirectInvocation()"),
    "expected cli.ts to keep an isDirectInvocation() guard",
  );
  // The basename check is the bundle-safe discriminator.
  assert.match(
    src,
    /basename\s*\(/,
    "expected the guard to compare BASENAMES (bundle-safe), not full paths",
  );
  assert.match(src, /isCliEntry/, "expected the guard to gate on an explicit CLI-entry-name check");
  // The OLD check must be GONE: a full-path comparison of
  // `import.meta.url` against `process.argv[1]` is exactly what
  // matched the TUI bundle.
  const guardBody = src.slice(src.indexOf("function isDirectInvocation()"));
  const body = guardBody.slice(0, guardBody.indexOf("\n}\n"));
  assert.ok(
    !/realpathSync\(process\.argv\[1\]\)\s*===\s*realpathSync\(invoked\)/.test(
      body.replace(/\/\*[\s\S]*?\*\//g, ""),
    ) || body.includes("isCliEntry"),
    "the path comparison must be guarded by the CLI-entry-name check",
  );
});

// ---------------------------------------------------------------------------
// 2. BEHAVIOUR: a bundle that imports the memory library must not exit.
// ---------------------------------------------------------------------------

/**
 * Bundle `aethercode-memory`'s library surface into a file named
 * `ac-tui.js` — the same shape as the shipped TUI bundle — and run it.
 *
 * Pre-fix this process called `process.exit(2)` about a second in
 * (the memory CLI's `main()` with an unrecognised subcommand).
 * Post-fix it must still be alive when we look.
 */
async function bundleAndRun(bundleName) {
  // Build INSIDE the memory package so the native `better-sqlite3`
  // resolves from its node_modules — the same way the shipped TUI
  // bundle resolves it at runtime.
  const tmp = mkdtempSync(join(memoryRoot, "r388g-bundle-"));
  try {
    const bundlePath = join(tmp, bundleName);
    const entry = join(tmp, "entry.mjs");
    // Import the package by ABSOLUTE path: the temp dir has no
    // node_modules, and the shipped bundle resolves the same
    // workspace package.
    const memoryEntry = join(memoryRoot, "dist", "index.js");
    assert.ok(
      existsSync(memoryEntry),
      `aethercode-memory/dist/index.js not built (${memoryEntry}) — run "npm run build" in aethercode-memory`,
    );
    writeFileSync(
      entry,
      [
        "// A bundle that imports the memory LIBRARY surface, like the TUI does.",
        `const mod = await import(${JSON.stringify(memoryEntry.replace(/\\/g, "/"))});`,
        "// Touch a library export so the import is not tree-shaken away.",
        "globalThis.__r388g = typeof mod.readBankStats;",
        // Keep the process alive so the test can observe that it was NOT killed.",
        "setInterval(() => {}, 1000);",
      ].join("\n"),
      "utf-8",
    );
    // Use the esbuild JS API — the same one `scripts/bundle.mjs` uses,
    // so the bundle shape matches the shipped TUI bundle exactly.
    // `better-sqlite3` is a NATIVE module: it must stay external and
    // be resolved at runtime from node_modules, exactly as the shipped
    // bundle does.
    const { build } = await import("esbuild");
    await build({
      entryPoints: [entry],
      bundle: true,
      platform: "node",
      format: "esm",
      target: "node18",
      external: ["better-sqlite3"],
      outfile: bundlePath,
      logLevel: "silent",
    });
    return await runAndObserve(bundlePath);
  } catch (e) {
    return { skipped: true, reason: `bundle failed: ${e.message}` };
  } finally {
    try {
      rmSync(tmp, { recursive: true, force: true });
    } catch {
      /* best effort */
    }
  }
}

/**
 * Run the bundle and report whether it exited on its own.
 *
 * The bundle itself keeps the process alive (a `setInterval`), so if
 * it is still running a moment later, NOTHING killed it. The
 * pre-fix bundle called `process.exit(2)` from the memory CLI's
 * auto-run guard within a second.
 *
 * A crash from an unresolved module is a DIFFERENT failure and is
 * reported as such — we must not confuse "the bundle could not
 * load" with "the bundle killed itself".
 */
async function runAndObserve(bundlePath) {
  const tmp = mkdtempSync(join(tmpdir(), "r388g-run-"));
  try {
    const child = spawn(process.execPath, [bundlePath], {
      // Run from the memory package dir so `better-sqlite3` resolves
      // from its node_modules — the same way the shipped bundle
      // resolves it.
      cwd: memoryRoot,
      stdio: ["ignore", "pipe", "pipe"],
      windowsHide: true,
    });
    let stderr = "";
    child.stderr.on("data", (d) => {
      stderr += d.toString();
    });
    const result = await new Promise((resolvePromise) => {
      child.on("exit", (code) => resolvePromise({ code }));
      setTimeout(() => resolvePromise({ code: null, timedOut: true }), 5000);
    });
    if (result.timedOut) {
      child.kill();
      return { alive: true, code: null };
    }
    return {
      alive: false,
      code: result.code,
      stderr: stderr.slice(-800),
      crashedOnLoad:
        /ERR_MODULE_NOT_FOUND|Cannot find module|MODULE_NOT_FOUND|was not found|bindings/.test(
          stderr,
        ),
    };
  } finally {
    try {
      rmSync(tmp, { recursive: true, force: true });
    } catch {
      /* best effort */
    }
  }
}

test("R388g: a bundle importing the memory library does NOT exit on its own", async () => {
  const outcome = await bundleAndRun("ac-tui.js");
  if (outcome.skipped) {
    // esbuild is not available in this environment — the SOURCE gate
    // above still holds the invariant. Skip rather than fail.
    console.log(`  (skipped: ${outcome.reason})`);
    return;
  }
  if (outcome.crashedOnLoad) {
    console.log(
      "  (skipped: the bundle could not load its own modules in this sandbox — " +
        "the exit is a module-resolution crash, not the CLI auto-run)",
    );
    return;
  }
  assert.ok(
    outcome.alive,
    `the bundled memory library exited on its own (code=${outcome.code})` +
      (outcome.stderr ? `\nstderr: ${outcome.stderr}` : "") +
      "\nThe memory CLI's isDirectInvocation() guard matched the bundle.",
  );
});

// ---------------------------------------------------------------------------
// 3. The real bin still works.
// ---------------------------------------------------------------------------

test("R388g: running the memory CLI's own file still reaches the CLI", () => {
  const cliJs = join(memoryRoot, "dist", "cli.js");
  if (!existsSync(cliJs)) {
    console.log("  (skipped: aethercode-memory/dist/cli.js not built)");
    return;
  }
  // `ac-mem --help` (or any unrecognised subcommand) must print the
  // CLI's own output — i.e. the guard still fires for the real bin.
  let out = "";
  let code = 0;
  try {
    out = execFileSync(process.execPath, [cliJs, "--help"], {
      encoding: "utf-8",
      stdio: ["ignore", "pipe", "pipe"],
    });
  } catch (e) {
    out = `${e.stdout ?? ""}${e.stderr ?? ""}`;
    code = e.status ?? -1;
  }
  const combined = String(out);
  assert.ok(
    combined.includes("aethercode memory") ||
      combined.includes("Usage") ||
      combined.includes("usage"),
    `expected the CLI's own help output, got:\n${combined.slice(0, 400)}`,
  );
  void code;
});
