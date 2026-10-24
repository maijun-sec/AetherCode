/**
 * A mutation battery rewrites PRODUCT SOURCE IN PLACE.
 *
 * If the process dies mid-case, the restore never runs, and the next
 * test run reads a deliberately broken product — the failures then
 * belong to neither the battery nor the round. Killing a battery by
 * accident is easy: a truncated pipe (`| Select-Object -First N`)
 * closes stdout, and on Windows PowerShell stops the upstream process
 * outright. (That is not hypothetical: it is how R375's own battery
 * was killed, leaving `RUN_STOP_METHOD = "task/kill"` in the source
 * and three false failures in the full suite.)
 *
 * ## The guard
 *
 * The original bytes of every file the battery may touch are written to
 * a snapshot file BEFORE the first mutation. A battery that STARTS
 * with a snapshot present restores from it first — so a killed battery
 * repairs the tree on the next run. A snapshot file is used rather
 * than a `process.on("exit")` handler because an outright kill runs
 * NO handler at all, and `git checkout --` is not an option (it
 * restores to HEAD and would swallow every uncommitted fix — Lesson
 * 1171).
 *
 * ## Usage
 *
 *     import { recoverIfInterrupted, writeSnapshot } from "./mutation-snapshot.mjs";
 *
 *     const SNAPSHOT = join(root, "tmp-rNNN-mutate.snapshot.json");
 *     recoverIfInterrupted(SNAPSHOT);            // BEFORE reading the originals
 *     const original = ...;                        // now reads a clean tree
 *     const snapshot = writeSnapshot(entries, SNAPSHOT);   // AFTER
 *     ... run the cases ...
 *     snapshot.clear();
 *
 * `entries` is `[[absolutePath, originalContent], ...]`.
 */

import { existsSync, readFileSync, writeFileSync, unlinkSync } from "node:fs";

/**
 * Repair the tree from a previous run's snapshot, if there is one.
 *
 * Call this BEFORE the originals are read into memory — otherwise the
 * "original" would be the mutated content, and the battery would
 * happily cement the mutation as the baseline.
 *
 * @param {string} snapshotPath
 * @returns {boolean} whether anything was restored
 */
export function recoverIfInterrupted(snapshotPath) {
  if (!existsSync(snapshotPath)) return false;
  let snap = {};
  try {
    snap = JSON.parse(readFileSync(snapshotPath, "utf-8"));
  } catch {
    snap = {};
  }
  let restored = 0;
  for (const [file, content] of Object.entries(snap)) {
    try {
      writeFileSync(file, content, "utf-8");
      restored++;
    } catch {
      // The file may have moved or been deleted; there is nothing to
      // restore for it, and the rest of the snapshot still matters.
    }
  }
  try {
    unlinkSync(snapshotPath);
  } catch {
    /* already gone */
  }
  if (restored > 0) {
    console.log(`  !! recovered ${restored} file(s) from an interrupted mutation run`);
  }
  return restored > 0;
}

/**
 * Write the snapshot of the files a battery is about to mutate.
 *
 * @param {Array<[string, string]>} entries [absolutePath, originalContent]
 * @param {string} snapshotPath
 * @returns {{ clear: () => void }}
 */
export function writeSnapshot(entries, snapshotPath) {
  const snap = {};
  for (const [file, content] of entries) snap[file] = content;
  writeFileSync(snapshotPath, JSON.stringify(snap), "utf-8");
  return {
    clear() {
      try {
        unlinkSync(snapshotPath);
      } catch {
        /* already gone */
      }
    },
  };
}
