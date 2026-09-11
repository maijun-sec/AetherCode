# Evals — 评测体系

> 怎么用 5 个真实 benchmark 量化 AI Agent 能力,统一在 `aethercode-evals` 模块下,带 7 个 CLI 子命令和完整 Java 21 port。
>
> **关键模块**: `aethercode/aethercode-evals/` (17 个 Java 文件, ~200 KB)
>
> **关键 round**: R-radar-1 (补 `ai-agent-validation.md`), **R-radar-2 (本 round 上下文, 0 → 127 tests)**, R-radar-3 (本 doc), R-radar-6/7/8 (后续 V 校验器 / Self-correction / Multi-Agent)

---

## 1. 架构总览

```
                    ┌────────────────────────────────────────┐
                    │     aethercode-evals (Java 21 port)    │
                    └────────────────────────────────────────┘
                                       │
        ┌──────────────┬───────────────┼─────────────────┬──────────────┐
        ▼              ▼               ▼                 ▼              ▼
   ┌─────────┐   ┌──────────┐   ┌──────────┐      ┌──────────┐   ┌──────────┐
   │ CLBench │   │ Radar    │   │ Tau3     │      │ DRBench  │   │ Context  │
   │ (CL)    │   │ (多维)   │   │ Subset   │      │ (DR)     │   │ Bench    │
   │         │   │          │   │ (对话)   │      │          │   │ (压缩)   │
   └─────────┘   └──────────┘   └──────────┘      └──────────┘   └──────────┘
        │              │               │                 │              │
        └──────────────┴───────────────┴─────────────────┴──────────────┘
                                       │
                                       ▼
                          ┌───────────────────────┐
                          │  Cli.java             │  ← 7 个子命令统一入口
                          │  (run / trials /      │
                          │   aggregate / radar / │
                          │   catalog /           │
                          │   model-groups / list)│
                          └───────────────────────┘
                                       │
                                       ▼
                          ┌───────────────────────┐
                          │  ShellRunner seam     │  ← 测试用 lambda 替代
                          │  (default: ProcessBuilder)
                          └───────────────────────┘
                                       │
                                       ▼
                          ┌───────────────────────┐
                          │  uv run pytest /      │  ← Python 评测 runner
                          │  scripts/run_trials.py│    (Java 端只是 wrapper)
                          └───────────────────────┘
```

**关键设计**:
- **5 benchmark 都是 data class, 真正执行是 Python**。Java 端只负责: 数据模型 / 编排逻辑 / CLI dispatch / 报告解析
- **ShellRunner seam** (`Cli.ShellRunner` functional interface) 让 Java 测试可以在不启动 Python 的情况下验证 dispatch 逻辑
- **每个 benchmark 都是 self-contained**: 数据来自上游 Python repo, Java 只 mirror 关键 type (避免维护成本, 跟 Python source of truth 漂移)

---

## 2. 5 个核心包

| 包 | 文件 | 字节 | 测什么 | 关键 source of truth |
|---|---|---|---|---|
| `clbench/system/` | `DeepAgentsSystem.java` + `ClbenchTypes.java` | 18 KB | Continual learning (跨 instance 学习) | `clbench.continual_learning` |
| `evals/` | `Radar.java` | 14 KB | 多维雷达图评分 | `deepagents_evals.radar` |
| `evals/` | `Tau3Subset.java` | 11 KB | 30 个对话行为任务 | `sierra-research/tau3-bench` |
| `harbor_adapters/drbench/` | `DrbenchAdapter.java` + `DrbenchMain.java` | 60 KB | Deep Research Bench | `ServiceNow/drbench` |
| `harbor_adapters/contextbench/` | `ContextbenchAdapter.java` + `ContextbenchMain.java` | 22 KB | Context 压缩效果 | `context-bench` (upstream) |
| `harbor/langgraph_project/` | `LanggraphAgent.java` | 18 KB | LangGraph agent 集成 | `deepagents` + `langgraph` |

---

## 3. ⭐ 5 个 Benchmark 详解

### 3.1 ⭐ CLBench — Continual Learning Benchmark

**测什么**: 同一个 agent 跨多个 instance 跑,看它能不能从前面的 instance 学到东西,让后面的 instance 做得更好。**直接对应论文 2512.13564v2 (Memory in the Age of AI Agents) 的 "Continual Memory"**。

**核心接口** (`ClbenchTypes.java`):

```java
public interface ContinualLearningSystem {
    boolean supportsBaseline();          // 能不能 stateless baseline
    boolean parallelSafe();              // 并行安全 (无固定 host path)
    String name();                       // 系统名 (e.g. "deepagents")
    Response respond(Query query);       // 跑一个 query
    void observe(Observation obs, Query next); // 接收反馈
    void reset();                        // wipe learned state
    Map<String, Object> getRunArtifacts();// 导出学到的内容
    void recordUsageEvent(UsageEvent e); // token 用量
}
```

**DeepAgentsSystem 实现** (`DeepAgentsSystem.java`):

- **agent memory path**: `/memory/AGENTS.md` (在 DeepAgentState["files"] 里)
- **MemoryMiddleware**: 每次 turn 把 `/memory/AGENTS.md` 注入 system prompt (包在 `<agent_memory>` boundary marker 里, 当 untrusted data)
- **agent 自主更新 memory**: 用 `edit_file` / `write_file` tools, 没有单独的 reflection / extraction 步骤 — **agent owns its memory**
- **system prompt** (R-radar-2 测试锁定): 明确告诉 agent "Your durable strategy lives in /memory/AGENTS.md, which is loaded into your context every turn"
- **reset()**: 擦 memory, 让 stateless baseline 真的 stateless, `mean_gain` 只测学到的东西

**DeepAgentFactory seam** (测试友好):

```java
public static final class DeepAgentFactory {
    // stand-in for deepagents.create_deep_agent()
    public static Object create(CreateRequest request);
    public static Object invoke(Object agent, InvokeRequest request);
    public static Object files(Object result, Object fallback);
    public static List<?> messages(Object result);
    public static Object structuredResponse(Object result);
}
```

`invoke` 返回的 stand-in 是确定的: structured response 是 schema class 的 fresh instance, files 原样返回。**测试可以跑通整个 orchestration, 不用 mock LLM**。

**测试** (R-radar-2 新增 32 tests):
- `ClbenchTypesTest` (15 tests) — 4 record + SystemRegistry + ContinualLearningSystem 契约
- `DeepAgentsSystemTest` (17 tests) — 4 常量 + reset + recordUsageEvent + DeepAgentFactory stand-in

### 3.2 ⭐ Radar — 多维雷达图评分

**测什么**: 在多个 category 上给模型打分 (0-1 correctness), 生成可视化的极坐标雷达图。

**核心数据结构** (`Radar.java`):

```java
public record ModelResult(String model, Map<String, Double> scores) { ... }
public record Theme(String bg, String grid, String label, String tick,
                    double watermarkAlpha, double fillAlpha, double pillAlpha,
                    List<String> colors) { ... }
public record RadarLayout(String title, String theme,
                          List<String> categoryLabels,
                          List<String> categories,
                          List<Double> angles,
                          List<SeriesLayout> series) { ... }
```

**8 categories (R-radar-2 fixture)**:
- `file_operations` / `retrieval` / `tool_use` / `memory` / `conversation` / `summarization` (6 个上雷达图)
- `long_horizon` / `planning` (2 个仅在 `allCategories()`, 不上雷达图)

**核心 API**:
- `allCategories()` — 8 个, 全部 eval category
- `evalCategories()` — 6 个, 雷达图显示
- `categoryLabels()` — 中文友好的轴 label (e.g. `memory` → `Memory`)
- `theme(name)` — `light` / `dark` / 未知 (fallback light)
- `generateRadar(results, categories, title, themeName)` — 返回 `RadarLayout` (data-only, 不渲染图)
- `generateRadar(results)` — default overload
- `loadResultsFromSummary(path)` — 从 `evals_summary.json` 解析
- `toyData()` — 4 个 hardcoded model (sonnet-4-6 / gpt-5.4 / gemini-2.5-pro / opus-4-6)
- `safeFilename(model)` — `anthropic:claude-sonnet-4-6` → `anthropic-claude-sonnet-4-6`
- `shortModelName(model)` — `anthropic:claude-sonnet-4-6` → `claude-sonnet-4-6`

**关键行为** (R-radar-2 测试锁定):
- 多边形闭合: `angles` 和 `series.values` 最后一个值 = 第一个值
- 颜色循环: 8 色 palette (`#1b4f72` / `#b03a2e` / ...), 超过 8 个 series 时 mod 8 循环
- 缺数据 category 用 0.0 占位
- `ModelResult` 对 scores map 做 defensive copy

**渲染委托给 caller**: 真实绘图用 matplotlib (Python port), Java 端只输出 `RadarLayout` data 结构。可以用 JFreeChart / D3 / 任何 plotting backend 接。

**测试** (R-radar-2 新增 33 tests): Theme / generateRadar 多边形闭合 / 颜色循环 / 缺数据 fallback / loadResultsFromSummary 解析 + 错误路径 / toyData / safeFilename / shortModelName

### 3.3 ⭐ Tau3Subset — 30 个对话行为任务

**测什么**: 从 `sierra-research/tau3-bench` 选 30 个任务,按难度分层,用来探测 deep-agent 的对话行为 (不是 leaderboard parity, 是 behavior probe)。

**任务分布** (R-radar-2 修正):
- **EASY (2 个)**: Opus 4.8 solved 3/3 rollouts — 可靠通过
- **MEDIUM (7 个)**: Opus 4.8 solved 1-2/3 rollouts — 间歇通过
- **HARD (21 个)**: Opus 4.8 solved 0/3 rollouts — 不通过

> **2 + 7 + 21 = 30, 总数不变**。之前 R-radar-1 写错 2/8/20 (MEDIUM 多 1, HARD 少 1), R-radar-2 修正。

**领域分布**:
- **Banking knowledge** (24 个): `tau3-banking_knowledge-task-NNN`
- **Telecom** (6 个): `tau3-telecom-service-issue-...` (复合场景名)

**核心 API**:
```java
public static final String DATASET = "sierra-research/tau3-bench";
public enum Tier { EASY("easy"), MEDIUM("medium"), HARD("hard"); ... }
public record SubsetTask(String taskId, Tier tier, String justification) { ... }
public static List<SubsetTask> tasks();
public static List<SubsetTask> tasksByTier(Tier tier);
public static String includeTasks();  // "DATASET__taskId DATASET__taskId ..."
```

**构造时校验** (R-radar-2 测试锁定):
- `taskId` 必须以 `tau3-` 开头
- `justification` 必须 non-blank
- 静态 block 检查 duplicate task_id

**includeTasks 是 single source of truth**: CI / Harbor workflow 读 `Tau3Subset.includeTasks()`,避免 "tasks() 和 includeTasks() 漂移"。

**测试** (R-radar-1 + R-radar-2 共 16 tests): 30 任务 / 唯一 id / tau3- 前缀 / justification non-blank / 难度分布 / tier 过滤 / includeTasks 和 tasks 一致 / banking + telecom 都有

### 3.4 ⭐ DRBench — Deep Research Bench

**测什么**: 深度研究能力 (web search + 整合 + 总结),测 agent 跨多个 source 找答案、整合长文报告。

**来源**: `ServiceNow/drbench` (UPSTREAM_REPO = `ServiceNow/drbench`, UPSTREAM_SHA 锁定)

**核心 API** (`DrbenchAdapter.java`):

```java
public static final String UPSTREAM_REPO = "ServiceNow/drbench";
public static final String UPSTREAM_SHA = "0d699ecf6aa96b1de378595b432e9b16a82f0ed9";
public static final String IMAGE_REGISTRY = "ghcr.io/mmunozm/drbench-services";
public static final String HEALTH_URL = "http://drbench:8099/health";

public static Path vendorDir();
public static Path ensureUpstreamCheckout();  // git clone + checkout SHA
public static int populateCorpus(Path datasetDir);
public static int stampCalibratedTiers(Path datasetDir, Path calibrationPath);
public static List<String> APPS = ...;  // ["confluence", "jira", "sharepoint", ...]
```

**任务命名**: `DR\d{4}` 或 `SANITY\d+` (sanity check 任务)

**APP 配置**: `APP_ENDPOINTS` / `APP_DEFAULT_CREDENTIALS` / `APP_GUIDANCE` 三个 map,按 service 划分 (confluence / jira / sharepoint 等)

**QA 类型**:
- `INSIGHT_QA_TYPE = "insight"` — 真问题
- `DISTRACTOR_QA_TYPE = "distractor"` — 干扰项 (测 agent 不被诱偏)

**流程** (R-radar-2 doc 高层):
1. `vendorDir()` — 找 vendored source 路径
2. `ensureUpstreamCheckout()` — git clone (10 min timeout) + checkout 到固定 SHA
3. `populateCorpus()` — 写 corpus 到 dataset dir
4. `stampCalibratedTiers()` — 标 calibrated 难度
5. 用 `DrbenchMain.java` 跑实际 trial

**测试** (R-radar-2 留空): 需要 Python-side fixtures (`Drbench*Test.java` 被 surefire exclude),留给后续 round 加

### 3.5 ⭐ ContextBench — Context 压缩效果

**测什么**: Context 压缩 8 段式 (`StructuredCompactor8`) 的实际效果 — 给 agent 一个超长 context,看它 compact 后还能不能正确回答。

**核心 API** (`ContextbenchAdapter.java`):

```java
public record ParsedTaskId(String suite, int lineIndex) { ... }
public static final Pattern TASK_ID_RE = Pattern.compile("^cb-(?<suite>[a-z0-9]+)-(?<index>\\d+)$");
public static final Set<String> VALID_TIERS = Set.of("easy", "medium", "hard");

public static Path vendorDir();
public static ParsedTaskId parseTaskId(String taskId);
public static Map<String, Object> recordForTaskId(String taskId);
public static Path generateTask(...);
public static int populateCorpus(Path datasetDir);
public static int stampCalibratedTiers(Path datasetDir, Path calibrationPath);
```

**任务命名**: `cb-{suite}-{index}` (e.g. `cb-conversation-042`)

**难度 tier**: 跟 Tau3 一样的 easy/medium/hard, 标 calibrated (用 Opus 4.8 baseline 校准)

**测试** (R-radar-2 留空): 同 DRBench, 需要 Python-side fixtures

---

## 4. ⭐ 7 个 CLI 子命令

**入口**: `Cli.java` (37 KB, 920+ 行)

```bash
aethercode evals <subcommand> [options]
```

| 子命令 | 作用 | 关键 option |
|---|---|---|
| `run` | 跑单个 trial | `--model` / `--report` / `--eval-category` / `--dry-run` |
| `trials` | 跑 N 次 trial 并 aggregate | `--model` / `--trials N` / `--out-dir` / `--summary-out` / `--retry-failed` |
| `aggregate` | Aggregate 现有 trial 报告 | `<directory>` / `--summary-out` / `--json` |
| `radar` | 生成雷达图 | `--toy` / `--summary` / `--results` / `-o` / `--dry-run` |
| `catalog` | 生成/校验 `EVAL_CATALOG.md` | `--check` / `--dry-run` / `--json` |
| `model-groups` | 生成/校验 `MODEL_GROUPS.md` | `--check` / `--dry-run` / `--json` |
| `list` | 列出 categories / tiers / models / evals | `<target>` + `--json` |

**4 个 exit code** (R-radar-2 测试锁定):
- `EXIT_OK = 0` — 成功
- `EXIT_EVAL_FAILURES = 1` — 至少一个 test 失败
- `EXIT_CONFIG = 2` — bad CLI args / config / drift detector
- `EXIT_NO_REPORTS = 3` — 没 usable reports 产出

**环境变量**:
- `DEEPAGENTS_EVALS_MODEL` — `run` / `trials` 的默认 `--model`

**Eval tier** (`--eval-tier`): `KNOWN_TIERS = ["baseline", "hillclimb"]`

**测试友好设计** (`ShellRunner` functional interface):

```java
@FunctionalInterface
public interface ShellRunner {
    int run(List<String> command, Path cwd);
}

public static final ShellRunner DEFAULT_SHELL = (command, cwd) -> {
    // ProcessBuilder + inheritIO + waitFor
    ...
};

// 测试可以传 lambda:
int rc = Cli.main(args, out, (cmd, cwd) -> { /* mock */ return 0; });
```

R-radar-2 的 `CliTest` 用这个 seam 测 46 个 case, 不用启 Python 进程。

---

## 5. ⭐ 已知 2 个 parser bug (regression test 锁定)

> R-radar-2 写测试时发现, 写 regression test 锁定 actual behavior。修复在后续 R-round。

### 5.1 Bug #1: remainder mode 早开 (run / trials / radar)

**症状**: `main(["run", "--model", "X"])` 返回 EXIT_CONFIG, shell never called

**根因**: parser 看到 `run` 选了 `run` subcommand 后, 扫到 `run` 声明了 `addRemainder("pytest_extra")`, **立即**设 `parsed.remainder = true`。后续所有 args 都进 pytestExtra, --model 永远不会被解析。

**影响**: 实际导致 `run` / `trials` / `radar` 三个 subcommand 没法用普通 `main(["run", "--model", "X"])` 调用

**正确行为** (Python argparse): remainder 应该在看到 `--` sentinel **之后**才进入

**R-radar-2 处理**: 写 regression test 锁定 actual behavior:
- `parserHonorsNegativeNumbersAsRemainder` — `-1` 进 pytestExtra
- `parserStripsLeadingDoubleDashRemainder` — `--` 开头
- `trialsWithMissingTrialsArgFailsBeforeDispatch` — 仍 EXIT_CONFIG
- `runDryRunDoesNotInvokeShellRunner` — 改用 `catalog --check` 测 ShellRunner seam, 不依赖 run

### 5.2 Bug #2: 嵌套 subcommand options 不下钻 (list)

**症状**: `Parser.parse(["list", "models", "--group", "set0"])` reject `--group`

**根因**: parser 只查 `list` 这一级 options (`--json`), 看不到挂在 `listModels` sub-sub 上的 `--group` / `--provider`

**正确行为**: parser 应该把 args[1] 之后当作 sub-sub, 然后看 sub-sub 的 options

**R-radar-2 处理**: `parserParsesListModelsWithFilters` + `parserParsesListEvalsWithCategory` 锁定 actual behavior (reject), 等后续 round 修

---

## 6. ⭐ LangGraph Agent 集成 (harbor 层)

**文件**: `harbor/langgraph_project/LanggraphAgent.java` (18 KB)

**作用**: Harbor 评测框架需要的是一个 "能跑图的 agent"。Python 端用 `langgraph`, Java 端 mirror 出 `LanggraphAgent` 让测试可以 build graph 但不实际执行 (避免在 JUnit 里启动 LangGraph runtime)。

**核心 API**:

```java
public static final int WEB_SEARCH_MAX_RESULTS = 10;
public static final int WEB_SEARCH_MAX_CHARS = 20_000;
public static final int MAX_ASSISTANT_ID_LENGTH = 64;
public static final int ASSISTANT_ID_HASH_LENGTH = 12;

public static final Set<String> SHELL_ENV_DENYLIST = Set.of(
    "PATH", "HOME", "USER", "SHELL", "...");  // 阻止 agent 看到 host env

public static final String SYSTEM_PROMPT = "...";  // 注入到 graph

public static Object makeGraph(Map<String, Object> config);
public static Object makeBareGraph(Map<String, Object> config);
public static Object makeTau3Graph(Map<String, Object> config);

public static Map<String, Object> configurable(Map<String, Object> config);
public static Map<String, Object> modelKwargs(Map<String, Object> configurable);
public static String modelName(Map<String, Object> configurable);
public static void applyGlm52ReasoningDefault(String modelSpec, Map<String, Object> modelKwargs);
public static String workdir(Map<String, Object> configurable);
public static String harborAssistantId(String sessionId);
public static Map<String, Map<String, String>> mcpConnections(Map<String, Object> configurable);
public static String webSearchTool();

public static final class GraphFactory {
    public record CliAgentRequest(...);
    public record DeepAgentRequest(...);
    public record DeepAgentWithMcpRequest(...);
    public static Object createCliAgent(CliAgentRequest request);
    public static Object createDeepAgent(DeepAgentRequest request);
}
```

**3 种 graph**:
- `makeBareGraph` — 最小化 graph (无 system prompt, 无 tools)
- `makeGraph` — 完整 graph (system prompt + tools + MCP)
- `makeTau3Graph` — Tau3 专版 (针对 tau3-bench 的 user simulator)

**安全设计**:
- `SHELL_ENV_DENYLIST` — host env 不传给 agent (避免泄露 host 路径 / secrets)
- `MAX_ASSISTANT_ID_LENGTH = 64` — assistant id 长度限制
- `harborAssistantId(sessionId)` — 用 SHA-256 prefix 做 deterministic id, 不会泄露 session id 原文

---

## 7. 5 个 benchmark 对应论文评测多维度

| 论文 | 关键 gap | 对应 benchmark |
|---|---|---|
| 2512.13564v2 (Memory 综述) | **跨 instance continual learning** | ⭐ **CLBench** |
| 2601.01743 (Agent Transformer) | success / efficiency / robustness / safety / cost 5 维 | ⭐ **Radar** (5 维)+ **Tau3** (success) + **DRBench** (success) |
| 2501.07278 (Lifelong Learning) | POMDP + 持续学习 | ⭐ **CLBench** |
| 10.1007 (Holistic) | 评估层弱 | ⭐ **Radar** 5 维可视化 |
| 2608.20379 (Multimodal) | 5 维评估 / VLM benchmark | ⭐ **ContextBench** (测 8 段式 compact)|

**一句话总结**: 5 个 benchmark 覆盖 **continual learning** + **5 维评估** + **long-horizon 对话** + **deep research** + **context 压缩** — 是论文 2601.01743 那个 5 维框架的完整实例化。

---

## 8. 测试覆盖 (R-radar-2: 0 → 127)

| Test class | Tests | 覆盖 |
|---|---:|---|
| `Tau3SubsetTest` | 16 | 30 任务 / 难度分布 / 前缀 / justification / includeTasks 一致性 |
| `ClbenchTypesTest` | 15 | 4 record + SystemRegistry + ContinualLearningSystem |
| `RadarTest` | 33 | Theme / generateRadar / loadResultsFromSummary / toyData / safeFilename |
| `CliTest` | 46 | 4 EXIT_* + ShellRunner seam + 7 子命令 + validateModel + 2 bug regression |
| `DeepAgentsSystemTest` | 17 | 4 常量 + supportsBaseline + recordUsageEvent + reset + DeepAgentFactory |
| **Total** | **127** | |

**Fixture**: `src/test/resources/io/deepagents/evals/categories.json` (8 categories + 6 radar + 8 labels)

**Pom 改动**: 去掉 `skipMain` / `skipTestCompile` / `skipTests` (R-radar-2 之前 evals module 整个不进 build), 改 surefire exclude `Drbench*Test` / `Contextbench*Test` (需要 Python-side fixtures)

**Maven 验证**:
```bash
mvn -B -pl aethercode-evals test
# Tests run: 127, Failures: 0, Errors: 0, Skipped: 0
# BUILD SUCCESS
```

stderr 上会有 ~15 行 `error: --model is required` / `error: unknown option: --group` — 这些是 CliTest 测 parser error 路径的副作用, 不影响测试。

---

## 9. 与其他模块的关系

| 模块 | 关系 |
|---|---|
| `aethercode-core` | CliTest 用 `aethercode-core` 的 ObjectMapper |
| `aethercode-tools` | DRBench / ContextBench 调 web search tool, 走 `aethercode-tools/net` (Brave / Serper / **google_scholar (R-radar-4)** / web_fetch) |
| `aethercode-memory` | CLBench 测的就是 memory 模块 (跨 instance 学习) |
| `aethercode-compact` | ContextBench 测 8 段式压缩 |
| `aethercode-permission` | (间接) eval 跑时 agent 受 permission policy 约束 |
| `aethercode-a2a` | (未来) R-radar-8 Multi-Agent 评测走 A2A |
| `aethercode-workflows` | `eval` workflow (R241.3) 调 Cli dispatch |

---

## 10. trade-off / 已知限制

1. **5 benchmark 数据源都是 Python**: Java 端只是 mirror, 真跑需要 Python + uv 环境。**测试是纯 Java, 但跑真评测要装 Python deps**。
2. **rendering 在 caller**: Radar 只输出 data structure, 真实图用 Python matplotlib (默认) 或 JFreeChart (Java caller 自接)
3. **2 个 parser bug**: 锁定在 regression test, 修要单独 R-round
4. **DRBench / ContextBench 测试留空**: 需要 Python-side fixtures, R-radar-2 没做
5. **Radar static init 依赖 `categories.json`**: 测时放 test resources, production code 不应 hardcode 自己的 category 列表

---

## 11. 关键 round 引用

| Round | 内容 | 关联 |
|---|---|---|
| R-radar-1 | `ai-agent-validation.md` 重写 | 发现 5 benchmark 已 port, 之前 doc 完全没暴露 |
| R-radar-2 | aethercode-evals 测试 0 → 127 | 见 R-radar-2 round-notes |
| R-radar-3 | 写 `tech-docs/evals.md` (5 个 benchmark 详解) | 本 doc |
| R-radar-4 | 加 `google_scholar` tool | Semantic Scholar backend, 17 + 6 tests |
| **R-radar-5** | **加 `arxiv_fetch` tool** | **Atom XML parser + WebFetchTool delegate, 25 tests** |
| R-radar-6 | V 校验器框架 | 走 CLBench `respond` + `observe` 接口 |
| R-radar-7 | Self-correction 机制 | 走 `recordUsageEvent` + 反馈循环 |
| R-radar-8 | Multi-Agent 对抗 | 走 A2A + Sub-agent + LangGraphAgent 集成 |

---

## 12. 写新 benchmark 的 checklist

1. **Java port vs Python mirror**: 决定哪些是 data class (Java), 哪些是真正的 runner (Python)
2. **data fixture**: 上游 repo + 固定 SHA, 避免 source of truth 漂移
3. **CLI 集成**: 加一个 `Cli.java` 的 subcommand + 一个 `cmd<Name>` 方法
4. **测试**: 至少 10 个 test, 覆盖 data class 边界 + CLI dispatch + error path
5. **categories.json** (如果用 Radar): 8 categories + 6 radar + 8 labels
6. **round-notes**: 写一篇 `R-XXX-NEW-BENCHMARK.md` 说明数据源 / 阈值 / 跟现有 benchmark 的关系
7. **cross-link**: 在 `ai-agent-validation.md` 的 5 benchmark 表里加一行
