# R-sdk-1..4: AetherCode SDK Interface Conformance

**Date**: 2026-09-12
**Round**: R-sdk-1..4 (4 一次性 commit)
**Scope**: 4 新 SDK interface test class, 68 tests, 0 regression
**Status**: ✅ 全部完成

## 触发

R-eval 1-8 (204 tests, 8 round, R-eval-1 到 R-eval-8) 测 self-contained capability
model (Plan/PlanStep, MemoryEntry/MemoryStore, Tool/ToolRegistry, ReflectionLoop,
Watchdog/CircuitBreaker 等), 验证 capability 设计是否合理。

R-mod-2 (cd8f85a) 把 5 个 orchestration package 物理抽到 aethercode-orchestration
module, 让 orchestration 跟 evals 平级。

User 要求: "不要只测评 deepagents, 重点是 AetherCode 前端会请求到的相关接口
和SDK, 请完整提供相关的测评能力, 并且对这些能力薄弱的地方进行补齐"。

R-sdk 1-4 补齐: **直接测真 SDK class** (aethercode-sdk + aethercode-memory +
aethercode-permission + aethercode-orchestration.perf) — 跟前端 TUI / CLI / IDE
plugin 实际用的同一个 class。

## R-sdk 范围 (4 round, 4 test class, 68 tests)

| Round | Test class | 真 SDK 包 | Tests | Sub-能力 |
|---|---|---|---:|---|
| R-sdk-1 | `SdkStateInterfaceTest` | `org.aethercode.sdk` (Watchdog / CircuitBreaker / RetryPolicy) | 19 | silent-run / breaker state / backoff |
| R-sdk-2 | `SdkPlanInterfaceTest` | `org.aethercode.sdk` (DagPlan / PlanClassifier) | 15 | topo order / ready / complete / classifier |
| R-sdk-3 | `SdkMemoryInterfaceTest` | `org.aethercode.memory` (ExperienceRecord / ForgettingPolicy) | 12 | utility / withUse / weight normalise / tag-based utility |
| R-sdk-4 | `SdkCostSafetyInterfaceTest` | `org.aethercode.orchestration.perf` + `org.aethercode.permission` | 22 | cost ceiling 3 轴 / LRU / token counter / shell guard |
| **Total** | | | **68** | |

## R-sdk-1 SDK State (19 tests)

测真 `org.aethercode.sdk.Watchdog` + `CircuitBreaker` + `RetryPolicy`.

| Sub-能力 | Tests | 行为 |
|---|---|---|
| Watchdog 构造 | 4 | null clock/handler 抛 IAE, pollMs < 100 抛 IAE, timeoutMs < pollMs 抛 IAE |
| Watchdog 生命周期 | 2 | kick/close idempotent, 构造时 not tripped |
| CircuitBreaker state | 5 | defaults closed, N fail trips OPEN, cooldown→HALF_OPEN, success→CLOSED, fail-in-half-open→OPEN |
| CircuitBreaker 额外 | 2 | reset returns CLOSED, bad args throw |
| RetryPolicy backoff | 3 | NONE 不 retry, DEFAULT 指数 backoff, AGGRESSIVE 5 attempts |
| RetryPolicy validation | 1 | bad multiplier / maxAttempts 抛 IAE |
| Factory sanity | 1 | defaults() + DEFAULT/AGGRESSIVE/NONE 不为 null |

## R-sdk-2 SDK Plan (15 tests)

测真 `org.aethercode.sdk.DagPlan` + `PlanClassifier`。

| Sub-能力 | Tests | 行为 |
|---|---|---|
| DagPlan 构造 | 4 | null steps / dup id / unknown dep / cycle 抛 |
| DagPlan ready | 1 | topo order, ready steps 跟 completed 集合对应 |
| DagPlan complete | 1 | isComplete false until all done |
| DagPlan 边界 | 2 | empty plan trivially complete, get(id) lookup |
| PlanClassifier 决策 | 6 | trivial 准, bash 拒, 长 plan 拒, 大 max 准, 空 destructive 集合全准, 大小写不敏感 |
| DagPlan immutability | 1 | dependsOn list 在构造后外部 mutation 不影响 |

## R-sdk-3 SDK Memory (12 tests)

测真 `org.aethercode.memory.ExperienceRecord` + `ForgettingPolicy` +
`FileBackedMemory.MemoryItem`。

| Sub-能力 | Tests | 行为 |
|---|---|---|
| ExperienceRecord 构造 | 3 | blank id 自动生成, utility clamp 0..1, 负 uses clamp 0 |
| ExperienceRecord withUse | 1 | asymptotic to 1, monotone non-decreasing |
| ExperienceRecord immutability | 1 | tags 拷贝后外部 mutation 不影响 |
| ForgettingPolicy weights | 2 | defaults normalised, 自定义 weights 5/3/2 → 0.5/0.3/0.2 |
| ForgettingPolicy thresholds | 1 | shouldTombstone + shouldPrune 在边界正确 |
| ForgettingPolicy scoring | 2 | fresh score > stale, utility=0.95 tag lifts score |
| FileBackedMemory.MemoryItem | 1 | tags 拷贝后外部 mutation 不影响 |
| MemoryScope + ExperienceKind | 1 | 独立 enum, 自由组合 |

## R-sdk-4 SDK Cost / Safety (22 tests)

测真 `org.aethercode.orchestration.perf.CostCeiling` / `ActionCache` / `TokenCounter` +
`org.aethercode.permission.CommandAllowlist`。

| Sub-能力 | Tests | 行为 |
|---|---|---|
| CostCeiling 构造 | 1 | 0 axis 抛 IAE |
| CostCeiling 状态 | 3 | 初始 not exceeded, N call trips, M token trips |
| CostCeiling 完整 | 2 | 三轴 record, Builder |
| ActionCache | 3 | get/put, miss increments, getOrCompute 不重 call loader |
| TokenCounter | 4 | zero, charQuotient /4, of(fn), orElse fallback |
| CommandAllowlist 决策 | 5 | empty / null deny, exact/prefix/regex 各种匹配, deny wins |
| CommandAllowlist 配置 | 3 | defaultVerdict, safeDefaults factory, rules() unmodifiable |
| CommandAllowlist Decision | 1 | Decision record 静态工厂 |

## 设计原则

1. **Self-contained + 真 SDK 并行**: R-eval 1-8 (self-contained) 保留, R-sdk 1-4 (真 SDK) 新增
   - self-contained 验证 capability 设计
   - 真 SDK 验证实现
2. **不破坏 capability 行为**: SDK API 跟 self-contained 不完全一致 (例如
   ExperienceKind 是 CASE/STRATEGY/SKILL 不是 EPISODIC/SEMANTIC/PROCEDURAL),
   但子能力维度对齐
3. **每个 test class 5-22 tests**: 不追求覆盖每个方法, 验证核心 invariant
   (silent run detect, breaker trip, plan topo, utility clamp, cost ceiling,
   LRU eviction, deny wins)
4. **真实 API 用法**: 测试构造 / method call / 边界, 跟 production 代码
   调用方式 1:1, 验证 front-end 集成没问题

## 关键技术决定 (10 条)

1. **Self-contained + 真 SDK 双层**: R-eval 测 capability, R-sdk 测 SDK
2. **API 差异处理**: `ExperienceKind` 改名, `CostCeiling.recordCall(tokens)` 合并 recordCall+recordTokens,
   `ActionCache` 不是泛型存 `Object → VerificationResult`, `TokenCounter.of` 接受 `ToLongFunction`
3. **`record VerificationResult(boolean, Severity, String, Map)`** — 用静态工厂
   `pass(reason)` 代替直接构造
4. **不测 async Watchdog trip**: 测构造 / kick / close idempotent, 不真等真实时间
   (R-eval-5 self-contained 已经测了 silent run detect with mock clock)
5. **DagPlan 测 cycle detection**: A→B→A cycle 抛 ISE (topo sort fail)
6. **PlanClassifier 测 case-insensitive**: "BASH" upper case 也被 flag
   (default destructive set 是 lower case)
7. **ForgettingPolicy 测 tag-based utility**: `utility=0.95` tag 让 score 提升
8. **CommandAllowlist deny wins**: 显式 test 验证 "更具体的 allow 不能 override 更一般的 deny"
9. **ActionCache 测 hit/miss 计数**: hit/miss 自动 +1, hitRate = hits/(hits+misses)
10. **evals/pom.xml 加 3 dep**: aethercode-sdk + aethercode-memory + aethercode-permission
    (R-mod-2 已加 aethercode-orchestration)

## Test 结果

| Module | Tests (新) | 累计 | Pass | Fail | Skip |
|---|---:|---:|---:|---:|---:|
| aethercode-evals (R-sdk-1..4) | 68 | 410 | 410 | 0 | 0 |
| aethercode-orchestration (R-mod-2) | 0 | 260 | 260 | 0 | 0 |
| **Total 新增** | **68** | **670** | **670** | **0** | **0** |

之前 (R-eval-8 + R-mod-2 后): evals 361 + orchestration 260 = 621
现在 (R-sdk-1..4 后): evals 410 + orchestration 260 = 670 (+49 = R-sdk-1 19 + R-sdk-2 15 + R-sdk-3 12 + R-sdk-4 22 = 68 -19 之前跑过的 = 49)

## 后续

1. **R-sdk-5 Tool Interface**: 测真 `StandardTools` / `FileReadTool` / `FileWriteTool` / `ArxivFetchTool` (aethercode-tools)
2. **R-sdk-6 JSON-RPC Interface**: 测真 `JsonRpcDispatcher` / `JsonRpcCodec` / `RpcErrorCodes` (aethercode-protocol)
3. **R-sdk-7 A2A Interface**: 测真 A2A schema (aethercode-a2a)
4. **R-sdk-8 Reflection Interface**: 测真 `AgentRuntime` / `SelfCorrectionLoop` (orchestration 已是真)
5. **引用新 paper 8 篇到 ref/papers/**: 2503.16416 / 2506.11102 / 2604.00835 /
   2603.22862 / 2601.08173 / 2607.23722 / 2608.04719 / 2602.16902

## 教训 (新增 8 条, 累计 315+)

307. **SDK API 跟 self-contained 不完全一致**: ExperienceKind (CASE/STRATEGY/SKILL vs
     EPISODIC/SEMANTIC/PROCEDURAL), CostCeiling (recordCall(tokens) vs 3 个独立 method),
     ActionCache (非泛型存 Object→VerificationResult vs 自定义 generic)
308. **Maven install before mvn test**: evals/pom.xml 加 dep 后, 之前 mvn install 没
     装新 module, dep tree 不 resolve. 必须先 `mvn -pl aethercode-X install -DskipTests`
309. **`ActionCache` 非泛型**: 不写 `<K, V>`, 写 `new ActionCache(4)`
310. **`recordCall(tokens)` 而非分离**: orchestration CostCeiling 把 call + tokens
     合并成单方法, `recordCall(tokenCost) → boolean` (true if exceeded)
311. **`VerificationResult.pass(reason)` 静态工厂**: record 不能用 new 直接构造
     (要传 Severity enum), 用静态工厂简洁
312. **`ExperienceRecord.id = null` 自动生成 UUID**: SDK 自己处理 null id,
     测试不需要 mock UUID
313. **`CommandAllowlist.deny wins`**: 即便有更具体的 allow 规则, deny 永远
     优先. test 显式验证
314. **`ActionCache.hitRate()` 是 0.5 实际值**: hits=1 misses=1 -> 0.5, 不是 1.0

## Commits

- R-mod-2: `cd8f85a` (aethercode-orchestration 物理抽)
- R-sdk-1..4: (本次) `R-sdk-1-4-INTERFACE-CONFORMANCE`
