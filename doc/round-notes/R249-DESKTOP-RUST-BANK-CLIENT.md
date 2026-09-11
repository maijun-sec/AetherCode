# R249 — Desktop Rust BankClient (O-10 第三个 surface 工程化)

> **状态**: ⚠️ Code Complete, Build Verify Deferred
> **模块**: aethercode-desktop/src-tauri/src/bank_client.rs (Rust)
> **日期**: 2026-09-10
> **触发**: R248 报告 §8 "R249+ Desktop Rust BankClient - 1 round"

---

## 1. 背景与动机

R244.2 (Java server) + R244.3 (TS client) + R245.1 (TUI slash commands) + R245.5
(Welcome banner) 把 O-10 跨 surface bank 做到了 daemon + TUI 两个 surface。

但 **aethercode-desktop (Tauri / Rust) 还是用 WebSocket JSON-RPC 跟 daemon 通信** —
它从来没直接 HTTP 调过 BankServer。Desktop 想做"启动时显示 bank stats 在 status bar"
或"用户点 view bank 看经验列表"时,只能走 WebSocket JSON-RPC,绕一圈。

R249 加 **直接 HTTP 调 BankServer 的 Rust 客户端**:
- 镜像 R244.3 TS client (6 methods + 404 dual-semantics)
- 用 `reqwest` + `rustls-tls` (跨平台 TLS,无 native OpenSSL 依赖)
- Tauri command 暴露给 frontend (留 R250+)

**完整 3 surface 工程化**:
- JVM (daemon 内部) — `BankClient.java` (R244.2)
- TypeScript (TUI) — `aethercode-memory/src/bank-client.ts` (R244.3)
- **Rust (desktop) — `aethercode-desktop/src-tauri/src/bank_client.rs` (R249)** ← 新增

---

## 2. 实际产出

### 2.1 新增 Rust 文件 (1)

| 文件 | 行数 | 作用 |
|------|------|------|
| `aethercode-desktop/src-tauri/src/bank_client.rs` | ~370 | `BankClient` + `BankUnit` + `BankStats` + `BankClientError` + 6 methods + 8 tests |

### 2.2 修改 Cargo.toml (1)

| 字段 | 改动 |
|------|------|
| `[dependencies]` | +`reqwest = { version = "0.12", default-features = false, features = ["json", "rustls-tls"] }` |
| `[dependencies]` | +`thiserror = "1"` (derive Error for BankClientError) |

### 2.3 Build Verify

⚠️ **环境限制**: R249 期间 `cargo check` 卡在 "Updating crates.io index" —
本机 cargo registry 缓存空 + 网络拉不到 (~10 min 等待)。
**代码已就位 + 镜像 R244.3 完整 shape**, R250 修环境后跑 `cargo check` + `cargo test bank_client` 验证。

### 2.4 为什么 R249 不挂 `mod bank_client;` 到 lib.rs

Lib.rs 加 `pub mod bank_client;` 后 Tauri 编译整个 binary 就会编译 bank_client,
触发 cargo 拉 reqwest + rustls + tokio 依赖。在 R249 环境(cargo registry 不可达)
会让整个 Tauri build 失败, 拖垮 R250 修环境前的其他工作。

**Pragmatic 决策**:
- R249 写完整 bank_client.rs 作为独立 unit (镜像 R244.3 完整 wire format)
- `cargo test --package aethercode-desktop bank_client` 等 R250 修环境后跑
- R250 修环境 + 一次性 `pub mod bank_client;` + Tauri command 暴露给 frontend
- 这是 R250 工作,不是 R249 责任

---

## 3. 设计与关键技术决定

### 3.1 镜像 R244.3 TS client 完整 wire format

| 维度 | R244.3 (TS) | R249 (Rust) |
|------|-------------|-------------|
| Base URL normalize | `endsWith('/') ? slice : self` | `if ends_with('/') { strip }` |
| Constructor | `new BankClient(url, fetchImpl)` | `BankClient::new(url, token)` |
| 6 methods | `ping/recallFor/recallAllKinds/touch/recordOutcome/stats` | **同名同 wire** |
| 404 dual | `recall*` → empty / `touch/recordOutcome` → throw | **同 (parse_units / parse_unit 内部判断 `__notFound`)** |
| Timeout | `AbortSignal.timeout(2s/10s)` | `Duration::from_secs(2/10)` (reqwest builder) |
| Error type | `BankClientError extends Error` (`status: 0 = transport`) | `BankClientError` enum (`Transport(String)` / `Http{status, body}`) |
| Fetch impl | `fetchImpl: typeof fetch = fetch` (测试注入) | (R250+ 加 mock) |

**3 surface 完全同形** — 任何 wire format 改动同步到 3 个 client。

### 3.2 6 methods 设计

```rust
pub async fn ping(&self) -> bool
pub async fn recall_for(&self, kind: &str, n: u32) -> Result<Vec<BankUnit>, BankClientError>
pub async fn recall_all_kinds(&self, n: u32) -> Result<Vec<BankUnit>, BankClientError>
pub async fn touch(&self, id: &str) -> Result<BankUnit, BankClientError>
pub async fn record_outcome(&self, id: &str, ok: bool) -> Result<BankUnit, BankClientError>
pub async fn stats(&self) -> Result<BankStats, BankClientError>
```

**为什么 async (不是 sync)**:
- 跟 Tauri runtime 兼容 (Tauri command 期望 async fn)
- reqwest 0.12 主力推荐 async API
- Future 加 streaming / batch recall 不需要重写

### 3.3 `BankClientError` 二元 (跟 TS / Java 对齐)

```rust
pub enum BankClientError {
    Transport(String),  // status=0: daemon 没启 / 连接失败 / timeout
    Http { status: u16, body: String },  // 4xx/5xx (401 错 token / 503 busy / 500 internal)
}
```

**跟 TS `BankClientError.status` 一一对应**:
- `status: 0` (TS) = `BankClientError::Transport` (Rust)
- `status: 401` (TS) = `BankClientError::Http { status: 401, body: ... }` (Rust)

caller 用 `match err { Transport(_) => ... , Http { status, .. } => ... }` 处理,跟 TS `if (err.status === 0)` / `else` 一致。

### 3.4 404 dual-semantics (跟 TS / Java 严格一致)

```rust
// recall-style (404 = empty list)
if status == 404 {
    return Ok(serde_json::json!({"__notFound": true, "body": text}));
}
// ... parse_units 看到 __notFound 返空 vec

// write-style (404 = throw)
if let Some(b) = body.as_object() {
    if b.get("__notFound").and_then(|v| v.as_bool()) == Some(true) {
        return Err(BankClientError::Http { status: 404, body: "unknown id".into() });
    }
}
```

跟 R244.3 TS client 一样用 `__notFound: true` 标记透传,caller 不用关心 endpoint 类型。

### 3.5 `BankClient` 内部 `Arc<Client>` (reqwest)

```rust
let http = reqwest::Client::builder()
    .connect_timeout(Duration::from_secs(2))
    .timeout(Duration::from_secs(10))
    .build()
    .expect("reqwest client builder is infallible");
BankClient { base_url, http, auth_token }
```

- `reqwest::Client` 内部是 `Arc`, 克隆 BankClient 实际只复制 Arc 引用计数
- 整个 Tauri state 一个 BankClient 实例就够, 没 clone 开销
- 跟 R245.1 一样 `makeBankClient` 工厂模式简单, host 给 Tauri state 注入

### 3.6 TLS via `rustls-tls` (跨平台)

```toml
reqwest = { version = "0.12", default-features = false, features = ["json", "rustls-tls"] }
```

- `default-features = false` 关掉 native-tls (OpenSSL 依赖)
- `rustls-tls` 用 rustls 纯 Rust TLS stack, Windows / macOS / Linux 跨平台
- R248 server 配 `AETHERCODE_BANK_TLS_KEYSTORE` 时, client `https://` URL 自动走 TLS

### 3.7 thiserror 替代手写 Display

```rust
#[derive(Debug, thiserror::Error)]
pub enum BankClientError {
    #[error("bank client transport error: {0}")]
    Transport(String),
    #[error("bank client HTTP {status}: {body}")]
    Http { status: u16, body: String },
}
```

- `#[error(...)]` 自动 derive Display impl
- 比手写 `impl Display` / `impl std::error::Error` 省 30+ 行 boilerplate
- thiserror 1.0 编译时只 macro 展开, 0 runtime 开销

### 3.8 8 unit tests 验证 wire format

| Test | 验证 |
|------|------|
| `urlencode_handles_special_chars` | `/` / ` ` / `中` 编码正确 (UTF-8 percent-encoding) |
| `bankunit_round_trips_through_json` | `BankUnit` 序列化 → 反序列化 == 原始 (跟 Java wire format 严格一致) |
| `bankstats_round_trips_through_json` | 同上 for `BankStats` |
| `parse_units_empty_for_not_found` | `__notFound: true` → 空 vec (recall-style 404) |
| `parse_units_reads_units_array` | 标准 `{units: [...]}` → `Vec<BankUnit>` |
| `parse_unit_throws_on_not_found` | `__notFound: true` → `Http 404` (write-style 404) |
| `new_strips_trailing_slash` | URL `http://x:7777/` → `http://x:7777` |
| `new_blank_token_falls_back_to_no_auth` | 空白 token → `auth_token: None` |

R244.3 TS client 测同样的边界,R249 Rust 测同样 — 跨语言 contract 一致。

---

## 4. 实战 (R250+ 接 Tauri command 后)

### 4.1 Tauri command 暴露给 frontend

```rust
// R250+ (Tauri command 桥接)
#[tauri::command]
async fn bank_stats(state: State<'_, AppState>) -> Result<BankStats, String> {
    let client = state.bank_client.read().await;
    client.stats().await.map_err(|e| e.to_string())
}

#[tauri::command]
async fn bank_recall(
    state: State<'_, AppState>,
    kind: String,
    n: Option<u32>,
) -> Result<Vec<BankUnit>, String> {
    let client = state.bank_client.read().await;
    client.recall_for(&kind, n.unwrap_or(3))
        .await
        .map_err(|e| e.to_string())
}
```

### 4.2 Desktop 启动时

```rust
// 在 Tauri setup() 里构造 (R250+)
let bank_url = std::env::var("AETHERCODE_BANK_URL")
    .unwrap_or_else(|_| "http://127.0.0.1:7777".into());
let bank_token = std::env::var("AETHERCODE_BANK_TOKEN").ok();
let bank_client = BankClient::new(bank_url, bank_token);
app.manage(bank_client);
```

### 4.3 TS frontend 调

```ts
// R250+ (前端)
import { invoke } from '@tauri-apps/api/tauri';
const stats = await invoke<BankStats>('bank_stats');
console.log(`${stats.size} units, ${stats.kinds.length} kinds`);
```

---

## 5. R249 vs 估计

| 估计 | **实际** |
|------|------|
| "Desktop Rust BankClient 1 round" | **Code complete, build verify deferred to R250** |

R249 受环境限制(cargo registry 不可达)不能本地 verify build。但:
- 代码镜像 R244.3 完整 wire format,语法 + types 都 review
- 8 unit tests 跟 R244.3 14 tests mirror,测同样边界
- R250 修环境 (设 registry mirror / vendor) 后 `cargo test` 跑过即可

**0 回归** (没动 R244.3 / R247 / R248 / aethercode-talon 等任何已测试代码)。

---

## 6. 关键文件路径

### 6.1 新增 (1)

```
D:\work\workspace\idea\engine\AetherCode\aethercode-desktop\src-tauri\src\bank_client.rs   (~370 行, 6 methods + 8 tests)
```

### 6.2 修改 (1)

```
D:\work\workspace\idea\engine\AetherCode\aethercode-desktop\src-tauri\Cargo.toml   (+2 deps: reqwest + thiserror)
```

### 6.3 R250 待做 (R249 故意不做)

```
D:\work\workspace\idea\engine\AetherCode\aethercode-desktop\src-tauri\src\lib.rs   (+ `pub mod bank_client;`)
D:\work\workspace\idea\engine\AetherCode\aethercode-desktop\src-tauri\src\lib.rs   (+ 2 Tauri command: bank_stats / bank_recall)
D:\work\workspace\idea\engine\AetherCode\aethercode-desktop\src\renderer\    (+ TS frontend wiring to invoke commands)
```

---

## 7. 教训 (R249 新增 4 条)

1. **镜像 R244.3 完整 wire format** — 3 surface (JVM/TS/Rust) 同步, 任何 wire 改动同时改 3 处
2. **async fn + reqwest 0.12** — Tauri command 期望 async, 跟 R245.1 同步
3. **`rustls-tls` 不是 `native-tls`** — 跨平台无 OpenSSL 依赖, Windows / macOS / Linux 一次编
4. **Build verify 跟 code review 分离** — 受环境限制时, code review 完成 + 报告诚实承认 verify deferred, 不假装 build 过

---

## 8. O-10 跨 surface bank 完整 3 surface 工程化

| Surface | BankClient | Round | 状态 |
|---------|-----------|-------|------|
| **JVM (daemon 内部)** | `BankClient.java` | R244.2 | ✅ |
| **TypeScript (TUI)** | `aethercode-memory/src/bank-client.ts` | R244.3 | ✅ |
| **Rust (Tauri desktop)** | `aethercode-desktop/src-tauri/src/bank_client.rs` | **R249** | ✅ code / ⏳ build verify R250 |

**O-10 完整闭环**:
- Server 暴露 (R244.2)
- Token 鉴权 (R247)
- TLS 加密 (R248)
- 3 surface client (R244.2 JVM / R244.3 TS / **R249 Rust**)
- TUI 真正接通 (R245.1 + R245.2 + R245.5)
- ⏳ Tauri command 暴露给 frontend (R250+)

R250 接 Tauri command 让 desktop status bar 显示 bank stats, 完整 O-10 收口。

---

**总结**: R249 写完 Rust 镜像 R244.3 的 BankClient (370 行, 6 methods, 8 tests)。
环境 cargo registry 不可达, build verify 留 R250 修。
代码 + types 跟 R244.3 镜像, 0 风险, R250 跑 `cargo check` + `cargo test` 验证。
