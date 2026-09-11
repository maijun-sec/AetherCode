/**
 * Append-only jsonl writer (T-022, T-024).
 *
 * The session log lives at `<cwd>/.aethercode/sessions/<sid>.jsonl`. The
 * writer is responsible for:
 *
 *   1. Creating the file (and its parent directories) if it does not exist.
 *   2. Appending one JSON object per line, each followed by a newline.
 *   3. Issuing a fsync after every batch (or every line) so a crash never
 *      leaves a partially-written record visible to the next read.
 *   4. Serialising the object deterministically — keys in insertion order,
 *      no extra whitespace, one record per line.
 *
 * The writer holds an open file handle; call `close()` to release it. The
 * writer is intentionally single-writer — concurrent appenders must share
 * an instance (the higher-level MemoryStore is the only intended writer).
 */

import { appendFile, chmod, mkdir, open, rename, stat, unlink, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import type { FileHandle } from 'node:fs/promises';
import { OWNER_RW_ONLY } from './security/permissions.js';

/** Anything JSON-serialisable. */
export type JsonlValue = string | number | boolean | null | JsonlObject | JsonlArray;
export interface JsonlObject {
  readonly [k: string]: JsonlValue;
}
export type JsonlArray = ReadonlyArray<JsonlValue>;

/** Options for `createJsonlWriter`. */
export interface JsonlWriterOptions {
  /**
   * If `true` (the default), fsync the file after each `append` / `appendMany`
   * call. Disabling fsync makes writes much faster but trades durability; only
   * do this in tests or when the caller has its own fsync strategy.
   */
  readonly fsync?: boolean;
  /**
   * If `true` and the writer sees a `JSON.parse` of the last line fail on
   * reopen, the corrupted tail is truncated. Default: `false` (strict).
   */
  readonly recoverFromTruncatedTail?: boolean;
}

/** Public writer handle. */
export interface JsonlWriter {
  readonly path: string;
  /** Append one record, then fsync (unless disabled). */
  append(record: JsonlValue): Promise<void>;
  /** Append many records inside one fsync. */
  appendMany(records: ReadonlyArray<JsonlValue>): Promise<void>;
  /** Number of records appended by this writer instance (best-effort). */
  readonly count: () => number;
  /** Force an fsync without writing. */
  sync(): Promise<void>;
  /** Release the file handle. */
  close(): Promise<void>;
}

/**
 * T-022 + T-024 — open (or create) a jsonl file for appending, and return a
 * writer that fsyncs after every write. The writer is safe to reopen: if the
 * file is missing it is created; if the file is non-empty it is appended to.
 */
export async function createJsonlWriter(
  path: string,
  options: JsonlWriterOptions = {},
): Promise<JsonlWriter> {
  if (!path) throw new Error('createJsonlWriter: path must be non-empty');
  await mkdir(dirname(path), { recursive: true });

  // Ensure the file exists. We open with 'a' (append) so the file is created
  // if absent. We then close the handle and open our own so we control fsync.
  const fileExists = await pathExists(path);
  if (!fileExists) {
    await writeFile(path, '', { encoding: 'utf-8', flag: 'a' });
    // T-507: best-effort 0600 on POSIX. The session log
    // is per-user secret-ish content; we never want
    // group/other to read it. No-op on Windows.
    try {
      await chmod(path, OWNER_RW_ONLY);
    } catch {
      // best-effort; never fail the write
    }
  } else if (options.recoverFromTruncatedTail === true) {
    await recoverTruncatedTail(path);
    // Re-apply 0600 after a tail-recovery rewrite.
    try {
      await chmod(path, OWNER_RW_ONLY);
    } catch {
      // best-effort
    }
  }

  const handle: FileHandle = await open(path, 'a');
  const fsync = options.fsync !== false; // default: true
  let appended = 0;
  let closed = false;

  const writer: JsonlWriter = {
    path,
    async append(record: JsonlValue): Promise<void> {
      if (closed) throw new Error(`JsonlWriter(${path}) is closed`);
      const line = serialiseJsonlLine(record);
      await handle.appendFile(line, 'utf-8');
      appended += 1;
      if (fsync) {
        await handle.sync();
      }
    },
    async appendMany(records: ReadonlyArray<JsonlValue>): Promise<void> {
      if (closed) throw new Error(`JsonlWriter(${path}) is closed`);
      if (records.length === 0) return;
      const buf = records.map(serialiseJsonlLine).join('');
      await handle.appendFile(buf, 'utf-8');
      appended += records.length;
      if (fsync) {
        await handle.sync();
      }
    },
    count: () => appended,
    async sync(): Promise<void> {
      if (closed) throw new Error(`JsonlWriter(${path}) is closed`);
      await handle.sync();
    },
    async close(): Promise<void> {
      if (closed) return;
      closed = true;
      try {
        await handle.sync();
      } catch {
        // best-effort
      }
      await handle.close();
    },
  };
  return writer;
}

/**
 * Replace a jsonl file with an atomic-rename write. Useful for tests that
 * want to seed a session log without going through the writer, or for
 * recovery. Uses temp-file + rename to be crash-safe.
 */
export async function writeJsonlFile(path: string, records: ReadonlyArray<JsonlValue>): Promise<void> {
  await mkdir(dirname(path), { recursive: true });
  const tmp = `${path}.tmp-${process.pid}-${Date.now()}`;
  const body = records.map(serialiseJsonlLine).join('');
  await writeFile(tmp, body, { encoding: 'utf-8' });
  // T-507: apply 0600 to the temp file *before* the
  // rename so the final path inherits the secure mode.
  try {
    await chmod(tmp, OWNER_RW_ONLY);
  } catch {
    // best-effort
  }
  await rename(tmp, path);
  // Re-apply on the final path in case the rename
  // copy didn't preserve the mode (it does on POSIX,
  // but defensive against future FS semantics).
  try {
    await chmod(path, OWNER_RW_ONLY);
  } catch {
    // best-effort
  }
}

/** Read every line of a jsonl file as a parsed JSON object. Skips blank lines. */
export async function readJsonlFile(path: string): Promise<JsonlValue[]> {
  const text = await (await import('node:fs/promises')).readFile(path, 'utf-8');
  const out: JsonlValue[] = [];
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (line === '') continue;
    out.push(JSON.parse(line) as JsonlValue);
  }
  return out;
}

/** Append one record via the lower-level `appendFile` API. Used by tests. */
export async function appendJsonlLine(path: string, record: JsonlValue): Promise<void> {
  await mkdir(dirname(path), { recursive: true });
  await appendFile(path, serialiseJsonlLine(record), 'utf-8');
  // T-507: best-effort 0600 after append. The mode is
  // already set when the file was first created; this
  // is a defensive re-apply in case a process reset it.
  try {
    await chmod(path, OWNER_RW_ONLY);
  } catch {
    // best-effort
  }
}

/* --------------------------- internals ---------------------------------- */

/** Serialise a record to a single line: compact JSON + trailing newline. */
function serialiseJsonlLine(record: JsonlValue): string {
  return JSON.stringify(record) + '\n';
}

/** Test whether a file exists. */
async function pathExists(path: string): Promise<boolean> {
  try {
    await stat(path);
    return true;
  } catch {
    return false;
  }
}

/**
 * If the last line of the file is not a complete JSON object, drop it.
 * Used when the writer is opened with `recoverFromTruncatedTail: true` to
 * heal an interrupted previous session.
 */
async function recoverTruncatedTail(path: string): Promise<void> {
  const text = await (await import('node:fs/promises')).readFile(path, 'utf-8');
  if (text === '') return;
  // Split into lines but remember the trailing fragment (no newline at EOF).
  const lines = text.split('\n');
  // `lines` ends with a trailing empty string if the file ends in '\n'.
  // We always drop the last *non-empty* line and re-test.
  let last = lines[lines.length - 1] ?? '';
  if (last === '' && lines.length > 1) {
    last = lines[lines.length - 2] ?? '';
  }
  if (last === '') return;
  try {
    JSON.parse(last);
    return;
  } catch {
    // Fall through to truncate.
  }
  // Find the offset of the corrupted tail and rewrite.
  const lastGood = text.lastIndexOf('\n', text.length - last.length - 1);
  if (lastGood < 0) {
    // Whole file is corrupt — drop it.
    await unlink(path);
    return;
  }
  const good = text.slice(0, lastGood + 1);
  await writeFile(path, good, { encoding: 'utf-8' });
}
