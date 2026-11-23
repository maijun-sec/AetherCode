# R392 — 怎么创建 session，以及会话库为什么跟着项目跑

> 2026-11-23. 用户的原话：
>
> > 现在我怎么创建个 session，或者一个任务？另外你说的 --session-dir 可以顺带着修
>
> 截图里的桌面端长这样：
>
> ```
> CURRENT SESSION
> 📂 —
> Active
> 0 sessions
> ```
>
> 没有项目分组，没有「＋」按钮，没有「+ 新会话」。

---

## 0. 结论先说

用户问的「怎么创建 session」，在那一轮里**答案是：创建不了**。
不是「藏得深」，是**按钮根本不存在**。

三个互相独立的缺陷叠在一起，才凑出这张空白的左栏：

| # | 缺陷 | 位置 | 后果 |
|---|------|------|------|
| A | 渲染层的 `cwd` 从不恢复 | `store/index.ts` `initialize()` | 头部 `📂 —`；新建 session 没有 cwd 可绑 |
| B | 「＋ 新会话」按钮挂在**由会话派生的分组**里 | `ProjectGroup.tsx` `ProjectGroupList` | **0 会话 ⇒ 0 分组 ⇒ 0 按钮** |
| C | 会话库跟着项目走 | `src-tauri/src/lib.rs` `spawn_daemon_on_port` | 切一次项目，历史会话全部消失 |

「任务（sub-task）」是另一回事：**任务不是用户手动创建的**。
模型用 `todo_write` 声明，界面以子任务卡片呈现（R391 已修好归属）。

---

## 1. 缺陷 A：渲染层的 `cwd` 从不恢复

### 1.1 事实

store 里的 `cwd` 只有三个写入点：

| 写入点 | 何时触发 |
|--------|---------|
| `cwd_changed` 通知 | **只在 cwd 变化时**推 |
| `bindSessionCwd` | 会话显式绑定时 |
| `setCwd` | 用户主动切项目时 |

`initialize()` —— 也就是冷启动那一次 —— **一个都没碰**。

所以每次重启桌面端，`store.cwd` 都是 `null`，
哪怕 daemon 好好地跑在项目目录里。

### 1.2 daemon 其实知道

`AetherCodeMethods.listProjects`（`aethercode-protocol/.../AetherCodeMethods.java:2547`）返回：

```java
return Map.of("projects", List.of(current), "current", cwd);   // cwd = System.getProperty("user.dir")
```

`user.dir` 就是 daemon 被 spawn 进去的目录 = 桌面端持久化的「上次项目」。
**daemon 是「当前项目」的真相源**。

而渲染层把这一半扔了：

```ts
// methods.ts —— 连类型都没声明
listProjects(): Promise<{ projects: ProjectInfo[] }> { return this.call('listProjects'); }
```

```ts
// store/index.ts —— 拿到了 current，但没往 cwd 写
projects: projects.projects ?? [],
currentProjectId: projects.projects?.find((p) => p.active)?.id ?? null,
// cwd 呢？没有。
```

### 1.3 修法

```ts
// methods.ts
listProjects(): Promise<{ projects: ProjectInfo[]; current?: string | null }>

// store/index.ts
rpc.listProjects().catch(() => ({ projects: [], current: null })),   // fallback 保持同形
// …
cwd: projects.current ?? null,
```

> fallback 必须同形。`.catch(() => ({ projects: [] }))` 会让
> `projects` 变成 `ProjectInfo[] | { projects: never[]; current: null }`
> 的联合类型，`projects.current` 直接编译不过。
> **类型不齐 ⇒ 形状不齐 ⇒ 运行期是 undefined 而不是 null。**

---

## 2. 缺陷 B：「＋」按钮挂在由会话派生的分组里 —— 这才是「没法建 session」的真凶

修好 A 之后，左栏会显示项目分组了。但**如果一个会话都没有，分组还是不存在**。

`ProjectGroupList` 的分组是这么来的：

```ts
const groups = new Map<string | null, SessionInfo[]>();
for (const s of sessions) {                    // ← 只看已有会话
  const key = s.cwd ?? null;
  groups.set(key, …);
}
```

而「创建 session」这个动作**只存在于分组头部的「＋」按钮里**
（`ProjectGroup.tsx:87-97`）。左栏顶部那个总按钮在早先一轮按用户要求删掉了
（用户原话：「project 和 session 之间没必要再加一层 sessions」）。

于是形成了一个鸡生蛋：

```
没有会话 → 没有分组 → 没有「＋」→ 建不出会话 → 还是没有会话
```

用户那次正好一个活跃会话都没有（他刚把自己的会话挪进了回收站），
所以按钮是**彻底消失**的。

### 2.1 修法：当前项目恒有分组

一个 session 不属于某个项目（桌面端本来就按 session 自己的 `cwd` 分组显示），
但**当前项目**是用户正站在的地方，它必须永远有一个分组：

```ts
const currentKey = currentCwd && currentCwd.trim() ? currentCwd : null;
if (currentKey && !groups.has(currentKey)) {
  groups.set(currentKey, []);        // 空分组：ProjectGroup 会渲染「这个项目下没有 session」
}
```

排序也改了：**当前项目排第一**。

```ts
if (a === currentKey) return -1;     // 当前项目即使 0 会话（recency=0）也要在最前
if (b === currentKey) return 1;
```

否则一个空分组的 `lastUsedAt` 是 0，会被排到最后 —— 用户还得往下滚才看得见那个按钮。

### 2.2 为什么不给顶部加回一个总按钮

用户上一轮明确说过不要「project 与 session 之间的 sessions 层」。
本轮**尊重那个决定**：动作仍然是「每项目一个 ＋」，
只是保证当前项目那个 ＋ 永远在。

### 2.3 接线

```tsx
// LeftPanel.tsx
<ProjectGroupList
  sessions={all}
  currentSessionId={currentSessionId}
  currentCwd={useStore((s) => s.cwd)}     // ← 新增
  onNewSession={onNewSessionInCwd}
  onSoftDelete={softDeleteSession}
/>
```

`ProjectGroupList` 直接读 store 也可以，但**走 prop** 更好测：
行为测试可以自己喂 `currentCwd`，不必去动全局 store。

---

## 3. 缺陷 C：会话库跟着项目走（`--sessions-dir` 解耦）

### 3.1 症状

daemon 的 `--sessions-dir` 一直是这么给的：

```rust
.arg("--sessions-dir")
.arg(cwd.join(".aethercode").join("sessions"))    // ← 跟着项目
```

所以「切项目」=「换一整个会话库」。
R391 部署时就撞上了：用户的会话库跟着 cwd 跑到了 `D:\tmp\abc_1`。

### 3.2 修法：钉死一个全局位置

```
%USERPROFILE%\.aethercode\sessions
```

钉死在 `desktop-state.json` 的 `sessionsDir` 键里，**第一次写入后不再变**：

```rust
fn default_sessions_dir() -> Option<PathBuf> {
    let home = std::env::var_os("USERPROFILE")
        .map(PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(PathBuf::from))?;
    Some(home.join(".aethercode").join("sessions"))
}

fn resolve_sessions_dir() -> Option<PathBuf> {
    let path = desktop_state_path()?;
    if let Some(d) = load_persisted_sessions_dir_from(&path) { return Some(d); }
    let d = default_sessions_dir()?;
    let _ = save_persisted_sessions_dir_to(&path, &d);
    Some(d)
}
```

`spawn_daemon` 多带一个 `sessions_dir` 参数，一路穿到
`spawn_daemon_on_port`（真正拼命令行的地方）：

```rust
.arg("--sessions-dir")
.arg(sessions_dir.to_string_lossy().to_string())
```

### 3.3 状态文件必须「合并写」

`desktop-state.json` 现在有两个键：`lastCwd` 与 `sessionsDir`。

**原来 `save_persisted_cwd_to` 是整体替换**：

```rust
std::fs::write(&path, serde_json::to_vec_pretty(&json!({ "lastCwd": cwd }))?)?;
//                                     ↑ sessionsDir 就这么没了
```

这就是 R390 在 Java 侧学到的同一条教训的 Rust 版：
**能被一次 NULL 覆盖删掉的持久事实，不算持久**。

现在两个写者都是合并：

```rust
let mut body: serde_json::Value = std::fs::read(path)
    .ok()
    .and_then(|b| serde_json::from_slice::<serde_json::Value>(&b).ok())
    .filter(|v| v.is_object())
    .unwrap_or_else(|| serde_json::json!({}));
body["lastCwd"] = serde_json::json!(cwd.to_string_lossy());
```

### 3.4 顺带修：`get_cwd` 报错了目录

```rust
async fn get_cwd(state: State<'_, AppState>) -> Result<Option<String>, String> {
    if let Some(c) = state.cwd.lock().await.clone() { … }
    // R392：persisted 优先于 current_dir
    if let Some(c) = state.persisted_cwd.lock().await.clone() { … }
    Ok(std::env::current_dir()…)
}
```

daemon 是**用 persisted cwd spawn 的**，进程自己的 `current_dir`
（exe 被双击时所在的目录）不是项目目录。
报出启动目录会让桌面端显示一个 daemon 根本没在用的目录。

---

## 4. 迁移：三个库，一份真相

动数据之前先把三个库都读了一遍：

| 库 | 行数 | 内容 |
|----|------|------|
| `C:\Users\maijun\.aethercode\sessions` | 1 | 一个 2026-10-29 的旧 `default`（cwd = 用户主目录），外加 legacy `large_tool_results/` |
| `D:\tmp\abc_1\.aethercode\sessions` | 6 | **活库**（当前 daemon 正在用），R391 从 RAG 拷来的 |
| `D:\work\workspace\idea\engine\RAG\.aethercode\sessions` | 6 | 上面那 6 个的原件 |

迁移脚本 `.aethercode/r392-migrate.mjs` 的顺序是**先备份、再拷贝、最后重指**：

1. 把全局库里已有的 `default.jsonl` + `sessions.db` 备份到 `sessions.bak-r392\`；
2. 把活库的 6 个 transcript + 索引 + 3 个 per-session 状态目录拷到全局位置；
3. 重指索引里的绝对路径：

   ```sql
   UPDATE sessions SET raw_path = replace(raw_path, ?, ?) WHERE raw_path LIKE ?%
   -- 'D:\tmp\abc_1\.aethercode\sessions' → 'C:\Users\maijun\.aethercode\sessions'
   ```

4. **验证每一行的 `raw_path` 在磁盘上都存在** —— 不存在就退出码 1。

不删任何东西：旧的项目侧库原地保留，全局库的 legacy 目录也原地保留。

### 4.1 一个读数陷阱

第一次导出索引时我查的是 `deleted_at`：

```js
deleted_at: r.deleted_at ?? r.deletedAt ?? null,     // ← 这两列都不存在
```

SQLite 对**不存在的列**返回 NULL，**对存在但为 NULL 的值**也返回 NULL ——
从外面看一模一样。于是第一版导出显示「一个都没删」，
而界面明明写着 `Trash (1)`。

实际列名是 **`trashed_at`**。先读 schema 再读行，顺序不能反。

---

## 5. 测试

| 层 | 文件 | 例数 | 覆盖 |
|----|------|------|------|
| 渲染层行为 | `src/components/__tests__/projectGroupR392.test.tsx` | 5 | **真渲染真点击**：0 会话时分组在不在、「＋」在不在、点了传的是不是当前项目的 cwd、当前项目排不排第一 |
| 渲染层契约 | `src/__tests__/cwdRestoreR392.test.ts` | 9 | wire 形状 / `initialize()` 恢复 / Rust 侧不跟着 cwd / 状态文件合并写 / LeftPanel→GroupList 的接线 |
| Rust | `src-tauri/src/lib.rs` `desktop_state_tests` | 4 | cwd 写不抹 sessionsDir / sessionsDir 写不抹 cwd / 钉死后不随项目变 / 默认位置在用户主目录下 |

### 5.1 变异验证

把「当前项目补全」那三行改成 `if (false && …)`：

```
5 tests | 3 failed | 2 passed
```

变红的是 3 条依赖该修复的；另 2 条是回归护栏（不随该改动变化，**理应**不红）。
恢复源码后 12/12 绿。

> 行为测试 + 源码 pin 两条腿都要有：
> 行为测试证明「按钮真的能点」，源码 pin 证明「LeftPanel 真的把 cwd 递过去了」。
> 少任何一条，另一种改法都能让其中一个悄悄失效。

### 5.2 实机验证（CDP 只读探针 + 真点一次「＋」）

```
daemon 命令行:
  java ... -jar .../resources/aethercode.jar --http-port 22888 \
       --sessions-dir C:\Users\maijun\.aethercode\sessions      ← 钉死的全局位置

listProjects → current="D:\tmp\abc_1"
get_cwd      → "D:\tmp\abc_1"                                   ← Rust 修复生效

左栏文本:
  CURRENT SESSION
  📂 D:\tmp\abc_1                                                 ← cwd 恢复了
  Active / Trash
  0 sessions
  ▾ abc_1  0  ＋                                                  ← 分组 + 按钮
  这个项目下没有 session

项目分组探针:
  abc_1 (count=0)  plusButton=PRESENT title="新建 session (cwd: D:\tmp\abc_1)"

点「＋」之后:
  picker UP; options=["普通","SDD 规范化","Workflow","取消"]      ← R331 模式选择器
  取消后: picker closed; groups=["abc_1(0)"]                     ← 无副作用
```

回收站里的 6 个会话在新位置全部完好（标题 / 预览 / 消息数 / cwd 都在）：

| id | 消息数 | cwd |
|----|--------|-----|
| `26ee7d8d…` | 482 | `D:\tmp\abc_1` |
| `61bfe997…` | 20232 | `D:\tmp\abc_1` |
| `fa8358f4…` | 2415 | — |
| `de8d1067…` | 867 | — |
| `b57984b3…` | 5380 | — |
| `default` | 5029 | `D:\tmp\abc_1` |

### 5.3 换项目重启：会话库不动（这是本轮的头号主张，必须实测）

只验证机制不够 —— 要**真的换一个项目重启一次**，看会话库会不会跟着走。

```
基线（项目 = D:\tmp\abc_1）
  lastCwd     = D:\tmp\abc_1
  sessionsDir = C:\Users\maijun\.aethercode\sessions
  回收站会话  = 6

把 lastCwd 改成 D:\work\workspace\idea\engine\AetherCode，重启桌面端
  daemon 命令行:
    --sessions-dir C:\Users\maijun\.aethercode\sessions      ← 纹丝不动
  listProjects.current = D:\work\workspace\idea\engine\AetherCode   ← 项目确实换了
  回收站会话  = 6                                          ← 一个没少
    26ee7d8d… msgs=482    cwd=D:\tmp\abc_1
    61bfe997… msgs=20232  cwd=D:\tmp\abc_1
    fa8358f4… msgs=2415   cwd=
    de8d1067… msgs=867    cwd=
    b57984b3… msgs=5380   cwd=
    default    msgs=5029   cwd=D:\work\workspace\idea\engine\AetherCode
  左栏:
    📂 D:\work\workspace\idea\engine\AetherCode
    ▾ AetherCode  0  ＋                                       ← 新项目照样有「＋」

把 lastCwd 改回 D:\tmp\abc_1，重启
  current = D:\tmp\abc_1     sessionsDir 不变     左栏 ▾ abc_1 0 ＋
```

**读法**：`cwd` 那一列是**每个会话自己的** cwd（会话属于它被创建时的项目），
所以切项目不该改它们；`default` 那行的 cwd 跟着 daemon 走，是 daemon 的行为。
**会话本身一个都没丢** —— 这正是 R391 部署时撞到的那个问题。

### 5.4 一个已知遗留（本轮未动）

启动时控制台有一条：

```
[store] pre_warm_daemon failed for D:\tmp: Command pre_warm_daemon not found
```

**这是既有的，不是本轮引入的。** `initialize()` 仍然会调
`preWarmCwd(suggestSibling(info.cwd))`（父目录 = `D:\tmp`），
而 R361 已经把 Rust 侧的 pre-warm 整个撤掉了 —— **store 里的调用是死代码**。
它无害（异常被 catch 住），但会在排查启动问题时误导人。
清理它值得单独一轮（顺带把那两行死代码删掉）。

> 确认它与本轮无关的依据：`suggestSibling(info.cwd || get().cwd)` 走的是
> `info.cwd`（daemon 回报的目录），**本轮改的是 `get().cwd`**；
> 旧构建里 `get().cwd` 是 null，`info.cwd` 一样是 `D:\tmp\abc_1` ⇒
> 父目录一样是 `D:\tmp` ⇒ 这条警告旧构建也有。
> 换项目重启后它又打了一次（父目录变成 `D:\work\workspace\idea\engine`）——
> **它在跟着父目录变，跟本轮的 cwd 恢复无关**。

---

## 6. 现在的答案

### 怎么建 session

- **左栏当前项目分组头部的「＋」** —— 必现。
  新建的会话自动绑定当前项目（cwd 来自 daemon 的 `listProjects.current`）。
- 也可以用命令面板（`Ctrl-K`）的「新建会话」。
- 首次启动若还没有任何项目，桌面端会弹目录选择器（`NEEDS_CWD` 状态）。

### 怎么建「任务」

**不用建。** 任务（sub-task）不是用户手动创建的：
模型用 `todo_write` 声明，界面渲染成子任务卡片（标题 = todo 的 `content`），
右侧 Plan 面板可以看全貌。

R391 修的就是这一层的归属：卡片不再全挤在第一个块里，
被移出计划的 todo 也不会再画成「skipped」。

---

## 7. 教训

**Lesson 1441 — 真相源在 daemon 侧，别在渲染层自己推导。**
`listProjects` 早就把 `current` 送上来了，渲染层把它扔了。
自己推导的「当前项目」必然漂移；**daemon 进程的工作目录**才是真相。

**Lesson 1442 — 状态文件要合并写，不要整体替换。**
两个键共用一个文件，一个写者整体替换就把另一个删了。
「能被一次 NULL 覆盖删掉的持久事实，不算持久」（R390 Java 侧的同一条）。

**Lesson 1443 — 会话库不该跟着项目走。**
session 不属于项目。把它挂在「上次打开的项目」下面，
等于把用户的上下文绑死在一次目录切换上。

**Lesson 1444 — 一个动作的入口挂在「由该动作的产物派生的容器」里，就是死锁。**
「＋ 新会话」在会话分组里，而分组由会话派生 ⇒
0 会话时按钮不存在 ⇒ 建不出第一个会话。
**入口必须挂在一个不依赖该动作结果的地方。**

**Lesson 1445 — 不存在的列和 NULL 值，从外面看一模一样。**
查 SQLite 前先读 schema。第一版导出查 `deleted_at`（不存在），
读出来「一个都没删」，而库里明明有 6 个 `trashed_at` 非空。

**Lesson 1446 — 变红的集合要按「测试方法」数，而且要分清「该红的」与「理应不红的」。**
变异掉「当前项目补全」：5 条里红 3 条，另 2 条是回归护栏。
护栏不红不是漏洞，是护栏的定义。
