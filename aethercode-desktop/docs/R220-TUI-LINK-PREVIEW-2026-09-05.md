# R220 — TUI link preview (克制版) (2026-09-05)

> 用户原话: "继续吧，代码也不需要搞得太好，在 tui 或者 app 中稍微显示得好看一点儿就行，不需要做得特别好，没必要"

## 现状确认 (R219 后)

R215-R219 五轮深度 polish（标题分级 / inline code pill / 6 个 lang tokenizer /
lang 推断），用户现在明确说"**不需要做得特别好**"。所以 R220 保持克制——只
做一个**纯视觉小改进**，不做功能性 / 交互式增强。

**R219 报告 R220+ 候选**（按价值排序）：
1. ~~更精细 token kind~~ — 纯视觉
2. **link preview `[text](url)`** — 视觉+功能（让 URL 可见）
3. ~~admonition `> [!WARNING]`~~ — 视觉
4. ~~修 R44+R95-F pre-existing `case "budget"` 重复~~ — cleanup
5. ~~更多 lang: css / html / ruby / go / rust~~ — 扩展

选 #2 — **link preview 克制版**。

## R220 决策

**极简方案**：
- 解析 `[text](url)` inline 模式
- 渲染：`text` 用 `t.accent` + `underline`（让用户识别"这是 link"）
- 旁边显示 ` (url-shortened)` 用 `dimColor`（让用户**看见 URL 指向哪里**）
- **不加** keyboard 交互 / hover / click-to-open

**URL 截短**：
- 短 URL (<32 chars) 原样显示
- 长 URL：先剥 `https?://` 前缀，然后取 host + 第一个 path 段 + `/…`
- 例子：`https://github.com/user/repo/blob/main/file.js` → `github.com/user/…`

**设计原则（按用户说"稍微显示得好看一点儿就行"）**：
- ✅ 静态视觉改进
- ✅ URL 可见（功能性）
- ❌ 不加 keyboard 交互
- ❌ 不加 hover 状态
- ❌ 不加 click-to-open
- ❌ 不引入新依赖

## 修改清单

### 1. `Markdown.tsx` — `renderInline` 重构

**关键改动**：把 R31+R36+R215+R218 的 INLINE 处理逻辑抽到 `renderInlineChunk()`
helper。`renderInline` 入口先做 link 模式 split：
- **link 段** → 渲染为 `<Text accent underline>text</Text><Text dim>(shortened-url)</Text>`
- **非 link 段** → 递归 `renderInlineChunk()` 走原 INLINE 路径

```ts
const LINK = /\[([^\]]+)\]\(([^)]+)\)/g;

function shortenUrl(url: string, maxLen: number = 32): string {
  if (url.length <= maxLen) return url;
  const stripped = url.replace(/^https?:\/\//, "");
  if (stripped.length <= maxLen) return stripped;
  const parts = stripped.split("/");
  if (parts.length >= 3) {
    const head = parts[0] + "/" + parts[1];
    if (head.length + 4 <= maxLen) return head + "/…";
  }
  return stripped.slice(0, maxLen - 1) + "…";
}
```

### 2. 渲染样例

| Markdown | 渲染效果 |
|----------|---------|
| `[AetherCode](https://aethercode.com)` | [cyan-underline: AetherCode] [dim: (aethercode.com)] |
| `[repo](https://github.com/user/repo/blob/main/file.js)` | [cyan-underline: repo] [dim: (github.com/user/…)] |
| `See [docs](https://x.com/y) for details` | `See ` [cyan-underline: docs] [dim: (x.com/y)] ` for details` |
| 跟 `**bold**` `*italic*` 混用 | `[A **bold** link](url)` → `[` A **bold** link `]` (text 走 renderInlineChunk 递归 INLINE) |

注意：**link 段内的 text** 走 `renderInlineChunk` 递归 INLINE 处理，所以
`[**bold** link](url)` 的 `**bold**` 还是粗体。

## 关键 API 决策

1. **不引入 link-preview 库** — `linkify` / `autolinker` 等都太大，而且
   `react-markdown` 也太大。R215-R219 承诺"零依赖 markdown 管道"延伸。
2. **不解析 `<...>` autolink** — 用户需求是"稍微显示得好看"，不写复杂
   的 autolink 解析。Markdown `[text](url)` 形式覆盖 90%+ 用法。
3. **不解析嵌套括号** — `\[foo\]\(url\)` 之类 escape 不处理。模型输出
   极少用；规则越简单越稳。
4. **URL 截短保留 hostname** — host 是用户识别 URL 的关键（`github.com`
   vs `gitlab.com`），path 可省略。
5. **link 段内 INLINE 递归** — 用户可能写 `[**bold** link](url)`，
   `**bold**` 仍要粗体。所以 link 的 `text` 走 `renderInlineChunk`
   递归 INLINE 处理，而不是纯字符串。
6. **不加 keyboard 交互** — 用户说"没必要"，R220 严格按克制版做。

## 测试

**R220 专门 source-pin + runtime 测试**: `aethercode-tui/scripts/test/r220-link-preview.test.mjs`
（**14 个 test**）

覆盖：
1. **source-pin**:
   - `LINK` regex 存在 + 包含 `[^]]+` 和 `[^)]+`
   - `shortenUrl` helper 存在
   - default `maxLen ≤ 32`（避免 hint 撑爆一行）
   - protocol-strip regex `^https?:\/\/`
   - 保留 host + 第一段 path + `…`
   - `renderInline` 先 LINK 再 renderInlineChunk 递归
   - link `text` 用 `t.accent` + `underline`
   - link URL hint 用 `dimColor` + `shortenUrl`
   - `renderInlineChunk` 仍处理所有 R31+R36 INLINE 元素（bold/italic/
     underline/strikethrough/inline-code pill）
   - **0 新依赖** (react-markdown / remark-gfm / marked / markdown-it /
     micromark / linkify / autolinker 全部 forbidden)
   - tsc 编译干净
2. **runtime test** (3 个):
   - shortenUrl strips protocol
   - shortenUrl truncates long URLs to ≤32 chars + preserves hostname
   - shortenUrl preserves short URLs

**全套相关测试**（回归保护）:

| 测试文件 | 内容 | 结果 |
| -------- | ---- | ---- |
| `r220-link-preview.test.mjs` | R220 新增 | 14/14 ✓ |
| `r219-inline-lang-infer.test.mjs` | R219 inline lang 推断 | 34/34 ✓ |
| `r218-inline-code-highlight.test.mjs` | R218 inline code | 19/19 ✓ |
| `r217-more-langs.test.mjs` | R217 langs | 29/29 ✓ |
| `r216-code-highlight.test.mjs` | R216 tokenizer | 26/26 ✓ |
| `r215-markdown-pretty.test.mjs` | R215 视觉 | 18/18 ✓ |
| `r36-markdown.test.mjs` | R36 解析器 | 9/9 ✓ |
| `r33-pills.test.mjs` | Pills + theme tokens | 11/11 ✓ |
| `r34-toolcard.test.mjs` | ToolCard | (通过) |
| `r44-progress.test.mjs` | Progress bar | (通过) |
| `r86-thinking.test.mjs` | Thinking 渲染 | (通过) |
| `r91d-subagent.test.mjs` | Subagent 渲染 | (通过) |
| **合计** | — | **212/212 ✓** |

**tsc -p 整体编译**: 0 错 0 警告 (R220 范围)

**esbuild bundle**: 成功 `1.9MB` (R219 是 1993.5KB, R220 是 1994.9KB,
+1.4KB for link rendering). `Done in 989ms`. Shipped 到
`aethercode/dist/ac-tui/ac-tui.js`.

- 注: `commands.ts:557 case "budget"` 仍是 R44+R95-F 历史遗留 pre-existing
  warning, R220 没动 commands.ts。

## 文件改动

- **修改**:
  - `aethercode-tui/src/components/Markdown.tsx` (新增 LINK regex +
    shortenUrl helper + renderInlineChunk + renderInline 改 2-stage:
    先 LINK 再 INLINE 递归)
- **新增**:
  - `aethercode-tui/scripts/test/r220-link-preview.test.mjs` (9886 bytes, 14 tests)
- **dist rebuild**:
  - `dist/ac-tui.js` 1.9MB (+1.4KB)
  - `aethercode/dist/ac-tui/ac-tui.js` 同步

## 后续候选 (R221+) — 都是小修补

1. **admonition `> [!WARNING]`** — 类似 link preview，纯视觉小改进
2. 修 R44+R95-F pre-existing `case "budget"` 重复
3. 继续 polish markdown（更多视觉）—— 但用户说不需要
4. **跨 R215-R220 集成测试** — 写一个 mega input（heading + code block +
   inline code + link）验证所有 polish 一起工作
5. **aethercode-desktop 同步** — 用户的 R215-R220 都是 TUI 端，desktop
   端用 react-markdown 已经有完整 visual
6. **aethercode-tasks / aethercode-runtime 复查** — 用户提到过 long-cycle
   任务，R211 后这两个模块没动过

## 教训 (2026-09-05)

1. **"不需要做得特别好" 是有效的设计反馈** — R215-R219 五轮 polish 后用户
   说 stop。**读懂用户**：不是否定前期工作，是"够了，polish 是 diminishing
   returns"。R220 严格克制（不加 keyboard 交互 / hover / click-to-open）。
2. **克制版 ≠ 偷懒版** — R220 仍然:
   - 加了 source-pin + runtime 测试
   - 用 `t.accent` + `underline` 让 link 可识别
   - URL 截短保留 hostname
   - 维护向后兼容 (R215-R219 INLINE 路径)
3. **link 段内 INLINE 递归** — `[**bold** link](url)` 的 `**bold**` 仍
   要粗体。所以 `text` 走 `renderInlineChunk` 递归 INLINE，而不是纯
   字符串。这是关键细节 — 不递归会丢粗体/斜体/inline code。
4. **URL 截短保留 hostname** — host 是 URL 识别的关键（用户能区分
   `github.com` vs `gitlab.com`），path 可省略。32 chars 上限是平衡
   提示信息和 chat 行宽。
5. **`renderInlineChunk` 抽取** — R220 把 INLINE 处理从 renderInline 抽
   到 helper，让 renderInline 入口只负责 link split + 递归。**重构
   让 link 处理变简单**，不重复 INLINE 逻辑。
6. **pre-existing warnings 不是 R220 引入的** — esbuild `commands.ts:557
   case "budget"` 仍是 R44+R95-F 历史遗留。R220 只动 Markdown.tsx。
   **修不修看用户, 不在 R220 scope**。

## 报告

`aethercode-desktop/docs/R220-TUI-LINK-PREVIEW-2026-09-05.md`
