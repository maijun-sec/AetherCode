# Skill System

> Skill 是 prompt 模板 + 允许工具的组合,激活时注入到 system prompt。
>
> **关键代码**: `aethercode-skills/src/main/java/org/aethercode/skills/`

---

## 1. 核心抽象

```java
public record Skill(
    String name,             // kebab-case id
    String description,      // 人类可读一行
    String body,             // Markdown body = 注入到 system prompt 的内容
    List<String> allowedTools, // 激活时附加到 tool pool
    Map<String, Object> metadata
) {
    public Skill {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("skill name is required");
        if (body == null) body = "";
        if (allowedTools == null) allowedTools = List.of();
        if (metadata == null) metadata = Map.of();
    }
}
```

**4 个字段**:
- `name` — 唯一 id (kebab-case)
- `description` — 触发说明 (UI 列表展示)
- `body` — 激活后注入的 Markdown prompt
- `allowedTools` — 激活时附加的工具集

**激活语义**: 选中的 skill 拼到 system prompt,**同时** 把 `allowedTools` 附加到 tool pool。

---

## 2. 3 个核心类

| 类 | 文件 | 字节 | 作用 |
|---|---|---|---|
| `Skill` | `Skill.java` | 972 | record: name/description/body/allowedTools/metadata |
| `SkillRegistry` | `SkillRegistry.java` | 2,619 | 从目录加载 .md 文件 (front matter) |
| `SkillMarketplace` | `SkillMarketplace.java` | 5,240 | 内存 marketplace (5 个内置 + 用户安装) |

---

## 3. ⭐ SkillRegistry — 文件格式

Skill 是 **Markdown + YAML front matter**:

```markdown
---
name: my-skill
description: "What this skill does"
allowed-tools: file_read, file_write, bash
---

When the user invokes this skill, do X.
Then do Y.
Finally output Z.
```

**解析逻辑** (`SkillRegistry.loadOne`):
1. 第 1 行必须是 `---` (front matter 开始)
2. 找下一个 `---` (front matter 结束)
3. 中间行解析成 `key: value` → 顶层 `name` / `description` 进 record,其他进 `metadata`
4. 第二个 `---` 之后所有行 → `body`

**fallback**:
- 无 front matter → 整个文件当 body, name = 文件名 (去 .md)
- 没找到第二个 `---` → front matter 当 body 起点
- 解析失败 → 返回空 body 的 Skill

**目录扫描**: `Files.list(dir).filter("*.md")` — 扫所有 `.md` 文件,忽略子目录。

---

## 4. ⭐ SkillMarketplace — 5 个内置 + 用户

```java
public final class SkillMarketplace {
    private final Map<String, Skill> byName = new LinkedHashMap<>();
    private final Set<String> installed = new HashSet<>();

    public SkillMarketplace() { addAll(builtins()); }
    ...
}
```

### 4.1 5 个 builtins (写死)

| Name | Description | Allowed tools |
|---|---|---|
| `frontend-review` | Review frontend diff (a11y, perf, visual) | `file_read`, `bash` |
| `backend-debug` | Diagnose 5xx / stalled request | `file_read`, `bash`, `web_fetch` |
| `sql-optimizer` | Tune slow SQL query | `file_read`, `bash` |
| `security-audit` | OWASP top-10 audit | `file_read`, `file_edit` |
| `test-author` | Author JUnit 5 + AssertJ tests | `file_read`, `file_write` |

**设计选择**: marketplace 是**纯逻辑**,无 I/O — 调用方预加载用户目录 (`~/.aethercode/skills/`, `~/.aethercode/marketplace/`) 然后 `addAll()` / `install()`。

### 4.2 3 个操作

| 方法 | 行为 |
|---|---|
| `add(skill)` | 注册 skill, 同名覆盖 |
| `install(name)` | 标记为"已安装"(跟 "loaded" 区别开) |
| `install(skill)` | add + 标记 installed |

**"loaded" vs "installed" 区别**:
- `loaded` = 在 `byName` map (可被发现)
- `installed` = 用户显式选过 (CLI 显示 "installed" 标签)

---

## 5. ⭐ 3 段加载路径

类似 `WorkflowPaths`,skill 也有 3 段覆盖:

| 优先级 | 位置 | 用途 |
|---|---|---|
| 1 | `<cwd>/.aethercode/skills/*.md` | 项目本地 |
| 2 | `~/.aethercode/marketplace/*.md` | 用户全局 |
| 3 | bundled 5 个 builtin (hard-coded) | 兜底 |

`SkillRegistry.loadDir()` 一次扫一个目录,marketplace 聚合 3 个来源。

---

## 6. 激活机制

```java
// 用户在 UI 选 skill "sql-optimizer"
Skill s = marketplace.byName("sql-optimizer");

// 1. 拼到 system prompt
String augmented = systemPrompt + "\n\n## Active skill: sql-optimizer\n\n" + s.body();

// 2. 附加 allowed tools 到 tool pool
List<Tool> newPool = baseTools + toolsByName(s.allowedTools());

// 3. engine 用新 prompt + 新 tool pool 继续 LLM call
```

**Allowed tools 检查**: `toolsByName(s.allowedTools())` 找不到的工具**不报错**(只附加存在的),防止 skill 引用不存在的 tool。

---

## 7. Skill vs Workflow vs MCP — 区别

| 维度 | Skill | Workflow | MCP Server |
|---|---|---|---|
| 触发 | 用户选 / 命令 | 命令 `workflow_run(name)` | 自动发现 (stdio/socket/...) |
| 形式 | Markdown + front matter | YAML workflow | 外部进程 + JSON-RPC |
| 注入 | System prompt + tool pool | 完整 session (system + user + todos) | 仅 tool list |
| 复杂度 | 单 prompt 增强 | 多步任务编排 | 外部能力调用 |
| 状态 | 无 | 长期 session | 外部进程 |
| LLM call | 0 (用户决定) | N (workflow steps) | M (model 调) |

**关系**: skill ⊂ workflow (workflow 可在 system prompt 注入 skills), mcp ⊥ skill/workflow (独立维度)。

---

## 8. 关键测试

```
SkillRegistryTest.java             (front matter 解析)
SkillMarketplaceTest.java          (add/install/builtins)
SkillActivationTest.java           (注入 system prompt)
SkillAllowedToolsTest.java         (tool pool 合并)
```

---

## 9. 已知 trade-off

| 决策 | 优点 | 缺点 |
|---|---|---|
| Front matter Markdown | 简单, 用户友好 | 无嵌套结构, 无 schema 验证 |
| 5 个内置 hard-coded | 永远可用, 离线可 | 加新内置要 fork |
| Marketplace 纯逻辑 | 测试简单 | 调用方负责 I/O |
| Allowed tools 不存在不报错 | 鲁棒 | skill 作者难发现 typo |
| `body` String (不解析 AST) | 灵活 | 不能 skill 间引用 |
| 没版本号 | 简单 | 升级时可能 silent 改变行为 |

---

## 10. 关键 round 引用

- **R250+**: 引入 SkillRegistry + SkillMarketplace
- **R250+6**: 6 个内置 workflow 引用 skills (tdd/security-audit/code-review/migrate-deps/add-changelog/explain-failure)
- 详细过程见 `../round-notes/` 相应文档
