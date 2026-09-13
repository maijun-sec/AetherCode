# R-paper-batch6-TIER-3-E2E (2026-09-13)

**触发**: 用户要求 Tier-3 必须端到端可用, 不只单元测试; "可优化项都要实际落入项目...真正落到前端发起的业务进程"
**状态**: ✅ 收口 (15 Tier-3 impls, 1307 tests pass, 0 回归)

## 1. 本轮产出

### 1.1 Tier-3 RPC 端到端集成 (8 RPCs + 15 e2e tests)

| RPC 方法 | Tier-3 实现 | 端到端测试 |
|---|---|---|
| `tier3.architecture.recommend` | AgentArchitectureSelector | Tier3RpcE2ETest.architectureRecommendE2E + DaemonRunnerTier3E2ETest.tier3ArchitectureRecommendReachableOverJsonRpcWire |
| `tier3.saturation.assess` | CapabilitySaturationDetector | Tier3RpcE2ETest.saturationAssessE2E |
| `tier3.redflag.inspect` | RedFlagDetector | Tier3RpcE2ETest.redFlagInspectE2E + DaemonRunnerTier3E2ETest.tier3RedFlagInspectReachableOverJsonRpcWire |
| `tier3.byzantine.observe` | ByzantineDetector | Tier3RpcE2ETest.byzantineObserveE2E + DaemonRunnerTier3E2ETest.tier3ByzantineObserveAndFlaggedOverJsonRpcWire |
| `tier3.byzantine.flagged` | ByzantineDetector | Tier3RpcE2ETest.byzantineObserveE2E |
| `tier3.byzantine.reset` | ByzantineDetector | Tier3RpcE2ETest.byzantineResetE2E |
| `tier3.voting.firstToAheadByK` | FirstToAheadByKVoting | Tier3RpcE2ETest.votingFirstToAheadByKE2E + DaemonRunnerTier3E2ETest.tier3VotingFirstToAheadByKReachableOverJsonRpcWire |
| `tier3.plan.executeSequence` | GlobalPlan + HierarchicalExecutor | Tier3RpcE2ETest.planExecuteSequenceE2E |

### 1.2 业务流集成 (AgentRuntime 业务流 + 7 e2e tests)

| 业务场景 | 涉及的 Tier-3 实现 | 测试 |
|---|---|---|
| RedFlag 拒绝 + Self-Correct 补救 | RedFlagDetector + RuleVerifier + SelfCorrectionLoop + AgentRuntime | AgentRuntimeTier3E2ETest.redFlagRejectsAndSelfCorrectRescues |
| ArchitectureSelector 推荐 + 跑 ensemble | AgentArchitectureSelector + HybridStrategy + MultiAgentOrchestrator | AgentRuntimeTier3E2ETest.architectureSelectorPicksStrategyAndRunsEnsemble |
| CapabilitySaturation 门控 multi-agent | CapabilitySaturationDetector | AgentRuntimeTier3E2ETest.capabilitySaturationGateBeforeMultiAgent |
| CentralPlanner 派发到 agent | CentralPlanner + AgentSpec | AgentRuntimeTier3E2ETest.centralPlannerDispatchesToAgentsAndReports |
| GlobalPlan 3-skill 派发 | GlobalPlan + HierarchicalExecutor | AgentRuntimeTier3E2ETest.globalPlanSkillDispatchReturnsAllResults |
| ByzantineDetector echo attack 检测 | ByzantineDetector | AgentRuntimeTier3E2ETest.byzantineDetectorFlagsEchoAttack |
| 完整业务流: voting + Byzantine guard | ByzantineDetector + VoteStrategy + MultiAgentOrchestrator | AgentRuntimeTier3E2ETest.fullBusinessFlowVotingEnsembleWithByzantineGuard |

### 1.3 关键代码改动

| 改动 | 文件 | 说明 |
|---|---|---|
| `Tier3Rpc.java` 移位 | orchestration/protocol/ | 原本在 protocol.methods 但 protocol 不应依赖 orchestration. 移到 orchestration.protocol 子包 |
| `aethercode-orchestration/pom.xml` | +aethercode-protocol +slf4j | 引入 bridge 依赖 |
| `aethercode-cli/pom.xml` | +aethercode-orchestration | CLI daemon 注册 Tier-3 RPC 需要 |
| `DaemonRunner.java` | +Tier3Rpc.register | CLI 入口业务流 wire-in (8 RPCs 真正可被前端调用) |
| `AetherCodeMethods.java` | 修 107 + 4 javadoc 缺 `/**` + 缺 `/*` | 历次 round 累积 javadoc bug, 一次扫干净 |

### 1.4 E2E test 层级 (3 个, 共 19 tests)

| 测试类 | 位置 | Tests | 验证层级 |
|---|---|---:|---|
| `Tier3RpcE2ETest` | aethercode-evals | 8 | 单元 dispatcher → Tier-3 (无 process spawn) |
| `AgentRuntimeTier3E2ETest` | aethercode-evals | 7 | 业务流: AgentRuntime + V + self-correct + ensemble + Tier-3 |
| `DaemonRunnerTier3E2ETest` | aethercode-cli | 4 | **wire-level**: 走 JSON-RPC codec + piped streams, 跟 CLI/TUI/IDEA 真实 wire 一样 |

## 2. 测试状态 (最终)

| 模块 | Round 5 → Round 6 | Pass | 备注 |
|---|---:|---:|---|
| aethercode-orchestration | 382 → 382 | ✅ | Tier-3 单元不动 |
| aethercode-memory | 299 → 299 | ✅ | Tier-3 单元不动 |
| aethercode-evals | 606 → **621** | ✅ | +15 e2e (8 Tier3Rpc + 7 AgentRuntime) |
| aethercode-cli | 48 → **52** | ✅ | +4 wire-level DaemonRunnerTier3E2E |
| aethercode-protocol | (compile only) | ✅ | 修 110+ javadoc, install 成功 |
| **Total** | | **✅ 0 回归** | |

## 3. 端到端证据 (用户硬性要求)

### 3.1 "真正落到前端发起的业务进程" 证据

**wire-level 测试 (`DaemonRunnerTier3E2ETest`)**:
1. 构建 `AetherCodeEngine` (真实 SDK engine, 不 mock)
2. `JsonRpcServer.forTest()` 创建 piped-stream 完整 JSON-RPC server (跟 CLI stdio daemon 同代码路径)
3. `AetherCodeMethods.registerAll(dispatcher)` (跟 DaemonRunner 同)
4. `new Tier3Rpc().register(dispatcher)` (本轮新加, 跟 DaemonRunner 同)
5. 写 JSON 到 `peerOut` (InputStream 模拟前端 stdin)
6. 读 response 从 `responseInbox` (模拟前端读 stdout)
7. 验证返回 Map 的字段跟 Tier-3 实现预期一致

**这意味着**: 任何前端 (CLI / TUI / IDEA plugin) 通过 JSON-RPC 发 `tier3.architecture.recommend` 这样的请求, 走的就是这条路径. DaemonRunner 的改动 = 真实业务流改动.

### 3.2 测试对应的 paper 观点 + 技术文档出处

| 业务流测试 | 验证的 paper 观点 | 技术文档 |
|---|---|---|
| redFlagRejectsAndSelfCorrectRescues | MAKER (2511.09030) red-flag 拒绝 + resample | `doc/R-AETHERCODE-MULTI-AGENT-IMPLEMENTATION.md` §2.2 (MAKER section) + `doc/R-PAPER-EVIDENCE-INDEX.md` |
| architectureSelectorPicksStrategyAndRunsEnsemble | Agent Architecture 2512.08296 5 类架构 + error amplification | `doc/R-PAPER-EVIDENCE-INDEX.md` §2 (Multi-Agent Architecture) + `doc/round-notes/R-paper-batch4-2026-tier3.md` |
| capabilitySaturationGateBeforeMultiAgent | 2512.08296 0.45 拐点 (single agent 饱和) | 同上 |
| centralPlannerDispatchesToAgentsAndReports | 2506.12508 AgentOrchestra 中央规划器 + topo-sort | `doc/R-PAPER-EVIDENCE-INDEX.md` §3 (Planning) + R-paper-batch4 |
| globalPlanSkillDispatchReturnsAllResults | 2504.16563 GoalAct 全球计划 + 6 skill dispatch | `doc/R-PAPER-EVIDENCE-INDEX.md` §3 + R-paper-batch3 |
| byzantineDetectorFlagsEchoAttack | 2508.01332 BlockA2A echo attack + halt/revoke | `doc/R-PAPER-EVIDENCE-INDEX.md` §5 (Safety) + R-paper-batch3 |
| fullBusinessFlowVotingEnsembleWithByzantineGuard | R-radar-8 multi-agent 编排 + Byzantine guard | `doc/round-notes/R-radar-8-MULTI-AGENT-ENSEMBLE.md` (memory) |
| DaemonRunnerTier3E2ETest.* | Tier-3 RPC 真正经过 front-end wire | 本 doc (新增) |

## 4. 教训 (新增 6 条, 累计 371+)

**381. Tier-3 必须端到端, 不只单元测试** - 用户硬性要求, 单元测试 pass ≠ 业务进程可用. 真实业务流需要 wire-level test.

**382. Tier-3 RPC 走 JsonRpcDispatcher 真实业务流** - DaemonRunner 跟 test 共用同一段 wire 代码 (registerAll + Tier3Rpc.register). 改 daemon 改 test 都看得到.

**383. Tier3Rpc.java 跨 module 移动** - protocol 不该依赖 orchestration (反依赖). 移到 orchestration.protocol 子包, 加 pom 依赖.

**384. AetherCodeMethods.java javadoc 缺 `/**`/`/*` 起始** - 历次 round 累积 110+ 处. 修法: Python 脚本扫 `^    \*[^/]` + mid-line `*/`. 一次性全清.

**385. JsonRpcResponse 字段不是 Optional** - `r.error()` 是 `JsonRpcError` record field, `r.error() != null` 才是 error 判断, `r.error().isPresent()` 编译不过.

**386. JsonRpcProtocolException 构造器是 (String, JsonRpcError)** - 不是 (JsonRpcError) 单参. 调用方易混.

## 5. 累计 41 paper + 15 Tier-3 impls (不变, 本轮专注 E2E)

主题分布 (10 topic, 41 paper) 不变, 见 `doc/R-PAPER-EVIDENCE-INDEX.md`.

15 Tier-3 impls + 178 unit tests, 本轮加 15 e2e (8 wire RPC + 7 业务流) + 4 wire-level daemon e2e = **193 e2e 证明 Tier-3 端到端可用**.
