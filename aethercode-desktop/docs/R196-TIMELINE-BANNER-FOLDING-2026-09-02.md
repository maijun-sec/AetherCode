# R196: Unified timeline + banner above input + folding visibility (2026-09-02)

## TL;DR

Round 4 of R193-family refinements. The user gave 3 pieces of
feedback; this round delivers the 2 highest-impact ones:

1. Banner position: above MessageInput (between chat list and
   input), not below MessageInput (at the very bottom of the page).
2. Folding visibility: bigger chevron, hover background, tooltip
   — the user couldn't tell the headings were clickable.
3. Order refactor: unified timeline so system messages and
   sub-task blocks are interleaved chronologically, not grouped
   by type with system messages at the end.

**Defered** (needs more design):
- CWD bug: when the user picks `D:\tmp\abc_2`, the engine
  still operates on `D:\tmp\abc_1` (the previous cwd of the
  current session). Need to decide: "new cwd = new session" or
  "in-place cwd update". The current code does the latter;
  the user is reporting the latter doesn't work for them.

## #3 Banner above MessageInput

R195 put `<PermissionPromptBanner />` BELOW `<MessageInput />`,
which made the banner the bottommost element. The user said
"确认应该放到中间展示的下面,不是整个页面的最下面" — they
want the banner between the chat list and the input, so the
input stays the bottommost element (the user's primary action
surface).

**Change** (App.tsx):
```diff
-        <MessageInput />
-        <PermissionPromptBanner />
+        <PermissionPromptBanner />
+        <MessageInput />
```

R196 source-pin: the test now asserts `PermissionPromptBanner`
index < `MessageInput` index in App.tsx.

## #2 Folding visibility

Pre-R196 the chevron was 10px and a low-contrast muted colour.
The user reported "之前提到的很多问题都没有" — they couldn't
see the `<details>` fold affordance. R196 made it obvious:

- Chevron: 10px → 14px, accent-coloured
- Summary row: hover background tint (`rgba(99,102,241,0.08)`)
- Title: hover turns accent
- Tooltip on summary: "点击折叠 / 展开"
- Subtle "点击折叠" hint on the right edge of the row, fades
  in on hover (60% opacity)

The fold is still native HTML5 `<details>` (no React state
management). The visual affordance is now:
- Big accent chevron
- Hover background + colour change
- Tooltip
- "点击折叠" hint on hover

## #2 Chronological timeline (the big one)

Pre-R196, MessageList rendered events grouped by type:
```tsx
{userMessages.map(...)}
{preambleSteps && <AgentMarkdownMessage ... />}
{subTasks.map((st) => <AgentMarkdownMessage ... />)}
{messages.filter(m => m.role === 'system').map(...)}
```

System messages always floated to the bottom. The user said
"tool 的执行和 tool 的输出仍然在最下面" — `[loop_warn_2]`,
`[todo-step-bump]`, `[todo-ask-llm]` etc. all appeared at the
end of the chat, not in time order with the agent's actions.

**R196** builds a unified timeline:
```ts
type TimelineEvent =
  | { kind: 'user'; ts: number; message: ChatMessage }
  | { kind: 'system'; ts: number; message: ChatMessage }
  | { kind: 'preamble'; ts: number; steps: ChatStep[]; isLive: boolean }
  | { kind: 'subtask'; ts: number; subTask: ChatSubTask; steps: ChatStep[]; isLive: boolean };

const timeline = useMemo(() => {
  const events: TimelineEvent[] = [];
  // ... collect from messages, subTasks, steps ...
  // Sort by ts ascending. Ties: user first, then sub-tasks,
  // then system messages.
  events.sort((a, b) => a.ts - b.ts);
  return events;
}, [messages, steps, subTasks, currentSubTaskId, isStreaming]);
```

Then `timeline.map(ev => ...)` renders in time order:
- User message bubble
- System pill (in time order with everything else)
- Sub-task block (preamble or named)
- Live streaming cursor on the in-flight event

The R176-D placeholder (`等待模型响应…`) is now gated on
`isStreaming && timeline.length === 0` instead of the old
`isStreaming && subTasks.length === 0 && preambleSteps.length === 0`.
The intent is the same: only show the placeholder while a
query is in flight and no events have arrived yet.

## CWD bug (defered)

Root cause analysis (didn't fix in this round, needs design):

When the user picks `D:\tmp\abc_2`:
1. `pickCwd()` opens dialog
2. `setCwd('D:\tmp\abc_2')` runs
3. `setCwd` calls `bindSessionCwd({ cwd: 'D:\tmp\abc_2', sessionId: currentSessionId })`
4. The daemon updates the engine's appState.cwd to `D:\tmp\abc_2`
5. Local store's `cwd` is updated
6. **But the session is NOT migrated**: the session ID still
   points to the old session on the daemon bound to `D:\tmp\abc_1`

The pre-warm step (`preWarmCwd`) spawns a sibling daemon for
the new cwd, but doesn't create a session in it. The user has
to manually start a new session.

Two options:
- (A) "New cwd = new session": when the user picks a different
  cwd, automatically create a new session on the new daemon
  and switch to it. Clean separation, but loses chat history.
- (B) "In-place cwd update": just update the engine's cwd
  (current behavior). The session is preserved, but if the
  model has context from the old cwd it may still operate
  there.

The user's report ("I picked abc_2 but it operates on abc_1")
suggests they expect (A). Need to confirm with the user.

**Likely user confusion**: even with (A), the model would
operate on abc_2 from now on, but the previous chat history
(from abc_1) would be lost. The user might actually want
(B) but with a fix for the model not picking up the new cwd.

## Test count

- Before R196: 903/903
- After R196: **904/904** (76 test files)

R196 source-pin tests:
1. `PermissionPromptBanner` is rendered BEFORE `MessageInput`
2. MessageList uses a `TimelineEvent` union + `timeline`
   variable + `a.ts - b.ts` sort
3. R176-D placeholder is gated on `isStreaming && timeline.length === 0`

## Build

```
$ npx tauri build
... Finished `release` profile [optimized] target(s)
... 2 bundles:
   - AetherCode_0.2.32_x64_en-US.msi
   - AetherCode_0.2.32_x64-setup.exe
```

(Both NSIS and MSI succeeded this time — no network timeout.)

- `AetherCode.exe` 3,944,448 bytes (v0.2.38 was 3,942,912; +1.5KB
  for the timeline + folding CSS)
- SHA256 [computed at release time]
- `aethercode-0.2.39.jar` 55,310,537 bytes (unchanged from v0.2.38)
- `AetherCode_0.2.39-setup.exe` 2,098,403 bytes (NSIS installer)
- `AetherCode_0.2.39.msi` 2,580,480 bytes (MSI installer)

## Lessons (2026-09-02)

1. **"中间展示的下面" ≠ "整个页面的最下面"** —— 用户要的
   是 banner 在 chat 列表下面 (中间展示区), 不是整个
   页面的最底部. 跟 R195 我把 banner 放在 MessageInput 下面
   不一样. 教训: **"在下面" 永远要问 "在哪两个元素之间"**,
   不能默认理解为"页面最底"
2. **"看不到" 跟 "做不到" 是两种 bug** —— 用户说"折叠没
   有" 不一定是 `<details>` 没工作, 可能是 chevron 太
   小看不出来. 加 hover 效果 + tooltip + hint 三层
   视觉提示, 一次解决"视觉不够明显"的问题
3. **顺序错乱 = 数据模型错乱** —— 之前我把 messages 跟
   sub-tasks 分开渲染, 时间线被打乱. 真正修法是引入
   unified timeline (events 数组, 每个有 timestamp), 按
   时间顺序 render. R196 做了这个, 但保留了 sub-task
   跟 system message 的分组 (timeline 内的 kind)
4. **CWD 是行为问题, 不是 UI 问题** —— 用户选 abc_2 但
   仍然操作 abc_1, 这是 bindSessionCwd 跟 currentSessionId
   的交互问题. 需要设计: "new cwd = new session" 还是
   "in-place update + 模型 context 切换". **不能瞎改,
   问用户**
5. **"input 永远在底部" 是 chat UX 的隐性契约** —— 任何
   不阻塞 input 的 UI 都不应该把 input 推到下面. banner
   在 input 上面, input 是 bottommost, 用户随时能输入
