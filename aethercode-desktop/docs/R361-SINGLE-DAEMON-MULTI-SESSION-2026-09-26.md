# R361 — Single daemon, multi-session, multi-cwd, multi-frontend

**Date:** 2026-09-26
**Scope:** desktop + daemon
**Status:** implemented (Phase 1 + 2 + 3 + 4 complete, 2026-09-26)

## TL;DR

把"切 cwd = 起新 daemon" 这层 Rust supervisor 抽象彻底拆掉。回到 daemon Java 端**本来就支持**的 single-process multi-session 架构：所有 RPC 都带 `sessionId`，daemon 内部用 `SessionManager` 管 N 个 engine，每个 engine 独立 cwd；前端可以是 desktop / TUI / idea-plugin 任意多个，每个前端持有独立 WS，同一个 daemon 同时服务所有前端。

驱动力：
1. **过度 swap**——切 cwd 触发 `pre_warm_daemon` + `swap_to_pre_warm`，~1.5s 延迟，杀死老 JVM
2. **bug 1+2+3**（R360 已修了一部分）—— 老 daemon 被 kill 时 WS 断了，store 端 messages / sessions 残留
3. **多前端需求**——同一台机器要 desktop + TUI + idea-plugin 共用一个 daemon，每个前端独立 session
4. **存储需求**——session 累积需要压缩，原始 + 压缩双存储

## 设计目标

| 需求 | 当前 | 目标 |
|------|------|------|
| 一个 daemon 服务多个 session | ❌ 1 JVM 1 engine 1 cwd | ✅ 1 JVM N engine，每个独立 cwd |
| 多个前端共用一个 daemon | ❌ Rust 强绑 cwd | ✅ N 个 WS 同时连同一 daemon，按 sessionId 路由 |
| 切 cwd 体感 | ❌ ~1.5s swap | ✅ 立即（只是 engine 内部 cwd 切换） |
| 切 session 不影响其他 session | ⚠️ session 级 lock（busy 阻塞） | ✅ session 级 lock 保留，但 session 切换是元数据切换，不影响 daemon |
| SessionStore 跨 daemon 共享 | ❌ 每个 daemon 一份 JSONL | ✅ 全局 SQLite 单文件 |
| 原始 + 压缩双存储 | ❌ 只存当前 transcript | ✅ `transcript.jsonl` 原始 + `compacted.jsonl` 压缩 |
| 空 session 不落盘 | ⚠️ lazy-create 部分实现 | ✅ 显式 lazy-create，第一个 query 成功后落盘 |

## 架构变化

### Before (R199)

```
desktop (WS client)
       │
       ▼
Rust supervisor
   ├─ state.daemon_handle: Option<Child>
   ├─ state.pre_warm: Option<DaemonInfo>
   ├─ state.cwd: Option<PathBuf>  (单 cwd，跟 JVM 绑死)
   └─ WS task
       │
       ▼
JVM daemon (abc_1)
   ├─ Engine 1 (cwd=abc_1)
   ├─ SessionStore (cwd/abc_1/.aethercode/sessions/)
   └─ WS server :17888
```

切换 cwd 到 abc_2 时：
1. `pre_warm_daemon(abc_2)` —— 起新 JVM `:17889`
2. `swap_to_pre_warm()` —— kill abc_1 JVM，promote abc_2
3. WS 重连
4. RPC `createSession({cwd: abc_2})` 在 abc_2 上
5. 老 abc_1 daemon 永久消失（它的 sessions 留在磁盘 cwd/abc_1/.aethercode/sessions/ 里，下次启动 abc_1 才看得到）

### After (R361)

```
desktop-frontend-A ── WS-A ──┐
desktop-frontend-B ── WS-B ──┼─→ Rust supervisor (per-WS routing) ─→ JVM daemon
TUI             ── WS-C ──┤                                       (1 JVM, 1 process)
idea-plugin     ── WS-D ──┘                                              │
                                                                         ▼
                                                              SessionManager
                                                              ├─ engine-1 (cwd=abc_1, sessions=[s1])
                                                              ├─ engine-2 (cwd=abc_2, sessions=[s2, s3])
                                                              └─ engine-3 (cwd=abc_3, sessions=[s4])
                                                                         │
                                                                         ▼
                                                              Shared SessionStore
                                                              (SQLite @ ~/.aethercode/sessions.db)
                                                              ├─ transcript (原始)
                                                              └─ compacted  (压缩)
```

**关键变化：**
- Rust supervisor 只负责 WS 透传（per-WS state），不再管 cwd / swap
- daemon 启动参数去掉 `--cwd`、`--sessions-dir` hardcode，改为环境变量 / 默认值
- 所有 RPC 都带 `sessionId`（向后兼容：空字符串 → active engine）
- SessionStore 全局 SQLite，所有 engine 共享

## RPC Schema

### 向后兼容原则

- 所有**已有**的 RPC 接受可选 `sessionId` 字段（`null` / 空字符串 → active engine）
- 新 RPC 不破坏已有 client 的 wire shape

### 新增 / 修改的 RPC

| RPC | 改动 | 说明 |
|-----|------|------|
| `createSession({sessionId?, cwd?, worktree?, model?, firstPrompt?})` | 扩展 | 现在调用 SessionManager.createEngine 包装，可以跨 cwd |
| `bindSessionCwd({sessionId?, cwd})` | 已有 | 不变，session-level cwd binding |
| `loadSession({sessionId})` | 已有 | 不变 |
| `deleteSession({sessionId})` | 已有 | 不变 |
| `query({prompt, sessionId?})` | 扩展 | 加 sessionId 路由 |
| `getState({sessionId?})` | 已有 | 加 sessionId |
| `setModel({model, sessionId?})` | 已有 | 加 sessionId |
| `setPermissionMode({mode, sessionId?})` | 已有 | 加 sessionId |
| `cancel({sessionId?})` | 已有 | 加 sessionId |
| `listSessions({withPreview?, limit?})` | 已有 | 不变（已经返回全量 session） |
| `listEngines({})` | 已有 | 不变（SessionManager 已有 list） |
| `setActiveEngine({sessionId})` | 已有 | 不变 |
| `getActiveEngine({})` | 已有 | 不变 |
| `getEngineState({sessionId?})` | 新 | 取指定 engine 的 state |
| `getTranscript({sessionId})` | 已有 | 不变 |
| `createEngine({sessionId, cwd?, worktree?, model?, firstPrompt?})` | 已有 | 不变，明确接口 |
| `compactSession({sessionId, force?})` | 新 | 显式触发 transcript → compacted 转换 |
| `getCompactedTranscript({sessionId})` | 新 | 取压缩版 transcript |

### `sessionId` 字段语义

- **存在 + 非空** → 路由到该 sessionId 对应的 engine
- **缺失 / `null` / 空字符串** → 路由到当前 active engine（向后兼容）
- 客户端（desktop/TUI/idea-plugin）**应该**显式带 sessionId，但 daemon 容错兼容

### 事件通知

- `stream_event`（R360 已加 `sessionId`）
- `transcript_event`（已有 `sessionId`）
- `cwd_changed`（已有 `sessionId`）
- `permission_request`（需要加 `sessionId`，参见改动清单）
- `permission_auto_approved`（同上）
- `subagent_event`（已有）
- `task_state`（需要加 `sessionId`）
- `task_event`（同上）
- `log`（保持原样）
- `notification_*`（保持原样）

## SessionStore 改造

### 当前实现

每个 daemon 启动时 `--sessions-dir <cwd>/.aethercode/sessions`，每个 daemon 目录里有一堆 JSONL：

```
abc_1/.aethercode/sessions/
  ├─ s1.jsonl     # 原始 transcript
  ├─ s1.cwd       # cwd sidecar
  ├─ s1.metadata.json
  ├─ s2.jsonl
  └─ s2.cwd
```

### 目标实现

**全局 SQLite 单文件**（`~/.aethercode/sessions.db`）：

```sql
CREATE TABLE sessions (
  id TEXT PRIMARY KEY,
  cwd TEXT,
  worktree TEXT,
  model TEXT,
  permission_mode TEXT,
  first_prompt TEXT,
  created_at INTEGER NOT NULL,
  last_used_at INTEGER NOT NULL,
  message_count INTEGER NOT NULL,
  -- 双存储
  raw_path TEXT,        -- 原始 transcript JSONL 文件路径（写时复制到 ~/.aethercode/sessions/<id>.jsonl）
  compacted_path TEXT,  -- 压缩后 transcript JSONL 路径
  compacted_at INTEGER, -- 最后一次压缩时间
  -- 元数据
  metadata_json TEXT
);

CREATE INDEX idx_sessions_cwd ON sessions(cwd);
CREATE INDEX idx_sessions_last_used ON sessions(last_used_at);
```

**双存储规则：**

- `transcript` JSONL 文件持续写入（每次 append message）
- 当 `message_count > COMPACTION_THRESHOLD`（默认 50 条消息）触发自动压缩：
  - 旧的 transcript 保留为 `raw`（用于 debug / 历史回放）
  - 压缩后的精简版写入 `compacted` JSONL
  - 引擎运行时读 `compacted`（context window 内）
- 压缩策略沿用 R347-R350 已有的 compaction 流程（不需要重写）

**保留旧的 per-cwd JSONL**作为 raw transcript 的物理位置（不要全在一个 SQLite BLOB 里，查询性能差）。

```
~/.aethercode/
  ├─ sessions.db                     # SQLite 元数据
  └─ sessions/
      ├─ <id>.jsonl                  # 原始 transcript
      └─ <id>.compacted.jsonl         # 压缩后 transcript（按需）
```

**空 session 不落盘：**

- `createEngine` 只在 SessionManager 内存里加 handle
- 第一个 `query` 成功后，才把 session 写入 SQLite + 创建 `<id>.jsonl`
- 老逻辑已经叫 "lazy-create"，R361 显式声明这个语义

## Rust supervisor 改造

### 删除

- `pre_warm_daemon()` 调用路径（仅在 `set_cwd_daemon` 中使用，删掉）
- `swap_to_pre_warm()` 调用路径（仅在 `set_cwd_daemon` 中使用，删掉）
- `state.pre_warm` 字段
- `state.pre_warm_handle` 字段
- `--cwd` 启动参数（不再需要，daemon 启动后 session-level cwd 通过 `bindSessionCwd` 设置）
- `--sessions-dir` 启动参数 hardcode（改为读 `AETHERCODE_SESSIONS_DIR` env，默认 `~/.aethercode/sessions/`）

### 新增 / 修改

- `rpc_call(method, params, session_id)` —— Tauri command 增加可选 `session_id` 字段，透传到 daemon WS
- `set_cwd(path)` —— 改为：
  1. 调 `rpc.bindSessionCwd({sessionId: currentSessionId, cwd: path})`
  2. 不重启 daemon，不杀 pre-warm
- `ensure_daemon()` —— 只在 daemon 没起时启动一次；启动后 `cwd` 仅用于 daemon 启动命令（不是 session 级 cwd）
- `create_session({sessionId, cwd?, worktree?, model?, firstPrompt?})` —— 直接转发到 daemon WS
- 多 WS 连接：每个前端启动时独立 WS（不复用现有 ws_tx），daemon WS server 是多 client 的（已有 JsonRpcServer 支持）

### state.daemon 结构变化

```rust
// 之前
struct AppState {
    daemon_handle: Mutex<Option<Child>>,
    pre_warm: Mutex<Option<PreWarmSlot>>,
    daemon: Mutex<Option<DaemonInfo>>,  // 含 cwd
    ws_tx: Mutex<Option<WsSender>>,
    cwd: Mutex<Option<PathBuf>>,
    ...
}

// 之后
struct AppState {
    daemon_handle: Mutex<Option<Child>>,
    daemon: Mutex<Option<DaemonInfo>>,  // 不含 cwd，加 primary_session_id: String
    ws_clients: Mutex<HashMap<ClientId, WsSender>>,  // 多 WS
    primary_session_id: Mutex<Option<String>>,  // daemon 启动时注册的 default session
    ...
}
```

## Desktop store 改造

### RPC 调用统一带 sessionId

```ts
// 之前
const r = await rpc.query(input, { sessionId: get().currentSessionId ?? undefined });

// 之后（已经部分在做）
const r = await rpc.query(input, { sessionId: get().currentSessionId ?? '' });
```

所有 `rpc.xxx` 调用都传 `sessionId`。

### `setCwd` 改造

```ts
setCwd: async (path) => {
    // 之前：调 Rust supervisor 的 set_cwd_daemon → 起新 daemon + swap
    // 之后：调 daemon 的 bindSessionCwd，session-level cwd 切换
    const sid = get().currentSessionId;
    if (!sid) {
        // 没 active session，just update local cwd slot
        set({ cwd: path });
        return;
    }
    await rpc.bindSessionCwd({ sessionId: sid, cwd: path });
    set({ cwd: path });
    // 不再清空 messages / sessions / 不再 refresh daemonInfo
}
```

### `createNewSession` 改造

```ts
createNewSession: async (opts) => {
    const newId = crypto.randomUUID();
    const cwd = get().cwd ?? undefined;
    // 之前：调 Rust supervisor 的 swap + createSession
    // 之后：直接调 daemon 的 createEngine
    await rpc.createEngine({ sessionId: newId, cwd, firstPrompt: undefined });
    set({
        currentSessionId: newId,
        cwd,
        messages: [],
        // ... reset live-streaming state (R360 already)
    });
}
```

**空 session 不落盘**：让 daemon 的 lazy-create 处理，desktop 不强制落盘。

### `switchSession` 改造

```ts
switchSession: async (sessionId) => {
    // 之前：调 Rust supervisor 的 loadSession + WS 重连
    // 之后：直接调 daemon 的 setActiveEngine + loadSession
    await rpc.setActiveEngine({ sessionId });
    await rpc.loadSession({ sessionId });
    // ... reset state
}
```

### `pickCwd` 改造

`pickCwd` 在 desktop 里调用 `setCwd`，自动适配。

## 测试矩阵

### 单元测试（Java）

| 测试 | 内容 |
|------|------|
| `bindSessionCwdRoutesToCorrectEngine` | `bindSessionCwd({sessionId: S2, cwd: C2})` 不会影响 S1 的 cwd |
| `createEngineAddsToSessionManager` | `createEngine({sessionId: S3, cwd: C3})` 后 `SessionManager.get(S3)` 返回 handle，cwd 正确 |
| `queryRoutesBySessionId` | `query({prompt, sessionId: S1})` 在 S1 上跑，不影响 S2 |
| `emptySessionNotPersisted` | `createEngine` 后立即 `deleteSession`，磁盘上无 `<id>.jsonl` |
| `firstQueryPersistsSession` | `createEngine + query` 后磁盘上有 `<id>.jsonl` |
| `compactionTriggered` | message_count > threshold 后自动写 `compacted.jsonl` |
| `sessionStoreSharedAcrossEngines` | 两个 engine 调用 listSessions 都看到全部 sessions |

### 集成测试（Rust supervisor）

| 测试 | 内容 |
|------|------|
| `rpcCallPassesSessionId` | `rpc_call(method, params, session_id)` 把 sessionId 透传到 daemon WS |
| `setCwdDoesNotRestartDaemon` | 调 `set_cwd`，daemon_handle 还是同一个 Child |
| `multiWsConnectionsCoexist` | 起两个 WS client，daemon 同时服务两个 |

### 端到端测试（Desktop store）

| 测试 | 内容 |
|------|------|
| `createNewSessionUsesRpc` | 调 createNewSession，发出 createEngine RPC |
| `switchSessionUsesRpc` | 调 switchSession，发出 setActiveEngine + loadSession RPC |
| `setCwdDoesNotClearMessages` | 调 setCwd，messages 保留 |
| `multipleSessionsCoexistInStore` | store.sessions 有多个 session |

### 兼容性测试

| 测试 | 内容 |
|------|------|
| `legacyClientStillWorks` | 不带 sessionId 的 RPC 仍然路由到 active engine |
| `wireShapeBackwardCompat` | 老客户端的 wire shape 不被破坏 |

## 实施阶段

### Phase 1 — RPC 路由纯化（最小化改动）

1. Java daemon 端：`bindSessionCwd` / `query` / `getState` / `setModel` / `setPermissionMode` / `cancel` / `setActiveEngine` 全部接受 `sessionId`（空 = active）
2. Java daemon 端：`SessionStore` 行为不变（保持 per-cwd JSONL，但允许不同 cwd 共存）
3. Rust supervisor：`set_cwd` 改为调 `bindSessionCwd`，不重启 daemon
4. Desktop store：`setCwd` 调 `bindSessionCwd`，不再 swap
5. Desktop store：`createNewSession` 调 `createEngine`（保留 daemon 端 lazy-create）
6. Desktop store：`switchSession` 调 `setActiveEngine` + `loadSession`

**Phase 1 验证**：desktop 切 cwd 不再重启 daemon，秒级响应；多个 session 在同一个 daemon 里共存；切 session 不影响其他 session。

### Phase 2 — SessionStore 共享

1. Java daemon：`SessionStore` 改用 SQLite 单文件
2. 保留 per-cwd JSONL 作为 raw transcript 物理位置（或者全用 SQLite BLOB，需要性能测试）
3. 双存储：原始 + 压缩

**Phase 2 验证**：所有 engine 的 listSessions 返回全量；空 session 不落盘；compaction 正常工作。

### Phase 3 — 多前端 WS

**结论：daemon 端已经满足要求，Rust supervisor 不需要改成多 WS。**

实现细节：

- **daemon 端**：`HttpJsonRpcServer` 已经按 multi-client 设计
  (`aethercode-protocol/src/main/java/org/aethercode/protocol/http/HttpJsonRpcServer.java:63` —
  `clients: ConcurrentHashMap<String, WsContext>`)。每个 WS 连接：
  - 独立 request id namespace（`HttpJsonRpcServer.java:633` 注释明确）
  - 共享 `AetherCodeMethods` 实例（同一 engine state）
  - 收到 `broadcastNotifier` 推送（line 133 + line 215）— 每个 appendMessage / run_end / session idle 都会 broadcast 到所有 client

- **Rust supervisor** 维持**单 WS** `ws_tx: Mutex<Option<mpsc::UnboundedSender<RpcRequest>>>` —
  它**只是 desktop Tauri backend → daemon** 的 client 通道，不是 multi-frontend 路径。
  Tauri 内部 `app.emit(...)` 已经自动 broadcast 给 desktop WebView 的所有 subscriber。

- **TUI / idea-plugin** 不经过 Rust supervisor —— 直接连 `ws://localhost:<http-port>/ws`。
  同 daemon 同时接受 desktop + TUI + idea-plugin 三个 client（不同 port 都不用，直接共用 daemon 的 HTTP+WS server）。

**R361 PM 决策**：不把 Rust supervisor 改成 `ws_clients: Mutex<HashMap<ClientId, WsSender>>`。
那个方向是错的：supervisor 服务桌面 renderer，TUI/idea-plugin 直接连 daemon。改 supervisor
不会让外部前端用上多 WS，反而引入无意义的复杂度。

**Phase 3 验证**：
- `HttpJsonRpcServerTest` 已覆盖 GET /、/healthz、/api/methods、/api/info 等 HTTP 路由
- daemon `engine.setTranscriptPush(http.broadcastNotifier)` 让每次 `appendMessage` 自动 broadcast 到所有 client
- Phase 1 + Phase 2 已确保 RPC handler `resolveRpcTarget(sessionId)` 按 session 路由，每个 session 状态独立
- 多前端验证留给未来 round（TUI / idea-plugin 实际接入时跑 E2E）

### Phase 4 — 文档 + 收尾

1. 把 R199 的"multi-daemon cwd"概念从代码里完全清掉：
   - 删 `pre_warm_daemon` / `swap_to_pre_warm` Tauri commands（dead code）
   - 删 `lib.rs` 里相关的注释 + state slot
2. 写 `docs/R361-SINGLE-DAEMON-MULTI-SESSION.md`（本文档已写）
3. 更新 `aethercode-cli/README.md` 反映 daemon 启动参数变化
4. 更新 `aethercode-desktop/README.md` 反映 set_cwd 行为变化

## 风险

| 风险 | 缓解 |
|------|------|
| daemon 端 RPC routing 改错导致 session 串台 | Phase 1 写完整单元测试覆盖每个 RPC 的 sessionId 路由 |
| 老的 wire shape 客户端破坏 | 向后兼容：sessionId 缺省 = active engine |
| SQLite 迁移数据丢失 | 保留 per-cwd JSONL 作为 raw transcript 物理位置（不要全在一个 SQLite BLOB 里），listSessions 优先读 SQLite 元数据 |
| 多前端 WS 性能 | daemon 端 WS server 已经有 NIO 实现，多 client 是常态 |
| 切 cwd 后老 session 的 transcript 怎么办 | 用户已经手动 load 了 session，cwd 是 per-session 元数据，不影响 transcript 内容 |
| `bindSessionCwd` 的 engine 状态 | 已经实现（`AetherCodeMethods.java:2033`），需要 verify 不影响 in-flight query |

## 兼容性

- 老 client 不带 sessionId：路由到 active engine（向后兼容）
- 新 client 带 sessionId：路由到指定 engine
- daemon 端 RPC handler：`resolveRpcTarget(sessionId)` 已经有空字符串 fallback 到 active

## 不在 R361 范围

- 多 daemon 分布式部署（多机共享 session）—— 后续 round
- Worktree 支持（`SessionSpec.worktree`）—— 已有接口，但 desktop 端没接
- TUI / idea-plugin 的 R361 适配 —— 后续 round（这次只做 desktop + daemon）

## 相关文件

- `aethercode-desktop/src-tauri/src/lib.rs` —— Rust supervisor
- `aethercode-desktop/src/store/index.ts` —— Desktop store
- `aethercode-desktop/src/components/ProjectGroup.tsx` —— useNewSessionInCwd
- `aethercode-desktop/src/components/LeftPanel.tsx` —— session 列表
- `aethercode-protocol/src/main/java/org/aethercode/protocol/methods/AetherCodeMethods.java` —— RPC handlers
- `aethercode-protocol/src/main/java/org/aethercode/protocol/methods/EngineContinuationDispatcher.java` —— continuation
- `aethercode-sdk/src/main/java/org/aethercode/sdk/SessionManager.java` —— SessionManager
- `aethercode-sdk/src/main/java/org/aethercode/sdk/SessionSpec.java` —— SessionSpec
- `aethercode-core/src/main/java/org/aethercode/core/transcript/SessionStore.java` —— SessionStore (要改成 SQLite)
- `aethercode-cli/src/main/java/org/aethercode/cli/DaemonRunner.java` —— DaemonRunner
- `aethercode-cli/src/main/java/org/aethercode/cli/Main.java` —— buildEngineForSession