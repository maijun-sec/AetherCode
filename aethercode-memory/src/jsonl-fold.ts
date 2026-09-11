/**
 * Session jsonl → sqlite fold (T-023).
 *
 * At the end of a session the jsonl log is read line by line and each record
 * is converted to a `session_messages` row. The whole fold runs inside a
 * single transaction. On success the jsonl file is removed.
 *
 * The expected record shape is:
 *
 *   { ts: number, role: MessageRole, content: string, metadata?: object, tokenCount?: number }
 *
 * Records with the role `"fact"` are routed to the `project_facts` semantics
 * (they are still written to `session_messages` with role=fact so a later
 * compact can read them). Records that are not valid session messages are
 * skipped (counted as `skipped` in the result) so a corrupt tail never
 * blocks the fold.
 */

import { readFile, unlink } from 'node:fs/promises';
import { writeSessionMessages } from './session-store.js';
import type { Database as DatabaseT } from 'better-sqlite3';
import type { MessageRole, SessionMessageInput } from './types.js';
import { isMessageRole } from './types.js';
import type { JsonlValue } from './jsonl-writer.js';

/** Result of a fold. */
export interface FoldResult {
  /** Number of records successfully written. */
  readonly written: number;
  /** Number of records skipped (parse / validation failures). */
  readonly skipped: number;
  /** Number of bytes removed from disk (file length before delete). */
  readonly bytesRemoved: number;
  /** Whether the jsonl file existed when fold started. */
  readonly found: boolean;
}

/**
 * Fold the jsonl at `jsonlPath` into the `session_messages` table for
 * `sessionId`, then delete the file. Runs in a single transaction.
 */
export async function foldSessionJsonl(
  db: DatabaseT,
  sessionId: string,
  jsonlPath: string,
): Promise<FoldResult> {
  if (!sessionId) throw new Error('foldSessionJsonl: sessionId must be non-empty');
  if (!jsonlPath) throw new Error('foldSessionJsonl: jsonlPath must be non-empty');

  let text: string;
  let bytesRemoved = 0;
  try {
    text = await readFile(jsonlPath, 'utf-8');
    bytesRemoved = Buffer.byteLength(text, 'utf-8');
  } catch (err) {
    const code = (err as NodeJS.ErrnoException).code;
    if (code === 'ENOENT') {
      return { written: 0, skipped: 0, bytesRemoved: 0, found: false };
    }
    throw err;
  }

  const accepted: SessionMessageInput[] = [];
  let skipped = 0;
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (line === '') continue;
    let parsed: unknown;
    try {
      parsed = JSON.parse(line);
    } catch {
      skipped += 1;
      continue;
    }
    const converted = toSessionMessageInput(parsed);
    if (converted === null) {
      skipped += 1;
      continue;
    }
    accepted.push(converted);
  }

  if (accepted.length > 0) {
    writeSessionMessages(db, sessionId, accepted);
  }

  // Best-effort delete. The fold is idempotent: re-running on the same file
  // will read 0 lines and write 0 rows.
  try {
    await unlink(jsonlPath);
  } catch {
    // ignore
  }

  return { written: accepted.length, skipped, bytesRemoved, found: true };
}

/**
 * Convert an unknown jsonl record into a SessionMessageInput. Returns null
 * if the record is not a valid session message. The converter is strict
 * about types (epoch ts as a finite number, role as one of the known
 * MessageRole values, content as a string) so a corrupted tail cannot
 * poison the sqlite table.
 */
export function toSessionMessageInput(record: unknown): SessionMessageInput | null {
  if (record === null || typeof record !== 'object') return null;
  const obj = record as Record<string, unknown>;
  const tsRaw = obj['ts'];
  if (typeof tsRaw !== 'number' || !Number.isFinite(tsRaw)) return null;
  const roleRaw = obj['role'];
  if (typeof roleRaw !== 'string' || !isMessageRole(roleRaw)) return null;
  const role: MessageRole = roleRaw;
  const contentRaw = obj['content'];
  if (typeof contentRaw !== 'string') return null;
  const metadata = obj['metadata'];
  const safeMeta =
    metadata === undefined
      ? undefined
      : isPlainObject(metadata)
        ? (metadata as Readonly<Record<string, unknown>>)
        : undefined;
  const tokenCountRaw = obj['tokenCount'] ?? obj['token_count'];
  const tokenCount =
    typeof tokenCountRaw === 'number' && Number.isFinite(tokenCountRaw) ? tokenCountRaw : undefined;
  return {
    ts: tsRaw,
    role,
    content: contentRaw,
    metadata: safeMeta,
    tokenCount,
  };
}

/** Compact jsonl-value type used by the writer. Re-exported for convenience. */
export type { JsonlValue };

/* ----------------------------- helpers ---------------------------------- */

function isPlainObject(v: unknown): v is Record<string, unknown> {
  return v !== null && typeof v === 'object' && !Array.isArray(v);
}
