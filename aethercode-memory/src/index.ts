/**
 * aethercode-memory — public API.
 *
 * Phase 1 (Round 1): types, markdown parse/serialize (global + project),
 *   versioned SQLite migration runner.
 * Phase 1 (Round 2): session/project stores, jsonl writer, jsonl fold,
 *   LRU cache, high-level MemoryStore.
 * Phase 1 (Round 3): compression (4-shot LLM, WAL, resume), project
 *   switcher (hash(cwd), breadcrumb, supervisor hook), and JSON-RPC
 *   handlers (memory/get, appendProjectChange, appendSessionFact,
 *   compact, switchProject, list).
 */

export type {
  MemoryEntry,
  MemoryReadResult,
  MemoryScope,
  MemorySource,
  Fact,
  Rule,
  ChangeEntry,
  Breadcrumb,
  MessageRole,
  SessionMessageInput,
  SessionMessageQuery,
  SessionMessageRow,
} from './types.js';
export {
  isFact,
  isRule,
  isChange,
  isBreadcrumb,
  isMemoryScope,
  isMessageRole,
} from './types.js';

export {
  parseGlobalMemory,
  serializeGlobalMemory,
  globalMemoryEntries,
  type GlobalMemory,
  type GlobalSection,
} from './markdown.js';

export {
  parseProjectMemory,
  serializeProjectMemory,
  projectMemoryEntries,
  readProjectMemoryFile,
  type ProjectMemory,
} from './project-markdown.js';

export {
  openAndMigrate,
  withDatabase,
  readSchemaVersion,
  runStatement,
  queryAll,
  DEFAULT_MIGRATIONS,
  type Migration,
} from './sqlite.js';

export {
  writeSessionMessage,
  writeSessionMessages,
  readSessionMessages,
  countSessionMessages,
  deleteSession,
  deleteSessionMessage,
} from './session-store.js';

export {
  upsertProject,
  readProject,
  listProjects,
  deleteProject,
  appendProjectChange,
  appendProjectChanges,
  listProjectChanges,
  countProjectChanges,
  markChangesCompressed,
  deleteProjectChange,
  projectChangeToEntry,
  projectRowToFacts,
  // R-MEM-3
  appendTeamChange,
  listTeamChanges,
  countTeamChanges,
  promoteTeamChanges,
  type ProjectRow,
  type ProjectUpsert,
  type ProjectChangeRow,
  type ProjectChangeInput,
} from './project-store.js';

export {
  createJsonlWriter,
  writeJsonlFile,
  readJsonlFile,
  appendJsonlLine,
  type JsonlWriter,
  type JsonlWriterOptions,
  type JsonlValue,
  type JsonlObject,
  type JsonlArray,
} from './jsonl-writer.js';

export {
  foldSessionJsonl,
  toSessionMessageInput,
  type FoldResult,
} from './jsonl-fold.js';

export {
  createLruCache,
  type LruCache,
  type LruEntry,
  type LruCacheOptions,
  type CacheKey,
} from './lru-cache.js';

export {
  createMemoryStore,
  ensureProjectFile,
  ensureGlobalFile,
  type MemoryStore,
  type MemoryStoreOptions,
  type SessionHandle,
} from './memory-store.js';

export {
  // T-050 trigger
  shouldTriggerCompression,
  DEFAULT_CHANGE_LIMIT,
  DEFAULT_MAX_DESCRIPTION_TOKENS,
  // T-051 prompt
  DEFAULT_COMPRESSION_PROMPT,
  buildCompressionPrompt,
  renderChangesForPrompt,
  estimateDescriptionTokens,
  // T-052 / T-053 WAL
  startWalEntry,
  markWalLlmDone,
  markWalCommitted,
  markWalFailed,
  listWalRows,
  findResumableWal,
  // T-052 entry
  requestCompression,
  runCompressionPass,
  // adapter
  adaptMemoryStore,
  type CompressionLlmClient,
  type CompressionOptions,
  type CompressionStore,
  type CompressionFileFs,
  type CompressionWalRow,
  type CompactResult,
} from './compression.js';

export {
  // T-060 entry
  switchProject,
  // T-061 hash
  hashCwd,
  defaultProjectDir,
  defaultProjectMemoryPath,
  type ProjectSwitchStore,
  type SwitchOptions,
  type SwitchResult,
  type SwitchFileFs,
  type SupervisorEvent,
  type SupervisorNotify,
} from './project-switcher.js';

export {
  MemoryRpcError,
  // T-070
  memoryGet,
  // T-071
  memoryAppendProjectChange,
  // T-072
  memoryAppendSessionFact,
  // T-073
  memoryCompact,
  // T-074
  memorySwitchProject,
  // T-075
  memoryList,
  // R-MEM-1
  memoryFind,
  // R-MEM-2
  memoryConsolidate,
  memoryForget,
  memoryStats,
  // R-MEM-3
  memoryShareToSubagent,
  memoryReadTeamMemory,
  memoryPromoteFromSubagent,
  // R-MEM-4
  memoryUpsertSkill,
  memoryRecordSkillOutcome,
  memoryFindSkill,
  // R-MEM-5.1
  memoryAppendImage,
  // R-MEM-5.2
  memoryRecordProvenance,
  memoryVerifyChain,
  // R-MEM-5.3
  memoryFindByType,
  // R-MEM-6.2
  memoryVerifySignedChain,
  // R-MEM-6.4
  memoryRecordRetrievalOutcome,
  memoryGetRetrievalFeedbackStats,
  type MemoryGetParams,
  type MemoryAppendProjectChangeParams,
  type MemoryAppendSessionFactParams,
  type MemoryCompactParams,
  type MemoryCompactResult,
  type MemorySwitchProjectParams,
  type MemorySwitchProjectResult,
  type MemoryListParams,
  type MemoryFindParams,
  type MemoryFindResult,
  type MemoryFindHit,
  type MemoryConsolidateParams,
  type MemoryConsolidateResult,
  type MemoryForgetParams,
  type MemoryForgetResult,
  type MemoryStatsParams,
  type MemoryStatsResult,
  type MemoryShareToSubagentParams,
  type MemoryShareToSubagentResult,
  type MemoryReadTeamMemoryParams,
  type MemoryReadTeamMemoryResult,
  type MemoryPromoteFromSubagentParams,
  type MemoryPromoteFromSubagentResult,
  type MemoryUpsertSkillParams,
  type MemoryUpsertSkillResult,
  type MemoryRecordSkillOutcomeParams,
  type MemoryRecordSkillOutcomeResult,
  type MemoryFindSkillParams,
  type MemoryFindSkillResult,
  type MemoryFindSkillHit,
  type MemoryAppendImageParams,
  type MemoryAppendImageResult,
  type MemoryRecordProvenanceParams,
  type MemoryRecordProvenanceResult,
  type MemoryVerifyChainParams,
  type MemoryVerifyChainResult,
  type MemoryFindByTypeParams,
  type MemoryFindByTypeResult,
  type MemoryVerifySignedChainParams,
  type MemoryVerifySignedChainResult,
  type MemoryRecordRetrievalOutcomeParams,
  type MemoryRecordRetrievalOutcomeResult,
  type MemoryGetRetrievalFeedbackStatsResult,
} from './rpc.js';

// R-MEM-1: vector store + embedding providers.
export {
  VectorStore,
  cosineSimilarity,
  type VectorScope,
  type VectorRow,
  type VectorSearchParams,
  type VectorSearchResult,
  type MemoryType,
} from './vec-store.js';

export {
  HashEmbeddingProvider,
  RemoteEmbeddingProvider,
  DEFAULT_EMBEDDING_DIM,
  type EmbeddingProvider,
  type RemoteEmbeddingOptions,
} from './embedding/index.js';

// R-MEM-2: evolution (consolidation + forgetting).
export {
  consolidateProjectChanges,
  forgetProjectChanges,
  vacuumForgottenChanges,
  restoreForgottenChange,
  readMemoryStats,
  touchAccess,
  touchVectorStoreHits,
  type ConsolidateOptions,
  type ConsolidateResult,
  type ForgetResult,
} from './evolution.js';

export {
  softDeleteProjectChange,
  restoreProjectChange,
  vacuumSoftDeletedChanges,
  markConsolidatedInto,
  touchChangeAccess,
  findForgetCandidates,
  readProjectMemoryStats,
  type ProjectMemoryStats,
  type ForgetPolicy,
} from './project-store.js';

// R-MEM-4: skill memory.
export {
  upsertSkill,
  incrementSkillSuccess,
  recordSkillFailure,
  getSkill,
  listSkills,
  countSkills,
  deleteSkill,
  findSkillsByText,
  type SkillRow,
  type SkillInput,
  type SkillSearchOptions,
  type SkillSearchHit,
} from './skill-store.js';

// R-MEM-5.1: multimodal image memory helpers.
export { sha256File, sha256Buffer, embedTextForImage } from './image-hash.js';

// R-MEM-5.2: provenance chain (F7 trust).
export {
  appendProvenance,
  appendProvenanceSigned,
  readChain,
  readChainForEntry,
  countChain,
  dropChain,
  verifyChain,
  verifySignedChain,
  contentHash,
  type ProvenanceRow,
  type VerifyChainResult,
  type VerifySignedChainResult,
  type SignedProvenanceResult,
} from './provenance-store.js';

// R-MEM-6.2: Ed25519 signing helpers.
export {
  signRow,
  verifyRowSignature,
  getSignerPublicKeyHex,
  isEphemeralKey,
  signingInput,
  _resetSigningKeyForTests,
} from './signing.js';

// R-MEM-6.3: type-specific ranking (F8+ boost).
export {
  rankByType,
  shouldApplyTypeRanking,
  type RankByTypeParams,
  type RankedVectorRow,
} from './ranking.js';

// R-MEM-6.4: retrieval feedback (F3 RL, lightweight).
export {
  recordRetrievalOutcome,
  bumpUsed,
  bumpNotUsed,
  getFeedback,
  getFeedbackBulk,
  readFeedbackStats,
  type RetrievalFeedbackRow,
  type FeedbackStats,
} from './feedback-store.js';

// T-090 ~ T-093: CLI surface. The `ac-mem` bin in package.json
// resolves to `dist/cli.js`; library consumers can also import
// `runCli` / `runCliAsync` / `parseArgs` directly to drive the
// same dispatcher programmatically (e.g. from the desktop
// app's "Memory" panel).
export {
  runCli,
  runCliAsync,
  parseArgs,
  main,
  CliUsageError,
  HELP_TEXT,
  type CliArgs,
  type CliResult,
  type CliStreams,
  type CliDeps,
  type MemorySubcommand,
  type SpawnResult,
} from './cli.js';

// T-507: cross-platform 0600 helper. Mirrors the Java
// SecureFilePermissions in aethercode-core. The write
// paths in jsonl-writer.ts and memory-store.ts call
// this automatically; library consumers can also call
// it directly when writing custom user-state files.
export {
  SECURE_FILE_MODE,
  OWNER_RW_ONLY,
  isPosixSupported,
  chmodOwnerReadWriteOnly,
  chmodOwnerReadWriteOnlyAsync,
  chmodDirectoryOwnerOnlyAsync,
  type PermissionWarn,
} from './security/permissions.js';

export { BankClient, BankClientError } from './bank-client.js';
export type { BankUnit, BankStats } from './bank-client.js';
export {
  DEFAULT_BANK_URL,
  resolveBankUrl,
  makeBankClient,
  formatBankStats,
  formatRecall,
  readBankStats,
  readBankRecall,
  type BankSummary,
} from './bank-recall.js';
export {
  unitConfidence,
  aggregateReport,
  formatSelfEvalReport,
  auditSelfEval,
  type SelfEvalAuditReport,
  type SelfEvalAuditSummary,
  type KindAudit,
} from './self-eval-audit.js';
