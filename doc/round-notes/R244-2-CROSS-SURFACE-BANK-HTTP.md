# R244.2 — Cross-Surface Bank via HTTP (O-10 跨 surface)

**Status**: shipped
**Date**: 2026-09-10
**Parent**: R241.2 + R241.3 + R243.1 + R243.2 + R243.2B + R243.3 + R244.1 (O-3 + O-6) + R239 roadmap
**Tests**: aethercode-deepagents **218/218 pass, 0 regressions** (was 209 in R244.1; +9 new)

---

## Why R244.2

R241.2 - R244.1 让 deep-agent runtime 自己用上了 O-3 经验库。但 bank 仍然在 JVM 进程内。**跨 surface** (TUI / desktop / IntelliJ / 其他 daemon) 拿不到:

- TUI 是 Node 进程 (aethercode-tui/)
- Desktop 是 Tauri (aethercode-desktop/)
- IntelliJ 插件
- 其他 daemon 进程

R244.2 解决：暴露一个 **localhost HTTP API** 给这些 surface，让它们读 / 写同一个 bank。

### 论文 reference

- A2A protocol (Google open standard, R241.1 已落地) — task-oriented
- Memory survey arXiv:2512.13564 §6 — 跨进程 memory 共享 best practice
- **本文不直接用 aethercode-a2a**: A2A 是 task-oriented (message/send), 不是 CRUD; 把 bank 操作塞进 message/send 会让两边 schema 都变扭. BankServer 是独立的小 HTTP front-end, 让 aethercode-a2a 跟 aethercode-deepagents 各司其职

### 设计决定

| 决策 | 选择 | 理由 |
|---|---|---|
| 协议 | HTTP + JSON | 跨语言最简单, TUI/IntelliJ/CLI 都能调 |
| HTTP 实现 | JDK `com.sun.net.httpserver.HttpServer` | 零新依赖 |
| 端口 | 7777 (default) | 可 env override |
| 安全 | localhost-only | 跨 surface 用, 不暴露公网 |
| 写并发 | 简化 synchronized (per request) | 单 daemon 写 + 多 reader, 不需要复杂锁 |
| schema | 复用 `ReasoningUnit.toMap()` | 跟 R244.1 wire format 兼容 |

---

## What shipped

### 1. `BankServer` (14.9 KB) — R244.2 新增

JDK 内置 HttpServer + 5 endpoints + healthz:

| Method + Path | 用途 |
|---|---|
| `GET  /healthz` | liveness probe |
| `GET  /bank/recall?kind=K&n=3` | top-N for a kind |
| `POST /bank/recall-all-kinds?n=3` | cross-kind top-N (跟 BankRecallMiddleware 一致) |
| `POST /bank/touch?id=X` | bump uses/utility |
| `POST /bank/record-outcome?id=X&ok=true\|false` | R244.1 self-eval 反馈 |
| `GET  /bank/stats` | size / per-kind / total ok / notOk |

设计要点:
- 默认端口 7777, env `AETHERCODE_BANK_PORT` 覆盖
- 4 thread pool, daemon 线程
- handler exception → JSON 500, 不挂 server
- `start(0)` 选 ephemeral port (tests 用)
- `stop()` 幂等

### 2. `BankClient` (8.3 KB) — R244.2 新增

跨 surface client:
- `ping()` — healthz
- `recallFor(kind, n)` / `recallAllKinds(n)` — 读
- `touch(id)` / `recordOutcome(id, ok)` — 写
- `stats()` — snapshot

设计要点:
- JDK `java.net.http.HttpClient` (零依赖)
- `BankClientException` (含 `status()`) — network/decode 错误
- transport fail → status=0 异常
- 404 跟其他 4xx/5xx 区分

### 3. `TalonSelfReflectWiring.startBankServer()` 静态 helper

```java
BankServer server = TalonSelfReflectWiring.startBankServer(wiring);
server.port(); // 实际绑定的端口 (含 ephemeral)
```

Opt-in — 不在 `build()` 自动启, host 显式调

### 4. `DeepAgentRuntime` 集成 — 启动时启 BankServer

Env opt-in:
- `DEEPAGENTS_TALON_EXPOSE_BANK=true` → 启 BankServer
- `DEEPAGENTS_TALON_BANK_PORT=8888` → 自定义端口

`runtime.bankServer()` getter 暴露 server.

### 5. `BankServerTest` (9 tests) — R244.2 新增

- `healthzReturnsOk`
- `recallReturnsRankedUnits`
- `recallAllKindsMergesAcrossKinds`
- `touchBumpsUsesAndUtility`
- `recordOutcomeUpdatesCounts`
- `statsReportsSizeAndCounts`
- `unknownIdReturns404`
- `startStopIsIdempotent`
- `talonSelfReflectWiringStartsServer`

---

## Tests (9 new)

| 套件 | tests | 覆盖 |
|---|---:|---|
| `BankServerTest` | 9 | healthz / recall / touch / record-outcome / stats / 404 / idempotent / wiring 集成 |

### Cumulative

```
aethercode-deepagents: 218 tests  (R241.2 + R241.3 + R243.1 + R243.2 + R243.3 + R244.1 + R244.2 + 之前累积)
                        0 fail
aethercode-talon:        5 tests  (R243.2B)
                        0 fail
```

---

## 实战 (R244.2 之后)

### Daemon (aethercode-talon) 启用 bank exposure

```bash
DEEPAGENTS_TALON_EXPOSE_BANK=true \
DEEPAGENTS_TALON_BANK_PORT=7777 \
java -jar aethercode-talon-0.1.0-SNAPSHOT.jar
# → log: "deep-agent bank exposed on http://127.0.0.1:7777"
```

### TUI / desktop / IntelliJ 调 bank

```java
BankClient client = new BankClient(URI.create("http://127.0.0.1:7777"));
if (client.ping()) {
    List<Map<String, Object>> top = client.recallAllKinds(3);
    for (Map<String, Object> u : top) {
        System.out.println(u.get("taskKind") + ": " + u.get("fixStrategy"));
    }
    // Push self-eval back
    client.recordOutcome("u1", true);
}
```

### Java 进程内 (host 集成)

```java
TalonSelfReflectWiring.Result wiring = TalonSelfReflectWiring.build(
    assistantDir, chatClient, env);
BankServer server = TalonSelfReflectWiring.startBankServer(wiring);
// ... runtime loop ...
server.stop();  // 显式停
```

---

## Files

### NEW (R244.2)

- `aethercode-deepagents/.../selfimprove/BankServer.java` (14.9 KB)
- `aethercode-deepagents/.../selfimprove/BankClient.java` (8.3 KB)
- `aethercode-deepagents/.../test/.../selfimprove/BankServerTest.java` (9 tests)

### MOD (R244.2)

- `aethercode-deepagents/.../selfimprove/TalonSelfReflectWiring.java` (+ `startBankServer` helper)
- `aethercode-talon/.../runtime/DeepAgentRuntime.java` (+ BankServer 启动 + bankServer() getter + stop)

---

## 设计决定 (R244.2)

1. **JDK `com.sun.net.httpserver.HttpServer` 而非 Spring/Jetty/Netty** — 零新依赖, ~30 KB 类路径
2. **不走 aethercode-a2a (避免反依赖)** — A2A 是 task-oriented, bank 是 data-plane CRUD
3. **单端口 7777** — 简单, env 可改
4. **localhost-only** — 跨 surface 用, 不暴露公网
5. **opt-in 启用** — `DEEPAGENTS_TALON_EXPOSE_BANK=true` 才启, 默认不暴露
6. **4-thread daemon pool** — server 不会阻止 JVM shutdown
7. **handler exception → JSON 500** — 不让一个坏 request kill server
8. **`start(0)` ephemeral port** — tests 用, 避免端口冲突
9. **`stop()` 幂等** — runtime.stop() 多次调用安全
10. **wire format 复用 `toMap()`** — 跟 R244.1 一致, 跨 surface client 直接 deserialize

---

## What did NOT ship in R244.2 (deferred to R244.3+)

1. **TUI 实际集成** — aethercode-tui/ 跨语言, 需要写 TypeScript BankClient
2. **Desktop 实际集成** — aethercode-desktop/ 跨语言, 需要写 TS / Rust BankClient
3. **IntelliJ 插件集成** — 需要写 IntelliJ plugin BankClient
4. **TLS / auth** — 当前纯 localhost, 不需要; 暴露公网时再考虑
5. **streaming SSE** — 当前纯 request/response; long-running recall 可以流式返回
6. **write 锁** — 当前 synchronized 简化版; 高并发写需要更精细的 lock
7. **Metrics** — Prometheus exporter 没做; production 需要

---

## 教训 (R244.2)

1. **`Optional` import 漏** — `BankServer.java` 用了 `Optional<ReasoningUnit> updated = bank.touch(id);` 但没 import `java.util.Optional`, javac 报"找不到符号". **结论: 用 record 类型的 wrapper (Optional / List / Map) 要先 grep 实际 import**
2. **`URLEncoder/URLDecoder` 跨包** — 在 `java.net`, 不是 `java.nio`. 之前在 `java.nio.charset.StandardCharsets` 旁边 import 漏了. **结论: 写 query parser 一次性 import 全部 `java.net.*` + `java.net.URLEncoder/URLDecoder`**
3. **Constructor `this.baseUri =` 双赋值** — 我先 `this.baseUri = Objects.requireNonNull(...)` 又在 strip slash 后 `this.baseUri = URI.create(...)`. javac 报"可能已分配变量 baseUri". 修: 第一次只存 local var, 最后一次性 `this.baseUri = ...`. **结论: strip-normalize-this 不要在 init list 里**
4. **aethercode-talon test 单跑找不到 deepagents class** — 没 install 到 m2 时 cross-module 引用编译失败. **结论: 跨 module test 永远用 `-am`**
5. **HTTP 简单协议更适合 data-plane CRUD** — A2A task-oriented 协议不适合塞 bank 操作, 单独 small HTTP surface 更好
6. **JDK `com.sun.net.httpserver` 在 classpath 模式 OK** — 不是 module 模式时不需要 explicit requires

---

## 关键文件 SHA / 路径

**新增 3 java** (在 `aethercode-deepagents/.../selfimprove/`):
- `BankServer.java` (14.9 KB)
- `BankClient.java` (8.3 KB)
- `BankServerTest.java` (7.3 KB, 9 tests)

**修改 2 java**:
- `TalonSelfReflectWiring.java` (+ `startBankServer`)
- `aethercode-talon/.../runtime/DeepAgentRuntime.java` (+ BankServer 启动 + getter + stop)

**报告**:
- `doc/项目文档/R244-2-CROSS-SURFACE-BANK-HTTP.md` (本文)
- 父报告: `doc/项目文档/R244-1-SELF-EVAL-CONFIDENCE-METRIC.md`
- 祖报告: `doc/项目文档/R243-3-DRIFT-BANK-TO-AGENTS.md`
- 祖: `doc/项目文档/R241-EXPERIENCESTORE-STRATEGY-LIBRARY.md`

**Build artifacts**:
- `~/.m2/repository/org/aethercode/aethercode-deepagents/0.1.0-SNAPSHOT/aethercode-deepagents-0.1.0-SNAPSHOT.jar`
- `~/.m2/repository/org/aethercode/aethercode-talon/0.1.0-SNAPSHOT/aethercode-talon-0.1.0-SNAPSHOT.jar`

---

## Backups

- R241.2/R241.3/R243.1/R243.2/R243.2B/R243.3/R244.1 报告保持原样
- R244.2 是 R239 路线图 O-10 跨 surface 维度第一个 round
- 出包策略: 跟前 11 round 一起下次 release 时整体出 0.2.58

---

## Next (R239 路线图剩余)

1. **R244.3 实际 TUI/desktop 集成** — 估 1-2 round
2. **出包 0.2.58** — 1 round, 工程里程碑
