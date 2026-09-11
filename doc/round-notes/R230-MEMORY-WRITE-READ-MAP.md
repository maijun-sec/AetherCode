# AetherCode Memory — 写什么、什么时候写、什么时候读（截至 R231）

**Status**: 截至 R231 (2026-09-07) 的实际状态
**Scope**: 7 种 Memory × {典型内容、写时机、读时机、读场景}
**重要约定**: 表格里"已 wire"= 真实在 query 流程里跑；"仅 API"= 代码里有但需要 RPC/手工触发；"仅设计"= 类的形态在，但没有调用方

---

## 0. TL;DR — 一张表全览

| Memory 类型 | 物理位置 | 主要内容 | 写时机 | 读时机 | 当前 wire 状态 |
|---|---|---|---|---|---|
| **GLOBAL (USER)** | `~/.aethercode/agent-memory/<agentType>/*.md` | 跨项目的稳定偏好、build 命令、用户身份信息 | 仅 RPC `setMemory({scope:USER})` 或手工编辑 | 每个 query start（recall 走 R19-B） | **几乎不会被 session 自动写** |
| **PROJECT** | `<cwd>/.aethercode/agent-memory/<agentType>/*.md` + 变更日志 | 项目专有知识、build/run 命令、change log | ① RPC `setMemory({scope:PROJECT})` ② 每次 change log append 触发 50 行 LLM 压缩 | 每个 query start | **change log 自动 append + 压缩**已 wire；自动 extraction 未 wire |
| **SESSION** | `~/.aethercode/sessions.db` (SQLite) + `<cwd>/.aethercode/sessions/<sid>.jsonl` | 消息流、每 session 的临时 k/v 事实 | ① 每条 assistant/user/tool 消息自动 append 到 jsonl ② RPC `setMemory({scope:SESSION})` | 同 session 内 recall（默认 scope 不扫） | **jsonl 自动写**已 wire；k/v 仅 RPC |
| **EXPERIENCE** (R230) | `<scope>/agent-memory/<agentType>/experience/<id>.json` | 成功的 trajectory (CASE) 或抽象洞察 (STRATEGY/SKILL) | 每个 query 成功后 R231 Tier 2 启发式写入 | 下次 query start 召回 top-k | **R231 已 wire** (heuristic, 无 LLM) |
| **WORKING** (R230 骨架) | 进程内 in-memory LRU | 单 query 内的 active scratchpad（TODO / EVIDENCE / PLAN_STEP） | query start 时 new；query 内部 agent 可 put；query 结束 clear | 仅在同 query 内 | **R231 仅骨架**，R232 接入 QueryEngine |
| **AUDIT** (R230) | `<memoryBase>/audit.log` (JSONL) | 所有 memory read/write/decay/recall 事件 | 每个 lifecycle hook 触发 | 用户显式 `viewAuditLog`（R232 才有 RPC）；后台 调试用 | **R231 已 wire** 每个 hook |
| **TASK** (R23-E) | `<memoryBase>/agent-memory-tasks/<agentType>/<taskId>/` | 子 agent 的隔离 memory | 子 agent 显式 put | 子 agent 显式 get | **设计完整**，但需 subagent 实例化时显式拿 view |

**关键事实**：当前 session 内自动写入的只有 3 类 —— **SESSION jsonl transcript**、**EXPERIENCE (R231 case-based)**、**AUDIT (R231 hook)**。其他都是 RPC / 手工触发。

---

## 1. GLOBAL Memory（USER 范围，跨项目稳定）

### 1.1 内容画像

"什么时候进 USER scope" = "这个信息是不是跨项目都成立"。

| 应该进 | 不应该进 |
|---|---|
| 用户身份：`user.name = 张三`、`user.email` | 当前项目的 build 命令（→ PROJECT） |
| 全局偏好：`preferPowerShellOnWindows=true` | 当天的工作进度（→ SESSION） |
| 全局身份偏好：`always use junit5`、`always use tabs` | 一次性的 project 笔记（→ PROJECT） |
| 用户的 MCP token / API key (标 `Sensitivity.PII`) | 工具执行结果（→ SESSION transcript） |
| 跨项目的"硬规则"：`never rm -rf /` | 一次成功的 trajectory（→ EXPERIENCE） |

**判定启发式**（R232 计划实现）：
- tag = `scope-promote` 才会从 PROJECT/SESSION promote 到 USER
- sensitivity 显式标记 `pii` 时才进 USER 的 PII 槽（不进普通 USER）
- 用户在 UI 显式 "pin to global" 才进

### 1.2 写时机

| 触发 | 代码路径 | 状态 |
|---|---|---|
| RPC `setMemory({scope: "USER", key, content, tags})` | `AetherCodeMethods.setMemory` → `memoryStore.putUser` | **已 wire** |
| TUI "Pin to global" 按钮 | 走同一个 RPC | **已 wire**（前端） |
| Session 内的自动 promotion | 无 | **未 wire**（R232 计划） |
| 启动时自动 bootstrap | 无 | **未 wire**（MEMORY.md 是手工/PowerShell 写的） |

⚠️ **当前现状**：USER 范围在运行时**几乎不会被自动写入**。`~/.aethercode/agent-memory/` 通常是手工编辑或一次性 bootstrap 出来的。

### 1.3 读时机

| 触发 | 路径 | 状态 |
|---|---|---|
| 每个 query start | `AetherCodeEngine.buildMemorySection` → `MemoryRecall.recall` 扫 USER 目录 | **已 wire**（R19-B） |
| RPC `getMemory/listMemory({scope:USER})` | `AetherCodeMethods.getMemory/listMemory` | **已 wire** |
| Memory Panel UI | 走 RPC | **已 wire**（R80） |

**读顺序**：`USER > PROJECT > LOCAL`，先命中的赢（去重），所以 USER 文件的优先级最高。

### 1.4 实际读到的内容

当前 AetherCode 这个项目里，**USER 范围目前是空的**（`~/.aethercode/agent-memory/` 不存在），所以 query 启动时 recall 不会命中 USER 范围。所有实际可见的 memory 是 PROJECT 范围的（`<cwd>/.aethercode/agent-memory/MiniMax-M3/`）。

---

## 2. PROJECT Memory（cwd 绑定，per-project）

### 2.1 内容画像

| 应该进 | 不应该进 |
|---|---|
| 项目 build/test/run 命令 | 用户个人偏好（→ USER） |
| 项目专有约定：`Maven multi-module`、`use PowerShell` | 当天聊天（→ SESSION） |
| 项目的 external refs（特定库版本） | 跨项目可复用的洞察（→ EXPERIENCE/USER） |
| 项目的 MEMORY.md 入口（含 200 行 / 25KB 硬上限） | 频繁变动的运行时状态 |
| 项目的 change log（自动生成，[ISO timestamp] content） |  |

### 2.2 写时机

| 触发 | 代码路径 | 状态 |
|---|---|---|
| 每次用户/agent 显式 put | RPC `setMemory({scope: "PROJECT", cwd, content, tags})` | **已 wire** |
| TUI "Add to project memory" | 走 RPC | **已 wire** |
| `appendProjectChange` 后超 50 行触发 LLM 压缩 | `ProjectMemoryCompressor.maybeCompress` (R127) | **已 wire**（LLM 可用时） |
| 切换 cwd 时 | 旧的 `invalidateProject` 清缓存，**不删内容** | **已 wire**（R127） |
| Session 结束的自动 extraction | `MemoryExtractor.extract()` 设计存在 | **未 wire**（无调用方） |
| R231 Tier 2/3 自动写 experience | `MemoryLifecycle.onQueryEnd` | **已 wire**（heuristic, 无 LLM） |

⚠️ **当前现状**：PROJECT 范围的"自动 extraction"路径（`MemoryExtractor.extract`）虽然有完整的类+测试，但**没有任何调用方 wire 进 query 流程**。R231 用自己的启发式（更便宜的方案）做了 case-based 经验。

### 2.3 读时机

| 触发 | 路径 | 状态 |
|---|---|---|
| 每个 query start | `buildMemorySection` → `MemoryRecall.recall` 扫 PROJECT 目录 | **已 wire** |
| TUI 展示 | RPC | **已 wire** |
| `appendProjectChange` 时 | 同 store 内部读 | **已 wire** |
| `compressProjectMemory({force:true})` 手动触发 | RPC | **已 wire** |

**读顺序**：USER 之后。所以 PROJECT 命中会作为 fallback 出现。

### 2.4 PROJECT 范围的实际文件

```
<cwd>/.aethercode/agent-memory/<agentType>/
├── MEMORY.md            # 入口，最多 200 行 / 25KB；列其他文件
├── build.md             # build/test/run 命令
├── preferences.md       # 项目偏好
├── change log line      # 自动 append，格式 "[<iso8601>] <content>"
└── (其他用户手动 put 的 .md)
```

**重要事实**：这些 .md 文件是**手工或一次性脚本**创建的（看 MEMORY.md 里有 PowerShell `Out-File` 命令痕迹），不是 daemon 启动时自动生成的。R127 设计是 model 自己往这里写，但**没有触发机制让 model 在 query 过程中自动写 PROJECT 范围的 fact/rule**。

---

## 3. SESSION Memory（per-session，临时）

### 3.1 内容画像

| 应该进 | 不应该进 |
|---|---|
| 当天对话的全部消息流 | 跨 session 成立的偏好（→ USER） |
| 用户在某个 session 提到的临时 k/v | 项目长期约束（→ PROJECT） |
| 该 session 产出的中间事实 | 工具执行结果摘要（可重新生成） |
| 每条 user / assistant / tool 消息 |  |

### 3.2 写时机

| 触发 | 代码路径 | 状态 |
|---|---|---|
| **每条消息** | `appState.appendMessage` → `SessionStore` → jsonl append | **已 wire**（高频，每条消息一次） |
| RPC `setMemory({scope: "SESSION", sessionId, key, value})` | `memoryStore.putSession` | **已 wire**（低频） |
| R231 Tier 0 tool call | `MemoryLifecycle.onToolCall` 写 audit（不是 session memory 本身） | **已 wire** |
| R231 Tier 1 query start/end | audit 写，不是 session memory | **已 wire** |

⚠️ **当前现状**：SESSION 范围有两种物理存储，**都自动写**：
- jsonl: 每条消息（高频）
- SQLite k/v (`session_memory` 表): 仅 RPC（低频，几乎没人用）

### 3.3 读时机

| 触发 | 路径 | 状态 |
|---|---|---|
| 同 session 内 recall | **当前 `buildMemorySection` 不会主动扫 SESSION**（R19-B 仅扫 USER/PROJECT/LOCAL） | **未 wire** session 召回 |
| 加载已存在的 session | `AetherCodeEngine.loadSession` 读 jsonl | **已 wire** |
| RPC `getMemory({scope:SESSION})` | `AetherCodeMethods.getMemory` | **已 wire** |

⚠️ **GAP**：SESSION 范围**当前不会进入 system prompt 的 recall 段**。这是一个真实的设计空缺 —— 你 5 分钟前说的"用 junit 5"可能现在同 session 内 recall 不到，因为只读 jsonl 流而不读 session_memory k/v 表。R232 计划补。

### 3.4 SESSION 范围的实际文件

```
~/.aethercode/
├── sessions.db                          # SQLite: session_info + session_memory k/v
└── sessions/
    ├── <timestamp>_<sid>.jsonl          # 消息流（每条一行）
    └── <timestamp>_<sid>.cwd            # session 绑定的 cwd
```

---

## 4. EXPERIENCE Memory（R230 落地 + R231 wire）

### 4.1 内容画像

| Kind | 内容 | 例子 |
|---|---|---|
| **CASE** | 一次具体 trajectory 的解法 | "在 mavis 项目跑 maven build 用了 -B install -DskipTests，42 秒成功" |
| **STRATEGY** (R232) | 抽象的 workflow / 启发式 | "当 mavis 编译失败时，先看 surefire-reports 再 dump bytecode" |
| **SKILL** (R232) | 可调用的 function/API/MCP 描述 | "SkillWeaver: 自动把成功 trajectory 提炼成可调用脚本" |

### 4.2 写时机（R231 当前实现）

| 触发 | 路径 | 状态 |
|---|---|---|
| **每个成功 query** + ≥1 tool call | `MemoryLifecycle.onQueryEnd` → `extractCaseFromTranscript` → `LayeredMemoryStore.appendUserExperience` 或 `appendProjectExperience` | **已 wire**（Tier 2，启发式，**无 LLM**） |
| query transcript 含策略关键词（always/remember/...）或同工具 ≥3 次 | `MemoryLifecycle.onQueryEnd` → `maybeExtractStrategy` | **R231 stub 返回 null**，R232 实装 LLM |
| RPC `appendExperience` | 设计上存在 | **未 wire**（R232 计划） |
| 用户显式 "save this" | 设计上存在 | **未 wire** |

**Tier 2 body 内容**（无 LLM）:
- `title` = 首条 user 消息前 80 字符
- `body` = 末条 assistant 消息 + "## Tools used" 段（含调用计数）
- `tags` = 去重的工具名前 8 个
- `sourceOutcome` = "success"
- `utility` = 0.5（默认）
- `links` = 空（KG 升级留 R232）

**写到哪里**：当前 projectCwd 存在 → PROJECT scope；否则 → USER scope。

### 4.3 读时机

| 触发 | 路径 | 状态 |
|---|---|---|
| **每个 query start** | R231 **未把 experience 召回进 buildMemorySection** | **未 wire** ⚠️ |
| R231 加了 `listUserExperience(k)` / `listProjectExperience(cwd, k)` API | 在 `LayeredMemoryStore` 上 | **已 wire**（API 层） |
| 召回排序 | `utility desc → uses desc → createdAt desc` | **已 wire**（`ExperienceStore.topK`） |

⚠️ **GAP**：R230 建了经验库，R231 wire 了写，**但读没接进 buildMemorySection**。这是个明显的 R232 待办 —— 否则经验库再大也没用，没人召回。

### 4.4 物理位置

```
<scopeRoot>/agent-memory/<agentType>/experience/<id>.json
```

每个 record 一个 JSON 文件（one file per record），不是单文件 JSONL 数组。

---

## 5. WORKING Memory（R230 骨架 + R231 部分 wire）

### 5.1 内容画像

单 query 内的 active scratchpad。6 种 entry kind:

| Kind | 用途 |
|---|---|
| TEXT | 任意短文本 |
| KEY_VALUE | JSON-like `{k:v, k:v}` |
| REFERENCE | 指向 memory item (id, scope, key) |
| PLAN_STEP | 当前 working plan 的一个 bullet |
| EVIDENCE | 模型在"思考"的事实/snip |
| TODO | open todo |

### 5.2 写时机

| 触发 | 路径 | 状态 |
|---|---|---|
| **每个 query start** | `MemoryLifecycle.onQueryStart` → `new WorkingMemoryBuffer(sessionId, queryId)` | **已 wire**（R231） |
| Agent 在 query 内部 put | 设计上允许 | **未 wire**（QueryEngine 没接 R232） |
| Tool result 后 put | 设计上允许 | **未 wire**（R232） |
| **每个 query end** | `MemoryLifecycle.onQueryEnd` → `currentBuffer.clear()` | **已 wire**（R231） |

⚠️ **当前现状**：buffer 被**创建**了、被**清空**了，但**没人往里写东西**。query 内部还是依赖 context window。

### 5.3 读时机

| 触发 | 路径 | 状态 |
|---|---|---|
| QueryEngine 渲染 system prompt | `WorkingMemoryBuffer.render()` | **未 wire**（R232） |
| Agent tool 调用 | 设计上允许 | **未 wire** |

### 5.4 物理位置

进程内 in-memory LRU（默认 50 条），不落盘。query 结束 clear，**跨 query 不保留**。

---

## 6. AUDIT Memory（R230 落地 + R231 wire）

### 6.1 内容画像

append-only JSONL 日志，每行一个 JSON 对象，字段：

```json
{
  "ts": "2026-09-07T...",
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

### 6.2 写时机（R231 全部 wire）

| Hook | 频率 | action |
|---|---|---|
| `onQueryStart` | 每 query 一次 | WRITE / kind=buffer |
| `onToolCall` | 每个 tool 调用 | WRITE / kind=tool-call |
| `onMemoryRecallHit` | 每个 recall 命中文件 | RECALL / scope / key / kind=hit |
| `onMemoryWrite` | 每次 write | WRITE / scope / key / kind |
| `onQueryEnd` | 每 query 一次 | WRITE / kind=query-end / decision=ALLOW\|DENY |
| `runPeriodicDecay` | 每 5 分钟（如有清理） | DECAY / decision=TOMBSTONED |

### 6.3 读时机

| 触发 | 路径 | 状态 |
|---|---|---|
| `MemoryAudit.readRecent(N)` | 进程内 API | **已 wire**（但 TUI 还没按钮） |
| TUI "View audit log" 按钮 | 走 RPC `viewAuditLog` | **未 wire**（R232 计划） |
| 用户 `cat ~/.aethercode/audit.log` | 文件直接读 | **已可用** |

### 6.4 物理位置

```
<memoryBase>/audit.log
```

**R230 引入；R231 全面 wire**。这是 R230 之后**唯一一类在每个 query 都自动写**的 memory。

---

## 7. TASK Memory（R23-E 设计，未完全 wire）

### 7.1 内容画像

子 agent 隔离的临时 memory。per-task namespace 避免子 agent 之间互相污染。

### 7.2 写时机

| 触发 | 路径 | 状态 |
|---|---|---|
| Subagent 显式 store | `TaskMemoryStore.store(taskId, key, value)` | **已 wire**（API） |
| Subagent 显式 recall | `TaskMemoryStore.recall(taskId, key)` | **已 wire**（API） |
| 自动写 | 无 | **未 wire** |

### 7.3 读时机

| 触发 | 路径 | 状态 |
|---|---|---|
| 同 subagent 内 | `TaskView` | **已 wire**（API） |
| 跨 subagent | **不允许**（按设计） | **已 wire**（R23-E 隔离保证） |

### 7.4 物理位置

```
<memoryBase>/agent-memory-tasks/<agentType>/<taskId>/<key>
```

---

## 8. 整体数据流（一张图）

```
┌─────────────────────────────────────────────────────────────────┐
│                     SESSION 启动                                 │
│                                                                  │
│  USER query ──→ AetherCodeEngine.query()                        │
│                                                                  │
│  ┌─── Tier 0: onToolCall (每个 tool) ───────────────────┐      │
│  │  audit: WRITE kind=tool-call                          │      │
│  └────────────────────────────────────────────────────────┘      │
│                                                                  │
│  ┌─── Tier 1: onQueryStart ─────────────────────────────┐     │
│  │  new WorkingMemoryBuffer                              │     │
│  │  audit: WRITE kind=buffer                            │     │
│  │  throttled decay pass (if due)                       │     │
│  └────────────────────────────────────────────────────────┘     │
│                                                                  │
│  ┌─── buildMemorySection ──────────────────────────────┐      │
│  │  for each scope in [USER, PROJECT, SESSION, LOCAL, AUTO]:│ │
│  │    MemoryRecall.recall(scopeDir, userInput, recentTools) │ │
│  │    for each hit:                                      │     │
│  │      appState.markMemorySurfaced (去重)              │     │
│  │      audit: RECALL kind=hit  (Tier 1.5)              │     │
│  │  render as system-prompt section                     │     │
│  └────────────────────────────────────────────────────────┘     │
│                                                                  │
│  inner QueryEngine.query() ──→ stream events                  │
│  on each RunEnd:                                                │
│    onQueryEnd(success, transcript)                              │
│      ┌─── Tier 2: extractCaseFromTranscript (heuristic, no LLM)│
│      │  if success && toolCalls >= 1:                          │
│      │    build ExperienceRecord (CASE)                         │
│      │    store.appendUserExperience OR                        │
│      │          .appendProjectExperience (R231)                │
│      │  audit: WRITE kind=experience                           │
│      └────────────────────────────────────────────────────────┘│
│      ┌─── Tier 3: maybeExtractStrategy (gated, LLM stub R231)│
│      │  if pattern matched (always/remember/... or same tool×3)│
│      │    [R232] call LLM, build ExperienceRecord (STRATEGY)  │
│      └────────────────────────────────────────────────────────┘│
│      clear WorkingMemoryBuffer (Tier 1)                        │
│      audit: WRITE kind=query-end (ALLOW or DENY)               │
│                                                                  │
│  ┌─── Tier 4: 5min 后台循环 ──────────────────────────────┐    │
│  │  ForgettingPolicy.runDecayPass(userStore)                  │    │
│  │  ForgettingPolicy.runDecayPass(projectStore)                │    │
│  │  audit: DECAY decision=TOMBSTONED                          │    │
│  └────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────┘
```

---

## 9. 现状痛点（按严重度排）

| # | 痛点 | 影响 | 建议 R-series |
|---|---|---|---|
| 1 | **EXPERIENCE 写而不读** | R231 在每个 query 写经验，但 query start 不召回它们。经验库白写。 | **R232 必做** |
| 2 | **SESSION memory 不参与 recall** | 5 分钟前用户说的"用 junit 5"可能 recall 不到（除非全文搜 user query）。 | R232 接入 |
| 3 | **USER 范围几乎不自动写** | "全局偏好"靠手工编辑，不靠 session 沉淀。 | R232 加 promote 机制 |
| 4 | **MemoryExtractor 已设计未 wire** | 类+测试完整，但调用方=0。`extract()` 本可把会话摘要写 SESSION 的 MEMORY.md。 | R232 接入或删 |
| 5 | **WORKING buffer 没消费者** | 每次 query 创建+清空，但 agent 没法 put。QueryEngine 没接。 | R232 接入 |
| 6 | **Strategy / Skill extraction 仅 stub** | Tier 3 R231 永远 null。R232 才有真 LLM。 | R232 |
| 7 | **TUI 看不到新东西** | Memory Panel 还显示老 entry-based facts，没 experience/audit/decay。 | R233+ |
| 8 | **AUTO scope 没真用** | R19-B 扫的是 `[USER, PROJECT, SESSION, LOCAL, AUTO]` 但 AUTO 不会真出现。 | R232 cleanup |
| 9 | **Multimodal memory 完全缺** | arXiv 综述 §7.4 重要方向。 | R234+ |
| 10 | **no offline consolidation (sleep)** | 综述 §7.8 强调的 CLS 理论。 | R235+ |

---

## 10. 一句话回答你的问题

> 全局 Memory（USER）目前**几乎不会被 session 自动写** —— 路径有，但触发器缺。  
> 实际在 query 流里**自动写**的只有 3 类：**SESSION transcript jsonl**、**R231 Tier 2 经验（heuristic case）**、**R231 audit hook**。  
> **读**的入口有 4 个：每个 query 的 buildMemorySection（USER+PROJECT+LOCAL）、TUI Memory Panel、RPC getMemory/listMemory、MemoryAudit.readRecent。  
> **真正生效的 recall** 只有第 1 个：query start 时扫 USER+PROJECT 范围、按 lexical + tool-name 评分、命中后注入 system prompt。

如果 R232 想接续，最大的杠杆是：**把 EXPERIENCE 召回接进 buildMemorySection**（写而不用是最亏的）和 **wire MemoryExtractor 把会话摘要写 SESSION MEMORY.md**（已有的类终于派上用场）。


---

## 11. R232 落地（2026-09-07）— 关闭上表标注的 3 个 ⚠️

### 11.1 改造

| 改造 | 关闭的 GAP | 文件 |
|---|---|---|
| MemoryLifecycle.recallExperience(...) + 
enderExperienceSection(...) | **GAP #1 EXPERIENCE 写而不用** —— 召回接进 system prompt | MemoryLifecycle.java + AetherCodeEngine.buildExperienceSection |
| MemoryLifecycle.recallSessionKv(...) + 
enderSessionKvSection(...) | **GAP #3 SESSION 不进 recall** —— 5 分钟前说的"用 junit 5"能召回 | MemoryLifecycle.java + AetherCodeEngine.buildSessionKvSection |
| MemoryLifecycle.setMemoryExtractor(...) + DaemonRunner 装配 | **GAP #2 MemoryExtractor 已设计未 wire** —— 长会话 LLM 摘要写 SESSION MEMORY.md | MemoryLifecycle.java + AetherCodeMethods.setMemoryExtractor + DaemonRunner |
| combineThreeSections(...) | 三个 section 拼装 + SideNote 计数 | AetherCodeEngine.java |

### 11.2 现在的 system-prompt section 顺序

`
[先] MemoryRecall   → USER+PROJECT+LOCAL 范围的文件（"## filename.md"）
[中] Experience     → R230/R231 Tier 2 写、R232 读；top-3 user + top-3 project（"### N. title [kind]"）
[后] Session facts  → R127 设计意图终于实现：同 session 内的 k/v（"**key**: value"）
`

AetherCodeEngine.queryInternal 在 uildMemorySection 后加 uildExperienceSection + uildSessionKvSection，三者合并后通过 setMemorySection 一次性注入。

### 11.3 SideNote 升级

旧：
ecalled 3 memory file(s)
新：
ecalled 3 memory file(s) + 2 experience(s)

计数方式：扫 ##  行（但排除"## Past experience"/"## Relevant memories"）+ ###  行（experience entries）。

### 11.4 MemoryExtractor wire

之前 MemoryExtractor 类完整、测试完整，但**调用方=0**（R230 GAP #2）。R232 做了：
- MemoryLifecycle.setMemoryExtractor(extractor) setter（lazy-wired by daemon）
- onQueryEnd 在 success 路径上跑 extractor.shouldExtract(transcript) + extractor.extract(transcript)，把结果写到 SESSION scope 的 MEMORY.md
- AetherCodeMethods.setMemoryExtractor(extractor) 暴露给 daemon
- DaemonRunner.run 创建 MemoryExtractor(chatClient, sessionMemoryDir)，用 methods.chatClientResolverField() 解析的 chat client 喂它
- 触发阈值沿用原类的 10k/5k/3 tool calls

### 11.5 测试

| 模块 | tests | R232 增量 |
|---|---|---|
| aethercode-memory | 207 (was 185) | +21 MemoryLifecycleR232Test |
| aethercode-sdk | 256 | 不变（编译过 = wiring 有效） |
| aethercode-protocol | 269 (排除 R183/R126 老 fail) | 不变 |
| aethercode-cli | 42 | 不变 |

**总 774 tests pass, 0 regression**。R232 关闭了 R230 doc 标注的 3 个 ⚠️ 中最重要的 3 个（GAP #1、#2、#3）。剩 #4（WORKING buffer 没消费者）和 #5（Strategy 提取仍 stub）留给 R233。

### 11.6 R233 落地（2026-09-07）— 关闭 WORKING 工具 + Strategy LLM + viewAuditLog RPC

R233 收尾 R230 设计里的最后两个未 wire 能力：

**1. WORKING buffer 工具化（R233-1）**：
- 新增 `WorkingMemoryTools.java`：`wm_put` / `wm_get` / `wm_list` / `wm_clear` 4 个 tool
- 通过 `StreamingToolExecutor.withWorkingMemorySupplier(Supplier<Optional<Object>>)` 注入 —— 用 `Object` 装箱避开 `aethercode-core` → `aethercode-memory` 的循环依赖
- buffer 在 `CallContext.extras["working_memory"]` 里 stash，`WorkingMemoryTools.currentBuffer(ctx)` 取出
- 没 wire 时工具不抛异常，返回结构化 "no working buffer active" —— 缺 wiring 不会让 agent crash
- `Main.java` 在默认 tool pool 里 `pool.addAll(WorkingMemoryTools.all())`

**2. Strategy LLM 真接上（R233-2）**：
- `MemoryLifecycle.setStrategyChatClient(ChatClient)` setter（`null` 是 no-op，daemon-scoped 寿命）
- `maybeExtractStrategy(transcript, now)` 现在真调 LLM：
  - heuristic gate 没变（仍要 `STRATEGY_PATTERN` 关键词 OR 同 tool 调 ≥3 次）
  - 取 transcript 最后 6 条做 prompt
  - prompt 要求 200 字符内、可泛化；太琐碎返回字面 `"NONE"`
  - 返回 `ExperienceRecord{ kind=STRATEGY, body=## Distilled strategy + ## Source conversation }`
  - LLM 抛异常 → catch-all 返回 null，不破 query
  - 超过 `strategyBodyMaxChars`（默认 1500）截断加 `…`
- `runStrategyChat()` 同步 drain `Stream<StreamEvent>`：累加 `TextDelta.text()`，遇到 `RunEnd` break
- `buildStrategyPrompt()` 集中 prompt 文案

**3. viewAuditLog RPC（R233-3）**：
- `AetherCodeMethods.viewAuditLog(Object params)` 注册到 dispatcher
- 输入：`{ sinceMs?: number, limit?: number }`（默认 100, max 1000）
- 返回：`{ ok, count, entries: [...JSONL 字符串], path }`
- best-effort sinceMs filter：扫 tail-ward 200 行，parse `"ts":"..."` 字段（解析失败保留）
- 没 audit 时返回 `{ok:true, count:0, entries:[], path:""}`

**4. 跨模块 wiring**：
- `AetherCodeEngine.withWorkingMemorySupplier(() -> lc.currentBuffer().map(buf -> (Object) buf))` 装到 executor
- `DaemonRunner` 把 `extractorChat` 同时注入 `setMemoryExtractor` + `setStrategyChatClient` + `setMemoryChatClient`
- 4 个模块编译过 = wiring 有效

**测试结果**：

| 模块 | tests | R233 增量 |
|---|---|---|
| aethercode-memory | 240 (was 207) | +21 `WorkingMemoryToolsTest` +13 `MemoryLifecycleR233Test` -1 (one test rebased to match setter contract) |
| aethercode-sdk | 256 | 不变（编译过 = wiring 有效） |
| aethercode-protocol | 269 (排除 R126) | 不变 |
| aethercode-cli | 42 | 不变 |

**总 807 tests pass, 0 regression**。R233 关闭了 R232 留下的 #4 + #5；TUI 按钮留给 R234 TUI 任务。

### 11.7 已知限制（留给 R234+）

- **TUI 按钮**：`viewAuditLog` RPC 后端已上线，桌面 "View audit log" 按钮是 R234 TUI 任务
- **多 session**：当前 MemoryExtractor 是 per-default-session 单例。多 session 时每个 session 需要自己的 extractor
- **AUTO scope**：枚举里有但 buildMemorySection 不会真用
