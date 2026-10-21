# 技术方案：AetherCode 适配 Multica 的 qwen 协议族

**日期**：2026-10-21
**状态**：待 review（配套 [决策文档](./MULTICA-AETHERCODE-INTEGRATION-DECISION.md)）
**约束**：**Multica 源码不改**，AetherCode 单侧适配
**源码基准**：`github.com/multica-ai/multica` @ `main`

> Multica 侧结论标注 `[源码]` / `[文档]`；AetherCode 侧来自本仓库实读。

---

## 1. 架构

```
┌──────────────────────────────────────────────────────────┐
│  Multica Server (Go, PostgreSQL 17)                      │
│  issue 看板 / WS 推送 / task 队列 / Public API           │
└─────────────────────────┬────────────────────────────────┘
                          │ REST + WS
┌─────────────────────────▼────────────────────────────────┐
│  Multica Daemon (本地 Go)                                │
│  · 注入 10 个 MULTICA_* env                              │
│  · qwen 协议族 adapter:注入 --output-format stream-json  │
│  · 写 <workDir>/.qwen/skills/                            │
│  · 代为上传 progress / messages / final status          │
└─────────────────────────┬────────────────────────────────┘
                          │ command + args
                ┌─────────▼──────────┐
                │  wrapper 脚本       │  ← profile 的 command 字段
                │  （可无）           │
                └─────────┬──────────┘
                          │ stdin: prompt    stdout: NDJSON
┌─────────────────────────▼────────────────────────────────┐
│  aethercode run --output-format stream-json --yolo       │
│  · 读 stdin 到 EOF 作 prompt                             │
│  · StdioTransport 复用（进程内自包含 QueryEngine）        │
│  · StreamEvent → qwen schema 映射                        │
│  · 读 workdir AGENTS.md + .qwen/skills/                  │
└──────────────────────────────────────────────────────────┘
```

### 1.1 为什么 command 字段可能需要 wrapper 脚本

docs 原文：
> The command field takes an executable and arguments, **not a shell script**. Plain arguments, quotes, and backslash escapes work; pipes, redirects, `&&`, `;`, backticks, and environment variable expansion **do not**.

参数顺序 `[文档]`：
```
<your command> <your fixed args> <Multica's protocol args> <agent's custom args>
```

docs 举的例子：
> If your command is `ccms start q36`, the tool sees `start q36` first and can select its subcommand before Multica's `-p` and the rest arrive — which is the only order such a wrapper accepts.

→ 若 AetherCode 的 `run` 是子命令（推荐形态），可以直接把 command 填成 `aethercode` + fixed args `run`，**不需要 wrapper**。wrapper 只在需要环境变量预处理时才用。

### 1.2 为什么 prompt 必须走 stdin

Windows `CreateProcess` 的 `lpCommandLine` 上限 32767 字符（走 `.cmd` shim 时 8191）。`opencode.go:133-141` 注释：

> Inlining it instead fails hard on Windows: CreateProcess caps lpCommandLine at 32,767 characters

qwen backend 的做法 `[源码]`：prompt 走 stdin，**写完立即 `close(stdin)`**（`qwen.go:146-155`）—— 关闭 stdin 就是"prompt 结束"的信号。

→ AetherCode 的 `run` 必须**读到 EOF** 才当 prompt 结束。

---

## 2. qwen 协议族规范

### 2.1 Multica 注入的 argv `[源码]`

`qwen.go:buildQwenArgs`（行 61-79）：

```
qwen --output-format stream-json [--model <M>] [--resume <SID>] --yolo <ExtraArgs> <CustomArgs>
```

⚠️ **注意没有 `-p`**。`-p`/`--prompt` 虽在 blocklist 里（`qwen.go:26-33`）但 backend **不注入** —— prompt 走 stdin。

→ AetherCode 的 `run` 必须接受：`--output-format` / `--model` / `--resume` / `--yolo` + 位置外的任意 custom args。

### 2.2 事件 schema `[源码]`

`qwen.go:237-271`：

```json
{
  "type": "system" | "assistant" | "user" | "result" | "error",
  "subtype": "...",
  "session_id": "...",
  "model": "...",
  "message": {
    "id": "...",
    "model": "...",
    "content": [
      {"type": "text",        "text": "..."},
      {"type": "thinking",    "thinking": "..."},
      {"type": "tool_use",    "id": "call_x", "name": "bash", "input": {...}},
      {"type": "tool_result", "tool_use_id": "call_x", "content": "..."}
    ],
    "usage": {...}
  },
  "result": "...",
  "is_error": false,
  "usage": {"input_tokens": 0, "output_tokens": 0, "cache_read_input_tokens": 0},
  "error": "..."
}
```

**终态**：`type:"result"`（`qwen.go:309-311`）或 `type:"error"`（`:329-333`）。

### 2.3 StreamEvent 映射表

| AetherCode `StreamEvent` | qwen wire |
|---|---|
| `RunStart(runId, modelId)` | `{"type":"system","subtype":"init","session_id":<runId>,"model":<modelId>}` |
| `TextDelta(text)` | `{"type":"assistant","message":{"content":[{"type":"text","text":<text>}]}}` |
| 模型 thinking | `{"type":"assistant","message":{"content":[{"type":"thinking","thinking":...}]}}` |
| `ToolUseStart(id, name, input)` | `{"type":"assistant","message":{"content":[{"type":"tool_use","id":...,"name":...,"input":{...}}]}}` |
| `ToolResult(id, content, isError)` | `{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":...,"content":...}]}}` |
| `RunEnd(stopReason, blocks)` | `{"type":"result","result":<拼接文本>,"is_error":<bool>,"usage":{...}}` |
| 异常 | `{"type":"error","error":<msg>}` |

### 2.4 终态映射（抄 qwen 的 `finalizeStreamResult` 语义）

`stream_json_result.go:89` 是 **fail-closed** 契约：干净退出 ≠ 成功，必须见到 result 事件。

| 条件 | `Result.Status` |
|---|---|
| 无 result 事件 | `failed`（"stream ended without terminal result"） |
| `is_error: true` | `failed` |
| `context.DeadlineExceeded` | `timeout` |
| `context.Canceled` | `aborted` |
| 成功但空文本 | 返回空 output |

⚠️ **必须主动保证每次都有 result 事件** —— 这正是我们 R735→R746 那一串修复在保证的事（`StreamConsumerGuard` + `StreamEvent` 终态契约）。

### 2.5 `is_error` 怎么算

**照抄 qwen 的判法**：qwen 有 `terminal_reason`（对应 claude 的 `prompt_too_long`）且**优先于 `is_error`**（`stream_json_result.go:101-120`）。

AetherCode 侧用 `StreamEvent.bareKind()` 判 kind，**禁止自己写 `startsWith` 启发式**（R743 教训：stopReason 是 decorated 长串 `error: <class>: <msg>`，~120 字符）。

```java
// 伪代码
String kind = runEnd.stopReason() == null ? "end_turn" : new StreamEvent.RunEnd(...).bareKind();
boolean isError = !Set.of("stop","end_turn","tool_calls","awaiting_user_decision","empty_input").contains(kind);
```

（这个集合抄自 `claude.go:166-170` 的 `spanOk` 判定。）

---

## 3. AetherCode 侧改造

### 3.1 新增 `run` 子命令

picocli，注册在 `Main.java:227` 附近（现有：`tui` / `session` / `workflow` / `mcp` / `a2a`）。

```
aethercode run --output-format stream-json [--model M] [--resume SID] [--yolo] [custom args...]
```

**行为**：
1. 读 stdin 到 EOF → prompt 文本
2. 建 `QueryEngine`（复用 `DaemonRunner.java:130-156` 的自包含模式）
3. 消费 Spliterator → 映射成 qwen NDJSON 写 stdout
4. 发 `type:"result"` 终态

### 3.2 NDJSON emitter（~200 行）

`QwenStreamEmitter.java`：
- `StreamEvent` → qwen JSON 的 switch
- 一行一个 JSON，`System.out` 直写（`StdioTransport.java:42` 的 `stdioDefault()` 可参考）
- 日志走 stderr（照抄 `DaemonRunner.java:120-125` 的 `System.setOut(System.err)` 模式）

⚠️ **R746 教训**：消费侧必须接 `StreamConsumerGuard`，别裸 `forEach`。裸 `forEach` 在 Spliterator 阻塞时没有任何超时保护。

### 3.3 `--yolo` 的处理

Multica 会强制注入 `--yolo` `[文档]`。qwen backend 的 `--yolo` 意思是"跳过所有权限确认"。

AetherCode 必须**照做** —— 否则 headless 环境下权限询问会卡死。

但**不能照抄 claude 的 `--permission-mode bypassPermissions`**（那是绕过 claude 自己的模型）。AetherCode 应该走自己的 `BYPASS_PERMISSIONS` 模式，保留 hooks（`WriteExistingFileGuardHook`）—— 只跳过人工确认，不跳过安全钩子。

### 3.4 Skill discovery

Multica 会把 agent 绑定的 skills 写到 `<workDir>/.qwen/skills/` `[文档]`。

AetherCode 现有 skill 路径（`aethercode-core/.../skill/SkillRegistry.java:26-33`）：
- 用户层 `~/.aethercode/skills/`
- 项目层 `<cwd>/.aethercode/skills/`

→ **加一条**：也读 `<cwd>/.qwen/skills/`（1 处改动）。

⚠️ 顺带修一个坑：**`<cwd>` 不能只靠 `cmd.Dir`**。`opencode.go:181-188` 注释：

> cmd.Dir alone is not enough: [the agent] reads PWD (inherited from the parent daemon) before falling back to process.cwd()

AetherCode 侧应在解析 `.qwen/skills/` 时**显式用 `MULTICA_TASK_ID` 关联的 workdir 或显式参数**，不猜 `PWD`。

### 3.5 `MULTICA_TOKEN` 的使用（可选，Phase 2）

AetherCode 读这 5 个 env var，调 Multica Public API 写 issue comment 回报进度。

⚠️ **endpoint + payload 形态本轮未查到**（docs 只给了锚点引用）。下一轮摸清`server/internal/handler` 路由表 + `pkg/publicapi/v1/openapi.yaml`。

**首期可以不做** —— Multica 已经会自动把 daemon 采集的 messages/result 上传，AetherCode 不主动写comment 也能跑。

⚠️ **安全**：如果做了，**绝不能**把 token 写进 prompt / log / 仓库文件（docs 明确警告）。且 AetherCode spawn 大量 bash，token 会被子进程继承 —— 需要 shell policy（后续轮次）。

---

## 4. 已知坑清单

| # | 坑 | 来源 | 处理 |
|---|---|---|---|
| 1 | `--yolo` 强制注入，必须实现 | docs | 走 `BYPASS_PERMISSIONS`，保留 hooks |
| 2 | protocol-critical flag 被 drop | docs | `run` 必须接受这些参数 |
| 3 | **daemon 启动需要至少一个内置 CLI** | docs | 本机先装 `claude` 或 `codex` |
| 4 | prompt 走 stdin 且**写完必须关** | `qwen.go:146-155` | 读到 EOF 才算结束 |
| 5 | Windows argv 32767 上限 | `opencode.go:133-141` | prompt 不走 argv |
| 6 | resume 时 usage 是累计值 | `qwen.go:319-325` | Multica 会拒绝用 resume 的 usage 填缺口；AetherCode 若支持 `--resume` 不要把累计当增量报 |
| 7 | `MULTICA_TOKEN` 被所有子进程继承 | docs 警告 | 后续轮次做 shell policy |
| 8 | 无 `type:"result"` = fail | `stream_json_result.go:89` | 用 `StreamConsumerGuard` 保证终态 |

---

## 5. 关键源码索引

### Multica（`@ main`）

| 内容 | 位置 |
|---|---|
| **qwen backend（模仿对象）** | `server/pkg/agent/qwen.go`（~400 行） |
| qwen argv 构造 | `qwen.go:buildQwenArgs` 行 61-79 |
| qwen blocked args | `qwen.go:26-33` |
| qwen 事件 schema | `qwen.go:237-271` |
| qwen 终态判定 | `qwen.go:309-311`（result）/ `:329-333`（error） |
| qwen prompt 写 stdin + close | `qwen.go:146-155` |
| qwen resume usage 特殊处理 | `qwen.go:319-325` |
| **共享终态契约** | `server/pkg/agent/stream_json_result.go:89` `finalizeStreamResult` |
| terminal_reason 优先于 is_error | `stream_json_result.go:101-120` |
| 协议观察日志 | `qwen.go:170-179` `logStreamProtocolObservation` |
| 日志脱敏 | `server/pkg/agent/launch.go:399` / `:324-364` |
| daemon env 注入 | `server/internal/daemon/daemon.go:179-193` |
| 支持的 family 白名单 | `server/pkg/agent/agent.go:374-401`（26 项） |
| PWD 覆盖坑 | `server/pkg/agent/opencode.go:186-189` |

### AetherCode（本仓库）

| 内容 | 位置 |
|---|---|
| stdio 自包含模式 | `aethercode-cli/.../DaemonRunner.java:130-156` |
| 日志/协议分离写法 | `DaemonRunner.java:120-125` |
| stdio 传输 | `aethercode-protocol/.../StdioTransport.java:42` |
| CLI 主类 | `aethercode-cli/.../Main.java:227` |
| 消费侧终止契约 | `aethercode-core/.../stream/StreamConsumerGuard.java`（R746） |
| **stopReason 分类法** | `aethercode-core/.../stream/StreamEvent.java` `bareKind()` / `isDeterministicHardStop()`（R743） |
| skill registry | `aethercode-core/.../skill/SkillRegistry.java:26-33` |
| 权限模式 | `PermissionHook` / `WriteExistingFileGuardHook` |

---

## 6. 分期

### Phase 0：本地验证（半天，不碰 Multica）

- 实现 `run` 子命令 + emitter
- 手工验证：`echo "总结这个项目" | aethercode run --output-format stream-json`
- 检查：事件流完整、有 `type:"result"` 终态、`session_id` 稳定

### Phase 1：接 Multica（1-2 天）

- 装 Multica CLI/Desktop
- 装至少一个内置 CLI（`claude` 或 `codex`）让 daemon 启动
- 加 `.qwen/skills/` discovery
- UI → Add custom runtime，family=`qwen`，command=`aethercode`（+ fixed args `run`）
- 创建 agent 选这个 runtime，派一个真issue

### Phase 2：resume（可选，~150 行）

- `--resume` + `session_id` 输出
- ⚠️ usage 只报增量

### Phase 3：comment 回报 + shell policy

- 摸清 Public API 后读 `MULTICA_TOKEN` 写 comment
- shell policy 防token 泄露到 bash 子进程