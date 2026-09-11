# R224 — Lazy Session Create + Tool Loop 调查 (2026-09-05)

> 用户原话:
> "两个问题：
> 1、现在 tool 调用还是大量失败，没有执行的命令；
> 2、在已经开始执行时，仍然显示"新会话"，我给你个推荐建议：在还没有输入 prompt 时，不要创建这个 session，此时左侧不显示这个 session，输入 prompt，或者 选择 cwd 时，只是在前端预填信息，等点了 enter，或者"提交"后，同时将 cwd 和 prompt 传送到后端，创建 session，并且生成 session 的名字（就是之前显示 preview 信息），然后在前端展示这个 session，然后继续执行任务。"

## 根因诊断

### 问题 1 — Tool call 大量失败

**不是 UI bug，是 model 端问题。** 翻 session jsonl log 看到 model 的 `<think>` 块：

```
"text": "<think>The error says `command` is required and I'm somehow
not passing it correctly. Let me look at my call format. Oh I see —
I need to include the command parameter in the XML structure. Let
me retry with the actual command.</think>",
"tool_use": { "name": "bash", "input": {} }
```

Model 在 "空转" — 知道要调 `bash`（name 填对了），但 `input` 一直空。

**根因追溯**: model 收到的 system prompt 用 Mavis 的 `tool_call` + `<invoke>` XML 格式举例，但 AetherCode 引擎不识别这个格式。Model 按 Mavis 格式输出参数（裸 tag），XML parser 解析失败，结果 `input: {}`。

**修法不在 R224 范围**:
- 需要改 system prompt，告诉 model 用 AetherCode 自己的工具格式
- 或者在 daemon 加 Mavis 格式 → AetherCode 格式的 parser
- 或者在 prompt 训练时把工具调用格式钉死
- 都不是一轮 R 能改干净的

**用户层 workaround**: 看到连续空 tool call 直接点 "停止" (cancel)，让 model 退到正常状态。session 列表里这类 session 可以直接删除。

### 问题 2 — "新会话" 还在出现

按用户建议的 lazy create 方案实现：

| 状态 | pre-R224 | post-R224 |
|------|----------|-----------|
| 打开 app | 自动创建 "新会话" session，列在左侧 | 不创建任何 session |
| 选 cwd | 触发 `setCwd` → swap daemon → 创建新 session | 只更新 store.cwd，不创建 session |
| 敲 prompt | 在 "新会话" 里输入 | 在 currentInput 里，不创建 session |
| 点 Enter | 给当前 session 发 query | **如果没 active session → lazy create: 同时传 cwd + firstPrompt 200 字 → daemon 创建 session 存 firstPrompt → query** |
| 左侧显示 | "新会话" + "新会话" placeholder | 真实 session，title 是 prompt 头 10 字 |

## 修改

### D1 — Daemon `AetherCodeMethods.createSession` 接 `firstPrompt`

```java
// R224: optional firstPrompt
String firstPrompt = null;
if (p.get("firstPrompt") instanceof String s && !s.isBlank()) {
    firstPrompt = s.length() > 200 ? s.substring(0, 200) : s;
}
// ...
memoryStore.sessionStore().upsertSession(
        newId, resolvedCwd, firstPrompt);  // 原来是 null
```

`upsertSession` 早就有 `firstPrompt` 这个 column (session_info 表)，只是 wire 上没传。R224 接上。

### D2 — Daemon `AetherCodeMethods.listSessions` 用 firstPrompt 作 preview fallback

```java
if (withPreview) {
    String preview = extractSessionPreview(info);  // 从 transcript 读第一行 user message
    if ((preview == null || preview.isBlank()) && memoryStore != null) {
        var msRow = memoryStore.sessionStore().loadSession(info.id());
        if (msRow.isPresent() && msRow.get().firstPrompt() != null) {
            preview = msRow.get().firstPrompt();
        }
    }
    m.put("preview", preview == null ? "" : preview);
}
```

Lazy create 时 transcript 还是空文件，`extractSessionPreview` 返空 → fall back 到 firstPrompt → LeftPanel 立刻有真实 title 显示。

### D3 — Frontend `methods.ts` `createSession` wrapper 接 firstPrompt

```ts
createSession(opts?: { cwd?: string; worktree?: string; firstPrompt?: string }): Promise<...> {
  return this.call('createSession', {
    cwd: opts?.cwd ?? null,
    worktree: opts?.worktree ?? null,
    firstPrompt: opts?.firstPrompt ?? null,
  });
}
```

### D4 — Frontend store 删 `defaultOpenBehavior='new'` 自动创建

`store/index.ts:3396-3413` 原本在 `initialize()` 末尾调 `rpc.createSession()` 自动 mint 一个 "新会话"。R224 整个 block 删掉，换成一行 `void get().defaultOpenBehavior;` 注释，**首次会话推迟到 sendMessage 时**。

### D5 — Frontend store `sendMessage` 加 lazy create

```ts
let sid = get().currentSessionId;

// R224: lazy create
if (!sid) {
    const behavior = get().defaultOpenBehavior;
    let daemonCurrent: string | null = null;
    try { const sess = await rpc.listSessions(); daemonCurrent = sess.current ?? null; } catch {}
    const wantFresh = behavior === 'new' || !daemonCurrent;
    if (wantFresh) {
        const r = await rpc.createSession({
            cwd: get().cwd ?? undefined,
            firstPrompt: input.slice(0, 200),  // daemon 端再 cap 一次
        });
        sid = r.sessionId;
        try { await rpc.loadSession(sid); } catch {}
        set({
            currentSessionId: sid,
            messages: [],
            currentInput: '',
            sessions: [
                ...get().sessions,
                {
                    id: sid, name: undefined,
                    preview: input.slice(0, 200),  // ⭐ 本地立刻有 preview
                    lastUsedAt: Date.now(), messageCount: 0,
                    cwd: r.cwd ?? get().cwd ?? undefined,
                },
            ],
        });
    } else {
        sid = daemonCurrent;
        set({ currentSessionId: sid });
        void get().hydrateTranscript(sid);
    }
}
```

### 协作链

```
user: 输入 prompt → 点 Enter
  ↓
store.sendMessage()
  ↓ currentSessionId is null
rpc.listSessions() 取 daemon 当前 session
  ↓
if (wantFresh):
    rpc.createSession({
        cwd: get().cwd,
        firstPrompt: input.slice(0, 200)
    })
    ↓
daemon: createSession RPC
    - 写 cwd 到 session_info
    - 写 firstPrompt 到 session_info (R224)
    - mint sessionId + 写空 transcript
    - return { sessionId, ... }
  ↓
store.set({ sessions: [...prev, newSession with preview] })
  ↓
rpc.query(input, { sessionId: newId })
  ↓
daemon: query RPC
    - 写 user message 到 transcript
    - extractSessionPreview 现在能从 transcript 读到 (R159)
  ↓
listSessions.refreshSessions() → LeftPanel 重新拉
    - preview 优先用 transcript 里的 user message (D2 fallback)
    - 如果 transcript 还没写入, fall back 到 session_info.first_prompt
  ↓
LeftPanel 显示新 session, title = preview 头 10 字
```

## 用户体验

- 打开 app → 左侧无 "新会话" placeholder
- 选 abc_4 cwd → store 记下，session 列表不变
- 敲 "请生成一个 java maven 项目" → currentInput 有内容
- 点 Enter → **侧栏立刻多出一行**，title 显示 "请生成一个 jav…" (10 字 + …)，后面 real-time stream tool call / 模型回复
- 旧 session (R222/R223 时代) 没 firstPrompt，仍显示 "新会话" (R223 fallback) — 这是预期的

## 验证

### TypeScript
```
npx tsc -b --noEmit
(no output — 0 errors, 0 warnings)
```

### desktop vitest
```
Test Files  81 passed (81)
     Tests  1013 passed (1013)
  Duration  147.87s
```

### Maven build
```
==> AetherCode build v0.2.1
==> Java tests passed
==> Packaging shaded CLI jar
    -> dist\aethercode-0.2.1.jar (53.01 MB)
==> Building the TypeScript TUI
    [memory] tsc build ...
    tsc + esbuild bundle ...
    -> dist\ac-tui\ac-tui.js (1.95 MB)
```

### Bundle 验证 (index-*.js)
```
firstPrompt in bundle: True
R224 reference in bundle: True
lazy createSection: True
200 char cap: True
```

### Tauri build
```
Finished `release` profile [optimized] target(s) in 8m 48s
Built application at: ...\aethercode-desktop.exe
```

### Smoke test (4/4 pass)
- `java -jar aethercode-0.2.1.jar --version` → `aethercode 0.2.0` ✓
- `java -jar aethercode-0.2.1.jar tui --help` → usage ✓
- `ac-tui-standalone.exe --version` → `ac-tui v0.2.1` ✓
- `aethercode-desktop.exe` PE header → `4D5A` (PE) ✓

## R224 产物 (release/aethercode-0.2.1/)

| File | Size (bytes) | SHA256 |
|------|--------------|--------|
| `aethercode-0.2.1.jar` | 55,583,901 | `c39ad64b6a83e3c26489477570c85371c7bf7ca760608ab828a85d9450436ae2` ⬅ R224 NEW (daemon 接 firstPrompt) |
| `ac-tui/ac-tui.js` | 2,042,768 | `bbe6a19d3590eea3eab8c3bf6c9fbb247a29d5ea10bf7d178d328b7f35059bc9` (unchanged) |
| `ac-tui-standalone.exe` | 100,106,240 | `50538ab48274879c87c43b7b3d6772cc98101d6cc0ac7ea737be4132ed784541` (unchanged) |
| `desktop/aethercode-desktop.exe` | **3,975,168** | `100fc432aafdc12604d25716b0ff18d83618153fdbbd72fb22745038a649171b` ⬅ R224 NEW (lazy create) |
| `aethercode-0.2.1.zip` | **94,013,656** (89.66 MB) | `58d1bb2012e72735890b53926ccd4a02c7042a92386c36c461250aa0dad67641` ⬅ R224 NEW |

## 教训 (2026-09-05)

1. **Lazy create 是更好的默认行为** — "新会话" placeholder 在用户没输入 prompt 时就出现是 UX 反模式。Pre-R224 的 `defaultOpenBehavior='new'` 自动创建 session 让用户被迫在"无内容的 session"里输入。R224 把"创建 session"绑到"提交 prompt"这个明确动作上。
2. **名字 (name / firstPrompt / preview) 应该在 create 时就固化** — 等 transcript 写入再 back-fill 是被动方案。R224 让前端把 prompt 头 200 字直接传给 daemon，daemon 写进 session_info，表里就有真实 title，listSessions 立刻能读到。
3. **空 tool call 是 model 训练 / prompt 问题** — 不是 UI bug。Model 知道 tool name 但不填参数 = 它**不会**正确的 tool call XML/JSON 格式。这是 system prompt 和 model 训练需要修的。短期 UI 没法根除，只能靠 model 训练 / prompt 调整 / 换更稳的 model。
4. **fallback chain 让系统更稳** — `extractSessionPreview` 返空时回退到 `session_info.first_prompt`；sessionLabel 名字 chain 优先用 preview，没有再回退到 firstPrompt 的截断，最后才是 "新会话"。每一层都让"最常见的失败"被无感吸收。
5. **R 系列 round 之间 source change 要立刻 re-grep** — R222 修了 4 个老问题，R222 后用户报告 landing page 还有 `.minimax` (Welcome.tsx hard-coded text)；R223 修了 4 个 UX 问题，R223 后用户报告 tool call 还失败 + lazy session。**每一轮用户新反馈都是上一轮 grep 没扫到的 corner case**。

## 后续候选 (R225+)

1. **修 model tool call 格式问题** — system prompt 加 AetherCode 自己的工具调用示例 (XML 格式 + 转义规则)，或者在 daemon 加 Mavis 格式 → AetherCode 格式的 parser。这个能彻底修问题 1
2. **fold 连续空 tool call** — UI 层把 N 个连续 `X bash (missing command)` 折叠成一条 "8 个连续 tool call 失败 (model 没填参数)"，噪音减少
3. **daemon 端 LLM 生成 10 字 session 摘要** — R222 shortSummary + R224 firstPrompt 的组合：前端传 200 字 prompt，daemon 用 LLM 抽 10 字存 firstPrompt，LeftPanel 直接显示 LLM 摘要
4. **bump 版本号** — `build.ps1` / `package.ps1` 写死 0.2.1，实际是 R220+10 轮 polish
5. **desktop 同步 R215-R220 markdown polish** — desktop 用 react-markdown 已经有 visual，但没和 TUI 端 12 theme token 对齐
6. **修 `package.ps1` Stage 4 tauri splat bug** — `@tauriArgs` 改成 `string[]` 不用 splat
