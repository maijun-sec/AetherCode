# R20-A: TUI 全屏框架 (2026-08-06)

## 目标

把 R19 的行式 REPL 升级为全屏 TUI。引入 Lanterna 3.1.3 作为显示层，保留 JLine 3 作输入层。

## 变更

### 依赖
- 新增 `com.googlecode.lanterna:lanterna:3.1.3` (MIT, ~250KB)
- `aethercode-tui/pom.xml` 加依赖
- 父 `pom.xml` 加 `lanterna.version` 属性

### 新增类 (`aethercode-tui/src/main/java/.../screen/`)
- `LayoutState` — 帧间状态 (mutable POJO)：brand / model / session / 当前工具 / elapsed / token / plan progress
- `Layout` — 三段布局 (record)：header row + main area + status row + input row
- `HeaderBar` — 顶部 1 行：brand · spinner+status · 右侧 model · session · mode · tool palette
- `StatusBar` — 底部 1 行：plan 进度 | 当前工具 + 预览 | token 进度条 (10 格 ▓░) + 百分比 | elapsed + hint
- `MainViewport` — 中间区：根据 `historyViewOffset` 渲染 `Scrollback` 的最后 N 行 (带 ANSI 剥离)
- `InputBar` — 最小行编辑器：type + backspace + enter + 上下历史；Ctrl-C 抛 `UserInterrupt`
- `ReplScreen` — Lanterna `TerminalScreen` 生命周期包装：`start()` / `startVirtual(cols,rows)` / `repaint()` / `close()`

### `TerminalPalette.stripAnsi`
- 新增 ANSI 转义序列剥离 (CSI)，给 `MainViewport` 用 — Lanterna 的 `putString` 不接受裸 ESC

### ReplApp 改动
- 新增 `screen` / `queryStartMs` / `lastEventAtMs` 字段
- 新增 `withFullScreen(ReplScreen)` 和 `closeScreen()` 钩子
- `runOne()` 每次事件后调用 `updateScreenState(ev)` + `repaintScreen()`
- 新增 `repaintScreen()` / `updateScreenState(StreamEvent)` / `estimateTokensUsed()` / `countRunningTasks()`
- 状态映射：RunStart → "thinking" / TextDelta → "writing" / ToolUseStart → 工具名 + 预览 / ToolResult → 清空 / RunEnd → 状态

### Main 改动
- 新增 `--fullscreen` (默认 on) 和 `--no-fullscreen` flag
- TTY 自动检测：`System.console() != null` 才开 fullscreen；非 TTY（CI / IDEA 插件宿主）自动回退行式
- `runRepl()` 在 screen 失败时 fallback 到 line mode

## 测试

新增 31 tests，0 regression：

| Test class | Tests |
|---|---|
| `LayoutStateTest` | 7 (tokenPercent / spinner / snapshot / defaults) |
| `LayoutTest` | 6 (zone 计算 / 太小不渲染) |
| `StatusBarTest` | 6 (fmtTokens / formatElapsed) |
| `ReplScreenTest` | 8 (lifecycle / state / repaint / 太小屏幕) |
| `InputBarTest` | 4 (history dedupe / cap / empty / clear) |
| **合计** | **31** |

总测试数：**1258** (R19: 1227 → R20-A: 1258，+2.5%)

## 验证

```
$ mvn -B test         # BUILD SUCCESS, 1258 tests, 0 fail, 0 error
$ mvn -B -pl aethercode-cli package -DskipTests
$ java -jar aethercode-cli-0.1.0-SNAPSHOT-shaded.jar --version
  → aethercode 0.1.0
$ java -jar ... --help | grep fullscreen
  → --fullscreen, --no-fullscreen
```

## 已知限制 / 下一步

- `MainViewport` 暂时不染色（剥离 ANSI 后用 default 色）。R20-C 会换成 box-drawing 风格的面板。
- `InputBar` 不支持 completion / multi-line paste。R20-C 加。
- 没有 token-per-second 计数器。R20-B 加。
- 没有长任务 watchdog。R20-J 加。
- 10 个 round 中的 TUI 视觉部分只完成了 1 个。还需要：
  - R20-B 实时进度（thinking / 当前工具 / token delta）
  - R20-C 配色 + 边框 + 启动 banner
  - R20-G 任务树可视化

## 关联

- 见 `docs/R20-ROADMAP.md` 的 10 轮规划
- 备份目录：`docs/backups/r20a/` (待备份)
- 下一轮：R20-B 实时进度
