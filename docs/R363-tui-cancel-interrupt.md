# R363 — TUI 可以中断正在运行的这一轮

> 日期：2026-10-22
> 范围：`aethercode-tui`（Ink + line 双 renderer），**不触碰 desktop**
> 起点：R750a benchmark 结论 "TUI 不能中断运行中的 run"

---

## 1. 问题

daemon 侧其实**早就有**一个真正的中断能力，只是 TUI 一行代码都接不到。

`AetherCodeMethods.cancel`
（`aethercode-protocol/.../methods/AetherCodeMethods.java`）在 R721 被改成
hard cancel：

- `done.cancel(true)`
- `Thread.interrupt()`（worker thread 在 `runThreads` 里按 runId 登记）
- `ProcessHandle.destroyForcibly()`（`runChildren` 里登记的子进程）
- 5s `Thread.stop()` 兜底（`AETHERCODE_R721_CANCEL_FORCE_MS`）

E2E settle 时间 ~7ms。**能力齐备，缺的是全部管线。**

benchmark 记录的三个具体缺陷：

| # | 位置 | 症状 |
|---|---|---|
| 1 | `tui.tsx` `sendPrompt` | `client.request<unknown>("query", …)` —— `query` 返回 `{runId, accepted}`，这个响应被**整个丢掉** |
| 2 | `tui.tsx` `useInput` Esc | Esc 只关搜索栏 / help；`Ctrl+C` 直接 `exit()` 把 daemon 一起拆掉 |
| 3 | `tui.tsx` decision-card | 两个 `key.escape && …` 分支在候选位置**先 return** |

第 3 条最阴险：**即使把 1、2 修好，运行中的这一轮仍然在结构上不可中断。**
因为那两个分支先命中，Esc 只会折叠 / 展开决策卡片。

---

## 2. 实现

### 2.1 state.ts —— run id 是必需状态，不是缓存

```ts
activeRunId: string | null;   // query 响应 + 两个 notification fallback 喂进来
cancelRequested: boolean;     // Esc → cancel RPC 在飞行中
```

三个 action：`setActiveRunId` / `requestCancelRun` / `setCancelSettled`。

边界规则（这几条任何一条漏掉，缺陷就会换个形状回来）：

- `submit` 清 `activeRunId` + `cancelRequested` —— 否则一次滞留的 Esc 会 cancel 一个已死掉的 run
- `streamEnd` 清同样两个字段 —— 否则**下一轮的第一下 Esc 是静默 no-op**，看起来像又不可中断了
- `requestCancelRun` 自幂等 —— daemon 侧 cancel 虽然近乎幂等，但重复 RPC 会重新 arm 那个 5s `Thread.stop` 计时器
- `setCancelSettled` 单独存在 —— cancel RPC **自己失败**时（老 daemon / transport 掉线）必须把 `cancelRequested` 放回去，否则 strip 会永远显示 "stopping…" 而 run 还在跑

### 2.2 tui.tsx —— 三个 runId 来源

| 来源 | 时机 | 必要性 |
|---|---|---|
| `query` **响应** `{runId}` | 提交瞬间 | 唯一可靠来源 |
| `query` 响应 `{ok:false, busyRunId}` | session 锁冲突 | 否则 Esc 停不掉**正在阻塞我们**的那个 run |
| `stream_event` / `permission_request` 的 params | 流式过程中 | query 响应丢失（请求与响应之间 transport 掉了）时的兜底 |

permission 那一路特意排除了 daemon 的历史占位符 `"n/a"`
（见 `AetherCodeMethods.askPermission`）—— 拿它去 cancel 是注定失败的。

**daemon 拒绝时不进 streaming**：走 `streamEnd` + toast。
否则 `submitting` 永远为 true，队列 drain 永久卡住。

### 2.3 Esc 优先级阶梯（统一到一个块）

```
面板打开      → 面板持有键盘（已有 gate）
搜索栏 / help → 关掉它自己打开的东西
submitting    → void cancelActiveRun()          ← R363 新增
pendingPrompts → clearPendingPrompts            ← R362
（都没有）    → decision-card 折叠/展开           ← 旧行为，退到最后
```

规则本身是常识，难的是**它必须放在那两个 decision-card 分支之前**。
它们先于一切 `return`，所以位置不是风格问题，是可达性问题。

### 2.4 可见性

```
submitting && !queue  → "esc to interrupt"
submitting &&  queue  → "esc to interrupt (press again to drop the queue)"
cancelRequested       → "stopping... (interrupt sent)"
```

R721 的 cancel 是**异步**的（最多 5s），所以第三行是必需的 ——
否则 strip 会在那几秒里撒谎。

### 2.5 line mode 同一条路径

`LineInputCallbacks` 新增可选 `onEscape`。关键细节：
**只对 bare ESC 触发**，CSI 序列（方向键 / Home / End / F 键）先被消费。
判据是 peek 下一个字符是不是 `[`。

`pickFromList` 会先 `lineInput.pause()`，所以 picker 仍然优先拥有 Esc。

---

## 3. 回归门

`scripts/test/r363-cancel.test.mjs` —— **23 例**。

真正值钱的不是这 23 例，而是 `scripts/test/r363-mutate.mjs`
（不参与 `npm test`）：**10 个变异，全部被捕获**。

| 变异 | 被哪条抓住 |
|---|---|
| 删掉 query 响应的 runId 捕获 | `sendPrompt` 体内必须出现 `resp.runId` |
| 退回 `request<unknown>("query")` | `doesNotMatch` |
| 把 Esc-cancel 块挪到 decision-card 之后 | 位置断言 |
| 删掉 strip 上的 `esc to interrupt` | 可见性断言 |
| 去掉 `requestCancelRun` 的幂等 latch | reducer 引用相等断言 |
| `submit` 不清 `cancelRequested` | reducer 行为 |
| `streamEnd` 不清两个字段 | reducer 行为 |
| `onEscape` 对 CSI 也触发 | CSI-consume 必须在前 |
| line mode run_end 不重置 latch | 源码 pin |
| line mode 不接 `onEscape` | 源码 pin |

### 两个 gate 自身的问题（都被变异测试抓出来了）

1. **整树 `setActiveRunId` 匹配是假阳性。** 两个 notification fallback 就足以满足它，删掉 query 响应捕获照样绿。→ 断言必须收窄到 `sendPrompt` 函数体内。
2. **排序断言锚点选错。** 锚在 `setDecisionCardExpanded` 上时，mutation 把它插到 anchor **之前** —— 那是正确顺序，于是变异是个 no-op，"通过"了。→ 锚必须落在明显更下游的位置。

> 教训：**绿灯在看着它变红之前不算数。** 而"变红"这一步本身也可能因为锚点选错而作弊。

---

## 4. 顺带修掉的测试竞争（pre-existing，但当时正在冒烟）

全量跑的时候 `T-7-10: tsc --noEmit of the full project is clean` 红了，
但**单独跑必绿**。

真因：15 个 node:test 套件把 `_...` shim 写进 `src/`，跑完再 `unlinkSync`。
`node --test` 并行执行，全项目 `tsc` 正好在 write→unlink 窗口里 glob 到 `src/`：

```
error TS6053: File '.../src/components/_r196_helpers.ts' not found.
```

处理：

- `tsconfig.json` 加 `src/**/_*.ts{,x}` 到 `exclude`（**根治**，一个点覆盖 15 个套件）
- `r196-notifications` / `r196-threads` / `r196-updates` 三个套件把 shim 移到各自的 `tmp/`，`--rootDir` 上提到包根，**不再触碰 `src/`**
- 清理 `src/` 下 11 个残留 shim（移到 `tmp-r363-shim-trash/`，可恢复）
- `.gitignore` 补齐 `src/**/_*.ts` 与新的 tmp 目录

> 这正是我该注意的那类"顺手修掉"的东西 —— 一个测试改动编译另一个测试要编译的目录，是测试顺序地雷，不是偶发失败。

---

## 5. 基线

| | tests | pass | fail | failing files |
|---|---|---|---|---|
| HEAD baseline | 1081 | 1030 | 51 | 16 |
| R362 | 1119 | 1071 | 48 | 14 |
| **R363** | **1142** | **1095** | **47** | **14** |

47 个失败**全部落在 14 个既有文件**里，无一属于 R362/R363：
`docs-structure` / `line-runend` / `r112-skiplow-suggestion` / `r165-connection-state` /
`r167-statusbar-visual` / `r33-pills` / `r35-spinner` / `r44-progress` / `r48-tokenchart` /
`r77-metrics` / `r78-traces` / `r79-trace-tree` / `r94e-prompt` / `t441-statusbar-contextmeter`。

`T-7-10` 从红转绿。`npx tsc -p tsconfig.json --noEmit` exit 0。

---

## 6. 遗留（明确没做假成功）

- **`setCwd` RPC 在 Java transport 里不存在。** `AetherCodeMethods` 只把它放进
  tag map，没有 `register("setCwd", …)`；`HttpJsonRpcServer` 没有对应 case。
  `/cwd-pick` 目前只能做到面板 + 诚实降级，不能假装切换成功。
- `agent/setActive`、完整 effort 域、MCP RPC、memory update/delete 同理 ——
  面板给出明确的不可用提示，不做假成功。
- R362 的 12 类面板 + 队列 strip 还没做**实机视觉验收**（Ink 交互 / 80 列宽度）。