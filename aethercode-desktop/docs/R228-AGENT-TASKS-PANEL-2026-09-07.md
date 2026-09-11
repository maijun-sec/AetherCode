# R228 — RightPanel "Plan" tab + AgentTasksPanel (实时多层 TODO list)

**日期**: 2026-09-07
**触发**: 用户希望 desktop 右边栏显示 TODO list，支持多层 (task → subtask)，并展示执行进度
**范围**: AetherCode desktop 端改动 (5 个 source file + 1 个 test file)
**wire path**: daemon `tool_use_start` event (toolName='todo_write', input.todos=[...]) → desktop store → AgentTasksPanel

## 0. 关键发现 (AetherCode 已经有的)

AetherCode 早就有了多层 TODO 的支持（R85 + R16B）：
- `aethercode-tools/.../TodoWriteTool.java` 的 wire shape 是 `{todos: [{content, status, active_form, subtasks: [{id, content, status, summary}]}, ...]}` — 完整支持 5 个 subtask status + 5 个 top-level status
- `AppState.setTodoList` 通知 listener（line 251）
- `AppState.onTodoUpdate` listener pattern（line 263）
- 已经有 `subTasks: ChatSubTask[]` state (R85) 跟踪 sub_task_start / sub_task_end events

**问题**：desktop 收到的 `tool_use_start` event 把 `input.todos` 通过 `humanizeToolInput` 简化成空字符串（todos 是 array of object，todos 不是 string），所以 RightPanel 看不到 todo list。

**已有但没接通的方案**：`TodoBoard` 组件（`src/components/todo/TodoBoard.tsx`）订阅 R200+ 的 `task/event kind=todo_update` 事件。**但 daemon 端没实现 emit**（`aethercode-acp/AgentServerACP.java` 有 `handleTodoUpdate` 但只用于 child event，主 agent 路径不 emit `task/event`）。所以 TodoBoard 在 desktop 是**空壳**。

R228 走 quick fix 路径 A：用现成的 `tool_use_start` event 拿完整 todos，**desktop 端独享**的方案，不改 daemon。

## 1. 实现

### 1.1 store 改动 (`src/store/index.ts`)

- 新增 `TodoItem` + `TodoSubTask` interface
- AppState 加 `currentTodos: TodoItem[]` 字段
- initial state: `currentTodos: []`
- `tool_use_start` case special-case `toolName === 'todo_write'`，把 `ev.input.todos` (含 subtasks) 解析成 TodoItem[]，存到 currentTodos
- `clearCurrentQuery` 加 `currentTodos: []` reset
- defensive parsing：typeof / Array.isArray guards，model emit garbage 时不 throw，panel 退化到空状态

### 1.2 AgentTasksPanel (`src/components/AgentTasksPanel.tsx` + `.css`)

- 新建组件
- 多层渲染：top-level task 卡片 + nested subtask 行
- 5 个 top-level status icon (○ ▶ ✓ ⊘ –) + 5 个 subtask status icon (○ ▶ ✓ ✗ –)
- 顶部 summary (n/m done · ▶ k running · ✗ k failed · ⊘ k cancelled)
- 进度条 (0-100%)
- "Currently working on" 高亮区 (activeForm 优先，否则 content)
- "Plan not started yet" 空状态
- 颜色：pending 灰, in_progress 蓝, completed 绿, failed 红, cancelled/skipped 灰+斜体
- subtask status **优先**用 R85 的 `subTasks: ChatSubTask[]` (runtime events)，fallback 到 model-declared status

### 1.3 RightPanel ("Plan" tab)

- RightPanel.tsx 加 'plan' 到 tab union
- 加 "Plan" tab button (在 Telemetry 和 Tasks 之间)
- 加 `tab === 'plan'` branch 渲染 `<AgentTasksPanel />`
- tab 顺序：Telemetry / **Plan** / Tasks / Subagents / Agents

### 1.4 测试 (`src/components/agentTasksPanelR228.test.ts`)

20 个 source-only assertion（跟项目约定一致 — R182/R193 都走 source-only）：
- 文件存在
- wire 路径完整（store import → AppState field → RightPanel tab → AgentTasksPanel selector）
- 5 个 status icon map 完整 (top-level + subtask)
- "Plan not started yet" empty state literal
- progress bar attribute
- subtask 嵌套渲染 literal
- todo_write parser 是 defensive
- subtask status override (R85 cross-reference)

## 2. 验证

| 项 | 结果 |
|---|---|
| tsc -b | 0 错 0 警告 ✓ |
| vitest R228 新加 20 tests | 20/20 ✓ |
| vitest 全量 | **1033/1033** ✓ (vs R225 1013/1013，无 regression) |
| vite build | 7.88s ✓ (vs R225 25.50s) |
| bundle verify | 5/5 markers (`agent-tasks-panel` `currentTodos` `Plan not started yet` `agent-tasks-progress` ...) ✓ |
| tauri build | 1m 21s ✓ (rust re-link only) |
| R225 jar markers in 0.2.52.jar | 3/3 ✓ (R228 不改 java，jar 跟 R227 内容相同) |

## 3. R228 产物

| 文件 | 大小 | SHA256 |
|---|---|---|
| `aethercode-0.2.52.jar` | 55,585,147 B | `3FFC5433BEC5F47DE16DF8905C10CCF633B9A2FA8B81A9FC4052C1BD05234A2A` (R225 unchanged, R228 jar=0.2.51 promoted to 0.2.52) |
| `desktop/aethercode-desktop.exe` | 3,980,800 B | **`E7BF13CF92856055CCE31F061304D77B536EC0024BB4B0A7D4FCF26FFBD2C8B8`** (R228 NEW, +5,632 B vs R227) |
| `desktop/resources/aethercode.jar` | 55,585,147 B | `3FFC5433...` (R225, embedded by tauri build) |
| `desktop/resources/icon.ico` | 20,545 B | `784B7E26...` |
| `ac-tui/ac-tui.js` | 2,042,768 B | `BBE6A19D...` (unchanged) |
| `ac-tui-standalone.exe` | 100,106,240 B | `50538AB4...` (unchanged) |
| `aethercode-0.2.52.zip` | 144,117,789 B (137.45 MB) | **`E79C8A79F48F624CEC4F5322A5841440005D7728D57FFA1DAAFE46F16194977C`** (R228 NEW) |

**完整路径**: `D:\work\workspace\idea\engine\AetherCode\release\aethercode-0.2.52\`

## 4. 教训 (2026-09-07)

1. **R200+ 的 `task/event kind=todo_update` 链路不完整** — desktop 端 TodoBoard 订阅了，但 daemon 端没 emit。R228 走 `tool_use_start` 事件快速绕开，是正确选择。**结论**：R229 可以补 daemon 端 emit，让 TodoBoard 真正 work（统一两条路径，删掉 R228 的 quick fix）。
2. **Source-only test pattern (R182/R193) 适合 wire 完整性验证** — 不实际 render，但覆盖 file 存在 + import 关系 + tab 字符串 + state field 名。20 个 assertion 跑 1.12s，覆盖率够用。
3. **AetherCode 已经有 TodoWriteTool + R85 subTask + R16B AppState 完整架构** — 缺的是 desktop 端"真的渲染"。R228 5 个 source file 改动就把这条链激活。
4. **R-series 出包 4 步流程** 现在跑得很顺：jar promote-jar.py R228 + tauri build + promote-r228-release.py + zip-r228.py。**R227 写的脚本都直接复用**。
5. **R228 没改 java** — 所以 jar 跟 R227 (0.2.51) 一样，只是文件名升到 0.2.52。SHA 一样 (`3FFC5433...`)。desktop exe 不一样 (`E7BF13CF...`)。
6. **PowerShell `npx tauri build` 进程监控技巧** — `task_query status=running` 不可靠，但 `(Get-Item exe).LastWriteTime` 配合 `Get-Process -Id` 检测实际状态。R228 tauri build 1m 21s（vs R226/R227 7-9 min），因为 desktop-only 改动，rust 只 re-link 没 full compile。

## 5. 后续候选 (R229+)

- **补 R200+ 的 `task/event kind=todo_update` 链路** — daemon 端从 `AppState.onTodoUpdate` listener emit 到 desktop，删 R228 quick fix，TodoBoard 真正 work
- **R228 AgentTasksPanel 跟 TodoBoard 合并** — 两个组件功能重复，合并成单一组件
- **`agent-tasks-panel` 跟 `task-list-view` 集成** — 让用户能 jump to step detail (currently a separate R110-5 modal)
