# R194: Permission banner + markdown sub-headings (2026-09-02)

## TL;DR

Two issues from the v0.2.36 user feedback:

1. **Permission prompts were invisible.** The user said "工具调用会卡住
   阻塞, 是否是在等待用户确认? 但是在前端没有给我展示出来确认按钮、
   或者选择项". Root cause: `PermissionList` lived inside `RightPanel`,
   which is closed by default. A `permission_request` arrived, the model
   waited, the user never saw the prompt, the model retried in a loop.
   Fix: new `PermissionPromptBanner` always visible above the message
   list when `pendingPermissions > 0`.

2. **Markdown structure had no top-level heading for the think block.**
   The user said "think 没有上层标题,所以不大可能支持折叠. 我推荐
   的是在之前,添加一个要做的事情的总结,然后是执行的 思考、工具
   调用 等操作,最后给出一个总结". Fix: each sub-task markdown
   document now has `## content` (the sub-task name) → `### 步骤` (wraps
   the think + tool calls) → `### 结果` (wraps the summary blockquote).
   Markdown viewers can collapse `### 步骤` to hide just the execution.

## Issue 1: Permission banner

### Root cause

`PermissionList` was rendered only inside `RightPanel` (App.tsx:320):
```tsx
{p.rightPanelOpen && <RightPanel onClose={() => p.setRightPanelOpen(false)} />}
```

The user has to manually click "📊 Telemetry" in the Header to open
the right panel. Pre-R194, the flow was:

1. Backend `permission_request` notification
2. Store `rpc.on('permission_request', ...)` adds to `pendingPermissions`
3. `PermissionList` reads from `pendingPermissions` → renders inside
   the right panel
4. **If the user hasn't opened the right panel, the request is
   invisible.** The model waits for a response that never comes,
   then retries the same call. After 3 retries, the model writes
   "用户第三次让我继续执行, 一直到文件全部生成完成. 看起来用户
   可能在 TUI 中没有看到我的权限询问, 或者工具权限已经松开了."

The user has been telling the model to continue for **2 hours**
(11:40 + 15:03 + many messages in between) and the model has been
retrying because it never got the permission decision.

### Fix

New component `src/components/PermissionPromptBanner.tsx`:
- Always visible above `MessageList` (sits next to `AwaitingDecisionBanner`)
- Renders only when `pendingPermissions.length > 0`
- Shows the **oldest** pending request first (FIFO)
- Buttons: 允许 (one-shot allow) / 本会话始终 / 本项目始终 / 拒绝
- Risk level badge (low=green / medium=amber / high=orange / critical=red)
- Input preview (extracted from `input.file_path` / `input.command` /
  etc., 100-char cap)
- "+N" badge for additional pending requests
- Pulse animation (red glow) to grab attention

Mounted in `App.tsx`:
```tsx
<AwaitingDecisionBanner />
<PermissionPromptBanner />   // R194: NEW
```

### Why a separate banner, not just expand PermissionList

`RightPanel` is a 320px column with 7 sub-panels (TokenUsage /
ContextMeter / TraceList / PermissionList / MemoryPanel / PromptHistory /
KanbanPanel / AgentsPanel / SubagentPanel). The user is unlikely to
have it open, and even when open, the PermissionList is one tab among
many. A top-of-chat banner:

- Sits in the user's eyeline (above the message list, where they're
  reading the model's think text)
- Has a unique red border + pulse that says "URGENT"
- Has direct action buttons (no scroll, no tab switch)
- Auto-hides when there are no pending requests (no UI noise)

### Diff
- `+ src/components/PermissionPromptBanner.tsx` (new, 130 lines)
- `+ src/components/PermissionPromptBanner.css` (new, 130 lines)
- `~ src/App.tsx` (import + render)
- `+ 2 source-pin tests` in `preparingCardR177.test.ts`

## Issue 2: Markdown sub-headings

### Root cause

Pre-R194, each sub-task became one markdown document:
```markdown
## ${subTask.content}

[think text + tool events]

> **${subTask.status}**: ${subTask.summary}
```

When the user pastes this into a markdown viewer (VS Code preview,
GitHub, Obsidian), they can collapse `##` to hide the whole sub-task,
but they can't collapse just the think + tools to skim the summary.

### Fix

`stepsToMarkdown` now wraps sections with H3 sub-headings:
```markdown
## ${subTask.content}

### 步骤

[think text + tool events]

### 结果

> **${subTask.status}**: ${subTask.summary}
```

The user can now:
- Collapse `## content` to hide the whole sub-task
- Collapse `### 步骤` to hide just the execution, leaving the
  `## content` heading and the `### 结果 / > summary` blockquote
- Collapse `### 结果` to hide just the summary

### Why `步骤` and `结果`

The user said: "添加一个要做的事情的总结, 然后是执行的 思考、工具
调用 等操作, 最后给出一个总结". Mapping:

| User's words | Markdown section |
|---|---|
| 要做的事情的总结 | `## ${subTask.content}` (the sub-task name) |
| 执行的 思考、工具调用 | `### 步骤` (R194: NEW) |
| 总结 | `### 结果` (R194: NEW) → `> ...` blockquote |

`步骤` (steps) and `结果` (result) are 2 characters each, fit
naturally next to the existing sub-task heading, and read in any
markdown viewer.

### Preamble run (no sub-task)

The preamble run has no `## content` heading (the model is still
thinking before declaring a sub-task). The `### 步骤` / `### 结果`
wrappers only appear when there's a sub-task — for preamble, the
output is the raw stream as before.

## Test count

- Before R194: 898/898
- After R194: **900/900** (76 test files, +2 R194 source-pin tests)

R194 source-pin tests:
1. App.tsx imports `PermissionPromptBanner`
2. App.tsx renders `<PermissionPromptBanner />` in JSX

Plus the existing R193-followup-3 test was updated to require
`### 步骤` and `### 结果` in the source:

```ts
expect(mlSrc).toMatch(/### 步骤/);
expect(mlSrc).toMatch(/### 结果/);
```

## Build

```
$ npm run typecheck
$ npm run test
Tests  900 passed (900)
$ npx tauri build
... Finished `release` profile [optimized] target(s) in 2m 24s
   Built application at: ...\aethercode-desktop.exe
... NSIS bundle timeout (network, same as previous versions)
```

- `AetherCode.exe` 3,940,352 bytes (v0.2.36 was 3,938,304; +2KB for
  the new banner)
- SHA256 `1420C4DEBD78774EEA70511D6B21B2B4C8815E5DD9020AF1A088138226895537`
- `aethercode-0.2.37.jar` 55,310,537 bytes (same as v0.2.36; no Java
  changes for R194)
- SHA256 `C61F177187AC985AB86FD92FC9CB450A0779BC8DBEB4011682BE9AC3C7E4F5E5`

## Lessons (2026-09-02)

1. **"看不到确认按钮" 是 critical bug, 不是 cosmetic** —— 用户的
   反馈里"卡住阻塞"听起来像"模型慢", 实际是"权限请求根本没显示".
   区分这两个: 看截图里模型自己写"用户第三次让我继续执行"就
   知道模型在等用户, 不是慢. **永远问: 模型在等什么?** 答: 权限.
   那权限 UI 在哪儿? 答: 右面板里. 答: 用户没开右面板. bug
2. **permission UI 不应该藏在 toggle panel 里** —— 任何阻塞流程
   (权限 / 决策 / 错误) 都应该 surface 在用户的视线路径上, 不能
   藏在二级面板. AwaitingDecisionBanner 已经在 message list 上方
   常驻了, PermissionPromptBanner 同样常驻
3. **"折叠"是 markdown 的核心价值** —— 用户提"折叠"的时候不
   是在说 React 组件的折叠, 是在说 markdown 文档 viewer 里的
   折叠 (H1/H2/H3 都有折叠手柄). 加 `### 步骤` / `### 结果` 是
   给 markdown viewer 留折叠锚点, 跟我们 React 怎么渲染无关
4. **"在之前" / "然后" / "最后" → H1/H2/H3** —— 用户描述结构用
   "前中后", 翻译成 markdown 就是 H1/H2/H3 三级标题. 简单的
   模式: 一个 sub-task = H2 (name) + H3 (steps) + H3 (result),
   一目了然
5. **R193-followup-3 的"flat markdown" 是 markdown 文档,
   R194 的"折叠锚点"也是 markdown 文档的. 两件事一起做** ——
   R193 干掉卡片, R194 给 markdown 文档加结构. 不要把"flat"
   理解成"全平", flat 是"无 card chrome", 不是说"无结构"
6. **source-pin 测试 = 行为契约** —— 2 个 R194 新 pin: import
   + render. 任何未来 refactor 把 PermissionPromptBanner 移走
   都会失败. 这是行为级别的"必须有这个组件在 App 里"
