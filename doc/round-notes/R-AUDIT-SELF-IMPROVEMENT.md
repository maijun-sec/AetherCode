# R-AUDIT-SELF-IMPROVEMENT: AetherCode Agent 测评覆盖度自审 + 改进计划

**Date**: 2026-09-12
**Source**: 8 篇 paper (Survey on Eval / AI Agent Systems / Memory / Language-to-Action / Holistic Review / Agentic AI / Lifelong Learning / Multimodal) + 28 R-round 累计 (723 tests)
**Status**: 写报告 + 立即补齐 5-6 critical round

## 一、覆盖度全景

| 维度 | R-eval | R-sdk | Survey / Paper 引用 | 状态 |
|---|---:|---:|---|---|
| 规划 / multi-step | 21 | 15 | 2503.16416 §2.1, 2601.01743 §3.1 | ✅ 强 |
| 任务分解 | (in 21) | 15 | 2503.16416 §2.1 | ✅ 强 |
| 状态跟踪 | 26 | 19 | 2503.16416 §2.1 | ✅ 强 |
| 自我纠正 | 24 | 9 | 2503.16416 §2.3, 2508.17281 §5.2 | ✅ 强 |
| 因果理解 | (in 26) | (无) | 2503.16416 §2.1 | ⚠️ 弱 (只测 1 个 graph model) |
| 元规划 | (in 21) | 15 | 2503.16416 §2.1 | ✅ 强 |
| 记忆 episodic | 23 | 12 | 2512.13564 §2.1, 2503.16416 §2.4 | ✅ 强 |
| 记忆 semantic | (in 23) | (in 12) | 同上 | ✅ 强 |
| 记忆 procedural | (in 23) | **缺** | 2503.16416 §2.4, 2512.13564 | ⚠️ 弱 (R-sdk-3 没专门测 SKILL kind) |
| 记忆 parametric | **缺** | **缺** | 2512.13564 §2.1 | ❌ 缺失 (MemoryLLM / M+ / WISE) |
| 记忆 latent | **缺** | **缺** | 2512.13564 §2.1 | ❌ 缺失 (MemGen / MemoryBank) |
| 工具选择 | 25 | 8 | 2503.16416 §2.2, 2604.00835 Table 4 | ✅ 强 |
| 工具参数 | (in 25) | 8 | 同上 | ✅ 强 |
| 工具 sandbox | (in 25) | 22 | 2503.16416 §2.2 | ⚠️ 弱 (R-sdk-4 测 CommandAllowlist 但没测 WebFetch SSRF / file path) |
| 工具 multi-step | (in 25) | (无) | 2603.22862, 2608.04719 | ❌ 缺失 (long-horizon multi-tool) |
| JSON-RPC | 35 | 20 | 2503.16416 §5 | ✅ 强 |
| 多智能体 | 22 | 16 | 2503.16416 §2.6 | ✅ 强 |
| 成本 / 性能 | 28 | 22 | 2601.01743 §5.2 | ✅ 强 |
| 安全性 | (in 28) | 22 | 2601.01743 §5.6 | ✅ 强 |
| 鲁棒性 RobustSucc | **缺** | **缺** | 2601.01743 §5.5, 2503.16416 §5.5 | ❌ 缺失 |
| WorstSucc / Var | **缺** | **缺** | 2601.01743 §5.5 | ❌ 缺失 |
| LoopRate | **缺** | **缺** | 2601.01743 §5.4 | ❌ 缺失 |
| ToolExecSucc | (in 25) | (无) | 2601.01743 §5.3 | ⚠️ 弱 (测了 invoke 协议但没测执行成功率) |
| RL / IL / ICL 学习 | **缺** | **缺** | 2601.01743 §3.1 | ❌ 缺失 (用户没要求) |
| 编码 SWE-bench | **缺** | **缺** | 2508.17281 §3.3 | ❌ 缺失 |
| Web WebArena | **缺** | **缺** | 2508.17281 §4.1 | ❌ 缺失 |
| 多轮对话 | **缺** | **缺** | 2508.17281 §3.5 | ❌ 缺失 |
| VLM 评估 | **缺** | **缺** | 2608.20379 | ❌ 缺失 |
| Workflow engine | **缺** | **缺** | AetherCode 内部 | ❌ 缺失 (16 class 0 tests) |
| Hooks 系统 | **缺** | **缺** | AetherCode 内部 | ❌ 缺失 (9 class 0 tests) |
| MCP 协议 | **缺** | **缺** | AetherCode 内部 | ❌ 缺失 (15 class 0 tests) |
| Bridge / Swarm | **缺** | **缺** | AetherCode 内部 | ❌ 缺失 (10 class 0 tests) |
| Task Scheduler | **缺** | **缺** | AetherCode 内部 | ❌ 缺失 (40+ class 0 tests) |
| ACP 协议 | **缺** | **缺** | AetherCode 内部 | ❌ 缺失 (15+ class 0 tests) |
| Config / Prompts | **缺** | **缺** | AetherCode 内部 | ❌ 缺失 (13 class 0 tests) |

## 二、薄弱点优先级排序

### Tier 1: 立即做 (核心 SDK + 论文 24 维)

1. **Robustness / WorstSucc / Var** (paper 2601.01743 §5.5)
   - 没有: R-eval 测 capability, R-sdk 测 happy path, 都没测扰动下成功 / 最坏情况 / 跨种子方差
   - 影响: user 没法知道 AetherCode 在 prompt injection / 网络抖动下能不能用
   - 候选: **R-eval-9 Robustness** (10-12 tests)
2. **Tool Sandbox** (paper 2503.16416 §2.2)
   - 测了: bash CommandAllowlist
   - 没测: WebFetch SSRF guard / file path allow list / tool permission reasoner 三档 (ALLOW/DENY/ASK)
   - 影响: web 类 tool 的安全没锁, SSRF 漏洞风险
   - 候选: **R-sdk-16 Tool Safety** (12-15 tests, 测真 WebFetchTool / BashTool 实际 guard)
3. **Long-horizon Multi-Tool** (paper 2603.22862, 2608.04719)
   - 测了: 单独 tool 调用
   - 没测: 5+ tool 串联 / dependency chain / partial failure recovery
   - 影响: AetherCode 主要场景是 long-horizon (R-orch-3 已经 E2E 8 paper, 但没有 multi-tool dependency test)
   - 候选: **R-eval-10 Multi-Tool Pipeline** (12-15 tests)
4. **WorkflowEngine** (AetherCode 核心 16 class 0 tests)
   - WorkflowEngine / WorkflowLoader / WorkflowValidator / VariableSubstitution / SkillComposer
   - 影响: workflow 任何 bug 都没人测
   - 候选: **R-sdk-9 WorkflowEngine Interface** (15-20 tests)
5. **Hooks 系统** (9 class 0 tests)
   - PhaseTracker / WriteExistingFileGuardHook / EditErrorRecoveryHook / PhaseBudgetHook
   - 影响: hook 写错可能 break 全 system, 没测
   - 候选: **R-sdk-10 Hooks Interface** (12-15 tests)

### Tier 2: 应该做 (其他 SDK module + 论文指标)

6. **Task Scheduler** (40+ class 0 tests, R-eval-5 测了 Watchdog/CircuitBreaker 但没测 aethercode-tasks 子系统)
   - TaskStateMachine / TaskScheduler / PersistentTaskRegistry / SupervisorService
   - 候选: **R-sdk-11 Task Scheduler** (15-20 tests)
7. **MCP 协议** (15 class 0 tests)
   - McpManager / McpRegistry / McpHealthCheck / McpAuthCache
   - 候选: **R-sdk-12 MCP** (12-15 tests)
8. **LoopRate metric** (paper 2601.01743 §5.4)
   - 没专门测 agent 实际跑时循环 / 振荡率
   - 候选: **R-eval-11 Loop & Drift** (10-12 tests)
9. **Tool Canary Safety** (paper 2608.04719)
   - 故意构造 canary tool 测 agent 是否会选错
   - 候选: **R-eval-12 Tool Canary Safety** (8-10 tests)
10. **Bridge / Swarm** (10 class 0 tests)
    - SwarmCoordinator / BridgeClient / ReconnectStrategy / Blackboard
    - 候选: **R-sdk-13 Bridge** (10-12 tests)

### Tier 3: 可选 (用户没要求, 离 AetherCode 远)

11. **SWE-bench style** — 编码任务长时程 E2E, 离当前 R-orch-3 (paper review pipeline) 远
12. **WebArena** — 真实网页交互, 跟 BashTool 重叠
13. **VLM 评估** — AetherCode 主要 VLM 在视觉场景, 我们主要 text agent
14. **RL/IL/ICL** — 训练相关, AetherCode 是 inference-only

## 三、本次执行计划 (Tier 1 全部 + Tier 2 部分)

**本次执行 5 critical round, 目标 +80-100 tests**:

1. **R-eval-9 Robustness** — 12 tests
   - 扰动下 Watchdog / CircuitBreaker / SelfCorrect 仍工作
   - Worst-case: 极端 budget / max retry 耗尽
   - Var: 跨 seed 复现
2. **R-eval-10 Multi-Tool Pipeline** — 12 tests
   - 5 tool 串联 dependency chain
   - 1 tool 失败后续 tool 是否继续
   - tool 输出作为下个 tool 输入
3. **R-eval-11 Loop & Drift** — 10 tests
   - 重复 prompt 触发循环
   - HeuristicVerifier RepetitionCheck
   - TaskWatchdog 触发
4. **R-sdk-9 WorkflowEngine Interface** — 15-20 tests
   - WorkflowEngine.run / load / validate
   - WorkflowValidator 错误检测
   - VariableSubstitution ${} 替换
5. **R-sdk-10 Hooks Interface** — 12-15 tests
   - HookRegistry.register / dispatch
   - PhaseTracker 阶段边界
   - WriteExistingFileGuardHook 拦重复写
   - EditErrorRecoveryHook 错误恢复
6. **R-sdk-16 Tool Safety (合并入 R-eval-10)** — 12-15 tests
   - WebFetch SSRF 拦截
   - file path allow list
   - PermissionReasoner ALLOW/DENY/ASK 三档

(可选) **R-sdk-11 Task Scheduler** — 15-20 tests (Tier 2)

**累计目标**: 6 round, 80-100 new tests, 总数 723 → 800-820

## 四、6 维度 (User 原始) → 改进 round 映射

| User 维度 | 现有 | 改进 |
|---|---|---|
| 1. 基础能力 | R-eval-1/4/5 + R-sdk-1/2/8 | +R-eval-9 (Robustness 强化) |
| 2. 各类记忆 | R-eval-2 + R-sdk-3 | (无, parametric/latent 离 AetherCode 远) |
| 3. 其他 (tool/function) | R-eval-3 + R-sdk-5 | +R-eval-10 (long-horizon), +R-eval-12 (canary), +R-sdk-16 (safety) |
| 4. 应用特定 | R-eval-3 部分 | +R-eval-12 (canary tool safety) |
| 5. 通用 Agents | R-eval-7 + R-sdk-7 | +R-eval-11 (loop/drift) |
| 6. AetherCode 接口 | R-eval-6/8 + R-sdk-4/5/6/7/8 | +R-sdk-9/10/11 (Workflow/Hooks/Tasks) |

## 五、push 后续

6 critical round 完成后, 跑全 evals + orchestration + 6 个新 module tests,
最后 commit + push (force-with-lease)。

## 六、教训 (新增 3 条, 累计 329+)

327. **Self-audit 比加新 test 更重要** — 看 8 篇 paper 才知道覆盖 gap
328. **Paper 24 维 metric** (2601.01743) 是 checklist — 每条对应一个 eval round
329. **AetherCode 16+ 个 SDK module 0 tests** — 不能光看 architecture, 还要看每个 module 测没测
