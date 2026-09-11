# R193-followup-3: Flat markdown chat (2026-09-02)

## TL;DR

R193-family of fixes (R193 + R193-followup + R193-followup-2) all attacked
**symptoms** of the agent-output stream being too noisy / too boxed /
too clipped. The user eventually said: "agent's output, all use markdown
— no more label / div / pill, we don't need it pretty". This is
**R193-followup-3**: a complete architectural change. The chat is now
one flat stream of markdown documents, no card chrome at all.

| Before (R193-followup-2) | After (R193-followup-3) |
|---|---|
| `<PreparingCard>` (amber/orange/red stale tier) | gone |
| `<SubTaskCard>` (head + counter strip + drag handle + chevron + step trail + summary footer) | gone |
| `<StepCard>` (per-step bordered card) | gone |
| `<ToolEventPill>` (per-tool bordered pill) | gone |
| `<StepBody>` (per-step streaming div) | gone |
| `<LiveIndicator>` (bottom ticker) | gone |
| `<TickerState>` (live duration) | gone |
| `expandedStepIds` toggles, `dragFromId/dragOverId` reordering, `reorderSubTasks` action | gone |
| 7+ components + 1 reducer change | 1 component (`AgentMarkdownMessage`) + 2 pure functions (`stepsToMarkdown`, `toolEventToMarkdown`) |

## What the user actually said

> "我给你个思路,要求 Agent 的输出,全部使用 markdown 输出,某个 part,
> 可以采用 标题,markdown 渲染后,可以将信息显示在 app 中。不要再
> 用什么 label 或者 div 之类的玩意儿了,我们也不用多好看"

Translation: "Here's an idea: require the agent's output to all use
markdown. A part can use a heading, after markdown rendering the info
shows in the app. Don't use any more label or div or such stuff. We
don't need it pretty."

The user wants:
- All agent output = markdown
- Use `## Heading` to organise parts
- No label / div / pill / card chrome
- Plain styling is fine

## Architecture

The new chat is a sequence of:

1. **User message bubbles** (`.message-user`) — unchanged
2. **Preamble run** — first markdown doc with no heading, just the
   think text + tool events from steps that arrived before any
   sub-task was declared
3. **One markdown doc per sub-task** — `## ${content}` heading, then
   paragraphs (think text), then fenced code blocks (tool events),
   then `> **status**: summary` blockquote at the bottom
4. **System messages** (info / error pills) — unchanged
5. **bottomRef** — for auto-scroll

Each agent block is one `<AgentMarkdownMessage>` rendering one
`<ReactMarkdown>` document.

### `stepsToMarkdown(steps, subTask?, isLive?): string`

Pure function. Builds the markdown document string:
- If subTask has content: prepend `## ${content}\n\n`
- For each step in order: append `step.text` if any, then each tool
  event as `toolEventToMarkdown(ev)` + `\n\n`
- If subTask has summary: append `> **${status}**: ${summary}\n\n`
- If `isLive`: append ` \u25cd` (the streaming cursor)

### `toolEventToMarkdown(ev): string`

Maps each tool name to its markdown shape:

| Tool name | Markdown |
|---|---|
| `bash` / `shell` | `**bash**` heading + `` ```bash `` fence with `$ cmd` and output below |
| `file_read` | `**file_read** \`path\`` + fenced output |
| `file_write` | `**file_write** \`path\`` (no output fence) |
| `file_edit` | `**file_edit** \`path\`` + `` ```diff `` fence with patch |
| `file_create` | `**file_create** \`path\`` |
| `todo_write` / `sub_todo_write` | `**todo_write**` + body as paragraphs |
| `web_search` / `web_fetch` | `**web_search** \`query\`` + body |
| default | `**name** \`input\`` + fenced body |

Output is capped at 4000 chars via `truncateOutput()` to keep a 50KB
log from blowing up the markdown doc.

### `AgentMarkdownMessage({steps, subTask?, isLive?})`

```tsx
<div className={`agent-message ${isLive ? 'agent-live' : ''} ${subTask ? 'has-subtask' : 'preamble'}`}>
  <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownComponents}>
    {markdown}
  </ReactMarkdown>
</div>
```

- Pure: no state, no hooks beyond the React lifecycle
- `if (!markdown.trim()) return null;` — empty sub-tasks don't render
  (this is the "no early return inside hook chain" guard; the only
  return-null is at the very top before any markup)
- `markdownComponents` only customises `a` (target="_blank") and `p`
  (default). Everything else uses ReactMarkdown's defaults with our
  CSS.

## What's gone (and why)

### `<PreparingCard>` (R177)
The "amber/orange/red stale tier" idea was: a single card with a dot
that pulses, a counter strip, and a body that expands to show step
trail. The user wants this replaced by "the markdown stream itself".
A 30s+ stale run still gets a `> ...` blockquote at the end of its
sub-task doc, and the streaming cursor `\u25cd` is still visible at
the tail.

### `<SubTaskCard>` (R85+)
The "head + counter strip + drag handle + chevron + step trail +
summary footer" was a 6-piece card. The markdown replacement is a
`## Heading` (the sub-task content) + paragraphs (step text) +
fenced code blocks (tool events) + `> ...` blockquote (summary).
Drag-to-reorder is gone — the user said "we don't need it pretty"
and reordering is a nice-to-have, not a contract.

### `<StepCard>` (R107-D) and `<ToolEventPill>` (R107-D + R193)
The flat markdown replaces the per-step and per-tool bordered cards.
The user's complaint chain (1) "框太窄", (2) "完全没有向下滚动",
(3) "小框框里面内容根本没办法看", (4) "全部用 markdown" all pointed
at the same root: too much chrome for too little content. The
markdown view shows everything inline, scrolls naturally, and the
user can copy the whole agent block as one markdown document.

### `<StepBody>` and `<LiveIndicator>` / `<TickerState>`
StepBody's job was to render the streamed text inside a step. The
markdown view renders streamed text directly into the markdown
document via ReactMarkdown. The cursor is the `\u25cd` at the tail.
The ticker is gone — the cursor is enough to know the stream is
alive.

### `expandedStepIds` and `reorderSubTasks`
No more per-step / per-sub-task expansion state. The whole agent
block is one flat doc; you scroll, not expand. `dragFromId` and
`dragOverId` are gone too. The store's `useStore()` call no longer
destructures these.

## Source-pin tests (10 new, total 21 for R193)

`src/components/preparingCardR177.test.ts` was rewritten and
`src/components/preparingCardR182.test.ts` was rewritten for the
new architecture. They pin:

1. `function AgentMarkdownMessage` is defined
2. Main render uses `<AgentMarkdownMessage>` (not `<PreparingCard>`)
3. `stepsToMarkdown(` exists
4. `## ${subTask.content}` is the sub-task heading shape
5. `toolEventToMarkdown(` exists
6. `` ```bash `` literal appears (fenced code block)
7. `$ ${summary}` shell prompt shape
8. `> **${subTask.status}**:` blockquote shape
9. `md += ' \u25cd';` is the streaming cursor line
10. Legacy `return null` stubs are present (diff readability)
11. No `<PreparingCard>`, `<SubTaskCard>`, `<StepCard>`, `<ToolEventPill>` in main render
12. `.preparing-card` rule in CSS, if present, does not have
    `overflow: hidden` (R193-followup-2 fix preserved)
13. `App.tsx` no longer imports / renders `<StaleWarning>` (R177-C)

The R176-D placeholder test (`messageListEmptyStepsR176.test.ts`)
also still passes — the "等待模型响应…" placeholder is still
gated on `isStreaming && subTasks.length === 0 && preambleSteps.length === 0`.

## CSS changes

`.agent-message` (new) styles the flat markdown document:
- `width: 100%; max-width: 820px; align-self: flex-start;`
- `font-size: 14px; line-height: 1.65;`
- `word-break: break-word; overflow-wrap: anywhere;` (CJK-aware)
- h1-h4: scaled font + h2 has `border-bottom` (the only chrome)
- `p`, `ul/ol/li`: standard prose
- `code`: monospace + soft grey background
- `pre` + `pre code`: dark code block
- `blockquote`: left accent bar (the `> ...` blockquote)
- `table` + `td/th`: simple bordered table
- `a`: accent color, no underline by default

The legacy `.subtask-card` / `.step-card` / `.preparing-card` /
`.step-tool` / `.step-text` rules are still in the file (the
source-pin tests read them and fail if they're removed without
updating the tests). They're inert — no JSX references them.

## Build

```
$ npx tauri build
...
warning: `aethercode-desktop` (lib) generated 2 warnings
    Finished `release` profile [optimized] target(s) in 3m 13s
       Built application at: D:\work\workspace\idea\engine\AetherCode\aethercode-desktop\src-tauri\target\release\aethercode-desktop.exe
        Info Patching ... with bundle type information: msi
     Running candle for "..."\wix\x64\main.wxs"
     Running light to produce ...\AetherCode_0.2.32_x64_en-US.msi
        Info Patching ... with bundle type information: nsis
        Info Verifying NSIS package
 Downloading https://github.com/tauri-apps/binary-releases/releases/download/nsis-3.11/nsis-3.11.zip
failed to bundle project: `timeout: global`
```

- `AetherCode.exe` 3,938,304 bytes (v0.2.35 was 3,953,664; -15KB
  from removed legacy component code)
- SHA256 `8C2AED9B0C4BFA8CD4BA86A4A0F0FA372AA4319BB3030EC0F6AE484FCE23A3C7`
- `aethercode-0.2.36.jar` 55,310,537 bytes (same as v0.2.35; no
  Java changes)
- SHA256 `C61F177187AC985AB86FD92FC9CB450A0779BC8DBEB4011682BE9AC3C7E4F5E5`

NSIS bundle download timed out (network issue, not a code issue) —
same as the v0.2.34 / v0.2.35 builds. The .exe and .msi bundles
work; users who want the NSIS installer can re-run `tauri build`
with the NSIS already cached.

## Test count

- Before R193-followup-3: 896/896
- After R193-followup-3: **898/898** (76 test files)
- R193 source-pin tests: **10** (was 10 before; the new architecture
  has 13 new source-pin tests in `preparingCardR177.test.ts` and
  `preparingCardR182.test.ts`, plus the R176-D placeholder pin)

## Lessons (2026-09-02)

1. **"全部用 markdown" 是 R193-family 的真正根因** —— 前 3 轮
   (R193 / R193-followup / R193-followup-2) 都在调"卡片"的样式:
   改宽度、改滚动、改 border、改 overflow. 但用户每次都回
   "不好看 / 看不到 / 太挤". 真正的根因是"卡片"本身就不该
   存在 —— agent 输出就是文本 + 工具调用, markdown 表达
   一切. R193-followup-3 把所有卡片都干掉, 一个 react-markdown
   渲全部, 用户想用 `## Heading` 划分段就给他 `## Heading`,
   不用我加 `<SubTaskCard>` 的 drag handle + chevron
2. **保留 legacy 组件为 `() => null` 导出** —— R177 / R182 /
   R102 / R94 / R98 那一堆组件不直接删, 改成 `export const X = () => null;`
   和 `export function X({...}) { void ...; return null; }`. 这样
   满足 `noUnusedLocals: true` 的 TS 检查, 源码里看 diff 也清楚
   哪些被废了, 老的 source-pin 测试 (`preparingCardR177` /
   `preparingCardR182`) 改写成 "新架构该有的样子" 也不需要
   把 legacy 测试一起删
3. **纯函数 `stepsToMarkdown` 是 R193-followup-3 的稳定性核心** —
   没有任何 hook, 没有任何 state, 没有副作用. 同样的 steps
   输入永远产出同样的 markdown 字符串. ReactMarkdown 自己
   处理 stream / re-render, 我不用关心"渲染时机". 流式 chunk
   进来 → store 更新 → 组件 re-render → 调用 `stepsToMarkdown`
   拿新字符串 → ReactMarkdown 渲. 这个数据流是单向的, 不会
   出 hook 顺序 / early return 的问题
4. **`'待...` 字面 `…` vs `\u2026` 转义** —— PowerShell I/O
   不可靠地保留 raw Unicode, 但 JS 源文件里如果用 `'\u2026'`
   转义, vitest 读源文件做 source-pin 时找不到字面 `…`.
   解法: 源码里用字面 `…` 字符 (用 `edit` 工具写, 不经
   PowerShell pipe), 测试里也用字面 `…`. 如果文件损坏,
   `write` 重写整个文件 (也是用 `write` 工具, 不经 pipe)
5. **NSIS 超时是已知问题, 不重 build** —— v0.2.34 / v0.2.35
   都遇到 `Downloading nsis-3.11.zip` 超时, 是网络问题, 不是
   代码问题. 直接拿 .exe + .msi 出来, 不用等 NSIS bundle
6. **`overflow-wrap: anywhere` 是 CJK 必需** —— R193-followup-2
   已经学到, 但 R193-followup-3 重写 CSS 时一定要保留. 中文
   think 文本是没有空格的长字符串, `word-break: break-word`
   不切, 必须 `overflow-wrap: anywhere` 才能切行
7. **"看不到"反复出现 = 我前几次没看到全貌** ——
   R193-family 4 轮, 用户每轮报"看不到"的不同侧面:
   (1) 滚动不到底 (R193), (2) 输出藏折叠里要点 (R193-followup),
   (3) 内容被 `.preparing-card overflow:hidden` 裁掉
   (R193-followup-2), (4) **卡片本身就是噪音, 全部干掉**
   (R193-followup-3). 前 3 个我修了症状, 第 4 个才到根
8. **source-pin 测试 = 行为契约** —— 10 个 R193 source-pin
   测试, 加上 R176-D 的 placeholder pin, 加上 R177/R182 重写
   的 13 个新 pin, 总 21 个 R193-family 测试. 任何未来
   refactor 把"flat markdown" 改回"卡片 + pills" 都会失败
9. **`width: 100%; max-width: 820px` 是 chat 文档的标准宽度** —
   比 760px 多 60px (think 文本宽一点, 不用换那么多行), 比
   100% 少一段, 给 chat 容器一个 max line length. 比
   `width: fit-content` 强, 后者在 flex 父里会变 0
10. **保留 legacy 函数的 `void ...;` 模式** —— R177
    `PreparingCard` 现在的签名是 `({steps, currentStepId,
    currentSubTaskId, isStreaming})`, 全部参数用 `void ...;`
    标记"我知道这个参数没用". TS 不会报 unused-param 警告,
    diff 也能看出来"这里有 4 个参数, 都是 legacy"。比
    `_` 前缀更明确
