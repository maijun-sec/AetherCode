# Workflow Engine

> YAML 驱动的多步任务编排系统,6 个内置 workflow + 用户可自定义。
>
> **关键代码**: `aethercode-workflows/src/main/java/org/aethercode/workflows/`
>
> **bundled workflows**: `aethercode-workflows/src/main/resources/workflows/*.yaml` (6 个)

---

## 1. 整体架构

```
用户: workflow_run(name="tdd-feature", inputs={...})
  ↓
[WorkflowEngine.run]                 ← 唯一 public 方法
  │
  ├─ 1. WorkflowPaths.resolveFile    ← project → user → bundled
  │     └─ 没找到 → throw WorkflowNotFound
  │
  ├─ 2. WorkflowLoader.load          ← YAML → Workflow record
  │     └─ WorkflowParserException on syntax error
  │
  ├─ 3. WorkflowValidator.validate    ← 语义检查 (8 类错误)
  │     └─ ValidationError[] → 累积抛 WorkflowParserException
  │
  ├─ 4. coerceInputs                 ← 类型转换 (string/number/integer/boolean/enum)
  │     └─ 必填缺失 → throw
  │
  ├─ 5. SkillComposer.compose         ← skills[] → system prompt suffix
  │     └─ 注入到第一个 system prompt 或新建一个
  │
  ├─ 6. VariableSubstitution.substitute  ← {{...}} → 实际值
  │     └─ inputs.X / cwd / date / os
  │
  ├─ 7. composePlanBlock             ← todos[] → "## Plan (pre-seeded TODOs)"
  │     └─ 拼到 final prompt 末尾
  │
  └─ 8. SessionSpawner.spawn         ← @FunctionalInterface
        └─ 委托给 supervisor (生产) / fake (测试)
  ↓
SessionRef (短哈希 ID + ref handle)
  ↓
UI 监听 session 事件流
```

**8 步流水线**,单次 `run()` 调用走完。

---

## 2. 6 个 Bundled Workflows

| Name | 用途 | 关键 inputs | 关键 skills | round |
|---|---|---|---|---|
| `tdd-feature` | TDD red-green-refactor 循环 | `feature` (string, required) | `tdd` | R241.3 |
| `security-audit` | 安全审计 + CWE 标签 | `target` (string) | `security-audit` | R241.3 |
| `add-changelog` | 加 CHANGELOG entry | `version`, `entries` | `changelog` | R241.3 |
| `explain-failure` | 解释 CI 失败 | `ci_log` (string) | `debug` | R241.3 |
| `code-review` | Review diff/branch 输出 verdict | `diff` (string) | `code-review` | R250+6 |
| `migrate-deps` | 升级依赖, dry-run 默认 true | `exclude`, `allow_major` | `migrate` | R250+6 |

**分类**:
- R241.3 (4 个演示性): TDD / 安全 / 发布 / 调试
- R250+6 (2 个实战): code-review + migrate-deps 是日常最常用的 2 类

---

## 3. 关键类索引 (按代码量排序)

| 类 | 文件 | 字节 | 作用 |
|---|---|---|---|
| `SsdRunner` | `SsdRunner.java` | 21,300 | 5 分钟 demo 专用 runner |
| `DefaultWorkflowService` | `DefaultWorkflowService.java` | 14,069 | RPC service 实现 (load/list/run) |
| `WorkflowPaths` | `WorkflowPaths.java` | 14,001 | project → user → bundled 三段路径解析 |
| `SsdConfig` | `SsdConfig.java` | 13,939 | SSD demo 配置加载 |
| `WorkflowEngine` | `WorkflowEngine.java` | 12,445 | 核心 8 步流水线 |
| `WorkflowLoader` | `WorkflowLoader.java` | 10,779 | YAML → Workflow record |
| `WorkflowValidator` | `WorkflowValidator.java` | 10,018 | 8 类语义验证 |
| `SkillComposer` | `SkillComposer.java` | 9,091 | skills[] → system prompt |
| `Workflow` | `Workflow.java` | 6,541 | record: name/inputs/prompts/todos/limits |
| `StubWorkflowService` | `StubWorkflowService.java` | 6,511 | 测试用 stub |
| `VariableSubstitution` | `VariableSubstitution.java` | 6,092 | {{...}} 占位符替换 |
| `WorkflowService` | `WorkflowService.java` | 4,849 | RPC service 接口 |
| `WorkflowServiceException` | `WorkflowServiceException.java` | 3,610 | Code enum + service 异常 |
| `WorkflowParserException` | `WorkflowParserException.java` | 1,556 | 解析异常 |

---

## 4. ⭐ Workflow YAML Schema (v1)

`Workflow` record 字段对应 YAML 顶层 key:

```yaml
version: 1                              # 必须
name: tdd-feature                       # kebab-case, 匹配文件名
description: "TDD red-green-refactor"   # 必填, 人类可读

inputs:                                 # 声明的输入
  feature:
    type: string                        # string | number | integer | boolean | enum
    required: true
    description: "Feature to implement"
    default: null                       # 可选
    values: [a, b, c]                   # 仅 type=enum

skills:                                 # 注入到 system prompt 的 skills
  - tdd
  - code-review

prompts:                                # 有序的 system/user 消息
  - role: system
    content: |
      You are a TDD assistant.
      Work on {{inputs.feature}}.
  - role: user
    content: "Start with the failing test."

todos:                                  # 预填充的 TODO 列表
  - "Write failing test for {{inputs.feature}}"
  - "Implement minimum to pass"
  - "Refactor"

limits:                                 # 可选资源上限
  wall_clock_ms: 600000
  max_tokens: 100000
  max_turns: 30
```

### 4.1 ⭐ Input 类型系统

`coerce(name, value, def)` 支持 5 种类型:

| Type | 输入 | 转换 |
|---|---|---|
| `string` | any | `value.toString()` |
| `number` | 数字或字符串 | `Double.parseDouble()` |
| `integer` | 数字或字符串 | `Long.parseLong()` |
| `boolean`/`bool` | bool/字符串 | `true`/`yes`/`1` → true, `false`/`no`/`0` → false |
| `enum` | 字符串 | 必须在 `values` 列表中,否则 throw |

**未知类型不抛** — `default -> v` 透传(向后兼容)。

**未知 input key 不抛** — 记 warn,保留值(让 downstream tool 能拿到)。

### 4.2 ⭐ Limits 资源上限

`Limits` record (单字段可空):

| Key | 含义 | 默认 |
|---|---|---|
| `wall_clock_ms` | 总运行时间上限 | 无 |
| `max_tokens` | 总 token 上限 | 无 |
| `max_turns` | 最大 turn 数 | 无 |

通过 `withLimits(newLimits)` 在 per-run 时合并 override。

---

## 5. ⭐ VariableSubstitution 语法

4 种占位符:

| 语法 | 含义 | 来源 |
|---|---|---|
| `{{inputs.X}}` | 引用 input 值 | `Map<String, Object> inputs` |
| `{{cwd}}` | 当前工作目录绝对路径 | `cwd` 参数 |
| `{{date}}` | 今天, ISO-8601 (`yyyy-MM-dd`) | `LocalDate.now()` |
| `{{os}}` | `windows`/`macos`/`linux`/`other` | `OS.detect()` |

**Pattern** (容忍 brace 内部空格):
```java
private static final Pattern PLACEHOLDER =
        Pattern.compile("\\{\\{\\s*([a-zA-Z][a-zA-Z0-9_.]*)\\s*}}");
```

**Missing input 抛** (`WorkflowParserException`),不静默留空 — 用户看到具体哪个 placeholder 错了。

---

## 6. ⭐ SkillComposer — 注入 skills 到 system prompt

```java
public String compose(List<String> skillNames) {
    StringBuilder sb = new StringBuilder();
    for (String name : skillNames) {
        Skill s = registry.load(name);
        if (s != null) {
            sb.append("\n## Skill: ").append(name).append("\n");
            sb.append(s.body());
        }
    }
    return sb.toString();
}
```

**注入策略** (`WorkflowEngine.composePrompt`):
- 找到第一个 `role=system` 的 prompt → 把 skill suffix append 到末尾
- 没有 system prompt → 在 final prompt 头部插入新 `[SYSTEM]\nYou have the following skills available:\n...`
- `injected` 标志位保证 skill suffix 只注入一次(防重复)

---

## 7. ⭐ WorkflowValidator 8 类验证

(从 `WorkflowValidator.java` 推测,需看代码补全)

| # | 错误 | 触发 |
|---|---|---|
| 1 | `version != 1` | 当前 schema 只认 v1 |
| 2 | `name` 跟文件名不匹配 | 防混淆 |
| 3 | `name` 不是 kebab-case | 防 URL/路径问题 |
| 4 | `description` 为空 | 强制人类可读 |
| 5 | 必填 input 缺 `description` | 文档一致性 |
| 6 | `enum` input 没 `values` 列表 | 语义不完整 |
| 7 | 引用了未声明的 `inputs.X` | typo 防御 |
| 8 | `skills` 列表里 skill 找不到 | 防 silent 失败 |

错误**累积** (不 early return),`formatErrors` 一次性抛 multi-line `WorkflowParserException`,用户一次看到所有问题。

---

## 8. ⭐ SessionSpawner — 关键解耦

`@FunctionalInterface` — 1 个方法 `spawn(prompt, cwd, model, meta) → SessionRef`。

```java
@FunctionalInterface
public interface SessionSpawner {
    SessionRef spawn(String finalPrompt, String cwd, String model, Map<String, Object> meta);
}
```

**为什么是 @FunctionalInterface**:
- 生产: `SupervisorSessionSpawner` 调 supervisor process spawn
- 测试: `FakeSessionSpawner` 立即返回 mock SessionRef
- 集成: `LocalInProcessSpawner` 不开 supervisor 直接跑

`WorkflowEngine` 不持有 supervisor 引用,**0 硬耦合**。这是 R241.3 之后的核心架构改造(原来 supervisor 写死在 WorkflowEngine 里)。

---

## 9. ⭐ WorkflowPaths — 三段解析顺序

```java
Path file = paths.resolveFile(name);
if (file != null) return loader.load(file);
// Fall back to bundled resource
return loader.loadResource("workflows/" + name + ".yaml");
```

| 优先级 | 位置 | 例子 |
|---|---|---|
| 1 | `<cwd>/.aethercode/workflows/<name>.yaml` | 项目内自定义 (覆盖) |
| 2 | `~/.aethercode/workflows/<name>.yaml` | 用户级自定义 |
| 3 | bundled `src/main/resources/workflows/<name>.yaml` | 6 个内置 |

**覆盖机制**: 用户能在项目里 drop 一个 `tdd-feature.yaml` 完全 override 内置版本,无需 fork。

---

## 10. ⭐ composePlanBlock — TODO 预注入

```java
private String composePlanBlock(Workflow wf, VariableSubstitution sub) {
    if (wf.todos() == null || wf.todos().isEmpty()) return "";
    StringBuilder sb = new StringBuilder("## Plan (pre-seeded TODOs)\n");
    int n = 0;
    for (String t : wf.todos()) {
        String rendered = sub.substitute(t);
        sb.append("- [ ] ").append(rendered).append('\n');
        n++;
    }
    sb.append("\nBefore you do anything else, call the `todo_write` tool ")
            .append("with these ").append(n).append(" items so the TODO board reflects the plan.");
    return sb.toString();
}
```

**效果**: workflow 一启动,模型先看到 `## Plan` section,要求**第一步**调 `todo_write` 把 N 个 item 落到 TODO board。这样:
- 用户进 UI 立即看到 plan
- 进度可观察
- 模型有结构化目标

---

## 11. 配置示例 — 6 个内置 workflow

### 11.1 ⭐ code-review (R250+6)

```yaml
version: 1
name: code-review
description: "Review a diff or branch and emit a verdict"
inputs:
  diff:
    type: string
    required: true
    description: "Diff or branch to review"
  base_branch:
    type: string
    default: "main"
  exclude:
    type: string
    default: ""
  verdict:
    type: enum
    values: [approve, request_changes, comment]
    default: comment
skills:
  - code-review
prompts:
  - role: system
    content: |
      You are a senior reviewer. Output a verdict.
  - role: user
    content: |
      Review the following diff:
      {{inputs.diff}}
      Base branch: {{inputs.base_branch}}
      Exclude patterns: {{inputs.exclude}}
todos:
  - "Scan diff for syntactic issues"
  - "Check semantic correctness"
  - "Verify tests cover the change"
  - "Output verdict: {{inputs.verdict}}"
```

### 11.2 ⭐ migrate-deps (R250+6)

```yaml
version: 1
name: migrate-deps
description: "Upgrade dependencies with dry-run default true"
inputs:
  exclude:
    type: string
    default: ""
  allow_major:
    type: string
    default: ""
  dry_run:
    type: boolean
    default: true
  manifest:
    type: string
    required: true
    description: "Path to package.json / pom.xml / Cargo.toml"
skills:
  - migrate
prompts:
  - role: system
    content: "You migrate dependencies safely. Always default to dry-run."
  - role: user
    content: |
      Manifest: {{inputs.manifest}}
      Exclude: {{inputs.exclude}}
      Allow major bumps: {{inputs.allow_major}}
      Dry-run: {{inputs.dry_run}}
todos:
  - "Parse manifest to find outdated deps"
  - "Propose bumps respecting exclude + allow_major"
  - "If dry_run: show proposed diff only"
  - "If !dry_run: apply bumps + run tests"
limits:
  wall_clock_ms: 600000
```

---

## 12. RPC 接口 (WorkflowService)

```java
public interface WorkflowService {
    /** List available workflows (bundled + project + user). */
    List<WorkflowInfo> list();

    /** Load + parse a workflow definition. */
    Workflow load(String name) throws WorkflowServiceException;

    /** Run a workflow with inputs; returns a session ref. */
    SessionRef run(String name, Map<String, Object> inputs,
                   String cwd, String model) throws WorkflowServiceException;
}
```

**WorkflowServiceException** 带 `Code` enum (NOT_FOUND / VALIDATION / IO / RUNTIME / DENIED 等),RPC 客户端能精确处理。

---

## 13. 关键测试

```
WorkflowEngineTest.java            (主流程: 6 个 workflow 集成测)
WorkflowLoaderTest.java            (YAML 解析)
WorkflowValidatorTest.java         (8 类验证)
VariableSubstitutionTest.java      (4 种占位符)
SkillComposerTest.java             (skill 注入)
WorkflowPathsTest.java             (3 段路径)
InputCoercionTest.java             (5 种类型)
R250Plus6WorkflowTests.java        (code-review + migrate-deps 实战 8 个)
ShippedWorkflowsTest.java          (6 个内置 workflow 全覆盖)
```

---

## 14. 已知 trade-off

| 决策 | 优点 | 缺点 |
|---|---|---|
| 5 种 input 类型 (无 list/object) | 简单, schema 1 页讲完 | 复杂输入要 JSON 字符串化 |
| `{{inputs.X}}` 单层 prefix | 易记, 易解析 | 不支持嵌套 `{{a.b.c}}` |
| 3 段路径覆盖 (project > user > bundled) | 用户可 override 内置 | 冲突排查难 (哪个生效?) |
| 错误累积抛 multi-line | 一次看到所有问题 | 长 message 难 parse |
| SessionSpawner @FunctionalInterface | 测试简单, 0 硬耦合 | spawner 异常是 RuntimeException |
| `{{date}}` 用 LocalDate.now() | 简单 | 同一天内多次运行 date 不变 |
| TODOs 走 `## Plan` + `todo_write` | 进度可观察 | 模型不一定严格按 todos 走 |
| `Limits` 单字段可空 | 灵活 | 没组合 (max_tokens + max_turns 都要单独配) |

---

## 15. 关键 round 引用

- **R241.3**: 引入 WorkflowEngine + 4 个演示 workflow (tdd-feature / security-audit / add-changelog / explain-failure)
- **R250+6**: 加 2 个实战 workflow (code-review / migrate-deps) + SessionSpawner 解耦
- **R250+**: WorkflowService 完整 RPC + WorkflowPaths 三段解析 + SkillComposer 注入策略
- 详细过程见 `../round-notes/R241-3-PERSISTENCE-UTILITY-DECAY.md` 和 `../round-notes/R250+6` 相关
