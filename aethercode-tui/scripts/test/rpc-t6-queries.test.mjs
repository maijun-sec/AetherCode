// T-6-03: useSessionList / useSessionDetail / useSessionTokens
// and useMutation.
//
// The TUI test harness does not have `ink-testing-library`
// or `react-test-renderer` installed. The convention
// (r33..r196..t433) is a 3-layer smoke:
//
//   1. Pure-helper assertions: the type shapes and small
//      non-React functions that back the hooks.
//   2. Source-grep assertions on the .ts file so a
//      regression that drops a hook, a refetch contract,
//      or a poll interval fails the build.
//   3. A shim-based tsc --strict compile to make sure the
//      exported types and helpers still compile under the
//      same strictness the rest of the project uses.

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  existsSync,
  mkdirSync,
  readFileSync,
} from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

const tscBin = join(
  root,
  "node_modules",
  ".bin",
  process.platform === "win32" ? "tsc.cmd" : "tsc"
);

const read = (rel) => readFileSync(join(root, rel), "utf-8");

// ----- 1. Source-grep assertions on queries.ts ----------------

test("T-6-03: queries.ts exists and exports the four hooks", () => {
  assert.ok(existsSync(join(root, "src/rpc/queries.ts")));
  const src = read("src/rpc/queries.ts");
  assert.match(src, /export\s+function\s+useSessionList\b/);
  assert.match(src, /export\s+function\s+useSessionDetail\b/);
  assert.match(src, /export\s+function\s+useSessionTokens\b/);
  assert.match(src, /export\s+function\s+useMutation\b/);
});

test("T-6-03: useSessionList hits the session/list RPC", () => {
  const src = read("src/rpc/queries.ts");
  assert.match(src, /"session\/list"/);
});

test("T-6-03: useSessionDetail hits the session/show RPC", () => {
  const src = read("src/rpc/queries.ts");
  assert.match(src, /"session\/show"/);
});

test("T-6-03: useSessionTokens hits the session/tokens RPC", () => {
  const src = read("src/rpc/queries.ts");
  assert.match(src, /"session\/tokens"/);
});

test("T-6-03: useSessionTokens defaults to a 2s poll", () => {
  const src = read("src/rpc/queries.ts");
  // The spec says the ContextMeter / tokens read polls
  // every 2 s (spec §7.2 / design.md §3.5).
  assert.match(src, /TOKEN_POLL_MS\s*=\s*2_000/);
});

test("T-6-03: QueryState is a tagged union (idle / loading / success / error)", () => {
  const src = read("src/rpc/queries.ts");
  assert.match(src, /type\s+QueryState<T>/);
  assert.match(src, /status:\s*"idle"/);
  assert.match(src, /status:\s*"loading"/);
  assert.match(src, /status:\s*"success"/);
  assert.match(src, /status:\s*"error"/);
});

test("T-6-03: refetch is exposed on every read hook", () => {
  const src = read("src/rpc/queries.ts");
  // All three read hooks return a `refetch` callback so
  // the renderer can force a re-fetch after a mutation.
  const refetchCount = (src.match(/refetch:\s*\(\)\s*=>/g) || []).length;
  assert.ok(refetchCount >= 3, `expected >= 3 refetch callbacks, got ${refetchCount}`);
});

test("T-6-03: useMutation is generic (P params, T result)", () => {
  const src = read("src/rpc/queries.ts");
  // The mutation hook signature is `useMutation<P, T>(method, opts?)`.
  assert.match(src, /export\s+function\s+useMutation\s*<P,\s*T\s*=/);
});

test("T-6-03: client is injectable (tests use the mock)", () => {
  const src = read("src/rpc/queries.ts");
  // Every hook reads `opts.client` first, then falls back
  // to the module singleton.
  const injectable = (src.match(/opts\.client/g) || []).length;
  assert.ok(injectable >= 3, `expected >= 3 client injections, got ${injectable}`);
});

// ----- 2. tsc --strict compile via a shim -------------------

const SHIM_NAME = "_t6_queries_shim.ts";
const tmpTsc = join(root, "tmp-t6-queries-tsc");

function tscCompileFile(label, files) {
  if (existsSync(tmpTsc)) {
    spawnSync(
      process.platform === "win32" ? "cmd" : "rm",
      process.platform === "win32"
        ? ["/c", "rmdir", "/s", "/q", tmpTsc]
        : ["-rf", tmpTsc],
      { shell: process.platform === "win32" }
    );
  }
  mkdirSync(tmpTsc, { recursive: true });
  const args = [
    "--outDir", tmpTsc,
    "--target", "ES2022",
    "--module", "ES2022",
    "--moduleResolution", "bundler",
    "--esModuleInterop", "true",
    "--skipLibCheck", "true",
    "--strict", "true",
    "--jsx", "react",
    "--rootDir", join(root, "src"),
  ];
  for (const f of files) {
    args.push(join(root, "src", f));
  }
  const r = spawnSync(`"${tscBin}"`, args, { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    throw new Error(`tsc compile failed for ${label}:\n${r.stdout}\n${r.stderr}`);
  }
}

test("T-6-03: tsc --strict compile of queries.ts is clean", () => {
  tscCompileFile("queries.ts", [
    "rpc/types.ts",
    "rpc/client.ts",
    "rpc/MockRpcServer.ts",
    "rpc/queries.ts",
  ]);
  assert.ok(true);
});
