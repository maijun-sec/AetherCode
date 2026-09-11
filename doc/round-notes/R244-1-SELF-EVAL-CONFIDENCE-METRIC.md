# R244.1 — Binary Self-Eval + Confidence-Weighted Recall

**Status**: shipped
**Date**: 2026-09-10
**Parent**: R241.2 + R241.3 + R243.1 + R243.2 + R243.2B + R243.3 (O-3 完整闭环) + R239 roadmap
**Tests**: aethercode-deepagents **209/209 pass, 0 regressions** (was 195 in R243.3; +14 new)

---

## Why R244.1

R243 O-3 完整闭环里，recall 排序只用 `utility` 一个因子。问题：**一个 utility 高但实际错误的反思会一直占位**，因为 utility 不会因为"被用错"而下降。

R244.1 解决：加 **binary self-eval (ok/not_ok)** 反馈，让 recall 排序从单因子变成**双因子 (utility × confidence)**：
- `confidence` = `okCount / (okCount + notOkCount + 1)` (Laplace-smoothed)
- `score = confidence × effectiveUtility`

**论文 reference**:
- Reflexion (paper 4 §11.1) — "self-eval" 半边
- ReasoningBank 2025 paper §4.2.2 — 明确说 "binary feedback" 是 future work
- Survey arXiv:2512.13564 §5.2.3 — 经验"trust score" 是必备

---

## What shipped

### 1. `ReasoningUnit` schema 扩展 (R244.1)

8 字段 → 10 字段：
- **新**: `okCount`, `notOkCount` (long)
- 新方法: `withOutcome(boolean ok)`, `confidence()`
- **8-arg 兼容构造器** 保留, delegate 到 canonical 10-arg (输入 0L, 0L)
- Jackson 反序列化旧 JSON 自动用 0 default (向后兼容)

### 2. `ReasoningBank.recordOutcome(id, ok)` (R244.1)

```java
public Optional<ReasoningUnit> recordOutcome(String id, boolean ok);
```
- 跟 `touch(id)` 风格一致
- 写盘 + 写内存
- unknown id 返回 empty

### 3. `BankRecallMiddleware` 排序公式升级 (R244.1)

**Before** (R241.2):
```java
comparingDouble(ReasoningUnit::utility).reversed()
```

**After** (R244.1):
```java
comparingDouble((u) -> u.confidence() * u.utility()).reversed()
```

`formatRecall` 输出也加 `confidence` 字段:
```
1. task_kind: file_edit
   error_pattern: permission denied
   fix_strategy: ensure dir exists first
   (uses=3, utility=0.85, confidence=0.75)
```

### 4. `SelfEvalClassifier` (R244.1) — 3.0 KB

- `heuristic()` (默认) — 无 exception = ok, exception 或 null = not_ok
- `never()` — 关闭 self-eval
- 自定义 `Evaluation` record 含 reason 字段

### 5. `SelfEvalMiddleware` (R244.1) — 6.7 KB

- `wrapToolCall` 后 (无论成功失败) 调 classifier → 对当前 recall 列表每个 unit 调 `recordOutcome`
- 4 个 counter: `totalEvaluations`, `totalOk`, `totalNotOk`, `skippedClassifier`
- never throws (recordOutcome 失败只 log warn)
- `enabled=false` 跳过整个 self-eval 路径

### 6. `TalonSelfReflectWiring` 集成 (R244.1)

Wiring 现在默认返回 **4 middlewares**:
1. SelfReflectMiddleware (失败反思)
2. SuccessReflectMiddleware (成功反思)
3. BankRecallMiddleware (读侧 recall)
4. **SelfEvalMiddleware** (新) — 写侧 feedback loop

---

## Tests (14 new)

| 套件 | tests | 覆盖 |
|---|---:|---|
| `SelfEvalTest` | 14 | confidence 数学 (4) / recordOutcome (2) / classifier (4) / middleware 集成 (3) / 排序验证 (1) |

**新增 14 tests 详细**:
- `confidenceWithZeroObservationsIsSmoothed` (Laplace)
- `confidenceApproachesOneWithAllOk`
- `confidenceApproachesZeroWithAllNotOk`
- `withOutcomeBumpsTheRightCounter`
- `recordOutcomeUnknownIdReturnsEmpty`
- `recordOutcomeUpdatesBankInPlace`
- `heuristicClassifiesExceptionAsNotOk`
- `heuristicClassifiesNullResultAsNotOk`
- `heuristicClassifiesSuccessfulCallAsOk`
- `neverClassifierAlwaysReportsOk`
- `successfulCallBumpsOkCountOnRecalledUnits`
- `failingCallBumpsNotOkCount`
- `disabledMiddlewareSkipsEvaluation`
- `recallRankingPrefersHighConfidenceHighUtility`

### Cumulative

```
aethercode-deepagents: 209 tests  (R241.2 + R241.3 + R243.1 + R243.2 + R243.3 + R244.1 + 之前累积)
                        0 fail
aethercode-talon:        5 tests  (R243.2B 5)
                        0 fail
```

---

## 关键设计决定 (R244.1)

1. **加 2 字段 (okCount, notOkCount) 到 ReasoningUnit record** — 紧凑 + 持久化一起搞定, 比新建 sidecar table 简单
2. **8-arg 兼容构造器** — R241.2/R241.3/R243 时代的 call site 0 改动继续编译
3. **Laplace-smoothed confidence** — `+1` 在分母让零观察的 unit 不至于被压成 0
4. **`confidence × utility` 排序** — 单因子变双因子, 高 utility 错误 unit 自动降权
5. **Heuristic 默认 (no exception = ok)** — 零成本, 不调 LLM
6. **recordOutcome 对 recall 列表所有 unit 调** — 召回越频繁的 unit, 评估反馈越频繁
7. **`Evaluation` record 工厂方法改 `of(boolean, String)`** — 避免 `ok()` 跟 record accessor `ok()` 冲突
8. **SelfEvalMiddleware 独立 (不并入 BankRecall)** — 写侧 (wrapToolCall) vs 读侧 (beforeModel) 不同生命周期
9. **never-throws** — recordOutcome 异常只 log warn, 不污染 tool call
10. **disabled flag** — host 可以关 self-eval (e.g. 单元测试场景)

---

## What did NOT ship in R244.1 (deferred to R244.2+)

1. **Self-eval metric 写 MemoryAudit** — bank.okCount / bank.notOkCount 是 in-memory + 持久化, 没接 R230 MemoryAudit
2. **R244.2 O-10 跨 surface** — bank 现在 desktop/TUI/IntelliJ 都能用 (因为 aethercode-deepagents 是公共模块)
3. **periodic decayPass 自动 idle tick** — 仍需手动调
4. **confidence-aware DRIFT** — DRIFT 不看 confidence, 只看 utility
5. **失败时回滚** — 现在 recordOutcome 立即 save, 没有 batch/buffer
6. **outlier 检测** — 突然 notOk 大幅上升时报警
7. **per-tool classifier** — 现在 heuristic 对所有 tool 一样; 长跑工具 (e.g. 30s shell) 可能误判

---

## 实战 (R244.1 之后)

**自动生效 (talon deployment)**
```bash
mvn -pl aethercode-talon -am install
java -jar aethercode-talon-0.1.0-SNAPSHOT.jar
# → 4 middlewares 自动 wire (含 SelfEvalMiddleware)
# → tool 成功 → recordOutcome(ok=true) on recalled units
# → tool 失败 → recordOutcome(ok=false) on recalled units
# → 下次 recall 时 confidence × utility 排序
```

**手动 (host-driven)**
```java
ReasoningBank bank = TalonSelfReflectWiring.build(...).bank();
bank.recordOutcome("u1", true);  // 直接调
```

**关闭**
```java
SelfEvalMiddleware mw = new SelfEvalMiddleware(
    bank, recall, SelfEvalClassifier.heuristic(), false);  // enabled=false
```

---

## 教训 (R244.1)

1. **Record accessor 跟静态方法同名冲突** — `record Evaluation(boolean ok, ...)` 自动生成 `boolean ok()` accessor, 我又定义 `static Evaluation ok()` 工厂, javac 报"记录中的存取方法无效". 修: 工厂改名 `Evaluation.of(boolean, String)`. **结论: record field 名跟工厂方法名要避免冲突**
2. **8-arg 兼容构造器是 R230/R241.2 经验** — 加 2 字段前 R241.2/R241.3/R243 6 个 test 文件用 8-arg `new ReasoningUnit(...)`, 加字段会全崩. 修: 在 canonical 10-arg 之外显式加 8-arg 委托构造器, 输入 `0L, 0L`. **结论: record 字段扩展时永远保留老构造器**
3. **Surefire `--%` 模式 `+` 不解析** — 一次跑 7 个 test class 用逗号分隔 (沿用 R241.2 经验)
4. **TalonSelfReflectWiringTest 期望 3 middlewares** — 加 SelfEval 后变 4, 必须改测试. **结论: 增 middleware 必查 wiring test**
5. **Laplace smoothing 重要** — 零观察的 unit 不至于 confidence=0 全废, 总能用
6. **`confidence × utility` 排序单测要构造"高 utility 零观察"+"高 utility 多观察"对比** — 才能验证排序生效

---

## 关键文件 SHA / 路径

**新增 2 java** (在 `aethercode-deepagents/.../selfimprove/`):
- `SelfEvalClassifier.java` (3.0 KB)
- `SelfEvalMiddleware.java` (6.7 KB)

**修改 3 java**:
- `ReasoningUnit.java` (5.3 KB) — +2 字段 + withOutcome + confidence + 8-arg 兼容构造器
- `ReasoningBank.java` (+ recordOutcome)
- `BankRecallMiddleware.java` (排序公式升级, formatRecall 加 confidence)
- `TalonSelfReflectWiring.java` (4 middlewares)

**新增 1 test 套件** (在 `aethercode-deepagents/.../test/.../selfimprove/`):
- `SelfEvalTest.java` (9.1 KB, 14 tests)

**修改 1 test 套件**:
- `TalonSelfReflectWiringTest.java` (期望 3 → 4 middlewares)

**报告**:
- `doc/项目文档/R244-1-SELF-EVAL-CONFIDENCE-METRIC.md` (本文)
- 父报告: `doc/项目文档/R243-3-DRIFT-BANK-TO-AGENTS.md`
- 祖报告: `doc/项目文档/R241-EXPERIENCESTORE-STRATEGY-LIBRARY.md`

---

## Backups

- R241.2/R241.3/R243.1/R243.2/R243.2B/R243.3 报告保持原样
- R244.1 是 R239 路线图 O-6 持续学习维度第一个 round
- 出包策略: R244.1 跟前 10 round 一起下次 release 时整体出 0.2.58

---

## Next (R239 路线图剩余)

1. **R244.2 O-10 跨 surface** — 估 1-2 round
2. **出包 0.2.58** — 1 round, 工程里程碑
