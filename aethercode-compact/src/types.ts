/**
 * Public types for the AetherCode context compression pipeline.
 *
 * Mirrors `design.md §2.1` and `spec.md §2`. All types are
 * implementation-agnostic: they describe shape only, not storage.
 */

/**
 * The 8-segment structured summary produced by Layer 3 (full LLM compact).
 * Field names and order are fixed; see `design.md §2.4`.
 */
export type CompactSummary = {
  /** 1. User task intent — the original goal. */
  userTaskIntent: string;
  /** 2. Project context — which files, modules, concepts matter. */
  projectContext: string;
  /** 3. Approach taken — what was tried and what worked. */
  approachTaken: string;
  /** 4. Bugs / failures and their fixes. */
  bugsAndFailures: string;
  /** 5. Tool outputs we explicitly want to keep. */
  toolOutputsRetained: string;
  /** 6. Decisions and trade-offs. */
  decisionsAndTradeoffs: string;
  /** 7. Pending todos. */
  openTodos: string;
  /** 8. Next-step plan. */
  nextStepPlan: string;
};

/** Information about the model in use. */
export type ModelInfo = {
  /** Provider model id (e.g. "claude-opus-4-1"). */
  name: string;
  /** Maximum context window in tokens (input + output). */
  maxTokens: number;
  /** Maximum output tokens the model can emit in one response. */
  maxOutputTokens: number;
};

/** A single role-tagged message in the conversation history. */
export type Message = {
  id: string;
  role: "user" | "assistant" | "tool" | "system" | "summary";
  /** Plain text content (for tool messages this is the rendered body). */
  content: string;
  /** For tool messages: the name of the tool that produced this result. */
  toolName?: string;
  /** For tool messages: the id of the matching `tool_use` block. */
  toolCallId?: string;
  /** Epoch ms of when the message was created. */
  ts: number;
  /** Token estimate. Optional; computed lazily. */
  tokens?: number;
  /** When the body has been replaced by the Layer 1 placeholder. */
  cleared?: boolean;
};

/** A tool result, parallel to the matching tool_use block. */
export type ToolResult = {
  id: string;
  /** Name of the tool that produced this result. */
  toolName: string;
  /** The id of the matching tool_use block in the assistant message. */
  toolCallId: string;
  /** Raw text body. Will be cleared for large read-class tools in Layer 1. */
  body: string;
  /** Whether the body was replaced with the placeholder by Layer 1. */
  cleared?: boolean;
  /** Epoch ms. */
  ts: number;
  /** Token estimate of the body. */
  tokens?: number;
};

/**
 * Read-class tool result returned from one of the supported memory backends.
 * Kept minimal here — the full definition lives in `aethercode-memory` and
 * we don't take a hard dependency on that module.
 */
export type MemoryReadResult = {
  source: "cache" | "sqlite" | "file";
  /** Token count of the loaded entries. */
  totalTokens: number;
  /** Whether the underlying store truncated the entries to fit a budget. */
  truncated: boolean;
  /** Opaque entries blob — left to the consumer to render. */
  entries: ReadonlyArray<{ kind: string; content: string; ts?: number }>;
};

/** The cache state of the most recent LLM call. */
export type CacheStatus = "warm" | "cool" | "unknown";

/** Input to a compact pass. */
export type CompactInput = {
  model: ModelInfo;
  globalMemory: MemoryReadResult;
  projectMemory: MemoryReadResult;
  sessionMemory: MemoryReadResult;
  history: ReadonlyArray<Message>;
  toolResults: ReadonlyArray<ToolResult>;
  cacheStatus: CacheStatus;
  /** Epoch ms of the last assistant message; used for cache-warm detection. */
  lastAssistantTs?: number;
  /** Optional pre-computed token count for the prompt (skips re-estimation). */
  inputTokens?: number;
};

/** Output of a compact pass. */
export type CompactResult = {
  /** Which layer actually ran (1, 2, or 3). */
  layer: 1 | 2 | 3;
  /** Token count before the compact. */
  beforeTokens: number;
  /** Token count after the compact. */
  afterTokens: number;
  /** The new history (may be shortened or replaced). */
  history: Message[];
  /** The new tool results (may have cleared bodies). */
  toolResults: ToolResult[];
  /** When the cache is warm and a server-side directive should be sent. */
  cacheReference: string | null;
  /** Wall-clock duration of the pass. */
  elapsedMs: number;
  /** For layer 3 results: the validated 8-segment summary. */
  summary?: CompactSummary;
  /** When a tool result was replaced with a placeholder. */
  clearedToolResultIds?: string[];
};

/** Pipeline-level options. */
export type CompactPipelineOptions = {
  /**
   * If `lastAssistantTs` is within this many ms, the cache is treated as
   * "warm" and the local tool-result body is preserved (server drops it).
   * Default: 30_000 (30 s).
   */
  cacheWarmWindowMs: number;
  /**
   * Number of recent user messages to keep verbatim when Layer 3 runs.
   * Default: 5.
   */
  layer3RecentKeep: number;
  /**
   * Hard cap on the LLM output tokens for the 8-segment summary.
   * Default: 8_000 (1 K tokens per segment × 8 segments).
   */
  layer3MaxOutputTokens: number;
  /**
   * Byte threshold for clearing a tool-result body in Layer 1.
   * Default: 4_096 (4 KiB).
   */
  layer1ClearThresholdBytes: number;
  /**
   * Optional override of the default 4-shot prompt template.
   * Must produce output that validates against the 8-segment schema.
   */
  layer3PromptTemplate?: string;
};

/** Minimal LLM-client contract used by Layer 3. */
export type LlmClient = {
  /**
   * Send `prompt` and return the model's raw text response.
   * The compact pipeline handles JSON parsing and validation.
   */
  complete: (
    prompt: string,
    options?: { maxTokens?: number; model?: string },
  ) => Promise<string>;
};

/** Read-class tool names that Layer 1 inspects for body-clearing. */
export const READ_CLASS_TOOL_NAMES: ReadonlySet<string> = new Set([
  "read_file",
  "bash",
  "grep_files",
  "glob_files",
  "web_search",
  "web_fetch",
  "edit_file",
  "write_file",
]);

/** Placeholder text used when a read-class tool result is cleared. */
export const CLEARED_PLACEHOLDER = "[Old tool result content cleared]";
