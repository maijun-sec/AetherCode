// Docs structure sanity check.
//
// The R33+ docs-update workflow requires that the docs/ tree
// exists and is well-organised. This test catches accidental
// removal of the structure (e.g. someone deletes README.md and
// doesn't notice because the rest of the build still works).

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const tuiRoot = join(here, "..", "..");
const docsRoot = join(tuiRoot, "..", "docs");

test("docs/README.md exists and lists the structure", () => {
  const p = join(docsRoot, "README.md");
  assert.ok(existsSync(p), "docs/README.md missing");
  const src = readFileSync(p, "utf-8");
  assert.match(src, /user-guide/);
  assert.match(src, /dev-guide/);
  assert.match(src, /api/);
  assert.match(src, /changelog/);
});

test("docs/user-guide/ has the 6 user-facing files", () => {
  for (const f of ["getting-started.md", "tui-guide.md", "keybindings.md",
                   "slash-commands.md", "features.md", "themes.md"]) {
    const p = join(docsRoot, "user-guide", f);
    assert.ok(existsSync(p), `docs/user-guide/${f} missing`);
  }
});

test("docs/dev-guide/ has the 3 dev files", () => {
  for (const f of ["architecture.md", "adding-rounds.md", "testing.md"]) {
    const p = join(docsRoot, "dev-guide", f);
    assert.ok(existsSync(p), `docs/dev-guide/${f} missing`);
  }
});

test("docs/api/ has jsonrpc.md + state-model.md", () => {
  for (const f of ["jsonrpc.md", "state-model.md"]) {
    const p = join(docsRoot, "api", f);
    assert.ok(existsSync(p), `docs/api/${f} missing`);
  }
});

test("docs/api/jsonrpc.md documents the getMetrics RPC (R77)", () => {
  const src = readFileSync(join(docsRoot, "api", "jsonrpc.md"), "utf-8");
  assert.match(src, /getMetrics/);
});

test("docs/user-guide/slash-commands.md documents /metrics (R77)", () => {
  const src = readFileSync(join(docsRoot, "user-guide", "slash-commands.md"), "utf-8");
  assert.match(src, /\/metrics/);
});

test("docs/user-guide/keybindings.md documents Ctrl-? (R49)", () => {
  const src = readFileSync(join(docsRoot, "user-guide", "keybindings.md"), "utf-8");
  assert.match(src, /Ctrl-\? \/ F1/);
});

test("docs/changelog/ has at least one R-round retro", () => {
  const files = ["R33-R82.md", "R77.md", "R78.md", "R79.md"];
  for (const f of files) {
    const p = join(docsRoot, "changelog", f);
    assert.ok(existsSync(p), `docs/changelog/${f} missing`);
  }
});

test("docs/api/jsonrpc.md documents the getTraces RPC (R78)", () => {
  const src = readFileSync(join(docsRoot, "api", "jsonrpc.md"), "utf-8");
  assert.match(src, /getTraces/);
});

test("docs/user-guide/slash-commands.md documents /trace (R78)", () => {
  const src = readFileSync(join(docsRoot, "user-guide", "slash-commands.md"), "utf-8");
  assert.match(src, /\/trace/);
});

test("docs/api/state-model.md documents TraceSummary (R78)", () => {
  const src = readFileSync(join(docsRoot, "api", "state-model.md"), "utf-8");
  assert.match(src, /TraceSummary/);
  assert.match(src, /recentTraces/);
});

test("docs/api/jsonrpc.md documents the getTrace RPC (R79)", () => {
  const src = readFileSync(join(docsRoot, "api", "jsonrpc.md"), "utf-8");
  assert.match(src, /getTrace/);
  // The getTrace row should mention BFS order (parent linkage).
  assert.match(src, /parentSpanId/);
});

test("docs/user-guide/slash-commands.md documents /trace <id> (R79)", () => {
  const src = readFileSync(join(docsRoot, "user-guide", "slash-commands.md"), "utf-8");
  // The /trace <tr-id> row.
  assert.match(src, /tr-<id>|\/trace\s+tr-/);
});

test("docs/api/state-model.md documents spanTree (R79)", () => {
  const src = readFileSync(join(docsRoot, "api", "state-model.md"), "utf-8");
  assert.match(src, /spanTree/);
  assert.match(src, /selectedTraceId/);
});

test("docs/CHANGELOG.md mentions 0.2.4 (R79), 0.2.3 (R78) and the new docs/ structure (R77+)", () => {
  const src = readFileSync(join(docsRoot, "CHANGELOG.md"), "utf-8");
  assert.match(src, /0\.2\.4/);
  assert.match(src, /0\.2\.3/);
  assert.match(src, /0\.2\.2/);
  assert.match(src, /docs structure|user-guide|dev-guide/);
});
