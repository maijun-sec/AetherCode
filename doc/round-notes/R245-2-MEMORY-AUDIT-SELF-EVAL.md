# R245.2 — MemoryAudit 接 self-eval metric (O-6 + O-10 闭环)

> **状态**: ✅ 完成
> **模块**: aethercode-memory (TS, vitest)
> **日期**: 2026-09-10
> **触发**: R245.1 报告 §8 "R245+ scope 候选 #1" — MemoryAudit 写 self-eval metric (R244.1 + R230 接)

---

## 1. 背景与动机

R244.1 在 `aethercode-deepagents` 加了 `SelfEvalMiddleware` + `ReasoningUnit.confidence()` —
每次 tool call 完成后给 recall 列表里的 unit 调 `recordOutcome`,累加 `okCount` / `notOkCount`,
再用 Laplace-smoothed `okCount / (okCount + notOkCount + 1)` 算 confidence。

R230 在 `aethercode-memory` 实现了 `MemoryAudit` 三层 memory 审计(global / project / session)。

但两边是**两个独立进程**(JVM daemon + Node TUI),R244.1 写的数据在 `~/.aethercode/bank/*.json`,
R230 跑的 audit 在 TUI 进程的 sqlite 里 — 互不知道。

R245.2 把 R244.1 的 self-eval metric **暴露给 TUI 端**,让 MemoryAudit 跟 self-eval 接通:
- TUI 通过 R244.2 暴露的 `BankClient.stats()` + `recallAllKinds(1000)` 拉全部 unit
- 聚合 per-kind 成功率 + Laplace 平均 confidence
- 产出 1-screen audit 报告,跟 R230 MemoryAudit 系列在同一个 UX 表面

**没改 Java 端** — 全部 cross-process 读 R244.2 server,R245.1 client,新加聚合层。

---

## 2. 实际产出

### 2.1 新增文件 (1 TS + 1 test)

| 文件 | 行数 | 作用 |
|------|------|------|
| `aethercode-memory/src/self-eval-audit.ts` | ~270 | Self-eval audit 聚合器 + TUI 渲染 |
| `aethercode-memory/src/__tests__/self-eval-audit.test.ts` | ~315 | 19 vitest (unit math 7 + aggregator 7 + format 3 + wrapper 3) |

### 2.2 修改文件 (3)

| 文件 | 改动 |
|------|------|
| `aethercode-memory/src/index.ts` | +9 行 export `unitConfidence/aggregateReport/formatSelfEvalReport/auditSelfEval/SelfEvalAuditReport/SelfEvalAuditSummary/KindAudit` |
| `aethercode-tui/src/commands.ts` | +1 SLASH_HELP line, +1 SLASH_COMMANDS entry, +1 SLASH_COMMANDS_DETAILED entry, +6 行 handleSlash "memory-audit" case |
| `aethercode-tui/src/tui.tsx` | +15 行 fire-and-forget `__MEMORY_AUDIT__` dispatch |

### 2.3 测试结果

```
$ cd aethercode-memory && npm test

 Test Files  33 passed (33)
      Tests  513 passed (513)
   Duration  11.69s
```

**aethercode-memory 513/513 pass** (R245.1 494 + R245.2 19 新增, 0 回归)
**aethercode-tui typecheck** exit 0
**aethercode-deepagents 218/218 + aethercode-talon 5/5** 0 回归

---

## 3. 设计与关键技术决定

### 3.1 跟 R244.1 Java 端的 confidence 公式严格一致

Java 端 `ReasoningUnit.confidence()`:
```java
public double confidence() {
    return (double) okCount / (double) (okCount + notOkCount + 1L);
}
```

TS 端 `unitConfidence(ok, notOk)`:
```ts
export function unitConfidence(okCount: number, notOkCount: number): number {
  const ok = finiteNonNeg(okCount);
  const not = finiteNonNeg(notOkCount);
  const denom = ok + not + LAPLACE_K;   // LAPLACE_K = 1
  return ok / denom;
}
```

**数值严格一致** — 同一个 unit, Java 跟 TS 算的 confidence 一模一样(只受 IEEE 754
浮点精度差异影响,~1e-15 级别)。

### 3.2 3 层 audit,2 个 sort key

```
R230 MemoryAudit (aethercode-memory local)         ← 三层 memory
R245.2 Self-Eval Audit (aethercode-deepagents 远端) ← bank self-eval
```

- `R230` 审计 aethercode-memory 的三层 memory 状态(global / project / session 各自大小、压缩率、最近 fact)
- `R245.2` 审计 daemon bank 的 self-eval 质量(per-kind 成功率、avg confidence、weakest/top kind)

两者**正交** — MemoryAudit 不依赖 bank,bank audit 不依赖 memory。`/memory-audit` 走 R245.2 路径
(调 BankClient),不混。

### 3.3 `aggregateReport` 是 pure function,无 IO

```ts
export function aggregateReport(stats: BankStats, units: BankUnit[]): SelfEvalAuditReport
```

- 接 BankStats + BankUnit[] (已经从 wire 拉到的纯数据)
- 产出 SelfEvalAuditReport (pure data)
- 测试直接构造 synthetic input,不需要 mock fetch / BankClient

这跟 R245.1 测 wire 格式 / R245.2 测 wire 解析 + 聚合的**测试金字塔**对齐:
- `bank-client.test.ts`: 测 fetch + JSON parse
- `bank-recall.test.ts`: 测 BankClient wrapper
- `self-eval-audit.test.ts`: 测 math (pure) + 测 TUI wrapper

### 3.4 `auditSelfEval` 接可选 client 参数 (DI)

```ts
export async function auditSelfEval(
  envOrClient: Record<string, string | undefined> | BankClient = process.env,
  maybeEnv?: Record<string, string | undefined>,
): Promise<SelfEvalAuditSummary> {
  let client: BankClient;
  if (envOrClient && typeof (envOrClient as BankClient).stats === 'function') {
    // First arg is a BankClient (test or DI scenario).
    client = envOrClient as BankClient;
  } else {
    // First arg is the env record; build a client from it.
    client = makeBankClient(envOrClient as Record<string, string | undefined>);
  }
  // ...
}
```

**为什么这样**: test 时直接传 `fakeClient = { stats: mockStats, recallAllKinds: mockRecallAllKinds }`,
绕开 `makeBankClient` 跟 env。production 时省略 client,走 `makeBankClient(process.env)`。

**踩坑 (教训 §R245.2 #1)**: 一开始 test 用 `vi.mock('../bank-recall.js')` mock `makeBankClient`,
但 vitest 的 mock 没 transitive 覆盖到 `self-eval-audit.js` import 的 BankClient,
真 BankClient 真去 fetch `127.0.0.1:7777`,拿到 ECONNREFUSED。
**修法**: 让 auditSelfEval 接 client 参数(不依赖 mock 链)。

### 3.5 `formatSelfEvalReport` 多行 1-screen

```ts
export function formatSelfEvalReport(r: SelfEvalAuditReport): string {
  const lines: string[] = [];
  lines.push(`self-eval audit: ${r.totalUnits} units, success ${rateText}, avg confidence ${r.avgConfidence.toFixed(2)}`);
  if (r.observed > 0) {
    if (r.weakestKind) { lines.push(`  weakest: ${w.kind} (${pct(w.rate)} over ${w.observationCount} obs)`); }
    if (r.topKind && r.topKind !== r.weakestKind) { lines.push(`  top:     ${t.kind} (${pct(t.rate)} over ${t.observationCount} obs)`); }
  }
  return lines.join('\n');
}
```

**为什么 3 行而不是 1 行**:
- 1 行能装"12 units, 80% (8/10), avg 0.55" — 但 user 看不到 per-kind 质量
- 3 行能装"weakest: build (33% over 3 obs)" — user 立刻知道哪个 kind 需要修
- 留 1-2 行空余,跟 R240.1 `/stats` 的多行 UX 一致

### 3.6 `weakestKind` / `topKind` 只考虑有观察的 kind

```ts
const ranked = perKind.filter((k) => k.observationCount > 0);
const weakestKind = ranked.length === 0 ? null : ranked[0].kind;
const topKind = ranked.length === 0 ? null : ranked[ranked.length - 1].kind;
```

**为什么**: 一个新建的 unit (0 obs) 算 confidence=0,如果直接当"weakest",会污染
报告 — 用户看到 "weakest: build" 但 build 实际表现不差,只是没观察过。

**修法**: 排除 0-obs kind,只对有反馈的 kind 排序。0-obs kind 不在 weakest/top 出现。

### 3.7 test fake client 必须 mirror 真 BankClient interface

R245.2 第一版 fake client 只有 `stats` + `recallFor`,但 `buildSelfEvalReport` 调
`recallAllKinds()` — fake 上没这个方法,buildSelfEvalReport 抛 `TypeError: client.recallAllKinds is not a function`。

**修法**: fake client 必须实现 `BankClient` 用到的所有方法。本 round 用 `stats` + `recallAllKinds`。
**教训**: test fake 不能"按需"实现,要 mirror production interface 完整 surface。

---

## 4. 实战

### 4.1 启 daemon (跟 R245.1 一致)

```bash
DEEPAGENTS_TALON_EXPOSE_BANK=true \
DEEPAGENTS_TALON_BANK_PORT=7777 \
MINIMAX_API_KEY=... \
java -jar aethercode-0.2.61.jar
# log: "deep-agent bank exposed on http://127.0.0.1:7777"
```

### 4.2 TUI 跑 /memory-audit

```
> /memory-audit
[memoryAudit] self-eval audit: 12 units, success 80% (8/10), avg confidence 0.55
[memoryAudit]   weakest: build (33% over 3 obs)
[memoryAudit]   top:     file_edit (100% over 7 obs)
```

### 4.3 0 观察

```
> /memory-audit   # 全新 daemon,没跑过 task
[memoryAudit] self-eval audit: 0 units, success no outcomes yet, avg confidence 0.00
```

### 4.4 daemon 没启

```
> /memory-audit   # 127.0.0.1:7777 没人在听
[memoryAudit] self-eval audit: down (transport error)
```

---

## 5. R245.2 vs R245.1 估计

| R245.1 估计 | **R245.2 实际** |
|---|---|
| "MemoryAudit 写 self-eval metric 1 round" | **< 1 round (19 tests)** |

**连续 15 round 全部 < 1 round**: R240/R240.2/R241.1/R242.1/R242.2/R241.2/R241.3/R243.1/R243.2/R243.2B/R243.3/R244.1/R244.2/R244.3/R245.1/R245.2

---

## 6. 关键文件路径

### 6.1 新增 (2)

```
D:\work\workspace\idea\engine\AetherCode\aethercode-memory\src\self-eval-audit.ts                  (~270 行)
D:\work\workspace\idea\engine\AetherCode\aethercode-memory\src\__tests__\self-eval-audit.test.ts   (~315 行, 19 tests)
```

### 6.2 修改 (3)

```
D:\work\workspace\idea\engine\AetherCode\aethercode-memory\src\index.ts                              (+9 行 export)
D:\work\workspace\idea\engine\AetherCode\aethercode-tui\src\commands.ts                              (+8 行: help + cmd + case)
D:\work\workspace\idea\engine\AetherCode\aethercode-tui\src\tui.tsx                                  (+15 行: __MEMORY_AUDIT__ dispatch)
```

---

## 7. 教训 (R245.2 新增 6 条)

1. **vi.mock 不 transitive 覆盖** — `vi.mock('./bank-client.js')` 不会覆盖 `bank-recall.js` 里的 `import { BankClient } from './bank-client.js'`。
   修法: 让 production 函数接可选 client 参数(DI),test 直接传 fake 绕开整个 mock 链
2. **Test fake 必须 mirror 完整 interface** — fake client 只实现 `stats/recallFor` 不够,production 调 `recallAllKinds` 就 throw `TypeError`。
   fake client 跟真 BankClient interface 1:1
3. **aggregateReport 设计成 pure function** — 接 `BankStats + BankUnit[]`,产 `SelfEvalAuditReport`,
   跟 IO 完全分离,test 用 synthetic input 验证 math,无 mock 复杂度
4. **Laplace K=1 实际是"拉低"语义** — `unitConfidence(0, 1) = 0`,不是 0.5。
   跟 R244.1 Java `confidence()` 严格一致,但容易在 test 期望值写错
5. **0-obs kind 不进 weakest/top 排序** — 避免新 unit 噪声污染报告
6. **DI 参数 + 鸭子类型 dispatch** — `auditSelfEval(envOrClient)` 第一参是 env 还是 client,
   用 `typeof stats === 'function'` 区分。production 调用零摩擦,test 不需要 mock 链

---

## 8. O-6 + R230 接通完成

| 维度 | 状态 |
|------|------|
| R244.1 SelfEvalMiddleware (JVM 进程内) | ✅ |
| R244.1 ReasoningUnit.confidence() Laplace formula | ✅ |
| R244.2 BankServer 暴露 /bank/stats + /bank/recall-all-kinds | ✅ |
| R244.3 TS BankClient 拉 self-eval 数据 | ✅ |
| **R245.2 aggregateReport 聚合 per-kind** | ✅ |
| **R245.2 /memory-audit TUI 命令** | ✅ |
| R245+ Periodic decayPass 自动 idle tick | 📋 |
| R245+ Confidence-aware DRIFT | 📋 |
| R245+ Welcome banner 自动显示 bank status | 📋 |

**R245.2 之后,MemoryAudit 能看 bank self-eval 全貌:总成功率、avg confidence、weakest/top kind。
TUI 跟 daemon 在 self-eval metric 这一层闭环。**

---

**总结**: R245.2 用 19 个 vitest 把 R244.1 (JVM self-eval) + R230 (TS MemoryAudit)
通过 R244.2 server + R245.1 client 真正接通。aethercode-memory 513/513 + deepagents 218/218 + talon 5/5 全绿。
新加 `/memory-audit` slash command 给 TUI 用户一个 1-screen self-eval 报告。
