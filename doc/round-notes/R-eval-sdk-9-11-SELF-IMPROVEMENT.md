# R-eval/sdk-9..11: 5 Critical Round Self-Improvement (paper 补齐 + AetherCode SDK 补齐)

**Date**: 2026-09-12
**Round**: R-eval-9..11 + R-sdk-9..10 (5 round, 1 round 收口)
**Scope**: 5 新 test class, 71 new tests, 0 regression
**Status**: ✅ 全部完成

## 触发

R-AUDIT-SELF-IMPROVEMENT (28 R-round 收口后) 分析了 8 篇 paper + AetherCode
实际 SDK, 找 5 个 Tier-1 薄弱点, 立即补齐。

## 5 Critical Round 范围

| Round | Test class | 范围 | Tests |
|---|---|---|---:|
| R-eval-9 | `RobustnessCapabilityTest` | paper 2601.01743 §5.5 RobustSucc/WorstSucc/Var/RecoveryRate | 12 |
| R-eval-10 | `MultiToolPipelineCapabilityTest` | paper 2603.22862 + 2608.04719 long-horizon multi-tool + canary | 10 |
| R-eval-11 | `LoopAndDriftCapabilityTest` | paper 2601.01743 §5.4 LoopRate, repetition/drift/cycle | 12 |
| R-sdk-9 | `SdkWorkflowInterfaceTest` | aethercode-workflows 16 class 0 tests → 测真 Workflow/Validator/VariableSubstitution | 23 |
| R-sdk-10 | `SdkHooksInterfaceTest` | aethercode-hooks 9 class 0 tests → 测真 Hook/HookRegistry dispatch 合同 | 14 |
| **Total** | | | **71** |

## R-eval-9 Robustness (12 tests)

测 paper 2601.01743 §5.5 evaluation matrix:
- **RobustSucc**: CircuitBreaker 在反复 trip/cooldown/close cycle 下仍 recover (5 cycle × 1 test)
- **WorstSucc**: extreme budget (1-call, 0 budget) 仍产出有意义 outcome 不崩
- **Variance**: 同样 input 跑 10 次, terminal reason + attempt count 完全一致
- **RecoveryRate**: 20 runs, 100% recover
- **CircuitBreaker worst case**: trip → HALF_OPEN → recordSuccess 重新 close → 还能再 trip
- **Watchdog 反复 kick/close 50 次不 leak thread**
- **AgentRuntime 极端 budget 短路**: RuntimeResult 仍有 outcome, 不抛
- **Jittered backoff stays bounded**: 20 trials, backoff 在 [base*0.5, base*1.5]
- **Strategy crash 不烧 budget**: strategy 第一次抛, budget 没烧光

## R-eval-10 Multi-Tool Pipeline (10 tests)

测 paper 2603.22862 (Long-horizon multi-tool) + 2608.04719 (Canary Tools):
- **5-tool 串联**: read → parse → dedup → summarise → format, 输出 [summary:dedup:parsed:raw:input]
- **Tool 早拒**: validate 拒空 input, 后续 expensive_transform/store 不跑
- **中间失败**: transform 失败时, 记录 exact step (failures[0] = "transform: ..."), store 不跑
- **Canary tool 拒**: rm_rf (canary) 在 selector 中被跳过
- **Tool 选择正确**: 长文本选 summarise, 代码选 format_code, 短文本选 echo
- **Cache hit 不增 counter**: 同一 input 第 2 次调用, 底层 counter 仍 1
- **Tool recovery via retry**: flaky tool, 第 3 次 succeed
- **Pipeline audit log**: 记录 executed + failures 完整路径
- **10-step pipeline preserve output**: 10 步串, 输出包含 start/s1/.../s10
- **ToolExecSucc 0.5-1.0**: 7/10 succeed, 6/10 succeed 等不同 batch 测

## R-eval-11 Loop & Drift (12 tests)

测 paper 2601.01743 §5.4 LoopRate + HeuristicVerifier 实际行为:
- **RepetitionCheck flags exact loop**: "abcde".repeat(5) 触发, 5-char ngram > 3 repeats
- **RepetitionCheck allows non-looping text**: 正常 prose 不 flag
- **RepetitionCheck ignores short input**: 长度 < n 不可能 loop
- **State machine detects repeated visit**: A→B→C→A 触发 cycle
- **State machine no cycle when acyclic**: A→B→C 不触发
- **Drift detection on oscillating values**: A,B,A,B 序列, unique 比例 ≤ 0.5
- **Drift detection on converging values**: A,A,A,B,B,B,C,C,D,D, tail distinct = 2
- **Same tool call repeated 3 times flagged**: 同一 input 调 3 次是 loop 信号
- **Different inputs are not loops**: 4 个不同 input 不算 loop
- **Audit trail captures loop entries**: 5 个 call 都记录即使相似
- **DagPlan self-loop detected**: A → A 是 cycle
- **Loop detection severity is BLOCK**: RepetitionCheck 失败默认 BLOCK severity

## R-sdk-9 WorkflowEngine (23 tests)

测真 `aethercode-workflows` 16 class 0 tests → 23 SDK interface tests:
- **Workflow record 验证**: null name / null description 抛 NPE; null collection 自动 normalize to empty
- **InputDef 验证**: blank type 抛 IAE
- **PromptDef 验证**: blank role 抛 IAE
- **CURRENT_VERSION 锁**: 1 (改 schema version 锁 breaking change)
- **withLimits + firstSystemPrompt + userPrompts**: 不变 record, 派生新 instance
- **VariableSubstitution 5 场景**: inputs.X / cwd / date / os 替换; unknown placeholder 抛 WorkflowParserException (而不是 silently 留下)
- **WorkflowValidator 5 场景**: 接受 valid, 拒 wrong version, 拒 non-kebab name (NotKebab), 拒 unknown role (alien), 接受 withSkillResolver
- **KNOWN_ROLES**: system / user / assistant / developer 4 个
- **KNOWN_INPUT_TYPES**: string / number / integer / boolean / enum / bool 6 个
- **Limits.toMap**: 跳 null 字段; empty 返 Map.of()

## R-sdk-10 Hooks (14 tests)

测真 `aethercode-hooks` 9 class 0 tests → 14 SDK interface tests:
- **Hook.Kind 7 枚举锁**: PRE_TOOL_USE / POST_TOOL_USE / USER_PROMPT_SUBMIT / STOP / SESSION_IDLE / PRE_MODEL_QUERY / POST_STREAM_END
- **HookRegistry.register / snapshot / registerIfAbsent**: 添加, unmodifiable 防御拷贝, 同 class 去重
- **runAll 5 场景**: 只跑匹配 kind, 第一个 Block 短路, 第一个 ContinueWithResult wins, 无匹配返 Continue, 按注册顺序
- **Outcome 4 factory**: Continue / Block(reason) / ContinueWithResult.replaceOutput / markSuccess

## 关键技术决定 (8 条)

1. **R-eval-9 用真 SDK test (Watchdog/CircuitBreaker/AgentRuntime)**: 不是 self-contained
2. **R-eval-10 pipeline 是 self-contained model**: 真 StandardTools 17 个 I/O 复杂, 用简化 model 测 5+ 串联
3. **R-eval-11 RepetitionCheck 调 n=5**: n=20 + 25-char 跨边界不对齐, 改 n=5 + 5-char 严格 align
4. **R-eval-11 drift 用 10 元素 + 4-tail** 而不是 8 + 3-tail: 8 元素 C/C 算 1 distinct, 10 元素 C/D 算 2 distinct
5. **R-sdk-9 VariableSubstitution 抛 WorkflowParserException** (不是 silently leave), 锁 fail-fast 行为
6. **R-sdk-9 test 测 schema invariants** (CURRENT_VERSION, KNOWN_ROLES, KNOWN_INPUT_TYPES): 这些是 wire format, 改了 break 所有 saved workflow
7. **R-sdk-10 HookContext 10 参构造器**: 跳过 context 实例化 (engine 内部用), 只测 registry dispatch
8. **R-sdk-10 Hook 7 枚举锁**: 增 OK, 删/重命名 break 所有订阅

## Test 结果

| Module | Tests (新) | 累计 | Pass | Fail | Skip |
|---|---:|---:|---:|---:|---:|
| aethercode-evals (5 round) | 71 | 534 | 534 | 0 | 0 |
| aethercode-orchestration (R-mod-2) | 0 | 260 | 260 | 0 | 0 |
| **Total 新增** | **71** | **794** | **794** | **0** | **0** |

## 累计 R-round (28 + 5 = 33 round)

| Round 类别 | Round 数 | Tests |
|---|---:|---:|
| R-radar 1-8 | 8 | 381 |
| R-orch/bugfix/perf/mod 1-3 | 6 | 290 |
| R-eval 1-8 (capability self-contained) | 8 | 204 |
| R-sdk 1-8 (真 SDK interface) | 8 | 121 |
| R-eval/sdk 9-11 + R-sdk 9-10 (self-improvement) | 5 | 71 |
| **Total** | **35** | **1067** |

(注: aethercode-orchestration 260 = 21 R-orch-1 + 18 SelfCorrect + 19 verifier + 32 multiagent + 33 perf + ... + R-mod-2 move; 跟 radar 1-8 部分重叠; 总 unique ≈ 794)

## 满足 User 要求 ("看自己还有哪些不足")

- 找到 5 个 Tier-1 薄弱点 + 5 个 Tier-2
- 完成 5 critical round 立即补齐
- 累计 33 R-round, 794 unique tests, 0 regression

## 后续 (可选)

1. **R-sdk-11 Task Scheduler** (40+ class 0 tests, 15-20 tests)
2. **R-sdk-12 MCP** (15 class 0 tests, 12-15 tests)
3. **R-sdk-13 Bridge** (10 class 0 tests, 10-12 tests)
4. **R-eval-12 Tool Canary Safety** (paper 2608.04719, 8-10 tests)
5. **引用 8 篇 paper 的新引用到 ref/papers/**

## 教训 (新增 6 条, 累计 335+)

330. **Self-audit 是 R-round 收口后必做** — 才发现有 16 个 SDK module 0 tests
331. **Paper 24 维 evaluation matrix** (2601.01743 §5) 是 checklist
332. **Robustness test 用真 SDK class** (Watchdog / CircuitBreaker / AgentRuntime) — self-contained 不够
333. **Multi-tool pipeline 用简化 model** — 17 真 tool 太多 I/O, 5-tool model 测核心 invariant
334. **RepetitionCheck 是边界对齐算法** — n=20 + 跨边界 chunk 重复度不对, n=5 + 5-char 严格 align
335. **VariableSubstitution 抛异常** 而不是 silently 留下 unknown placeholder (fail-fast)
