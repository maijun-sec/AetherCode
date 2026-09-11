# R97-E — UX fixes (Enter send, send button 缩, no DOS window, run-lock 抗 race)

## 触发

User feedback on R97-D (2026-08-17), App 端 6 个问题:
1. **架构问题** — daemon 跟 cwd 强绑定, 应该 daemon 无 cwd, session 跟 cwd 关联
2. **两个 DOS 窗口** — App 启 daemon 时, 弹两个 `cmd.exe` 黑框
3. **"输入继续后连接断"** — auto-continue 跟 user query race, WS 看起来断
4. **页面部分内容显示重叠**
5. **Enter 应该是 send 键** — 当前 Ctrl+Enter 才发
6. **Send 按钮太大** — 应该缩小放右下角

R97-E 解决 2/3/5/6 四个。问题 1 留作 R97-M 跟 user 一起决定,问题 4 等 user 重新测试 rebuild 后反馈。

## 改动

### 问题 2: Rust spawn 加 CREATE_NO_WINDOW

`aethercode-desktop/src-tauri/src/lib.rs`:
- `Command::new(java)` 加 `creation_flags(0x08000000)` (Windows 专属)
- `#[cfg(windows)]` / `#[cfg(not(windows))]` gate 兼容 macOS/Linux
- 之前: 启 primary daemon + pre-warm daemon = 2 个黑框
- 之后: 0 个黑框 (JVM 仍然在跑, 但没有窗口)

### 问题 5: Enter 发, Shift+Enter 换行

`aethercode-desktop/src/components/MessageInput.tsx`:
- `onKeyDown` 修: 之前 `Enter && (ctrl || meta)` 触发 send, 改成 `Enter && !(ctrl || meta)` 触发 send
- `Ctrl+Enter` 走 textarea 默认(换行)
- `Shift+Enter` 同样换行 (默认)
- Mention / slash dropdown 里的 Enter 行为保持: 选中 dropdown, 不发

### 问题 6: Send 按钮缩成 32x32, 放 textarea 右下角

`MessageInput.tsx`:
- `<button className="message-input-btn primary">` 改成 `<button className="message-input-corner primary">`
- 内容从 `➤ Send` 缩到 `➤` (icon only)
- Cancel 同样缩到 `⏹` icon, 加 pulse animation
- tooltip 从 "Send (Ctrl+Enter)" 改成 "Send (Enter)"

`MessageInput.css`:
- `.message-input-wrap` 改成单 column (无 flex row)
- `.message-input-corner` 绝对定位 right: 8px, bottom: 8px, 32x32, 圆角 8px
- `.message-input` padding-right 56px (清出 send button 空间)
- width: 100% (替代 flex: 1)

### 问题 3: Session-level run lock

**Root cause**: query 1 跑完后, SESSION_IDLE 触发, TodoContinuationHook 启动 2s 倒计时, auto-continue 调 `engine.query(prompt)` 开新 run。User 看到 run 1 完成后, 立刻输 "继续" → query 2。query 2 跟 auto-continue 同时跑, 两个 engine.query() 流共享同一个 session, transcript 跟 tool_use_start 事件交错, session 的 in-flight 状态机出错, WS 看起来断。

**修法**: 加 session-level run lock。

`aethercode-protocol/src/main/java/.../AetherCodeMethods.java`:
- `Map<String, String> sessionRunLock` (sessionId → runId)
- `query()` 开始时 `putIfAbsent` acquire lock; 失败时 wait 800ms, 再失败就返 `{ok: false, error: "session is busy with run X", busyRunId: X}`
- run-end finally 里 `sessionRunLock.remove(sid, runId)` 释放
- `cancelPendingContinuationCountdown(_targetSid)` 在 query() 入口就调, 取消任何 2s 倒计时

`EngineContinuationDispatcher.java`:
- `dispatchContinuation()` 入口调 `methods.tryAcquireSessionLockForContinuation(sessionId)`
- 失败 (返回 null) → skip dispatch, 不 race
- 成功 (返回 newRunId) → 跑 engine.query, finally 调 `methods.releaseSessionLock`

**关键 timing**:
- user 输 "继续" 立即 acquire lock (wait 800ms 给前一个 run 收尾)
- auto-continue 2s 倒计时在 query() 入口被 `cancelPendingContinuationCountdown` 取消
- 即使 auto-continue 已经 fire 派线程, 派线程的 `tryAcquireSessionLockForContinuation` 也会失败 → skip
- 永远不会两个 run 在同 session 同时跑

**UI 端处理** (`aethercode-desktop/src/store/index.ts`):
- `sendMessage` 检查 `r.ok === false` → 显示 `[busy] ...` system message
- 不假装 query 成功, 不污染 chat history

## Test coverage

### Java 单元测试 (新增 3 个, R97-E)

`aethercode-protocol/src/test/java/.../SessionManagerRpcTest.java`:
- `r97E_queryAcquiresSessionLockAndReleasesIt` — 单 query 拿锁, run 结束释放
- `r97E_secondQueryWhileFirstInFlightIsRejected` — 锁住后, 第二个 query 失败 with `{ok: false, error: "session is busy..."}`
- `r97E_continuationDispatchAcquiresAndReleasesSessionLock` — dispatcher 也走同一个 lock, session busy 时 dispatch 跳过

3/3 tests pass

### Mvn 全量

- aethercode-sdk: 152 unchanged
- aethercode-protocol: 88 (R97-G 85 + 3 R97-E)
- 其他 14 modules: unchanged
- **Total Java**: **1914 tests** (vs R97-D 1911, +3 R97-E)

### TS

- `npx tsc --noEmit` 通过
- 33 个 desktop store test 不变

### Tauri build

- 第一次 build (R97-E 改动全部): 17:25 完成, exe 3,672,064 bytes (R97-D 末 3,671,552 + 512 bytes Rust 改动), SHA256 `B23B6133E829C01B9D130168FF995D1E43B510BD093AD91E0C97FA1CE6EC31D8`
- 第二次 build (R97-E 包含 busy 错误 UI): running

## Build artifacts (R97-E 末)

- `dist/aethercode-0.2.1.jar` 39,893,284 bytes (R97-D 末 39,892,339 + 945 bytes sessionRunLock)
- `dist/ac-tui/ac-tui.js` 1.59 MB unchanged
- `dist/release-r97g/app/aethercode-desktop.exe` 3,672,064 bytes (R97-D 末 3,671,552 + 512 bytes CREATE_NO_WINDOW)
- `dist/release-r97g.zip` + `dist/release-r97g.tar.gz` rebuild

## Files

### 修改
- `aethercode-desktop/src-tauri/src/lib.rs` — Rust spawn 加 CREATE_NO_WINDOW
- `aethercode-desktop/src/components/MessageInput.tsx` — Enter send, send button 改 corner icon
- `aethercode-desktop/src/components/MessageInput.css` — corner button 绝对定位, wrap 单 column
- `aethercode-desktop/src/store/index.ts` — busy 错误处理 (R97-E)
- `aethercode/aethercode-protocol/src/main/java/.../AetherCodeMethods.java` — sessionRunLock, tryAcquireSessionLockForContinuation, releaseSessionLock, test-only helpers
- `aethercode/aethercode-protocol/src/main/java/.../EngineContinuationDispatcher.java` — dispatchContinuation 走 sessionRunLock
- `aethercode/docs/CHANGELOG.md` — R97-E entry (0.3.8)

### 新增
- `aethercode/docs/R97-E-UX-FIXES.md` (本文件)
- 3 个 Java 单元测试 (R97-E)
- 改 1 个 Rust 文件 (CREATE_NO_WINDOW)

## 关键决策

1. **CREATE_NO_WINDOW 是 Windows 专属**: `#[cfg(windows)]` gate。`creation_flags(0x08000000)` 等于 Win32 `CREATE_NO_WINDOW`。macOS / Linux 不需要这个
2. **sessionRunLock 是 by-session 而非 by-engine**: 因为 session 是 user 可见的 unit, user 切 session 时可以并发跑不同 session 的 run
3. **800ms wait gate**: 大部分 run 在 800ms 内结束 (model 流完第一波 text 就到 end), wait 一下避免 false rejection。Hard cap 后, 第二次 query 真的 busy → 返 error
4. **busy 返 `{ok: false, error}` 而不是 throw**: daemon 不抛 `JsonRpcProtocolException`, 而是 result 字段返 error。Renderer 端 check `r.ok === false` (不是 catch)
5. **Cancel button 加 pulse animation**: 让 user 看到 "引擎在跑, 这个按钮可以停它"。之前那个 ⏹ Cancel 跟 Send 一样大的版本, pulse 比静止更能传达 "正在做什么"
6. **Enter 跟 Shift+Enter 一起支持换行**: Shift+Enter 走默认, Ctrl+Enter 走默认, Enter 自定义。这样 3 种 newline binding 都能用, Enter 仍然是 send
7. **Send 按钮缩成 32x32 不显示 "Send" 文字**: icon-only 是 chat-app 的 convention (Slack, WeChat, iMessage, ChatGPT)。文字 "Send" 占空间, 含义已经通过 icon ➤ 表达

## Lessons

- **`engine.query()` 流是 single-session**: 多个流共享同一 session 的 transcript 跟 todoList, 状态机不是 thread-safe。两个 run 同时跑会 corruption
- **auto-continue 的 2s 窗口是 race window**: user-typed query 跟 auto-continue 在这 2s 内可能 race。Cancel pending countdown + acquire lock 是 two-step 保护
- **`{ok: false, error}` 跟 throw 都要 handle**: 一些 error 是 RPC layer 抛 (throw), 一些是业务逻辑返 (result.ok=false)。TS 端两路都要看
- **CREATE_NO_WINDOW 在 Windows 上 isTableForBackgroundProcess**: 启动子进程没窗口是 Background process 的基本要求。Tauri 启动 JVM 不应该弹 cmd
- **icon-only button 节省空间不损语义**: 32x32 corner button + tooltip 比 72x wide "Send" 文字 button 更现代、更小

## 后续

- **R97-F (TBD)**: 解决 user 的架构问题 — daemon 不绑 cwd, session 绑 cwd
- **R97-G (TBD)**: 处理 user 测试后的 overlap 反馈 (需要 user 重新测试 rebuild 看)
