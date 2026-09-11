# AetherCode Memory — 能力与使用指南

**版本**: 截至 R233 (2026-09-07)
**面向读者**: AetherCode 用户 / 集成方 / 想了解 daemon 内部的人
**配套文档**:
- 设计视角 — `doc/项目文档/R230-MEMORY-WRITE-READ-MAP.md`(写/读时机详细矩阵)
- 调研背景 — `aethercode/docs/R230-MEMORY-SURVEY-DRIVEN-OPTIMIZATION.md`(arXiv 综述 2512.13564 映射)
- 轮次报告 — `aethercode/docs/R127 / R19B / R230 / R232 / R233-*.md`

---

## 0. TL;DR — 一图看全

AetherCode Memory 是一个**7 类分层 + 4 阶编排 + 跨进程 RPC** 的记忆系统。它不只是"存东西" —— 而是让 daemon 在每个 query 自动决定"读什么、写什么、什么时候忘"。

| # | 类型 | 物理位置 | 典型内容 | 谁写 | 谁读 |
|---|---|---|---|---|---|
| 1 | **GLOBAL (USER)** | `~/.aethercode/agent-memory/<agentType>/*.md` | 跨项目偏好、用户身份、全局硬规则 | RPC `setMemory` / 手工编辑 | 每 query start 召回 |
| 2 | **PROJECT** | `<cwd>/.aethercode/agent-memory/<agentType>/*.md` | 项目 build/test 命令、专有约定、change log | RPC + 自动 LLM 压缩 | 每 query start 召回 |
| 3 | **SESSION** | `~/.aethercode/sessions.db` (SQLite) + `<cwd>/.aethercode/sessions/<sid>.jsonl` | 当天对话流 + per-session k/v 事实 | 每条消息 + RPC | 同 session 内 recall |
| 4 | **EXPERIENCE** | `<scope>/agent-memory/<agentType>/experience/<id>.json` | 成功 trajectory (CASE) / 抽象策略 (STRATEGY) / 技能 (SKILL) | daemon 自动 (Tier 2/3) | 下次 query start 召回 |
| 5 | **WORKING** | 进程内 in-memory LRU | 单 query scratchpad (TODO / EVIDENCE / PLAN_STEP) | 模型通过 `wm_*` 工具 + daemon 自动清 | 进程内同 query |
| 6 | **AUDIT** | `<memoryBase>/audit.log` (JSONL) | 所有 memory read/write/decay/recall 事件 | daemon 每次操作 | RPC `viewAuditLog` / 手工 `cat` |
| 7 | **TASK** | `<memoryBase>/agent-memory-tasks/<agentType>/<taskId>/` | 子 agent 隔离 memory | 子 agent 显式 store | 子 agent 显式 recall |

**关键事实**:
- daemon 启动时自动 wire `MemoryLifecycle`(4-tier orchestrator),无需手工开
- **807 tests pass, 0 regression**(R233 baseline)
- 所有 memory 操作走统一 RPC,跨进程 / 跨语言 / 跨客户端都能用

---

## 1. 7 类 Memory 各自能做什么

### 1.1 GLOBAL (USER scope)

**定位**: 跨项目稳定的用户身份与偏好,所有项目共享。

| 应该进 | 不应该进 |
|---|---|
| `user.name = 张三`、`user.email = ...` | 当前项目的 build 命令(→ PROJECT) |
| `preferPowerShellOnWindows = true` | 当天的工作进度(→ SESSION) |
| `always use junit5` / `always use tabs` | 一次性的项目笔记(→ PROJECT) |
| MCP token / API key(标 `Sensitivity.PII`) | 工具执行结果(→ SESSION transcript) |
| 跨项目的硬规则:`never rm -rf /` | 一次成功的 trajectory(→ EXPERIENCE) |

**物理位置**:
```
~/.aethercode/agent-memory/<agentType>/
├── MEMORY.md        # 入口,200 行 / 25KB 硬上限
├── preferences.md   # 偏好
└── *.md             # 其他分类文件
```

### 1.2 PROJECT

**定位**: 项目专有知识,跟 cwd 走(切到别的项目就不见了),可 git 共享。

| 应该进 | 不应该进 |
|---|---|
| 项目 build/test/run 命令 | 用户个人偏好(→ USER) |
| 项目专有约定:`Maven multi-module` | 当天聊天(→ SESSION) |
| 项目的库版本、API key | 跨项目可复用洞察(→ EXPERIENCE) |
| 项目 MEMORY.md 入口 | 频繁变动的运行时状态 |
| 项目 change log(自动生成) | |

**物理位置**:
```
<cwd>/.aethercode/agent-memory/<agentType>/
├── MEMORY.md
├── build.md
├── preferences.md
├── change log line    # 自动 append,格式 "[<iso8601>] <content>"
└── (其他 .md)
```

**自动行为**: `change log` 超过 50 行后,LLM 压缩成一段保留(默认保留最近 10 条原样)。

### 1.3 SESSION

**定位**: 当天对话 + per-session 临时事实。两种物理存储:

```
~/.aethercode/
├── sessions.db                                  # SQLite: session_info + session_memory k/v
└── sessions/
    ├── <timestamp>_<sid>.jsonl                  # 消息流(每条一行)
    └── <timestamp>_<sid>.cwd                    # session 绑定的 cwd
```

- **jsonl**: 每条 user/assistant/tool 消息自动 append(高频,无感)
- **k/v 表**: 仅 RPC,适合存"5 分钟前用户说的 build_cmd" 这种 per-session 事实

### 1.4 EXPERIENCE(R230 引入)

**定位**: 跨 session 的"经验库"。三种 kind:

| Kind | 内容 | 例子 |
|---|---|---|
| **CASE** | 一次具体 trajectory | "在 mavis 跑 maven build 用了 `-B install -DskipTests`,42 秒成功" |
| **STRATEGY** | 抽象 workflow / 启发式 | "maven 编译失败时,先看 surefire-reports 再 dump bytecode" |
| **SKILL** | 可调用 function/MCP 描述 | "SkillWeaver: 自动把成功 trajectory 提炼成可调用脚本" |

**写入时机**: 每个成功 query 结束,Tier 2 自动写 CASE;若 transcript 满足策略关键词(同工具 ≥3 次)Tier 3 调 LLM 写 STRATEGY。

**读取时机**: 每个 query start 召回 top-3 user + top-3 project 拼进 system prompt。

### 1.5 WORKING(R230 引入 + R233 工具化)

**定位**: 单 query 内的 active scratchpad。6 种 kind:

```java
enum Kind {
    TEXT,        // 任意短文本
    KEY_VALUE,   // JSON 风格 {k:v, k:v}
    REFERENCE,   // 指向某条 memory item
    PLAN_STEP,   // 工作计划的一条
    EVIDENCE,    // 模型"正在思考"的事实/snippet
    TODO         // 开放 todo
}
```

**生命周期**: query start new → 模型 `wm_put` → query end 自动 `clear`(跨 query 不保留)。

**容量**: 默认 50 条 / ~2-5k tokens,LRU 淘汰。

### 1.6 AUDIT(R230 引入)

**定位**: append-only JSONL 日志,所有 memory 操作都留痕。

```json
{
  "ts": "2026-09-07T12:34:56Z",
  "actor": "agent:mavis",
  "action": "read|write|delete|compress|decay|recall|share|setSensitivity|tombstone|prune",
  "scope": "user|project|session",
  "key": "...",
  "kind": "fact|rule|change|breadcrumb|experience|buffer|query-end|tool-call|decay",
  "sourceSessionId": "...",
  "decision": "allow|deny|filtered-pii|tombstoned",
  "meta": {...}
}
```

**文件**:
```
<memoryBase>/audit.log
```

### 1.7 TASK

**定位**: 子 agent 隔离的临时 memory,per-task namespace。

**物理位置**:
```
<memoryBase>/agent-memory-tasks/<agentType>/<taskId>/<key>
```

仅子 agent 显式 `store` / `recall` 调用,跨 subagent 不共享。

---

## 2. 写入机制(谁在写)

### 2.1 哪些是**自动**写(用户无感)

| Hook | 频率 | 写什么 |
|---|---|---|
| `appState.appendMessage` | 每条消息 | SESSION jsonl |
| `MemoryLifecycle.onToolCall` | 每个 tool 调用 | AUDIT (kind=tool-call) |
| `MemoryLifecycle.onQueryStart` | 每 query | AUDIT (kind=buffer) + new WorkingMemoryBuffer |
| `MemoryLifecycle.onQueryEnd` (success) | 每成功 query | EXPERIENCE CASE (Tier 2 启发式) |
| `MemoryLifecycle.onQueryEnd` (策略命中) | 满足 gate | EXPERIENCE STRATEGY (Tier 3 LLM) |
| `LayeredMemoryStore.appendProjectChange` 后超 50 行 | 自动 | PROJECT LLM 压缩 |

### 2.2 哪些需要 **RPC 触发**

- `setMemory` / `deleteMemory` / `listMemory`(任何 scope 的 k/v)
- `writeMemoryFile` / `readMemoryFile` / `deleteMemoryFile` / `listMemoryFiles`(直接读写 .md)
- `compressProjectMemory`(force=true 立刻压缩)
- `viewAuditLog`(读 audit)

### 2.3 Working Memory 工具(R233 引入)

模型可以主动 put / get / list / clear:

| Tool | 调用 |
|---|---|
| `wm_put(kind, content, meta?)` | 加一条 entry |
| `wm_get(id)` | 读单条 |
| `wm_list()` | 渲染整个 buffer |
| `wm_clear()` | 清空 |

**自动装入**:`Main.java` 默认 tool pool,无需手工注册。**没 wire 时不抛异常**,返回 "no working buffer active"。

### 2.4 4-tier MemoryLifecycle 总览

```java
// MemoryLifecycle.java
Tier 0 — per tool call (free)        : onToolCall  → audit
Tier 1 — per query (free)            : onQueryStart → new buffer + audit
                                       onQueryEnd   → clear + audit
Tier 2 — per successful query        : extractCaseFromTranscript (heuristic, no LLM)
Tier 3 — per pattern (gated)         : maybeExtractStrategy (heuristic + LLM)
Tier 4 — background 5min             : ForgettingPolicy.runDecayPass
```

**关键不变量**:
- 所有 tier 都不抛异常(失败 → audit + 静默)
- `setStrategyChatClient(null)` 是 no-op(daemon-scoped 寿命)
- LLM 调用全部走同一个 `chatClientResolver`

---

## 3. 读取机制(谁在读)

### 3.1 每个 query start 自动召回

`AetherCodeEngine.queryInternal()` 流程:

```
1. onQueryStart(userInput)              // Tier 1
2. buildMemorySection(userInput)         // MemoryRecall.recall()
3. buildExperienceSection(userInput)     // MemoryLifecycle.recallExperience()
4. buildSessionKvSection(userInput)      // MemoryLifecycle.recallSessionKv()
5. combineThreeSections() → 注入 system prompt
```

**最终 system-prompt 顺序**:
1. MemoryRecall(USER+PROJECT+LOCAL 文件, "## filename.md")
2. Experience Section("## Past experience (auto-recalled)")
3. Session Facts("## Session facts")

### 3.2 召回评分

`MemoryRecall.recall(dir, userInput, recentToolNames, surfaced)`:
1. 扫 `*.md`(排除 MEMORY.md 入口)
2. 取每个文件前 5 行作 manifest
3. lexical match against `userInput` + `recentToolNames`
4. 候选 ≤ 3 × MAX_RECALL 时,可选地调一个 side-query LLM 排序
5. 返回 top-5(MAX_RECALL),记入 `surfacedMemories` 去重

### 3.3 可见性(Sensitivity)

```java
enum Sensitivity { PUBLIC, INTERNAL, SENSITIVE, PII }
```

- `PII` / `SENSITIVE` 默认从 recall 隐藏(避免把 API key / 邮箱烤进 prompt)
- 想强制召回用 opt-in RPC

### 3.4 衰减 / 遗忘(ForgettingPolicy)

每 5 分钟 Tier 4 后台跑一次:

```java
ForgettingPolicy.score(item, accessCount, now) = 
    0.5 * recency  // exp(-Δdays / τ)  τ = 30 days
  + 0.3 * frequency // log-scaled
  + 0.2 * utility  // 0..1
```

- score < `0.05` 且 ≥ 7 天 → tombstone(移到 `.trash/`)
- score < `0.01` → 等 `--memory-prune` 显式调用(永不自动 hard-delete)

---

## 4. 使用方法(怎么用)

### 4.1 RPC API(11 个)

`AetherCodeMethods` 注册在 `JsonRpcDispatcher` 上,所有方法都接收 `Map<String, Object>`,返回 `Map<String, Object>`。

#### 4.1.1 通用 k/v: get / set / list / delete

```javascript
// USER scope
await rpc("setMemory", {
  scope: "USER",
  content: "user.name = 张三",
  tags: ["identity"]
});
// → { ok: true, scope: "USER", key: "<id>", content: "..." }

await rpc("getMemory", { scope: "USER", key: "<id>" });
// → { ok: true, scope: "USER", key, content, tags, createdAt, updatedAt }

await rpc("listMemory", { scope: "USER" });
// → { ok: true, count, entries: [{key, content, tags, ...}, ...] }

await rpc("deleteMemory", { scope: "USER", key: "<id>" });
// → { ok: true, scope: "USER", key }
```

**PROJECT** 多一个 `cwd` 字段,`SESSION` 多一个 `sessionId` 字段(默认当前 engine 的 sessionId)。

#### 4.1.2 文件级: list / read / write / delete

```javascript
await rpc("listMemoryFiles", { scope: "PROJECT" });
// → { files: [{name, path, size, mtime, isEntry, scope}, ...], count }

await rpc("readMemoryFile", { scope: "PROJECT", name: "build.md" });
// → { content: "..." }

await rpc("writeMemoryFile", {
  scope: "PROJECT",
  name: "build.md",
  content: "- Build: mvn -B install -DskipTests\n"
});
// → { ok: true, path: "<cwd>/.aethercode/.../build.md" }

await rpc("deleteMemoryFile", { scope: "PROJECT", name: "old.md" });
// → { ok: true, ... }
```

**安全**: path-scope 检查,拒绝 `..` / 符号链接逃出 memory 目录。

#### 4.1.3 压缩

```javascript
await rpc("compressProjectMemory", { force: true, threshold: 50, keepRecent: 10 });
// → { ok, compressed, beforeCount, afterCount, reason, cwd, file }
```

#### 4.1.4 Audit 读取

```javascript
await rpc("viewAuditLog", { limit: 100, sinceMs: 1726000000000 });
// → { ok: true, count, entries: ["...json line 1...", ...], path: "<absolute path>" }
```

- 默认 limit=100,max=1000
- `sinceMs` 是 best-effort 过滤(扫 tail-ward 200 行,parse `ts` 字段;解析失败保留)

#### 4.1.5 看到当前 system prompt 里有什么 memory

```javascript
await rpc("getSystemPrompt");
// → { text, totalChars, sectionCount, sections: [{name, length, source, firstLine, paths?}, ...] }

await rpc("getSystemPromptSection", { name: "memory" });
// → { ok, name, source, length, text }
```

#### 4.1.6 完整 RPC 列表

| RPC | 用途 |
|---|---|
| `setMemory` | 写一条 k/v 到 USER / PROJECT / SESSION |
| `getMemory` | 按 key 读一条 |
| `listMemory` | 列出 scope 下所有 k/v |
| `deleteMemory` | 按 key 删一条 |
| `listMemoryFiles` | 列 scope 下所有 .md 文件 |
| `readMemoryFile` | 读 .md 全文 |
| `writeMemoryFile` | 写 .md 全文(覆盖) |
| `deleteMemoryFile` | 删 .md |
| `compressProjectMemory` | 触发 LLM 压缩 PROJECT change log |
| `viewAuditLog` | 读 audit log |
| `getSystemPrompt` / `getSystemPromptSection` | 看 system prompt 的 memory section |

### 4.2 文件直接编辑

最简单粗暴的方式 — daemon 启动时会自动 include 到 system prompt:

```bash
# USER 全局偏好(对所有项目生效)
echo "- preferPowerShellOnWindows: true" >> ~/.aethercode/agent-memory/MiniMax-M3/preferences.md

# PROJECT 项目约定(只对当前项目生效)
echo "- Maven multi-module, build with -B" > .aethercode/agent-memory/MiniMax-M3/build.md
```

### 4.3 wm_* 工具(模型调用)

模型在自己的 query 里:

```
wm_put(kind="TODO", content="查 mavis jar SHA 跟 0.2.51 是不是同一个")
wm_put(kind="EVIDENCE", content="maven build 42s success", meta={"source": "build.log"})
wm_list()
wm_get(id="...")
wm_clear()  # 重置 scratchpad
```

> 注:working memory 在 query 结束自动 clear,跨 query 不保留。

### 4.4 环境变量 / 系统属性

| 变量 | 作用 | 默认 |
|---|---|---|
| `AETHERCODE_MEMORY_DIR` | 覆盖 memoryBase 根目录 | `~/.aethercode` |
| `-Daethercode.memory.audit.enabled=false` | 全局关掉 audit | `true` |

### 4.5 看 audit log

**TUI / RPC**(已上线):
```javascript
await rpc("viewAuditLog", { limit: 200 });
```

**直接看文件**(随时):
```bash
tail -f ~/.aethercode/audit.log | jq -c .
```

**TUI 按钮**(R234): 后端已就绪,桌面按钮留给 TUI round。

---

## 5. 实际场景示例

### 5.1 跨项目偏好:每次新建项目都想用 PowerShell + tabs

```javascript
// 一次性写
await rpc("setMemory", {
  scope: "USER",
  content: "- always use PowerShell on Windows\n- indent with tabs, not spaces",
  tags: ["preference", "formatting"]
});
```

效果:以后所有项目 query start,这段会出现在 system prompt 的 memory section。

### 5.2 项目构建命令:让模型知道怎么 build

**方式 A** — 手工 .md:
```bash
cat > .aethercode/agent-memory/MiniMax-M3/build.md <<'EOF'
- Build: mvn -B install -DskipTests
- Test: mvn -B test
- Run CLI: java -jar aethercode-cli/target/aethercode-cli-0.1.0-SNAPSHOT-shaded.jar --print "..."
EOF
```

**方式 B** — RPC:
```javascript
await rpc("writeMemoryFile", {
  scope: "PROJECT",
  name: "build.md",
  content: "- Build: mvn -B install -DskipTests\n..."
});
```

### 5.3 同 session 内事实:用户刚说"用 junit 5"

**模型自动**(R232 新行为):query start 召回时,如果 session_memory k/v 表里有相关 key,自动拼进 system prompt。

**手动**:
```javascript
await rpc("setMemory", {
  scope: "SESSION",
  key: "test_framework",
  value: "junit 5"
});
// 之后 listMemory + recallSessionKv 自动能召回
```

### 5.4 经验沉淀:这次 maven 编译成功想记下来

完全不用手工。query 成功后 daemon 自动:
- 满足 heuristic → 写一条 `EXPERIENCE { kind: CASE, title: "...", body: "..." }`
- transcript 含"always / remember / 以后"或同工具 ≥3 次 → 调 LLM 写 `EXPERIENCE { kind: STRATEGY }`

下次 query start 自动召回 top-3 user + top-3 project 进 system prompt。

### 5.5 单 query scratchpad:长任务分步骤

```
[query start]
wm_put(kind="PLAN_STEP", content="1. 读 MEMORY.md 看现有规则")
wm_put(kind="PLAN_STEP", content="2. 查 mavis 的 mtime")
wm_put(kind="PLAN_STEP", content="3. 算 SHA 比对")
wm_list()   # 在 system prompt 看到完整计划
... 执行中 ...
wm_put(kind="EVIDENCE", content="mavis.jar SHA 跟 0.2.51 一致")
[query end]
buffer 自动 clear
```

### 5.6 审计:谁动了我的 memory

```javascript
// 查最近 100 条
const r = await rpc("viewAuditLog", { limit: 100 });
r.entries.forEach(line => console.log(JSON.parse(line).action, JSON.parse(line).key));

// 看 1 小时前到现在的
const sinceMs = Date.now() - 3600 * 1000;
const r2 = await rpc("viewAuditLog", { limit: 500, sinceMs });
```

### 5.7 子 agent 隔离

子 agent 调 `TaskMemoryStore.store(taskId, "draft", "...")` 不会污染主 agent。

---

## 6. API 速查表

### 6.1 工具(Tool pool)

| Tool | 来源 | 何时装入 |
|---|---|---|
| `file_read` / `file_write` / `file_edit` | `StandardTools` | 默认 |
| `glob` / `grep` / `bash` | `StandardTools` | 默认 |
| `todo_write` / `sub_todo_write` | `StandardTools` | 默认 |
| `agent` / `subagent_status` / `subagent_list` | `StandardTools` | 默认 |
| `web_fetch` / `web_search` | `StandardTools` | 默认 |
| `notebook_edit` / `ask_user_question` | `StandardTools` | 默认 |
| `wm_put` / `wm_get` / `wm_list` / `wm_clear` | `WorkingMemoryTools` (R233) | 默认 |

### 6.2 RPC 速查(11 个)

| RPC | 输入 | 输出 |
|---|---|---|
| `setMemory` | `{scope, key?, content/value, tags?, sessionId?, cwd?}` | `{ok, scope, key, content, ...}` |
| `getMemory` | `{scope, key, sessionId?, cwd?}` | `{ok, scope, key, value, ...}` |
| `listMemory` | `{scope, sessionId?, cwd?}` | `{ok, count, entries: [...]}` |
| `deleteMemory` | `{scope, key, sessionId?, cwd?}` | `{ok, scope, key}` |
| `listMemoryFiles` | `{scope, agentType?}` | `{files: [...], count}` |
| `readMemoryFile` | `{scope, name, agentType?}` | `{content}` |
| `writeMemoryFile` | `{scope, name, content, agentType?}` | `{ok, path}` |
| `deleteMemoryFile` | `{scope, name, agentType?}` | `{ok, ...}` |
| `compressProjectMemory` | `{force?, threshold?, keepRecent?}` | `{ok, compressed, beforeCount, afterCount, reason, ...}` |
| `viewAuditLog` | `{sinceMs?, limit?}` | `{ok, count, entries: [...], path}` |
| `getSystemPrompt` / `getSystemPromptSection` | `{}` / `{name}` | `{text, sections: [...], ...}` |

### 6.3 关键 Java 类(模块 aethercode-memory)

| 类 | 作用 |
|---|---|
| `MemoryScope` | 枚举 USER / PROJECT / SESSION / LOCAL / AUTO |
| `MemoryPaths` | 路径解析 + `AETHERCODE_MEMORY_DIR` 覆盖 |
| `FileBackedMemory` | 单文件存储 + add/get/list/remove/touch/setSensitivity |
| `LayeredMemoryStore` | 7 类统一门面(USER/PROJECT/SESSION/EXPERIENCE) |
| `SessionMemoryStore` | SQLite-backed session k/v |
| `MemoryRecall` | 文件级 recall 评分 + 渲染 |
| `MemoryLifecycle` | **4-tier 编排器** —— query 生命周期的中心 |
| `MemoryExtractor` | 长 session 自动 LLM 摘要写 SESSION |
| `MemoryAudit` | append-only JSONL audit log |
| `Sensitivity` | 4 级 sensitivity 标签 |
| `ForgettingPolicy` | decay 评分 + tombstone/prune |
| `WorkingMemoryBuffer` | 单 query 内的 LRU scratchpad |
| `WorkingMemoryTools` | 4 个 wm_* 工具(R233) |
| `ExperienceRecord` / `ExperienceKind` / `ExperienceStore` | 经验记录(三种 kind) |
| `TaskMemoryStore` | 子 agent 隔离 |
| `MemoryShare` | 跨 session tag-based 共享(USER scope) |
| `ProjectMemoryCompressor` | 50 行 LLM 压缩 |

---

## 7. 限制 & 注意事项

| 限制 | 说明 | 何时修 |
|---|---|---|
| **USER 范围几乎不自动写** | session 内的"全局偏好"靠手工编辑 / RPC | R234+ 加 promote 机制 |
| **TUI "View audit log" 按钮** | RPC 后端就绪,桌面按钮没接 | R234 TUI round |
| **多 session MemoryExtractor** | 当前 per-default-session 单例 | R234+ |
| **AUTO scope 没用** | 枚举里有,`buildMemorySection` 不会真扫 | R234+ cleanup |
| **Multimodal memory 缺** | arXiv §7.4 重要方向 | R235+ |
| **Offline consolidation (sleep)** | 综述 §7.8 CLS 理论 | R235+ |
| **wm_* 工具 permission** | 默认 `Allow`,安全因为只动进程内 buffer | (不需要改) |
| **审计体量** | audit log 不轮转,长期运行会变大 | 建议 logrotate |
| **PII 召回** | PII 默认隐藏,真要召回要 opt-in RPC | (安全设计) |

---

## 8. 排错速查

| 症状 | 排查 |
|---|---|
| 写了 USER memory 但 recall 不到 | 1) `getMemory` 验 key 存在 2) `Sensitivity` 不是 PII / SENSITIVE 3) 文件名非空 |
| PROJECT change log 一直没压缩 | 1) `listMemoryFiles` 看 count 2) `compressProjectMemory({force: true})` 强制 |
| wm_put 提示 "no working buffer active" | daemon 没装 MemoryLifecycle(检查 `DaemonRunner`) |
| audit log 没写入 | 1) `-Daethercode.memory.audit.enabled=true` 2) MemoryLifecycle 装了 |
| SideNote "recalled 0 memory file(s)" | 1) scope 目录没 .md 2) 都 PII 隐藏 3) `surfacedMemories` 这次 query 已 surfaced 过(去重) |
| MemoryItem 同 key 多次 setMemory 行为 | `putUser` 总是 add(append),需要 update 用 `FileBackedMemory.update(id, content, tags)`(目前未暴露 RPC) |
| 项目切换后 PROJECT memory 不见了 | 检查 cwd,PROJECT 跟 cwd 走 |

---

## 9. 进阶:自己扩展

### 9.1 加一种新的 memory kind

1. 在 `ExperienceKind` 加枚举(`STRATEGY` / `SKILL` / `CASE` 之外)
2. 在 `LayeredMemoryStore.appendUserExperience` / `appendProjectExperience` 支持
3. (可选) 加一个 `MemoryLifecycle` tier,触发器写在 `onQueryEnd`
4. 写测试,跑 `mvn test -pl aethercode-memory`(baseline 240 tests)

### 9.2 加一个新 scope

不推荐(改动面太大:5 个 RPC、MemoryPaths、recall pipeline 都要动)。优先用 `Sensitivity` 区分。

### 9.3 自定义衰减策略

`ForgettingPolicy` 构造接受 5 个 weight + 3 个 threshold。`MemoryLifecycle.Config` 有 `decayIntervalMs` 字段。

### 9.4 关闭 audit(测试场景)

JVM 启动加 `-Daethercode.memory.audit.enabled=false`。
