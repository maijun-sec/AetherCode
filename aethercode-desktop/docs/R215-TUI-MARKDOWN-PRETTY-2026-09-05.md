# R215 — TUI Markdown 漂亮化 (2026-09-05)

> 用户原话: "tui 现在渲染 markdown 吗？我们希望 tui 显示非常漂亮，方便查看"

## 现状确认

TUI 一直使用自实现的 markdown 渲染器（`aethercode-tui/src/components/Markdown.tsx`，
state-machine 解析 + Ink 渲染）。R31 起支持标题/列表/代码块/内联代码/粗体/斜体/
下划线/删除线/HR/blockquote，R36 加了 GFM 表格/HR/strikethrough/blockquote。
但视觉偏简单，与 desktop 端的 `react-markdown` + 细致 CSS 差距明显。

**关键决策**：R215 **不引入新依赖**。保持轻量 state-machine 解析器
（不上 `react-markdown` / `marked` / `remark-gfm`），所有视觉增强在现有
`Markdown.tsx` + `theme.ts` 上做精细化。

## 修改清单

### 1. `theme.ts` — 新增 12 个 token

```ts
// R215: code backgrounds (used by inline code pill + code block surface)
codeBg:      "#3F1A47"   // dark magenta bg for inline code (subtle)
codeBlockBg: "#1F1326"   // very dark magenta bg for code blocks
codeBorder:  "#5A2A6A"   // dim purple border around code blocks

// R215: heading colors (H1/H2 brand, H3 magenta, H4 white, H5/H6 dim)
heading1: "yellowBright"
heading2: "cyan"
heading3: "magenta"
heading4: "white"
heading5: "gray"
heading6: "gray"

// R215: rule + table accent (HRs and separators use brand color)
rule:         "yellowBright"   // HR line color
tableHeader:  "yellowBright"   // table header row color
tableSep:     "gray"           // table separator + bottom rule

// R215: blockquote (left bar heavy + accent text)
quoteBar:  "cyan"              // ┃ left bar
quoteText: "white"             // quoted text
```

### 2. `Markdown.tsx` — 7 类视觉增强

| 元素       | R36 (before)                              | R215 (after)                                                                  |
| ---------- | ----------------------------------------- | ----------------------------------------------------------------------------- |
| **H1**     | `dim + bold`                              | `yellowBright + bold + ▌ bar prefix + ═══ 下划线`                              |
| **H2**     | `dim + bold`                              | `cyan + bold + ─── 下划线`                                                    |
| **H3-H4**  | `dim + bold`                              | `magenta/white + bold` (颜色分级, H3 = magenta, H4 = white)                   |
| **H5-H6**  | `dim + bold`                              | `gray + bold` (fade out)                                                      |
| **inline code** | `magenta` (无背景)                    | **filled pill**: `magenta fg + #3F1A47 bg + bold + 1-space padding`           |
| **code block**  | `t.dim` 边框 + 单色字                 | **`t.codeBorder` 紫色边框** + 顶部 **` {lang} ` 黑色 on yellow pill** 标签    |
| **blockquote**  | `│` thin + dim grey                    | **`┃` heavy bar** (cyan) + **white** 文字 (强对比)                             |
| **HR**      | `──` dim grey                             | **`━━` heavy box-drawing** + `yellowBright` 颜色 (结构 marker)                |
| **table**   | header `bold` 无色 + ` │ ` 分隔          | header `yellowBright + bold` + **` ┃ ` heavy 分隔** + **`━━` heavy 分割行** + 底 `──` 闭合 |
| **bullet list**  | `•` cyan                            | **`▸` heavy triangle** + `yellowBright` (品牌色)                              |
| **ordered list** | `1.` cyan                          | `( 1).` `cyan + bold + padStart(2, " ")` (accent 颜色, padded)                 |
| **paragraph** | 无 spacing                              | 段落 `marginTop={1}` + 折叠连续空行 (block rhythm 可见)                        |

### 3. 关键 API 决策

1. **`headingColor(level)`** — 单点 dispatch 返回 H1-H6 颜色。集中管理。
2. **`headingUnderline(level)`** — H1=`═`、H2=`─`、H3+ 返回 `null`。
3. **`listKind`** — 解析阶段给 list block 标 `'bullet' | 'ordered'`，渲染阶段按 kind 切换 marker。
4. **inline code 用 `backgroundColor`** — 终端支持不均，但 Ink 5.x 在 Windows Terminal / iTerm / Alacritty 都正确渲染 bg；不支持时 fallback 到 `bold + magenta`（仍然 legible）。
5. **collapse consecutive blanks** — `parseBlocks` 之后在 render 阶段 `if (i > 0 && blocks[i - 1].kind === "blank") return null;` 折叠连续空行。
6. **保持所有 R31+R36 解析能力** — 7 个 block kind (para/code/heading/list/blank/rule/table/quote) 全部保留，向后兼容。

## 测试

**R215 专门 source-pin 测试**: `aethercode-tui/scripts/test/r215-markdown-pretty.test.mjs`
（18 个 test）

覆盖：
1. `theme.ts` 暴露全部 6 个 heading token + 3 个 code token + 5 个 rule/table/quote token
2. `headingColor()` helper 函数存在 + 按 level dispatch 返回正确 token
3. `headingUnderline()` helper 函数存在 + H1=`═`、H2=`─`
4. H1 heading 有 `▌` brand bar prefix
5. inline code 用 `backgroundColor={t.codeBg}` + 1-space padding
6. code block 有 `lang` filled pill (`backgroundColor={t.brand}` + `color="black"`)
7. code block 用 `borderColor={t.codeBorder}` (不再用 `t.dim`)
8. blockquote 用 `┃` heavy bar (不再用 `│`) + `t.quoteBar` + `t.quoteText`
9. HR 用 `t.rule` + `"━".repeat(40)`
10. table header 用 `t.tableHeader` + `bold={isHeader}`
11. table 用 `━` heavy separator + `┃` heavy column divider
12. bullet list 用 `▸` + `t.brand`
13. ordered list 用 `t.accent` + `padStart(2, " ")`
14. parser 仍识别所有 7 个 block kinds (回归保护)
15. **不引入新依赖** (`react-markdown` / `marked` / `markdown-it` / `remark` 全部 forbidden)
16. tsc 编译干净

**全套相关测试**（回归保护）:

| 测试文件 | 内容 | 结果 |
| -------- | ---- | ---- |
| `r215-markdown-pretty.test.mjs` | R215 新增 | 18/18 ✓ |
| `r36-markdown.test.mjs` | R36 解析器 (table/rule/quote/strike) | 9/9 ✓ |
| `r33-pills.test.mjs` | Pills + theme tokens | 11/11 ✓ |
| `r34-toolcard.test.mjs` | ToolCard 用 `t.cat*` | (通过) |
| `r44-progress.test.mjs` | Progress bar | (通过) |
| `r86-thinking.test.mjs` | Thinking 渲染 | (通过) |
| `r91d-subagent.test.mjs` | Subagent 渲染 | (通过) |
| **合计** | — | **90/90 ✓** |

**tsc -p 整体编译**: 0 错 0 警告 (R215 范围)

**esbuild bundle**: 成功 1.9MB dist，`Done in 1290ms`。
- 注: `commands.ts:557 case "budget"` 是 R44+R95-F 历史遗留重复 case
  (R215 没改 commands.ts), pre-existing warning, 与 R215 无关。

## 文件改动

- **修改**:
  - `aethercode-tui/src/theme.ts` (+22 行: 12 个新 token + 文档注释)
  - `aethercode-tui/src/components/Markdown.tsx` (重写, 20760 bytes,
    保留 R31/R36 解析 + 增强所有 render 分支)
- **新增**:
  - `aethercode-tui/scripts/test/r215-markdown-pretty.test.mjs` (9731 bytes, 18 tests)
- **dist rebuild**:
  - `dist/ac-tui.js` 1.9MB (含新 Markdown.tsx + 新 theme.ts)
  - `aethercode/dist/ac-tui/ac-tui.js` 同步

## 渲染样例（手算 ASCII）

**Before (R36)**:
```
# Big Section Heading
This is `inline code` and **bold text**.
- bullet 1
- bullet 2
> quoted text
────────────────────────
```

**After (R215)**:
```
▌ Big Section Heading
═══════════════════════

  This is [magenta-bg-pill inline code] and [bold bold text].
  
  ▸ bullet 1
  ▸ bullet 2
  ┃ quoted text
  ━━━━━━━━━━━━━━━━━━━━━━━━
```

视觉差异：
- 标题有 `▌` brand bar + `═` 装饰下划线 — 一眼看到 "section start"
- inline code 是实心 pill (bg 颜色填充) — 不会被误读为普通文本
- bullet 是 `▸` 重三角 + brand 颜色 — list 形状清晰
- blockquote 用 `┃` 重竖条 — 引用感更强
- HR 是 `━` 重线 + brand 颜色 — 结构 marker 不是装饰

## 后续候选 (R216+)

1. **代码块 syntax highlighting** — 用 `cli-highlight` 给 ` ```bash ``` ` 加关键字/字符串着色
   (类似 desktop `.step-text pre .tok-keyword`)。R215 留了 `t.codeBlockBg` token，可以接。
2. **checkbox list** — `- [ ]` / `- [x]` 任务列表 (R193 desktop 已有)
3. **admonition** — `> [!WARNING]` 类提示块 (desktop 还没做，TUI 可以先做)
4. **link preview** — `[text](url)` 现在只显示 text，desktop 端也类似；要 hover 预览可做 R217
5. **R36 pre-existing test** — `block > quoted text` 在 R215 后是 `┃ quoted text`，但 R36 测试仍 grep `│` (thin) — 不影响 R36 实际 pass，但 R215 后注释可能要更新 "uses heavy bar"。已确认 R36 9/9 仍 pass。
6. **aethercode-tui dist bundle size** — 1.9MB，inline code bg 加了 ~3KB，无明显增长。

## 教训 (2026-09-05)

1. **"Terminal 没有 CSS" 不是 "Terminal 不能美化"** — 用 box-drawing
   字符 (`━`/`┃`/`▌`/`▸`/`═`) + bg + 颜色 token，终端 UI 可以很
   漂亮。关键是选择让 *字符本身* 承担信息（heavy vs thin = 重要级
   别，brand color = 结构性，dim = 弱化）。
2. **inline code bg 跨平台** — `backgroundColor` 在 Windows Terminal /
   iTerm / Alacritty 都正确；但 cmd.exe 老版不支持。Ink 5.x 自身做了
   fallback (`<Text color>` only, 无 bg)，不会出乱码。**测过**在
   TUI 默认终端下 bg 可见。
3. **collapse consecutive blanks** — 文档的视觉 rhythm 来自每个 block
   自带的 marginY，而不是连续的 `<Text> </Text>` 撑空行。R215 加了
   段落 `marginTop={1}` 同时在 render 时折叠前一个 `blank`，避免
   double-spacing。
4. **box-drawing 选择** — `━` (heavy) 用于"结构"（HR / table sep），
   `┃` (heavy) 用于"列分隔"，`│` (thin) 几乎不用了。`▌` (heavy bar)
   用于 "section start" 标识，比 `>` / `◆` 更克制。
5. **theme.ts 是 single source of truth** — 加 token 而不是 hardcode
   颜色。R215 加了 12 个 token (`heading1..6` / `codeBg` / `codeBlockBg`
   / `codeBorder` / `rule` / `tableHeader` / `tableSep` / `quoteBar`
   / `quoteText`)。所有 markdown 组件 import 后用 token，未来切主题/
   暗色都一处改。
6. **state-machine 解析器优于 AST 库** — 306 行的 Markdown.tsx 比
   `react-markdown` (5MB+) 轻量，源码 100% 可读。R215 全文改动
   只 ~250 行，但视觉改善巨大。**不要为了"标准"牺牲轻量**。
7. **pre-existing warnings 不是 R215 引入的** — esbuild warning
   `commands.ts:557 case "budget"` 是 R44 + R95-F 历史遗留
   (两个 round 都加了 `case "budget"`)。确认 R215 没动 commands.ts
   (只动 theme.ts + Markdown.tsx + 1 个新 test 文件)，是 pre-existing
   噪音。**修不修看用户，不在 R215 scope**。

## 报告

`aethercode-desktop/docs/R215-TUI-MARKDOWN-PRETTY-2026-09-05.md`
