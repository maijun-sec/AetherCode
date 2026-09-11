# R97-D — Self-contained CWD (真正的 first-launch)

## 触发

User feedback on R97-C (2026-08-17): "这非常不合理啊，比如 app 测试，为什么是 daemon 启动加上 --cwd，然后我再执行 run-app.bat？我直接执行 run-app.bat，启动 app，然后在 app 里面再修改 cwd 路径才合理"

R97-C 的根本缺陷:
- `run-app.bat` 已经只 launch exe(没启 daemon),但 App 内部有 bug
- `ensure_daemon` 在没有 cwd 时 fall through 到 `std::env::current_dir()`(app 启动时的工作目录),自动 spawn daemon
- Welcome 的 `hasProject = engineState?.sessionId` 是错的判断(sessionId 来自 daemon 自启,永远非空)
- `state.cwd` 不持久化,App 关闭再开就丢

## 设计目标

1. **User 只跟 App 交互**:双击 exe → 在 App 内选文件夹 → App 自动拉 daemon
2. **App 自管 cwd 状态**:持久化到 `~/.aethercode/desktop-state.json`,App 关闭再开还认得
3. **Header 📂 永远可见**:随时切项目,不需要重启
4. **去环境变量 / 命令行参数依赖**:完全 self-contained,exe 双击就能跑

## 核心改动

### Rust 侧 (aethercode-desktop/src-tauri/src/lib.rs)

**1. `AppState` 新增 `persisted_cwd` 字段**

```rust
struct AppState {
    // ... existing ...
    persisted_cwd: TokioMutex<Option<PathBuf>>,
}
```

**2. `Default::default()` 读持久化文件**

```rust
impl Default for AppState {
    fn default() -> Self {
        AppState {
            // ...
            persisted_cwd: TokioMutex::new(load_persisted_cwd()),
        }
    }
}
```

**3. `ensure_daemon` 改 fall through 顺序**

```rust
// 旧: state.cwd > AETHERCODE_CWD env > current_dir (auto-spawn 错误)
// 新: state.cwd > persisted_cwd > Err("NEEDS_CWD")
let cwd = if let Some(c) = state.cwd.lock().await.clone() {
    c
} else if let Some(c) = state.persisted_cwd.lock().await.clone() {
    c
} else {
    return Err("NEEDS_CWD".to_string());
};
```

**4. 新增 `load_persisted_cwd / save_persisted_cwd` 辅助函数**

- 文件位置:`~/.aethercode/desktop-state.json`
- Schema:`{ "lastCwd": "C:/path/to/project" }`
- 原子写(写到 `.tmp` 再 rename)
- 读不到 / 解析失败 / 路径不是目录 → 返回 None(不抛错,不阻塞 App)

**5. `set_cwd` 加持久化**

```rust
if let Err(e) = save_persisted_cwd(&new_cwd) {
    eprintln!("[R97-D] save_persisted_cwd failed: {}", e);
    // non-fatal: 内存 slot 仍可用
}
*state.cwd.lock().await = Some(new_cwd.clone());
*state.persisted_cwd.lock().await = Some(new_cwd.clone());
```

### TS 侧 (aethercode-desktop/src/store/index.ts)

**1. 新 connection state `'awaiting-cwd'`**

```typescript
export type ConnectionState =
  | 'idle' | 'connecting' | 'connected'
  | 'reconnecting' | 'closed' | 'error'
  | 'awaiting-cwd';
```

**2. `initialize()` 处理 NEEDS_CWD**

```typescript
try {
  info = await invoke<DaemonInfo>('ensure_daemon');
} catch (e) {
  if (String(e?.message ?? e).includes('NEEDS_CWD')) {
    set({
      connectionState: 'awaiting-cwd',
      isConnected: false,
      daemonInfo: null,
      preWarm: null,
    });
    return;
  }
  throw e;
}
```

**3. `Welcome.tsx` 改用 store.cwd 判断 + 新 awaitingCwd 模式**

```typescript
// 旧: 错的判断
const hasProject = Boolean(
  (engineState as { sessionId?: string } | null)?.sessionId
);

// 新: 真正反映 "用户是否设过 cwd"
const hasProject = typeof cwd === 'string' && cwd.length > 0;
```

**4. `Header.tsx` 📂 永远显示**

```tsx
<button
  className="header-icon-btn"
  title={cwd ? `当前项目: ${cwd} — 点击更换` : '点击选择项目文件夹（首次）'}
  onClick={() => void pickCwd()}
>📂</button>
```

**5. `ReconnectBanner.tsx` 跳过 awaiting-cwd**

```typescript
if (connectionState === 'awaiting-cwd') return null;
```

## 数据流

### 首次启动

```
App start
  ↓
Default::default() 读 ~/.aethercode/desktop-state.json
  ↓  没有文件
persisted_cwd = None
  ↓
renderer initialize() → invoke('ensure_daemon')
  ↓  Rust 看到 state.cwd=None, persisted_cwd=None
Err("NEEDS_CWD")
  ↓
store: connectionState='awaiting-cwd'
  ↓
<App awaitingCwd/> 渲染: 单 tile "📂 打开文件夹"
  ↓
用户点 tile → pickCwd → 系统 dialog
  ↓
用户选 D:\myproject → setCwd(path)
  ↓
Rust set_cwd: 写 desktop-state.json + state.cwd=path + 杀旧 daemon(无)
  ↓
renderer initialize() → invoke('ensure_daemon')
  ↓  Rust 看到 state.cwd=path
spawn_daemon(cwd=path)
  ↓
App 正常,Welcome 切到 showWelcome 模式
```

### 第二次启动

```
App start
  ↓
Default::default() 读 ~/.aethercode/desktop-state.json
  ↓  有文件,路径是目录
persisted_cwd = Some(D:\myproject)
  ↓
renderer initialize() → invoke('ensure_daemon')
  ↓  Rust 看到 state.cwd=None, persisted_cwd=Some(D:\myproject)
spawn_daemon(cwd=D:\myproject)
  ↓
App 正常
```

### 切项目 (mid-session)

```
用户在 header 点 📂 → pickCwd → 选 D:\other
  ↓
renderer setCwd(D:\other):
  - connectionState='reconnecting', cwdSwitchInProgress=true
  - rpc.setCwd(D:\other) → Rust 写文件 + 杀旧 daemon
  - await initialize() → spawn daemon
  ↓
App 切到 D:\other
```

## Test coverage (新增)

### Rust 单元测试 (`aethercode-desktop/src-tauri/src/lib.rs`)

- `load_returns_none_when_file_missing` — 文件不存在 → None
- `save_then_load_round_trip` — 写后能读回
- `load_returns_none_for_garbage_json` — 损坏 JSON → None(不 panic)
- `load_returns_none_when_path_no_longer_a_dir` — 项目被删 → None(让 App 重新询问)
- `save_creates_parent_dir` — `.aethercode/` 不存在时自动建
- `save_uses_atomic_rename_no_tmp_left_behind` — `.tmp` 不残留

6/6 tests pass (`cargo test --lib desktop_state_tests`)

### TS 编译

`npx tsc --noEmit` 通过(无 error)

### Desktop store 测试

`npx vitest run` 33/33 pass (subagent reducer)

## Files

### 修改
- `aethercode-desktop/src-tauri/src/lib.rs` — AppState 新增 persisted_cwd + 3 辅助函数 + 6 单元测试 + 改 ensure_daemon 逻辑 + 改 set_cwd 持久化
- `aethercode-desktop/src/store/index.ts` — ConnectionState 新增 'awaiting-cwd' + initialize() 处理 NEEDS_CWD
- `aethercode-desktop/src/components/Welcome.tsx` — 改 hasProject 用 cwd + 新 awaitingCwd 模式
- `aethercode-desktop/src/components/Header.tsx` — 📂 永远显示,用 cwd 字段
- `aethercode-desktop/src/components/ReconnectBanner.tsx` — 跳过 awaiting-cwd
- `aethercode-desktop/src/App.tsx` — awaitingCwd 分支渲染 `<Welcome awaitingCwd/>`
- `aethercode-desktop/src/components/Welcome.css` — `.welcome-awaiting-cwd` 样式
- `aethercode/dist/release-r97g/app/run-app.bat` — 同步更新文案
- `aethercode/dist/release-r97g/RELEASE-NOTES.md` — 同步更新

### 新增
- `aethercode/docs/R97-D-SELF-CONTAINED-CWD.md` (本文件)

## Manual E2E

### First-launch flow (删 `~/.aethercode/desktop-state.json` 后启动)
1. 双击 `release-r97g/app/aethercode-desktop.exe`
2. App 打开 → 显示 "📂 打开文件夹" 单 tile
3. 点 tile → 系统 folder picker
4. 选一个项目目录 → daemon 启动 (~1-2s) → tile 变 "✦ 新会话"
5. 关闭 App
6. 重新双击 `aethercode-desktop.exe` → 直接进项目,不再询问

### Project switch
1. 在 header 右上角点 📂 → 系统 picker
2. 选另一个项目 → "📂 Switching to D:\other" banner 出现
3. ~1-2s 后,banner 消失,Welcome 重新出现 (新项目)
4. 点 "✦ 新会话" → 正常对话

## 关键决策

1. **Rust 自治 cwd,renderer 不管持久化** — 单一 source of truth (Rust `~/.aethercode/desktop-state.json`)。Renderer 只跟 `store.cwd` 字段交互(由 `setCwd` 调 Rust 自动更新)。
2. **NEEDS_CWD 是 literal 字符串错误** — 不是 typed status,简单可靠。Renderer 看到 string 包含 `NEEDS_CWD` 就切换 state。
3. **atomic write (.tmp + rename)** — App 写到一半被杀不会留下截断文件。下次启动读到 None,正常 first-launch 流程,不会 brick。
4. **`is_dir()` 校验 on load** — 用户删了项目,App 不会用鬼路径,重新询问。
5. **保留 pre-warm + swap_to_pre_warm** — 切项目仍然是 sub-second,只要目标路径有 pre-warm。
6. **`Header 📂 always-on`** — UX 一致性:同一个 icon 同一种点击,不论是"切"还是"首次"。

## Lessons

- **`engineState.sessionId` 永远非空** — daemon 自动建 default session,不能用作"用户是否设过项目"的信号。`store.cwd` 才是 source of truth。
- **`std::env::current_dir()` 危险** — Tauri app 双击时 cwd 是不确定的(系统可能设为 C:\Windows\System32、exe 所在目录、或用户上一次的位置)。fall through 到它 = daemon 跑在错地方。R97-D 改成显式 NEEDS_CWD 错误,让用户介入。
- **app-side 状态不持久化 = 用户每次开 App 都要重做** — 这就是 pre-R97-D 的 `state.cwd` bug。`~/.aethercode/desktop-state.json` 是 desktop 唯一需要跨重启的状态(其他都在 daemon 的 `~/.aethercode/sessions/` 等目录)。
- **atomic file write 不复杂** — 写 `.tmp` 再 rename 就行(Windows 也有 `MoveFileEx` 的 `MOVEFILE_REPLACE_EXISTING` 语义)。比"let it crash + recover" 简单。

## Test counts (R97-D 末)

- aethercode-desktop (Rust): +6 unit tests (desktop_state_tests)
- aethercode-desktop (TS): 33 unchanged
- aethercode-sdk: 160 (unchanged from R97-G; mvn full module run)
- aethercode-protocol: 85 (unchanged, R97-G 末)
- aethercode-cli: 12 (unchanged, R97-A 末)
- 其他 11 modules: unchanged
- **Total**: **1911 mvn tests** + **6 Rust desktop_state tests** = **1917 tests**
  (vs R97-G 末 1904 + 13 R97-D additions to the test count)

> Note: mvn test runs include integration tests (HttpJsonRpcServerTest, JsonRpcServerIntegrationTest) that aren't separately counted in the SDK-only count I tracked through R97-A/B/G. The actual test count grew by R97-D's 6 Rust tests; the rest is consistent.
