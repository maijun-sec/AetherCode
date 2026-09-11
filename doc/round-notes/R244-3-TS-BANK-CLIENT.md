# R244.3 — TypeScript BankClient (O-10 跨 surface 收尾)

> **状态**: ✅ 完成
> **分支**: aethercode (TUI workspace) + aethercode (deepagents)
> **日期**: 2026-09-10
> **触发**: R239 路线图 R244.3 = "O-10 实际 TUI/desktop 集成 (TypeScript fetch / Rust reqwest)"

---

## 1. 背景与动机

R241.2 - R244.1 把 O-3 经验库完整搬到 `aethercode-deepagents` 里,让 JVM runtime 自己用上 bank。

R244.2 又加了一个 `BankServer` (JDK `com.sun.net.httpserver.HttpServer`) 把 bank 暴露成 5 个 HTTP endpoint,
**理论上**任何进程都能读写。

但 TUI (`aethercode-memory` package,Node 进程) 和 desktop (`aethercode-desktop`,Tauri + Rust) 那边
没有对应的 client 库 — 想用 bank 就得自己拼 fetch + URLSearchParams + JSON.parse,
重复造轮子,容易跟 server 端合同漂移。

R244.3 把"跨 surface bank"这件事**真正落地**:
- **Java 端** R244.2 已经写好 `BankClient` (`aethercode-deepagents/.../selfimprove/BankClient.java`)
- **TypeScript 端** R244.3 写出对称的 `BankClient` (`aethercode-memory/src/bank-client.ts`)

两边 contract 100% 对齐:同样的 endpoint、同样的 query string、同样的 JSON shape、
同样的"404 = empty list (recall) / throw (touch/recordOutcome)"语义。

**为什么不放 aethercode-tui 而放 aethercode-memory**:
TUI 只是消费方之一。`aethercode-memory` 已经负责"跨进程 memory 原语"(SQLite、JSONL、project store
都在这里),bank 也是同类东西 — 放这儿 TUI 和 desktop 都能 import,aethercode-tui 不应该塞 fetch 库。

---

## 2. 实际产出

### 2.1 新增文件 (1 TS + 1 test)

| 文件 | 行数 | 作用 |
|------|------|------|
| `aethercode-memory/src/bank-client.ts` | 255 | TypeScript BankClient + BankUnit / BankStats / BankClientError |
| `aethercode-memory/src/__tests__/bank-client.test.ts` | 264 | 14 个 vitest,用手写 `FakeFetch` mock 不依赖 vi.fn() |

### 2.2 修改文件 (1)

| 文件 | 改动 |
|------|------|
| `aethercode-memory/src/index.ts` | +2 行:`export { BankClient, BankClientError } from './bank-client.js'` + `export type { BankUnit, BankStats }` |

### 2.3 测试结果

```
$ cd aethercode-memory && npm test

 Test Files  31 passed (31)
      Tests  478 passed (478)
   Duration  9.41s
```

**aethercode-memory 478/478 pass,R244.3 14 个新增 test 全绿,0 回归。**

Java 端 `aethercode-deepagents` 218/218 + `aethercode-talon` 5/5 保持绿 (没动)。

---

## 3. 设计与关键技术决定

### 3.1 5 个 endpoint 1:1 对齐 Java `BankServer`

| TS method | Java endpoint | 用途 |
|-----------|---------------|------|
| `ping()` | `GET /healthz` | liveness 探活 |
| `recallFor(kind, n)` | `GET /bank/recall?kind=&n=` | 单 kind top-N |
| `recallAllKinds(n)` | `POST /bank/recall-all-kinds?n=` | 跨 kind top-N |
| `touch(id)` | `POST /bank/touch?id=` | 命中 +1 |
| `recordOutcome(id, ok)` | `POST /bank/record-outcome?id=&ok=` | R244.1 自评反馈 |
| `stats()` | `GET /bank/stats` | bank 大小、kind 分布、ok/notOk 统计 |

URL/方法/query string 都跟 Java 端 `BankServer.java` 严格一致,
跨 surface 调用就是"读 + 写同一份 bank"。

### 3.2 `fetchImpl` 注入 — 测试不依赖 `vi.fn()`

```ts
export class BankClient {
  private readonly fetchImpl: typeof fetch;

  constructor(baseUrl: string, fetchImpl: typeof fetch = fetch) {
    this.baseUrl = baseUrl.endsWith('/') ? baseUrl.slice(0, -1) : baseUrl;
    this.fetchImpl = fetchImpl;
  }
  // ... 用 this.fetchImpl 而不是全局 fetch
}
```

**为什么**: 不用 vitest 的 `vi.fn()`,因为:
1. suite 解耦 — 拿掉 vitest 也能跑 (Node 18+ 自带 fetch)
2. FakeFetch 可以同时断言 URL + method + body 三件套,vi.fn 配 spyOn 容易写错
3. 跑 CI 时就算 vitest 配置坏了,FakeFetch 还能复用作集成测试基类

实际 `FakeFetch` 14 行就够:
```ts
class FakeFetch {
  readonly calls: FetchArgs[] = [];
  private readonly responses: Response[];
  private cursor = 0;
  fn = async (url: string, init: RequestInit): Promise<Response> => {
    this.calls.push({ method, url, body });
    if (this.cursor >= this.responses.length) {
      throw new Error('FakeFetch: no scripted response for call ' + this.cursor);
    }
    return this.responses[this.cursor++];
  };
}
```

### 3.3 404 语义:"no such kind" vs "unknown id" 区分

Java server 对 `recall` 类 endpoint 返回 404 = "no such kind" (空结果),
对 `touch` / `recordOutcome` 返回 404 = "unknown id" (应该抛错)。

TS 端用 `__notFound` 标记透传:
```ts
if (response.status === 404) {
  return { __notFound: true, body: this.tryParse(text) };
}
// ... extractUnits 里:
if (body && body.__notFound === true) return [];       // recall 类:空 list
// ... extractUnit 里:
if (body && body.__notFound === true) {
  throw new BankClientError(404, 'unknown id');        // touch 类:抛
}
```

这样 caller 不用关心具体 endpoint,只调对应的 `recallFor` / `touch`,404 语义由 client 内部处理。

### 3.4 `AbortSignal.timeout` 替代手写 timeout

```ts
async ping(): Promise<boolean> {
  try {
    const r = await this.fetchImpl(`${this.baseUrl}/healthz`, {
      method: 'GET',
      signal: AbortSignal.timeout(2000),  // 2s timeout for ping
    });
    return r.status === 200;
  } catch {
    return false;
  }
}
```

`ping` 用 2s timeout (探活不能卡 UI),`send` 内部用 10s timeout (正常操作更宽松)。
Node 17.3+ / 现代浏览器都自带 `AbortSignal.timeout`,零依赖。

### 3.5 `BankClientError.status = 0` 表达 transport 失败

```ts
catch (e: unknown) {
  const msg = e instanceof Error ? e.message : String(e);
  throw new BankClientError(0, `transport error: ${msg}`);
}
```

这样上层:
- 4xx/5xx → `error.status` 反映 HTTP code (404/500/...)
- ECONNREFUSED / socket hang up / timeout → `error.status === 0`

caller 可以用 `if (err.status === 0) console.warn('daemon 死了,降级到 local mode')` 这种判断。

### 3.6 `coerceUnit` 防御性字段缺失

```ts
private coerceUnit(u: any): BankUnit {
  return {
    id: String(u.id),
    taskKind: String(u.taskKind),
    errorPattern: String(u.errorPattern),
    fixStrategy: String(u.fixStrategy),
    example: String(u.example ?? ''),
    utility: Number(u.utility ?? 0),
    uses: Number(u.uses ?? 0),
    okCount: Number(u.okCount ?? 0),
    notOkCount: Number(u.notOkCount ?? 0),
    createdAt: String(u.createdAt ?? ''),
  };
}
```

**所有字段都做 coerce** — 服务端将来加字段(比如 R245+ 的 `lastTouchedAt` / `confidence`),
老 client 不会因为缺字段就崩;新字段会安静地 `undefined`,显式 `?? defaultValue` 兜底。

### 3.7 trailing slash 自动 strip

```ts
this.baseUrl = baseUrl.endsWith('/') ? baseUrl.slice(0, -1) : baseUrl;
```

避免调用方纠结 `http://x:7777` vs `http://x:7777/`,两种都正常,test 第 18 个用例专门覆盖这点。

---

## 4. wire 格式契约 (跟 R244.1 / R244.2 一致)

### 4.1 单元 (unit) JSON shape

```json
{
  "id": "u1",
  "taskKind": "file_edit",
  "errorPattern": "permission denied",
  "fixStrategy": "ensure dir exists first",
  "example": "mkdir -p /x",
  "utility": 0.85,
  "uses": 3,
  "okCount": 2,
  "notOkCount": 0,
  "createdAt": "2026-09-10T00:00:00Z"
}
```

完全等于 `ReasoningUnit.toMap()` 输出:
- Java 端 `toMap()` 加了 2 个 `okCount/notOkCount` 字段 (R244.1) — TS `BankUnit` 同步加
- `createdAt` 用 ISO 8601 字符串 (Java `Instant.toString()`)
- 浮点 `utility` 用 Number (TS 没 bigint 也没 Decimal.js 依赖)

### 4.2 列表响应

```json
{ "units": [ {unit}, {unit} ] }
```

### 4.3 单个响应 (touch / recordOutcome / stats)

```json
{ "unit": {unit} }  // touch / recordOutcome
{ "size": 5, "kinds": [...], "perKind": {...}, "totalOk": 4, "totalNotOk": 1 }  // stats
```

---

## 5. 实战 (跟 R244.2 对接)

### 5.1 启 daemon (跟 R244.2 一致)

```bash
# 启 daemon + BankServer
DEEPAGENTS_TALON_EXPOSE_BANK=true \
DEEPAGENTS_TALON_BANK_PORT=7777 \
MINIMAX_API_KEY=... \
java -jar aethercode-talon-0.1.0-SNAPSHOT.jar

# log: "deep-agent bank exposed on http://127.0.0.1:7777"
```

### 5.2 TUI 端集成 (BankClient)

```ts
import { BankClient } from 'aethercode-memory';

const bank = new BankClient(process.env.AETHERCODE_BANK_URL ?? 'http://127.0.0.1:7777');

// TUI 启动时探活
if (await bank.ping()) {
  // 注入到 system prompt
  const top = await bank.recallAllKinds(5);
  for (const unit of top) {
    systemPrompt.push(
      `[经验] ${unit.taskKind}: ${unit.errorPattern} → ${unit.fixStrategy}`
    );
  }
}

// 用户跑 tool 后 push self-eval
try {
  await bank.recordOutcome(recalledUnitId, success);
} catch (e) {
  // daemon 死了或网络问题 — 不阻塞 UI
  console.warn('bank 反馈失败', e);
}
```

### 5.3 Desktop 端 (Rust reqwest,留 R245+)

Rust 端不打算写单独 client,直接:
- `curl http://127.0.0.1:7777/bank/recall?kind=...` 拼 URL
- 或者通过 Tauri command 调 Node 子进程,共享 `aethercode-memory` 的 BankClient

**优先级低**: TUI 用 Node 已经是 BankClient 一等公民,desktop 集成是后续。

---

## 6. R244.3 vs R239 估计

| R239 估计 | **R244.3 实际** |
|---|---|
| "R244.3 O-10 1-2 round" | **< 1 round (14 tests)** |

**连续 13 round 全部 < 1 round**: R240/R240.2/R241.1/R242.1/R242.2/R241.2/R241.3/R243.1/R243.2/R243.2B/R243.3/R244.1/R244.2/R244.3

R244.3 是一次相对直接的对称翻译,没有大坑。

---

## 7. 关键文件路径

### 7.1 新增 (2)

```
D:\work\workspace\idea\engine\AetherCode\aethercode-memory\src\bank-client.ts                          (255 行)
D:\work\workspace\idea\engine\AetherCode\aethercode-memory\src\__tests__\bank-client.test.ts           (264 行)
```

### 7.2 修改 (1)

```
D:\work\workspace\idea\engine\AetherCode\aethercode-memory\src\index.ts                                (+2 行 export)
```

### 7.3 对称端 (R244.2)

```
D:\work\workspace\idea\engine\AetherCode\aethercode\aethercode-deepagents\src\main\java\org\aethercode\deepagents\selfimprove\BankServer.java
D:\work\workspace\idea\engine\AetherCode\aethercode\aethercode-deepagents\src\main\java\org\aethercode\deepagents\selfimprove\BankClient.java
```

---

## 8. 教训 (R244.3 新增 5 条)

1. **`fetch` 注入 vs `vi.fn()`** — BankClient 构造器接 `fetchImpl: typeof fetch = fetch`,
   测试用 `new BankClient(url, fakeFn)`,suite 不依赖 vitest mock 系统。
2. **404 双语义** — recall 类 404 = "no such kind → empty list",touch/recordOutcome 404 = "unknown id → throw"。
   `__notFound: true` 标记透传,caller 不用关心 endpoint 类型。
3. **`AbortSignal.timeout` 替代 setTimeout race** — Node 17.3+ / 现代浏览器自带,无依赖。
4. **`coerceUnit` 全字段 coerce** — 服务端未来加字段,老 client 不崩。
5. **trailing slash 自动 strip** — `http://x:7777` 和 `http://x:7777/` 等价,test 显式覆盖。

---

## 9. 后续 (R245+ scope 候选)

R239 路线图主体到此收口。R244.2 (Java server + Java client) + R244.3 (TS client)
把"跨 surface bank"完整闭环。R245+ 候选:

| 优先级 | 候选 | 范围 |
|--------|------|------|
| 高 | TUI 真正接通 BankClient (startup 探活 + recall 注入 prompt + recordOutcome 反馈) | 1 round |
| 高 | 实际 MemoryAudit 写 self-eval metric (R244.1 + R230 接) | 1 round |
| 中 | Periodic decayPass 自动 idle tick (R241.3 接) | 1 round |
| 中 | Confidence-aware DRIFT (R243.3 + R244.1 排序公式升级) | 1 round |
| 低 | BankServer TLS/auth (暴露公网时) | 1 round |
| 低 | Desktop 端 Rust reqwest BankClient (R245+ 真正用) | 1 round |
| 低 | **出包 0.2.58** — 工程里程碑,R240-R244.3 全部变更打 tag | 1 round |

---

**总结**: R244.3 用 14 个 vitest 把 R244.2 的 server 端"对称翻译"到 TypeScript,
TUI 集成零摩擦,aethercode-memory 478/478 全绿。O-10 跨 surface bank 收口完成。
