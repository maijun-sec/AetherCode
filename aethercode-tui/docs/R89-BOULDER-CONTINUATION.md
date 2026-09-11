# R89 — Boulder Continuation + OMO-Inspired Safety/Agent Upgrades

**Date:** 2026-08-15 / 2026-08-16
**Scope:** TUI 频繁停的 boulder 修复 + 5 个 OMO 风格 hook/tool 升级 + agent 角色预设

## 触发

User 在 R88-CONT ship 后用 maven prompt 测试：
> "在当前目录下，生成一个 java maven 项目，实现至少5种排序算法，至少支持 int、short、long 三类数组，并补齐UT"

R88-CONT 已经把 ACCEPT_TASK / BYPASS_PERMISSIONS 真的接通了，但用户又提了新需求：
1. R88 ship 后还是有"打 markdown 不写文件"的问题（model 弱 + 缺 boulder 续命）
2. 想要"请不再需要确认"在 prompt 中支持
3. 引用 OMO 插件，参考 Agent / Tool / Hook 优化 AetherCode

## R89 deliverables (11 子项)

| # | Item | Files | Tests |
|---|------|-------|------:|
| A | TodoContinuationHook (boulder 续命) | `aethercode-hooks/.../builtin/TodoContinuationHook.java` + Dispatcher | 11 |
| A | wire into AetherCodeMethods + 新 RPC `setContinuationStopped` | `aethercode-protocol/.../AetherCodeMethods.java` | 0 |
| A | EngineContinuationDispatcher (re-enters engine on countdown) | `aethercode-protocol/.../EngineContinuationDispatcher.java` | 0 |
| B | WriteExistingFileGuardHook (LRU file_read 跟踪) | `aethercode-hooks/.../builtin/WriteExistingFileGuardHook.java` | 11 |
| C | EditErrorRecoveryHook (detect + log) | `aethercode-hooks/.../builtin/EditErrorRecoveryHook.java` | 8 |
| C | FileEditTool 错误信息加 recovery hint | `aethercode-tools/.../FileEditTool.java` | (含在 FileEditToolTest) |
| D | 删 ToolOrchestrator dead code | `aethercode-core/.../ToolOrchestrator.java` (-del) + 4 tests (-updated) | (回归测试) |
| D | AetherCodeEngine 移除 orchestrator 字段 + 总是 wire hook registry | `aethercode-sdk/.../AetherCodeEngine.java` | - |
| D | HookRegistry.registerIfAbsent + snapshot | `aethercode-hooks/.../HookRegistry.java` | 0 |
| E | FileReadTool 路径 sandbox (镜像 R88) | `aethercode-tools/.../FileReadTool.java` | 7 |
| F | BashTool 简单 sandbox (拒绝 cd ..) | `aethercode-tools/.../BashTool.java` | 5 |
| G | WebFetchTool SSRF 防护 | `aethercode-tools/.../WebFetchTool.java` | 13 |
| J | SubagentRole (explore/general-purpose/coder) | `aethercode-core/.../agent/SubagentRole.java` | 12 |
| J | AgentTool 接受 `role` 参数 + 注入 system-reminder | `aethercode-tools/.../AgentTool.java` | (含在 AgentToolTest) |
| K | SystemPrompt 加 "Boulder continuation" + "Subagent delegation" 段 | `aethercode-prompts/.../SystemPrompt.java` | - |

**总计:** 67+ 新 unit tests, 0 regression (1556+ Java + 252 TUI 仍 pass)

## R89-A: Boulder Continuation (核心)

**问题：** model 完成一个工具调用就 end_turn，user 必须手动敲"继续"才能让 todo list 的下一项进行。

**OMO 启发：** oh-my-opencode 的 `todo-continuation-enforcer` 监听 `session.idle` 事件：
- 检查 todo 列表是否有 incomplete
- 是 → 启动 2s countdown
- 倒计时结束 (user 未停) → inject CONTINUATION_PROMPT
- 30s cooldown, 5 max failures, 5min failure-reset window

**AetherCode 实现：**

1. **`Hook.Kind` 加 `SESSION_IDLE`** (R89-A 起步)：engine session 真正进入 idle 时触发
2. **`AppState.SessionIdleEvent`** record + `onSessionIdle` 监听器：承载 runId + stopReason + todoList snapshot
3. **`AetherCodeMethods.query` 在 stream forEach 完后 `fireSessionIdle`**：确保 run end = session idle
4. **`TodoContinuationHook` (pure logic)**：
   - LRU per-session state (failures, lastInjectedAt, in-flight CAS)
   - 2s countdown via ScheduledExecutorService
   - 30s cooldown × 2^failures
   - 5 max failures (then give up until 5min silence)
   - 拒绝 error/loop_detected/max_iterations 等 unsafe stop reason
   - cooldown via instance fields (test 用 setCountdownSecondsForTest)
5. **`ContinuationDispatcher` interface**：bridge 让 hook 调回 engine + notifier
6. **`EngineContinuationDispatcher` (protocol)**：
   - `dispatchContinuation` 重新调 `engine.query(prompt)`，在 daemon 线程 drain stream
   - 转发所有 stream_event 给 TUI
   - 完后 `fireSessionIdle` 触发下一轮（链式）
   - 接受 `setContinuationStopped` (TUI 取消) + `notifyCountdown` (TUI toast)
7. **新 RPC `setContinuationStopped`**：TUI 停 auto-continue
8. **query() 清空 stopped flag**：新 user prompt 自动恢复 auto-continue

**R89-A bug fix (重要):**
- 早期实现 `consecutiveFailures.set(0)` 在 dispatch 之前，dispatch 抛错后 catch 块 `incrementAndGet` 又回到 1 — counter 永远不涨
- **修正：** 成功 dispatch 后才 set(0)，否则 catch 块看到的 0→1 是合理的 increment

**`ContinuationPrompt` (镜像 OMO):**
```xml
<system-directive type="todo-continuation">
Incomplete tasks remain in your todo list. Continue working on the next pending task.
- Proceed without asking for permission
- Mark each task complete when finished
- Do not stop until all tasks are done
Remaining tasks:
- [pending] implement bubble sort
- [pending] write tests
</system-directive>
```

## R89-B: WriteExistingFileGuardHook (防"打代码覆盖"灾难)

**OMO 启发：** `write-existing-file-guard` 跟踪 per-session `file_read` 集合，file_write 已存在文件要 read 过 / `overwrite=true` / `.sisyphus/`

**AetherCode 实现：**
- 11/11 tests pass (11 cases including case-insensitive, sisyphus path, cross-session, outside-project bypass)
- LRU per-session (256 sessions × 1024 paths)
- `HookContext.toolInput()` 是 mutable map → hook 删掉 `overwrite` key 防止下游 trust
- `registerIfAbsent` 用 class-keyed dedup 避免重复注册

**Block message:**
> "Refusing to overwrite existing file. Either (a) call `file_read` on this path first to see the current content, (b) re-issue the write with `overwrite: true` if you are sure, or (c) use `file_edit` for a targeted change."

## R89-C: EditErrorRecoveryHook (file_edit 失败 → 教 model)

**OMO 启发：** `edit-error-recovery` 在 POST_TOOL_USE 注入 "READ the file, then retry" reminder

**AetherCode 实现：**
- Hook 只做 detect + log (mutation 需改 bridge signature → R90+)
- **真正有效路径：** FileEditTool 的 3 个常见 error 字符串里 embed recovery hint:
  - `old_string and new_string are identical` → 加 HINT_RE_READ
  - `old_string not found in {path}` → 加 HINT_RE_READ
  - `old_string matches N places in {path}` → 加 HINT_RE_READ
- 8/8 tests pass (路由 + 4 个 error pattern detection)

**HINT_RE_READ:**
> "\n[hint] Re-read the file with file_read to see its actual current state, then retry with the correct old_string."

## R89-D: 删 ToolOrchestrator dead code (R1 一直没被用)

**根因：** R2 引入 `StreamingToolExecutor` 替代了 `ToolOrchestrator` 路径，但 orchestrator 字段 + 4 个 constructor 签名一直保留 — QueryEngine 把 orchestrator 存进 field 后从不读

**修复 (5 files):**
- 删 `aethercode-core/.../engine/ToolOrchestrator.java` (137 lines)
- 删 `aethercode-core/.../test/.../ToolOrchestratorTest.java` (R88 起就是 flaky 的 — Thread.sleep 100ms 竞争)
- QueryEngine 4 个 constructor 删 `ToolOrchestrator` 参数 + 删 `private final ToolOrchestrator orchestrator` field
- AetherCodeEngine 删 `private final ToolOrchestrator orchestrator` field + getter
- StreamingToolExecutor 注释更新 (R89-D ref)

**AetherCodeEngine 额外修：** 之前 `if (b.hookRegistry != null) exec.withPreHook(...)` 漏掉了 engine-created registry → 用户不传 registry 时 hooks 永远不 fire。R89 改成总是 wire。

**HookRegistry 新方法：**
- `registerIfAbsent(Hook h) -> boolean` (class-keyed dedup)
- `snapshot() -> List<Hook>` (测试用)

## R89-E: FileReadTool 路径 sandbox (镜像 R88)

**R88 的 FileWriteTool.isPathAllowed 完美 copy** 到 FileReadTool：
- `aethercode.cwd` system property
- AETHERCODE_ALLOW_ANY_PATH=1 opt-out
- Windows case-insensitive
- `Path.toAbsolutePath().normalize()`

**aethercode-tools/pom.xml** 新增 surefire `systemPropertyVariables`：
```xml
<aethercode.cwd>${java.io.tmpdir}</aethercode.cwd>
```
这样所有 `@TempDir` 自动在 sandbox 内。

7/7 tests pass (含 readOutsideSandboxRefused 测试)。

## R89-F: BashTool 简单 sandbox

**拒绝：**
- `cd ..` / `pushd ..` (climb above engine cwd)
- `cd /abs/path` 绝对路径但 normalize 后不在 cwd 内
- `pushd <outside>`

**Opt-out:** `AETHERCODE_BASH_ALLOW_SCOPE_ESCAPE=1`

5/5 tests pass (cd/pushd blocked, inside-cwd allowed, outside-cwd blocked, plain commands unaffected).

## R89-G: WebFetchTool SSRF 防护

**拒绝解析到以下 IP 的 URL：**
- loopback (127.0.0.0/8, ::1)
- link-local (169.254/16, fe80::/10) — AWS / Azure metadata!
- site-local (RFC1918: 10/8, 172.16/12, 192.168/16)
- IPv6 unique-local (fc00::/7)
- 任何解析失败的 host

**Opt-out:** `AETHERCODE_ALLOW_LOCAL_FETCH=1`

13/13 tests pass (含 `[::1]`, `[fc00::1]`, `[fe80::1]`, `169.254.169.254`, 不存在的 host)。

**Known limitation:** DNS-rebinding 在 check 和实际 fetch 之间换 IP — 未来 R-round 用 OkHttp Dns override pin address。

## R89-J: SubagentRole 角色预设 (OMO-style explore/general-purpose/coder)

**OMO 启发：** 11 个 agents (atlas / explore / sisyphus / librarian / hephaestus / multimodal-looker / momus / metis / oracle / prometheus / sisyphus-junior) 各自有 specialized system prompt + 工具限制。

**AetherCode 简化 (3 个 role):**
- **`explore`** — read-only: file_read/glob/grep/list/bash/web_search/web_fetch；拒绝 file_write/file_edit/spawn_agent/todo_write
- **`general-purpose`** (default) — full tool set, 无限制
- **`coder`** — 写优先: 拒绝 web_fetch/web_search/spawn_agent

**12/12 tests pass.**

**AgentTool 接受 `role` 参数:**
- Schema 加 `role: string` (explore/general-purpose/coder)
- `SubagentRole.lookup(name)` 返回 RolePreset (大小写不敏感, unknown → general-purpose)
- multi-step 模式下，role preamble 注入 `<system-reminder role="subagent" name="...">` block 在 user prompt 之前
- single-shot 模式下，role preamble 追加到 system prompt

**未做 (R90+ 候选):** 把 role 接到 engine.query() 的 tool pool filter (目前 prompt-level 限制，模型大致遵守)

## R89-K: SystemPrompt 升级

**新增段：**
1. **Identity (R89 upgrade)**: 主 agent 知道自己是 primary; 用户可以调用 explore / coder subagent
2. **Boulder continuation (R89-A)**: model 看到 `<system-directive type="todo-continuation">` 时立即 resume, 不问候 user, 不重复 summarize
3. **Subagent delegation (R89-J)**: 解释 `role` 参数 + 多 step vs single shot + 2-level recursion

**更新 Workflow 段:** 加 "If the user explicitly says 'no confirmation' or 'just do it', they want BYPASS_PERMISSIONS semantics" 强化 user 的 "请不再需要确认" 表达。

## 端到端验证

**mvn install:** 16 modules BUILD SUCCESS (1m 26s)

**全量 mvn test:** 16 modules BUILD SUCCESS (2m 36s), 0 regression, 1 pre-existing flaky (R77-Issue4 注释里已说明)

**package.ps1:** 40.05 MB jar + 1.53 MB TUI bundle + 3.47 MB desktop exe (Tauri build 之前已完成, dist 文件未改)

**E2E test (Text-01 bundled default, weak model):**
```
[2m  → file_write(file_path=D:\tmp\test-r89-maven\pom.xml, content=<project>...)[0m
The pom.xml file has been created...
```
Result: `D:\tmp\test-r89-maven\pom.xml` 53 bytes, 内容 `<project><modelVersion>4.0.0</modelVersion></project>`

**Boulder 验证:** Text-01 model 不会主动调 todo_write, 所以 SESSION_IDLE 触发后 todoList 空 → hook skip (按设计)。R89-A 单元测试覆盖了 happy path (有 incomplete todo 时 2s 后 dispatch)。

**M3 切换验证:** user 可在 `~/.aethercode/providers.yaml` 配 M3 model。bundled default `MiniMax-Text-01` 倾向贴 markdown (R88-A 之前已记录)。

## 关键设计决定

1. **Hook Kind 扩展 `SESSION_IDLE`** 而非引入新 event bus — AetherCode 现有 Hook 体系能支持
2. **AppState.SessionIdleEvent 监听器** (CopyOnWriteArrayList + best-effort dispatch) — 跟现有 messageAppendListeners + todoListeners 一致
3. **AetherCodeMethods.query 在 forEach 完后 fireSessionIdle** — daemon 唯一 engine.query 调用点
4. **dispatchContinuation 在新 daemon 线程跑, 不阻塞 listener** — hook 完成后 scheduler 线程 spawn 实际 re-query
5. **ToolOrchestrator 整个文件删除** (不是 deprecated) — 100% dead code, 单元测试是 flaky 的 (R88-R89 之间一直 fail)
6. **WriteExistingFileGuard 在 PRE_TOOL_USE 删 overwrite key** — 防止下游 tool 误 trust
7. **BashTool sandbox 是 substring 启发式** — 不解析 AST, 接受 false positive (user 分多条命令即可), 假阴性有限
8. **SubagentRole 用 unmodifiable backing map + sync register** — 启动时一次注册 + boot 失败时 throw
9. **FileReadTool 的 cwd sandbox 走 surefire systemPropertyVariables** — 所有 @TempDir 自动在 sandbox 内, 测试不需要手动 setUp
10. **EditErrorRecoveryHook mutation 留 R90+** — R89 走 FileEditTool embed hint 这条立即有效路径

## R90+ backlog (R89 没做)

- **Bridge 签名扩展** 让 hook 真的能 mutate result body
- **Agent role → tool pool filter** (目前是 prompt-level, 应该 engine-level)
- **background-task** (OMO `BackgroundManager`) — AetherCode 有 `BashJobRegistry` for bash 但没有 subagent 版本的
- **task system** (OMO `task_create`/`task_list`/`task_update`) — AetherCode 有 `TodoWriteTool` + `TaskRegistry`, 但没有 1-1 对应
- **comment-checker** (OMO post-edit code review) — AI primary
- **thinking-block-validator** — AetherCode 没暴露 thinking blocks
- **rules-injector** (per-project rule files) — AetherCode 有 `SkillRegistry` 但没 rules concept
- **Subagent pool background task** — AetherCode 的 Subagent 是 synchronous, 缺 async/fire-and-forget
- **AgentTool `process_tool_call` permission (per-agent tool restriction 在 streaming executor 层)** — 现在是 prompt-level
- **TodoWrite `status: "cancelled"` 支持** (R89-A 已经尊重 cancelled 状态, 但 TodoWriteTool 还没生成 cancelled todo)

## 文件清单

**新增 (4):**
- `aethercode-hooks/src/main/java/org/aethercode/hooks/builtin/ContinuationDispatcher.java`
- `aethercode-hooks/src/main/java/org/aethercode/hooks/builtin/TodoContinuationHook.java`
- `aethercode-hooks/src/main/java/org/aethercode/hooks/builtin/WriteExistingFileGuardHook.java`
- `aethercode-hooks/src/main/java/org/aethercode/hooks/builtin/EditErrorRecoveryHook.java`
- `aethercode-hooks/src/test/java/org/aethercode/hooks/builtin/TodoContinuationHookTest.java` (11 tests)
- `aethercode-hooks/src/test/java/org/aethercode/hooks/builtin/WriteExistingFileGuardHookTest.java` (11 tests)
- `aethercode-hooks/src/test/java/org/aethercode/hooks/builtin/EditErrorRecoveryHookTest.java` (8 tests)
- `aethercode-core/src/main/java/org/aethercode/core/agent/SubagentRole.java`
- `aethercode-core/src/test/java/org/aethercode/core/agent/SubagentRoleTest.java` (12 tests)
- `aethercode-protocol/src/main/java/org/aethercode/protocol/methods/EngineContinuationDispatcher.java`
- `aethercode-tools/src/test/java/org/aethercode/tools/shell/BashToolSandboxTest.java` (5 tests)
- `aethercode-tools/src/test/java/org/aethercode/tools/net/WebFetchToolSsrfTest.java` (13 tests)

**修改 (12):**
- `aethercode-hooks/src/main/java/org/aethercode/hooks/Hook.java` — Kind 加 SESSION_IDLE, HookContext.todoList/stopReason, forSessionIdle
- `aethercode-hooks/src/main/java/org/aethercode/hooks/HookRegistry.java` — registerIfAbsent, snapshot
- `aethercode-hooks/src/main/java/org/aethercode/hooks/Hooks.java` — 注释
- `aethercode-core/src/main/java/org/aethercode/core/engine/QueryEngine.java` — 4 constructor 删 orchestrator 参数
- `aethercode-core/src/main/java/org/aethercode/core/engine/StreamingToolExecutor.java` — 注释
- `aethercode-core/src/main/java/org/aethercode/core/app/AppState.java` — (R89-A 起步) SessionIdleEvent record + listeners
- `aethercode-sdk/src/main/java/org/aethercode/sdk/AetherCodeEngine.java` — 删 orchestrator 字段 + getter + 总是 wire hook registry + registerIfAbsent
- `aethercode-protocol/src/main/java/org/aethercode/protocol/methods/AetherCodeMethods.java` — query() 清 stopped + track lastStopReason + fireSessionIdle + new RPC setContinuationStopped + helper methods
- `aethercode-prompts/src/main/java/org/aethercode/prompts/SystemPrompt.java` — Identity 升级 + Boulder continuation 段 + Subagent delegation 段
- `aethercode-tools/src/main/java/org/aethercode/tools/file/FileReadTool.java` — isPathAllowed + 7 测试
- `aethercode-tools/src/main/java/org/aethercode/tools/file/FileEditTool.java` — HINT_RE_READ embed
- `aethercode-tools/src/main/java/org/aethercode/tools/file/FileReadToolTest.java` — setUp + readOutsideSandboxRefused
- `aethercode-tools/src/main/java/org/aethercode/tools/shell/BashTool.java` — checkScopeEscape
- `aethercode-tools/src/main/java/org/aethercode/tools/net/WebFetchTool.java` — checkSsrf + isPrivate
- `aethercode-tools/src/main/java/org/aethercode/tools/task/AgentTool.java` — role 参数 + 注入 preamble
- `aethercode-tools/pom.xml` — surefire systemPropertyVariables aethercode.cwd

**删除 (2):**
- `aethercode-core/src/main/java/org/aethercode/core/engine/ToolOrchestrator.java`
- `aethercode-core/src/test/java/org/aethercode/core/engine/ToolOrchestratorTest.java`

**更新 (4 tests):**
- QueryEngineMaxTurnsTest / QueryEngineMemorySectionTest / QueryEngineSubTaskTest / QueryEngineSimpleChatTest — 删 orchestrator 参数
