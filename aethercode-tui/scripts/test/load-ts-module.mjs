// Compile a TypeScript module out of `src/` and import the compiled
// value.
//
// Why not a regex re-read of the source? Because a gate that checks
// the TABLE would then be checking a SECOND copy of the table — the
// copy in the test file — and the two could disagree. R369 moved the
// table into `src/` precisely so there is one of it; this loader is
// how a gate gets at that one.
//
// Why not `import()` the .ts directly? Node cannot, and the npm gates
// compile with the file on the command line (so `tsconfig.json` is
// ignored). The flags below are the same ones the rest of the suite
// uses.

import { mkdirSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

/**
 * Compile `relPath` (relative to the package root) and import it.
 *
 * @param {string} root - the package root (the directory with `src/`)
 * @param {string} relPath - e.g. `src/rpcCapabilities.ts`
 * @returns {Promise<Record<string, unknown>>} the module's exports
 */
export async function loadTsModule(root, relPath) {
  const tmp = join(tmpdir(), `tsmod-${process.pid}-${relPath.replace(/[^\w]/g, "_")}`);
  mkdirSync(tmp, { recursive: true });
  const tscBin = join(
    root,
    "node_modules",
    ".bin",
    process.platform === "win32" ? "tsc.cmd" : "tsc",
  );
  const r = spawnSync(
    `"${tscBin}"`,
    [
      "--outDir",
      join(tmp, "out"),
      "--target",
      "ES2022",
      "--module",
      "ES2022",
      "--moduleResolution",
      "bundler",
      "--rootDir",
      join(root, "src"),
      `"${join(root, relPath)}"`,
    ],
    { encoding: "utf-8", shell: true },
  );
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
    process.exit(1);
  }
  const outFile = join(tmp, "out", relPath.replace(/^src\//, "").replace(/\.tsx?$/, ".js"));
  return await import(pathToFileURL(outFile).href);
}
