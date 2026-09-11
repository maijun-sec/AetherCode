# R-eval Master Plan — AetherCode AI Agent Evaluation Suite (2026-09-12)

## 触发

`reference/papers/` 已有 8 篇 survey / paper，覆盖 agent architecture
+ memory + lifelong learning + multimodal，但缺一个核心 paper:

- **2503.16416 (Yehudai et al., 2025)** — *A Survey on Evaluation of
  LLM-based Agents*. 5 维度 (Core LLM capabilities / Application-specific
  benchmarks / Generalist agents / Benchmark dimensions / Frameworks),
  50+ 具体 benchmark 引用.

外加 2506.11102 (Evolutionary Perspectives) + 2604.00835 (Agentic Tool
Use Table 4) 等新 paper。

之前 R-radar-2 ~ R-radar-8 + R-orch-1 ~ R-orch-3 + R-bugfix-1 + R-perf-1
覆盖了 verifier / self-correct / multi-agent / cost ceiling / cache,
但 **AetherCode 前端实际调用的 SDK + Protocol + Tools 接口还没系统
eval 过**。R-eval 系列补齐这个 gap。

## 范围对齐：用户原始要求

1. 基础能力评估 (Survey §2)：规划、多步推理、任务分解、状态跟踪、
   自我纠正、因果理解、元规划
2. 各类记忆能力 (episodic / semantic / procedural)
3. 其他能力 (tool use / function calling)
4. 应用特定评估 (web / code / science / conversational)
5. 通用 Agents 评估
6. **AetherCode 前端/SDK/Protocol 接口** ← 重点

## 关键 paper 索引

| ArXiv ID | 标题 | 在 R-eval 哪 round 用 |
|---|---|---|
| 2503.16416 | Survey on Evaluation of LLM-based Agents | R-eval master / 全部 |
| 2506.11102 | Evolutionary Perspectives on the Evaluation of LLM-Based AI Agents | R-eval-1 / R-eval-5 |
| 2604.00835 | Agentic Tool Use in LLMs (Table 4 benchmark 列表) | R-eval-3 |
| 2608.04719 | Diagnosing Tool-Selection Reasoning with Canary Tools | R-eval-3 |
| 2602.16902 | LLM-WikiRace (long-term planning) | R-eval-1 |
| 2603.22862 | Evolution of Tool Use (long-horizon multi-tool) | R-eval-3 |
| 2607.23722 | Multi-Step Tool-Use in Real-World | R-eval-3 |
| 2508.09124 | OdysseyBench (office workflows) | R-eval-1 |
| 2608.29397 | AlgoWorlds (algorithmic tool use) | R-eval-3 |
| 2601.08173 | The Agent's First Day (workplace learning) | R-eval-4 |
| 2608.04719 | Tool-Decathlon (diverse, realistic, long-horizon) | R-eval-3 |
| 2508.17281 | From Language to Action (Llm Agents) — 已有 | R-eval-4 / R-eval-7 |
| 2601.01743 | AI Agent Systems Architectures — 已有 | R-eval-1 / R-eval-7 |
| 2512.13564 | Memory in the Age of AI Agents — 已有 | R-eval-2 |

## AetherCode 实际组件 + R-eval 映射

| AetherCode 组件 | 位置 | 评估 round |
|---|---|---|
| `DagPlan` (DAG plan + 拓扑排序) | aethercode-sdk | **R-eval-1** |
| `PlanExecutor` (执行 plan) | aethercode-sdk | R-eval-1 |
| `PlanClassifier` (分类 plan) | aethercode-sdk | R-eval-1 |
| `SchedulerBackedSubagentPool` (多 sub-agent) | aethercode-sdk | R-eval-7 |
| `WorktreeManager` (git worktree) | aethercode-sdk | R-eval-1 |
| `Watchdog` (silent run detection) | aethercode-sdk | **R-eval-5** |
| `CircuitBreaker` (熔断) | aethercode-sdk | **R-eval-5** |
| `BackpressureException` (背压) | aethercode-sdk | R-eval-5 |
| `BreakerGuardedPool` (池保护) | aethercode-sdk | R-eval-5 |
| `CachedStepExecutor` (缓存 step) | aethercode-sdk | R-eval-5 |
| `RetryHelper` / `RetryPolicy` (重试) | aethercode-sdk | **R-eval-4** |
| `SessionManager` (会话管理) | aethercode-sdk | R-eval-2 / R-eval-5 |
| `LayeredMemoryStore` (多层 memory) | aethercode-memory | **R-eval-2** |
| `FileBackedMemory` (文件持久化) | aethercode-memory | R-eval-2 |
| `MemoryMethods` (6 个 memory RPC) | aethercode-protocol | R-eval-2 / R-eval-6 |
| `AetherCodeMethods` (主 RPC 集合) | aethercode-protocol | R-eval-6 |
| `PermissionMethods` (permission RPC) | aethercode-protocol | **R-eval-8** |
| `TaskMethods` (task RPC) | aethercode-protocol | R-eval-6 |
| `GrantMethods` (grant RPC) | aethercode-protocol | R-eval-8 |
| `ContextMethods` (context RPC) | aethercode-protocol | R-eval-6 |
| `CompactMethods` (compact RPC) | aethercode-protocol | R-eval-2 |
| `ThemeMethods` (theme RPC) | aethercode-protocol | R-eval-6 |
| `EngineContinuationDispatcher` | aethercode-protocol | R-eval-6 |
| `JsonRpcDispatcher` (RPC 总线) | aethercode-protocol | R-eval-6 |
| `JsonRpcProtocolException` (错误) | aethercode-protocol | R-eval-6 |
| `FileReadTool` / `FileWriteTool` / `FileEditTool` | aethercode-tools | **R-eval-3** |
| `BashTool` / `BashToolR130` / `BashToolSandbox` | aethercode-tools | R-eval-3 |
| `LspServerLifecycle` / `LspMultiplexer` | aethercode-tools | R-eval-3 |
| `GoogleScholarClient` / `ScholarSearchTool` | aethercode-tools | R-eval-3 |
| `ArxivFetchTool` / `ArxivAtomParser` | aethercode-tools | R-eval-3 |
| `WebSearchTool` / `WebFetchTool` / `WebFetchToolSsrf` | aethercode-tools | R-eval-3 |
| `NotebookEditTool` / `ImageUnderstandTool` / `VideoUnderstandTool` | aethercode-tools | R-eval-3 |
| `AgentTool` / `SubagentRegistry` / `SubagentListTool` / `SubagentStatusTool` | aethercode-tools | R-eval-7 |
| `AgentRuntime` (V+self-correct+ensemble 集成) | aethercode-evals.orchestration | R-eval-4 |
| `Verifier` (5 校验器) | aethercode-evals.verifier | R-eval-4 |
| `SelfCorrectionLoop` (自纠) | aethercode-evals.selfcorrect | R-eval-4 |
| `MultiAgentOrchestrator` (multi-agent) | aethercode-evals.multiagent | R-eval-7 |
| `CostCeiling` / `ActionCache` / `TokenCounter` | aethercode-evals.perf | **R-eval-8** |
| `A2A` protocol server | aethercode-a2a | **R-eval-7** |
| `A2A-DeepAgent` bridge | aethercode-a2a-deepagent-bridge | R-eval-7 |

## 8 R-eval round 内容

### R-eval-1: Planning & Multi-Step Reasoning (~25 tests)

覆盖 Survey §2.1 + 2506.11102 + 2602.16902:
- Task decomposition (DagPlan 多步)
- State tracking (PlanExecutor)
- Long-horizon planning (DagPlan 拓扑 + cycle detection)
- Meta-planning (PlanClassifier)
- Causal understanding (DagPlan 依赖链推理)

测的 class: `DagPlan`, `PlanExecutor`, `PlanClassifier`, `DagPlan.Step`,
`WorktreeManager`

### R-eval-2: Memory (~30 tests)

覆盖 Survey §2.4 + 2512.13564:
- Episodic memory (跨 session 记 + 检索)
- Semantic memory (fact-based)
- Procedural memory (操作 pattern)
- Cross-session persistence
- Memory compaction (CompactMethods)
- Memory token accounting
- LayeredMemoryStore (project + session)

测的 class: `LayeredMemoryStore`, `FileBackedMemory`, `SessionMemoryStore`,
`MemoryMethods`, `CompactMethods`

### R-eval-3: Tool Use & Function Calling (~40 tests)

覆盖 Survey §2.2 + 2604.00835 (Table 4) + 2603.22862 + 2607.23722 +
2506.11102:
- Intent recognition (tool 是否被调用)
- Function selection (工具选择正确)
- Parameter mapping (参数映射正确)
- Multi-tool orchestration (多工具链)
- Multi-turn / stateful (ToolSandbox 风格)
- Tool sandboxing (permission + SSRF)
- Long-context tool use

测的 class: 全部 aethercode-tools, ScholarSearchTool, ArxivFetchTool,
WebSearchTool, WebFetchTool, BashTool sandbox, FileRead/Write/Edit

### R-eval-4: Self-Reflection & Self-Correction (~25 tests)

覆盖 Survey §2.3 + 2601.08173 + 2508.17281:
- Self-correction loop (SelfCorrectionLoop)
- 反思重规划 (Reflection-Bench 风格)
- 错误恢复 (verifier crash containment)
- 多策略 fallback (RetryStrategy + LlmSelfCorrectionStrategy +
  HumanCorrectionStrategy)
- Cost-aware retry (R-perf-1 CostCeiling)

测的 class: `SelfCorrectionLoop`, `RetryStrategy`,
`LlmSelfCorrectionStrategy`, `HumanCorrectionStrategy`, `Verifier`,
`AgentRuntime`

### R-eval-5: State Tracking & Causal Reasoning (~25 tests)

覆盖 Survey §2.1 + 2506.11102:
- Watchdog state machine (silent run detection)
- CircuitBreaker state transitions (CLOSED/OPEN/HALF_OPEN)
- Session state (SessionManager)
- Causal chain reasoning (DagPlan 因果链)
- Worktree 状态

测的 class: `Watchdog`, `CircuitBreaker`, `BreakerGuardedPool`,
`BackpressureException`, `SessionManager`, `CachedStepExecutor`

### R-eval-6: AetherCode JSON-RPC Interface Conformance (~30 tests)

覆盖 protocol/methods 9 个 endpoint + JsonRpcDispatcher:
- memory/* 6 endpoints (R-eval-2 复用)
- compact/* 
- context/*
- engine/continuation
- grant/* (R-eval-8 复用)
- permission/* (R-eval-8 复用)
- task/*
- theme/*
- 错误处理 / JsonRpcProtocolException
- Schema 验证 (参数 + 返回)
- 命名空间隔离 (aether/* vs flat)

测的 class: `MemoryMethods`, `CompactMethods`, `ContextMethods`,
`TaskMethods`, `ThemeMethods`, `EngineContinuationDispatcher`,
`JsonRpcDispatcher`, `JsonRpcProtocolException`, `AetherCodeMethods`

### R-eval-7: A2A Multi-Agent (~25 tests)

覆盖 2601.01743 §III.1.1 + 2508.17281:
- A2A protocol 消息格式 (JSON-RPC)
- A2A SSE streaming
- Multi-agent orchestration
- 远端 agent 路由
- A2A-DeepAgent 桥接
- Multi-agent 通信 / 角色分工

测的 class: `aethercode-a2a` 全部 + `aethercode-a2a-deepagent-bridge`
全部 + `MultiAgentOrchestrator` + `AgentTool` + `SubagentRegistry`

### R-eval-8: Cost-Efficiency, Safety & Robustness (~25 tests)

覆盖 Survey §5 (框架 / 安全 / 成本):
- Cost ceiling 早停 (CostCeiling)
- Token 估算 (TokenCounter)
- Cache 命中率 (ActionCache)
- Permission 拒绝路径 (PermissionMethods)
- Grant system (GrantMethods)
- Injection 防御 (BashTool sandbox + WebFetch SSRF)
- 错误恢复 / 韧性

测的 class: `CostCeiling`, `TokenCounter`, `ActionCache`,
`PermissionMethods`, `GrantMethods`, `BashToolR203ReadOnlyTest`,
`WebFetchToolSsrfTest`

## 累计目标 (8 R-eval round 收口)

- 新增 ~225+ Java tests
- 全部 eval framework 跑过
- 100% 覆盖 aethercode-sdk + aethercode-protocol + aethercode-tools 公共 API
- 每个 round 写 round-notes + commit
- master plan 收口

## 实施顺序

R-eval-1 → R-eval-2 → R-eval-3 → R-eval-4 → R-eval-5 → R-eval-6 →
R-eval-7 → R-eval-8 → 收口

每个 round 1 commit (amend 累加)。最后 push 留给用户。

## 后续

- 真实 LLM 集成 (替换 mock AgentFn) 走 E2E
- a2a-eval-1 ~ a2a-eval-N: 远端 agent 跨网调用
- 性能 round: 大量并发 / cache 命中率 / latency
- 真实用户 trace replay: 跑历史的 TUI session
