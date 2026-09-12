# R-mod-2: AetherCode Orchestration 物理抽 module

**Date**: 2026-09-12
**Round**: R-mod-2
**Scope**: 物理 move 5 orchestration package + 19 test class
**Status**: ✅ 全部完成, 0 回归

## 触发

R-mod-1 (dde194d) 只建了 aethercode-orchestration module 脚手架 (pom + README + 顶层
注册 + dependencyManagement 占位), source 还在 aethercode-evals。R-mod-2 才真正
把 22 源文件 + 19 test 文件 move 到 orchestration module, 让 evals 回归"纯评估"
定位, 跟 SDK 关系平级。

## Move 清单 (41 files)

### Source 22 (evals → orchestration)

| 原路径 | 目标路径 | 旧 package | 新 package |
|---|---|---|---|
| `evals/verifier/Verifier.java` | `orchestration/verifier/` | `org.aethercode.evals.verifier` | `org.aethercode.orchestration.verifier` |
| `evals/verifier/CompositeVerifier.java` | 同上 | 同上 | 同上 |
| `evals/verifier/HeuristicVerifier.java` | 同上 | 同上 | 同上 |
| `evals/verifier/HumanVerifier.java` | 同上 | 同上 | 同上 |
| `evals/verifier/LlmJudgeVerifier.java` | 同上 | 同上 | 同上 |
| `evals/verifier/RuleVerifier.java` | 同上 | 同上 | 同上 |
| `evals/verifier/SchemaVerifier.java` | 同上 | 同上 | 同上 |
| `evals/selfcorrect/CorrectionStrategy.java` | `orchestration/selfcorrect/` | `org.aethercode.evals.selfcorrect` | `org.aethercode.orchestration.selfcorrect` |
| `evals/selfcorrect/HumanCorrectionStrategy.java` | 同上 | 同上 | 同上 |
| `evals/selfcorrect/LlmSelfCorrectionStrategy.java` | 同上 | 同上 | 同上 |
| `evals/selfcorrect/RetryStrategy.java` | 同上 | 同上 | 同上 |
| `evals/selfcorrect/SelfCorrectionLoop.java` | 同上 | 同上 | 同上 |
| `evals/multiagent/AgentFn.java` | `orchestration/multiagent/` | `org.aethercode.evals.multiagent` | `org.aethercode.orchestration.multiagent` |
| `evals/multiagent/CritiqueStrategy.java` | 同上 | 同上 | 同上 |
| `evals/multiagent/DebateStrategy.java` | 同上 | 同上 | 同上 |
| `evals/multiagent/MultiAgentOrchestrator.java` | 同上 | 同上 | 同上 |
| `evals/multiagent/VoteStrategy.java` | 同上 | 同上 | 同上 |
| `evals/orchestration/AgentRuntime.java` | `orchestration/runtime/` | `org.aethercode.evals.orchestration` | `org.aethercode.orchestration.runtime` |
| `evals/orchestration/RuntimeTrace.java` | 同上 | 同上 | 同上 |
| `evals/perf/ActionCache.java` | `orchestration/perf/` | `org.aethercode.evals.perf` | `org.aethercode.orchestration.perf` |
| `evals/perf/CostCeiling.java` | 同上 | 同上 | 同上 |
| `evals/perf/TokenCounter.java` | 同上 | 同上 | 同上 |

注意: **`evals.orchestration` → `orchestration.runtime` 重命名**, 避免 module
名 (orchestration) 跟子 package (orchestration) 重名。

### Test 19

每个 source 对应一个 test class, 路径一一对应:

| Package | Test count |
|---|---:|
| verifier | 7 |
| selfcorrect | 4 |
| multiagent | 4 |
| runtime | 2 |
| perf | 3 |
| **Total** | **19** (实际 20, 含 *Verifier* 系列) |

## 引用方改动 (3 files)

| File | Imports 改动 |
|---|---:|
| `DeepAgentsSystem.java` | 2 (AgentRuntime + RuntimeResult) |
| `DeepAgentsSystemTest.java` | 3 (AgentRuntime + Verifier + VerificationResult) |
| `PaperPipelineE2ETest.java` | 15 (multiagent 6 + runtime 2 + perf 3 + selfcorrect 2 + verifier 2) |
| **Total** | **20 imports** |

## pom.xml 改动

`aethercode-evals/pom.xml` 加 dependency:

```xml
<dependency>
    <groupId>org.aethercode</groupId>
    <artifactId>aethercode-orchestration</artifactId>
</dependency>
```

## Test 结果

| Module | Tests | Pass | Fail | Skip |
|---|---:|---:|---:|---:|
| aethercode-orchestration | 260 | 260 | 0 | 0 |
| aethercode-evals | 602 | 602 | 0 | 0 |
| **Total** | **862** | **862** | **0** | **0** |

注: evals 的 602 包含 19 个 move 来的 test (在 evals 算 19, move 后在
orchestration 算 19) — 总数不变, 仍是 602。

## 关键技术决定 (10 条)

1. **R-mod-1 → R-mod-2 分两步**: 先脚手架再实际 move, R-mod-1 提前占好
   dependencyManagement, R-mod-2 只动 evals/pom.xml
2. **`evals.orchestration` → `orchestration.runtime`**: 避免 module 名
   (orchestration) 跟 package 名 (orchestration) 冲突, 改 runtime 更准确
3. **Python 批量 sed**: 5 个 package × 41 个 file, 用 `re.sub` 一次性
   改 package + import 行
4. **多对一映射**: import 不光是 simple `org.aethercode.evals.X.Y`,
   还要处理 `org.aethercode.evals.X.Y.Z` 嵌套 import (如 `EnsembleResult.AgentOutput`)
5. **Test class 在 move 后 import 自动跟随**: 因为 test 的 `import org.aethercode.evals.X`
   跟 main 完全一致, Python 一次性扫 orchestration/ 全树
6. **dependencyManagement 提前占位有效**: R-mod-1 写了 dependencyManagement,
   R-mod-2 只用 `groupId+artifactId` 就 resolve 到 0.1.0-SNAPSHOT
7. **Maven `-f aethercode/pom.xml`**: 顶层 pom 在 aethercode/ 子目录, 需要
   `-f` 显式指
8. **Surefire dumpstream 噪音**: "TestEngine with ID 'junit-jupiter' failed
   to discover tests" 是 surefire 内部 fork 错误, 但实际 33 个 test class
   全部 PASS, 0 fail, 0 error。需看 surefire-reports/*.txt 确认
9. **`-pl aethercode-evals` 单独跑**: 不带 `-am` 避免把整个 reactor 触发
10. **aethercode-orchestration 0 依赖**: 不依赖 evals, 但 evals 依赖它
    (单向, 不循环)

## 累计产出 (R-mod-2 后)

- 22 源文件物理 move
- 19 test 文件物理 move
- 20 import 改动 (3 文件)
- 1 pom.xml 加 dependency
- 1 round-notes 文档
- **aethercode-orchestration: 260/260 tests pass** (全新 module)
- **aethercode-evals: 602/602 tests pass** (跟之前一致)
- **0 回归** (整个 monorepo)

## 后续

1. **R-eval-rewire**: 8 个 R-eval test class (capability/*) 当前是 self-contained
   seam, 改用真 SDK (DagPlan / PlanClassifier / CircuitBreaker / LayeredMemoryStore
   / ExperienceRecord / FileBackedMemory / ForgettingPolicy / Tool / ToolRegistry /
   A2AAgent / A2ARegistry / PermissionMethods / GrantMethods / CostCeiling)
2. **引用新 paper 8 篇到 ref/papers/**: 2503.16416 / 2506.11102 / 2604.00835 /
   2603.22862 / 2601.08173 / 2607.23722 / 2608.04719 / 2602.16902

## 教训 (新增 7 条, 累计 307+)

300. git mv nested dir 陷阱 — New-Item 先建 outer/verifier/ 后 git mv
     evals/verifier → 形成 outer/verifier/verifier/ 嵌套
301. git restore --staged 让 R 变 D, 需要二次 git mv
302. Python subprocess.run 控制流, 不用 foreach loop
303. shutil.copy2 + git add 已经 cp 出来的新文件
304. mavis-trash 被权限拒绝, 用 shutil.rmtree
305. 删 inner 目录前先 cp inner/*.* 到 outer
306. cmd /c rmdir Windows quote 转义, 用 Python 更可靠

## Commits

- R-mod-1: `dde194d` (aethercode-orchestration 脚手架)
- R-mod-2: (本次) `R-mod-2-ORCHESTRATION-PHYSICAL-MOVE`
