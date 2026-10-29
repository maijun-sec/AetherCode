// Copyright (c) 2026 maijun

// Licensed under the MIT License.
// See the LICENSE file in the project root for the full license text.

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
 * repairs the tree on the next run. A snapshot file is used rather than
 * a `process.on("exit")` handler because an outright kill runs
 * NO handler at all, and `git checkout --` is not an option (it
 * restores to HEAD and would swallow every uncommitted fix — Lesson
 * 1171).
 *
 * ## R379 — a snapshot that survives a CLEAN run is a landmine
 *
 * The snapshot exists to undo a KILLED battery's mutations. But the
 * next run cannot tell "the last battery was killed" from "the last
 * battery finished normally and left its snapshot behind" — both look
 * like "a snapshot file exists". The only difference was whether the
 * battery remembered to call `snapshot.clear()`.
 *
 * And it did not. R379's own battery was the only one of fourteen
 * that never called it, so its snapshot outlived the run. The NEXT
 * run then "recovered" from it — and silently reverted everything the
 * developer had changed in between: the round's tightened gate AND
 * the daemon's Java change, both of them, with a cheerful
 * "recovered 12 file(s)" in the log.
 *
 * That is the failure mode this file now makes IMPOSSIBLE rather than
 * merely discouraged:
 *
 *   - on exit, the handler compares every snapshotted file with its
 *     recorded original. If they all match, the tree is CLEAN, so the
 *     snapshot is DELETED — there is nothing to recover from, and no
 *     way for the next run to revert anything;
 *   - if any file still differs, the battery really did die
 *     mid-mutation, so the snapshot SURVIVES — and it is rewritten to
 *     record the MUTATED content as well.
 *
 * The recorded mutated content makes the recovery precise: the next
 * run restores a file only if it still holds the mutation. A file the
 * developer edited after the kill is left alone, and reported.
 *
 * **Lesson — a cleanup step that only the author remembers to perform
 * is a cleanup step that will be missed. Make the missed case
 * IMPOSSIBLE, not forbidden.**
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
 * One snapshot entry.
 *
 * @typedef {{ original: string, mutated: string|null }} SnapshotEntry
 */

/**
 * Read a snapshot file, tolerating BOTH shapes.
 *
 * The current shape is `{ file: { original, mutated } }`. The legacy
 * shape is `{ file: "<content>" }` — a snapshot written before R379.
 * A legacy entry has no recorded mutation, so it is restored
 * unconditionally (the old behaviour), and the next write upgrades it.
 *
 * @param {string} snapshotPath
 * @returns {Record<string, SnapshotEntry>}
 */
function readSnapshot(snapshotPath) {
  let raw;
  try {
    raw = JSON.parse(readFileSync(snapshotPath, "utf-8"));
  } catch {
    return {};
  }
  const out = {};
  for (const [file, entry] of Object.entries(raw ?? {})) {
    if (typeof entry === "string") {
      out[file] = { original: entry, mutated: null };
    } else if (entry && typeof entry === "object") {
      out[file] = {
        original: String(entry.original ?? ""),
        mutated: typeof entry.mutated === "string" ? entry.mutated : null,
      };
    }
  }
  return out;
}

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
  const snap = readSnapshot(snapshotPath);
  let restored = 0;
  let skipped = 0;
  for (const [file, entry] of Object.entries(snap)) {
    let now;
    try {
      now = readFileSync(file, "utf-8");
    } catch {
      continue; // the file moved or was deleted; there is nothing to restore
    }
    // R379 — restore only what is STILL the mutation. If the file was
    // changed after the kill, the change is somebody's work, not ours
    // to undo.
    if (entry.mutated !== null && now !== entry.mutated) {
      skipped++;
      console.log(`  !! ${file} was changed after the interrupted run — left alone (not restored)`);
      continue;
    }
    try {
      writeFileSync(file, entry.original, "utf-8");
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
  if (skipped > 0) {
    console.log(`  !! skipped ${skipped} file(s) that were changed after the interrupted run`);
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
  for (const [file, content] of entries) snap[file] = { original: content, mutated: null };
  writeFileSync(snapshotPath, JSON.stringify(snap), "utf-8");

  // R379 — decide the snapshot's fate by CONTENT, not by a flag.
  //
  // If every snapshotted file still holds its recorded original, the
  // tree is clean: there is nothing to recover from, and a surviving
  // snapshot would only be a chance for the NEXT run to revert the
  // developer's work. So it is deleted.
  //
  // If any file still differs, the battery died mid-mutation: the
  // snapshot must survive, and it is rewritten to record the mutated
  // content — which is what lets the next run tell "this is still the
  // mutation" from "somebody edited this file after the kill".
  const onExit = () => {
    try {
      if (!existsSync(snapshotPath)) return;
      const current = readSnapshot(snapshotPath);
      let dirty = false;
      for (const [file, entry] of Object.entries(current)) {
        let now;
        try {
          now = readFileSync(file, "utf-8");
        } catch {
          dirty = true;
          break;
        }
        if (now !== entry.original) {
          current[file] = { original: entry.original, mutated: now };
          dirty = true;
        }
      }
      if (!dirty) {
        unlinkSync(snapshotPath);
      } else {
        writeFileSync(snapshotPath, JSON.stringify(current), "utf-8");
      }
    } catch {
      /* a broken snapshot is worse than none — leave it for the next run */
    }
  };
  process.on("exit", onExit);

  return {
    clear() {
      process.off("exit", onExit);
      try {
        unlinkSync(snapshotPath);
      } catch {
        /* already gone */
      }
    },
  };
}
