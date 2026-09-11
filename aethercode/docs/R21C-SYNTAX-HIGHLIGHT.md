# R21-C Input Syntax Highlighter (2026-08-06)

## 目标

让 InputBar 在 paint() 时**实时**渲染 markdown 风格的高亮（代码
块、标题、加粗、斜体、内联代码、列表）。R8 写好的
`SimpleSyntaxHighlighter` 输出的是 raw ANSI，但 Lanterna 3.1.3 的
`putString` 拒绝 raw ESC（实测抛
`IllegalArgumentException: Cannot create a TextCharacter from
a control character (0x1b)`）。所以需要把 ANSI 解析成 Lanterna
友好的 segments 再 render。

## 改动

| 文件 | 角色 |
|---|---|
| `AnsiSegmentParser.java` | 拆 `ESC[...m` 包裹的字符串为 `List<Segment(text, TextColor, EnumSet<SGR>)>` |
| `screen/InputBar.java` | 新增 `withSyntaxHighlighter(SimpleSyntaxHighlighter)`；paint() 用 AnsiSegmentParser 渲染每行 |
| `screen/AnsiPutStringTest.java` | 反向断言 — 钉死 Lanterna 不接 raw ANSI |
| `screen/InputBarHighlighterTest.java` | 集成测试：backticks / headers / bullets / multi-line markdown 渲染不抛 |

支持的 ANSI codes（实际用到的）：
- 0: reset；1: bold；2: dim（无 Lanterna 等价，跳过）；3: italic
- 7: reverse；22-27: 关闭对应 modifier
- 30-37 / 39: 8 色 / 默认前景
- 90-97 / 99: bright 8 色 / 默认

## 关键 pitfall

1. **Lanterna `putString` 拒绝 raw ESC** — `AnsiSegmentParser` 必
   须把 ANSI 拆 segment 渲染，不能整串 putString
2. **TextColor 不可变** — `applyCodes` 用一个 `Style` 小 class
   持有可变 `color` 字段，不能用 `TextColor` 引用
3. **`SGR.DIM` 不存在** — Lanterna 3.1.3 的 `SGR` 只有
   `BOLD/REVERSE/UNDERLINE/BLINK/BORDERED/FRAKTUR/CROSSED_OUT/
   CIRCLED/ITALIC`，italic 用 `SGR.ITALIC`，没有 DIM 直接跳过

## ReplApp 接入

**没有接**。ReplApp 走 JLine 不是 InputBar，所以 R21-C 的成果
是 InputBar-only 的，JLine 路径的实际用户没感受到。
R22 候选：写一个 JLine 版本的 `Highlighter`，
调用 `SimpleSyntaxHighlighter` + `AnsiSegmentParser`。

## 测试

| 文件 | 测试数 |
|---|---|
| `AnsiSegmentParserTest` | 11 |
| `screen/AnsiPutStringTest` | 3 |
| `screen/InputBarHighlighterTest` | 6 |
| **R21-C 小计** | **+20** |

AetherCode 总数：1460 → **1480**，0 regression。

## 下一步

R21-D：把 `CommandPalette` 接到 InputBar，做一个
`SlashCommandPopup`（↑↓ 选 / Enter 接受 / Esc 关闭），
auto-open 当用户打 `/` 的时候。
