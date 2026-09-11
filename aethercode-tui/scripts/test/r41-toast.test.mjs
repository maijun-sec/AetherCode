// R41: toast notifications.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

// ----- 1. Compile state.ts and import --------------------------------
const tmp = join(root, "tmp-r41-tsc");
const tscBin = join(root, "node_modules", ".bin", process.platform === "win32" ? "tsc.cmd" : "tsc");
{
  if (existsSync(tmp)) {
    spawnSync(process.platform === "win32" ? "cmd" : "rm",
      process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp]);
  }
  const tscArgs = [
    "--outDir", tmp, "--target", "ES2022", "--module", "ES2022",
    "--moduleResolution", "bundler", "--esModuleInterop", "true",
    "--skipLibCheck", "true", "--rootDir", join(root, "src"),
  ];
  const r = spawnSync(`"${tscBin}"`, [...tscArgs, `"${join(root, "src", "state.ts")}"`], {
    encoding: "utf-8", shell: true,
  });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
    process.exit(1);
  }
}
const stateMod = await import("file:///" + join(tmp, "state.js").replace(/\\/g, "/"));
const { reducer, INITIAL, nextToastId } = stateMod;

test("R41: INITIAL has toasts=[]", () => {
  assert.deepEqual(INITIAL.toasts, []);
});

test("R41: pushToast appends a toast with kind + text", () => {
  const s = reducer(INITIAL, { type: "pushToast", kind: "ok", text: "task done" });
  assert.equal(s.toasts.length, 1);
  assert.equal(s.toasts[0].kind, "ok");
  assert.equal(s.toasts[0].text, "task done");
  assert.ok(typeof s.toasts[0].id === "number");
  assert.ok(typeof s.toasts[0].createdAt === "number");
});

test("R41: pushToast caps at 8 toasts", () => {
  let s = INITIAL;
  for (let i = 0; i < 12; i++) {
    s = reducer(s, { type: "pushToast", kind: "info", text: `t${i}` });
  }
  assert.equal(s.toasts.length, 8);
  // The last 8 (i=4..11) are kept; t4..t11.
  assert.equal(s.toasts[0].text, "t4");
  assert.equal(s.toasts[7].text, "t11");
});

test("R41: trimToasts drops expired entries (older than 2s)", () => {
  let s = reducer(INITIAL, { type: "pushToast", kind: "info", text: "old" });
  // Manually rewrite createdAt to 5 seconds ago.
  s = { ...s, toasts: s.toasts.map((t) => ({ ...t, createdAt: t.createdAt - 5000 })) };
  s = reducer(s, { type: "trimToasts", now: Date.now() });
  assert.equal(s.toasts.length, 0);
});

test("R41: trimToasts keeps fresh entries (younger than 2s)", () => {
  let s = reducer(INITIAL, { type: "pushToast", kind: "info", text: "fresh" });
  s = reducer(s, { type: "trimToasts", now: Date.now() + 1000 });
  assert.equal(s.toasts.length, 1);
  assert.equal(s.toasts[0].text, "fresh");
});

test("R41: nextToastId returns incrementing ids", () => {
  const a = nextToastId();
  const b = nextToastId();
  assert.ok(b > a, `${b} should be > ${a}`);
});

// ----- 2. Source code assertions -------------------------------------

test("R41: Toast.tsx exists + exports ToastStack", () => {
  assert.ok(existsSync(join(root, "src", "components", "Toast.tsx")));
  const src = readFileSync(join(root, "src", "components", "Toast.tsx"), "utf-8");
  assert.match(src, /export const ToastStack/);
  assert.match(src, /export interface Toast/);
  assert.match(src, /liveToasts/);
});

test("R41: state.ts has toasts field + pushToast + trimToasts actions", () => {
  const state = readFileSync(join(root, "src", "state.ts"), "utf-8");
  assert.match(state, /toasts: Array</);
  assert.match(state, /pushToast/);
  assert.match(state, /trimToasts/);
  assert.match(state, /nextToastId/);
});

test("R41: tui.tsx has trimToasts tick + ToastStack render", () => {
  const tui = readFileSync(join(root, "src", "tui.tsx"), "utf-8");
  assert.match(tui, /import\s+\{[^}]*\bToastStack\b[^}]*\}\s+from\s+"\.\/components\/Toast\.js"/);
  assert.match(tui, /<ToastStack\s+toasts=\{state\.toasts\} \/>/);
  assert.match(tui, /trimToasts/);
  assert.match(tui, /setInterval.*trimToasts/s);
});

// ----- 3. Build smoke test -------------------------------------------

test("R41: TypeScript compile of Toast.tsx is clean", () => {
  const tmp2 = join(root, "tmp-r41-tsc2");
  if (existsSync(tmp2)) {
    spawnSync(process.platform === "win32" ? "cmd" : "rm",
      process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp2] : ["-rf", tmp2]);
  }
  const tscArgs = [
    "--outDir", tmp2, "--target", "ES2022", "--module", "ES2022",
    "--moduleResolution", "bundler", "--jsx", "react",
    "--esModuleInterop", "true", "--skipLibCheck", "true",
    "--rootDir", join(root, "src"),
  ];
  const r = spawnSync(`"${tscBin}"`, [
    ...tscArgs,
    '"' + join(root, "src", "components", "Toast.tsx") + '"',
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error("tsc failed:\n" + r.stdout + "\n" + r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for R41 Toast");
});
