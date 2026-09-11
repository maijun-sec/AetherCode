# R21 Retrospective (2026-08-06)

## TL;DR

5 轮 5 个特性，**+99 测试 / 0 net regression**，AetherCode 总数
**1413 → 1512**。把 R20-A 搭好的全屏 TUI 从「能用」推到「好用」 —
输入框真的能补全、真的能高亮、真的能弹命令面板、真的能 Ctrl+R
反向搜历史了。

| 轮 | 主题 | 测试 | 关键文件 |
|---|---|---|---|
| R21-A | Multi-line input | +22 | `InputBar` (full rewrite) |
| R21-B | Tab completion | +25 | `Completion` / `FilePathCompleter` / `SlashCommandCompleter` / `CompletionEngine` / `JLineCompletionAdapter` |
| R21-C | Syntax highlighter | +20 | `AnsiSegmentParser` / `InputBar.withSyntaxHighlighter` |
| R21-D | Slash command popup | +23 | `SlashCommandPopup` / `InputBar.withSlashCommandPopup` |
| R21-E | Ctrl+R reverse search | +9 | `InputBar.withHistorySearch` + `reverseSearch*` helpers |
| **总计** | | **+99** | |

测试进度：1413 → 1435 → 1460 → 1480 → 1503 → 1512

## 关键发现 / Pitfalls

### 1. ReplApp 不走 InputBar — R21-B/C/D/E 的接入是 InputBar-only

这是 R21 cycle 最大的认知差异。R21-A 写了 `InputBar`
（Lanterna + 多行），但 **R20-A 的 `ReplApp` 用的是 JLine 的
`LineReader`**，没有 InputBar 的引用。所以 R21-B/C/D/E 的
`withSyntaxHighlighter` / `withSlashCommandPopup` / `withHistorySearch`
挂在 `InputBar` 上 —— **它们**真的能工作，**但只对使用 InputBar 的
code path 起作用**。R21-B 的 `JLineCompletionAdapter` 是这 5 轮
里**唯一**一个真的进 `ReplApp` 的（接进 JLine `LineReaderBuilder.completer(...)`）。

R22 的工作：要么把 ReplApp 切到 InputBar（彻底告别 JLine，
TUI 才真正统一），要么给 JLine 也补上 popup / history search /
syntax highlight。后者工作量小、风险低，是更合理的选择。

### 2. Lanterna 3.1.3 `putString` 不接 raw ANSI

之前 R20-C 的总结里就提过 "putString rejects raw ESC"，
R21-C 实测确认：`IllegalArgumentException: Cannot create a
TextCharacter from a control character (0x1b)`。

**解法**：写 `AnsiSegmentParser` 把 `SimpleSyntaxHighlighter` 输出的
raw ANSI 字符串拆成 `List<Segment(text, TextColor, EnumSet<SGR>)>`，
然后每段用 `setForegroundColor` + `enableModifiers` 渲染。

`AnsiPutStringTest` 现在是反向断言（assertThrows）— 把这个
限制**钉死**在测试里，防止以后有人无意中以为可以直接 putString。

### 3. SlashCommandPopup 的「userClosed 状态机」

第一版 popup 用 try/finally 在每个 key 后 `syncSlashPopup`：
发现 `Esc` 关掉后，**finally 又把 popup 重新打开**。修法：
popup 加 `userClosed` sticky 标志，Esc 时设 true，
sync 只在 `!userClosed` 时 open。
**新 `/` 输入**会清掉这个标志（`clearUserClosed` 在 sync 里调），
所以用户删完 `/` 重新打还是能再次唤起。

### 4. 反向搜索 Enter 路径要早拦截

第一版 reverseSearch block 放在 `handleKeyInner` **中间**，
但 `if (type == KeyType.Enter) { ... return true; }` 在它**之前**。
结果：Enter 命中，return true，但 `reverseSearching` 没被清，
下一次按任意键又进 reverse-search 分支。

修法：把 reverse-search block 挪到 `handleKeyInner` **最前面**，
跟 popup block 并列；Enter 在 reverse-search 模式里只设
`reverseSearching = false`，然后 fall through 到普通 Enter
handler 让它 commit。

### 5. Bash `R` 的 fuzzy 评分有边角

`HistorySearch.score("git checkout -b feature", "gitc")` 返回
57（substring match + position bonus），不是 0。
测 "no match" 用例得用 `xyzzz` / `qqq` 这种绝对没 match 的字串，
不能用看起来"明显"但其实 fuzzy 命中的（"gitc" 就能 fuzzy 命中）。

### 6. pre-existing R5 flaky 测试在 R21 也没修

`mvn test` 全跑还是会撞 5 个 timing/parallel-flaky 测试
（`StreamingToolExecutorBackpressureTest` /
`NotificationCoalescerTest` / `TokenBucketRateLimiterTest` /
`ToolOrchestratorTest` / `SubagentPoolTest`），单跑都过。
R20 retrospective 就提过这些是 R5 就存在的，本轮继续放过。

R22 候选：**修**而不是绕 — 给这 5 个测试加 `@ResourceLock` /
`@Execution(CONCURRENT)` 或放宽阈值。

## 流程改进

- **先 reactor build 验 BUG，再写测试**：
  R21-B 第一次跑就发现 `BashTool.JOBS` 引用、FilePathCompleter
  2 个真 bug（bare filename dir 错、absolute path 没走 isAbsolute）
  全部一次跑暴露。`mvn -pl aethercode-tui -am test` 是标配。

- **PowerShell 引号坑**：`-Dtest='X' -Dsurefire.failIfNoSpecifiedTests=false`
  在 PS 里被切成两半，第二个 `-D` 解析成 unknown phase。
  解决：用 `mvn 'arg1' 'arg2' 'arg3'` 单独引。

- **测试断言宁弱毋严**：
  - `/agent` 既匹配 `agent` 又匹配 `agents` 是对的，别写 `assertEquals(1, ...)`
  - `HistorySearch.search("")` 排序非确定，别写 `assertEquals("grep -r TODO src", ...)`
  - `assertTrue(... || ... || ...)` 比 `assertEquals(specific, ...)` 更稳

## 接入 ReplApp 的覆盖率

| 特性 | InputBar | ReplApp (JLine) | 备注 |
|---|---|---|---|
| Multi-line 输入 | ✅ | ❌ (JLine 自带) | ReplApp 用 JLine multi-line |
| Tab 补全 | ✅ | ✅ via `JLineCompletionAdapter` | 5 轮里唯一进 ReplApp 的 |
| 语法高亮 | ✅ | ❌ | R22+ 接入 JLine 的 painter |
| Slash 弹窗 | ✅ | ❌ | R22+ 接入 JLine |
| Ctrl+R 搜历史 | ✅ | ❌ | R22+ 接入 JLine |

**用户体验差距**：R21 的成果挂在 InputBar 上，但 ReplApp 走
JLine。**实际用户**用的是 JLine 路径，**只感受到了 R21-B 的
Tab 补全**。R21-C/D/E 是「基础组件就绪 + InputBar 可用 +
ReplApp 集成是 R22 工作」。

## R22 候选

### 体验补全（接 JLine）
1. **JLine SyntaxHighlighter painter** — JLine 3 的
   `LineReader.setHighlighter()` 接我们 `SimpleSyntaxHighlighter`，
   不过要走 Lanterna 而不是 ANSI（类似 R21-C 那套）
2. **JLine popup for `/` commands** — JLine 3 的
   `Widgets.Menu` + `AttributedString` 渲染命令面板
3. **JLine Ctrl+R reverse search** — 监听 Ctrl+R key，
   用 `HistorySearch` 找匹配，临时替换 buffer

### Bug 修复
4. **修 5 个 R5 flaky 测试** — 加 `@ResourceLock` 隔离 / 放宽阈值

### 基础组件
5. **AnsiSegmentParser 也供 OutputStyle 用** — 当前 `OutputStyle`
   也是 raw ANSI 输出，可以接 parser 统一渲染

### 下一轮路线
6. **R22 选哪个**：JLine 体验补全是 1-2 轮能搞定的事；
   flaky 测试修一轮；R22 大概率 3-4 轮 TUI 收尾
