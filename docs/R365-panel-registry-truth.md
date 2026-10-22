# R365 — 面板登记表不能再撒谎

> 日期：2026-10-22
> 范围：`aethercode-tui/src/panelIntents.ts` + 测试
> 起点：R364 末尾发现「`PanelIntent` 用同一个 `unavailable` 同时表示两种完全不同的情况」

---

## 1. R362 的登记表错了整整一轮，而且测试是绿的

R362 建立 `panelIntents.ts` 时写下了 12 行 intent，其中 **10 行标着**
`ink: 'unavailable', line: 'unavailable'`。

R365 实测之后：**其中 8 行是假的。**

| intent | R362 声明 | 实际 |
|---|---|---|
| `memory-panel` | unavailable ×2 | Ink `case "memory":` + line `case "__MEMORY_PANEL__":` |
| `thread-selector` | unavailable ×2 | Ink `PANEL_BY_INTENT` → `"threads"` + line arm |
| `todo-board` | unavailable ×2 | Ink `"todos"` + line arm |
| `agent-selector` | unavailable ×2 | Ink `"agents"` + line arm |
| `effort-picker` | unavailable ×2 | Ink `"effort"` + line arm |
| `grants-manager` | unavailable ×2 | Ink `"grants"` + line arm |
| `notification-center` | unavailable ×2 | Ink `"notifications"` + line arm |
| `mcp-viewer` | unavailable ×2 | Ink `"mcp"` + line arm |
| `workflow-picker` | unavailable ×2 | **line 有真 frame**（`panelWorkflow`），只有 Ink 缺 |
| `update-available` | unavailable ×2 | **line 有真 frame**（`panelUpdate`），只有 Ink 缺 |

R362 的两个子 agent 把面板接线做完了，**登记表没跟着更新**。

而 gapNote 里那些理由，逐条看都是静态推断而不是可达性验证：

- 「`MemoryPanel.tsx` 存在但从未挂载」——挂了
- 「`state.agentPickerOpen` 存在但 NOTHING dispatches it」——派发了，
  走 `intentForSentinel` → `PANEL_BY_INTENT` → `openPanel`
- 「line mode 没接」——`line.ts:2962` 一直都接了

---

## 2. 为什么 R362 的测试没抓到

R362 的门禁有 6 条针对这张表的检查，全部只查**结构**，没有一条查**真伪**：

| 检查 | 查的是什么 | 漏掉了什么 |
|---|---|---|
| 每个 sentinel 有 entry | 有没有这一行 | 这一行说的对不对 |
| `unavailable` 必须有 gapNote | 有没有解释 | 解释本身是不是假的 |
| sentinel 唯一 / id 唯一 | 重不重复 | 同上 |
| component 文件存在 | 路径在不在 | **文件有没有被 import** |
| title 是用户文案 | 有没有 R 编号 | 同上 |
| `describeSentinel` 不漏 token | 有没有泄漏 | 同上 |

所以「10 行假的 unavailable + 每行一个体面的 gapNote」是**完美的满分答案**。

> **Lesson：一个可以撒谎而不失败的状态字段，比没有这个字段更糟** ——
> 因为下一个读它的人会相信它。R362 的表看起来比没有表更权威。

R364 的 `/cwd-pick` 已经吃过一次同样的亏（标 unavailable，真相是 JSON-RPC / Tauri 命名混淆）。
R365 处理的是这一整类。

---

## 3. 修法：不再声明，而是推导

核心改动只有一句话：**状态列由测试从 renderer 源码重算，声明与事实不符就红。**

```ts
ink  = PANEL_BY_INTENT[id] 存在  &&  tui.tsx 里有 case "<panelId>":
line = intent 的每个 sentinel 都在 line.ts 里有 case "__SENTINEL__":
```

两边都是从源码解析出来的，不是硬编码的期望值。改 renderer 不改表 → 红；
改表不改 renderer → 红。

### 3.1 blocker：区分三种「不可用」

`unavailable` 这一个布尔量同时表达了三件性质相反的事：

| blocker | 含义 | 应对 |
|---|---|---|
| `not-wired` | 后端能用，只是没人写 UI | 去写代码 |
| `backend-missing` | daemon 压根没这个 RPC | **别写**，会做成死按钮 |
| `no-command` | slash 命令本身不存在，sentinel 没有生产者 | 先写命令 |

现在任一 renderer 为 `unavailable` 就必须带 `blocker` + `gapNote`。

### 3.2 `/help` 里的死命令（真实用户可见 bug）

`context-meter` 这一行的 `command` 是 `/context`，但 **`handleSlash` 里没有
`case "context"`** —— 打出来是 `default:` 分支的
`unknown slash command: /context. Try /help.`

而 `helpByGroup()` 遍历 `PANEL_INTENTS`，所以 **`/help` 一直在推荐一个不存在的命令**。

修法：`advertised?: boolean`，`helpByGroup()` 改从 `ADVERTISED_INTENTS` 构建。
登记表保留（组件是现成的，等命令），但不再出现在 `/help` 里。

> 广告一个「你一敲就告诉你不存在」的命令，比不列它更糟。

---

## 4. 门禁

`scripts/test/r365-panel-truth.test.mjs` —— **10 例**，
`scripts/test/r365-mutate.mjs` —— **9 个变异全捕获**。

变异全部是**把谎话说回去**，即还原成 R362 的样子：

| 变异 | gate fail |
|---|---|
| memory-panel 谎称 Ink unavailable | 3 |
| thread-selector 谎称 line unavailable | 3 |
| todo-board 谎称两边 unavailable | 4 |
| grants-manager 谎称两边 unavailable | 4 |
| mcp-viewer 谎称两边 unavailable | 4 |
| 把 `/context` 放回 `/help` | 2 |
| 去掉 blocker（退回纯文字说明） | 1 |
| 给已挂载的行加 blocker | 1 |
| effort-pick 谎称 unavailable（证明推导不是硬编码） | 3 |

第一条 guard 是 `PANEL_BY_INTENT parses and is non-trivial`：
如果哪天锚点漂了导致解析出空 map，所有 `mounted` 推导会翻成 `unavailable`，
门禁会因为**错误的原因**变绿。这条断言先挡住这个。

---

## 5. 顺带修掉

- `effort-picker` 的 component 路径写成 `components/models/../EffortPicker.tsx`
  —— 归一成 `components/EffortPicker.tsx`，与 tui.tsx 实际 import 一致
- 本文件 GBK 双重编码遗留的 `-?` 字符（实为 em dash 被破坏）全部改成 ASCII `-`；
  全文件保持纯 ASCII，避免再次损坏
- R362 的 `helpByGroup` 测试从「覆盖每个 intent」改成「覆盖每个 **advertised** intent」，
  并断言 `total < PANEL_INTENTS.length`（否则 `advertised:false` 就是死代码）

---

## 6. 基线

| | tests | pass | fail | failing files |
|---|---|---|---|---|
| HEAD baseline | 1081 | 1030 | 51 | 16 |
| R363 | 1142 | 1095 | 47 | 14 |
| R364 | 1154 | 1107 | 47 | 14 |
| **R365** | **1164** | **1117** | **47** | **14** |

47 个失败仍是同一批既有文件，与 R363/R364 逐条一致。
`tsc` exit 0，`T-7-10` 保持绿，全部改动文件 LF 行尾。

---

## 7. 下一轮入口

`workflow-picker` 和 `update-available` 现在被精确标记为 **Ink 侧 `not-wired`** ——
后端（`workflow/list`、`workflow/run`）是有的，line mode 也已经有真 frame，
只差 Ink 的一个 case。这是两份确定的、有边界的活，不是模糊的「待实现」。

另外 `context-meter` 的 `no-command` 标记指向一个真实的产品决定：
**要不要加 `/context` 命令？** 加了就把 `ContextMeter.tsx` 挂上去并取消
`advertised: false`；不加就删掉 sentinel，把组件留到有用的时候。
这个得人来定。