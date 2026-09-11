# R-radar-6: V 校验器框架 (论文 2601.01743 gap #1)

**Round**: R-radar-6  
**Date**: 2026-09-12  
**Module**: `aethercode/aethercode-evals/src/main/java/org/aethercode/evals/verifier/`  
**Goal**: 补全论文 2601.01743 §III "Agent Transformer A=⟨π_θ, M, T, V, E⟩" 中缺失的 V 组件, 提供可组合的校验器框架, 让 agent loop 在每步验证候选动作。

## TL;DR

- 新增 6 个 main 文件 (~30 KB) + 6 个 test 文件 (~50 KB) 在 `aethercode-evals/verifier/` package
- **114 tests** 跨 6 个 test class, **241/241 aethercode-evals tests pass** (含 R-radar-2 的 127 + 本 round 114)
- V 框架: 5 个具体 verifier (Schema / Rule / Heuristic / LlmJudge / Human) + 1 个 CompositeVerifier (ALL / ANY / MIN_THRESHOLD)
- Severity 3 档 (INFO / WARN / BLOCK) + structured result (pass / severity / reason / metadata)
- 错误全部 contained (verifier 抛异常不挂掉 composite)

## 关键决定 (8 条)

1. **跟原文对齐用 5 元组的 V 命名** — 论文 A=⟨π_θ, M, T, V, E⟩ 把 V 单独列为 verifier 集合。本 round 整个 package 用 `verifier/` 命名, 跟 paper 字段一一对应 (M = memory 模块 / T = aethercode-tools / V = 本 round)
2. **6 个 verifier 覆盖论文 5 大校验维度** — schema (类型) / rule (规则) / heuristic (形状) / llm-judge (语义) / human (高风险) + composite (组合策略)。5 类基本覆盖"长尾问题 → 类型检查 → 内容审查 → 高风险 gate" 全部 spectrum
3. **Severity 3 档 (BLOCK / WARN / INFO)** — 不是 simple boolean。论文说"风险高 → 更多验证 + 人工确认; 风险低 → 快速路径", severity 允许 partial-fail (一个 WARN 失败不挂 loop, 但 surface 给 audit log)
4. **Composite 3 种 policy** — ALL_MUST_PASS (安全关键) / ANY_MUST_PASS (permissive fallback) / MIN_THRESHOLD (软集成, e.g. 2-of-3 llm-judges)。论文 §VII direction #4 "verify-aware planning" 提到根据风险调整 verification 强度, 这 3 个 policy 是落地
5. **错误 containment** — composite 内部 verifier 抛 RuntimeException 不挂掉 composite, 而是 capture 成 BLOCK 失败 (带 exception type in metadata)。这样 production 不会因为一个 verifier 写崩了就把整个 agent loop 搞挂
6. **LlmJudgeVerifier 用 pluggable JudgeFn** — 不内置 LLM call, 而是 `JudgeFn = (input, rubric) -> JudgeReply`。测试可以传 stub, production wire 真 LLM。给后续 R-round (R-radar-7 self-correction) 留好接口
7. **HumanVerifier auto-deny when not wired** — safe default。一个 misconfigured loop 试图用 Human verifier 但没接 ask callback, 不会 silently 让 action 通过, 而是直接 BLOCK。这样 fail-loud 而不是 fail-silent
8. **Verdict 区分 FAIL vs INCONCLUSIVE** — LlmJudge 三个状态: PASS / FAIL / INCONCLUSIVE。INCONCLUSIVE 是"judge 没法判断" (模糊 rubric / parse 失败), 跟 FAIL 区分开, 默认 WARN 而不是 BLOCK。论文 §V 提到"评估差距: 仅'看起来不错'的答案", INCONCLUSIVE 是这个的 catch

## 架构

```
┌──────────────────────────────────────────────────────┐
│  Verifier<T> interface                                │
│  - name(), description()                              │
│  - verify(T) -> VerificationResult                   │
│                                                        │
│  VerificationResult(passed, severity, reason, meta)    │
│  Severity: INFO / WARN / BLOCK                        │
└──────────────────────────────────────────────────────┘
                       ▲
                       │ implements
        ┌──────────────┴──────────────┐
        │                              │
┌──────────────┐                ┌──────────────┐
│ 具体 verifier │                │ Composite    │
│  (5 个)       │                │  Verifier    │
│              │                │              │
│ - Schema     │                │ - ALL_MUST   │
│ - Rule       │                │ - ANY_MUST   │
│ - Heuristic  │                │ - MIN_THRESH │
│ - LlmJudge   │                │              │
│ - Human      │                │ + 错误 contain│
└──────────────┘                └──────────────┘
```

## 5 个具体 verifier

### 1. SchemaVerifier
**测什么**: 候选 action (Map<String, Object>) 是否符合 schema (type map)。
- 8 种 type: string / int / long / number / boolean / list / map (null 跟 unknown type = permissive)
- required / optional 字段
- 失败: 第一个错的字段 + expected type + actual type 在 metadata

### 2. RuleVerifier
**测什么**: 字符串内容是否满足一组规则 (must contain / must not contain / regex match / regex not match)。
- 4 种 kind
- 自定义 severity
- 失败: 第一个不满足的 rule + pattern + kind 在 metadata

### 3. HeuristicVerifier
**测什么**: 字符串形状 (长度 / 非空 / n-gram 重复)。
- 4 个 built-in check: NonEmpty / MinLength / MaxLength / Repetition
- Repetition: 同样 N-gram 连续重复 K 次以上 (catch LLM loop / mode collapse)
- 自定义 Check interface, 用户可以加自己的

### 4. LlmJudgeVerifier
**测什么**: LLM-as-judge, 给 input + rubric, 让 judge 给 PASS/FAIL/INCONCLUSIVE。
- Pluggable JudgeFn: 测试传 stub, production wire 真 LLM
- 自定义 fail / inconclusive severity
- metadata: judge_verdict / judge_reason / judge_score

### 5. HumanVerifier
**测什么**: 高风险 action 必须人工确认。
- 3 种模式: 配置 callback (production) / auto-deny (default) / auto-approve (override)
- CompletableFuture-based, 支持 timeout (默认 30s)
- 3 种 decision: APPROVE / REJECT / ABSTAIN
- Auto-deny 时 description 包含 "auto-deny (unwired)" 让 misconfig 可见

## CompositeVerifier 3 种 policy

| Policy | 用法 | 失败 surface | 例子 |
|---|---|---|---|
| **ALL_MUST_PASS** | 安全关键 | 最高 severity 失败 | tool call: schema + rule + policy 都得通过 |
| **ANY_MUST_PASS** | permissive fallback | "no verifier passed" | heuristic fail, 但 llm-judge 觉得 ok → 放过 |
| **MIN_THRESHOLD** | 软集成 | "X of Y passed" | 2-of-3 llm-judges 同意 → 放过 |

每个 composite result 的 metadata 包含 `verifier_results: List<VerificationResult>`, 让 agent loop / audit log / eval reports 可以 attribute 失败到具体 sub-check。

## 跟 paper 5 元组的对应

| A 组件 | 现有 | R-radar 状态 |
|---|---|---|
| π_θ (策略) | LLM/VLM | 已具备 |
| M (记忆) | `aethercode-memory` (5 scope + 4 阶编排) | 已具备 |
| T (工具) | `aethercode-tools` (StandardTools.all() 18 个 tool) | 已具备 |
| **V (校验器)** | **`aethercode-evals/verifier/` (本 round 6 个 verifier + 114 tests)** | **✅ R-radar-6 完成** |
| E (环境) | harbor + Docker + BashTool sandbox | 部分具备 |

R-radar 后续 round 可以把 V 集成进 DeepAgentsSystem.respond() / observe() 流程, 在每步 candidate action 上跑 verifier, fail 就进 recovery。

## 测试 (114 tests, 0 失败)

| Test class | Tests | 覆盖 |
|---|---:|---|
| `SchemaVerifierTest` | 21 | type matching (8 type) + 构造 + verify (pass / missing / wrong type / null / metadata) |
| `RuleVerifierTest` | 19 | 4 kind × (pass / fail) + multi-rule + severity + null input |
| `HeuristicVerifierTest` | 25 | 4 check × (pass / fail / boundary) + verify wiring + severity + null input |
| `LlmJudgeVerifierTest` | 16 | 3 verdict × pass / fail / inconclusive + custom severity + null judge + crash containment |
| `HumanVerifierTest` | 14 | 3 decision + auto-deny + timeout + crash + null future + summary builder |
| `CompositeVerifierTest` | 19 | 3 policy × (pass / fail / boundary) + 错误 containment + per-verifier attribution |
| **Total** | **114** | |

## 跟 R-radar-7/8 的关系

- **R-radar-7 (Self-correction)**: V 校验器失败 → 触发 self-correction loop. `LlmJudgeVerifier` 给的 reason 喂给 self-correction prompt, 让 agent 知道"哪里错了"再重试
- **R-radar-8 (Multi-Agent 对抗)**: 多个 agent 跑同一个任务, 每个 agent 的 output 过一个 verifier, 投票决定哪个 output 通过。MIN_THRESHOLD policy 天然支持这场景
- **R-radar-6 后续**: 把 V 集成到 DeepAgentsSystem.respond() 流, 在 execute action 之前跑 ALL_MUST_PASS composite, fail 就 route 到 recovery

## 教训 (新增 8 条, 累计 176+)

169. **🆕 record field 名字不要跟 static method 同名** — Java 拒绝在 record 里同时有 `boolean pass` 字段 (auto-accessor `pass()`) 和 static `pass()` 工厂。改名 `pass` → `passed`, 全局 rename `.pass()` → `.passed()` (instance accessor), 静态工厂保持 `pass()`。**或者** 改成 builder pattern
170. **🆕 构造函数 erasure 必须不同** — `(String, Map<String, String>)` 和 `(String, Map<String, FieldSpec>)` 擦除后都是 `(String, Map)`, 编译报"name conflict"。改用 `static ofTypeMap()` 工厂
171. **🆕 Severity 3 档比 boolean 强** — WARN 不挂 loop 但 surface 给 audit log, 是 "observability without friction" 的好工具
172. **🆕 错误 containment 在 composite 级别很重要** — verifier 抛 RuntimeException 经常发生 (LLM call 失败 / 文件 IO / network)。Composite 用 try-catch 转成 BLOCK 失败 + exception type in metadata, agent loop 不会因为一个 verifier 挂掉
173. **🆕 LLM-as-judge 用 pluggable function, 不内置 LLM** — 测试用 stub function, production wire 真 LLM。同一份代码两边都能跑
174. **🆕 INCONCLUSIVE 跟 FAIL 区分** — LLM judge 经常没法判断 (rubric 太模糊 / parse 失败), 这种应该 WARN, 不是 BLOCK。论文 §V "评估差距" 就是 INCONCLUSIVE 的 catch
175. **🆕 auto-deny 比 auto-approve 安全** — HumanVerifier 默认 auto-deny 当没接 callback。Loud failure > silent failure, 跟 fail-loud 哲学一致
176. **🆕 RepetitionCheck 设计: 连续 N-gram, 不是 total** — "foofoofoo" 是连续 3-gram 重复, 应该 catch; "the the the" 中间有空格, 算 3 个 1-gram, 不会 catch。`RepetitionCheck` 抓的是 LLM loop / mode collapse 这种"重复同样 block", 不是常见 prose 里的 word repeat

## 后续 (R-radar-7+)

- **R-radar-7**: Self-correction 机制, 失败 reason 喂给 retry loop
- **R-radar-8**: Multi-Agent 对抗, 多个 verifier 投票
- **R-radar-6 后续**: 集成 V 进 DeepAgentsSystem.respond()
- **可能单独 round**: 把 V 提到新 `aethercode-verifier` module, 给其他模块 (a2a / workflows) 用
