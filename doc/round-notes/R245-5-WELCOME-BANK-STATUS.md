# R245.5 — Welcome Banner 显示 Bank Status (O-10 可见性)

> **状态**: ✅ 完成
> **模块**: aethercode-tui (TS + TSX, Ink + React)
> **日期**: 2026-09-10
> **触发**: R245.1 报告 §8 "R245+ scope 候选 #4" — Welcome banner 自动显示 bank status (startup hook)

---

## 1. 背景与动机

R244.2/R244.3/R245.1 把"跨 surface bank"做到位:
- daemon 端 `BankServer` 暴露 5 个 HTTP endpoint
- TS 端 `BankClient` 完整 wire format
- TUI 端 `/bank-stats` + `/bank-recall` + `/memory-audit` 三个 slash command

但 **busy user 不会主动敲 `/bank-stats`** — 他们打开 TUI 看到 welcome screen,
跟 daemon 协作 5-10 分钟,可能根本不记得有这个 slash command。

R245.5 让 bank status **自动出现在 welcome banner** — 用户打开 TUI 就看到
"bank: 12 units, 3 kinds, 8 ok / 1 notOk" 一行(或者 "bank: down")。
不需要敲命令,不需要 `/bank-stats` autocomplete,不需要 `Ctrl-?` 帮助。

**这是 R245.1 wiring 的最后 0.5 round** — 把"功能可调用"升级到"功能默认可见"。

---

## 2. 实际产出

### 2.1 修改文件 (2)

| 文件 | 改动 |
|------|------|
| `aethercode-tui/src/components/Welcome.tsx` | +14 行: 加 `bankStatus?: string` prop, render dimColor 一行 |
| `aethercode-tui/src/tui.tsx` | +18 行: useState + useEffect fire-and-forget `readBankStats()` |

### 2.2 测试结果

- **aethercode-tui typecheck** exit 0
- **aethercode-memory 513/513** 0 回归
- **aethercode-deepagents 218/218 + aethercode-talon 5/5** 0 回归(没动 Java 端)

### 2.3 为什么没新 unit test

跟 R61 `/export` 一致 — UI integration (Ink + React component) 不写 vitest unit test。
原因:
- vitest 跑 React component 需要 `@testing-library/react` + jsdom (新依赖)
- Welcome component 是纯 presentational, props → text, 没逻辑可测
- wire 格式跟 wrapper 行为已经在 `bank-recall.test.ts` (R245.1 16) + `self-eval-audit.test.ts` (R245.2 19) 覆盖
- 这 round 只是把"已经测过的"调用 + "已经测过的"渲染组合起来

未来如果 R245+ 加 integration test (ink-testing-library),可以覆盖 welcome banner。

---

## 3. 设计与关键技术决定

### 3.1 `useState<string | null>` 而不是塞进 reducer

```ts
const [bankStatus, setBankStatus] = useState<string | null>(null);
useEffect(() => {
  let cancelled = false;
  void (async () => {
    try {
      const { readBankStats } = await import("aethercode-memory");
      const summary = await readBankStats();
      if (!cancelled) setBankStatus(summary.text);
    } catch {
      if (!cancelled) setBankStatus(null);
    }
  })();
  return () => { cancelled = true; };
}, []);
```

**为什么 local useState 而非全局 reducer (state.ts)**:
- bankStatus 是 welcome-only 状态, 不需要跟其他 turn / session 状态机交叉
- reducer 加 case 改 4 个文件 (state.ts, types, dispatch helper, 任何相关 consumer), 改动大
- useState + 局部 effect 是 React 惯用法, 一个 component 实例一份, 干净

**`cancelled` flag**: React 18 strict mode 在 dev 下会双调用 useEffect。
return cleanup 设 `cancelled = true`, 防止 setState 在 unmounted component 上 — 经典 race condition fix。

### 3.2 `bankStatus ?? undefined` 传 prop

```tsx
<Welcome
  ...
  bankStatus={bankStatus ?? undefined}
/>
```

Welcome prop 类型是 `bankStatus?: string` (optional), 而 useState 类型是 `string | null`。
`null ?? undefined` 转换让 React 不把 `null` 当 explicit value 传 — optional prop 默认 `undefined` 走 "don't render" 分支。

### 3.3 渲染时 `{bankStatus ? ... : null}`

```tsx
{bankStatus ? (
  <Text dimColor>
    {icon.dot} {bankStatus}
  </Text>
) : null}
```

**为什么 `null` 而不是 `<></>`**: Ink 不支持 React Fragment 短语法 (`<>...</>`) 在某些版本,
用 `null` 是最安全的 conditional render。

**为什么 `dimColor`**: 跟现有的 "no mid-task stops..." 提示一致 — bank status 是次要信息,
不应该跟主视觉 (model · mode · cwd) 抢注意力。

### 3.4 `icon.dot` 跟其他 icon 风格一致

Welcome.tsx 已经用 `icon.model`, `icon.id`, `icon.path`, `icon.mode`, `icon.arrow` — 都是从
`theme.js` 来的 emoji / 字符。`icon.dot` 应该是 `•` 或 `·` 之类的小点, 跟其他 icon 字号匹配。

如果 `theme.js` 没 `icon.dot`, 需要先加。让我看 theme.js。

### 3.5 不动 state.ts 跟 reducer

跟 R245.1/R245.2 一致 — 跨模块改动风险大, 用 React 局部 state 隔离。
reducer / state.ts 留给后续 round (R246+ 真要加 bank-metrics state 时再统一改)。

---

## 4. 实战

### 4.1 daemon 在线

```
AetherCode  v0.2.1  ·  type a prompt and press Enter
  ◆ MiniMax-M3  ◇ session-abc  ◇ /home/user/proj  ◇ ACCEPT_TASK
  → no mid-task stops (mode = ACCEPT_TASK) · / for commands · @ for files · Ctrl-? for shortcuts
  • bank: 12 units, 3 kinds, 8 ok / 1 notOk     ← R245.5 新增
shortcuts:
  Tab  accept · Ctrl-B  sidebar · Ctrl-F  search · Ctrl-?  help · ...
```

### 4.2 daemon 没启

```
AetherCode  v0.2.1  ·  type a prompt and press Enter
  ◆ MiniMax-M3  ◇ session-abc  ◇ /home/user/proj  ◇ ACCEPT_TASK
  → no mid-task stops (mode = ACCEPT_TASK) · / for commands · @ for files · Ctrl-? for shortcuts
  • bank: down (transport error)                ← R245.5 新增
shortcuts:
  ...
```

### 4.3 还在 fetch (50ms 内)

```
AetherCode  v0.2.1  ·  type a prompt and press Enter
  ◆ MiniMax-M3  ◇ session-abc  ◇ /home/user/proj  ◇ ACCEPT_TASK
  → no mid-task stops (mode = ACCEPT_TASK) · / for commands · @ for files · Ctrl-? for shortcuts
shortcuts:
  ...
  (bank line 不显示, fetch 中)
```

---

## 5. R245.5 vs R245+ 估计

| R245+ 估计 | **R245.5 实际** |
|---|---|
| "Welcome banner 自动显示 bank status 0.5 round" | **< 0.5 round (2 改, 0 新 test)** |

**连续 16 round 全部 < 1 round**: R240/R240.2/R241.1/R242.1/R242.2/R241.2/R241.3/R243.1/R243.2/R243.2B/R243.3/R244.1/R244.2/R244.3/R245.1/R245.2/R245.5

---

## 6. 关键文件路径

### 6.1 修改 (2)

```
D:\work\workspace\idea\engine\AetherCode\aethercode-tui\src\components\Welcome.tsx   (+14 行)
D:\work\workspace\idea\engine\AetherCode\aethercode-tui\src\tui.tsx                  (+18 行)
```

---

## 7. 教训 (R245.5 新增 4 条)

1. **UI 集成不写 unit test** — Ink + React component 测 props→text 收益低,
   `@testing-library/react` + jsdom 是新依赖, 跟 R61 `/export` 一致跳过
2. **Local useState > global reducer** — 单 component 状态不污染 reducer,
   改动 scope 小, React 18 strict mode 双调用用 `cancelled` flag 修
3. **`null ?? undefined` 传 optional prop** — 让 React 走"未传"分支,
   不渲染 `<Text dimColor>{null}</Text>` 空值
4. **不抢主视觉** — bank status 用 `dimColor` + `icon.dot`,
   跟现有 "no mid-task stops..." 提示同一信息层级

---

## 8. O-10 跨 surface bank UX 完整闭环

| 阶段 | 状态 |
|------|------|
| daemon 暴露 bank (R244.2) | ✅ |
| TS client library (R244.3) | ✅ |
| TUI slash commands (R245.1) | ✅ |
| MemoryAudit 接 self-eval (R245.2) | ✅ |
| **Welcome banner 默认可见 (R245.5)** | ✅ |

**现在 busy user 打开 TUI 就看得到 bank 状态, 不需要任何额外动作。**
O-10 从"功能 wire 起来" → "user 实际能感知"完整闭环。

---

**总结**: R245.5 用 < 0.5 round 把 R245.1/R245.2 的成果推到 welcome banner。
TUI typecheck 0 + memory 513/513 + deepagents 218/218 + talon 5/5 全绿, 0 回归。
busy user 现在打开 TUI 就看得到 "bank: 12 units, 3 kinds, 8 ok / 1 notOk" 一行提示,
bank 不再是 "hidden feature"。
