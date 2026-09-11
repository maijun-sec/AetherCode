# R21 路线图 — TUI 完善 (2026-08-06)

## 目标

把 R20-A 搭的全屏 TUI 从「能用 + 漂亮」升级到「好用 + 高效」。重点是输入体验：多行编辑、补全、语法高亮、历史搜索。

## 现状 (R20 末)

- Lanterna 全屏 + 实时 status bar + 任务树
- JLine 3 负责输入（InputBar 极简：type + backspace + enter + history）
- /命令由 ReplApp.handleCommand() 分发
- CommandPalette 已存在但粗糙

## 5 轮方案

| 轮 | 内容 | 预期测试 |
|---|---|---|
| R21-A | **Multi-line input** — Shift+Enter 插行 / Enter 提交；multi-line 数据结构；上下方向键在多行内导航再进入历史；空行 Enter no-op | +12 |
| R21-B | **Tab completion** — 文件路径补全 + /命令补全；`CompletionEngine` 接口 + 默认 `FilePathCompleter` + `SlashCommandCompleter` | +14 |
| R21-C | **输入区语法高亮** — 实时 tokenize + 上色；关键字 / 字符串 / 注释 / 数字；防抖（每 200ms 一次） | +10 |
| R21-D | **Slash 命令弹窗** — 输 `/` 时弹面板；fuzzy 过滤；↑↓ 选；Enter/Tab 接受 | +10 |
| R21-E | **Ctrl+R 历史反向搜索** — 类似 bash history-search；fuzzy 匹配；Enter 接受；Esc 取消 | +8 |

## 验证指标

- 进度：每轮 +N 测试，0 regression
- T21 末：~1500+ tests
- 体验：实际跑起来更顺手（multi-line paste 不卡、Tab 补全准、命令好找）

## 设计原则

- **R20 的 InputBar 仍保留** — R21 在它上面加层，不重写
- **新功能默认 on，旧行为不破坏** — 加 flag 让用户回退到 R20 模式
- **关键路径上做减法** — 不引入 5 层抽象，能直接调的就直接调
- **测试用 Lanterna 的 `DefaultVirtualTerminal`** — 不依赖真 TTY

## 关联

- R20 回顾：`docs/R20-RETROSPECTIVE.md`
- R20-A 全屏框架：`docs/R20A-FULLSCREEN-TUI.md`
- 上一轮 InputBar：`aethercode-tui/.../screen/InputBar.java`
- 现有 CommandPalette：`aethercode-tui/.../CommandPalette.java`
