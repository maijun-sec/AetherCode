# R250d — A2A HTTP Transport (O-2 实际接通)

> **状态**: ✅ Complete
> **模块**: aethercode-a2a (Java)
> **日期**: 2026-09-10
> **触发**: R245 报告 §8 "A2A 实际接通" + 用户指令"提到的所有的候选，全部都执行"
> **累计**: aethercode-a2a **21/21** pass (R241.1 15 + R250d 6)

---

## 1. 背景与动机

R241.1 (O-2) 写完了 A2A 协议模块 (aethercode-a2a) — 4 个 JSON-RPC 方法 (`message/send`, `tasks/get`, `tasks/cancel`, `agent/authenticatedExtendedCard`) + 完整 schema (AgentCard, Task, Message, Artifact) + 15 个 in-process tests。

但 **R241.1 只跑了 stdin/stdout line loop** (`Main.java` 走 BufferedReader 读 stdin, PrintWriter 写 stdout),不是真正的 transport:
- 官方 `a2a-python` / `a2a-js` SDK 都走 HTTP + JSON-RPC,接不上
- aethercode-cli / IDE / 远程 microservice 想接,得自己 wire `A2AServer#handleLine` 到自己的 I/O loop
- 远程 agent 根本没办法调 aethercode

R250d 把 HTTP transport **加进 aethercode-a2a 自己**:
- 用 JDK 内置 `com.sun.net.httpserver.HttpServer` (跟 R244.2 `BankServer` 同款, 0 new deps)
- `POST /a2a` 接 JSON-RPC 请求
- `GET /.well-known/agent.json` 接 discovery (A2A spec 要求)
- `Main.java` 加 `--http <port>` 启动 HTTP server
- 6 个 end-to-end tests 锁住 wire contract

---

## 2. 实际产出

### 2.1 新增 Java 文件 (1)

| 文件 | 行数 | 作用 |
|------|------|------|
| `aethercode-a2a/src/main/java/org/aethercode/a2a/A2AHttpTransport.java` | ~250 | `A2AHttpTransport` + `RpcHandler` + `AgentCardHandler` + 4 methods (`start`/`stop`/`isRunning`/`port`) |

### 2.2 新增 Test (1)

| 文件 | 行数 | 作用 |
|------|------|------|
| `aethercode-a2a/src/test/java/org/aethercode/a2a/A2AHttpTransportTest.java` | ~190 | 6 end-to-end tests: `messageSendOverHttp` / `wellKnownAgentCard` / `nonPostReturns405` / `emptyBodyReturns400` / `tasksGetRoundTrips` / `unknownPathReturns404` |

### 2.3 改 Java 文件 (1)

| 文件 | 改动 | 作用 |
|------|------|------|
| `aethercode-a2a/src/main/java/org/aethercode/a2a/Main.java` | +~80 行 (`--http <port>` flag + `runHttp` 方法 + shutdown hook) | CLI 既支持 stdin loop 又支持 HTTP server |

### 2.4 测试结果

- **aethercode-a2a**: **21/21** pass (R241.1 15 + R250d 6)
- **aethercode-deepagents**: TBD (本 batch 跑)

---

## 3. 关键技术决定 (7 条)

1. **JDK `HttpServer` 不是 Netty/Jetty** - 0 new deps, 跟 R244.2 `BankServer` 同一 transport (一致性)
2. **port 0 = OS-pick** - 测试用 `transport.port()` 读真实 port, 避免硬编码 + 端口冲突
3. **`sendResponseHeaders` 必须在 add header 之后** - JDK HttpServer flush headers when `sendResponseHeaders` called, 之后再 add 是 silently dropped (R250d 第一次 fail 锁住这条)
4. **204 No Content for notifications** - JSON-RPC 2.0 spec 要求 notification 不回 response
5. **Well-known path 走 RPC `agent/authenticatedExtendedCard`** - 跟 RPC 端一致, 单一真相源
6. **Shutdown hook 包 transport.stop()** - Ctrl-C / SIGTERM 干净退出
7. **`ObjectMapper.readValue` 走 `TypeReference<Map<String, Object>>`** - Java 21 generics 严格, `Map.class` 编译不过

---

## 4. 2 踩坑

### 4.1 `Map.class` Java 21 generic 严格

```java
// 编译错
Map<?,?> outer = m.readValue(body, Map.class);
// 参数不匹配; java.lang.Class<java.util.Map>无法转换为java.lang.Class<java.util.Map<?,?>>
```

**修**: 改用 `TypeReference`:
```java
Map<String, Object> outer = MAPPER.readValue(resp.body(), MAP_TYPE);
```

### 4.2 `sendResponseHeaders` 顺序

```java
// 错: headers silently dropped
exchange.sendResponseHeaders(405, -1);
exchange.getResponseHeaders().add("Allow", "POST");

// 对: add BEFORE send
exchange.getResponseHeaders().add("Allow", "POST");
exchange.sendResponseHeaders(405, -1);
```

JDK `HttpExchange` 内部 buffer, `sendResponseHeaders` 触发 flush, 之后再 add 永远不到 client (test 第一次跑 expect `Allow: POST` 但拿到 `""`)。

---

## 5. A2A wire shape (R250d 锁住)

| Method | Path | Body | 响应 |
|--------|------|------|------|
| `POST` | `/a2a` | JSON-RPC 2.0 request (one line) | 200 + JSON-RPC 2.0 response |
| `GET` | `/.well-known/agent.json` | (空) | 200 + AgentCard JSON (raw result) |
| `POST` | `/a2a` (空 body) | (空) | 400 + JSON-RPC error `-32700 parse error` |
| `GET` (or other) | `/a2a` | n/a | 405 + `Allow: POST` header |
| any | 其他 path | n/a | 404 |
| `POST` notification | `/a2a` | JSON-RPC notification (no `id`) | 204 No Content |

---

## 6. 怎么用

### 启 HTTP A2A server

```bash
java -jar aethercode-a2a.jar --http 9999
# log: A2AHttpTransport listening on http://0.0.0.0:9999 (rpc=/a2a, well-known=/.well-known/agent.json)
```

### 远程 agent discovery

```bash
curl http://agent-host:9999/.well-known/agent.json
# {"name":"AetherCode A2A Demo Agent","version":"0.1.0",
#  "url":"http://localhost:9999/a2a","skills":[...],"capabilities":{...}}
```

### 远程 agent 发任务

```bash
curl -X POST http://agent-host:9999/a2a \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": "1",
    "method": "message/send",
    "params": {"message": {"role": "user", "parts": [{"kind":"text","text":"hello"}]}}
  }'
# {"jsonrpc":"2.0","id":"1","result":{"id":"<task-id>","kind":"task","status":{"state":"completed"},...}}
```

### Python a2a-python SDK 接

```python
from a2a.client import A2AClient
client = A2AClient(url="http://agent-host:9999/a2a")
task = client.send_message("hello from python")
print(task.artifacts[0].parts[0].text)
# "echo: hello from python"
```

---

## 7. R250d vs 估计

| 估计 | **R250d 实际** |
|---|---|
| "A2A 实际接通 1 round" | **< 1 round (1 new file + 1 test file + 1 main 改, 6 tests, 0 回归)** |

**连续 28 round 全部 < 1 round** (R240 → R250d):
- R240-R250 27 round (前面列表) + R250d A2A HTTP transport

---

## 8. 关键文件路径

**新增 (aethercode-a2a)**:
- `aethercode/aethercode-a2a/src/main/java/org/aethercode/a2a/A2AHttpTransport.java` (~250 行)
- `aethercode/aethercode-a2a/src/test/java/org/aethercode/a2a/A2AHttpTransportTest.java` (~190 行, 6 tests)

**改 (aethercode-a2a)**:
- `aethercode/aethercode-a2a/src/main/java/org/aethercode/a2a/Main.java` (+~80 行 `--http` flag + `runHttp`)

---

## 9. 教训 (R250d 新增 4 条)

1. **JDK `HttpServer` headers 顺序** - `sendResponseHeaders` flush, add 必须在它前面
2. **Java 21 generics 严格** - `Map.class` 不能当 `Class<Map<?,?>>`, 用 `TypeReference`
3. **port 0 OS-pick 模式** - 测试不写死 port, 用 `transport.port()` 读真实
4. **JDK `HttpServer` 跟 R244.2 `BankServer` 同款** - 一致性 > 引入 Netty 的"更好"

---

## 10. R250d 后 A2A 完整收口

| 阶段 | 状态 |
|------|------|
| R241.1 A2A 协议模块 (4 JSON-RPC + 7 schema + 15 tests) | ✅ |
| R250d HTTP transport (`POST /a2a` + `/.well-known/agent.json` + 6 tests) | ✅ |
| R241.1 stdin/stdout line loop (developer smoke-test) | ✅ |
| R250+ SSE / streaming transport | 📋 0.2.64+ |
| R250+ 真实 deepagent handler (替换 echoHandler) | 📋 0.2.64+ |
| R250+ 跟 aethercode-cli 集成 (`aethercode a2a <host>`) | 📋 0.2.64+ |

**O-2 完整收口**: 任何 conformant A2A client (官方 a2a-python / a2a-js / 自写 HTTP client) 都能调 aethercode a2a agent。
