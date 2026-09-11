# API Reference

> 32+ JSON-RPC 2.0 methods 完整列表,按 namespace 分组。
>
> **关键模块**: `aethercode-protocol/`
>
> **英文详细参考**: `../API.md` (顶层)

---

## 1. ⭐ 协议格式 (JSON-RPC 2.0)

所有方法遵循 JSON-RPC 2.0 spec,带 `id` 同步调用,无 `id` 是 notification(单向 fire-and-forget)。

```json
// Request
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "session_create",
  "params": {"name": "my-session", "cwd": "/path/to/proj"}
}

// Response (success)
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {"sessionId": "s-abc123", "createdAt": 1736000000000}
}

// Response (error)
{
  "jsonrpc": "2.0",
  "id": 1,
  "error": {"code": -32600, "message": "Invalid request"}
}

// Notification (no id, no response)
{
  "jsonrpc": "2.0",
  "method": "stream_event",
  "params": {"type": "text_delta", "text": "Hello"}
}
```

**Transport 4 种**:
- **stdio + NDJSON** — CLI / IDE / TUI 一行一 JSON
- **HTTP POST** — Desktop + 外部 client
- **WebSocket** — Desktop 推流
- **HTTPS + Bearer** — BankClient (跨 device)

---

## 2. ⭐ 错误码 (JSON-RPC 2.0 + 扩展)

| Code | 名称 | 含义 |
|---|---|---|
| `-32700` | Parse error | JSON 语法错 |
| `-32600` | Invalid Request | 无 method / id |
| `-32601` | Method not found | method 不存在 |
| `-32602` | Invalid params | 参数类型错 / 缺必填 |
| `-32603` | Internal error | 引擎内部异常 |
| `-32000` | Server error (应用层) | 通用应用错误 |
| `-32001` | Task not found | A2A / supervisor task 找不到 |
| `-32002` | Task not cancelable | A2A task 不可取消 |
| `-32003` | Streaming not supported | A2A server 没装 streaming handler |
| `-32004` | Auth required | Bank 鉴权失败 |
| `-32005` | Workflow not found | workflow name 找不到 |
| `-32006` | Validation error | workflow 验证错 |
| `-32007` | Permission denied | 用户拒绝 |
| `-32008` | Rate limited | 限流 (MCP / bank) |
| `-32009` | Resource not found | memory / file 找不到 |
| `-32010` | Conflict | session / file 冲突 |

---

## 3. ⭐ Method 分类 (32+ methods)

### 3.1 Session 管理 (6)

| Method | 作用 | 关键参数 |
|---|---|---|
| `session_create` | 创建 session | `name`, `cwd`, `model` |
| `session_list` | 列 sessions | `archived` (bool) |
| `session_get` | 按 id 获取 | `sessionId` |
| `session_delete` | 删除 | `sessionId` |
| `session_switch` | 切换当前 | `sessionId` |
| `session_resume` | 恢复已关闭 | `sessionId` |

### 3.2 Query (3)

| Method | 作用 | 关键参数 |
|---|---|---|
| `query_send` | 同步 query | `sessionId`, `input` (text + attachments) |
| `query_stream` | 流式 (Server-Sent Events) | 同上 |
| `query_cancel` | 取消进行中 | `sessionId`, `queryId` |

### 3.3 Memory (6)

| Method | 作用 | 关键参数 |
|---|---|---|
| `memory_read` | 读 entry | `scope` (USER/PROJECT/SESSION), `key` |
| `memory_write` | 写 | `scope`, `key`, `content`, `tags` |
| `memory_list` | 列表 (按 scope 过滤) | `scope`, `limit` |
| `memory_search` | 关键词搜索 | `query`, `scope` |
| `memory_delete` | 删除 | `scope`, `key` |
| `memory_promote` | session → project/user 提升 | `key`, `fromScope`, `toScope` |

### 3.4 Compact (2)

| Method | 作用 | 关键参数 |
|---|---|---|
| `compact_run` | 同步触发压缩 | `force` (bool), `strategy` (structured/sliding-window/structured-v8) |
| `compact_status` | 查询状态 | (无) |

返回 `CompactRunResult`: `before_tokens`, `after_tokens`, `saved_pct`, `strategy`, `duration_ms`。

### 3.5 Bank (5)

| Method | 作用 | 关键参数 |
|---|---|---|
| `bank_list` | 列 bank entries | `kind`, `limit` |
| `bank_recall` | 按 id 读单条 | `id` |
| `bank_upsert` | 写或更新 | `entry` (id, kind, content, tags, ...) |
| `bank_touch` | 更新 lastAccess (防衰减) | `id` |
| `bank_outcome` | 记录 self-eval | `id`, `outcome` (success/confidence/drift) |

### 3.6 Workflow (3)

| Method | 作用 | 关键参数 |
|---|---|---|
| `workflow_run` | 跑 workflow (异步) | `name`, `inputs` (Map) |
| `workflow_status` | 查询状态 | `sessionRef` |
| `workflow_list` | 列 available workflows | (无) |

### 3.7 Tool (3)

| Method | 作用 | 关键参数 |
|---|---|---|
| `tool_list` | 列已注册 tools | (无) |
| `tool_info` | 查 tool schema | `toolName` |
| `tool_call` | 直接调 tool (绕过 LLM) | `toolName`, `input` |

### 3.8 Skill (3)

| Method | 作用 | 关键参数 |
|---|---|---|
| `skill_list` | 列已加载 skills | (无) |
| `skill_load` | 按需加载 | `skillName` |
| `skill_unload` | 卸载 | `skillName` |

### 3.9 A2A (5)

| Method | 作用 |
|---|---|
| `a2a_send` | 同步 `message/send` |
| `a2a_stream` | 流式 `message/sendSubscribe` (SSE) |
| `a2a_get` | `tasks/get` |
| `a2a_cancel` | `tasks/cancel` |
| `a2a_card` | `agent/authenticatedExtendedCard` |

### 3.10 Permission (4)

| Method | 作用 |
|---|---|
| `permission_set_mode` | 切换 mode (BYPASS/AUTO_READ_ONLY/ACCEPT_EDITS/...) |
| `permission_skip_next` | skip 接下来 N 次弹窗 |
| `permission_set_rules` | 改规则 (deny/ask/allow) |
| `permission_query` | 问"这个 tool 调能不能过" |

### 3.11 Task (3)

| Method | 作用 |
|---|---|
| `task_start` | 启动 async sub-task |
| `task_status` | 查询状态 (running/completed/failed) |
| `task_cancel` | 取消 |

### 3.12 MCP (3)

| Method | 作用 |
|---|---|
| `mcp_reload` | 重载 mcp.json (diff-based) |
| `mcp_list` | 列 active servers + health |
| `mcp_auth` | 触发 OAuth 流程 |

### 3.13 Context (2)

| Method | 作用 |
|---|---|
| `context_attach` | 附加文件 / 目录到 context |
| `context_detach` | 解除附加 |

### 3.14 Grant (2)

| Method | 作用 |
|---|---|
| `grant_list` | 列用户显式 allow/deny 记录 |
| `grant_revoke` | 撤销 grant |

### 3.15 Theme (1)

| Method | 作用 |
|---|---|
| `theme_set` | 切换主题 (dark/light/auto) |

### 3.16 Stats (2)

| Method | 作用 |
|---|---|
| `stats_get` | 当前 session stats (token/cost/perf) |
| `stats_history` | 历史 stats (per query) |

### 3.17 Config (3)

| Method | 作用 |
|---|---|
| `config_get` | 读 config |
| `config_set` | 改 config |
| `config_reset` | 重置 default |

---

## 4. ⭐ Stream events (Notifications)

不是 request/response,server 单向推流到 client:

| Event | Payload | 触发时机 |
|---|---|---|
| `text_delta` | `{text}` | 每个 streaming token |
| `tool_call` | `{toolName, input}` | 模型决定调 tool |
| `tool_result` | `{toolName, output, isError}` | tool 跑完 |
| `permission_prompt` | `{toolName, input, reason}` | 弹窗 |
| `permission_resolved` | `{toolName, decision}` | 用户决定 |
| `run_end` | `{stopReason, usage}` | LLM 一轮结束 |
| `error` | `{code, message, recoverable}` | 错误 |
| `workflow_done` | `{name, status, outputPath}` | workflow 完成 |
| `workflow_error` | `{name, error}` | workflow 失败 |
| `registry_reloaded` | `{added, removed, changed, unchanged}` | MCP 重新加载 |
| `cwd_changed` | `{oldCwd, newCwd}` | cwd 改变 |
| `compaction_done` | `{beforeTokens, afterTokens, savedPct}` | 压缩完成 |
| `todo_update` | `{todos[]}` | TODO 更新 |
| `agent_card_update` | `{card}` | A2A card 变化 |

---

## 5. ⭐ Schema 版本

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "version",
  "params": {}
}
```

返回:
```json
{
  "protocol": "2.0",
  "aethercode": "0.2.65",
  "api": 1,
  "features": ["streaming", "sse", "tls", "bank-sync", "a2a-v0.3", "workflow-v1"]
}
```

**Negotiation**: client 可在 `session_create` 传 `minProtocolVersion` / `maxProtocolVersion` 协商。

---

## 6. ⭐ 客户端示例

### 6.1 TypeScript (TUI)

```typescript
import { JsonRpcClient } from "aethercode-sdk";

const client = new JsonRpcClient({ transport: "stdio" });

// Request
const session = await client.call("session_create", { name: "demo", cwd: process.cwd() });

// Stream
const stream = client.stream("query_stream", { sessionId: session.id, input: "fix the test" });
for await (const event of stream) {
    if (event.method === "text_delta") process.stdout.write(event.params.text);
}
```

### 6.2 Java (aethercode-sdk)

```java
JsonRpcClient client = JsonRpcClient.stdio();

// Sync
Map<String, Object> result = client.call("session_create",
    Map.of("name", "demo", "cwd", cwd));

// Async stream
client.stream("query_stream", Map.of("sessionId", sid, "input", "fix"))
    .forEach(event -> {
        if ("text_delta".equals(event.method())) {
            System.out.print(event.params().get("text"));
        }
    });
```

### 6.3 HTTP (Desktop / 外部)

```bash
# Sync
curl -X POST http://localhost:7823/a2a \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"session_list","params":{}}'

# Stream
curl -N -X POST http://localhost:7823/a2a/stream \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"query_stream","params":{"sessionId":"s-abc","input":"hi"}}'
```

---

## 7. ⭐ 限流策略

| Endpoint | 限制 |
|---|---|
| `query_*` | 5 concurrent per session |
| `tool_call` | 100 / min per session |
| `memory_write` | 60 / min per scope |
| `bank_*` | 30 / min per client (Burst 5) |
| `mcp_auth` | 1 / 30s per server (AuthRateLimiter) |
| `permission_skip_next` | max 100 per request |
| WebSocket stream | 1 concurrent per connection |

---

## 8. ⭐ 关键类索引

| 类 | 文件 | 字节 | 作用 |
|---|---|---|---|
| `AetherCodeMethods` | `AetherCodeMethods.java` | 12 KB+ | 主入口 (20+ methods) |
| `MemoryMethods` | `MemoryMethods.java` | ~3 KB | 6 memory methods |
| `PermissionMethods` | `PermissionMethods.java` | ~2 KB | 4 permission methods |
| `TaskMethods` | `TaskMethods.java` | ~2 KB | 3 task methods |
| `CompactMethods` | `CompactMethods.java` | ~1.5 KB | 2 compact methods |
| `ContextMethods` | `ContextMethods.java` | ~1 KB | 2 context methods |
| `GrantMethods` | `GrantMethods.java` | ~1 KB | 2 grant methods |
| `ThemeMethods` | `ThemeMethods.java` | ~0.5 KB | 1 theme method |
| `EngineContinuationDispatcher` | `EngineContinuationDispatcher.java` | ~3 KB | engine dispatch |

---

## 9. 详细参考

- `../API.md` (顶层英文)
- `aethercode-protocol/src/main/java/org/aethercode/protocol/methods/`
- `aethercode-protocol/src/main/java/org/aethercode/protocol/jsonrpc/`
