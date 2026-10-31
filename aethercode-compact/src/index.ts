// Copyright (c) 2026 maijun
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

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
