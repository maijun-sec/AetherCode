# R21-D Slash Command Popup (2026-08-06)

## 目标

用户打 `/` 时自动弹命令面板，↑↓ 选 / Enter 接受 / Esc 关闭 /
Tab 接受。复用 R8 的 `CommandPalette`（14 tests 已 pass），新写
`SlashCommandPopup` 持有状态（open / closed / query / selected），
`InputBar` 拦截键盘 + 在 paint() 渲染。

## 改动

| 文件 | 角色 |
|---|---|
| `SlashCommandPopup.java` | 状态机：open/close/updateQuery/selectNext/selectPrev/highlighted；paint() 渲染 1-3 行 |
| `screen/InputBar.java` | `withSlashCommandPopup(...)`；handleKeyInner 顶部拦截 popup keys；try/finally 包 `syncSlashPopup()` |
| `SlashCommandPopupTest.java` | 12 unit tests（open/close/clamp/wrap/highlighted） |
| `screen/InputBarPopupTest.java` | 11 integration tests（auto-open / ↑↓ / Enter / Tab / Esc / backspace） |

## 关键设计

### "userClosed" 状态机

`Esc` 关掉 popup 后，**finally 又把 popup 重新打开**（因为 buffer
还以 `/` 开头）。修法：popup 加 `userClosed` sticky 标志。
- `Esc` → `closeByUser()` 设 `userClosed = true`
- `syncSlashPopup()` 看到 `userClosed` 就不 open
- 删掉 `/` 再重新打 `/` → `clearUserClosed()` 让 popup 重新可唤起

### updateQuery 改 clamp，不 reset

第一版 `updateQuery` 直接 `selected = 0` 重置选择。问题：用户
`/h` → 选到第 2 个 → 打 `e` 缩到 `/he` → 重置把 ↑↓ 状态搞没了。
改成 clamp：`if (selected >= n) selected = n - 1`，列表不变时
选择不动。

### sync 时机

`syncSlashPopup` 必须在 `handleKeyInner` 之后跑（在 try/finally
的 finally 里），不能在前 — 否则用户打 `/` 那一刻 buffer 还是
空的，sync 看不到 `/`、不会开 popup。

### Tab 接受 + Enter 接受

Tab 在 popup 模式等同 Enter（用 highlighted entry 替换 buffer
并关 popup）。Esc 关 popup 但**不改 buffer**。

## 关键 pitfall

1. **Palette filter 是插入顺序，不是字母序** — 测试原本写
   "first entry is clear" 实际是 "help"。`/hel` 只 match
   `help`（`history` 开头是 `his` 不 match）
2. **`/agent` 同时 match `agent` 和 `agents`** — 别写
   `assertEquals(1, ...)`，要用 `.stream().anyMatch(...)`
3. **`updateQuery` 改 reset → clamp 之后，原有测试期望要改**
   `updateQuery_resetsSelection` 改名为 `updateQuery_clampsSelection`

## ReplApp 接入

**没有接**。跟 R21-C 一样，ReplApp 走 JLine，R21-D 的成果是
InputBar-only。R22 候选：用 JLine 3 的 `Widgets.Menu` 同样效果。

## 测试

| 文件 | 测试数 |
|---|---|
| `SlashCommandPopupTest` | 12 |
| `screen/InputBarPopupTest` | 11 |
| **R21-D 小计** | **+23** |

AetherCode 总数：1480 → **1503**，0 regression。

## 下一步

R21-E：用 `HistorySearch` 替换 `InputBar.reverseSearch()` 的 stub
（"返回最近一个非空 history"），做真正的 Ctrl+R 反向搜 — 增量
查询、循环 next match、Esc 还原。
