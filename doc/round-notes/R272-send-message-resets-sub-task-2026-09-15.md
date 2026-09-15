# R272 — sendMessage resets sub-task tracking

Date: 2026-09-15
Round: R272
Type: fix (desktop-side)
Status: ✅ Deployed (commit `e4be6c5` push 成功)

## Trigger (用户反馈)

用户在 R271 修完之后又提了个 prompt澄清：
> "第二个问题，不一定是一个根因，因为后来又提了个 prompt，这时候是正常执行 tool 和输出信息的，但是都输出到上面去了"

也就是说：daemon 不再卡 busy 后，**新 prompt 正常被处理**了，但所有 tool call 和 model output 都**显示在 first prompt 上面**，新 prompt 下面空。

## 根因分析

### 状态泄漏

`sendMessage` (desktop store/index.ts:3818) 提交新 prompt 时：

```typescript
// 之前
set((s) => ({
  messages: [...s.messages, { id: newId('user'), role: 'user', content: input, timestamp: Date.now() }],
  currentInput: '', isStreaming: true,
  awaitingUserDecision: null,
  currentQuery: { id: qid, prompt: summary, startedAt: Date.now(), stepCount: 0, toolCount: 0 },
}));
```

**没有清 `subTasks`、`currentSubTaskId`、`currentStepId`**。

### Daemon 端的 emitSubTaskTransitions 行为

`QueryEngine.query()` (line 956) 在每个新 query 启动时调用 `emitSubTaskTransitions(action)`:

```java
emitSubTaskTransitions(action);
```

`emitSubTaskTransitions` 对 `appState.todoList()` 里所有 sub-task 跟 `lastSubTaskStatus` 对比。如果 `lastSubTaskStatus` 是新 query 清空的，`prev == null`，**对每个 old sub-task 都 emit SubTaskStart**。

也就是说：**新 query 启动时，daemon 会重新 emit 所有 old sub-task 的 SubTaskStart 事件**(因为 model 之前没显式关闭它们)。

### 事件顺序

```
1. user sends prompt B
2. sendMessage adds user B to messages (T2)
3. daemon starts new query:
   a. emits RunStart (T2)
   b. emits SubTaskStart(old_sub_task_1, status=in_progress)
4. desktop store receives events in order:
   a. run_start handler: 
      - s.currentQuery is set
      - s.currentStepId is null (cleared by previous run_end)
      - creates new step with subTaskId = s.currentSubTaskId
      - **如果 s.currentSubTaskId 还指向 old sub-task 1**, 新 step 的 subTaskId = "0:0"
   b. sub_task_start handler:
      - sees existing sub-task 1 entry → updates status to in_progress
      - sets s.currentSubTaskId = "0:0" (same old sub-task id)
```

### 结果

新 query 的所有 content (thinking + tool_use + tool_result) 都 tagged 到 old sub-task 1 的 step array 里。Timeline events:

```
- user A (T1)              ← first prompt
- sub-task 1 (T1)          ← still old sub-task with old content + new content appended
- user B (T2)              ← second prompt, alone
```

Sort: user A, sub-task 1, user B — **sub-task 1 在 user B 上面**, 但里面装着 first prompt + second prompt 的所有 content → "都输出到上面去了"。

## R272 修复

### Desktop fix — `sendMessage` 提交时清 sub-task tracking

```typescript
// R272 (2026-09-15): reset sub-task / step tracking before
// firing off the new query. Without this, the OLD
// currentSubTaskId from the previous query leaks into the
// new run_start handler — the new step inherits the old
// sub-task id, and every text_delta / tool_use for the
// new prompt is bucketed into the OLD sub-task card.
set({
  currentSubTaskId: null,
  currentStepId: null,
});
// R267 polish: queue the prompt instead of dropping...
if (get().isStreaming) {
  set({ pendingFollowUp: input, currentInput: '' });
  ...
  return;
}
```

**关键决策**：只清 `currentSubTaskId` + `currentStepId`，**不**清 `subTasks` array —— 旧 sub-task 卡片保留可见（用户能继续 navigate 历史）。新 step 的 `subTaskId=null` → 走到 `pre` (preamble) array → 在 timeline 里跟 user B 自然相邻。

### 如果 model 创建新的 sub-task

`sub_task_start` event 在新 step 创建之后才来 (subTaskId=null 已经是新的了):

1. run_start → 新 step with subTaskId=null
2. LLM 生成 first text_delta → step.text += "..."
3. LLM 调用 sub_todo_write 声明新 sub-task
4. daemon emits SubTaskStart(new_id)
5. desktop store sub_task_start handler:
   - subTasks 新增 entry (新 sub-task id)
   - currentSubTaskId = new_id
6. LLM 调用 file_write → tool_use_start
7. desktop store 把 tool event tag 给 current step (新 step), 但 step 的 subTaskId 仍是 null → 仍 in `pre`

也就是说：**新 step 的所有 content 都在 `pre` 里**。如果 model 后续开新 sub-task,那个 sub-task 在 timeline 里是空的 (steps 不在里面)。

视觉上: user B 下面 → `pre` 块 (新 step 内容)。新 sub-task 卡片可能出现在右边但不关联 step content。这是合理的 trade-off —— 比"内容都跑到 old sub-task 上面"好得多。

## 测试

### `sendMessageR272.test.ts` (3 个新 test)

1. **`clears currentSubTaskId when the user submits a new prompt`** — 模拟旧 sub-task "0:0" in_progress + step "step-old" tagged with old id; sendMessage 后 `currentSubTaskId=null` 但 `subTasks` array + `steps` array 保留
2. **`leaves the steps array intact`** — historical steps 完整保留,只有 live pointer 清掉
3. **`run_start after sendMessage creates a step with subTaskId=null`** — 模拟 run_start handler 在清完后执行:新 step 的 `subTaskId === null`,不会被错误 tag 到 old sub-task

### 测试结果

```
vitest 1087 → 1090 (+3), 0 regression
mvn protocol 269 → 274 pass (R271 daemon, jar 未变)
```

## 部署

### Build

```bash
mvn install -pl aethercode-protocol,aethercode-cli -am -DskipTests       ✓ (R271 jar 复用)
npx tsc -b --noEmit                                                       ✓ clean
npm run build                                                              ✓ built in 8.77s, bundle index-CX8eDVwt.js
cargo build --release --features tauri/custom-protocol                     ✓ 3m 55s
```

### 部署链

| 项 | 值 |
|---|---|
| commit | `e4be6c5` (push 成功) |
| exe SHA | `C19A0AB56A17E91F84A747930684ADAF1E521322` (R272 desktop build, JS bundle `CX8eDVwt`) |
| jar SHA | `A7379FC5...` (R271 daemon, 沿用) |
| zip SHA | `11A11E6024122337A4C61B1F70603F328F3FAE94` (108,495,927 bytes) |
| 老 zip | `.prev.bak` (R271 backup) |

### Exe bundle reference

`assets/index-CX8eDVwt` (R272 JS) + `assets/index-QOXXRanI` (R270 CSS, 沿用)

## 用户下一步验证

1. 双击 `release\aethercode-0.2.70\desktop\aethercode-desktop.exe` 重启 desktop
2. 切到 abc_1 session
3. **测 Bug 3**: 连续发两个 prompt (e.g. "请最后构建一次项目" 之类) → 第一个 prompt 下面独立 thinking + tool, 第二个 prompt 下面**独立**另一个 thinking + tool 块, **不再汇总到 first prompt 的 sub-task 里**

## 教训 (新增 2 条)

547. **stale live pointer 跨 query 是常见 bug** — `currentSubTaskId`、`currentStepId`、`currentQuery` 这些 "live" state 在新 query 启动时必须显式清零,否则上一次 query 的状态泄漏到下一次 query。daemon 端的 `emitSubTaskTransitions` 也会 re-emit 老 sub-task, 加上 live pointer 没清,导致 step tag 错乱。
548. **只清 live pointer,不清历史 array** — `subTasks`、`steps`、`messages` 这些是历史数据,应该保留(用户能 scroll up 看)。只清 live tracking (currentXxx),让新 query 从空白开始。

## 文件清单

### 修改
- `aethercode-desktop/src/store/index.ts` (`sendMessage` 加清 sub-task tracking)
- `release/aethercode-0.2.70/RELEASE-NOTES.md` (R272 section)

### 新增
- `aethercode-desktop/src/store/sendMessageR272.test.ts` (3 test)
- `doc/round-notes/R272-send-message-resets-sub-task-2026-09-15.md` (本文)