# R247 — BankServer Bearer-Token 鉴权 (O-10 公网暴露安全化)

> **状态**: ✅ 完成
> **模块**: aethercode-deepagents (Java)
> **日期**: 2026-09-10
> **触发**: R245.6 候选 #2 — BankServer TLS/auth (暴露公网时)

---

## 1. 背景与动机

R244.2 把 bank 暴露成 5 个 HTTP endpoint,**但默认 localhost-only**:
- `DEEPAGENTS_TALON_EXPOSE_BANK=true` 才启 server
- 没有任何 token / auth,任何本地进程都能读写 bank

**问题**: 当 user 想把 bank 暴露给远程 desktop / 远程 IntelliJ 插件时:
- 没有 auth,任何能扫到 port 的人都能读 bank(可能 leak 内部 fix_strategy)
- 任何能扫到 port 的人都能 `recordOutcome` 污染 self-eval
- 不能用公网(0.0.0.0) bind,只能 localhost

R247 加 bearer-token 鉴权 — server 启时 env 配 token,client 必须 `Authorization: Bearer <token>` 才能调 `/bank/*`。
`/healthz` 永远开放(load balancer 用),`/bank/*` 需要 token。

**TLS 留 R247+** — 这 round 集中搞 token, 1 round scope。HttpsServer 涉及 keystore / HttpsConfigurator, 是 R248+。

---

## 2. 实际产出

### 2.1 修改 Java 文件 (3)

| 文件 | 改动 |
|------|------|
| `BankServer.java` | +~70 行: `authToken` field, 2 参 constructor, `withAuthToken()` builder, `requireAuth()` helper, `authed()` 装饰器, `constantTimeEquals()` 防 timing attack, 6 个 createContext 包 `authed()` |
| `BankClient.java` | +~20 行: `authToken` field, `BankClient(URI, String)` 2 参 constructor, 4 参 canonical constructor, `send()` 在 header 上加 `Authorization: Bearer <token>` |
| `TalonSelfReflectWiring.java` | +1 常量 `ENV_BANK_TOKEN`, `startBankServer(wiring, port, env)` 3 参 overload 读 env 传 token |

### 2.2 测试结果

- **aethercode-deepagents: 240/240 pass** (R245.4 230 + R247 10, 0 回归)
- **aethercode-talon: 5/5 pass** (0 回归)
- **aethercode-memory: 513/513 pass** (0 回归)

---

## 3. 设计与关键技术决定

### 3.1 R244.2 默认 (no auth) 保持向后兼容

```java
// R244.2 default
public BankServer(ReasoningBank bank) { this(bank, null); }
public BankClient(URI baseUri) { this(baseUri, null); }
```

- 旧代码 `new BankServer(bank)` / `new BankClient(uri)` **零改动继续工作**
- 没配 `AETHERCODE_BANK_TOKEN` 的 daemon 行为完全不变
- **没有 breaking change**

### 3.2 `/healthz` 永远开放

```java
server.createContext("/healthz", new HealthHandler());
// R247: /bank/* routes go through the bearer-token gate.
// /healthz is intentionally unauthenticated so a load
// balancer can probe it without a credential.
server.createContext("/bank/recall",          authed(new RecallHandler()));
server.createContext("/bank/recall-all-kinds", authed(new RecallAllKindsHandler()));
...
```

- LB / K8s readiness probe 不需要 token
- token 泄漏 / 失配不会让整个 daemon 不可探活

### 3.3 `Authorization: Bearer <token>` RFC 6750 标准

```java
String presented;
if (header.regionMatches(true, 0, "Bearer ", 0, 7)) {
    presented = header.substring(7).trim();
} else {
    presented = header.trim();
}
```

- 标准 RFC 6750 用 `Bearer` 前缀
- 但 R247 也**接受裸 token**(无前缀) — curl / bash 脚本写起来简单
- 大小写不敏感 (`regionMatches(true, ...)`)
- trim 空白

### 3.4 `constantTimeEquals` 防 timing attack

```java
private static boolean constantTimeEquals(String a, String b) {
    if (a == null || b.length() != a.length()) return false;
    int diff = 0;
    for (int i = 0; i < a.length(); i++) {
        diff |= a.charAt(i) ^ b.charAt(i);
    }
    return diff == 0;
}
```

**为什么**: 标准 `String.equals` 是"第一个不同字符就 return false",理论上能通过
response time 推断 token 长度 / 字符。constant-time 实现消掉这个 side channel。

实际上 token 32-64 bytes, timing attack 极难实战,但代码表达"我们想过这个",
跟未来 code reader 沟通成本低。

### 3.5 `authed()` 装饰器 — DRY 5 个 handler

```java
private HttpHandler authed(HttpHandler inner) {
    return new HttpHandler() {
        @Override public void handle(HttpExchange ex) throws IOException {
            if (requireAuth(ex)) {
                inner.handle(ex);
            }
        }
    };
}
```

**5 个 /bank/* handler 不用每个都改**:
- 旧: `server.createContext("/bank/recall", new RecallHandler());`
- 新: `server.createContext("/bank/recall", authed(new RecallHandler()));`

只有一行,所有 handler 的 auth 检查一致 — 未来加新 `/bank/*` endpoint 不会忘记加 auth。

### 3.6 `withAuthToken()` builder 风格 hook

```java
BankServer server = new BankServer(bank)
    .withAuthToken("first-token")
    .withAuthToken("second-token")  // 后者覆盖前者
    .start(0);
```

**为什么需要 builder**: 构造器可能在 host 的不同 phase 拿到 token(e.g. config file 在 start 之前读)。
builder 让 host 在 BankServer 拿到 config token 后再注入,不必重新 `new`。

### 3.7 blank token 自动 fallback unauthenticated

```java
public BankClient(URI baseUri, String authToken) {
    // ...
    this.authToken = (authToken == null || authToken.isBlank()) ? null : authToken;
}
```

**为什么**: misconfiguration 不应该 lockout host — 配了空 token, server 退化成 R244.2 default (unauthenticated), log warn。host 立刻能重启 daemon 修 config。

### 3.8 4 参 canonical constructor, 其他 delegate

```java
public BankClient(URI baseUri, String authToken) {
    this(baseUri, null, null, authToken);  // → 4 参 (http=null, mapper=null, token=...)
}

public BankClient(URI baseUri, HttpClient http, ObjectMapper mapper) {
    this(baseUri, http, mapper, null);  // → 4 参 (token=null)
}

public BankClient(URI baseUri, HttpClient http, ObjectMapper mapper, String authToken) {
    // canonical 4 参 — 全部逻辑在这
}
```

**为什么 4 参分两半**: 3 参 `(URI, String, String)` 跟 3 参 `(URI, HttpClient, ObjectMapper)`
Java 编译器在 `this(baseUri, null, authToken)` 时会歧义。`http=null, mapper=null` (NullPointerException 风险)
跟 `authToken=null` 写法混淆。

**修法**: 2 参 `(URI, String)` 明确 delegate 到 `(URI, null, null, String)`,4 参是唯一真实 constructor。

---

## 4. 实战

### 4.1 启 daemon (有 token)

```bash
# 32-byte random token
TOKEN=$(openssl rand -hex 32)
echo "Save this: $TOKEN"

DEEPAGENTS_TALON_EXPOSE_BANK=true \
DEEPAGENTS_TALON_BANK_PORT=7777 \
AETHERCODE_BANK_TOKEN="$TOKEN" \
java -jar aethercode-0.2.62.jar

# log: "bank server listening on http://127.0.0.1:7777 (auth required)"
```

### 4.2 Client 调用 (有 token)

```java
URI base = URI.create("http://daemon.lan:7777");
BankClient client = new BankClient(base, "saved-token");
List<BankUnit> top = client.recallAllKinds(5);  // ← 自动带 Authorization header
```

### 4.3 curl 测试 (R244.2 默认无 token)

```bash
# 无 token daemon, curl 跟 R244.2 一样
$ curl http://127.0.0.1:7777/healthz
{"ok":true}

$ curl http://127.0.0.1:7777/bank/stats
{"size":5,"kinds":[...],"perKind":{...},"totalOk":4,"totalNotOk":1}
```

### 4.4 curl 测试 (有 token daemon)

```bash
# 无 Authorization header → 401
$ curl http://127.0.0.1:7777/bank/stats
{"error":"missing Authorization header"}
# HTTP/1.1 401 Unauthorized

# 错 token → 401
$ curl -H "Authorization: Bearer wrong" http://127.0.0.1:7777/bank/stats
{"error":"invalid bearer token"}

# 对 token → 200
$ curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:7777/bank/stats
{"size":5,"kinds":[...]}

# /healthz 永远 200 (跟 auth 无关)
$ curl http://127.0.0.1:7777/healthz
{"ok":true}
```

---

## 5. R247 vs 估计

| 估计 | **实际** |
|------|------|
| "BankServer TLS/auth 1 round" | **< 1 round (10 tests, 0 回归)** |

**连续 20 round 全部 < 1 round**: R240/R240.2/R241.1/R242.1/R242.2/R241.2/R241.3/R243.1/R243.2/R243.2B/R243.3/R244.1/R244.2/R244.3/R245.1/R245.2/R245.5/R245.3/R245.4/R246/R247

---

## 6. 关键文件路径

### 6.1 修改 (3)

```
D:\work\workspace\idea\engine\AetherCode\aethercode\aethercode-deepagents\src\main\java\org\aethercode\deepagents\selfimprove\BankServer.java           (+~70 行)
D:\work\workspace\idea\engine\AetherCode\aethercode\aethercode-deepagents\src\main\java\org\aethercode\deepagents\selfimprove\BankClient.java           (+~20 行)
D:\work\workspace\idea\engine\AetherCode\aethercode\aethercode-deepagents\src\main\java\org\aethercode\deepagents\selfimprove\TalonSelfReflectWiring.java  (+1 常量 + 1 overload)
```

### 6.2 修改 test (1)

```
D:\work\workspace\idea\engine\AetherCode\aethercode\aethercode-deepagents\src\test\java\org\aethercode\deepagents\selfimprove\BankServerTest.java   (+10 tests)
```

---

## 7. 教训 (R247 新增 5 条)

1. **Auth 加在装饰器层, 不在每个 handler 重复** — `authed(HttpHandler)` 装饰器, 5 个 `/bank/*` endpoint 1 行加 auth, 未来加新 endpoint 不会忘
2. **空白 token 自动 fallback unauthenticated** — `String.isBlank()` 检查, misconfiguration 不 lockout host
3. **constant-time compare 防 timing attack** — token 短, 实战风险低, 但代码表达"我们想过"
4. **4 参 canonical constructor 防 Java 歧义** — `this(baseUri, null, null, authToken)` 跟 `this(baseUri, http, mapper)` 编译器会冲突, 显式 4 参分两半
5. **`/healthz` 永远不 auth** — load balancer / k8s probe 不需要 token, 跟 `/bank/*` 解耦

---

## 8. O-10 跨 surface bank 安全化

| 阶段 | 状态 |
|------|------|
| R244.2 server 暴露 (5 endpoints, JDK HttpServer) | ✅ |
| R244.3 TS client library | ✅ |
| R245.1 TUI 真正接通 (slash commands) | ✅ |
| R245.2 MemoryAudit 接 self-eval | ✅ |
| R245.3 Periodic decay scheduler | ✅ |
| R245.4 Confidence-aware DRIFT | ✅ |
| R245.5 Welcome banner 自动显示 | ✅ |
| **R247 Bearer-token 鉴权** | ✅ |
| R247+ TLS (HttpsServer + keystore) | 📋 |

**现在 daemon 暴露公网前只需要配 `AETHERCODE_BANK_TOKEN=<32-byte random>`** —
任何 client 必须带 token 才能读写 bank, `/healthz` 永远开放给 LB。
R248+ 加 TLS 后可以公网 IP 暴露给远程 desktop / IntelliJ 插件。

---

**总结**: R247 用 10 个 Java test 在 BankServer 加 bearer-token 鉴权, 跟 R244.2 默认
(unauthenticated) 完全向后兼容。240 + 5 + 513 tests 全绿, 0 回归。
BankServer 跟 BankClient 都用 4 参 canonical constructor, future-proof。
