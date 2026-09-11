# Agents

> AetherCode 中的 Agent 是磁盘上以 Markdown 文件形式持久化的具名 persona。
> 每个 Agent 是一个 `agent.md` 文件,带 YAML frontmatter。
> 引擎启动时加载,JSON-RPC 暴露注册表;Desktop 的 `Settings → Agents` tab 和 TUI 的 `/agents` 命令都从这里读。
>
> **整合自 `doc/AGENTS.md`** (R250+7 doc 重构),已翻译为中文。

---

## 1. 文件布局

```
~/.minimax/agents/
├── code-reviewer/
│   └── agent.md
├── summarizer/
│   └── agent.md
└── test-writer/
    └── agent.md
```

每个 Agent **一个文件**: `<name>/agent.md`。目录名 = Agent id (必须 kebab-case,最长 64 字符,不含路径分隔符)。

---

## 2. Frontmatter

```markdown
---
name: code-reviewer              ← 必填, kebab-case
description: Reviews PR diffs    ← 简短描述 (picker 显示)
displayName: Code Reviewer       ← 人类可读 label (仅 UI 用)
model: glm/glm-4-flash           ← R109-3: per-agent model
---

You are a senior code reviewer. ...
```

### 2.1 字段参考

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | ✅ | kebab-case id; 必须跟目录名一致 |
| `description` | string | ❌ | 一行描述; picker 显示 |
| `displayName` | string | ❌ | 人类可读 label; 缺省回退到 `name` |
| `model` | string (R109-3) | ❌ | "provider/model" 格式; 空 = 走 engine 默认 |

### 2.2 ⭐ `model` 字段 (R109-3)

`model:` 字段是 "provider/model" 字符串。当 workflow executor 的 `kind: agent` 步骤把 Agent 派生为子 session 时:

1. 调 `AetherCodeEngine.getAgentMeta(name)` 读 Agent 的 frontmatter
2. 如果 `model` 非空, 调 CLI 的 `chatClientResolver.apply(model)` 拿到对应的 `ChatClient`
3. 调 `engine.query(prompt, override)` 带 per-call override

**per-call override** 捕获到 `QueryEngine` 的 `StreamSupport` Spliterator local 中;**engine 的 `chatClient` 字段不会被改**。这意味着**并发的主循环 query 和子 session 不会竞争共享字段**。

如果 `model` 是空, engine 用当前配置的 `ChatClient` (跟主循环同一个)。这是老的 R107-B 行为。

### 2.3 迁移

老的 Agent (R109-3 之前) 没有 `model:` 行。**继续工作** — 字段视为空, engine 走主循环 model。Editor 的 "(engine default)" label 会高亮这个状态,让用户知道哪些 Agent 有显式绑定,哪些是继承的。

---

## 3. 编写建议

- **一个 persona 一个文件**。不要把多个角色塞进同一个 Agent — 拆成多个。
- **body 控制在 2000 字内**。body 会在每次子 session query 时**完整**塞到 system prompt; 臃肿只会增加成本,不会提升质量。
- **用 Markdown 结构**。Model 把 body 当纯文本读, 但 `# 标题` / `- 列表` 让你编辑时更容易扫。
- **日常任务绑快速 model**。summariser Agent 不需要 Opus,绑 Haiku 或 GLM-4-Flash 节省成本。R109-3 让这变成一行 `model:` 改动。

---

## 4. API Surface

### 4.1 listAgents (wire shape)

返回:
```json
{
  "ok": true,
  "count": 3,
  "agents": [
    {
      "name": "code-reviewer",
      "description": "Reviews PR diffs",
      "displayName": "Code Reviewer",
      "model": "glm/glm-4-flash",
      "lastModifiedMs": 1723555200000
    }
  ]
}
```

老 Agent 的 `model` 字段是空字符串。Desktop 的 `AgentsPanel` 在每行显示 "model" badge;空 badge 变成 muted 的 "(default)" label。

### 4.2 getAgentBody (wire shape)

返回:
```json
{
  "ok": true,
  "name": "code-reviewer",
  "body": "You are a senior code reviewer ...",
  "path": "C:/Users/me/.minimax/agents/code-reviewer/agent.md",
  "description": "Reviews PR diffs",
  "displayName": "Code Reviewer",
  "model": "glm/glm-4-flash",
  "lastModifiedMs": 1723555200000
}
```

`body` 是 frontmatter 之后的原始 Markdown。其它字段是解析后的 frontmatter;缺失字段为空字符串。

### 4.3 create / update / delete

| 方法 | 行为 |
|---|---|
| `createAgent({name, description, displayName, model, body})` | 组装 frontmatter 并写文件。成功返回 `{ok, name}`,失败 `{ok: false, error}` |
| `updateAgent(opts)` | 同 shape, 覆盖现有文件 |
| `deleteAgent(name)` | 删除磁盘文件,返回 `{ok, name}` |

每次写操作后, registry 自动 reload,后续 `listAgents` 立即看到新状态,不用手动 refresh。

---

## 5. Desktop UI

`Settings → Agents` tab。列表视图显示每个 Agent 的 `name` + `model` badge。点行打开 editor;editor 预填 4 个 frontmatter 字段 + body textarea。Model dropdown 从 engine 的 provider registry 构建,可以选任意 provider/model 组合。

## 6. TUI 入口

| 命令 | 作用 |
|---|---|
| `/agents` | 列出所有 Agent,带 model 列 |
| `/agent <name>` | 显示某个 Agent 的 body + frontmatter |

跟 Desktop 信息一致;**TUI 是只读的** (不支持就地编辑)。要编辑, 用 Desktop。

---

## 7. 跨参考

- [`../tech-docs/workflow-engine.md`](../tech-docs/workflow-engine.md) — `kind: agent` 步骤怎么用 Agent
- [`../tech-docs/providers.md`](../tech-docs/providers.md) — provider/model 怎么解析成 ChatClient 实例
- `aethercode-desktop/docs/R109-3-AGENT-MODEL-LINKAGE-2026-08-13.md` — R109-3 model 绑定特性的 R-round retro
