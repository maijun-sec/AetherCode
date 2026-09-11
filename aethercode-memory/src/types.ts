/**
 * Memory system — type definitions.
 *
 * Three layers: global / project / session.
 * Four entry kinds: fact, rule, change, breadcrumb.
 * Source provenance: where the entry came from.
 *
 * See design.md §1.2 for the markdown layout that these types round-trip.
 */

/** Where an entry lives. */
export type MemoryScope = 'global' | 'project' | 'session';

/** Where an entry came from. */
export type MemorySource =
  | 'user' // explicitly entered by the user (CLI / TUI)
  | 'llm' // produced by the LLM during compression
  | 'system' // produced by the runtime (cwd switch, breadcrumb)
  | 'tool' // produced by a tool execution
  | 'imported'; // imported from an external file

/** Common fields every memory entry carries. */
interface MemoryEntryBase {
  /** Monotonic identifier within a layer. Stable for diff/merge. */
  readonly id: string;
  /** When the entry was created (epoch milliseconds). */
  readonly ts: number;
  /** Which layer the entry belongs to. */
  readonly scope: MemoryScope;
  /** Where the entry came from. */
  readonly source: MemorySource;
  /** Free-form tags for filtering (e.g. "cwd-switch", "compression"). */
  readonly tags: ReadonlyArray<string>;
}

/** A piece of information the user/LLM wants to remember. */
export interface Fact extends MemoryEntryBase {
  readonly kind: 'fact';
  /** Stable key, e.g. "user.name", "project.build_cmd". */
  readonly key: string;
  /** Value, kept as a string for portability. Structured values use JSON. */
  readonly value: string;
}

/** A durable rule the agent must follow. */
export interface Rule extends MemoryEntryBase {
  readonly kind: 'rule';
  /** Human-readable rule text. */
  readonly text: string;
}

/** A project change log entry (one bullet under "## 修改记录"). */
export interface ChangeEntry extends MemoryEntryBase {
  readonly kind: 'change';
  /** Short description of what changed. */
  readonly description: string;
  /** Has this change been folded into the project 说明 by compression? */
  readonly compressed: boolean;
}

/** A cross-project breadcrumb (e.g. cwd switch, project open/close). */
export interface Breadcrumb extends MemoryEntryBase {
  readonly kind: 'breadcrumb';
  /** Breadcrumb message, e.g. "cwd: /old → /new". */
  readonly message: string;
}

/** Union of all entry kinds. */
export type MemoryEntry = Fact | Rule | ChangeEntry | Breadcrumb;

/** Result of a read operation. */
export interface MemoryReadResult {
  /** Which backend served the read. */
  readonly source: 'cache' | 'sqlite' | 'file';
  /** All entries for the layer. */
  readonly entries: ReadonlyArray<MemoryEntry>;
  /** Total approximate token count (computed once at read time). */
  readonly totalTokens: number;
  /** Whether the result was truncated to fit a token budget. */
  readonly truncated: boolean;
}

/** Sentinel for unknown scope in error messages. */
export function isMemoryScope(value: string): value is MemoryScope {
  return value === 'global' || value === 'project' || value === 'session';
}

/** Role of a session message (matches the design.md comment on session_messages.role). */
export type MessageRole = 'user' | 'assistant' | 'tool' | 'system' | 'fact';

/** Sentinel type-guard for `MessageRole`. */
export function isMessageRole(value: string): value is MessageRole {
  return (
    value === 'user' ||
    value === 'assistant' ||
    value === 'tool' ||
    value === 'system' ||
    value === 'fact'
  );
}

/** A row in the `session_messages` table (sqlite shape). */
export interface SessionMessageRow {
  readonly session_id: string;
  readonly ts: number;
  readonly role: MessageRole;
  readonly content: string;
  /** Optional JSON-encoded metadata blob. */
  readonly metadata: string | null;
  /** Approximate token count (caller-supplied, used for budgeting). */
  readonly token_count: number | null;
}

/** What callers pass to `appendSessionMessage` — a friendlier shape. */
export interface SessionMessageInput {
  /** When the message was created (epoch ms). */
  readonly ts: number;
  readonly role: MessageRole;
  readonly content: string;
  /** Arbitrary metadata; serialised as JSON before storage. */
  readonly metadata?: Readonly<Record<string, unknown>>;
  /** Approximate token count, if known. */
  readonly tokenCount?: number;
}

/** A read filter for session messages. */
export interface SessionMessageQuery {
  /** Inclusive lower bound on `ts` (epoch ms). */
  readonly fromTs?: number;
  /** Inclusive upper bound on `ts` (epoch ms). */
  readonly toTs?: number;
  /** Limit the number of rows returned. */
  readonly limit?: number;
  /** When `true`, return rows in descending `ts` order. Default `false` (ascending). */
  readonly descending?: boolean;
}

/** Type guards. */
export function isFact(entry: MemoryEntry): entry is Fact {
  return entry.kind === 'fact';
}

export function isRule(entry: MemoryEntry): entry is Rule {
  return entry.kind === 'rule';
}

export function isChange(entry: MemoryEntry): entry is ChangeEntry {
  return entry.kind === 'change';
}

export function isBreadcrumb(entry: MemoryEntry): entry is Breadcrumb {
  return entry.kind === 'breadcrumb';
}
