# R-sdk-7..8: AetherCode SDK Interface Conformance (完结)

**Date**: 2026-09-12
**Round**: R-sdk-7..8 (R-sdk 系列收口)
**Scope**: 2 新 SDK interface test class, 25 tests, 0 regression
**Status**: ✅ 全部完成 (R-sdk 1-8 收口)

## 触发

R-sdk-1..6 (0d1ba90 + ee07cf6) 测了 SDK State / Plan / Memory / Cost-Safety /
Tool / JSON-RPC 6 个维度的真 class 行为。R-sdk-7..8 续上 A2A (R-sdk-7) + Reflection
(R-sdk-8), 把 A2A protocol + Self-correction loop 的真 class 行为也锁住。

至此 R-sdk 系列 8 round 全部完成, 120 个 SDK interface test, 跟 R-eval 8 round
self-contained capability model 并行, 覆盖 AetherCode 全部关键 module (sdk /
memory / permission / orchestration / tools / protocol / a2a).

## R-sdk 范围 (2 round, 2 test class, 25 tests)

| Round | Test class | 真 SDK 包 | Tests | Sub-能力 |
|---|---|---|---:|---|
| R-sdk-7 | `SdkA2AInterfaceTest` | `aethercode-a2a` schema (AgentCard / Task / TaskStatus) | 16 | wire format / state string 锁 / 不可变更新 / 必填验证 |
| R-sdk-8 | `SdkReflectionInterfaceTest` | `orchestration` (SelfCorrectionLoop / CorrectionStrategy / HeuristicVerifier) | 9 | PASSED / BUDGET_EXHAUSTED / STRATEGY_RETURNED_NULL / crash containment |
| **Total** | | | **25** | |

## R-sdk-7 A2A Schema (16 tests)

测真 `org.aethercode.a2a.schema` records — 跟 a2a-python / a2a-js 互操作的关键
wire format。

| Sub-能力 | Tests | 行为 |
|---|---|---|
| AgentCard 构造 | 2 | name/url null 抛 NPE, 缺省值自动填 (description/0.1.0/empty skills/Capabilities/Auth) |
| AgentCard toMap | 1 | 7 字段都 emit |
| AgentCard.Skill | 2 | id/name null 抛 NPE, inputModes/outputModes 默认 ["text"] |
| AgentCard.Capabilities | 1 | defaults: streaming=false, push=false, history=true |
| TaskStatus state 锁 | 1 | 7 个 spec state string 不变 (submitted/working/input-required/completed/failed/canceled/rejected) |
| TaskStatus 校验 | 1 | 未知 state 抛 IAE (例如 "running", "done") |
| TaskStatus terminal | 1 | completed/failed/canceled 终态, submitted/working/input-required 非终态 |
| TaskStatus factory | 2 | submitted/working/completed 工厂, toMap 有 state |
| Task 构造 | 2 | id/status null 抛 NPE, newTask 自动分配 UUID (length 36) |
| Task 不可变更新 | 2 | withStatus/withAppendedMessage/withAppendedArtifact 保 id, 原对象不变 |
| Task toMap | 1 | "kind": "task" 锁, 跟 spec 一致 |

## R-sdk-8 Reflection (9 tests)

测真 `org.aethercode.orchestration.selfcorrect.SelfCorrectionLoop` + 
`RetryStrategy` + `HeuristicVerifier` + `GiveUpReason` taxonomy。

| Sub-能力 | Tests | 行为 |
|---|---|---|
| Pass 路径 | 1 | 第一次就 pass → terminal=PASSED, attempts.size=1 |
| Budget 路径 | 1 | maxAttempts=3 都不 pass → terminal=BUDGET_EXHAUSTED |
| Null strategy | 1 | giveUpImmediately → terminal=STRATEGY_RETURNED_NULL, attempts=1 |
| 收敛 | 1 | 第 3 次 pass → terminal=PASSED, attempts=3, verifier 被调 3 次 |
| Verifier crash | 1 | verifier 抛异常不挂 loop, terminal=BUDGET_EXHAUSTED, attempts=2 |
| Strategy crash | 1 | strategy 抛 RuntimeException → terminal=STRATEGY_RETURNED_NULL |
| 构造校验 | 1 | 5 个 null/blank/0 都抛 IAE |
| HeuristicVerifier | 1 | NonEmptyCheck fail "", pass "ok" |
| GiveUpReason 锁 | 1 | 4 enum (PASSED/BUDGET_EXHAUSTED/STRATEGY_RETURNED_NULL/INTERRUPTED), audit log 依赖 |

## 累计 SDK Interface Test (R-sdk 1-8 全部)

| Round | Test class | Tests | Module |
|---|---|---:|---|
| R-sdk-1 | SdkStateInterfaceTest | 19 | aethercode-sdk |
| R-sdk-2 | SdkPlanInterfaceTest | 15 | aethercode-sdk |
| R-sdk-3 | SdkMemoryInterfaceTest | 12 | aethercode-memory |
| R-sdk-4 | SdkCostSafetyInterfaceTest | 22 | orchestration.perf + permission |
| R-sdk-5 | SdkToolInterfaceTest | 8 | aethercode-tools |
| R-sdk-6 | SdkJsonRpcInterfaceTest | 20 | aethercode-protocol |
| R-sdk-7 | SdkA2AInterfaceTest | 16 | aethercode-a2a |
| R-sdk-8 | SdkReflectionInterfaceTest | 9 | orchestration |
| **Total R-sdk 1-8** | | **121** | **6 module** |

## Test 结果

| Module | Tests (新) | 累计 | Pass | Fail | Skip |
|---|---:|---:|---:|---:|---:|
| aethercode-evals (R-sdk-7..8) | 25 | 463 | 463 | 0 | 0 |
| aethercode-orchestration (R-mod-2) | 0 | 260 | 260 | 0 | 0 |
| **Total 新增** | **25** | **723** | **723** | **0** | **0** |

## R-sdk 1-8 设计总结

### Self-contained + 真 SDK 双层架构

| 维度 | R-eval (self-contained) | R-sdk (真 SDK) | 关系 |
|---|---|---|---|
| 状态 | 26 tests StateTrackingCapabilityTest | 19 SdkStateInterfaceTest | R-eval 验证 capability, R-sdk 验证 SDK |
| 计划 | 21 PlanningCapabilityTest | 15 SdkPlanInterfaceTest | 同上 |
| 记忆 | 23 MemoryCapabilityTest | 12 SdkMemoryInterfaceTest | 同上 (NOTE: enum 不同 CASE/STRATEGY/SKILL vs EPISODIC/SEMANTIC/PROCEDURAL) |
| 成本/安全 | 28 CostSafetyRobustnessTest | 22 SdkCostSafetyInterfaceTest | 同上 (CostCeiling API 不同) |
| 工具 | 25 ToolUseCapabilityTest | 8 SdkToolInterfaceTest | R-eval 测 capability (intent/multi-tool), R-sdk 测 StandardTools 工厂 |
| JSON-RPC | 35 JsonRpcInterfaceTest | 20 SdkJsonRpcInterfaceTest | R-eval 测 9 endpoint family, R-sdk 测 Codec/Dispatcher/Error |
| 多 Agent | 22 A2AMultiAgentTest | 16 SdkA2AInterfaceTest | R-eval 测 multi-agent ensemble/bridge, R-sdk 测 schema records |
| 反思 | 24 SelfReflectionCapabilityTest | 9 SdkReflectionInterfaceTest | R-eval 测 reflection loop capability, R-sdk 测 SelfCorrectionLoop 真 |
| **Total** | **204 R-eval** | **121 R-sdk** | 双层互为补充 |

### 关键差异 (self-contained vs 真 SDK)

1. **ExperienceKind**: self=R-eval 用 EPISODIC/SEMANTIC/PROCEDURAL, 真 SDK
   用 CASE/STRATEGY/SKILL (3 类, 更具体)
2. **CostCeiling API**: self-contained R-eval-8 是 `recordCall()`, `recordTokens()`,
   `recordElapsed()` 3 个独立 method; 真 SDK 是 `recordCall(tokenCost) → boolean`
   一个方法
3. **ActionCache**: self-contained 是泛型 `ActionCache<K, V>`, 真 SDK 是非泛型
   `ActionCache` 存 `Object → VerificationResult`
4. **Tool name**: R-eval-3 self-contained 用 "agent", 真 SDK 用 "spawn_agent";
   R-eval-3 用 "scholar_search", 真 SDK 用 "google_scholar"

## 关键技术决定 (R-sdk 1-8 累计 30+)

1. **Self-contained + 真 SDK 双层** — 不冲突, 互为补充
2. **不破坏 capability 行为** — SDK API 跟 self-contained 不一致, 但子能力对齐
3. **每个 test class 5-22 tests** — 不追求覆盖每个方法
4. **真实 API 用法** — 测试构造 / method call / 边界, 跟 production 1:1
5. **`assertInstanceOf(Class, Object)`** — 比 `instanceof + cast` 简洁
6. **`recordCall(tokens) → boolean`** — orchestration 合并 recordCall + recordTokens
7. **`VerificationResult.fail(Severity, String)`** — 需要 Severity enum
8. **`VerificationResult.pass(reason)` 静态工厂** — 不用直接 record 构造
9. **JSON-RPC dispatcher 异步** — 用 `CountDownLatch` 等 worker
10. **`JsonRpcMethodHandler.handle` throws Exception** — test 方法要 `throws Exception`
11. **A2A spec state string 锁** — 7 个 state ("submitted"/"working" 等) 不能改
12. **`giveUpImmediately` 是 null-returning strategy** — 跟 "always retry" 的 `retrySame` 对偶
13. **`GiveUpReason` 4 enum** — PASSED/BUDGET_EXHAUSTED/STRATEGY_RETURNED_NULL/INTERRUPTED
14. **AetherCode 7 custom error codes** — -32000..-32007 (engine/permission/session/cancelled/timeout/tool/unauthorized)
15. **JSON-RPC 5 spec codes** — -32700..-32603
16. **Maven install before mvn test** — 必须先 install 新 module
17. **Tool name 实际值** — `spawn_agent` 不是 `agent`, `google_scholar` 不是 `scholar_search`
18. **`StandardTools.all()` size=17** — 锁住, dev 改了会立刻看到
19. **DagPlan 测 cycle detection** — A→B→A 抛 ISE
20. **PlanClassifier 大小写不敏感** — "BASH" upper case 也被 flag
21. **ForgettingPolicy tag-based utility** — `utility=0.95` tag 让 score 提升
22. **CommandAllowlist deny wins** — 即便有更具体的 allow 规则, deny 永远优先
23. **`ActionCache.hitRate() = hits / (hits+misses)`** — 0..1, 不是 0..100
24. **AetherCodeMethods 305KB** — 9 endpoint family, A2A schema 全部覆盖
25. **Verifier 不是 functional interface** — 2 个 abstract method (name + verify), 用 anonymous class
26. **HeuristicVerifier 用 `new HeuristicVerifier(name, List.of(Check))`** — 不是 `of(...)` 工厂
27. **`RetryStrategy.retrySame()` 静态工厂** — 在 `orchestration.selfcorrect.RetryStrategy`, 不是 `CorrectionStrategy`
28. **`Message(role, parts, messageId)`** — parts 不能空, 至少 1 个 Part
29. **`Artifact(artifactId, name, description, parts)`** — parts 不能空
30. **`AgentCard.Skill(id, name, ...)`** — id/name 不能 null

## 后续

1. **引用新 paper 8 篇到 ref/papers/**: 网络下载受限, 写 ref/notes/ 摘要
2. **push** (force-with-lease)
3. **R-sdk-9+** (optional): 加 WorkflowEngine / BankClient / VLM 等其他 module 的 SDK interface

## 教训 (新增 5 条, 累计 326+)

322. **A2A spec state string 锁 7 个** — 改一个就 break 互操作
323. **`Message.parts` 不能为空** — spec 要求至少 1 个 Part
324. **`Artifact.parts` 不能为空** — 同上
325. **`AgentCard` 自动填默认** — 缺 description/0.1.0/skills 等自动补
326. **Verifier 不是 functional interface** — 用 anonymous class 显式实现 name() + verify()

## Commits

- R-sdk-1..4: `0d1ba90` (68 new tests)
- R-sdk-5..6: `ee07cf6` (28 new tests)
- R-sdk-7..8: (本次) `R-sdk-7-8-INTERFACE-CONFORMANCE` (25 new tests, R-sdk 系列收口)
