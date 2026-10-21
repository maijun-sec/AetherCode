# 决策文档：让 Multica 调度 AetherCode（Multica 不动）

**日期**：2026-10-21
**状态**：已定案
**配套**：[技术方案](./MULTICA-AETHERCODE-INTEGRATION-TECH-DESIGN.md)
**源码基准**：`github.com/multica-ai/multica` @ `main`

---

## 1. 结论

**Multica 源码一行不改。AetherCode 声明自己兼容 `claude` protocol family。**

| 改动面 | 量级 |
|---|---|
| **Multica 侧** | **0**（UI 填 3 个字段） |
| **AetherCode 侧** | ~330 行 / 5 个必做项 |

用户在 Runtimes → 目标电脑 → **Add custom runtime**：

| 字段 | 值 |
|---|---|
| protocol family | `claude` |
| command | `aethercode` |
| fixed arguments | `run` |

---

## 2. 三条前置原则（用户明确要求）

### 2.1 AetherCode 与 MiniMax Code 无任何关系

⚠️ **纠正此前的误解**：AetherCode 开发过程中用到了 MiniMax Code，但**这只是开发工具的关系，不是产品血缘**。AetherCode **不得读取 MiniMax Code 的任何配置**。

具体禁止项：
- ❌ 不读 `~/.minimax/` 下任何配置
- ❌ 不读 `<workdir>/.minimax/` 下任何内容
- ❌ 不复用 mcode / ACP 协议族

即使某个协议族恰好用 `.minimax/skills` 路径（如 mcode），**也不因此产生关联**。

### 2.2 必须用主流协议生态

能用 **Claude Code** 或 **OpenCode** 的，不用其他的。排除：qwen / mcode / ACP 系（hermes/kimi/kiro/dim）/ dsh / codearts 等。

### 2.3 其余事项按顺序推进

不关键的不阻塞主线。

---

## 3. 为什么选 claude 而不是 opencode

两者的必做行数接近（claude ~330 / opencode ~260），但有**三条结构性差异**，权重远高于 70 行代码：

### 3.1 claude 有显式终态，opencode 要靠启发式猜

| | 终态判定 |
|---|---|
| **claude** | ✅ `type:"result"` 显式终态帧（`claude.go:279-294`） |
| opencode | ❌ 无终态事件，靠三重启发式：`step bracket 闭合` + `step_finish.part.reason` + "void step"（无 text / 无 tool / 无 usage）。任一不满足 → fail closed |

opencode 那套启发式占 **~140 行**（`opencode.go:432-467` + `:553-569` + `stepReportedUsage` `:596-608`）。

> **这 140 行每一条都是历史 bug 的化石。** AetherCode 任何边界情况（比如"只调工具不产文本"的合法任务）都可能踩进 false-green 或误 fail。

**对本项目的特殊意义**：AetherCode 的 R735→R746 那一串修复全在保证**每次终态都有 `run_end` 事件**（`StreamConsumerGuard` + `StreamEvent` 契约）。这套修复正好是 claude 协议族 `type:"result"` 帧的前提——引擎保证有终态，emitter 就能直接映射，不用猜。

### 3.2 claude 无版本分叉，opencode 永久锁死 1.x

`opencodeUsesV2Contract`（`opencode_v2.go:58-61`）第一行：
```go
if !cfg.BuiltinRuntime { return false }
```

源码注释直说：
> A custom profile pointed at OpenCode 2.x therefore stays broken until someone can map profiles onto capabilities directly

→ **Custom Runtime Profile 声明 opencode 后，2.x 根本不可达**。这不是"能选 1.x"，是"2.x 无法访问"。AetherCode 报什么版本号都没用。

### 3.3 MCP 走 flag 而非 env

| | MCP 通道 | 2.x 行为 |
|---|---|---|
| **claude** | `--mcp-config <0600 临时文件>`（`claude.go:59-70`） | — |
| opencode | 1.x：`OPENCODE_CONFIG_CONTENT` 环境变量 | 直接抛 `ErrOpenCodeV2MCPUnsupported` |

opencode 2.x 拒绝的理由（`opencode_v2.go:104-119`）值得单独记：env 不可靠，唯一可用通道是 `<workdir>/opencode.json`，但 MCP 条目带 bearer header / OAuth secret，而 **agent 一次 `git add -A` 就把凭据提交进 git 历史，文件权限无用——提交进程是同一个用户**。

claude 的临时文件方案不进工作树，没这个问题。

---

## 4. 候选对比总表

行数参照现有 backend：`claude.go` 1514 行 / `cursor.go` 1002 行 / `opencode.go` 768 行 / `copilot.go` 636 行 / `codex.go` 4055 行。

| 维度 | **claude** | opencode | cursor-agent | copilot | codex |
|---|---|---|---|---|---|
| 必做行数 | **~330** | ~260 | ~300 | ~200 | ~800+ |
| 终态判定 | ✅ `result` 帧 | ❌ 三重启发式 | ✅ `result` 帧 | ⚠️ 单 JSON 信封 | ✅ `turn/completed` |
| 双向 stdin 成本 | 0 | 0 | 0 | 0 | 必须长驻 JSON-RPC |
| prompt 通道 | stdin JSONL | stdin 纯文本 + 关 | stdin | stdin 纯文本 | RPC `turn/start` |
| 版本分叉 | ❌ 无 | ❌ **锁死 1.x** | ❌ 无 | ❌ 无 | ❌ 无 |
| MCP 通道 | ✅ flag | ⚠️ env / 2.x 拒 | 无 | 无 | 复杂（TOML） |
| 事件流粒度 | 高 | 高 | 高 | **无** | 高 |
| skills 目录 | `<workdir>/.claude/skills/` | `.opencode/skills/` | `.cursor/skills/` | `.github/skills/` | `$CODEX_HOME/skills/` |
| brief 文件 | **`CLAUDE.md`** | `AGENTS.md` | `AGENTS.md` | `AGENTS.md` | — |

### 排除理由

- **codex** —— app-server JSON-RPC over stdio，三套独立超时（`agent.go:61-75`），成本 claude 的 2.5 倍
- **copilot** —— 协议最省事，但**可观测性最差**（单行 JSON 信封不分 step），AetherCode 现有的 `StreamEvent` 细粒度进度全用不上
- **opencode** —— 见 §3

### 次选：cursor-agent

与 claude 几乎同构（`cursor.go:17-20` 注释："similar to Claude Code's stream-json"），有 `result` 帧，唯一额外成本是必传的 `--workspace <cwd>`。

**它的唯一优势**：brief 走 `AGENTS.md` 而非 `CLAUDE.md`，跟 AetherCode 现有项目约定一致。

如果实现 `claude` 时发现 `CLAUDE.md` 命名造成困扰，可以换 cursor-agent。

---

## 5. 一个意外收获：双向 stdin 成本是 0

此前我以为 claude 的 `control_request`/`control_response` 必须实现。**错了**。

`--permission-mode bypassPermissions` 是**被强制注入的**（`claudeBlockedArgs` 挡住用户 `custom_args` 和 `fixed_args` 覆盖，`claude.go:1063-1076` + `:1084`）。所以 CLI 不会为普通工具调用发 `control_request`。

→ **AetherCode 侧什么都不用做。**

唯一注意：**保持 stdin 打开直到进程结束**（不要提前关）。Multica 侧靠"不关 stdin"支撑 control 帧；AetherCode 若提前关，将来想发 `control_request` 就晚了。成本为零。

---

## 6. 配置隔离的具体边界

选了 claude 协议族后，Multica 会往运行目录写两样东西：

| Multica 写的 | AetherCode 应该 | 不应该 |
|---|---|---|
| `<workdir>/CLAUDE.md`（runtime brief） | ✅ **只读这一个文件**当任务上下文 | — |
| `<workdir>/.claude/skills/` | ✅ 只读 skills | ❌ 不读 `.claude/settings.json`、不读 `.claude/` 下任何其他配置 |

Multica 的 MCP 配置走 `--mcp-config <临时文件>`，**不进工作树**。AetherCode 读这个临时文件是合理的（那是 Multica 显式传给本次 run 的），但不应把它持久化到 AetherCode 自己的配置体系。

---

## 7. 分期（按用户要求顺序推进）

### Phase 0：`run` 子命令 + stream-json emitter（~330 行）

AetherCode 侧：
1. stream-json 事件输出层（~120 行）
2. stdin JSONL 读帧（~50 行）
3. 终态 `result` 帧（~60 行）
4. argv 兼容层（~60 行）
5. `--mcp-config` 解析（~40 行）

本地手工验证，不碰 Multica。

### Phase 1：接 Multica

- 装 Multica CLI/Desktop
- **装至少一个内置 CLI**（`claude` 或 `codex`）让 daemon 启动（见下方约束）
- Add custom runtime：family=`claude`，command=`aethercode`，fixed args=`run`
- 创建 agent 选这个 runtime，派真实 issue

### Phase 2：可选增强

- `system` 帧（提前暴露 session id）
- `usage` + `modelUsage` 双写（否则 token 计费为 0）
- `--resume` 会话续接

### Phase 3：comment 回报 + 安全

- 摸清 Public API 后读 `MULTICA_TOKEN` 写 issue comment
- shell policy 防 token 泄露到 bash 子进程

---

## 8. 官方约束备忘

### 8.1 daemon 启动前置条件

> The daemon **must detect at least one built-in supported CLI before it will start**. Custom runtime profiles sync after the daemon is up, so they **cannot be the sole startup condition** on a blank machine.

→ 本机需先装至少一个内置 CLI（`claude` / `codex` 任选）让 daemon 起来。装了 `claude` 正好一举两得。

### 8.2 protocol-critical flag 会被强制注入

`-p` / `--output-format` / `--input-format` / `--permission-mode` / `--mcp-config` / `--effort` —— 写在 `fixed_args` 或 `custom_args` 里都会被丢弃，因为覆盖会破坏 daemon 与工具的连接。

→ AetherCode 的 `run` 必须**接受**这些参数。

### 8.3 版本门

`MinVersions["claude"] = "2.0.0"`（`version.go:20`），daemon 检测 `CLIVersion`，解析不出 semver 直接报错。

→ AetherCode 的 `--version` 输出必须能被解析成 semver。

### 8.4 command 字段限制

> The command field takes an executable and arguments, not a shell script. pipes, redirects, `&&`, `;`, backticks, and environment variable expansion **do not**.

→ 需要复杂逻辑才写 wrapper 脚本。

---

## 9. 未实测项（Phase 1 验证时看）

| 项 | 为什么重要 |
|---|---|
| `control_request` 在 `bypassPermissions` 下是否 100% 不触发 | 若会触发，AetherCode 需实现回写（~35 行） |
| `terminal_reason` 完整取值枚举 | 源码只识别 `prompt_too_long` |
| resume 时 usage 的 baseline 机制 | AetherCode 没有 `~/.claude` 目录，需确认不会导致 usage 回退 |
| `--settings <path>` 的 JSON 能否被安全忽略 | 不报错即可 |
| Multica 注入的 argv 精确形态 | 实跑一次 `daemon logs` 确认 |