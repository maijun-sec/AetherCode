// R388n — prove the feature-4 gate can GO RED.
//
// Lesson 1219: "a build that has not run to completion is not a build;
// BUILD SUCCESS must name WHICH build." The same applies to a gate: a
// gate that has never been seen red is not a gate.
//
// Each mutation below breaks ONE thing the gate claims to protect, and
// the gate MUST report a failure. If a mutation survives, the gate is
// blind to that defect and the assertion is vacuous.
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  readFileSync,
  writeFileSync,
  copyFileSync,
  mkdtempSync,
  rmSync,
  existsSync,
} from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { tmpdir } from "node:os";
import { execFileSync } from "node:child_process";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

const FILES = {
  comment: join(root, "src", "permissionComment.ts"),
  tui: join(root, "src", "tui.tsx"),
  line: join(root, "src", "line.ts"),
  card: join(root, "src", "components", "Scrollback.tsx"),
};

if (process.env.R388N_MUTATION_DEBUG) {
  console.error(`[mutation] here=${here}\n[mutation] root=${root}`);
  for (const [n, p] of Object.entries(FILES)) {
    console.error(`[mutation]   ${n} -> ${p} exists=${existsSync(p)}`);
  }
}

/** Snapshot every file, mutate one, run the gate, restore. */
function withMutation(file, mutate, run) {
  const backupDir = mkdtempSync(join(tmpdir(), "r388n-mut-"));
  const backups = new Map();
  for (const [name, path] of Object.entries(FILES)) {
    const dest = join(backupDir, name);
    copyFileSync(path, dest);
    backups.set(name, dest);
  }
  try {
    const src = readFileSync(file, "utf8");
    const mutated = mutate(src);
    assert.notEqual(mutated, src, "the mutation must actually change the file");
    writeFileSync(file, mutated, "utf8");
    // ★ The mutation MUST be on disk when the gate reads it. Assert it
    // here — a mutation test that silently mutated nothing is worse
    // than no mutation test, because it reports "the gate survived".
    const onDisk = readFileSync(file, "utf8");
    assert.notEqual(onDisk, src, "the mutation must be ON DISK before the gate runs");
    if (process.env.R388N_MUTATION_DEBUG) {
      console.error(
        `[mutation] file=${file}\n[mutation] on-disk contains 'm' key: ${onDisk.includes('key: "m"')}`,
      );
    }
    return run();
  } finally {
    for (const [name, dest] of backups) {
      copyFileSync(dest, FILES[name]);
    }
    rmSync(backupDir, { recursive: true, force: true });
  }
}

/** Run the gate and return whether it failed. */
function gateFails() {
  const gatePath = join(root, "scripts", "test", "r388n-auto-mode.test.mjs");
  try {
    // ★ `NODE_TEST_CONTEXT` MUST be cleared. ★
    //
    // A `node --test` parent sets `NODE_TEST_CONTEXT=child-v8` in the
    // environment, and a child that sees it runs in TEST-RUNNER MODE:
    // it reports its failures to the parent over a channel the parent
    // never reads, and **exits 0 even when assertions failed**.
    //
    // So a mutation test that spawns the gate inherits the flag, the
    // gate's failures go nowhere, and every mutation "survives" —
    // the mutation test reports the gate is blind when the gate is
    // perfectly fine.
    //
    // → **Lesson 1358 — a child spawned by a test runner does not report
    // its own exit code, unless you take the flag away.**
    const env = { ...process.env };
    delete env.NODE_TEST_CONTEXT;
    execFileSync(process.execPath, ["--test", gatePath], {
      cwd: root,
      stdio: "pipe",
      encoding: "utf8",
      env,
    });
    if (process.env.R388N_MUTATION_DEBUG) {
      console.error(`[mutation] gate EXITED 0 (passed) — gatePath=${gatePath}`);
    }
    return false;
  } catch (e) {
    // A non-zero exit means at least one assertion failed.
    if (process.env.R388N_MUTATION_DEBUG) {
      const out = `${e.stdout ?? ""}\n${e.stderr ?? ""}`;
      console.error(`[mutation] gate FAILED as expected:\n${out.slice(-1500)}`);
    }
    return true;
  }
}

/**
 * Replace a WHITESPACE-TOLERANT pattern.
 *
 * <p>★ Lesson 1348/R376 — a formatter reflow can silently blind a
 * gate. ★ An anchor written with exact spaces and newlines stops
 * matching the moment prettier reflows the file, and the mutation
 * then changes NOTHING — which the mutation test reports as "the
 * mutation must actually change the file", a failure that looks like
 * a test bug rather than a blind gate.
 *
 * <p>So every anchor here is a REGEX with `\s+` where whitespace
 * varies, and the "did it change?" assertion below is the backstop:
 * if an anchor ever stops matching, the test says so loudly instead
 * of silently "passing" a mutation that never happened.
 */
function replaceLoose(src, pattern, replacement) {
  return src.replace(pattern, replacement);
}

test("R388n gate: removing the 'm' key from the registry turns it red", () => {
  const failed = withMutation(
    FILES.comment,
    (src) =>
      replaceLoose(
        src,
        /\{\s*key:\s*"m",\s*decision:\s*"allow",\s*label:\s*"allow and switch to auto mode",\s*mode:\s*MODE\.ACCEPT_TASK,\s*(?:number:\s*\d+,?\s*)?\},/,
        "",
      ),
    gateFails,
  );
  assert.ok(failed, "the gate must FAIL when the 'm' key is gone");
});

test("R388n gate: dropping the mode from the 'm' entry turns it red", () => {
  const failed = withMutation(
    FILES.comment,
    (src) => replaceLoose(src, /(key:\s*"m",[\s\S]{0,240}?)mode:\s*MODE\.ACCEPT_TASK,\s*/, "$1"),
    gateFails,
  );
  assert.ok(failed, "the gate must FAIL when the 'm' key carries no mode");
});

test("R388n gate: a hand-written 'm' branch in the Ink app turns it red", () => {
  // ★ THE DEFECT this round removes: the if-chain. Re-introducing it
  // (even a CORRECT one) must turn the gate red, because the chain is
  // a second list of the keys and the two can drift.
  const failed = withMutation(
    FILES.tui,
    (src) =>
      replaceLoose(
        src,
        /(\s*)const numericAlias = NUMBERED_DECISION_KEYS\.find\(\s*\(e\) => e\.number === Number\.parseInt\(input, 10\),\s*\);/,
        `$1if (input === "m" || input === "M") {
$1  replyPermission(client, ask, { decision: "allow", mode: MODE.ACCEPT_TASK }, dispatch);
$1  return;
$1}
$1const numericAlias = NUMBERED_DECISION_KEYS.find(
$1  (e) => e.number === Number.parseInt(input, 10),
$1);`,
      ),
    gateFails,
  );
  assert.ok(failed, "the gate must FAIL when a hand-written key branch comes back");
});

test("R388n gate: chaining setPermissionMode onto the reply turns it red", () => {
  const src = readFileSync(FILES.tui, "utf8");
  // Rewrite the reply into a chained form: the mode RPC moves INSIDE
  // the reply's promise chain.
  const chained = src.replace(
    /  void client\n    \.request\(\n      "permissionResponse",[\s\S]{0,400}?\n    \)\n    \.catch\(\(e: Error\) => \{\n      dispatch\(\{ type: "log", message: `permission reply failed: \$\{e\.message\}` \}\);\n    \}\);/,
    `  void client
    .request("permissionResponse", { requestId: ask.requestId, decision })
    .then(() => client.request("setPermissionMode", { mode: mode ?? MODE.DEFAULT }))
    .catch((e: Error) => {
      dispatch({ type: "log", message: \`permission reply failed: \${e.message}\` });
    });`,
  );
  assert.notEqual(chained, src, "the chain mutation must change the file");
  writeFileSync(FILES.tui, chained, "utf8");
  try {
    assert.ok(gateFails(), "the gate must FAIL when the mode change is chained onto the reply");
  } finally {
    writeFileSync(FILES.tui, src, "utf8");
  }
});

test("R388n gate: sending setPermissionMode unconditionally turns it red", () => {
  const failed = withMutation(
    FILES.tui,
    (src) =>
      src.replace(
        '  if (mode) {\n    void client\n      .request("setPermissionMode", { mode })',
        '  if (true) {\n    void client\n      .request("setPermissionMode", { mode: mode ?? MODE.DEFAULT })',
      ),
    gateFails,
  );
  assert.ok(failed, "the gate must FAIL when the mode change is not guarded by the mode");
});

test("R388n gate: removing the [M] row from the card turns it red", () => {
  const failed = withMutation(FILES.card, (src) => src.replace(/\[M\]/, "[X]"), gateFails);
  assert.ok(failed, "the gate must FAIL when the card stops offering 'M'");
});

test("R388n gate: the label lookup by decision ALONE turns it red", () => {
  // ★ THE DEFECT ★ `a` and `m` both produce "allow" — a lookup keyed
  // on the decision finds the wrong one, and the comment box header
  // describes a DIFFERENT answer from the one about to be sent.
  const failed = withMutation(
    FILES.comment,
    (src) =>
      replaceLoose(
        src,
        /const entry = DECISION_KEYS\.find\(\s*\(e\) =>\s*e\.decision === state\.armed &&\s*e\.scope === state\.scope &&\s*e\.mode === state\.mode,\s*\);/,
        "const entry = DECISION_KEYS.find((e) => e.decision === state.armed);",
      ),
    gateFails,
  );
  assert.ok(failed, "the gate must FAIL when the label lookup ignores the mode");
});

test("R388n gate: a mode field on the wire but never applied turns it red", () => {
  // ★ The mutation must target the PERMISSION-REPLY call, not the
  // `/mode` picker's call. There are TWO `setPermissionMode` calls in
  // line.ts, and `String.replace` without the `g` flag replaces the
  // FIRST — which is the picker's, leaving the reply's intact. The
  // mutation then changed nothing the gate cares about, and it
  // "survived" for the wrong reason.
  //
  // → **Lesson 1319 — a needle that is not unique lands on the wrong
  // occurrence and pretends to have tested something.**
  const src = readFileSync(FILES.line, "utf8");
  const replyIdx = src.indexOf('"permissionResponse"');
  assert.ok(replyIdx > 0, "the line renderer must reply to the permission");
  const tail = src.slice(replyIdx);
  assert.match(
    tail,
    /client\.request\(\s*"setPermissionMode"/,
    "the permission reply must issue setPermissionMode",
  );
  const mutated =
    src.slice(0, replyIdx) +
    tail.replace(
      /client\.request\(\s*"setPermissionMode"/,
      'client.request("setPermissionModeXXX"',
    );
  assert.notEqual(mutated, src, "the mutation must change the file");
  writeFileSync(FILES.line, mutated, "utf8");
  try {
    assert.ok(gateFails(), "the gate must FAIL when the line renderer stops applying the mode");
  } finally {
    writeFileSync(FILES.line, src, "utf8");
  }
});

test("R388n gate: chaining the LINE mode RPC onto the reply turns it red", () => {
  const src = readFileSync(FILES.line, "utf8");
  // Rewrite the reply into a chained form: the mode RPC moves INSIDE
  // the reply's promise chain.
  const chained = src.replace(
    /await client\.request\(\s*"permissionResponse",[\s\S]{0,800}?\n\s*\);/,
    `await client
            .request("permissionResponse", { requestId, decision: "allow" })
            .then(() => client.request("setPermissionMode", { mode: MODE.ACCEPT_TASK }));`,
  );
  assert.notEqual(chained, src, "the line-chain mutation must change the file");
  writeFileSync(FILES.line, chained, "utf8");
  try {
    assert.ok(gateFails(), "the gate must FAIL when the line mode RPC is chained onto the reply");
  } finally {
    writeFileSync(FILES.line, src, "utf8");
  }
});
