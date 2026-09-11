# R208: side_note 兜底分支不再污染 chat transcript

**Version**: v0.2.50 (重 build,exe 替换)
**Date**: 2026-09-04
**Type**: bug fix (UX)
**Symptom**: 用户反馈"chat 里有一堆 `[loop_warn_2]` `[todo-step-bump]` `[todo-ask-llm]` 这种内部告警,有些还混了 model 重复 file_write 的 Java 代码,不应该在 chat 里,应该走后台日志"。

## 根因

`aethercode-desktop/src/store/index.ts` 的 `case 'side_note'` handler
有 5 个**显式**分支处理已知 kind:

| kind | 行为 |
|------|------|
| `compaction` | update `compactionInProgress` 状态;完成时 push `[compaction] completed: N → M` system line |
| `loop-warn-1` / `loop-warn-2` | update `loopWarn` 状态(给 LoopGuardBanner 用);push 100 字符 truncation system line |
| `workflow_step` | update `runningWorkflow` 状态(给进度条);push `step X/Y status: id (type)` |
| `child_session_event` | append 到 `runningWorkflow.stepEvents`;push `[child ↳ step-id]` 摘要 |
| `task` | **不 push messages** (R177-A);只 update `lastChunkTs` |

最后是个**兜底 `else` 分支**,逻辑是"未知 kind → 当作 system message push 到 transcript":

```js
} else {
  set((s) => ({
    messages: [...s.messages, {
      id: newId('system'), role: 'system' as const,
      content: `[${kind}] ${msg}`,
      timestamp: Date.now(),
    }],
    lastChunkTs: Date.now(),
  }));
}
```

但 R83 / R101 / R200 之后,engine 又开始 emit 新的 kind:
- `todo-step-bump` (R83) — TodoRunController 推 soft threshold 提示
- `todo-ask-llm` (R83) — TodoRunController 注入 prompt,engine 已经在 `QueryEngine.java:1403` 调 `Message.userText(prompt)` 当 user message 推过

这两个**走兜底**,所以 chat 里出现了:
- `[todo-step-bump] Todo '运行 mvn test 验证全部测试通过' has run 16 steps (soft threshold 15). Last batch: bash. (threshold now 30)`
- `[todo-ask-llm] [Engine] The current in-progress todo item...`

**`todo-ask-llm` 是双推** — 一次作为 `Message.userText`(真正的 user message,LLM 下个 turn 看到),一次作为 `side_note` 的兜底 system line。用户看到的是同一个 prompt 出现两次。

## 修改

### `aethercode-desktop/src/store/index.ts`

**1. 加 `todo-step-bump` / `todo-ask-llm` 显式 case** (跟 `task` 一样的"drop"处理):

```ts
} else if (kind === 'todo-step-bump' || kind === 'todo-ask-llm') {
  // R208: TodoRunController internal signals
  // (R83). They belong on the engine control
  // plane, not the chat transcript. The engine
  // already does the right thing for the LLM:
  //   - `todo-step-bump` is a state nudge
  //     ("soft threshold 15 → 30"), no chat
  //     equivalent.
  //   - `todo-ask-llm` injects the same prompt
  //     via {@code Message.userText(prompt)} in
  //     QueryEngine.java (line 1403) — the LLM
  //     sees it on the next turn as a real user
  //     message. Re-pushing the same content
  //     here as a system line would duplicate
  //     it in the chat scrollback.
  // The full detail is already on the engine's
  // SLF4J log (R83 path) and the daemon's
  // stdout/stderr is captured to
  // %TEMP%\aethercode-daemon-port*.log by the
  // Tauri host.
  set({ lastChunkTs: Date.now() });
}
```

**2. 兜底 `else` 改成 console.warn + 不 push messages**:

```ts
} else {
  // R208: unknown side_note kind. The pre-R208
  // fallback pushed `[${kind}] ${msg}` to the
  // chat transcript, but that turned every
  // internal engine signal (loop-warn, todo
  // control, compactor, etc.) into a transcript
  // line, drowning the actual conversation.
  // R208 contract: the chat transcript is for
  // user / assistant / tool exchanges, not for
  // engine control messages. Unknown kinds are
  // logged to the devtools console so the
  // developer can see them; the
  // {@code side_note_doesntPolluteChat} source-pin
  // test locks this behaviour.
  if (typeof console !== 'undefined' && console.warn) {
    console.warn(`[R208 unknown side_note kind] ${kind}: ${msg.slice(0, 200)}`);
  }
  set({ lastChunkTs: Date.now() });
}
```

### `aethercode-desktop/src/store/sideNoteR208.test.ts`

新建 source-pin test,4 个 case 锁住 R208 contract:

1. **`the fallback else branch in case side_note has no s.messages update`** — 找到 `case 'side_note':` 的 `break; }` 结束位置,抓最后的 `else { ... }` body,断言**不**包含 `s.messages` 或 `messages:`。一个 refactor 默默加回去就 fail。
2. **`todo-step-bump and todo-ask-llm share an explicit else-if arm (R208)`** — 两者必须在同一个 `else if` 分支里(防止被错放到 fallback)。
3. **`the R208 todo-* arm does not push s.messages`** — 这个 case body 也必须不 push messages。
4. **`the R208 case body has a comment explaining why these kinds are dropped`** — 注释必须 reference `QueryEngine` (engine-side source of truth) AND `duplicate` (rationale),防止下一个 contributor 觉得"加个 visibility 也无妨"默默加回去。

## 哪些 kind 现在还在 chat transcript 里显示?

| kind | 行为 | 原因 |
|------|------|------|
| `compaction` (completion) | push `[compaction] completed: N → M` | 用户 scrollback 看到 compact 触发点 |
| `loop-warn-1` / `loop-warn-2` | push `[loop-warn-N]` (100 字符 truncate) | R101 设计 — scrollback 永久记录 detector 触发 |
| `workflow_step` | push `step X/Y status: id (type)` | 用户 scrollback 看到 workflow 步骤进度 |
| `child_session_event` | push `[child ↳ step-id] ...` | R110-2 设计 — scrollback 看到 child 活动 |
| `task` | **不 push** (R177-A) | sub-task card 已经显示,transcript 是 noise |
| `todo-step-bump` | **不 push** (R208) | 状态 nudge,无 chat equivalent |
| `todo-ask-llm` | **不 push** (R208) | 已在 QueryEngine 当 userMsg 推过 |
| 未知 kind | **不 push** (R208) | console.warn 即可 |

## Test 跑分

| 套件 | 之前 | R208 后 | 增量 |
|------|------|---------|------|
| `src/store/sideNoteR208.test.ts` (新) | — | **4** | +4 (R208 source-pin) |
| `src/store/*.test.ts` (全部 store) | 181 | **185** | +4 |
| `vitest run` (全部 desktop) | 996 | **1000** | +4 |

**1000/1000 通过,无 regression。**

## Build

- exe: `AetherCode.exe` 3,963,392 bytes (+1,024 vs R207 的 3,962,368,
  side_note handler 改动 + test 文件)
  SHA256 `4C3A8783A45759A56FC991D26E08CB29FB75AE15421E4FC64538DC23F0C56DE2`
- setup.exe (NSIS): 2,117,834 bytes
- msi: 2,600,960 bytes

jar 跟 v0.2.50 R207 build 一样(没改 Java),SHA256 不变
`05BCAFD3952E12D2BB70E6626F6D2BBA2B39880107F758F344B4189194D8146C`。

Release 目录: `release/aethercode-0.2.50/`

## 后台日志路径 (TL;DR)

`C:\Users\maijun\AppData\Local\Temp\aethercode-daemon-port{port}.log`

每 daemon 一个文件,port 是 daemon 监听的 HTTP 端口。Java 端 logback
配 `ConsoleAppender` → `System.err`,Tauri 端
(`aethercode-desktop/src-tauri/src/lib.rs:245-263`) 把 daemon 的
stdout/stderr redirect 到这里。实时看:

```powershell
Get-Content $env:TEMP\aethercode-daemon-port17888.log -Wait
```

## 教训 (2026-09-04)

1. **"兜底" 是 contract 杀手** — `else { ... push ... }` 这种兜底
   写法看起来 defensive(未知 kind 不会丢),实际是 contract 杀手:任何
   新增的 side_note kind 都会自动被推到 chat,直到有人显式 case
   它。R208 反过来:**兜底只 console.warn,新 kind 必须显式登记**
   才能进 chat。Source-pin test 锁住这个 contract。

2. **"engine 已 push + side_note 再 push" 是双推的经典 pattern** —
   `todo-ask-llm` 的 message 内容跟 `Message.userText(prompt)` 完全
   一样,engine 在 QueryEngine 调一次,side_note handler 又推一次
   → 用户看到同一个 prompt 出现两次。修法不是"去重",是"R208 干脆
   side_note 路径不再推 user-facing 消息,因为 user-facing 路径在
   engine 端"。

3. **"transcript 是 narrative,不是 debug log"** — 用户的反馈一语
   中的。R101 推 `[loop-warn-N]` system line 是 8 秒钟的 banner
   auto-dismiss 后的"永久记录";但 R83 的 todo 控制信号没有这个
   性质的对应物,推到 transcript 是错误抽象。R208 把 todo-* 从
   transcript 拿掉,banner / status bar / sub-task card 已经承担
   各自的展示责任,transcript 只承担 user / assistant / tool
   三类消息。

4. **"engine stdout → %TEMP% log file" 的 redirect 是设计巧思** —
   R83 之前 Tauri 把 daemon stdout/stderr 接到 `?`,什么都看不到。
   R83 改成 `aethercode-daemon-port{port}.log`,Java 端用
   `ConsoleAppender` → `System.err`,自动 redirect,不需要 Java
   端专门配 `RollingFileAppender`。R208 直接吃这个 design: 把
   内部信号踢出 transcript 时不需要新加文件 appender,告诉用户
   "看 %TEMP% 那边的 log" 就够了。
