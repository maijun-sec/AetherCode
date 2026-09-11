# A2A Protocol

> Agent-to-Agent (A2A) 协议,跨 agent / 跨 surface 通信的 JSON-RPC 2.0 协议 (v0.3 spec)。
>
> **关键代码**: `aethercode-a2a/src/main/java/org/aethercode/a2a/`
>
> **Schema**: `aethercode-a2a/src/main/java/org/aethercode/a2a/schema/`

---

## 1. 4 层架构

```
┌────────────────────────────────────────┐
│  HTTP Layer (A2AHttpTransport)         │  ← POST /a2a, /a2a/stream, /.well-known/agent.json
├────────────────────────────────────────┤
│  Protocol Layer (A2AServer)            │  ← 4 个 JSON-RPC method 路由
├────────────────────────────────────────┤
│  Schema Layer (schema/*.java)          │  ← AgentCard / Task / Message / Artifact / Part
├────────────────────────────────────────┤
│  Wire (JSON-RPC 2.0)                   │  ← 手工编码, 严格 spec
└────────────────────────────────────────┘
        ↓
[Agent Runtime: deep-agent / fake]   ← Function<Message, Artifact>
```

**4 层职责清晰**:
- **HTTP**: JDK 内置 HttpServer,无新依赖 (跟 BankServer 风格一致)
- **Protocol**: 4 个 RPC method + task 状态机
- **Schema**: 6 个 record (AgentCard / Task / TaskStatus / Message / Artifact / Part)
- **Wire**: 手工 JSON-RPC envelope,避免 Jackson 默认渲染 "error":false

---

## 2. ⭐ 4 个 JSON-RPC Methods (v0.3 spec)

| Method | 作用 | 返回 |
|---|---|---|
| `message/send` | 创建/恢复 task,跑 agent,同步返回 `completed` task | `Task` |
| `tasks/get` | 按 id 查 task | `Task` |
| `tasks/cancel` | 把 task 转 `canceled` 状态 | `Task` |
| `agent/authenticatedExtendedCard` | 返回 server 发布的完整 AgentCard | `AgentCard` |

**`message/send` 是核心**: 接收 user `Message`,调 `Function<Message, Artifact>`,把返回打包成单 Artifact (默认 `text/plain` part),task 状态 `completed`。

**`message/sendSubscribe` (streaming)** — 走 `/a2a/stream` (POST),返回 SSE 流。需要在 server 上先 `installStreamingHandler(handler)`。

---

## 3. ⭐ 3 个 HTTP 端点

| Path | Method | 行为 |
|---|---|---|
| `POST /a2a` | POST | 同步 JSON-RPC,一请求一响应 |
| `POST /a2a/stream` | POST | `message/sendSubscribe`,`text/event-stream` SSE |
| `GET /.well-known/agent.json` | GET | AgentCard discovery |
| 其他 | — | 404 / 405 |

**spec 路径固定**: AgentCard discovery 必须是 `/.well-known/agent.json`,streaming 路径固定 `/a2a/stream`。只有 `/a2a` 可配置。

**Threading model**: 共享 `Executor` (默认 cached thread pool),并发请求由 `A2AServer` 内部 `ConcurrentHashMap` 保证线程安全。

---

## 4. ⭐ AgentCard (Schema v0.3 / v1.0)

AgentCard 是 agent 在 `https://{domain}/.well-known/agent-card.json` 发布的发现文档。**客户端先读 card,再决定是否调 agent**。

### 4.1 顶层字段

```json
{
  "name": "aethercode-agent",
  "description": "...",
  "version": "0.1.0",
  "url": "https://agent.example.com",
  "provider": {
    "organization": "AetherCode Team",
    "url": "https://aethercode.org"
  },
  "skills": [ ... ],
  "capabilities": { ... },
  "authentication": { ... }
}
```

| 字段 | 必填 | 含义 |
|---|---|---|
| `name` | ✅ | agent 名 |
| `description` | (默认 "") | 人类可读 |
| `version` | (默认 "0.1.0") | agent 自身版本 |
| `url` | ✅ | 客户端要打的 endpoint |
| `provider` | (可选) | 提供方信息 |
| `skills` | (默认 []) | agent 能力列表 |
| `capabilities` | (默认 defaults) | streaming / push / state-transition 等 |
| `authentication` | (默认 open) | 鉴权模式 |

### 4.2 Skill record

```java
public record Skill(
    @JsonProperty("id") String id,
    @JsonProperty("name") String name,
    @JsonProperty("description") String description,
    @JsonProperty("inputModes") List<String> inputModes,
    ...
)
```

每个 skill 是 agent 能做的一件事 (e.g. "code-review", "tdd-feature")。

### 4.3 Capabilities 默认值

`Capabilities.defaults()` — 默认包含 streaming/push 等。

### 4.4 Authentication

`Authentication.open()` — 默认无鉴权(适合内网)。生产可换 `bearer` / `oauth2` / `apiKey` 等。

---

## 5. ⭐ Task 状态机

```java
public record Task(
    String id,
    String contextId,    // session id (跨 message 复用)
    TaskStatus status,
    List<Message> history,
    List<Artifact> artifacts) {
}

public record TaskStatus(TaskState state, String message) {
    public enum TaskState { SUBMITTED, WORKING, INPUT_REQUIRED, COMPLETED, FAILED, CANCELED }
}
```

**6 个状态**:
- `SUBMITTED` — 刚接收,还没开始
- `WORKING` — agent 正在跑
- `INPUT_REQUIRED` — agent 需要更多 user input (multi-turn)
- `COMPLETED` — 成功完成
- `FAILED` — 失败
- `CANCELED` — 用户取消 (`tasks/cancel`)

**Task 持久化**: 内存 `ConcurrentHashMap` (生产可换 DB)。

---

## 6. ⭐ Message / Artifact / Part

```java
public record Message(
    String messageId,    // UUID
    String contextId,    // 可选, 同 session 共享
    String taskId,       // 可选
    Role role,           // USER | AGENT
    List<Part> parts) {
}

public record Artifact(
    String artifactId,   // UUID
    String name,
    String description,
    List<Part> parts) {
}

public record Part(
    String kind,         // "text" | "file" | "data"
    String text,         // text/plain
    String mimeType,     // e.g. "image/png"
    byte[] data,         // binary
    Map<String, Object> metadata) {
}
```

**Part 三种 kind**:
- `text` — 纯文本 (默认 `text/plain`)
- `file` — 带 mimeType 的二进制 (`image/png` / `application/pdf` 等)
- `data` — 结构化 JSON (`Map<String, Object>`)

---

## 7. ⭐ Streaming 模式 (R250+1)

### 7.1 StreamingHandler 接口

```java
@FunctionalInterface
public interface StreamingHandler {
    void stream(Message userMessage, Consumer<TaskStatusUpdate> emit);
}
```

### 7.2 SSE 帧格式

`text/event-stream`,每帧:
```
data: {"jsonrpc":"2.0","id":"req-1","result":{"taskId":"...","status":"WORKING"}}

data: {"jsonrpc":"2.0","id":"req-1","result":{"taskId":"...","artifact":{...}}}

data: {"jsonrpc":"2.0","id":"req-1","result":{"taskId":"...","status":"COMPLETED"}}
```

### 7.3 降级路径

如果 server **没装** `StreamingHandler` (默认),`message/sendSubscribe` 走 fallback:
- 用同步 `Function<Message, Artifact>` 一次性跑完
- emit 3 步合成流: `WORKING` → `artifact` → `COMPLETED`
- 客户端体验跟 streaming 一样,只是没有中间 progress

### 7.4 ⭐ 关键约束

`StreamingHandler` **必须** emit 终止状态 (`COMPLETED` / `FAILED` / `CANCELED`) 才返回,SSE transport 才会 close response stream。否则客户端永远 hang。

---

## 8. 手工 JSON-RPC 编码 (R250+1 关键修复)

`A2AServer.encode()` 不依赖 Jackson 默认,手写 `ObjectNode`:

```java
private String encode(JsonRpcSupport.Response r) {
    try {
        ObjectNode root = mapper.createObjectNode();
        root.put("jsonrpc", JsonRpcSupport.VERSION);
        if (r.id() != null) root.set("id", mapper.valueToTree(r.id()));
        if (r.isError()) {
            ObjectNode err = mapper.createObjectNode();
            err.put("code", r.error().code());
            err.put("message", r.error().message());
            root.set("error", err);
        } else {
            root.set("result", mapper.valueToTree(r.result()));
        }
        return mapper.writeValueAsString(root);
    } catch (Exception e) {
        return "{\"jsonrpc\":\"" + JsonRpcSupport.VERSION + "\",\"id\":null,\"error\":"
                + "{\"code\":-32603,\"message\":\"encode-failed: " + e.getMessage() + "\"}}";
    }
}
```

**为什么手写**: Jackson `@JsonInclude(NON_NULL)` 会让 `error: null` 渲染成 `"error":false` (因为 boolean 默认 false),**违反 spec**。手写避免这个坑。

**Fallback**: encode 自己失败 → 返回 `-32603 Internal Error` envelope,不让 server 整个挂。

---

## 9. JSON-RPC 错误码 (JSON-RPC 2.0 spec)

| Code | 含义 |
|---|---|
| `-32700` | Parse error (JSON 语法错) |
| `-32600` | Invalid Request (无 method / id) |
| `-32601` | Method not found |
| `-32602` | Invalid params |
| `-32603` | Internal error |
| `-32000 ~ -32099` | Server error (应用自定义) |

A2A 扩展用 `-32000 ~ -32099`:
- `-32001` Task not found
- `-32002` Task not cancelable
- `-32003` Streaming not supported (没装 StreamingHandler)
- `-32004` Auth required

---

## 10. 关键类索引 (按代码量)

| 类 | 文件 | 字节 | 作用 | round |
|---|---|---|---|---|
| `A2AServer` | `A2AServer.java` | 17,250 | 4 个 RPC method + task 状态机 | R241.1 |
| `A2AHttpTransport` | `A2AHttpTransport.java` | 13,099 | HTTP + SSE transport | R250D |
| `A2AClient` | `A2AClient.java` | 11,593 | Java 客户端 | R241.1 |
| `Main` | `Main.java` | 5,793 | CLI 启动器 (run a2a server standalone) | R241.1 |
| `AgentCard` | `schema/AgentCard.java` | 5,513 | 发现文档 record | R241.1 |
| `TaskMapper` | `TaskMapper.java` | 4,991 | 内部 task 状态映射 | R250+1 |
| `AgentCardMapper` | `AgentCardMapper.java` | 4,707 | AgentCard 转换 | R250+1 |
| `JsonRpcSupport` | `JsonRpcSupport.java` | 4,576 | JSON-RPC envelope 解析 | R241.1 |
| `Part` | `schema/Part.java` | 4,033 | 消息片段 (text/file/data) | R241.1 |
| `AgentCardDiscovery` | `AgentCardDiscovery.java` | 3,231 | discovery 客户端 | R250+3 |
| `TaskStatus` | `schema/TaskStatus.java` | 3,139 | 6 状态 enum | R241.1 |
| `Task` | `schema/Task.java` | 2,723 | task record | R241.1 |
| `Artifact` | `schema/Artifact.java` | 1,802 | output record | R241.1 |
| `Message` | `schema/Message.java` | 1,725 | 消息 record | R241.1 |

---

## 11. ⭐ 跨 surface 集成 (R250+3)

A2A client 不只在 Java 端:
- **Java**: `A2AClient` (R241.1)
- **TypeScript**: `aethercode-cli` (R250+3) — 跨进程调 A2A server
- **桥接**: `aethercode-a2a-deepagent-bridge` (R250+2) — DeepAgent 调 A2A

**典型场景**:
- TUI 启动 → daemon 内有 supervisor → supervisor 暴露 A2A server
- 另一个 CLI 客户端 → fetch `/.well-known/agent.json` → 调 `message/send`
- agent 内部 deep-agent → 通过 A2A bridge 调外部 agent

---

## 12. 配置示例

```yaml
# aethercode.yaml
a2a:
  enabled: true
  port: 7823
  rpc_path: /a2a                    # spec 默认值, 一般不改
  agent_card:
    name: aethercode-agent
    description: "Polyglot AI agent"
    version: 0.2.65
    url: http://localhost:7823
    provider:
      organization: AetherCode Team
      url: https://aethercode.org
    skills:
      - id: code-review
        name: Code Review
        description: "Review a diff and emit verdict"
        inputModes: [text]
        outputModes: [text]
    capabilities:
      streaming: true
      push_notifications: false
      state_transition_history: true
    authentication:
      schemes: [bearer]              # 跟 permission-control 共享
  streaming:
    enabled: true
    fallback_sync: true              # 没装 handler 时降级
```

---

## 13. 关键测试

```
A2AServerTest.java                (4 RPC method 单元)
A2AHttpTransportTest.java         (HTTP 路由 + SSE)
A2AStreamingHandlerTest.java      (R250+1 streaming)
A2AClientTest.java                (Java 客户端)
AgentCardDiscoveryTest.java       (R250+3 discovery)
JsonRpcSupportTest.java           (envelope 解析)
A2ADeepAgentBridgeTest.java       (R250+2 桥接)
A2AProtocolConformanceTest.java   (v0.3 spec 一致性)
```

---

## 14. 已知 trade-off

| 决策 | 优点 | 缺点 |
|---|---|---|
| JDK 内置 HttpServer | 无新依赖 | 性能不如 Netty |
| 内存 ConcurrentHashMap | 简单, 快 | server 重启 task 丢失 |
| 手工 JSON-RPC 编码 | 严格 spec | 维护成本高 |
| StreamingHandler @FunctionalInterface | 灵活 | 必须保证 emit 终止状态 |
| `inputModes/outputModes` 在 skill record | 强类型 | spec 改动要同步改 record |
| 6 个 task 状态 | 覆盖 multi-turn (INPUT_REQUIRED) | 状态机复杂度 |
| 没有重试 / 超时 | 简单 | 客户端要自己实现 |
| AgentCard 必须 fetch 才能调 | spec 严格 | 延迟 (但可缓存) |

---

## 15. 关键 round 引用

- **R241.1**: A2A 协议模块 (Java server + client + schema)
- **R250+1**: A2A SSE streaming + 手工 JSON-RPC 编码
- **R250+2**: A2A DeepAgent 桥接
- **R250+3**: aethercode-cli A2A 集成 + AgentCard discovery
- **R250D**: A2A HTTP transport (R250D 详细设计)
- 详细过程见 `../round-notes/R241-A2A-PROTOCOL-MODULE.md` 和 `../round-notes/R250D-A2A-HTTP-TRANSPORT.md`
