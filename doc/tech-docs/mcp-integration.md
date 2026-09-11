# MCP Integration

> Model Context Protocol (MCP) 集成,把外部 MCP server 暴露的工具桥接到 AetherCode engine。
>
> **关键代码**: `aethercode-mcp/src/main/java/org/aethercode/mcp/`
>
> **协议版本**: MCP spec 2024-11-05

---

## 1. 整体架构

```
mcp.json (配置文件)
  ↓
McpManager.reload(path)              ← R132 引入, diff-based hot reload
  │
  ├─ 1. parse JSON
  │
  ├─ 2. diff old vs new (按 server name)
  │     ├─ added    → spawn 新 client + loadTools
  │     ├─ removed  → close 旧 client
  │     ├─ changed  → close + spawn + loadTools
  │     └─ unchanged→ keep live client (零成本)
  │
  └─ 3. return ReloadResult { added, removed, changed, unchanged, tools, errors }
        ↓
Engine 原子 swap tool pool
  ↓
模型看到的新工具集
```

**关键点**: 不是 one-shot load,而是**长期持有 client handle**,reload 时按 diff 局部更新,未变 server 零中断。

---

## 2. ⭐ 4 种 Transport

MCP spec 定义 4 种 transport,AetherCode 全实现:

| Transport | 客户端 | 适用场景 | 文件 |
|---|---|---|---|
| **stdio** | `StdioMcpClient` | 本地进程 (e.g. `npx -y @modelcontextprotocol/server-filesystem`) | `stdio/StdioMcpClient.java` |
| **socket** | `SocketMcpClient` | TCP 跨进程 | `socket/SocketMcpClient.java` |
| **SSE** | `SseMcpClient` | HTTP 长连接, server push | `sse/SseMcpClient.java` |
| **WebSocket** | `WebSocketMcpClient` | 全双工 | `websocket/WebSocketMcpClient.java` |

**`McpClientHandle` tag interface** — 统一 4 个 client,提供 `close()`:

```java
public interface McpClientHandle {
    void close() throws Exception;
}
```

每个 client 通过简单 adapter `(Runnable) close::run` 包成 `McpClientHandle`,manager 统一调用。

---

## 3. ⭐ McpManager 关键设计 (R132)

### 3.1 解决 3 个老问题

| 老问题 | 修复 |
|---|---|
| One-shot load,reload 要 restart daemon | Stateful, 长期持有 client handle |
| 改一个 server 把所有重启 | Diff-based, 只改变动的 |
| 坏 server 影响其他 | Per-server try-catch, 失败隔离 |

### 3.2 Diff 算法 (按 name)

```
oldMap = {server1, server2, server3}     ← 现有 live servers
newMap = {server1, server3, server4}     ← mcp.json 新内容

added     = {server4}     ← 在 newMap 不在 oldMap
removed   = {server2}     ← 在 oldMap 不在 newMap
changed   = {}            ← 都在但 config 不同 (后续按 hash 比)
unchanged = {server1, server3}
```

- `unchanged` 零操作 (live client 保留)
- `removed` → `client.close()`
- `added/changed` → spawn 新 client + `loadTools()`
- 失败 → catch + log + 加到 `errors[]`,继续下一个

### 3.3 Atomic Tool Swap

返回 `ReloadResult.tools` 是**新完整 tool list**,engine 一次性替换 tool pool。
- 模型永远不会看到 half-loaded 状态
- 替换是 `synchronized` 块,无中间态

### 3.4 Threading: ReentrantLock + reader snapshot

```java
private final ReentrantLock lock = new ReentrantLock();

public List<Tool> currentTools() {
    // reader 拿 snapshot, 不阻塞 reload
    lock.lock();
    try {
        return toolsByName.values().stream().flatMap(List::stream).toList();
    } finally {
        lock.unlock();
    }
}

public ReloadResult reload(Path config) {
    lock.lock();
    try {
        // writer 串行化
    } finally {
        lock.unlock();
    }
}
```

`ReentrantLock` (不是 `synchronized`) 原因:reader snapshot 要快速返回,不能被 reload 长 IO 阻塞。

---

## 4. ⭐ mcp.json 格式

```json
{
  "mcpServers": {
    "filesystem": {
      "type": "stdio",
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/path/to/dir"],
      "env": { "DEBUG": "1" }
    },
    "github": {
      "type": "stdio",
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-github"],
      "env": { "GITHUB_TOKEN": "${env:GITHUB_TOKEN}" }
    },
    "remote-bridge": {
      "type": "websocket",
      "url": "ws://localhost:8765/mcp"
    },
    "sse-push": {
      "type": "sse",
      "url": "https://mcp.example.com/sse",
      "auth": "bearer"
    },
    "socket-local": {
      "type": "socket",
      "host": "127.0.0.1",
      "port": 9876
    }
  }
}
```

| 字段 | 含义 |
|---|---|
| `type` | `stdio` / `socket` / `sse` / `websocket` |
| `command` + `args` | stdio 模式启动命令 |
| `url` | sse / websocket URL |
| `host` + `port` | socket 模式 |
| `env` | 环境变量 (`${env:XXX}` 引用系统 env) |
| `auth` | 鉴权模式 (`bearer` / `oauth2` / `apiKey`) |

---

## 5. ⭐ McpAuthOrchestrator — OAuth 流

`McpAuthOrchestrator` 处理 OAuth 2.0 授权码 + PKCE 流程 (用于远程 MCP server):

```java
public final class McpAuthOrchestrator {
    private final Path mcpConfig;
    private final Path tokenStore;
    private final int defaultPort;
    private final AuthRateLimiter rateLimiter;     // 第二层防雪崩

    public boolean tryAcquireAuthSlot(String serverId) { ... }
    public Map<String, Object> loadAuthConfig(String serverId) { ... }
    public McpOAuthFlow.Token run(...) { ... }      // OAuth dance
    public void persistToken(String serverId, Token token) { ... }
}
```

**3 步 OAuth 流程**:
1. 启动本地 `OAuthCallbackServer` 监听 `defaultPort` 接收 callback
2. 用 `McpOAuthFlow` + PKCE 跟远程 server 跳 authorize → token
3. 持久化 token 到 `tokenStore` (`~/.aethercode/mcp-tokens.json`)

**AuthRateLimiter** — 同 server ID 短时间内禁止重试 (防 stampede)。

---

## 6. ⭐ McpHealthCheck + McpHealthDashboard

### 6.1 McpHealthCheck

定期 ping 每个 server (liveness probe):
- 状态: `HEALTHY` / `DEGRADED` / `UNREACHABLE`
- 延迟 (ms)
- 错误信息 (最近一次失败原因)

### 6.2 McpHealthDashboard

TUI 面板,显示所有 server 状态:
- 绿色: HEALTHY
- 黄色: DEGRADED (间歇失败)
- 红色: UNREACHABLE
- 灰色: 未启用

---

## 7. ⭐ McpPrompts — 暴露 server 提供的 prompt 模板

MCP server 不仅能 expose tools,还能 expose **prompt templates** (预填充的 prompt)。`McpPrompts` 把这些注册到 AetherCode 的 prompt 系统中,用户在 UI 选 prompt 模板 → 注入上下文。

---

## 8. JsonRpc — MCP 协议 envelope

跟 A2A 一样的 JSON-RPC 2.0 风格,但用 MCP 自己的 method 名:
- `initialize` — 握手
- `tools/list` — 列工具
- `tools/call` — 调工具
- `prompts/list` — 列 prompt 模板
- `prompts/get` — 取 prompt
- `resources/list` — 列资源 (R250+ 新增)
- `resources/read` — 读资源 (R250+ 新增)

`JsonRpc.java` 集中处理 envelope 编解码,跟 A2A 共享 pattern 但 method namespace 不同。

---

## 9. RPC 接口 (MCP 侧)

```java
// aethercode 提供给 engine / RPC 客户端
public interface McpManagement {
    ReloadResult reloadRegistries(Path config);
    List<LiveServer> list();
    Map<String, HealthStatus> health();
    boolean auth(String serverId);   // 触发 OAuth
}
```

**`reloadRegistries` RPC** payload:
```json
{
  "added": 1,
  "removed": 0,
  "changed": 2,
  "unchanged": 3,
  "mcpReloaded": true,
  "errors": []
}
```

---

## 10. 关键类索引 (按代码量)

| 类 | 文件 | 字节 | 作用 | round |
|---|---|---|---|---|
| `McpManager` | `McpManager.java` | 17,735 | Stateful + diff-based reload | R132 |
| `SseMcpClient` | `sse/SseMcpClient.java` | 10,473 | HTTP long-poll transport | R132 |
| `McpPrompts` | `McpPrompts.java` | 9,150 | Prompt 模板暴露 | R132 |
| `WebSocketMcpClient` | `websocket/WebSocketMcpClient.java` | 8,249 | WS full-duplex transport | R132 |
| `McpAuthOrchestrator` | `McpAuthOrchestrator.java` | 8,178 | OAuth + token 持久化 | R132 |
| `StdioMcpClient` | `stdio/StdioMcpClient.java` | 7,721 | 本地进程 transport | R132 |
| `SocketMcpClient` | `socket/SocketMcpClient.java` | 7,473 | TCP transport | R132 |
| `McpHealthCheck` | `McpHealthCheck.java` | 7,378 | Liveness probe | R132 |
| `McpOAuthFlow` | `McpOAuthFlow.java` | 7,037 | OAuth + PKCE 核心 | R132 |
| `JsonRpc` | `JsonRpc.java` | 5,281 | JSON-RPC envelope | R132 |
| `McpHealthDashboard` | `McpHealthDashboard.java` | 5,119 | TUI 面板 | R132 |
| `McpServers` | `McpServers.java` | 4,865 | 老 one-shot load (兼容) | <R132 |

**辅助 (auth/)**:
- `OAuthCallbackServer.java` — 本地 HTTP server 接 OAuth callback
- `AuthRateLimiter.java` — stampede defense

---

## 11. 配置示例

```yaml
# aethercode.yaml
mcp:
  enabled: true
  config_path: ~/.aethercode/mcp.json
  reload_on_change: true            # file watcher
  reload_debounce_ms: 500
  auth:
    default_port: 0                 # 0 = pick free port
    cooldown_seconds: 30            # 同 server 重试冷却
    token_store: ~/.aethercode/mcp-tokens.json
  health:
    check_interval_seconds: 60
    unhealthy_threshold: 3          # 连续 3 次 fail → UNREACHABLE
  transport:
    stdio_timeout_ms: 30000
    sse_reconnect_backoff_ms: 1000
    websocket_ping_interval_seconds: 30
```

---

## 12. 关键测试

```
McpManagerTest.java                (diff 算法 + atomic swap)
McpManagerReloadTest.java          (reload 场景)
SseMcpClientTest.java              (SSE transport)
StdioMcpClientTest.java            (stdio transport)
WebSocketMcpClientTest.java        (WS transport)
SocketMcpClientTest.java           (socket transport)
McpAuthOrchestratorTest.java       (OAuth flow)
McpOAuthFlowTest.java              (PKCE)
McpHealthCheckTest.java            (liveness)
JsonRpcTest.java                   (envelope)
```

---

## 13. 已知 trade-off

| 决策 | 优点 | 缺点 |
|---|---|---|
| Diff-based reload | 改动局部, 不重启 daemon | 需要 file watcher |
| ReentrantLock (vs synchronized) | reader 快速 snapshot | API 复杂一点 |
| Per-server try-catch | 失败隔离 | 一致性问题 (部分新部分旧) |
| 4 transport 各自独立 | 各优化 | 维护 4 套 client |
| OAuth 用 PKCE | 安全 | 流程复杂 |
| AuthRateLimiter 30s cooldown | 防 stampede | 用户 retry 要等 |
| TokenStore 明文 JSON | 简单 | 加密更好 (未来 TODO) |
| Health check 60s 间隔 | 低开销 | 失败检测延迟 |

---

## 14. 关键 round 引用

- **R132**: 引入 McpManager (替换老的 one-shot McpServers) + 4 transport + OAuth + HealthCheck + Dashboard
- **R250+**: Resources 协议扩展 (resources/list + resources/read)
- 详细过程见 `../round-notes/R132-MCP-MIGRATION.md` (如果存在) 或代码注释
