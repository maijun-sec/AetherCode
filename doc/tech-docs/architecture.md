# Architecture

> AetherCode 整体架构: 16 个 Maven 模块 + TS/Rust 子项目 + JSON-RPC 协议
>
> **关键文档**:
> - `../ARCHITECTURE.md` (顶层, 旧版英文, 简版)
> - `../round-notes/R250+-功能说明.md` (0.2.65 版本)
> - `../README.md` (根目录, quick start)

---

## 1. ⭐ 三层架构

```
┌─────────────────────────────────────────────────────────────────┐
│                    Presentation Layer (3 surfaces)              │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐             │
│  │ aethercode-  │ │ aethercode-  │ │ idea-plugin/ │             │
│  │ desktop      │ │ tui          │ │ (Kotlin)     │             │
│  │ (Tauri+React)│ │ (Bun+Ink)    │ │              │             │
│  └──────┬───────┘ └──────┬───────┘ └──────┬───────┘             │
│         │                │                │                      │
│         └────────────────┼────────────────┘                      │
│                          │ JSON-RPC 2.0 (stdio / HTTP / WS)     │
│         ┌────────────────▼─────────────────┐                    │
│         │       aethercode-sdk             │  ← SDK 入口 + 传输  │
│         │       aethercode-protocol        │  ← 32+ JSON-RPC    │
│         └────────────────┬─────────────────┘                    │
│                          │                                      │
│         ┌────────────────▼─────────────────┐                    │
│         │  aethercode (Java, 16 modules)   │  ← 核心            │
│         │  -core / -compact / -permission  │                    │
│         │  -memory / -tasks / -skills      │                    │
│         │  -mcp / -workflows / -tools      │                    │
│         │  -a2a / -deepagents / -talon     │                    │
│         │  -engine-springai / -bridge      │                    │
│         └────────────────┬─────────────────┘                    │
│                          │                                      │
│         ┌────────────────▼─────────────────┐                    │
│         │  Infrastructure / Foundation     │                    │
│         │  -config / -prompts / -protocol  │                    │
│         │  LLM SDK / Tool SDK              │                    │
│         │  Storage (SQLite, JSONL, MD)     │                    │
│         └──────────────────────────────────┘                    │
└─────────────────────────────────────────────────────────────────┘
```

**3 层职责**:
- **Presentation**: 3 个 surface (Desktop / TUI / IDE) + CLI
- **Java Core**: 16 个 Maven 模块, 全部核心能力
- **Infrastructure**: 配置 / 协议 / 存储 / LLM SDK

---

## 2. ⭐ 16 个 Maven 模块

按职责分组:

### 2.1 核心引擎

| 模块 | 字节 (target/) | 关键类 | 作用 |
|---|---|---|---|
| `aethercode-core` | ~50 MB | `QueryEngine`, `MemoryLifecycle`, `Message` | engine + agent loop + message + memory lifecycle |
| `aethercode-compact` | ~3 MB | `StructuredCompactor8` | 8 段式 context 压缩 |
| `aethercode-tasks` | ~5 MB | `TaskSupervisor`, `TaskStateMachine` | 长期任务 + supervisor |
| `aethercode-deepagents` | ~30 MB | `DeepAgentRuntime` | DeepAgent runtime |
| `aethercode-talon` | ~5 MB | `ChannelRegistry`, `ReactionChannelAdapter` | channel 适配器 |

### 2.2 能力

| 模块 | 字节 | 关键类 | 作用 |
|---|---|---|---|
| `aethercode-memory` | ~8 MB | `MemoryLifecycle`, `BankClient` | 7 类分层 memory + bank |
| `aethercode-permission` | ~2 MB | `ProjectPermissionPolicy`, `MatrixPermissionPolicy` | 3-tier 权限 + matrix |
| `aethercode-skills` | ~1 MB | `SkillRegistry`, `SkillMarketplace` | skill loader + 5 个 builtin |
| `aethercode-mcp` | ~2 MB | `McpManager`, 4 transport | MCP client/server |
| `aethercode-workflows` | ~1 MB | `WorkflowEngine`, 6 YAML | YAML workflow + 6 shipped |
| `aethercode-tools` | ~15 MB | `BashTool`, `FileReadTool`, VLM tools | built-in tools (bash, file, VLM) |

### 2.3 协议

| 模块 | 字节 | 关键类 | 作用 |
|---|---|---|---|
| `aethercode-protocol` | ~20 MB | `AetherCodeMethods`, DTOs | 32+ JSON-RPC methods |
| `aethercode-a2a` | ~3 MB | `A2AServer`, `A2AHttpTransport` | A2A v0.3 spec |
| `aethercode-a2a-deepagent-bridge` | ~1 MB | `A2ADeepAgentBridge` | DeepAgent ↔ A2A |
| `aethercode-claude-code` | ~2 MB | `ClaudeCodeAdapter` | Claude Code 集成 |

### 2.4 基础

| 模块 | 字节 | 关键类 | 作用 |
|---|---|---|---|
| `aethercode-config` | ~1 MB | `AetherCodeConfig`, `PermissionMatrix` | 配置 + matrix |
| `aethercode-sdk` | ~1 MB | `AetherCodeEngine`, transport | SDK 入口 |
| `aethercode-engine-springai` | ~2 MB | `SpringAiEngine` | Spring AI 引擎 (可选) |
| `aethercode-bridge` | ~1 MB | `SweBridge` | SWE bridge |

---

## 3. ⭐ 模块依赖图

```
Presentation
  ├─ aethercode-desktop (Tauri + React)
  │    └─ aethercode-tui-runtime + aethercode-protocol
  ├─ aethercode-tui (Bun + Ink)
  │    └─ aethercode-protocol
  └─ idea-plugin (Kotlin, IntelliJ Platform)
       └─ aethercode-protocol

Java
  ├─ aethercode-sdk
  │    └─ aethercode-protocol + aethercode-core
  ├─ aethercode-protocol  (no deps on other aethercode modules)
  ├─ aethercode-core      (depends on protocol, config, compact)
  │    ├─ aethercode-compact
  │    ├─ aethercode-permission
  │    ├─ aethercode-memory
  │    ├─ aethercode-skills
  │    ├─ aethercode-tasks
  │    ├─ aethercode-mcp
  │    ├─ aethercode-tools
  │    ├─ aethercode-workflows
  │    └─ aethercode-a2a
  └─ aethercode-engine-springai (可选, 跟 core 平级)
```

**关键约束**:
- `aethercode-protocol` 不依赖其他 aethercode 模块 (leaf)
- `aethercode-core` 依赖 8 个能力模块 (hub)
- `aethercode-a2a` 也可独立用 (走 JSON-RPC, 不需要 core)

---

## 4. ⭐ 4 种 Surface 通信协议

| Surface | Transport | 协议 | 用途 |
|---|---|---|---|
| **aethercode-cli / ide-plugin / tui** | stdio | JSON-RPC 2.0 (NDJSON) | 一行一 JSON, 简单 |
| **aethercode-desktop** | HTTP + WebSocket | JSON-RPC 2.0 over HTTP, WS push | 多 surface 跨进程 |
| **aethercode-a2a** | HTTP + SSE | JSON-RPC 2.0 + SSE streaming | 跨 agent |
| **aethercode-bank** | HTTPS | HTTPS + Bearer Token | 跨设备 memory sync |

**所有 surface 走 JSON-RPC 2.0**,`aethercode-protocol` 定义 DTO,各 surface 实现 transport adapter。

---

## 5. ⭐ 数据流 (典型 query)

```
[User 在 Desktop 输 "fix the failing test in Test.java"]
  ↓
[Desktop → daemon: JSON-RPC message/send]
  ↓
[aethercode-sdk 收, 调 aethercode-core]
  ↓
[QueryEngine.onQueryStart]
  ├─ MemoryLifecycle.onQueryStart (Tier 1)
  │    └─ Recall 5 个相关 memory file
  ├─ WorkingMemoryBuffer.create
  ├─ CompactGate.shouldCompact
  │    └─ 超阈值 → StructuredCompactor8.compact
  └─ 构造 initial prompt (system + memory + user)
  ↓
[ChatClient.stream → LLM API]
  ↓ (streaming events)
[Engine 收 tool_call event]
  ├─ ToolHookRegistry.runPre (7 hook)
  ├─ PermissionCheckHook.deny → 必要时弹窗
  ├─ BashTool.call
  ├─ ToolHookRegistry.runPost
  └─ 把 tool result 拼到 messages
  ↓
[LLM 继续, 下一轮 ...]
  ↓
[最终 assistant message → MemoryLifecycle.onQueryEnd]
  ├─ Tier 2: extractCase (heuristic)
  ├─ Tier 3: maybeExtractStrategy (LLM gated)
  └─ Bank touch + decay
  ↓
[Stream events 回到 Desktop]
  ├─ text_delta
  ├─ tool_call
  ├─ tool_result
  └─ run_end
  ↓
[UI 渲染]
```

---

## 6. ⭐ 7 类分层 Memory (跨 surface 共享)

| # | Scope | 物理位置 | 跨 surface | 跨 device |
|---|---|---|---|---|
| 1 | USER (GLOBAL) | `~/.aethercode/agent-memory/<agentType>/*.md` | ✅ | ✅ via bank |
| 2 | PROJECT | `<cwd>/.aethercode/agent-memory/<agentType>/*.md` | ✅ (同 project) | ✅ via bank |
| 3 | SESSION | `~/.aethercode/sessions.db` (SQLite) | ❌ (单 session) | ❌ |
| 4 | EXPERIENCE | `<scope>/agent-memory/<agentType>/experience/` | ✅ | ✅ via bank |
| 5 | WORKING | 进程内 LRU | ❌ (单 query) | ❌ |
| 6 | AUDIT | `<memoryBase>/audit.log` (JSONL) | ❌ (本机) | ❌ |
| 7 | TASK | `<memoryBase>/agent-memory-tasks/<agentType>/<taskId>/` | ❌ (单 sub-agent) | ❌ |

详细见 `memory-system.md`。

---

## 7. ⭐ 3 种 Cross-Surface 通信

| 协议 | 用途 | 客户端 | 服务端 |
|---|---|---|---|
| **JSON-RPC 2.0** | 同步 RPC (32+ methods) | CLI / IDE / TUI / Desktop | aethercode-sdk |
| **A2A (JSON-RPC + SSE)** | 跨 agent 通信 | 任意 (A2A client) | aethercode-a2a |
| **BankClient (HTTP + TLS + Bearer)** | 跨 device memory sync | TS / Rust / Java | BankServer |

**没有循环依赖**: A2A 用自己的 JSON-RPC transport,BankServer 用自己的 HTTPS transport,跟主 RPC 解耦。

---

## 8. ⭐ Build & Release

### 8.1 Build pipeline

```bash
# Java (16 modules, ~30s)
mvn -pl aethercode -am clean package

# TS (desktop + tui)
pnpm install
pnpm --filter aethercode-desktop tauri build

# TUI bundle
bun build aethercode-tui/src/cli.tsx --compile --outfile dist/tui
```

### 8.2 Release artifacts

| 名称 | 字节 | 平台 |
|---|---|---|
| `aethercode-<version>.jar` | ~50 MB | JVM (跨平台) |
| `aethercode-desktop.exe` | 4.16 MB | Windows |
| `aethercode-desktop.app` | ~5 MB | macOS |
| `aethercode-desktop.AppImage` | ~5 MB | Linux |
| `ac-tui-standalone.exe` | ~30 MB | Windows (Bun 单文件) |
| `aethercode-idea-plugin.zip` | ~5 MB | IntelliJ plugin |

### 8.3 Distribution channels

- **Maven Central** (Java SDK)
- **JetBrains Marketplace** (IDE plugin)
- **GitHub Releases** (Desktop / TUI binaries)
- **npm registry** (TypeScript SDK, 计划中)

---

## 9. ⭐ Module 复用模式

### 9.1 3 个 pattern

| Pattern | 模块 | 说明 |
|---|---|---|
| **Leaf** | `aethercode-protocol`, `aethercode-config` | 不依赖其他 aethercode 模块, 跨场景复用 |
| **Hub** | `aethercode-core` | 依赖 8 个能力模块, 入口 |
| **Plugin** | `aethercode-mcp`, `aethercode-a2a` | 独立可用 + 跟 core 集成 |

### 9.2 反向依赖禁止

- `aethercode-permission` 不依赖 `aethercode-tools` (用 `Tool.isReadOnly()` 接口)
- `aethercode-compact` 不依赖 `aethercode-core` (只依赖 `core.llm.ChatClient` 接口)
- `aethercode-a2a` 不依赖 `aethercode-core` (用 `Function<Message, Artifact>` 注入)

---

## 10. 关键 round 引用

- **R07**: IDEA 插件集成基础
- **R130**: 引入 ProjectPermissionPolicy (3-tier)
- **R132**: 引入 McpManager (替换 one-shot)
- **R141**: 引入 ToolHook + Registry
- **R151**: RedactionHook + TruncationHook
- **R230**: Memory 综述驱动优化
- **R233**: MemoryLifecycle 4 阶编排
- **R241.1**: A2A 协议模块
- **R244+**: BankClient 跨 surface
- **R247-249**: Bank 安全 + Desktop Rust client
- **R250D**: A2A HTTP transport
- **R250+1-6**: 7 个 R250+ round 收口
- **R250+7**: Desktop UI 修复
- 详细过程见 `../round-notes/` 相应文档
