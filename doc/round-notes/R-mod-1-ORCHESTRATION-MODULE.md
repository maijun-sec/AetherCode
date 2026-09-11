# R-mod-1 aethercode-orchestration module 抽离 (2026-09-12)

## 触发

R-radar-6/7/8 + R-orch-1/2 + R-perf-1 在 `aethercode-evals` 下加了 5 个
新 package, 22 个源文件 + 18 个测试文件:

- `org.aethercode.evals.verifier.*` (7 类) — V 校验器框架
- `org.aethercode.evals.selfcorrect.*` (5 类) — Self-correction 机制
- `org.aethercode.evals.multiagent.*` (5 类) — Multi-Agent 对抗
- `org.aethercode.evals.orchestration.*` (2 类) — AgentRuntime + RuntimeTrace
- `org.aethercode.evals.perf.*` (3 类) — CostCeiling + ActionCache + TokenCounter

这些是 **infrastructure** — V / self-correct / ensemble / runtime / cost
控制. 不依赖 eval benchmark, 跟 clbench / Tau3 / radar 没关系.
R-mod-1 把它们从 `aethercode-evals` 抽到独立 `aethercode-orchestration`
module, 给其他 module 复用 (deepagents / tools / workflows).

## 实际产出 (1 module 脚手架 + 1 plan doc, 1 round)

- 新建 `aethercode/aethercode-orchestration/` 目录 + `pom.xml` + `README.md`
- 顶层 `aethercode/pom.xml` 加 `<module>aethercode-orchestration</module>` +
  `dependencyManagement` entry
- 22 个源文件 + 测试暂未移动, 留 R-mod-2 做. R-mod-1 走"脚手架先, 实际
  抽 next batch"路线, 减少 R-mod-1 改动 risk
- `aethercode-orchestration` 当前是空 module, `mvn install -DskipTests`
  BUILD SUCCESS

## 关键技术决定 (5 条)

1. **先脚手架, 后实际抽** - R-mod-1 只建 module + 顶层 pom 注册 + README,
   不动 22 个源文件. R-mod-2 才物理 move + 改 import. 避免一次性大改.
2. **package 路径: `org.aethercode.orchestration.*`** - 不复用
   `aethercode.evals.*`, 拆得更干净
3. **orchestration → runtime 重命名** - `evals.orchestration.AgentRuntime`
   改成 `orchestration.runtime.AgentRuntime` (避免 module 名跟 package 名
   冲突, 也更精确 — orchestration 是 module, runtime 是其中一个 package)
4. **顶层 pom 加 dependencyManagement 占位** - R-mod-2 后 aethercode-evals
   会 declare `<dependency>aethercode-orchestration</dependency>`. R-mod-1
   把 entry 加好, R-mod-2 只动 aethercode-evals/pom.xml 一处
5. **不抽 `aethercode.evals.evals.*` (Cli/Radar/Tau3Subset/...)** - 这些
   是真的 eval-benchmark glue, 留在 evals module. R-mod-1 只抽通用的
   orchestration infrastructure

## Package mapping (R-mod-2 实际抽时用)

| 旧 (aethercode-evals) | 新 (aethercode-orchestration) |
|---|---|
| `org.aethercode.evals.verifier.Verifier` | `org.aethercode.orchestration.verifier.Verifier` |
| `org.aethercode.evals.verifier.CompositeVerifier` | `org.aethercode.orchestration.verifier.CompositeVerifier` |
| `org.aethercode.evals.verifier.SchemaVerifier` | `org.aethercode.orchestration.verifier.SchemaVerifier` |
| `org.aethercode.evals.verifier.RuleVerifier` | `org.aethercode.orchestration.verifier.RuleVerifier` |
| `org.aethercode.evals.verifier.HeuristicVerifier` | `org.aethercode.orchestration.verifier.HeuristicVerifier` |
| `org.aethercode.evals.verifier.LlmJudgeVerifier` | `org.aethercode.orchestration.verifier.LlmJudgeVerifier` |
| `org.aethercode.evals.verifier.HumanVerifier` | `org.aethercode.orchestration.verifier.HumanVerifier` |
| `org.aethercode.evals.selfcorrect.CorrectionStrategy` | `org.aethercode.orchestration.selfcorrect.CorrectionStrategy` |
| `org.aethercode.evals.selfcorrect.SelfCorrectionLoop` | `org.aethercode.orchestration.selfcorrect.SelfCorrectionLoop` |
| `org.aethercode.evals.selfcorrect.RetryStrategy` | `org.aethercode.orchestration.selfcorrect.RetryStrategy` |
| `org.aethercode.evals.selfcorrect.LlmSelfCorrectionStrategy` | `org.aethercode.orchestration.selfcorrect.LlmSelfCorrectionStrategy` |
| `org.aethercode.evals.selfcorrect.HumanCorrectionStrategy` | `org.aethercode.orchestration.selfcorrect.HumanCorrectionStrategy` |
| `org.aethercode.evals.multiagent.AgentFn` | `org.aethercode.orchestration.multiagent.AgentFn` |
| `org.aethercode.evals.multiagent.MultiAgentOrchestrator` | `org.aethercode.orchestration.multiagent.MultiAgentOrchestrator` |
| `org.aethercode.evals.multiagent.VoteStrategy` | `org.aethercode.orchestration.multiagent.VoteStrategy` |
| `org.aethercode.evals.multiagent.DebateStrategy` | `org.aethercode.orchestration.multiagent.DebateStrategy` |
| `org.aethercode.evals.multiagent.CritiqueStrategy` | `org.aethercode.orchestration.multiagent.CritiqueStrategy` |
| `org.aethercode.evals.orchestration.AgentRuntime` | `org.aethercode.orchestration.runtime.AgentRuntime` |
| `org.aethercode.evals.orchestration.RuntimeTrace` | `org.aethercode.orchestration.runtime.RuntimeTrace` |
| `org.aethercode.evals.perf.CostCeiling` | `org.aethercode.orchestration.perf.CostCeiling` |
| `org.aethercode.evals.perf.ActionCache` | `org.aethercode.orchestration.perf.ActionCache` |
| `org.aethercode.evals.perf.TokenCounter` | `org.aethercode.orchestration.perf.TokenCounter` |

## 引用方 (R-mod-2 要改 import 的文件)

- `aethercode-evals/src/main/java/org/aethercode/evals/evals/Cli.java` —
  用 `Verifier` 类型 (在 self-correct test) + `multiagent.MultiAgentOrchestrator`
  (在 evals test)
- `aethercode-evals/src/main/java/org/aethercode/evals/clbench/system/DeepAgentsSystem.java` —
  R-orch-2 加的 `AgentRuntime` import
- `aethercode-evals/src/test/java/org/aethercode/evals/**` — 所有 18 个测试文件
  + clbench / evals / multiagent / orchestration / perf / selfcorrect / verifier

## R-mod-2 migration script (执行计划)

```bash
# 1. 物理 move 源文件 + 测试
git mv aethercode-evals/src/main/java/org/aethercode/evals/verifier \
      aethercode-orchestration/src/main/java/org/aethercode/orchestration/
git mv aethercode-evals/src/main/java/org/aethercode/evals/selfcorrect \
      aethercode-orchestration/src/main/java/org/aethercode/orchestration/
git mv aethercode-evals/src/main/java/org/aethercode/evals/multiagent \
      aethercode-orchestration/src/main/java/org/aethercode/orchestration/
git mv aethercode-evals/src/main/java/org/aethercode/evals/orchestration \
      aethercode-orchestration/src/main/java/org/aethercode/orchestration/runtime
git mv aethercode-evals/src/main/java/org/aethercode/evals/perf \
      aethercode-orchestration/src/main/java/org/aethercode/orchestration/
# (同样 move test 目录)

# 2. 全局改 package 路径
find aethercode-orchestration/src -name '*.java' -exec sed -i \
  's|package org.aethercode.evals.|package org.aethercode.orchestration.|g' {} +

# 3. 全局改 import 路径 (在所有 evals 文件)
find aethercode -name '*.java' -exec sed -i \
  's|import org.aethercode.evals.verifier.|import org.aethercode.orchestration.verifier.|g' {} +
# 同样 4 个其他 package

# 4. aethercode-evals/pom.xml 加 dependency
# (在 <dependencies> 加 aethercode-orchestration)

# 5. 跑测试
mvn -B -pl aethercode-orchestration test
mvn -B -pl aethercode-evals test
mvn -B test
```

## 累计测试 (R-mod-1 后)

- aethercode-orchestration: 0 (空 module, 等 R-mod-2 才有测试)
- 其他 module 跟 R-perf-1 后一样: 395/395 evals + 253 deepagents + ...
- **0 回归** (R-mod-1 没动任何 source)

## 教训 (新增 5 条, 累计 227+)

223. **先脚手架后 actual move** - 大改动拆 2 步走, 减少 risk + 给
     future round 留 hook
224. **package 名 ≠ module 名** - orchestration module 下有多个
     sub-package (verifier / selfcorrect / multiagent / runtime / perf),
     避免 module/package 同名混淆
225. **顶层 pom dependencyManagement 提前占位** - R-mod-2 不用改
     parent pom, 只动 evals/pom.xml 加 dependency
226. **只抽通用的, 不抽 evals-specific** - clbench / Tau3 / Radar / Cli
     留 evals module, R-mod-1 只抽真 infrastructure
227. **README 解释 status** - 标明"脚手架已建, 实际抽在 R-mod-2",
     避免 reader 困惑"为什么 module 是空"

## 后续

- R-mod-2: 实际物理 move 5 个 package + 改 import 路径 + 跑全测试
- R-orch-3: 跟 `reference/papers/` 8 篇 paper 一起跑 end-to-end, 用上
  AgentRuntime + cost ceiling
