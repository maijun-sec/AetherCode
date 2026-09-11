# R-radar-2: aethercode-evals 测试补齐 (0 → 127)

**Round**: R-radar-2  
**Date**: 2026-09-12  
**Module**: `aethercode/aethercode-evals`  
**Goal**: 把 aethercode-evals 模块的测试覆盖从 0 提升到 50+ (实际 127)。

## TL;DR

- **0 → 127 tests** 跨 5 个 test class
- **0 失败 / 0 错误 / 0 跳过**
- `mvn -B -pl aethercode-evals test` 全过 (18.4s)
- 改 1 个 pom.xml (放开 main + test 编译), 加 1 个 test resource (categories.json)
- 发现 2 个 existing parser bug, 写成 regression test 锁定行为

## 产出 (5 个新 test class + 2 个 fixture)

| Test class | Tests | 文件 | 覆盖范围 |
|---|---:|---|---|
| `Tau3SubsetTest` | 16 | `src/test/java/.../evals/Tau3SubsetTest.java` | 30 任务分层 / 难度分布 / 前缀校验 / include_tasks 一致性 (R-radar-1 写, 本 round 修正难度分布数 2/8/20 → 2/7/21) |
| `ClbenchTypesTest` | 15 | `src/test/java/.../clbench/system/ClbenchTypesTest.java` | 4 record (Observation/Query/Response/UsageEvent) + SystemRegistry 注册/查/不可变 + ContinualLearningSystem 契约 |
| `RadarTest` | 33 | `src/test/java/.../evals/RadarTest.java` | Theme LIGHT/DARK + generateRadar 多边形闭合 + 颜色循环 + loadResultsFromSummary + toyData 验证 + safeFilename / shortModelName |
| `CliTest` | 46 | `src/test/java/.../evals/CliTest.java` | 4 EXIT_* 码 + ShellRunner seam + 7 子命令 dispatch + validateModel + buildSingleTrialArgv + Parser parse path + 2 个 parser bug 锁定 |
| `DeepAgentsSystemTest` | 17 | `src/test/java/.../clbench/system/DeepAgentsSystemTest.java` | AGENT_MEMORY_PATH / SYSTEM_PROMPT / MEMORY_SOURCES / SEED 常量 + supportsBaseline / parallelSafe + recordUsageEvent + reset + DeepAgentFactory stand-in |
| **Total** | **127** | | |

**Fixture 改动**:
- `pom.xml` — 去掉 `<skipMain>true</skipMain>` + `<skipTestCompile>true</skipTestCompile>` + `<skipTests>true</skipTests>`, 改 surefire 排除 Drbench/Contextbench adapter tests (那些需要 python-side fixtures)
- `src/test/resources/io/deepagents/evals/categories.json` — 8 categories + 6 radar categories + 8 labels, 让 `Radar` 的 static init 能从 classpath 加载

## 关键决定 (7 条)

1. **放 pom skip flags** — 原 pom 把 main + test compile 都 skip, 这是 "默认不进 build" 的保守策略, 但既然有测试就该 compile。改成正常 compile + surefire, exclude DrbenchAdapter / ContextbenchAdapter 这两个需要 python fixtures 的 (R-radar 后续再加)
2. **categories.json 放 test resources** — Radar 静态初始化要 `/io/deepagents/evals/categories.json`, 主 source 里没有。放 test classpath (`src/test/resources/...`) 而不是 `src/main/resources/...` 是因为 Radar 真的不该在 production code 里 hardcode 自己的 category 列表 (上游数据是 Python 源 of truth, Java port 只是 mirror)
3. **Tau3Subset 难度分布修正** — 我之前 hardcode 2 EASY / 8 MEDIUM / 20 HARD, 实际 TASKS 是 2 / 7 / 21 (2+7+21=30 仍然 30 个任务, 只是 MEDIUM/HARD 各移 1 个)。测试更准。
4. **发现并锁定 parser bug #1 (remainder mode 早开)** — `run` / `trials` / `radar` 声明了 `addRemainder("pytest_extra")` 之后, parser 从 START 就进入 remainder 模式, 任何 `--model X` 都被吞进 pytestExtra, 不会触发 option 解析。这是 production code 的 bug, 但 R-radar-2 范围是测试, 不修。改成测试 expect EXIT_CONFIG (validateModel 失败) + 直接通过 `Parser.parse` 验证 capture 行为
5. **发现并锁定 parser bug #2 (嵌套 subcommand options 不下钻)** — `list models --group X` 会 reject `--group`, 因为 parser 只查 `list` 这一级, 不下钻到 `listModels` 那个 sub-sub 的 options 表。同样 regression test 锁定
6. **CliTest 用 catalog/model-groups 验证 ShellRunner seam** — `run` / `trials` 因为 bug #1 无法用常规 `main(["run", ...])` 触发 shell 路径, 改用 `catalog --check` (无 remainder, 无 dry-run) 验证 shell 路径
7. **DeepAgentFactory 是 stand-in, 不是真 LLM** — 看 `DeepAgentFactory.invoke` 的 JavaDoc 注释: "returns a deterministic stand-in: the structured response is a freshly constructed instance of the requested schema class". 所以测试用普通 schema class (`Action`) 就能跑通整个 orchestration, 不用 mock LLM provider

## CliTest 设计要点 (8 条)

1. **captureStdout helper** — 用 ByteArrayOutputStream + UTF-8 PrintStream 把 `main()` 输出抓到 String, 用 `||` 分割 rc 和 body
2. **CliThunk functional interface** — 统一 `(out, runner) -> rc` 签名, 让 captureStdout 一行调用 dispatch
3. **exit code 常量直接 assertEquals** — 锁定 0/1/2/3 四个值, Python 文档化的契约
4. **ShellRunner mock 用 AtomicInteger + AtomicReference** — 既能验证调用次数, 也能捕获最后的 cmd list
5. **validateModel 直接调用, 不通过 main** — 避免 dispatch 干扰
6. **buildSingleTrialArgv 直接传 Parsed object** — 不依赖 parser 正确性, 测 buildSingleTrialArgv 自己的逻辑
7. **catalog/model-groups 验证 dry-run** — 这两个无 remainder, 是唯一能在不触发 bug #1 的情况下测 dry-run 输出和 --json 输出的 subcommand
8. **Parser.parse 单元测试** — 跨 catalog/model-groups/aggregate/list 测正常 parse 路径, 同时测 2 个 bug 锁定

## CliTest 失败的 parser bug 详细分析

### Bug #1: remainder mode 早开 (run / trials / radar)

**症状**:
- `main(["run", "--model", "X"])` → rc=EXIT_CONFIG, shell never called
- 实际流程:
  1. parser 看到 `run` 选了 `run` subcommand
  2. parser 扫到 `run` 的 options 里有 `remainder("pytest_extra")` → `parsed.remainder = true`
  3. parser 进 while loop, 看到 `--model`, 因为 `parsed.remainder=true` → `pytestExtra.add("--model")`
  4. `X` 同样进 pytestExtra
  5. dispatch `cmdRun` → `validateModel(args)` → `args.model` 还是 null → `parser.error()` → exitCode=2
- 期望: `run --model X` 让 `args.model = "X"`, dispatch 后 shell 被调用

**影响**: `run` / `trials` / `radar` 三个 subcommand 实际上无法用 `main(["run", "--model", "X"])` 这样的方式调用, 因为所有 options 都被吞。

**正确行为** (Python argparse 行为):
- remainder 应该在看到 `--` sentinel 之后才进入
- 或者在看到第一个非 option token 之后

**R-radar-2 决定**: 写 regression test 锁定 actual behavior, 不修 bug, 等 R-radar 后续 round 再修 (或单独 R-round)。

### Bug #2: 嵌套 subcommand options 不下钻 (list)

**症状**:
- `Parser.parse(["list", "models", "--group", "set0"])` → `Optional.empty()` + exitCode=2
- 实际: parser 找到 `list` 这一级, 但只看 `list` 自己的 options (`--json`), 看不到挂在 `listModels` sub-sub 上的 `--group` / `--provider`
- 类似 `list evals --category X` 也 reject

**正确行为**: parser 应该把 args[1] 之后当作 sub-sub, 然后看 sub-sub 的 options。

**R-radar-2 决定**: 写 regression test 锁定 actual behavior (reject), 不修。

## Maven 配置 (变更)

**Before**:
```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
                <skipMain>true</skipMain>
                <skipTestCompile>true</skipTestCompile>
            </configuration>
        </plugin>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-surefire-plugin</artifactId>
            <configuration>
                <skipTests>true</skipTests>
            </configuration>
        </plugin>
    </plugins>
</build>
```

**After**:
```xml
<build>
    <plugins>
        <!--
            R-radar-2: the evals module is self-contained (no other
            module depends on it), so compile + test in the normal
            `mvn test` cycle. Tests are pure-Java and don't shell
            out to uv / pytest; the ShellRunner seam in Cli keeps
            that side effect under test control.
        -->
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-surefire-plugin</artifactId>
            <configuration>
                <excludes>
                    <exclude>**/Drbench*Test.java</exclude>
                    <exclude>**/Contextbench*Test.java</exclude>
                </excludes>
            </configuration>
        </plugin>
    </plugins>
</build>
```

## 测试运行

```bash
mvn -B -pl aethercode-evals test

# 输出 (节选):
[INFO] Tests run: 15, Failures: 0, Errors: 0, Skipped: 0 -- ClbenchTypesTest
[INFO] Tests run: 17, Failures: 0, Errors: 0, Skipped: 0 -- DeepAgentsSystemTest
[INFO] Tests run: 46, Failures: 0, Errors: 0, Skipped: 0 -- CliTest
[INFO] Tests run: 33, Failures: 0, Errors: 0, Skipped: 0 -- RadarTest
[INFO] Tests run: 16, Failures: 0, Errors: 0, Skipped: 0 -- Tau3SubsetTest
[INFO] Tests run: 127, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

注意: stderr 上会看到一堆 `error: --model is required` / `error: unknown option: --group` — 这些是 CliTest 在测 parser error 路径时, validateModel / unknown option 主动 print 的。`Parser.error()` 直接 println 到 System.err, 不影响 test 正确性。R-radar 后续 round 可以把 error 改用 PrintStream 注入来静音。

## 跨 Round 影响

- **aethercode-core 编译时间**: 因为 evals 之前 skip main, 现在 compile 进 build cycle, 整个 reactor 编译时间从 30s+ → 50s+ (实测 18.4s 是 evals 单 module + 依赖, 全 reactor ~50s)。如果觉得慢, R-radar 后续可以加 `<profile><id>full</id>...</profile>` 把 evals tests 默认 skip, 用 `-Pfull` 才跑
- **R-radar-3 (evals.md) 文档**: 可以直接引用本 round 建立的 test 体系, 不需要再讲 "为什么没测试"
- **R-radar-6 (V 校验器)**: 会用到 ContinualLearningSystem 的 respond() + observe() 接口, DeepAgentsSystemTest 是好范本
- **R-radar-7 (Self-correction)**: 也会加 usage event 类的事件, recordUsageEvent 已经有现成接口

## 教训 (新增 8 条, 累计 149+)

142. **🆕 测试先行不意味不修 bug**: 发现 2 个 parser bug 但 R-radar-2 范围是 "0 → 50+ tests", 不修。修 bug 是单独的 round。先写 regression test 锁定 actual behavior 是标准做法
143. **🆕 production code 不写 dead data fixtures**: Radar 类的 static init 依赖 `categories.json`, 但 production source tree 里没有, 也没在 main resources 里 ship。这是 porting 时没做完的 step。Test fixture 补这个, 不污染 main
144. **🆕 `List.contains` 是 exact match, 不是 substring**: CliTest 自定义 shell runner 测 `cmd.contains("generate_eval_catalog.py")` 失败, 实际 list 里是 `"scripts/generate_eval_catalog.py"`。要么用 full token, 要么 `cmd.toString().contains(...)`
145. **🆕 跨平台 path**: `Path.of("/tmp/x")` 在 Windows render 成 `\tmp\x`, assertion 要用相对路径或 normalize
146. **🆕 Maven stderr 噪音 vs exit code**: `mvn` 即使 stderr 大量输出, BUILD SUCCESS 仍 exit 0。PowerShell pipe `2>&1 | Select-String` 会把 Select-String 的 exit code 当 final, 而不是 mvn 的
147. **🆕 XML comment 不能含 `--`**: pom.xml 的注释里有 `--` 会导致 POM parse 失败。`-- the ShellRunner` 这种写法要避免, 改成 "the ShellRunner"
148. **🆕 `Class.forName(name, true, loader)` 触发 static init**: 测 SystemRegistry 时, 不调用 `new DeepAgentsSystem()` 不会触发 static 注册。用 `Class.forName` 强制 load 是干净的
149. **🆕 remainder mode argparse port 的设计陷阱**: 真实的 argparse 是 "看到 `--` 之后才进 remainder", 但这个 port 是 "看到 remainder declaration 立刻进 mode"。后者更简单但限制更多, 应该直接 follow Python 行为, 而不是自己重新设计

## 后续 (R-radar-3+)

- **R-radar-3**: 写 `doc/tech-docs/evals.md` (5 benchmark 详解), 直接引用本 round 的 5 个 test class
- **R-radar-4**: 加 `google_scholar` tool
- **R-radar-6**: V 校验器框架, 复用 ContinualLearningSystem 契约
- **R-radar-7**: Self-correction 机制
- **可能单独 R-round**: 修 Cli parser 2 个 bug (remainder mode + nested sub-options)
