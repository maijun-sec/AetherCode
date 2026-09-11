# R-eval-1 Planning & Multi-Step Reasoning (2026-09-12)

## 触发

R-eval master plan (R-eval-MASTER-PLAN.md) 列出 8 个 eval round 覆盖
AetherCode 前端/SDK/Protocol/Tools 能力。R-eval-1 是第一个, 聚焦
**规划与多步推理** (Survey 2503.16416 §2.1 + 2506.11102 + 2602.16902)。

AetherCode SDK 已有 `DagPlan` / `PlanExecutor` / `PlanClassifier` /
`WorktreeManager` 等 planning 组件, 但**没有针对"agent 能否正确
规划"的能力测试**。R-eval-1 写一个 self-contained Plan / PlanStep /
PlanVerifier model, 5 个子能力测试 (任务分解 / 状态跟踪 / 多步推理
/ 元规划 / 因果理解), 21 个 test 覆盖。

## 实际产出 (1 test class, 21 tests, 1 round)

- `aethercode-evals/src/test/java/org/aethercode/evals/capability/planning/PlanningCapabilityTest.java`
  (22 KB), 21 tests:
  - **Plan model contract** (3 tests): flat chain / fan-out-fan-in / 拒绝
    未知 dep / 拒绝重复 id
  - **Cycle detection** (2 tests): 直接 cycle (a->b->a) / 传递 cycle
    (a->b->c->a)
  - **Multi-step reasoning** (2 tests): 10 步长 horizon / diamond 拓扑
  - **State tracking** (2 tests): isComplete / remaining work 计算
  - **Causal understanding** (2 tests): self-loop / build-test causal
    chain
  - **Meta-planning (PlanClassifier)** (5 tests): TRIVIAL auto-approve /
    destructive tool NEEDS_APPROVAL / 超过 step limit / empty plan /
    custom destructive set
  - **Causal chain audit** (1 test): diamond 最长 chain 长度
  - **Task decomposition** (2 tests): fix failing test decomposition /
    parallelizable fan-out
  - **E2E lifecycle** (1 test): plan 走完 topo + causal invariant 验证
  - **Self-loop** (1 test): ISE on dependsOn=id
- **aethercode-evals: 419/419 Java tests pass** (398 R-orch-3 + 21 new
  R-eval-1), 0 回归

## 关键技术决定 (8 条)

1. **Self-contained Plan model** - 不用 aethercode-sdk dep, 在 evals 下
   复制 `DagPlan` 行为. R-mod-2 抽 module 后再 wire 真 SDK, 行为对得上.
2. **5 子能力维度对齐 Survey §2.1** - task decomposition / state tracking /
   multi-step reasoning / meta-planning / causal understanding. 每个有
   独立 test 集合.
3. **Kahn's algorithm 拓扑排序** - 跟 R-radar-8 multi-agent orchestrator
   用同样的图算法, cycle detection 一致.
4. **PlanVerifier 1:1 复制 PlanClassifier** - max steps + destructive
   tool set, 跟 SDK 一致, 但自己实现便于 self-contained test.
5. **State 是 Set<stepId>** - 跟 SDK DagPlan.readySteps(Set) 一致, 跟
   R-orch-1 RuntimeTrace 状态表达对齐.
6. **causal chain audit 用 BFS 长度** - 测 plan 能否推理"最长依赖链",
   论文 2602.16902 提到 long-horizon planning 关键是 causal chain.
7. **fan-out / fan-in 测并行可调度性** - 不是测调度器本身, 是测 plan
   表达能否让调度器识别并行. PlanBench / FlowBench 风格.
8. **E2E lifecycle 测 causal invariant** - 走完 plan, 验证每条 edge
   都满足 "dep before dependent", 真正的 end-to-end 验证.

## 5 子能力 + 21 tests 映射

| 子能力 (Survey §2.1) | Tests | 论文映射 |
|---|---|---|
| Task decomposition | 3 (linear / fan-out / 拒绝 dup+missing) | 2602.16902 LLM-WikiRace |
| State tracking | 2 (isComplete / remaining work) | 2601.01743 5-tuple state |
| Multi-step reasoning | 4 (10 步 / diamond / cycle 直+传 / 自身) | 2506.11102 FlowBench |
| Meta-planning | 5 (TRIVIAL / 破坏 / 步数 / 空 / 自定义) | AetherCode PlanClassifier |
| Causal understanding | 3 (build chain / longest path / E2E lifecycle) | 2506.11102 causal reasoning |

## API shape (Plan + PlanVerifier)

```java
Plan plan = new Plan(List.of(
    new PlanStep("read", "read input", List.of()),
    new PlanStep("parse", "parse", List.of("read")),
    new PlanStep("summarize", "summarize", List.of("parse"))));

plan.topoOrder();         // ["read", "parse", "summarize"]
plan.readySteps(Set.of("read"));  // ["parse"]
plan.isComplete(Set.of("read", "parse", "summarize"));  // true

PlanVerifier verifier = new PlanVerifier();
verifier.classify(plan);  // TRIVIAL or NEEDS_APPROVAL
```

## AetherCode 实际 SDK 映射 (R-mod-2 之后 wire)

| R-eval-1 model | AetherCode SDK class | 备注 |
|---|---|---|
| `Plan` | `org.aethercode.sdk.DagPlan` | 拓扑 + cycle 行为一致 |
| `PlanStep` | `DagPlan.Step` record | (id, title, dependsOn) 一致 |
| `PlanVerifier` | `org.aethercode.sdk.PlanClassifier` | TRIVIAL/NEEDS_APPROVAL 一致 |
| `readySteps(Set)` | `DagPlan.readySteps(Set)` | 返回 ready step 集合 |

R-mod-2 抽 module 后, R-eval-1 test class 改成 import 真 SDK, 行为不变,
但真 wire 上了 AetherCode production code.

## 累计测试 (R-eval-1 后)

- aethercode-orchestration: 0 (空 module, 等 R-mod-2)
- aethercode-evals: **419/419** (398 R-orch-3 后 + 21 R-eval-1)
- 其他 module 不变
- 0 回归

## 教训 (新增 8 条, 累计 243+)

236. **Self-contained eval 比 wire 真 SDK 更稳** - 写 1:1 复制 model
     比加 dep 安全, R-mod-2 之后 wire 真 SDK, 行为对得上
237. **5 子能力维度对齐 Survey** - task decomposition / state tracking /
     multi-step reasoning / meta-planning / causal understanding 是
     paper 明确列的
238. **Kahn algorithm 是 cycle detection 标准** - 跟 R-radar-8 multi-agent
     排序对齐
239. **PlanClassifier 用 Set 描述 destructive** - Set.subset / contains
     check 高效, custom override 灵活
240. **causal chain audit 用 BFS 深度** - 测 plan 表达的因果链长度
241. **fan-out 表达 ≠ 调度器实现** - plan 数据结构只需正确表达依赖
     关系, 调度器实现是另一个问题
242. **E2E lifecycle 测 causal invariant** - 走完 plan 验证每条 edge
     "dep before dependent", 比单元测试更接近真实场景
243. **empty plan trivially complete** - plan.isComplete(Set.of()) 对
     0-step plan 返回 true, 跟 set 语义对齐

## 后续 (R-eval-2 ~ R-eval-8)

- R-eval-2: Memory (episodic / semantic / procedural)
- R-eval-3: Tool Use & Function Calling (aethercode-tools)
- R-eval-4: Self-Reflection & Self-Correction (SelfCorrectionLoop)
- R-eval-5: State Tracking & Causal Reasoning (Watchdog/CircuitBreaker)
- R-eval-6: AetherCode JSON-RPC Interface (9 endpoints)
- R-eval-7: A2A Multi-Agent (aethercode-a2a)
- R-eval-8: Cost-Efficiency, Safety & Robustness (cost + permission)
