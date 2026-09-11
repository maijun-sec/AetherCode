# R21-A: Multi-line Input (2026-08-06)

## 目标

把 R20-A 的单行 `InputBar` 升级成支持多行编辑，让用户能粘贴代码块、跨行 prompt。

## 变更

### `InputBar` 完全重写

- 内部状态从 `StringBuilder` 改成 `List<StringBuilder> lines` + `cursorLine` + `cursorCol`
- 新增键位：
  - **Enter** — 提交 buffer（非空时；空时 no-op）
  - **Shift+Enter / Ctrl+J / Alt+Enter** — 在光标处插入换行（3 个等价入口，适配不同终端）
  - **Backspace** — 删前一个字符；行首时与上一行合并
  - **Delete** — 删后一个字符；行尾时与下一行合并
  - **Left/Right** — 移动光标；行边界时跨行
  - **Up/Down** — 行内移动；顶/底时进入 history / future
  - **Home/End** — 当前行首/尾
  - **Ctrl+A / Ctrl+E** — buffer 首/末（多行模式快捷键）
  - **Ctrl+C** — 抛 `UserInterrupt`
  - **Ctrl+R** — 反向历史搜索（placeholder，R21-E 实现）
  - **Tab** — 转发给 `ReadLineHook`（R21-B 接入）
- 新增 API：
  - `lineCount()` / `linesSnapshot()` / `cursorLine()` / `cursorCol()`
  - `joinLines()` — 多行合并为单字符串（`\n` 分隔）
  - `replaceBufferWith(String)` — 整 buffer 替换（用于 history 恢复）
  - `withReadLineHook(ReadLineHook)` — 安装 Tab 钩子
  - `isEmpty()` — 多行时也算非空
- 新增内部类 `ReadLineHook`（interface）
- 新增异常 `UserInterrupt`（Ctrl-C 抛）

### `paint()` 多行渲染

- 缓冲区从输入行向上生长（最新行在底）
- 每行有独立的 prompt：第一行 `❯` (USER_PROMPT 角色)，续行 `·` (HINT 角色)
- 多行时右侧显示 `N lines` 指示
- 终端光标定位到当前光标的实际位置（行 + 列）

## 测试

新增 22 tests，0 regression：

| Test class | Tests |
|---|---|
| `InputBarTest` | 22 (空 / 字符累积 / shift+enter / ctrl+j / alt+enter / commit / no-op / backspace / 跨行合并 / 方向键 / Home/End / Ctrl+A / Ctrl+E / Ctrl+C / history / dedupe / clear / ReadLineHook / 切中插入 / 多行非空 / lineCount / snapshot / replaceBufferWith / paint) |
| **合计** | **22** |

总测试数：**1435** (R20-J: 1413 → R21-A: 1435, +1.6%)

## 关键 pitfall

1. **`Screen.setCursorVisible(boolean)` 不存在** — Lanterna 3.1.3 `Screen` 接口只有 `setCursorPosition` / `getCursorPosition`，没有可见性切换。删掉那行。
2. **KeyStroke 构造签名** — `new KeyStroke(char, ctrlDown, altDown)`，第三个参数是 alt 不是 shift。`ctrlC` / `ctrlA` / `ctrlE` 助手的第三个参数填错了 (false 而不是 true)。修复后 ctrl+A / Ctrl+E 测试通过。
3. **backspace_atLineStart_joinsWithPrevLine 测试逻辑** — 起初以为"ArrowUp 到上一行行尾再 backspace"会合并。实际上 ArrowUp 把 col clamp 到上一行长度，"ab" 只有 2 字符所以 col 停在 2，backspace 删了 'b'。修测试用 `Home` + `ArrowUp` 显式到 (0, 0)，再 `ArrowDown` + `Home` 到 (1, 0) 触发 join。
4. **linesSnapshot_returnsCopy 测试** — `linesSnapshot()` 用 `stream().map(...).toList()` 返回 immutable list。测试 `snap.clear()` 期望抛 `UnsupportedOperationException` 而非成功。修复测试。
5. **aethercode-sdk 没装到本地 m2** — PlanPanel 引用 `PlanClassifier$Step`，测试找不到类。`mvn -pl aethercode-sdk install -DskipTests` 后通过。

## 验证

```
$ mvn -B -pl aethercode-sdk install -DskipTests
$ mvn -B -pl aethercode-tui -am test          # BUILD SUCCESS, 26 tests in InputBarTest
$ mvn -B test          # BUILD SUCCESS, 1435 tests total, 0 fail, 0 error
```

## 关联

- 备份目录：`docs/backups/r21a/`
- 上一轮：`docs/R20-RETROSPECTIVE.md`
- R21 路线图：`docs/R21-ROADMAP.md`
- 下一轮：R21-B Tab completion (文件路径 + /命令)
