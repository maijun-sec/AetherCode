/**
 * Public API of `aethercode-compact`.
 *
 * Re-exports the public types, classes, and helpers. Consumers should
 * import only from this file (e.g. `import { CompactPipeline, ... } from "aethercode-compact"`).
 */

// Pipeline orchestration
export { CompactPipeline, effectiveWindow, layer3Trigger } from "./pipeline.js";
export type { CompactOptions } from "./pipeline.js";

// Layer 1 (microcompact)
export {
  microCompact,
  isCacheWarm,
  isReadClassTool,
  shouldClearBody,
  buildCacheReference,
} from "./layer1.js";
export type { Layer1Options } from "./layer1.js";

// Layer 3 (LLM compact, 8-segment)
export {
  llmCompact,
  buildPrompt,
  buildSchemaReminder,
  renderHistoryForPrompt,
  extractJsonFromResponse,
  parseAndValidate,
  describeSchema,
  assertSchemaShape,
  enforceSegmentCap,
  replaceHistoryKeepingRecent,
  DEFAULT_4_SHOT_PROMPT,
  compactSummarySchema,
} from "./layer3.js";
export type { Layer3Options } from "./layer3.js";

// Circuit breaker (auto-compact fail-safe)
export {
  CircuitBreaker,
  defaultCircuitBreakerOptions,
  DEFAULT_FAILURE_THRESHOLD,
} from "./circuit-breaker.js";
export type {
  CircuitBreakerOptions,
  CircuitBreakerStatus,
  CircuitBreakerFailure,
  CircuitBreakerFailureReason,
} from "./circuit-breaker.js";

// Reactive backstop (prompt_too_long retry)
export {
  llmCallWithBackstop,
  isPromptTooLong,
  buildShrunkCompactInput,
  reactiveBackstopPass,
  ReactiveBackstopExhaustedError,
} from "./reactive-backstop.js";
export type {
  BackstopAttempt,
  BackstopOutcome,
  ReactiveBackstopOptions,
} from "./reactive-backstop.js";

// Partial compact (--from / --up-to, T-160 → T-162)
export {
  findMessageIndex,
  planCompactFrom,
  planCompactUpTo,
  sliceForCompact,
  applyPartialCompact,
  applyPartialCompactFrom,
  applyPartialCompactUpTo,
  summarizeSliceForPartial,
  partialCompactCacheImpact,
  parsePartialCompactOptions,
} from "./partial-compact.js";
export type { PartialCompactPlan } from "./partial-compact.js";

// LRU readFileState (T-170 → T-171)
export {
  ReadFileState,
  hashContent,
  utf8ByteLength,
  DEFAULT_MAX_ENTRIES,
  DEFAULT_MAX_BYTES,
} from "./lru-file-state.js";
export type {
  ReadFileStateEntry,
  ReadFileStateOptions,
  ReadFileStateStats,
} from "./lru-file-state.js";

// Zod schema + constants
export {
  MAX_SEGMENT_CHARS,
  SEGMENT_FIELD_NAMES,
  compactSummarySchema as summarySchema,
} from "./schema.js";
export type { CompactSummarySchema } from "./schema.js";

// RPC surface (T-190 → T-194)
export {
  CompactRpc,
  DEFAULT_HISTORY_LIMIT,
  RPC_ERR_INTERNAL,
  RPC_ERR_INVALID_PARAMS,
  RPC_ERR_UNKNOWN_METHOD,
} from "./rpc.js";
export type {
  CompactEvent,
  CompactRpcOptions,
  CompactRpcFullOptions,
  CompactRunParams,
  CompactRunResult,
  CompactStatusResult,
  CompactResetResult,
  CompactHistoryParams,
  RpcDispatchResult,
  BuildInputFn,
} from "./rpc.js";

// Persistent history store (T-193) — SQLite-backed, with a
// memory fallback for environments without the native binding.
export {
  MemoryHistoryStore,
  SqliteHistoryStore,
  createHistoryStore,
  applyMigrations,
  readHistorySchemaVersion,
  DEFAULT_HISTORY_MIGRATIONS,
} from "./history-store.js";
export type {
  HistoryStore,
  CreateHistoryStoreOptions,
  SqliteHistoryStoreOptions,
  SqliteBinding,
  SqliteStatement,
  HistoryMigration,
} from "./history-store.js";

// Token helpers
export {
  estimateTokens,
  estimateMessageTokens,
  estimateToolResultTokens,
  estimateInputTokens,
} from "./tokens.js";

// Core types
export type {
  CompactInput,
  CompactResult,
  CompactSummary,
  CompactPipelineOptions,
  LlmClient,
  Message,
  MemoryReadResult,
  ModelInfo,
  ToolResult,
  CacheStatus,
} from "./types.js";

export { READ_CLASS_TOOL_NAMES, CLEARED_PLACEHOLDER } from "./types.js";
