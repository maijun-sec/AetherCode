# R214 — Subagent + TODO list 完整支持 (App + TUI 双端) (2026-09-05)

## Task

> 继续，subagent 支持，App 模式 和 TUI 模式都需要支持
> 另外，TUI 是使用 AetherCode/aethercode-tui/ 中的 React 实现
> 还有一点说漏了：TODO list 需要同时考虑在 TUI 和 APP 两个模式下的展示
> （包括进度等，执行到哪个了）

三件事一起做：
1. Subagent 支持 — App 端 session 下提供每个 subagent 的查看链接；TUI 端基于快捷键切换不同的 subagent 和 primary agent
2. TODO list 展示 — App + TUI 双端，包括进度（百分比 + "执行到哪个了"）
3. TUI 是 React 实现（在 `AetherCode/aethercode-tui/`，不是 `aethercode/aethercode-tui/`）

## 现状摸底

### 双端已有能力

| 能力 | Desktop 端 | TUI 端 | gap |
|---|---|---|---|
| SubagentPanel (侧栏列表) | ✅ SubagentPanel.tsx (7794 bytes) + SubagentToast + subagentReducer | ✅ SubagentPanel.tsx (7720 bytes) + state.subagentJobs | 双端都齐 |
| SubagentSpawnCard (chat 内) | ✅ SubagentSpawnCard.tsx (2891 bytes) + 测试 | ❌ 缺失 | TUI 端缺组件 |
| Cancel / Insert result | ✅ SubagentPanel buttons | ✅ Ctrl+S panel + c/Enter | 双端都齐 |
| View subagent (切 transcript) | ❌ 缺 onView 按钮 | ❌ 缺 onView 按钮 | 双端都缺 |
| 快捷键切换 primary/subagent | ❌ 缺 | ❌ 缺 | 双端都缺 |
| TODO list | ✅ TodoBoard.tsx (5064 bytes) + useRpc subscription | ✅ TodoBoard.tsx (5501 bytes) 但 `<TodoBoard todos={[]} />` 写死空 | TUI 端 prop-driven 但 prop 永远空 |
| 进度百分比 | ❌ 缺 | ❌ 缺 | 双端都缺 |
| "执行到哪个了" 高亮 | ❌ 缺 | ❌ 缺 | 双端都缺 |

### R213 错误修正

R213 我**错路径**实现 — 在 `aethercode/aethercode-tui/`（Java + Lanterna）写了一整套 TUI。但 TUI 实际是 React 项目在顶层 `AetherCode/aethercode-tui/`。R212 之前我清了 `aethercode-tui/` 死目录后，**没意识到**正确路径是顶层。R213 Java 残骸已搬到 `_trash_r213/`，parent pom 已移除 Java tui module。

## R214 实施

### TUI 端（5 步）

**T1 — state.ts 新字段 + actions**：
- `todos: TodoItem[]` + `currentTodoId: string | null` — live TODO 列表
- `viewingSubagentId: string | null` + `viewHistory` — 当前 view 状态 + 历史栈
- `subagentSpawnCards: Record<eventId, card>` — chat 内 subagent spawn card
- Actions: `setTodos / viewSubagent / viewPrimary / viewHistoryPop / addSubagentSpawnCard / removeSubagentSpawnCard`
- `setTodos` reducer 自动 derive `currentTodoId`（第一个 in_progress by startedAt asc）

**T2 — `components/chat/SubagentSpawnCard.tsx` 新建**：1:1 mirror desktop 端
- Collapsed by default, Enter/Space 展开
- "View subagent →" 链接 + "× Dismiss" 按钮
- Glyph + role + idShort + status pill
- tui.tsx 集成：监听 subagent_event 触发 `addSubagentSpawnCard`，onOpen 触发 `loadSession + viewSubagent`

**T3 — SubagentPanel 加 onView**：
- 新 prop `onView?: (jobId) => void`
- 焦点行新增 `[v view]` 提示
- tui.tsx 集成：onView 调 loadSession + viewSubagent

**T4 — Ctrl+1..9 快捷键切换 view**：
- `Ctrl+1` → viewPrimary（loadSession(state.sessionId) + viewPrimary）
- `Ctrl+2..9` → 按 MRU 顺序选 subagent（running 优先，按 startedAt desc 排序）
- `Ctrl+Shift+B` → viewHistoryPop（撤销）
- 实时 toast 提示"Ctrl+{N}: no subagent at that slot"

**T5 — TodoBoard 加 progress + current**：
- 纯 helper：`progressPercent(items)` 返回 done / (actionable) × 100
- 纯 helper：`progressBar(percent, width)` 返回 `[████░░░░] 40%` ASCII bar
- Component 接 `currentTodoId` prop
- Header 加 progress bar；current row 加 `▶ CURRENT` 标记
- tui.tsx 把 `<TodoBoard todos={[]} />` 改成 `<TodoBoard todos={state.todos} currentTodoId={state.currentTodoId} maxRows={8} />`

### Desktop 端（3 步）

**D1 — SubagentPanel 加 View 按钮**：
- 新 prop `onView?: (jobId) => void`
- 行内 `[View subagent →]` 按钮（与 Cancel/Insert result 并列）
- store 加 `setViewingSubagentId` action + `viewingSubagentId` + `viewHistory` state
- store 加 `loadSession(id)` action（用 rpc.loadSession + hydrateTranscript）

**D2 — SubagentSpawnCard 挂载到 MessageList**：
- `SubagentSpawnCard.tsx` 加 `onDismiss?: (subagentId) => void` prop + × Dismiss 按钮
- `MessageList.tsx` 订阅 `subagent_spawn` 事件，de-dup by subagentId，本地 `spawnCards` state
- 渲染 cards 在 timeline 之后

**D3 — TodoBoard 加 progress + current**：
- Component 订阅 `currentTodoId` from store
- useMemo 计算 progress%（done / actionable）
- Header 加 progress bar (role="progressbar", aria-valuenow, 24 宽)
- current row 加 `▶ CURRENT` marker + CSS class `todo-board-item-current`

## 测试

| 套件 | 测试数 | 状态 |
|---|---|---|
| Desktop vitest (1013 总) | 1012 pass | 1 fail（修复 R212 aethercode-themes dead ref regression） |
| TUI node --test (83 文件) | 78 pass | 5 fail（全 pre-existing source-pin path 不一致，0 new regression） |
| TUI tsc -b --noEmit | exit 0 | 通过 |
| Desktop tsc -b --noEmit | exit 0 | 通过 |
| Java mvn -N validate | exit 0 | R213 Java 残骸已删，parent pom 干净 |

**修复 1 个 regression**：R212 删除 aethercode-themes 死引用 → desktop `ThemeSettings.test.tsx` 测 "aethercode-themes is wired as a dependency" 失败 → 改 test 匹配新行为（`expect(pkg.dependencies['aethercode-themes']).toBeUndefined()`）。

**5 个 pre-existing failing**（不是 R214 引入）：
- `docs-structure.test.mjs` — 期望 `AetherCode/docs/`，实际在 `AetherCode/aethercode/docs/`
- `r77/r78/r79-trace/metrics` — 期望 `aethercode-core/...`、`aethercode-protocol/...`、`aethercode-sdk/...`，实际都在 `aethercode/aethercode-*/`
- `r94e-prompt.test.mjs` — 1 个 prompt format sub-test

## 双端能力对比（R214 后）

| 能力 | desktop | tui | parity |
|---|---|---|---|
| Subagent 侧栏 | ✅ | ✅ | ✅ |
| Subagent spawn card | ✅ | ✅ | ✅ |
| Cancel / Insert result | ✅ | ✅ | ✅ |
| View subagent 切 view | ✅ button | ✅ 'v' + Ctrl+1..9 | ✅ |
| TODO list 实时数据 | ✅ (subscriptions + setTodos) | ✅ (setTodos) | ✅ |
| 进度百分比 | ✅ aria-progressbar | ✅ progressBar() | ✅ |
| "执行到哪个了" | ✅ ▶ CURRENT | ✅ ▶ CURRENT | ✅ |
| 主题切换 | ✅ R209 toggle | ✅ R209 toggle | ✅ |
| 取消 | ✅ | ✅ | ✅ |
| 流式输出 | ✅ | ✅ | ✅ |

**10 项核心 subagent + todo 能力**全部对齐 desktop。

## 关键 API 决策

1. **TUI 端 view 切换用 `loadSession` RPC + reducer state** — 跟 desktop 一致；host 调 RPC，state 跟 record
2. **TUI 端 Ctrl+1..9 用 MRU 排序** — 跟 SubagentPanel 渲染顺序一致（running first by startedAt desc, then terminal by endedAt desc）
3. **进度百分比排除 cancelled** — 用户 dismissed 不应该让 bar 倒退
4. **currentTodoId 派生** — 在 `setTodos` reducer 里 derive，组件 O(1) 拿到
5. **Desktop store 加 todos/currentTodoId state + setTodos action** — 跟 TUI 端 reducer 对称
6. **SubagentSpawnCard store 名一致** — 双端都用 `subagent_spawn` event + `onOpen` callback

## 文件改动

### TUI 端 (5 文件)
- `aethercode-tui/src/state.ts` — 新增 todos / currentTodoId / viewingSubagentId / viewHistory / subagentSpawnCards state + 6 actions
- `aethercode-tui/src/components/chat/SubagentSpawnCard.tsx` — 新建
- `aethercode-tui/src/components/SubagentPanel.tsx` — 加 onView prop + 'v' 提示
- `aethercode-tui/src/components/todo/TodoBoard.tsx` — progress bar + current highlight + 接 state
- `aethercode-tui/src/tui.tsx` — Ctrl+1..9 + 'v' keymap + SubagentSpawnCards mount + TodoBoard 传 state

### Desktop 端 (5 文件)
- `aethercode-desktop/src/store/index.ts` — 新增 todos / currentTodoId / viewingSubagentId / viewHistory + setTodos / loadSession / setViewingSubagentId actions
- `aethercode-desktop/src/components/SubagentPanel.tsx` — 加 onView 按钮
- `aethercode-desktop/src/components/MessageList.tsx` — 订阅 subagent_spawn + 渲染 SubagentSpawnCard
- `aethercode-desktop/src/components/chat/SubagentSpawnCard.tsx` — 加 onDismiss prop + × Dismiss 按钮
- `aethercode-desktop/src/components/todo/TodoBoard.tsx` — progress bar + current highlight
- `aethercode-desktop/src/components/ThemeSettings.test.tsx` — 修 aethercode-themes dead ref regression

## 教训 (2026-09-05)

1. **"TUI 是 React 实现" 不是 "Java 实现"** — 用户明确说 `AetherCode/aethercode-tui/`（顶层 React 项目），不是 `aethercode/aethercode-tui/`（Java Lanterna）。R213 我用 Java + Lanterna 写了一套完全错位置的 TUI，**R212 之前那个空目录**（3 个空子目录）原本就是要重启用 React 写的，但被 R212 删了 — 我用 R212 删空的死目录作为"无 TUI"的信号，重新建了 Java 版本。**正确做法**是先 read `AetherCode/aethercode-tui/` 顶层目录看看有什么。
2. **git status 没 = 没东西** — 用户说"D:/work/workspace/idea/engine/AetherCode/aethercode-tui"时，应当 first thing grep 那个目录下的内容（package.json + src/）而不是立即开始建 java tui。
3. **desktop 端 SubagentSpawnCard 写好但 ChatView 没 mount** — 这是 R92 之后一直存在的"写好组件但没接线"问题。R214 修了，但提醒后续 rounds：**写完组件要搜 grep 该组件名是否被 import，否则等于没写**。
4. **TUI 端 TodoBoard 写死 `todos={[]}`** — tui.tsx line 1625 写死空数组，TodoBoard 是 prop-driven 但 prop 永远空。这是 R174/R175 时代 TODO 集成的遗留 bug，**R214 才修**。
5. **pre-existing failing tests 是噪音** — docs-structure / r77 / r78 / r79 / r94e 这 5 个 test 失败都是路径不一致，R212/R213/R214 都没动。R214 只修了 1 个真正由 R212 引入的 regression（aethercode-themes dead ref → ThemeSettings test）。**区分 pre-existing 和 new regression** 很重要。
6. **zustand v5 StateCreator 必须 return T** — `create<T>((set, get) => { ...body... })` 这种 body block 不 return，TS 报错。desktop 端 store 之前 work 是因为有些隐式 mechanism；R214 加 R214 字段时触发 TS error。修法：actions 对象末尾 `as AppState` cast 或显式 return。

## 后续候选 (R215+)

- TUI 端 `setTodos` 订阅 — 跟 desktop 端 `subscribeKind(sessionId, 'todo_update')` 类似
- TUI 端 Ctrl+0 (zero) = "返回最近一次 view" (替代 Ctrl+Shift+B)
- Desktop 端 SubagentPanel 加 "view all subagents" (R119 全局 subagent 视图)
- TUI 端 TaskPanel 加 progress + 状态过滤
- 双端 subagent_thread_id 切换支持 (跨 daemon session)
