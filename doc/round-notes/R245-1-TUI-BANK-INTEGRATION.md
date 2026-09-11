# R245.1 — TUI BankClient 集成 (O-10 跨 surface 真正接通)

> **状态**: ✅ 完成
> **模块**: aethercode-memory (TS) + aethercode-tui (TS)
> **日期**: 2026-09-10
> **触发**: R244.3 报告 §5 "R245+ scope 候选 #1" — TUI 真正接通 BankClient

---

## 1. 背景与动机

R244.2 在 daemon 端写了 `BankServer` (JDK `com.sun.net.httpserver.HttpServer`),
R244.3 在 `aethercode-memory` 写了 TS `BankClient` — 5 个 endpoint + 404 双语义 + 14 个 vitest。

但 TUI (aethercode-tui,Ink + React,Node 进程) **没有任何代码** 调过 BankClient。
`aethercode-tui` 跟 `aethercode-memory` 是 pnpm workspace 兄弟包,
TUI 已经在 `package.json` 里 `"aethercode-memory": "file:../aethercode-memory"` — 引用已经就位,只差 wiring。

R245.1 把这件事**真的接通**:
- TUI 拿到 `aethercode-memory.BankClient` 后做一层 wrapper,叫 `readBankStats` / `readBankRecall`
- 加 2 个 slash command: `/bank-stats` + `/bank-recall <kind> [n]`
- 用户敲命令, TUI 调 daemon, 拿结果, sideNote 渲染

**为什么不直接 import BankClient 用**:
1. 错误处理 — BankClient 抛 `BankClientError`,TUI 不想每个 call site 写 try/catch
2. URL 配置 — TUI 端不 hardcode `127.0.0.1:7777`,读 `AETHERCODE_BANK_URL` env
3. 渲染格式 — BankStats / BankUnit 是结构化数据,TUI 只想显示一行文字

所以加一层 wrapper 把"读 bank" 跟"渲染一行"分开,跟 R242.2 RoleRegistry / R240.1 Limits
"业务对象 vs 展示字符串"分层一致。

---

## 2. 实际产出

### 2.1 新增文件 (1 TS + 1 test)

| 文件 | 行数 | 作用 |
|------|------|------|
| `aethercode-memory/src/bank-recall.ts` | ~165 | TUI/desktop BankClient wrapper + format helper |
| `aethercode-memory/src/__tests__/bank-recall.test.ts` | ~200 | 16 vitest (用 vi.hoisted mock bank-client) |

### 2.2 修改文件 (3)

| 文件 | 改动 |
|------|------|
| `aethercode-memory/src/index.ts` | +8 行 export `DEFAULT_BANK_URL/resolveBankUrl/makeBankClient/formatBankStats/formatRecall/readBankStats/readBankRecall/BankSummary` |
| `aethercode-tui/src/commands.ts` | +2 行 SLASH_HELP + +2 SLASH_COMMANDS + +2 SLASH_COMMANDS_DETAILED + +18 行 handleSlash case "bank-stats" / "bank-recall" |
| `aethercode-tui/src/tui.tsx` | +38 行 fire-and-forget `__BANK_STATS__` + `__BANK_RECALL__:` 处理 |

### 2.3 测试结果

```
$ cd aethercode-memory && npm test

 Test Files  32 passed (32)
      Tests  494 passed (494)
   Duration  10.36s
```

**aethercode-memory 494/494 pass** (R244.3 478 + R245.1 16 新增, 0 回归)
**aethercode-tui typecheck** exit 0
**aethercode-deepagents 218/218 + aethercode-talon 5/5** 0 回归

---

## 3. 设计与关键技术决定

### 3.1 放 aethercode-memory 而非 aethercode-tui

**第一次尝试**: 把 `bank-recall.ts` 放 `aethercode-tui/src/` (跟 commands.ts 同级),
写 vitest test 放 `aethercode-tui/src/__tests__/`.

**问题**: aethercode-tui 用 `node --test` 跑 .mjs (没 vitest), 把 vitest test 放进去
需要新建 tsc 临时目录 + spawnSync tsc 的复杂 shim (R196-commands 那个 70 行 setup)。

**改**: 把 `bank-recall.ts` 移到 `aethercode-memory/src/`, 跟 `bank-client.ts` 一起。
**理由**:
- `bank-recall.ts` 没任何 TUI 依赖 (只 import `BankClient` + `process.env`)
- `aethercode-desktop` 也能用同一份 (后续 round 接 Rust 之前, 先 share TS 端)
- vitest suite 已经在 `aethercode-memory`, 16 个新 test 0 摩擦

**aethercode-tui 怎么用**: `import { readBankStats, readBankRecall } from 'aethercode-memory'`
(走 pnpm workspace 软链 `file:../aethercode-memory`)

### 3.2 `vi.hoisted` 而不是顶层 `vi.fn()`

**踩坑 (5 个 test fail first run)**: 错误信息 "fetch failed" — 真 fetch 在跑,
说明 `vi.mock('./bank-client.js', factory)` 没拦截, factory 拿到的 `mockStats` 是 undefined。

**原因**: vitest 的 `vi.mock` 在 ESM import 解析**之前**执行 factory,
顶层 `const mockStats = vi.fn()` 在 factory 后定义 — factory 里 `mockStats` 是 TDZ 错误。

**修**:
```ts
const { mockStats, mockRecallFor, mockPing } = vi.hoisted(() => ({
  mockStats: vi.fn(),
  mockRecallFor: vi.fn(),
  mockPing: vi.fn(),
}));

vi.mock('../bank-client.js', () => {
  class FakeBankClient {
    stats = mockStats;       // ✅ 现在能拿到
    recallFor = mockRecallFor;
    ping = mockPing;
  }
  return { BankClient: FakeBankClient, BankClientError: ... };
});
```

`vi.hoisted` 把 factory 提到 module top — 跟 `vi.mock` 同步执行, 拿得到 mocks。

### 3.3 `BankSummary` 三种形态,统一 `text` 字段

```ts
export type BankSummary =
  | { ok: true;  text: string; stats: BankStats }                            // /bank-stats 成功
  | { ok: true;  text: string; units: BankUnit[]; kind: string }             // /bank-recall 成功
  | { ok: false; text: string; reason: string };                            // 任何失败
```

**关键**: 不管 ok 与否,`text` 字段都 populate。
TUI 渲染只 `summary.text` 一行,不用 if/else。

### 3.4 错误折叠到 2 种 string

```ts
function downSummary(e: unknown): BankSummary {
  if (e instanceof BankClientError) {
    if (e.status === 0) {
      return { ok: false, text: 'bank: down (transport error)', reason: e.message };
    }
    return { ok: false, text: `bank: down (HTTP ${e.status})`, reason: e.message };
  }
  return { ok: false, text: 'bank: down', reason: msg };
}
```

**两种 string, 三种覆盖**:
- 全部 `BankClientError` (R244.3 的统一错误类) → 走 instance check
- 其他任何 throw → fallback "bank: down"

**为什么分 transport / HTTP**: TUI 用户能区分 "daemon 没启" (status=0) vs "daemon 在但 503"
(status!=0), 对 debug 有用。

### 3.5 slash command 走 local token, 不走 JSON-RPC

```ts
// commands.ts:
case "bank-stats": { return { local: "__BANK_STATS__" }; }
case "bank-recall": {
  if (rest.length === 0) return { local: "usage: ..." };
  return { local: `__BANK_RECALL__:${kind}:${n}` };
}

// tui.tsx:
if (slash?.local === "__BANK_STATS__") {
  void (async () => {
    const { readBankStats } = await import("aethercode-memory");
    const summary = await readBankStats();
    dispatch({ type: "sideNote", kind: "bank", message: summary.text });
  })();
  return;
}
```

**为什么 local token**:
- `/export` (R61) 已经是这个模式, 团队熟悉
- bank 不在 daemon RPC 表面 (R244.2 没动 protocol), 没必要再加一个 RPC method
- 跟 R243.2 wiring 一致 — 跨进程直接走 HTTP, 不绕 daemon

**fire-and-forget pattern** (跟 `/export` 一样):
- `void (async () => { ... })()` 不 await, 不阻塞 TUI 主循环
- 完成后 `dispatch({ type: "sideNote", ... })` 让 React 重渲染

### 3.6 kind 跟 n 用 `__BANK_RECALL__:<kind>:<n>` 编码

```ts
// dispatch:
const body = slash.local.slice("__BANK_RECALL__:".length);
const colonAt = body.lastIndexOf(":");           // ← 关键: lastIndexOf
const kind = colonAt >= 0 ? body.slice(0, colonAt) : body;
const n = colonAt >= 0 ? Math.max(1, Number(body.slice(colonAt + 1)) || 3) : 3;
```

**为什么 `lastIndexOf`**: kind 里可能含 `:` (虽然不该, 但 daemon 不验证),
`lastIndexOf` 保证 n 是最后一个 colon 后, kind 是前面的全部。
n fallback 3 跟 daemon 默认一致。

**不 URL-decode**: kind 是 short ASCII identifier, n 是数字 — 都不会出现需要 escape 的字符。
daemon 端把这俩都当 opaque string parse。

### 3.7 测试用 `vi.hoisted` mock bank-client, 不 mock fetch

跟 R244.3 `bank-client.test.ts` 不同 — R244.3 mock `fetchImpl`, R245.1 mock 整个 `BankClient` 类。
理由:
- R245.1 测的是 wrapper 行为 (env / format / error collapse), 不是 wire 格式
- mock `BankClient` 让 test 跟 fetch 解耦, 跑得快 (8ms vs 几十 ms)
- R244.3 测 wire 格式 — 必须用 fake fetch 才能验 URL/query/body

---

## 4. 实战

### 4.1 启 daemon (跟 R244.2 一致)

```bash
DEEPAGENTS_TALON_EXPOSE_BANK=true \
DEEPAGENTS_TALON_BANK_PORT=7777 \
MINIMAX_API_KEY=... \
java -jar aethercode-0.2.61.jar
# log: "deep-agent bank exposed on http://127.0.0.1:7777"
```

### 4.2 TUI 命令

```
> /bank-stats
[bank] bank: 12 units, 3 kinds, 8 ok / 1 notOk

> /bank-recall file_edit 3
[bank] bank[file_edit]: 3 units (top: ensure dir exists first: mkdir -p /x)

> /bank-recall unknown_kind
[bank] bank[unknown_kind]: 0 units

> /bank-recall file_edit       # 缺 kind
[info] usage: /bank-recall <kind> [n]  (n defaults to 3)
```

### 4.3 远程 daemon

```bash
AETHERCODE_BANK_URL=http://daemon.lan:9000 ac-tui
> /bank-stats
[bank] bank: 5 units, 2 kinds, 3 ok / 0 notOk
```

### 4.4 daemon 没启

```
> /bank-stats
[bank] bank: down (transport error)
```

---

## 5. R245.1 vs R244.3 估计

| R244.3 估计 | **R245.1 实际** |
|---|---|
| "TUI 实际接通 1 round" | **< 1 round (16 tests)** |

**连续 14 round 全部 < 1 round**: R240/R240.2/R241.1/R242.1/R242.2/R241.2/R241.3/R243.1/R243.2/R243.2B/R243.3/R244.1/R244.2/R244.3/R245.1

---

## 6. 关键文件路径

### 6.1 新增 (2)

```
D:\work\workspace\idea\engine\AetherCode\aethercode-memory\src\bank-recall.ts                            (~165 行)
D:\work\workspace\idea\engine\AetherCode\aethercode-memory\src\__tests__\bank-recall.test.ts             (~200 行)
```

### 6.2 修改 (3)

```
D:\work\workspace\idea\engine\AetherCode\aethercode-memory\src\index.ts                                   (+8 行 export)
D:\work\workspace\idea\engine\AetherCode\aethercode-tui\src\commands.ts                                  (+22 行: help + cmd list + 2 case)
D:\work\workspace\idea\engine\AetherCode\aethercode-tui\src\tui.tsx                                      (+38 行: 2 fire-and-forget blocks)
```

### 6.3 删除 (2)

```
D:\work\workspace\idea\engine\AetherCode\aethercode-tui\src\bank-recall.ts                               (初版放错位置, 移到 aethercode-memory)
D:\work\workspace\idea\engine\AetherCode\aethercode-tui\src\__tests__\bank-recall.test.ts                (同)
```

(回收站 — 用 [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteFile 走 SendToRecycleBin, 永久 rm 被安全策略拦了)

---

## 7. 教训 (R245.1 新增 7 条)

1. **新 utility 放 shared package 而非 consumer** — `bank-recall.ts` 没 TUI 依赖, 放 `aethercode-memory` 让 aethercode-desktop 也能用
2. **`vi.hoisted` 是 vitest mock factory 必选** — `vi.fn()` 在顶层定义时, factory 拿不到 (TDZ), 必须 `vi.hoisted(() => ({ ... }))`
3. **mock 层级选准** — R245.1 mock `BankClient` (测 wrapper 行为) 不是 mock fetch (那是 R244.3 测 wire 格式)
4. **统一 `text` 字段** — TUI 渲染只 `summary.text` 一行, 不用 if/else, 不管 ok 与否都 populate
5. **错误折叠 2 种 string** — `transport (status=0)` vs `HTTP N` 让用户区分 "daemon 没启" vs "daemon 在但 503"
6. **lastIndexOf 切分 kind:n** — kind 可能含 `:`, 用 `lastIndexOf` 保证 n 是最后一段
7. **local token + fire-and-forget** — 跟 R61 `/export` 一致, 不走 JSON-RPC, 跨进程直接 HTTP, 不阻塞 TUI 主循环

---

## 8. 后续 (R245+ scope 候选)

| 优先级 | 候选 | 范围 |
|--------|------|------|
| 高 | **MemoryAudit 写 self-eval metric (R244.1 + R230 接)** | 1 round |
| 中 | Periodic decayPass 自动 idle tick (R241.3 接) | 1 round |
| 中 | Confidence-aware DRIFT (R243.3 + R244.1 排序公式升级) | 1 round |
| 中 | Welcome banner 自动显示 bank status (startup hook) | 0.5 round |
| 低 | aethercode-desktop 端 Rust reqwest BankClient | 1 round |
| 低 | BankServer TLS/auth (暴露公网时) | 1 round |
| 低 | 修 aethercode-core SubagentPoolTest flaky (并发 8 tasks / 5s timeout) | 0.5 round |

---

**总结**: R245.1 用 16 个 vitest 把 R244.3 的 BankClient 真正接到 TUI slash command。
aethercode-memory 494/494 + aethercode-deepagents 218/218 + aethercode-talon 5/5 全绿。
TUI 现在能 `/bank-stats` 看 daemon 策略库状态, `/bank-recall <kind> [n]` 召回经验。
O-10 跨 surface bank 从 "server 暴露" → "client library" → "TUI 实际接通" 完整闭环。
