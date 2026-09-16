# R273 — desktop polish trio (2026-09-16)

## 触发

用户当时正在跑 `D:\tmp\abc_1` session（abc_1 / 312 个测试 heap-sort 通过），
dashboard 点报告了 3 个问题截图：

1. **顶部 + 底部都有 "✓ Composing…" 重复 pill** — 顶部是 `ActivityIndicator`（R82+，
   渲染 `currentActivity.label`），底部是 R269 的 `StreamingIndicator` footer，两者订阅同一
   store 字段所以同一帧渲染两次。
2. **假的 "YOU" 消息** — 右面板 Tasks 进度条 5/5 done，但 chat timeline 上出现一条
   `YOU 09:00:29` 蓝色气泡，内容是 `[Engine] The current in-progress todo item …
   has now taken 16 tool-call / turn steps (previous soft threshold was 15, bumped to 30)…
   Decide what to do …` — 这条用户从来没发过，transcript 里 `role: user` 但其实是 daemon
   loop-guard 自动 inject 的 pseudo-user。
3. **think 跟 tool 没按顺序交替** — 同一时间 streaming 中，看到中间主区域是
   `思考 · <think>...the user wants me to generate a java maven project...
   - bash cd /d D:\tmp\abc_1 && dir
   - bash mvn -version && java -version
   - file_write D:\tmp\abc_1\pom.xml
   ...`
   一个大的 think block 在最上面，所有 tool call 堆在底下，**不是 model-emit 的顺序**。

## 根因 (3 个独立 bug)

### Bug 1: 流状态指示器重复

`App.tsx` 主区域里有 `<ActivityIndicator />`（R82+ 加的，跟 `currentActivity` 绑）
+ MessageList 内部有 `<StreamingIndicator variant="footer" />`（R269 加的，跟 `isStreaming +
currentActivity` 绑）。两者都依赖 `currentActivity.label`，所以同一帧双渲染 —
"AetherCode 正在跑" 截图中 [done kind 绿色 checkmark] 在两个地方都看到。

`ActivityIndicator` 还包含一个 compaction 状态 (`⤓ 正在压缩对话历史…`) 是
StreamingIndicator 没有的，所以单独把它移除会丢这个 signal。

### Bug 2: Engine-injected loop-guard 消息被错误标 user

daemon `QueryEngine` 在 tool-call/turn 累计超过软阈值 (R183 默认 15) 时，
会 inject 一条 `role: user` 的消息问 LLM "decide what to do — pick ONE"，让 LLM
自己选择继续还是停止。这条消息 transcript shape 是：

```json
{"role":"user","content":[{"type":"text","text":"[Engine] The current in-progress todo item\n\n  > Run mvn test…\n\nhas now taken 16 tool-call / turn steps\n(previous soft threshold was 15, bumped to 30).\n\nDecide what to do — pick ONE and act on it this turn:\n(A) Mark this todo complete (or move on) if it\'s actually done…"}]}
```

content 第一行固定是 `[Engine] …` 前缀。LLM API 只接受 user/assistant role，所以
daemon 不能用 `role: system` 替代。但 desktop 渲染时无脑把任何 `role: user` 当用户
消息 — User 反馈里看到的就是它。

### Bug 3: think + tools 在 step 内部全部堆一起

R267 desktop polish 已经试过一次修：在 store 加 `pendingStepBoundary` flag，
tool_result 时置 true，下一次 text_delta 时 close 当前 step / open 新 step。
Worked for simple workflows but **只对 tool→text 边界起作用** — 一个长 think
块跟一群 parallel tool calls 时，整个 think 块累积到 `step.text`，所有 tools
累积到 `step.toolEvents`，buildBlocks 渲染成 `[think][tool][tool]…` 的"一个
think + 全部 tools" pattern。这是用户看到的"composing 30s 中间那条 think + 下面
所有 tool"。

## Fix

### Fix 1: 移除顶部 ActivityIndicator，StreamingIndicator 接管 compaction

`aethercode-desktop/src/App.tsx` 删掉 `<ActivityIndicator />` JSX + 删 import；
StreamingIndicator 加 `compacting = useStore((s) => s.compactionInProgress)`
subscribe，compaction 出现时渲染 `<…>⤓ 正在压缩对话历史…</…>` pill。

视觉变化：底部 input 区上方只看到一个 streaming pill（空时居中大 hero，
有 event 时 footer 紧凑对齐），不再重复。

### Fix 2: LegacyMessage 检测 `[Engine]` 前缀

`aethercode-desktop/src/components/MessageList.tsx` 给 LegacyMessage 增加分支：

```typescript
function isEnginePromptUserMessage(content: string): boolean {
  if (!content) return false;
  const trimmed = content.trimStart();
  return trimmed.startsWith('[Engine]');
}

function LegacyMessage({ m }: { m: ChatMessage }) {
  if (m.role === 'user') {
    if (isEnginePromptUserMessage(m.content)) {
      // muted centered pill with [引擎] label + details expand
      return ( <details className="message message-engine-hint">…</details> );
    }
    return <existing YOU bubble>;
  }
  // …
}
```

CSS 加 `.message-engine-hint` 视觉（lavender 紫调 `[引擎]` 标签 + 虚线边
+ 默认折叠，`<details>` 展开后显示完整内容）。User 现在 hover [引擎] pill 即知道
"这是 system nudge"，而不是误以为是"我什么时候发的"。

### Fix 3: text_delta 永远开新 step

`aethercode-desktop/src/store/index.ts` 删掉 `pendingStepBoundary` 模块 flag
+ 删掉 `run_start` / `tool_result` 的两个 setter + 改 `text_delta` handler：

```typescript
case 'text_delta': {
  const text = ev.text ?? '';
  if (!text) return;
  set((s) => {
    // ...
    let currentStepId = s.currentStepId;
    let steps = s.steps;
    if (currentStepId) {
      // R273: ALWAYS close previous + open new step for this chunk.
      // Tools arriving between this text and the next text_delta
      // land in this new step's toolEvents. buildBlocks then emits
      // [think,tool,think,tool,…] in model-emit order regardless of
      // how many parallel tool calls the model makes per think.
      const prev = steps.find((st) => st.id === currentStepId);
      const parentSubTaskId = prev?.subTaskId ?? null;
      const newStepId = newId('step');
      steps = [
        ...steps.map((st) => st.id === currentStepId
          ? { ...st, done: true, endedAt: Date.now() }
          : st),
        { id: newStepId, subTaskId: parentSubTaskId, startedAt: Date.now(),
          text, toolEvents: [],
          counters: { thinks: 1, fileReads: 0, fileWrites: 0, commands: 0,
                      searches: 0, web: 0, other: 0 },
          done: false },
      ];
      currentStepId = newStepId;
    }
    return { …, steps, currentStepId };
  });
  break;
}
```

新 step-boundary 模型严格 subsumes R267：每次 text_delta 都强制开新 step，所以
下面 work 的 case 都 work，而 R267 fix 覆盖不到的"长 think + 多个 parallel tools"
也修了。

## 测试

3 个新 test files / 3 个 update：

- `aethercode-desktop/src/store/stepBoundaryR273.test.ts` (4 tests) — 取代 R267 test (4 tests)：
  - text_delta case 必须 `done: true, endedAt: Date.now()` 关老 step
  - text_delta case 必须 open 新 step with `text, toolEvents: [], counters:`
  - 不允许 `let pendingStepBoundary: boolean = false;` 重新引入
  - 不允许 `pendingStepBoundary = true` 在 tool_result 重新引入
  - 不允许 `pendingStepBoundary = false` 在 run_start 重新引入
- `aethercode-desktop/src/components/streamingIndicatorR269.test.tsx` (12 tests, +3)
  - R273 footer 渲染 compaction pill when `compactionInProgress=true`
  - R273 CSS `.streaming-kind-compaction` 存在
  - R273 App.tsx 没有 `import …/ActivityIndicator` 也没有 `<ActivityIndicator />` JSX
- `aethercode-desktop/src/components/engineHintR273.test.tsx` (9 tests, all new)
  - source-pin: MessageList 定义 `isEnginePromptUserMessage` + 检测 `[Engine]`
  - source-pin: CSS `.message-engine-hint` 存在, lavender `#b48ead` accent
  - source-pin: LegacyMessage engine 分支用 `[引擎]`, 不 emit `.message-user`
  - behaviour: detects full loop-guard bump string (the user's screenshot text)
  - behaviour: normal user prompts, "ping", whitespace-only 都 negative
  - behaviour: tolerates leading whitespace
  - behaviour: case-sensitive (engine lowercase 不匹配)

`vitest`: 1090 → **1102** (+12 pass, 0 regression)。`mvn protocol`: 274 → 274 (jar unchanged)。
`npm run build` clean, `npx tsc --noEmit` clean, `tauri build` clean.

## 部署

- `aethercode-desktop/src-tauri/target/release/aethercode-desktop.exe` — rebuilt
  - JS bundle `DpsoQjYi` (683 KB → 206 KB gzip)
  - exe SHA `CBE06AD78FDDA4DBF28DE3F274BE7D19EC0044DCCE89FBAB2B4639D267ED8389`
    (5,175,808 bytes)
- `release/aethercode-0.2.70.zip` — repacked
  - SHA `C1989E94B0526E8106E095906DDB2419850750990837A34135ADA59D79E07D1B`
    (108,496,317 bytes / 103.47 MB)
  - desktop exe CBE06AD78…, jar 3A9F786A… (=R271/R272 unchanged, jar bytecode 不需要改)
  - jar bytecode markers present: R268e WriteExistingFileGuardHook, R270 AetherCodeMethods,
    R271 BashTool (×3)
- desktop.exe 跑通，daemon PID 21448 服务 18889，session a207b5b5 还在 R272 同一条 abc_1

## Commit & push

- `81749b0 ﻿R273: dedup streaming indicator + engine pseudo-user pill + step interleaving`
  - 10 files changed, +6665 −6197 (一大半是 package-lock.json + 删除 R267 source-pin test)
  - pushed to main ✓

## 关键技术决定

1. **StreamingIndicator 是 single source of truth** for activity pills (thinking / tool / done / error / compaction). ActivityIndicator removed entirely. 任何"在 chat 里看不到 status"的 prompt
   都只动 StreamingIndicator + MessageList，不用同步两份。
2. **`[Engine]` 前缀 detection at render time**, not metadata-level tagging. 原因：
   - Backward-compat — 已经存在的 transcript 已经有这些 messages, 重新标记 metadata
     需要 replay 整个 transcript。
   - Daemon 用 `role: system` 也不行 — LLM API 只接受 user/assistant。
   - 前缀是 daemon 写死的字面量 (`[Engine] ` 加上 newline), 不可能误报正常用户消息。
3. **`text_delta` 永远开新 step 取代 `pendingStepBoundary`**. 新 model 严格 subsumes 旧:
   - 旧 model 只有 tool_result→text_delta 边界 split, 不能处理"长 think 后 parallel tools"。
   - 新 model 每个 text chunk 独立 step, tools 累积到这个新 step 直到下一个 text,
     完美对应 buildBlocks 期望的 [think,tool,think,tool,…] interleaved order。
   - 失去的 `pendingStepBoundary` module flag 删除 dead code, 3 个 R267 source-pin
     assertions (declaration + tool_result setter + run_start reset) 全部 replace。

## 关键调试技巧

550. **重复 indicator 比 single-source-of-truth 难维护 10 倍** — 同一 store 字段被两处
     渲染就是 bug waiting to happen。挑一个砍掉。
551. **`role: user` 不是 user-typed 的保证** — 任何 system-injected 的 prompt
     (loop-guard、telemetry 触发器、A/B test prompt) 都会想 `role: user`，
     必须 prefix-or-metadata 标记才能区分。
552. **存储 schema 跟渲染预期 mismatch 时, 改存储比改渲染对** — R267 已经试过
     "storage flat, render interleaved" (改 buildBlocks)，效果不佳因为 step.text
     已经把"model emit order"压扁了；R273 改 storage 一劳永逸。
553. **flag-in-module 比 component state 重构难维护** — `let pendingStepBoundary`
     是 module-scope state，跨测试/事件handler 共用，依赖隐式。R273 用 store-local
     currentStepId 隐式约束，让 step 边界自然形成。

## 教训

551. **bulk 包删除 + 重写在混淆 UTF-8 special-char 时容易失败** — 多个 em-dash / arrow
     / 标签在同一段注释里，Python script 的 heredoc 容易 silent-normalize 某些字符。
     改用一组小 targeted edit (每个 `// old → // new`) 更可控。
552. **desktop-only round 不需要 rebuild jar** — 部署链轻，jar SHAs 保持不变，
     字节码 marker 验 R271 还在就够了。
553. **`isEnginePromptUserMessage` 用 prefix detect 而不是 content-type tag** — daemon
     transcript shape 有 `metadata.kind` 这种字段，但 desktop 重放旧 session 时不会自动
     populate，prefix 永远 signal-on, 一致。
554. **App.tsx 删除 import 时容易 emit "Cannot find name 'X'"** — 我第一遍把 `ActivityIndicator`
     import 替换成 comment block 时也吞了 `AwaitingDecisionBanner` import。grep imports
     不充分，应该 `Remove-Item` + `git diff` verify。
