#!/usr/bin/env node
/**
 * Bundle the TypeScript sources into a single self-contained
 * ESM file (dist/ac-tui.js). The bundle includes Ink, React,
 * and all transitive deps. The result has no node_modules
 * requirement — the user can copy dist/ac-tui.js anywhere
 * alongside a aethercode-*.jar and run it with `node`.
 *
 * R31 update: also copy the bundle to
 *   ../dist/ac-tui/ac-tui.js
 * so the TUI ships next to the Java jar for distribution.
 */

import { build } from "esbuild";
import { existsSync, mkdirSync, writeFileSync, copyFileSync, statSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(__dirname, "..");

// Pass --target=npm to skip the distribution copy (when building
// the npm package, you only want the local dist/ output).
const args = process.argv.slice(2);
const isNpm = args.includes("--target=npm");

if (!existsSync("dist")) mkdirSync("dist", { recursive: true });

// Provide stubs for modules we want to replace with empty
// implementations. The stub has its own package.json so
// esbuild treats it as a self-contained module.
const stubDir = "dist/.stubs";
if (!existsSync(stubDir)) mkdirSync(stubDir, { recursive: true });
writeFileSync(`${stubDir}/react-devtools-core.js`, "export default {};\n");
writeFileSync(`${stubDir}/package.json`, `{
  "name": "react-devtools-core",
  "main": "./react-devtools-core.js"
}\n`);
// Some dependencies do `require("assert")` which ESM doesn't
// expose by default. We point the alias at `node:assert` via
// a synchronous module — esbuild's __require polyfill will
// then handle the call. We use a self-contained .cjs file.
writeFileSync(`${stubDir}/assert.cjs`, `module.exports = require("node:assert");\n`);
writeFileSync(`${stubDir}/assert.js`, `import real from "node:assert"; export default real;\n`);
writeFileSync(`${stubDir}/assert-package.json`, `{
  "name": "assert",
  "main": "./assert.cjs"
}\n`);

await build({
  entryPoints: ["src/ac-tui.ts"],
  bundle: true,
  platform: "node",
  target: "node18",
  // ESM is the modern format. The `assert` alias below handles
  // the only common incompatibility (CJS `require("assert")`
  // from one of the transitive deps). The `createRequire`
  // banner shim provides a `require` symbol to esbuild's
  // generated __require polyfill — without it, the bundle
  // throws "Dynamic require of X is not supported" when run
  // from a pure-ESM context.
  format: "esm",
  outfile: "dist/ac-tui.js",
  alias: {
    "react-devtools-core": "./dist/.stubs/react-devtools-core.js",
    "assert": "./dist/.stubs/assert.cjs",
  },
  banner: {
    js: [
      "import { createRequire as __ac_tui_createRequire } from 'node:module';",
      "import { fileURLToPath as __ac_tui_fileURLToPath } from 'node:url';",
      "import { dirname as __ac_tui_dirname } from 'node:path';",
      "const require = __ac_tui_createRequire(import.meta.url);",
      "const __filename = __ac_tui_fileURLToPath(import.meta.url);",
      "const __dirname = __ac_tui_dirname(__filename);",
    ].join("\n"),
  },
  footer: { js: "" },
  sourcemap: false,
  minify: false,
  logLevel: "info",
});

// Also write a small package.json into dist/ so the bundle
// can be `npm install`ed as a single file artifact.
writeFileSync("dist/package.json", JSON.stringify({
  name: "aethercode-tui",
  version: "0.2.1",
  description: "AetherCode TUI (bundled)",
  type: "module",
  bin: { "ac-tui": "./ac-tui.js" },
  main: "./ac-tui.js",
  license: "MIT",
}, null, 2));

const localDist = resolve(projectRoot, "dist/ac-tui.js");
const localSize = statSync(localDist).size;
console.log(`bundled -> dist/ac-tui.js (${(localSize / 1024).toFixed(1)} KB)`);

if (!isNpm) {
  // Copy to <repo>/aethercode/dist/ac-tui/ for distribution alongside
  // the jar. Layout: <repo>/aethercode-tui -> <repo>/aethercode, so
  // we walk two levels up from `aethercode-tui` (the projectRoot).
  // R88: was `resolve(projectRoot, "../dist/ac-tui")` which assumed
  // aethercode-tui was directly inside the repo root, but it's
  // actually a sibling of `aethercode/`. The previous expression
  // shipped the bundle to <repo>/dist/ac-tui/ (one level too high),
  // so the TUI launched via `java -jar aethercode.jar tui` loaded
  // the stale 21:12:39 bundle even after a fresh build. We now
  // resolve the sibling `aethercode` explicitly.
  const repoRoot = resolve(projectRoot, "..");
  const shipDir = resolve(repoRoot, "aethercode/dist/ac-tui");
  if (!existsSync(shipDir)) mkdirSync(shipDir, { recursive: true });
  copyFileSync(localDist, join(shipDir, "ac-tui.js"));
  const shipPath = join(shipDir, "ac-tui.js");
  const shipSize = statSync(shipPath).size;
  console.log(`shipped -> ${shipPath} (${(shipSize / 1024).toFixed(1)} KB)`);

  // Also copy README if present.
  const readmeSrc = resolve(projectRoot, "README.md");
  if (existsSync(readmeSrc)) {
    copyFileSync(readmeSrc, join(shipDir, "README.md"));
  }
}
