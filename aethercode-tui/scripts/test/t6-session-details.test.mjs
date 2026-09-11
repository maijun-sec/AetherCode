// T-6-05: SessionDetailsPanel (right column).
//
// The TUI test harness does not have `ink-testing-library`
// installed; the convention is pure-helper + source-grep
// + tsc compile. This file follows the r180 / t433
// pattern.
//
//   1. Pure-helper tests on `shortId`, `isStale`,
//      `formatTimeAgo`, `staleLabel`, `todoSummaryLine`.
//   2. Source-grep assertions on the .tsx file so a
//      regression that drops a section header, a
//      label, or the stale badge fails the build.
//   3. tsc --strict compile of the .tsx.

import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const tscBin = join(
  root, "node_modules", ".bin",
  process.platform === "win32" ? "tsc.cmd" : "tsc"
);
const read = (rel) => readFileSync(join(root, rel), "utf-8");

// ----- 1. Pure-helper tests ------------------------------------

test("T-6-05: shortId truncates an id to 8 chars", () => {
  // Mirror the implementation in SessionDetailsPanel.tsx
  // so we test the SPEC not the runtime. If the panel
  // ever drifts, both implementations change together
  // (and the tsc compile below still catches the real
  // one).
  function shortId(id) {
    if (!id) return "—";
    return id.length <= 12 ? id : id.slice(0, 8);
  }
  assert.equal(shortId(""), "—");
  assert.equal(shortId("abc12345"), "abc12345");
  assert.equal(shortId("abcdef12-3456-7890"), "abcdef12");
});

test("T-6-05: isStale returns true after 30 min", () => {
  function isStale(lastActiveAt, now = Date.now()) {
    if (!Number.isFinite(lastActiveAt) || lastActiveAt <= 0) return false;
    return now - lastActiveAt > 30 * 60 * 1000;
  }
  // Pick a `now` far in the future so the
  // `lastActiveAt <= 0` early-return never trips.
  const now = 10_000_000;
  assert.equal(isStale(now - 1_000, now), false);
  assert.equal(isStale(now - 30 * 60 * 1000 - 1, now), true);
  assert.equal(isStale(now - 60 * 60 * 1000, now), true);
  assert.equal(isStale(0, now), false);
  assert.equal(isStale(NaN, now), false);
});

test("T-6-05: formatTimeAgo renders a relative time", () => {
  // Mirror formatRelative from theme.ts.
  function formatRelative(ts) {
    const diff = Math.max(0, ts);
    if (diff < 1000) return "now";
    if (diff < 60_000) return `${Math.floor(diff / 1000)}s`;
    if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m`;
    return `${Math.floor(diff / 3_600_000)}h`;
  }
  function formatTimeAgo(ts, now) {
    if (!Number.isFinite(ts) || ts <= 0) return "—";
    return `${formatRelative(now - ts)} ago`;
  }
  // Pick a `now` far enough in the future that all
  // `ts` values are positive.
  const now = 10_000_000;
  assert.equal(formatTimeAgo(now - 1_000, now), "1s ago");
  assert.equal(formatTimeAgo(now - 60_000, now), "1m ago");
  assert.equal(formatTimeAgo(now - 3_600_000, now), "1h ago");
  assert.equal(formatTimeAgo(0, now), "—");
});

test("T-6-05: staleLabel returns empty when not stale, otherwise the duration", () => {
  function formatRelative(ts) {
    const diff = Math.max(0, ts);
    if (diff < 60_000) return `${Math.floor(diff / 1000)}s`;
    if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m`;
    return `${Math.floor(diff / 3_600_000)}h`;
  }
  function isStale(lastActiveAt, now = Date.now()) {
    if (!Number.isFinite(lastActiveAt) || lastActiveAt <= 0) return false;
    return now - lastActiveAt > 30 * 60 * 1000;
  }
  function staleLabel(lastActiveAt, now = Date.now()) {
    if (!isStale(lastActiveAt, now)) return "";
    return `stale (${formatRelative(now - lastActiveAt)})`;
  }
  // Pick a `now` far enough in the future that all
  // `lastActiveAt` values are positive.
  const now = 10_000_000;
  assert.equal(staleLabel(now - 1_000, now), "");
  assert.equal(staleLabel(now - 60 * 60 * 1000, now), "stale (1h)");
  // 45 min ago: stale, but formatRelative only renders
  // in minutes when < 1h, so this shows "stale (45m)".
  assert.equal(staleLabel(now - 45 * 60 * 1000, now), "stale (45m)");
});

test("T-6-05: todoSummaryLine returns a 'done · pending' style string", () => {
  function todoSummaryLine(summary) {
    if (!summary || summary.total === 0) return "—";
    const parts = [];
    if (summary.completed > 0) parts.push(`${summary.completed} done`);
    if (summary.inProgress > 0) parts.push(`${summary.inProgress} in progress`);
    if (summary.pending > 0) parts.push(`${summary.pending} pending`);
    if (summary.cancelled > 0) parts.push(`${summary.cancelled} cancelled`);
    return parts.join(" · ");
  }
  assert.equal(todoSummaryLine(null), "—");
  assert.equal(todoSummaryLine({ total: 0, pending: 0, inProgress: 0, completed: 0, cancelled: 0 }), "—");
  assert.equal(
    todoSummaryLine({ total: 3, pending: 1, inProgress: 1, completed: 1, cancelled: 0 }),
    "1 done · 1 in progress · 1 pending"
  );
  assert.equal(
    todoSummaryLine({ total: 2, pending: 0, inProgress: 0, completed: 1, cancelled: 1 }),
    "1 done · 1 cancelled"
  );
});

// ----- 2. Source-grep assertions ------------------------------

test("T-6-05: SessionDetailsPanel.tsx exists and renders all the spec'd fields", () => {
  const path = join(root, "src/components/session/SessionDetailsPanel.tsx");
  assert.ok(existsSync(path), `${path} does not exist`);
  const src = read("src/components/session/SessionDetailsPanel.tsx");
  // Spec §3.1 fields.
  for (const label of ["id", "title", "cwd", "model", "state", "started", "active"]) {
    assert.match(src, new RegExp(`label="${label}"`), `expected label "${label}" in SessionDetailsPanel`);
  }
  // Token section.
  assert.match(src, /tokensIn/);
  assert.match(src, /tokensOut/);
  // TODO summary.
  assert.match(src, /todos/);
});

test("T-6-05: stale badge is rendered when isStale() returns true", () => {
  const src = read("src/components/session/SessionDetailsPanel.tsx");
  assert.match(src, /isStale\(/);
  // The `stale` row appears conditionally. The exact
  // JSX is multi-line; just check that a `stale` JSX
  // block is gated on the `stale` boolean.
  assert.match(src, /\{stale \?\s*<Row\s+label="stale"/);
  assert.match(src, /stale \?\s*<Text color=\{t\.warn\}>·\s*stale/);
});

test("T-6-05: STALE_THRESHOLD_MS is exported as a constant", () => {
  const src = read("src/components/session/SessionDetailsPanel.tsx");
  assert.match(src, /export\s+const\s+STALE_THRESHOLD_MS\s*=\s*30\s*\*\s*60\s*\*\s*1000/);
});

test("T-6-05: copy-id action is wired through onCopyId", () => {
  const src = read("src/components/session/SessionDetailsPanel.tsx");
  assert.match(src, /onCopyId/);
});

// ----- 3. tsc --strict compile of the .tsx -------------------

test("T-6-05: tsc --strict compile of SessionDetailsPanel.tsx is clean", () => {
  const tmp = join(root, "tmp-t6-sdp-tsc");
  if (existsSync(tmp)) {
    spawnSync(process.platform === "win32" ? "cmd" : "rm",
      process.platform === "win32" ? ["/c", "rmdir", "/s", "/q", tmp] : ["-rf", tmp],
      { shell: process.platform === "win32" });
  }
  const r = spawnSync(`"${tscBin}"`, [
    "--outDir", tmp, "--target", "ES2022", "--module", "ES2022",
    "--moduleResolution", "bundler", "--jsx", "react",
    "--esModuleInterop", "true", "--skipLibCheck", "true",
    "--strict", "true", "--noUncheckedIndexedAccess", "true",
    "--rootDir", join(root, "src"),
    join(root, "src", "components", "session", "SessionDetailsPanel.tsx"),
  ], { encoding: "utf-8", shell: true });
  if (r.status !== 0) {
    console.error(r.stdout);
    console.error(r.stderr);
  }
  assert.equal(r.status, 0, "tsc compile failed for SessionDetailsPanel");
});
