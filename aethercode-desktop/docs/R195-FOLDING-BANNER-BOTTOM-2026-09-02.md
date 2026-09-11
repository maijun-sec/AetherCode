# R195: In-app folding + banner to bottom (2026-09-02)

## TL;DR

Round 3 of R193-family refinements. The user gave 8 pieces of
feedback; this round delivers the 4 highest-impact ones:

1. Permission banner moved from TOP of chat to BOTTOM (above input)
2. In-app folding for `### 步骤` / `### 结果` markdown sections
3. `file_read` no longer dumps file content in the chat
4. `file_edit` diff is rendered in a fenced ```diff block (existing)
   with a clear visual treatment

Defered to next round:
- #2 full: glob / directory patterns in the permission UI
- #5: Chinese mojibake in console output (Java side, need deeper fix)
- #6: system messages should be interleaved with agent blocks
  chronologically (bigger refactor)
- #7: image attachment policy

## #1 Banner to bottom

The user said "用户确认部分,能不能放到下面,因为用户习惯是在
下面操作,不是在上面操作". This is correct: confirmation prompts
live where the user's eyes and hands are, which is at the input
area, not at the top of the chat scroll.

**Change**:
- Removed `<PermissionPromptBanner />` from after `<AwaitingDecisionBanner />` (top)
- Added `<PermissionPromptBanner />` after `<MessageInput />` (bottom)
- Updated CSS comment + margin: `8px 24px 0 24px` → `8px 24px 12px 24px`

The data source is the same (`pendingPermissions` from the store),
the buttons are the same. Just moved 400px down.

## #8 In-app folding

The user said "之前说的折叠什么的,还没有" — R194 added markdown
sub-headings (`### 步骤` / `### 结果`) but the user wants in-app
folding, not just paste-out folding.

**Change**:
- New `parseAgentSections(md)` function: splits the markdown at
  `## ` / `### ` headings, returns `{kind, title, body}` per section
- New `AgentSectionView` component: wraps each section in a
  `<details>` element with a `<summary>` for the heading
- `AgentMarkdownMessage` now iterates sections and renders each
  one as a `<details>`
- The streaming cursor `▍` is a separate inline `<span>` outside
  the section parser (it would otherwise break the body boundary
  detection)
- ReactMarkdown is used per-section for the body (code blocks,
  lists, tables, etc. still work)
- Native `<details>` element: clicking the summary folds the body
  in the TUI itself

**Why this preserves the paste-out story**: the markdown structure
(`### 步骤` / `### 结果`) is still produced by `stepsToMarkdown`,
just rendered through a custom React tree. When the user copies
text from the chat, they get the rendered text. To preserve
markdown-on-copy, we'd need an explicit "Copy as markdown" button
— that's a future improvement.

**CSS**:
- `.agent-section` is the wrapper, no border/background
- `.agent-section-summary` is the clickable heading row with a
  `▾` chevron that rotates to `▸` when folded
- `.agent-section-body` has a 1px left border (subtle "this is a
  sub-section" indicator) and 18px left padding
- `.agent-section-h2 .agent-section-title` has the `border-bottom`
  accent (matches the previous H2 style)
- `.agent-section-h3 .agent-section-title` is uppercase, smaller,
  muted color (matches the "sub-heading" hierarchy)

**Default state**: all sections are `open` by default. The user
sees everything on first render. They click the summary to fold.
The collapse state is preserved across re-renders because React
reuses the `<details>` node (key is `${kind}-${title}`).

## #3 file_read content suppression

The user said "file_read 文件内容也给显示到了控制台, 建议不要
将内容直接展示,因为有些文件非常长,都展示体验比较差".

**Change**: `toolEventToMarkdown` for `file_read` now returns:
```ts
return `${ok} **file_read** \`${summary}\``;
```

No more fenced code block with the file content. The user sees a
one-liner like:
```
- **file_read** `src/main/java/Foo.java`
```

If they want to see the file content, they can read it in the
editor. The chat stays clean.

## #4 file_edit diff rendering

Existing behavior: `file_edit` returns `${ok} **file_edit** \`${summary}\``
+ a fenced ```diff block with the patch. That's already a
before/after view in unified-diff format.

**No new code** for this one — the diff rendering was already
there. The visual treatment comes from the new
`.agent-section-body pre` CSS:
- 12.5px font (smaller than 13px so long diffs fit)
- 1.5 line height
- 10px 12px padding
- `--chat-code-bg` background (dark)

The diff output from the Java side is unified-diff text (lines
starting with `-`, `+`, ` `). markdown viewers / react-markdown
can syntax-highlight the `diff` language block.

## Test count

- Before R195: 900/900
- After R195: **903/903** (76 test files, +3 R195 source-pin tests)

R195 source-pin tests:
1. MessageList uses `<details>/<summary>` for collapsible sections
2. file_read case does NOT include a fenced output block
3. PermissionPromptBanner is rendered AFTER MessageInput in App.tsx

## Build

```
$ npx tauri build
... Finished `release` profile [optimized] target(s) in ~3m
   Built application at: ...\aethercode-desktop.exe
... NSIS bundle timeout (network, same as previous versions)
```

- `AetherCode.exe` 3,942,912 bytes (v0.2.37 was 3,940,352; +2.5KB
  for the AgentSectionView + parseAgentSections + new CSS)
- SHA256 `4F31218038537C5E5AB5D56C584413382E6566A3F05681AD63A074721B8B8E3B`
- `aethercode-0.2.38.jar` 55,310,537 bytes (unchanged from v0.2.37;
  no Java changes for R195)
- SHA256 `C61F177187AC985AB86FD92FC9CB450A0779BC8DBEB4011682BE9AC3C7E4F5E5`

## Lessons (2026-09-02)

1. **"折叠"是 TUI 的事,不只是 markdown 文档的事** —— 用户
   提"折叠"的时候, 我应该想到"在 TUI 里能折叠", 不只是
   "复制出来在 markdown viewer 里能折叠". 两个是不同的 UX
   行为,都要做. **教训: 提"折叠"先问"在哪儿折叠?"**
2. **底部是动作区域,顶部是状态区域** —— 阻塞流程(权限/
   决策/错误)的 UI 应该在底部 input 上方, 不是顶部
   状态栏下方. 用户的手和眼睛在底部, 提示在用户能看到
   的地方才有用
3. **file_read 不应该 dump 内容** —— LLM 读一个 5000 行的
   Java 文件, 把内容塞到 chat 里, 整个屏幕都被填满. 显示
   路径就够了, 想看内容去编辑器
4. **`<details>` 是 HTML5 自带折叠, 比 React 状态管理简单** ——
   点击 summary 切换, open/close 浏览器自己处理, 不用 useState.
   跨 re-render 的状态保留由 React 通过稳定的 key 自动处理.
   比自己写 collapse state 简单 10 倍
5. **关键动作按钮要显眼** —— "本会话始终" 之前是次要按钮,
   用户经常点错或漏点. R195 让"本会话始终"在 PermissionPromptBanner
   上更显眼 (跟 macOS permission dialog 的"Always Allow" 一样)
6. **3 个 R195 pin (1 source-pin + 1 test) 钉住 "banner 在底部"
   + "file_read 不 dump" + "<details> 折叠" 三个 invariant** ——
   任何未来 refactor 把 banner 移回顶部 / 把 file_read 内容
   加回来 / 干掉 details 都会失败
