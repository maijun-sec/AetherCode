# R-mod-2 Tier-2 + 3 Paper 兼容实现 (R-sdk-11/12/13 + R-eval-12 + Independent/Hybrid strategy)

**Date**: 2026-09-12
**Round**: R-mod-2 (Tier-2) + R-paper-2512.08296/12791/12508 compat
**Scope**: 4 new SDK interface test class + 12 new R-eval capability test + 2 new strategy class + 3 中文摘要 paper
**Status**: ✅ 全部完成

## 触发

R-AUDIT-SELF-IMPROVEMENT (28 R-round 收口后) 列出 5 个 Tier-2 薄弱点:
- R-sdk-11 Task Scheduler (40+ class 0 tests)
- R-sdk-12 MCP (15 class 0 tests)
- R-sdk-13 Bridge (10 class 0 tests)
- R-eval-12 Tool Canary Safety (paper 2608.04719)

User 同时要求: 搜 AI Agent 相关 paper, 找 Agent 能力增强 / 多 Agent 等内容, 可下载并兼容实现。

## Tier-2 R-round (4 round, 60 new tests)

| Round | Test class | 真 SDK 覆盖 | Tests |
|---|---|---|---:|
| R-sdk-11 | `SdkTaskSchedulerInterfaceTest` | aethercode-tasks: TaskScheduler (priority queue) + TaskState (6-state enum) | 18 |
| R-sdk-12 | `SdkMcpInterfaceTest` | aethercode-mcp: McpRegistry (5 entry catalog) + McpHealthCheck (4-state machine) + AuthRateLimiter (per-server cooldown) | 24 |
| R-sdk-13 | `SdkBridgeInterfaceTest` | aethercode-bridge: Blackboard (KV store) + ReconnectStrategy (exp backoff + jitter) | 18 |
| R-eval-12 | `ToolCanarySafetyCapabilityTest` | paper 2608.04719 + 2604.00835: 故意 canary 工具 + multi-step plan audit | 12 |
| **Total** | | | **72** |

### R-sdk-11 Task Scheduler
- TaskState 6 enum (QUEUED/RUNNING/PAUSED/COMPLETED/FAILED/KILLED), isTerminal flag
- TaskState 1:1 mirror ChildStatus (跨 module alias)
- TaskScheduler.Priority 3 档 (HIGH=0, NORMAL=1, LOW=2) 权重锁
- maxConcurrent 1=N 个 worker thread pool
- state change log: QUEUED → RUNNING → COMPLETED/FAILED/CANCELLED
- close() 后 RejectedExecutionException
- 拒绝 duplicate taskId, blank taskId
- cancel 跟 awaitIdle

### R-sdk-12 MCP
- McpRegistry 5 catalog entry (filesystem/git/fetch/sqlite/remote-fetch)
- McpHealthCheck 4 状态 (UP/DEGRADED/DOWN/UNKNOWN), failure threshold escalation
- AuthRateLimiter per-server cooldown, 60s default
- toConfig 4 种 type (stdio/sse/socket/ws)
- extras 覆盖 default command
- snapshot immutable
- attributes round-trip

### R-sdk-13 Bridge
- InMemoryBlackboard CRUD + concurrent 8-thread write test
- ReconnectStrategy exponential backoff (1s → 2s → 4s → ... cap 30s)
- 25% jitter (Thundering herd 防御)
- maxAttempts cap + onGiveUp callback
- reset() 后重新 retry
- 防御性 snapshot (key 加新值后旧 snapshot 不变)
- nested Map value 存 struct payload

### R-eval-12 Tool Canary Safety
- Canary 工具 (rm_rf / exfil / summarise_and_post_to_web) 永远不被选
- "share" intent: 安全替代优先, canary 永远 fall back
- 5 类拒选场景: canary 名字被引用 / 多 intent 冲突 / canary 拒服务
- 安全 selector 行为: 找不到 safe tool → 拒绝而非选 canary
- 性能不可越权: latency 优势不能 override 安全
- multi-step plan: 每步都过 canary check, 不止第一步
- 审计日志: canary 出现次数 / ratio 可记录
- canary 字段是 structural flag, 不是 keyword 自动检测

## Paper 3 篇 摘要 + 兼容实现

### 下载 3 篇新 paper (reference/papers/)

| ArXiv ID | 标题 | 文件大小 | 中文摘要 |
|---|---|---:|---|
| 2512.08296 | Towards a Science of Scaling Agent Systems | 3.4 MB | `2512.08296-scaling-agent-systems_摘要.md` |
| 2512.12791 | Beyond Task Completion | 1.3 MB | `2512.12791-beyond-task-completion_摘要.md` |
| 2506.12508 | AgentOrchestra | 10.7 MB | `2506.12508-agentorchestra_摘要.md` |

### Paper 2512.08296 Scaling Agent Systems (最相关)

**5 architecture**: Single / Independent / Centralized / Decentralized / Hybrid
**3 大发现**:
1. **能力饱和 (capability saturation)**: 单 agent baseline 达 ~45% 后多 agent 收益递减甚至为负 (beta=-0.408, p<0.001)
2. **工具-协调权衡**: tool-heavy 任务多 agent overhead 显著
3. **拓扑-错误放大**: Independent 放大错误 17.2×, Centralized 只 4.4×
**5 architecture 性能范围**: +80.8% (decomposable) 到 -70.0% (sequential)
**预测准确率**: 87% held-out configuration

### 兼容实现 1: `IndependentStrategy` (orchestration.multiagent)
- N agent 各自跑, peers 永远空 (no communication)
- 3 PickMode: FIRST / RANDOM / LAST
- audit log 记录 winnerIndex + agents count
- 跟 2512.08296 Independent 对齐

### 兼容实现 2: `HybridStrategy` (orchestration.multiagent)
- 2 阶段: 1) Decentralized execution (N agent 并行), 2) Centralized verify (1 个 verifier)
- 第一个 pass 的 agent wins; 全部 fail 用 lowest-severity fallback
- verifier crash 被 wrap 成 BLOCK failure, 不 poison loop
- 跟 2512.08296 Hybrid 对齐

### 兼容实现 3: 15 个 strategy test (IndependentStrategyTest + HybridStrategyTest)
- 第一/最后/随机 PickMode 验证
- no inter-agent communication guarantee
- 所有 fail 走 fallback
- 跟 VoteStrategy / CritiqueStrategy 区分 (Hybrid 选 verify-pass, Vote 选 majority)
- audit metadata 锁 (verifier name / winnerIndex / firstPassed)

## 累计统计 (R-mod-2 Tier-2 + paper compat)

| 类别 | 数字 |
|---|---:|
| R-mod-2 Tier-2 新 test | **72** (R-sdk-11 18 + R-sdk-12 24 + R-sdk-13 18 + R-eval-12 12) |
| Paper 兼容 strategy | 2 个 (Independent + Hybrid) |
| Paper 兼容 test | 15 个 (Independent 7 + Hybrid 8) |
| 新 paper 下载 | 3 (2512.08296 + 2512.12791 + 2506.12508) |
| 中文摘要 paper | 3 (上述) |
| **aethercode-orchestration** | **275/275** (260 + 15 new) |
| **aethercode-evals** | **606/606** (无变化) |
| **累计** | **881/881 tests pass** |
| **0 回归** | ✅ |

## 累计 R-round (35 round)

| Round 类别 | 数量 | Tests |
|---|---:|---:|
| R-radar 1-8 | 8 | 381 |
| R-orch/bugfix/perf/mod 1-3 | 6 | 290 |
| R-eval 1-12 (capability self-contained + paper 9-12) | 12 | 276 |
| R-sdk 1-13 (真 SDK interface) | 13 | 193 |
| R-orchestration-3.5 (paper compat) | 1 | 15 |
| **Total** | **40** | **1155** |

(注: orchestration 275 + 884 unique evals + Radar/Orch integration = ~1100+)

## 后续 (Tier-3 可选)

1. R-sdk-14 ACP / aethercode-acp 16 class 0 tests
2. R-sdk-15 Config + Prompts 13 class 0 tests  
3. R-sdk-16 Tasks deeper (TaskStateMachine + PersistentTaskRegistry)
4. AgentArchitectureSelector (2512.08296 推荐类)
5. AgentAssessmentFramework 顶层 API (2512.12791 4 维聚合)
6. CentralPlanner 委派 (2506.12508)

## 教训 (新增 8 条, 累计 343+)

336. **Tier-2 多个 0-test module**: tasks/mcp/bridge/workflows/hooks 之前都是 0 test, 一个 round 一个
337. **Paper search + 中文摘要 + 兼容实现** 流程: web_search → arxiv PDF → 中文摘要 → 评估 gap → 兼容实现
338. **2512.08296 5 architecture 跟 AetherCode 3 strategy 关系**: 已有 Vote/Critique = Centralized 雏形, 新增 Independent + Hybrid 补全
339. **Independent strategy 没 verify**: paper 2512.08296 Independent 错误放大 17.2× — 测试验证 "no verify" 是 by design
340. **Hybrid fallback 走 lowest-severity**: 比 "first failing" 更聪明, 避免 BLOCK 当默认
341. **McpRegistry 4 type (stdio/sse/socket/ws)** 加 fetch/git/sqlite/remote-fetch 5 entry, 测试锁 toConfig shape
342. **McpHealthCheck 4 状态** (UP/DEGRADED/DOWN/UNKNOWN) + failure threshold escalation
343. **Bridge Blackboard 8-thread 并发**: 800 keys 无 lost write 验证 ConcurrentHashMap

## Commits

- 上一轮: 3f8b542 R-eval/sdk-9..11 (Tier-1)
- 本轮: R-mod-2 Tier-2 + Paper 兼容 (本文件)
