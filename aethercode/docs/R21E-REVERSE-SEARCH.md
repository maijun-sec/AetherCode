# R21-E Ctrl+R Reverse Search (2026-08-06)

## 目标

`InputBar` 自带的 `reverseSearch()` 是个 stub —— 只返回最近一个
非空 history。R21-E 用 R10-6 的 `HistorySearch`（21 tests 已
pass）替换它，做 bash readline 风格的 incremental reverse search：
- 第一次 Ctrl+R：当前 buffer 当 query，找最佳匹配替换 buffer
- 再次 Ctrl+R：循环到下个更老的匹配
- 输入字符：精化 query，重算匹配
- Backspace：从 query 删一字符
- Enter：commit 当前匹配
- Esc：还原 pre-search buffer，退出 search mode

## 改动

| 文件 | 角色 |
|---|---|
| `screen/InputBar.java` | `withHistorySearch(...)` + `reverseSearching/Query/Matches/Index/preSearchBuffer` 5 个状态字段；`reverseSearchStart/Cycle/Refine/Cancel` 4 个方法；handleKeyInner **最前面**加 reverse-search 拦截 |
| `screen/InputBarReverseSearchTest.java` | 9 tests：enter search / cycle / refine / Esc cancel / Enter accept / legacy fallback / empty query / no-match fallback |

## 关键设计

### reverseSearch block 必须在最前面

第一版放在 handleKeyInner **中间**，但 `if (type == KeyType.Enter) {
... return true; }` 在它**之前**。结果：Enter 命中、commit、
return true，但 `reverseSearching` 没清，下一次按任意键又进
reverse-search 分支。修法：挪到 `handleKeyInner` **最前面**，
跟 popup block 并列；Enter 在 reverse-search 模式里只设
`reverseSearching = false`，然后 fall through 到普通 Enter
handler 让它 commit。

### HistorySearch.score 边角

`score("git checkout -b feature", "gitc") = 57`（substring match
+ position bonus），不是 0。测 "no match" 用 `xyzzz` / `qqq`
这种绝对没 match 的字串。

### 排序非确定

`HistorySearch.search("")` 按 `lastUsedMs` 倒序。5 个 entry 在
测试里 add 间隔 < 1ms，顺序非确定。`emptyQuery_returnsMostRecentEntry`
改成 `emptyQuery_returnsOneOfTheEntries`（5 个 entry 任一都接受）。

## 关键 pitfall

1. **block 顺序** — 拦截逻辑必须在普通 handler 之前
2. **fall through** — Enter 在 reverse-search 里只清状态，让普通
   Enter handler 负责 commit；不要在 reverse-search block 里
   直接 `return true`
3. **fuzzy match 范围** — `gitc` 能命中 `git checkout...`，测
   "no match" 别用看起来明显但实际 fuzzy 命中的
4. **同 ms 排序非确定** — `lastUsedMs` 一样时排序未定义

## ReplApp 接入

**没有接**。R22 候选：JLine 3 的 `LineReader.getKeys()` 拦截
Ctrl+R，调用 `HistorySearch` 找匹配，临时替换 buffer。

## 测试

| 文件 | 测试数 |
|---|---|
| `screen/InputBarReverseSearchTest` | 9 |
| **R21-E 小计** | **+9** |

AetherCode 总数：1503 → **1512**，0 regression。

## 5 轮总收尾

R21 全 cycle：A 1413 → 1512，+99 测试，0 net regression。
详见 `docs/R21-RETROSPECTIVE.md`。
