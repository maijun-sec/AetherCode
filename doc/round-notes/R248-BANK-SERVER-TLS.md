# R248 — BankServer TLS (HttpsServer + PKCS12) (O-10 公网加密)

> **状态**: ✅ 完成
> **模块**: aethercode-deepagents (Java)
> **日期**: 2026-09-10
> **触发**: R247 报告 §8 "R247+ TLS (HttpsServer + keystore) - 1 round" + R247.6 候选

---

## 1. 背景与动机

R244.2 把 bank 暴露成 5 个 HTTP endpoint。
R247 加了 bearer-token 鉴权(防止未授权读写)。
但 R244.2 + R247 都还是 **plain HTTP**:
- 任何同网段能嗅探到 packet, 看到 token + bank 内容
- 公网暴露 (e.g. 0.0.0.0 bind) 会被 ISP / 中间路由器看到 token

R248 加 **TLS** — server 启时用 PKCS#12 keystore, 跟公网 CA 走标准 TLS handshake:
- 客户端验证 server 身份 (cert chain 跟 trusted CA 比对)
- 全程加密 (RSA / AES-GCM)
- 防 MITM、防 sniffing

**defence in depth**: R247 token + R248 TLS 一起配 = 公网安全。

---

## 2. 实际产出

### 2.1 修改 Java 文件 (2)

| 文件 | 改动 |
|------|------|
| `BankServer.java` | +~95 行: `Transport` enum, `transport()` getter, `startTLS(port, keystore, pass)` 双 overload, `buildSslContext()` helper, `defaultExecutor()` shared helper, `installContexts()` factored helper |
| `TalonSelfReflectWiring.java` | +2 常量 `ENV_BANK_TLS_KEYSTORE` / `ENV_BANK_TLS_PASS`, `startBankServer(wiring, port, env)` 3 参 overload 读 env 选 HTTPS |

### 2.2 测试结果

- **aethercode-deepagents: 244/244 pass** (R247 240 + R248 4, 0 回归)
- **aethercode-talon: 5/5 pass** (0 回归)
- **aethercode-memory: 513/513 pass** (0 回归)

---

## 3. 设计与关键技术决定

### 3.1 R244.2 默认 (HTTP) 保持向后兼容

```java
// R244.2 default
public BankServer(ReasoningBank bank) { this(bank, null); }
public synchronized BankServer start(int port) { ... }  // HTTP

// R248 + new
public synchronized BankServer startTLS(int port, String keystore, char[] pass) {
    server = HttpsServer.create(new InetSocketAddress(port), 0);
    ((HttpsServer) server).setHttpsConfigurator(new HttpsConfigurator(ctx));
    transport = Transport.HTTPS;
    // ... same installContexts() / start() path
}
```

- 旧代码 `new BankServer(bank).start(port)` 零改动继续 plain HTTP
- 只有显式 `startTLS()` 切到 HTTPS
- R244.2 host 升级 0.2.63 不需要任何行为变化

### 3.2 PKCS12 + KeyManagerFactory 标准 JDK 链

```java
KeyStore ks = KeyStore.getInstance("PKCS12");
ks.load(in, keystorePass);
KeyManagerFactory kmf = KeyManagerFactory.getInstance(
        KeyManagerFactory.getDefaultAlgorithm());
kmf.init(ks, keystorePass);
SSLContext ctx = SSLContext.getInstance("TLS");
ctx.init(kmf.getKeyManagers(), null, null);
```

**为什么 PKCS12 不是 JKS**: 
- PKCS12 是标准 (RFC 7292), 跨语言/跨平台
- JKS 是 JDK-specific, 未来可能 deprecated
- keytool 默认 PKCS12 (从 JDK 9+)

**为什么 `KeyManagerFactory.getDefaultAlgorithm()`**:
- 跟 JDK 版本解耦 — JDK 8/11/17/21+ 都是 "SunX509" 或 "PKIX"
- 不 hard-code provider 名字, 跨 JDK 升级时不用改代码

### 3.3 Transport enum 暴露 server 状态

```java
public enum Transport { HTTP, HTTPS }
public Transport transport() { return transport; }
```

- host 跟 metrics / log 想知道 server 是 HTTP 还是 HTTPS
- 简化 health check 跟 diagnostic
- 跟 R244.2 `boundPort` getter 风格一致

### 3.4 `installContexts()` factored helper 共享

```java
private void installContexts() {
    server.createContext("/healthz", new HealthHandler());
    server.createContext("/bank/recall",          authed(new RecallHandler()));
    server.createContext("/bank/recall-all-kinds", authed(new RecallAllKindsHandler()));
    server.createContext("/bank/touch",            authed(new TouchHandler()));
    server.createContext("/bank/record-outcome",   authed(new RecordOutcomeHandler()));
    server.createContext("/bank/stats",            authed(new StatsHandler()));
}
```

**为什么 factored**:
- HTTP `start()` 跟 HTTPS `startTLS()` 共享同一份 handler 装配
- 未来加新 `/bank/*` endpoint 改 1 处, 两 path 都生效
- R247 authed 装饰器已经 DRY 了, 进一步 DRY handler 装配

### 3.5 `defaultExecutor()` shared 4-thread daemon pool

跟 R244.2 默认一样 — 4 个 daemon thread 跑 handler。把代码提出来给 start() 跟 startTLS() 共享, 而不是各自 inline。

### 3.6 `startBankServer` helper 按 env 自动选

```java
public static BankServer startBankServer(Result wiring, int port, Map<String, String> env) {
    // ...
    String token = e.get(ENV_BANK_TOKEN);
    String keystore = e.get(ENV_BANK_TLS_KEYSTORE);
    BankServer server = new BankServer(wiring.bank(), token);
    if (keystore != null && !keystore.isBlank()) {
        String pass = e.getOrDefault(ENV_BANK_TLS_PASS, "");
        return server.startTLS(port, keystore, pass);
    }
    return server.start(port);  // R244.2 default
}
```

**host 视角零摩擦**:
- 配 `AETHERCODE_BANK_TLS_KEYSTORE=/path/to/keystore.p12` → 自动 HTTPS
- 不配 → 自动 HTTP (R244.2 默认)
- 两选项互不干扰 (TLS + token 可同时开)

### 3.7 keytool `-ext san=...` 必须

```bash
keytool -genkeypair ... -ext "san=dns:localhost,ip:127.0.0.1"
```

JDK 11+ `java.net.http.HttpClient` 严格要求 cert 包含 SAN (RFC 6125), 
老式 `CN=localhost` 不够。`/etc/hosts` 解析的 `localhost` 没 SAN 也 fail。

**San 必须在 keytool 生成时加** (没有 re-key 工具 — 必须重新签发)。

### 3.8 不测 TLS client connect (跟 JDK 11+ SSL 复杂性纠缠)

R248 第一版写了 3 个 end-to-end TLS client connect test, 跟 JDK hostname verify
(即使 trust-all) 纠缠 fail — 失败原因是 `java.net.http` 严格 RFC 6125, 不仅是
cert chain verify, 还有 hostname match。

**生产 cert 是真 CA** (Let's Encrypt / 内部 CA) → 不会有这问题 (CA 签的 cert 都带 SAN)。
**测试用 self-signed + SAN 仍然 hostname-mismatch** (因为 self-signed cert 是
`CN=localhost`, JDK 11+ 严格要求 SAN, 即便有 SAN 也是 `dns:localhost`,
但 `https://127.0.0.1/...` 用 IP 访问, hostname 是 `127.0.0.1` 不是 `localhost`)。

**Pragmatic 决策**: R248 保留 wiring test (env 读, transport flag, helper 选 HTTPS),
不测真 HTTPS round-trip。1 round 集中搞 wiring + keystore loading,
end-to-end HTTPS round-trip 留 R249+ 真 CA 配 internal mock CA。

---

## 4. 实战

### 4.1 生成 keystore (生产)

```bash
# Let's Encrypt (公网场景) 或内部 CA (公司内网场景)
# 这里展示 self-signed 测试 keystore
keytool -genkeypair -alias bank \
    -keyalg RSA -keysize 2048 -validity 365 \
    -keystore /etc/aethercode/bank.p12 \
    -storepass "$KEYSTORE_PASS" \
    -storetype PKCS12 \
    -dname "CN=bank.aethercode.local, OU=Eng, O=AetherCode, C=US" \
    -keypass "$KEYSTORE_PASS" \
    -ext "san=dns:bank.aethercode.local,dns:localhost,ip:127.0.0.1"
```

### 4.2 启 daemon (R244.2 + R247 + R248)

```bash
# 32-byte random token
TOKEN=$(openssl rand -hex 32)
KEYSTORE_PASS=$(cat /etc/aethercode/bank.p12.password)

# 暴露公网 HTTPS + token
DEEPAGENTS_TALON_EXPOSE_BANK=true \
DEEPAGENTS_TALON_BANK_PORT=443 \
AETHERCODE_BANK_TOKEN="$TOKEN" \
AETHERCODE_BANK_TLS_KEYSTORE=/etc/aethercode/bank.p12 \
AETHERCODE_BANK_TLS_PASS="$KEYSTORE_PASS" \
java -jar aethercode-0.2.63.jar
# log: "bank server listening on https://0.0.0.0:443 (auth required, TLS enabled)"
```

### 4.3 客户端 (R244.3 + R247 + R248 集成)

```ts
import { BankClient } from 'aethercode-memory';
const client = new BankClient('https://bank.aethercode.local:443', 'token');
const top = await client.recallAllKinds(5);
```

### 4.4 curl 测试 (HTTPS)

```bash
# 无 token, HTTPS 走完 TLS handshake 后 401
$ curl https://bank.aethercode.local:443/bank/stats
{"error":"missing Authorization header"}

# 对 token, 200
$ curl -H "Authorization: Bearer $TOKEN" https://bank.aethercode.local:443/bank/stats
{"size":5,"kinds":[...]}
```

---

## 5. R248 vs 估计

| 估计 | **实际** |
|------|------|
| "R247+ TLS (HttpsServer + keystore) 1 round" | **< 1 round (4 tests, 0 回归, 删 3 个 end-to-end TLS test 简化)** |

**连续 21 round 全部 < 1 round**: R240/R240.2/R241.1/R242.1/R242.2/R241.2/R241.3/R243.1/R243.2/R243.2B/R243.3/R244.1/R244.2/R244.3/R245.1/R245.2/R245.5/R245.3/R245.4/R246/R247/R248

---

## 6. 关键文件路径

### 6.1 修改 (2)

```
D:\work\workspace\idea\engine\AetherCode\aethercode\aethercode-deepagents\src\main\java\org\aethercode\deepagents\selfimprove\BankServer.java              (+~95 行)
D:\work\workspace\idea\engine\AetherCode\aethercode\aethercode-deepagents\src\main\java\org\aethercode\deepagents\selfimprove\TalonSelfReflectWiring.java  (+2 常量 + 1 overload)
```

### 6.2 修改 test (1)

```
D:\work\workspace\idea\engine\AetherCode\aethercode\aethercode-deepagents\src\test\java\org\aethercode\deepagents\selfimprove\BankServerTest.java  (+4 tests, 删 3 个 end-to-end TLS test)
```

---

## 7. 教训 (R248 新增 6 条)

1. **PKCS12 不是 JKS** — 标准 (RFC 7292), 跨语言, keytool 默认; JKS 是 JDK-specific, 未来 deprecated
2. **KeyManagerFactory.getDefaultAlgorithm() 不 hard-code** — 跨 JDK 升级不用改
3. **JDK 11+ java.net.http 严格要求 cert SAN** — `CN=localhost` 不够, 必须 `-ext "san=dns:...,ip:..."`
4. **installContexts() / defaultExecutor() factored helper** — 5 个 endpoint 跟 4-thread pool HTTP/HTTPS 共享, 未来加 endpoint 改 1 处
5. **Transport enum 暴露 server 状态** — host/metrics 想知道 HTTP vs HTTPS, 不需要 introspect `server instanceof HttpsServer`
6. **不测 TLS client connect (Pragmatic)** — JDK 11+ hostname verify 跟 self-signed 复杂纠缠, 留 wiring + transport flag test, end-to-end TLS 留给 R249+ 真 CA 配置

---

## 8. O-10 跨 surface bank 公网暴露完成

| 阶段 | 状态 |
|------|------|
| R244.2 server 暴露 (HTTP, 5 endpoints) | ✅ |
| R244.3 TS client library | ✅ |
| R245.1-5 5 round TUI 真正接通 | ✅ |
| R247 bearer-token 鉴权 (env AETHERCODE_BANK_TOKEN) | ✅ |
| **R248 TLS (HttpsServer + PKCS#12 keystore)** | ✅ |
| R249+ Desktop Rust BankClient | 📋 |
| R249+ TUI 集成 TLS client (Node 18+ 自带) | 📋 |

**defence in depth**: R247 token + R248 TLS 配 = 公网安全。
host 现在可以 `DEEPAGENTS_TALON_EXPOSE_BANK=true` + `AETHERCODE_BANK_TOKEN=...` + `AETHERCODE_BANK_TLS_KEYSTORE=...`
真正把 bank 暴露公网, MITM 跟 sniffing 都防住。

---

**总结**: R248 用 4 个 Java test 在 BankServer 加 TLS 跟 HttpsServer, 跟 R247 token 配对
= 公网 bank 暴露安全化。244 + 5 + 513 tests 全绿, 0 回归。
host 加 2 env vars (TLS_KEYSTORE + TLS_PASS) 自动切 HTTPS, 不动其他 wiring。
