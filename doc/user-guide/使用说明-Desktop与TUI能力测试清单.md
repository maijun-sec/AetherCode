# AetherCode 使用说明 — Desktop & TUI 能力测试清单

> **目标**: 本文按"测试项"组织 AetherCode Desktop (`aethercode-desktop.exe`) 和
> TUI (`ac-tui-standalone.exe`) 在当前 release (`v0.2.57`) 下能实际验证的能力。
> 每条都告诉你**怎么触发 → 期望看到什么**,你可以勾选式逐项过。
>
> **基线版本**: 0.2.57 (R237 final)
> **适用范围**: 同一份 Java 后端 jar (`aethercode-0.2.57.jar`,
> SHA256 = `7B41A116981CD77355AFB34AA9110428E3203BF73285EEB95F454FE3DA65A82A`)
> 同时被 Desktop 和 TUI 加载使用,所有能力在两个 UI 下行为一致 (UI 表现层
> 略有差异,见每节"Desktop vs TUI"对照)。

---

## 0. 启动

### 0.1 Desktop (`aethercode-desktop.exe`)

```
D:\work\workspace\idea\engine\AetherCode\release\aethercode-0.2.57\desktop\aethercode-desktop.exe
```

启动后会:

1. 读取本机 `.aethercode/` 用户目录 (Windows 默认 `C:\Users\<you>\.aethercode\`)
2. 启动内置 daemon (jar 来自 `desktop/resources/aethercode.jar`)
3. WebView2 渲染 UI;本地 WS (`ws://localhost:<port>`) 推流
4. 浏览器开发者工具: `Ctrl+Shift+I` (已在 tauri.conf.json 启用 remote-debugging)

**预期窗口**: 1440×900, 标题 `AetherCode`, 左侧 Projects / Sessions
两 tab, 中间是空 Chat (Welcome 页), 右侧默认折叠 (Telemetry 面板关闭)。

### 0.2 TUI (`ac-tui-standalone.exe`)

Bun 单文件 (无 JVM 依赖):

```
D:\work\workspace\idea\engine\AetherCode\release\aethercode-0.2.57\ac-tui-standalone.exe
```

或 (如使用 npm + nvm + Node 18+):

```
node D:\work\workspace\idea\engine\AetherCode\release\aethercode-0.2.57\ac-tui\ac-tui.js
```

或最简单 (`run-tui.bat` / `run-tui.sh`):

```cmd
cd release\aethercode-0.2.57 && run-tui.bat
```

**预期**:

- Windows Terminal / ConPTY: 进入 Ink TUI (彩色框 + alt-buffer)
- cmd.exe / PowerShell ISE: 自动降级 line mode (行级)
- 也可强制: `ac-tui-standalone.exe --line` / `--tui`

**注意**: TUI 不会自己启动 daemon, 它会找 `aethercode-0.2.1.jar` (兼容旧名)
或 `aethercode-0.2.57.jar` (canonical), 或 `AETHERCODE_JAR` env 指定的 jar;
找不到时会**自动启动**最近一次的 canonical jar (marker-driven, 见 R227)。
如要手动启动 daemon, 跑:

```cmd
java -jar release\aethercode-0.2.57\aethercode-0.2.57.jar --daemon
```

### 0.3 同时跑两个 UI

Desktop 和 TUI 都用同一个 daemon, **可以同时开**;两边消息流几乎同步
(daemon 推的 WS / stdio 都会广播)。

---

## 1. Desktop 能力测试清单

### 1.1 顶部 (Header)

| ID | 能力 | 怎么测 | 期望 |
|----|------|--------|------|
| D-Header-1 | 项目名展示 | 启动后看 Header | 显示当前 cwd 项目名 |
| D-Header-2 | 模型切换 | Header → 模型下拉 | 列出所有 provider 的模型 (claude / glm / qwen / ...) |
| D-Header-3 | Tools & Permission | 顶栏 🔧 按钮 / `Ctrl+T` | 弹窗,列出可用工具 + 每个工具的安全等级 + 当前 permission 模式 |
| D-Header-4 | Telemetry 面板开关 | 顶栏图标 / `Ctrl+Shift+E` | 打开 / 关闭右侧 Telemetry tab |
| D-Header-5 | Settings 弹窗 | 顶栏 ⚙ 图标 | 弹出 SettingsPanel (见 §1.8) |
| D-Header-6 | ReconnectBanner | 启动后手动 kill daemon 再起 | 顶部黄底横条: "Reconnecting..." |

### 1.2 左侧栏 (LeftPanel)

| ID | 能力 | 怎么测 | 期望 |
|----|------|--------|------|
| D-Left-1 | Projects tab | 左侧点击 "Projects" | 列出所有已知 cwd (按路径分组) |
| D-Left-2 | 切换项目 (CWD) | Projects 里点别的路径 | 顶部 Header 项目名 + 当前 session 都跟着变 |
| D-Left-3 | Sessions tab | 左侧点击 "Sessions" | 列出当前 cwd 下的所有 session (含 id 尾 8 位) |
| D-Left-4 | 切换 session | 点列表里的 session | 消息流切换,右侧 Plan / Tasks tab 跟着新 session 走 |
| D-Left-5 | 新建 session | `Ctrl+Shift+N` 或 `/new` 命令 | mint 新 session id, 跳到空 chat |
| D-Left-6 | 删除 session | `/session delete <id 尾 8 位>` | 弹出确认后从列表移除 (不能删当前 session) |
| D-Left-7 | Trash 入口 | SettingsPage / TrashPage 路由 | 进入 TrashPage 看到已删 session (可恢复) |

### 1.3 中间 Chat 区 (MessageList + MessageInput)

| ID | 能力 | 怎么测 | 期望 |
|----|------|--------|------|
| D-Chat-1 | 发送消息 | 输入 "你好" → Enter | 立刻出现 user 卡片,几秒后 assistant 流式输出 |
| D-Chat-2 | Shift+Enter 换行 | 输入时按 `Shift+Enter` | 在输入框内插入换行,不发送 |
| D-Chat-3 | Markdown 渲染 | 让模型写一段带代码的回复 | ```js ... ``` 用 syntax highlight, 标题 / 列表 / 表格 / 链接都正确渲染 |
| D-Chat-4 | 流式输出 (text_delta) | 让模型写长回答 | 文字逐字浮出, 期间 ActivityIndicator 转动, 完成后 EndOfTaskPanel 弹出 (12s 自动消失) |
| D-Chat-5 | 取消流式 | 流式输出时按 Esc / 顶栏 X | 立刻停止, EndOfTaskPanel 显示 "cancelled" |
| D-Chat-6 | Tool call 卡片 | 让模型调用 file_read / shell 等 | 每个 tool 用卡片渲染, 状态图标 ○/▶/✓/✗, 折叠时只显 name, 展开看 args + result |
| D-Chat-7 | Plan card (R85) | 让模型用 plan / 多步 todo | 顶部出现 PlanCard, 列出 plan 步骤 + 实时勾选 |
| D-Chat-8 | File diff card | 让模型做 file_edit | 显示 before / after diff, 行号, + / - 高亮 |
| D-Chat-9 | Subagent spawn card | 让模型起子任务 | 显示子 agent 名字 + 入口 prompt + 完成状态 |
| D-Chat-10 | Slash 命令下拉 | 输入框输入 `/` | 弹出下拉,分类 (会话/上下文/模型/权限/工具/系统),filter + 上下选择 + Enter 执行 |
| D-Chat-11 | / 命令回退 | 输入框输入 `/xxx` 不存在 | 红色 status pill: "unknown command" |
| D-Chat-12 | 多行 / 跨行 query | 粘贴多行代码块 + Enter | 整段作为一条 query 发送, 不被 Enter 截断 |
| D-Chat-13 | Image input | 粘贴截图 / 拖图到输入框 | 图片作为 base64 发送给多模态模型 (用 MiniMax-M3 / claude-3 等) |
| D-Chat-14 | File mention | 输入 `@README.md` | 解析文件内容, 拼到 query 里 (大文件自动截断) |

### 1.4 右侧栏 (RightPanel, Tabs)

| Tab | 能力 | 怎么测 | 期望 |
|-----|------|--------|------|
| D-Right-Telemetry | 实时 token / cost 图 | 切到 Telemetry tab + 发查询 | ContextMeter + TokenUsage + TokenChart (R97) 实时更新 |
| D-Right-Plan | 多层 TODO 实时 (R228/R229) | 切到 Plan + 让模型跑 `todo_write` | 5 个状态图标 (○ ▶ ✓ ⊘ –), 进度条, "Currently working on" 高亮, daemon > wire 优先级 |
| D-Right-Tasks | Kanban 任务板 (R108-3) | 切到 Tasks | 看板, 拖动 reorder, 状态切换 |
| D-Right-Subagents | 后台子 agent 状态 (R92-B) | 切到 Subagents | badge 计数, 当前 running 子任务实时显示 |
| D-Right-Agents | Agent 注册表 (R109-2/3) | 切到 Agents | 列出所有 Mavis agent + 关联 model, 点行打开 AgentEditor (CRUD) |
| D-Right-Settings | 设置面板 (R115, R120, R122, R126) | 顶栏 ⚙ / 切到 Settings | 见 §1.8 Settings 弹窗 |
| D-Right-Workflows | Workflow 列表 | 切到 Workflows tab | 列出 .aethercode/workflows/ 下的 yaml, 增删改, 拖拽编辑器 |

### 1.5 底部 (StatusBar)

| ID | 能力 | 怎么测 | 期望 |
|----|------|--------|------|
| D-Status-1 | 连接状态 | 启动后看 status bar | 绿色 "connected · daemon :<port>" |
| D-Status-2 | 模型 / Session 标签 | 切模型 / 切 session | status bar 同步刷新 |
| D-Status-3 | Token / Cost 累计 | 跑几个查询 | "in=2.1k out=4.3k · $0.12" 实时累加 |
| D-Status-4 | Loop warning badge (R101, R114-C) | 故意让模型循环调同一 tool | 出现 ⚠ loop 徽章, 点击跳到 LoopGuardBanner |
| D-Status-5 | Auto-approve 计数 (R120/R126) | 开启 auto-approve-low 后跑 query | 出现 ✓ auto-allow: N 徽章, 点开 toggle 关闭 |
| D-Status-6 | Memory 监控 (R107-G) | 长跑 / 高并发 | 内存 75% 转琥珀色, 88% 转红色, badge 变 "throttled" |
| D-Status-7 | 紧凑模式 (R173) | 小窗口 / 点 "..." | 隐藏诊断徽章 (skip / pre-warm / subagent), 只留 4 个关键徽章 |

### 1.6 全局弹窗 / Banner

| ID | 能力 | 怎么测 | 期望 |
|----|------|--------|------|
| D-Modal-1 | ErrorBoundary (R180) | 让一个组件抛错 (dev 模式触发) | 红色兜底页, 不白屏 |
| D-Modal-2 | AwaitingDecisionBanner | 模型停下等 input | 顶部黄条 "Awaiting decision: ..." |
| D-Modal-3 | PermissionPromptBanner (R86/R88-C/R203/R207) | 跑会触发权限的 tool | 弹内联决策卡, 选项: allow / always / deny |
| D-Modal-4 | ConfirmDialog | 危险操作 (删 workflow / session) | 居中模态确认框 |
| D-Modal-5 | CommandPalette (Ctrl+P) | `Ctrl+P` | 命令面板, 模糊匹配 50+ 命令 |
| D-Modal-6 | SessionPickerModal (Ctrl+K) | `Ctrl+K` | 会话选择, 模糊搜索, 切到选中会话 |
| D-Modal-7 | LoopGuardBanner (R101) | 循环触发 | 内联 banner "重复调用 N 次", 按钮 "继续" / "停止" |
| D-Modal-8 | EndOfTaskPanel (R158) | query 结束 | 底部面板, 12s 自动消失, 列摘要 (steps / tokens / cost) |
| D-Modal-9 | WorkflowEditorModal (R102/R110-4) | `/workflow create` | YAML 编辑器, 实时解析, ● unsaved 标记, Cmd+S 存 |
| D-Modal-10 | StepDetailModal (R110-5) | WorkflowProgressBar 上点 step | 全事件历史, 折叠 raw payload, Copy raw 按钮 |
| D-Modal-11 | SubagentToast (R92) | 启子任务 | 右下吐司, 实时进度, 完成自消 |
| D-Modal-12 | ReconnectBanner | daemon 挂了 | 顶部黄条, daemon 复活后自动消失 |
| D-Modal-13 | AgentEditor (R173) | 切到 Agents tab → 点 agent | 编辑器: name / model / description / body, 存盘 RPC |

### 1.7 Memory Panel (R-MEM-1/2/3/4/5)

| ID | 能力 | 怎么测 | 期望 |
|----|------|--------|------|
| D-Mem-1 | 打开 Memory | `/memory` 或右侧切到 Memory | 三个 scope: USER / PROJECT / LOCAL |
| D-Mem-2 | 查看条目 | 点 scope 展开 | 列出所有 memory file (.md), mtime, size |
| D-Mem-3 | 编辑 | 点文件 → textarea | 改完点 "保存", 走 `memory/writeFile` RPC |
| D-Mem-4 | 新建条目 | 点 "+ New" | 用时间戳命名的 .md 文件 |
| D-Mem-5 | 向量检索 (R-MEM-1) | 在 Memory 搜索框输入关键词 | 走 `memory/find` RPC, 列出 top-K 相似条目 (F1 frontier) |
| D-Mem-6 | 项目 memory 统计 (R-MEM-2) | Memory 详情页底部 | 走 `memory/stats`: total / expired / consolidated / active |
| D-Mem-7 | 手动 compact (R-MEM-2) | Memory → "Compact" 按钮 | 走 `memory/consolidate` 把近重复合并, Jaccard 算法 |
| D-Mem-8 | 子 agent 共享 memory (R-MEM-3) | 让子 agent 写 memory, 主 agent 查 | `memory/readTeamMemory` 能读到子 agent 写的 team-shared 条目 |
| D-Mem-9 | Skill memory (R-MEM-4) | 装一个 skill 跑几次 | `memory/findSkill` 按 (signature+description+tags) 匹配, success_count 排序 |

### 1.8 Settings 弹窗 (R115/R120/R122/R126/R155/R160)

| ID | 能力 | 怎么测 | 期望 |
|----|------|--------|------|
| D-Settings-1 | Permission mode | 下拉选 Default/Accept Edits/Bypass/Plan | 走 `setPermissionMode` RPC, 输入栏徽章跟着变 |
| D-Settings-2 | Provider 切换 (R109-1) | provider 下拉 | 走 `switchProvider` RPC, 模型列表刷新 |
| D-Settings-3 | Concurrency profile | Low / Normal / High | 影响 (queries, tools, branches) semaphore |
| D-Settings-4 | Loop detector 阈值 | 拖动 slider | 调小 → 更敏感 (R101 早触发) |
| D-Settings-5 | Auto-restart supervisor (R155) | toggle | daemon 挂了自动重启 |
| D-Settings-6 | Reset daemon (R160) | 点 "Reset daemon" | 强杀, 重新 spawn |
| D-Settings-7 | Memory profile | 4 档 (low/med/high/safety) | 影响 memory 写入策略 |
| D-Settings-8 | Theme 切换 (R209) | light / dark | 立即生效 |
| D-Settings-9 | Auto-approve 低风险 (R120) | toggle on | StatusBar 出现 ✓ auto-allow: N 计数 |
| D-Settings-10 | Auto-approve 中高风险 (R126) | toggle on | StatusBar 出现 ⚠ auto-allow high: N 计数 |
| D-Settings-11 | Loop warn 阈值 (R101) | slider 0~20 | 调 0 → 不弹 LoopGuardBanner |

### 1.9 Desktop slash 命令完整清单 (R95/R100)

通过输入 `/` 触发下拉,按分类:

**会话** (5)

| 命令 | 行为 |
|------|------|
| `/clear` | 清当前子任务列表 |
| `/new` | 新建 session (`Ctrl+Shift+N`) |
| `/session delete <id 尾 8 位>` | 删一个 session |
| `/sessions` | 打开会话选择 (Ctrl+K) |
| `/cwd <path>` | 切工作目录 |

**上下文** (3)

| 命令 | 行为 |
|------|------|
| `/compact` | 提示由引擎自动压缩 |
| `/memory` | 聚焦 Memory 面板 |
| `/context` | 显示 token 用量 |

**模型** (1)

| 命令 | 行为 |
|------|------|
| `/model <name>` | 切模型 |

**权限** (1)

| 命令 | 行为 |
|------|------|
| `/permission <mode>` | 切 4 档 permission |

**工具** (8)

| 命令 | 行为 |
|------|------|
| `/help` | 打印所有命令 |
| `/status` | 连接 / session / model / perm / streaming 一行 |
| `/workflow list` | 刷新并展示 workflow |
| `/workflow create` | 打开编辑器 (新建) |
| `/workflow modify <name>` | 打开编辑器 (改) |
| `/workflow delete <name>` | 删 (二次确认) |
| `/skill add <url-or-path>` | 装 skill 到 `~/.aethercode/skills/` |

> 用户也可以用 R93 模板 (R100): 在 prompt 历史里给某条 prompt 加 `slashName`,
> 该 prompt 就自动变成 `/<slashName>` 命令, 出现在下拉的"系统"分类里。

### 1.10 Desktop RPC 能力 (R-205/R-206/R-207 之后)

桌面可以直连 daemon 的 JSON-RPC, 当前稳定的 RPC 方法集
(节选自 `aethercode-desktop/src/rpc/types.ts`):

```
session/{show,spawn,resume,delete,restore,trash,events,rename,tokens}
task/{spawn,resume,pause,kill,attach,events,list,setLimits,attached}
grants/{list,revoke,clear,setPreset}
model/{list,get,set}
workflow/{list,show,run,upsert,delete}
compact/{status,run}
```

> 完整 RPC 列表见 `aethercode-protocol/.../AetherCodeMethods.java`,
> 包括 `memory/{get,append,list,find,consolidate,forget,stats,shareToSubagent,
> readTeamMemory,promoteFromSubagent,upsertSkill,recordSkillOutcome,findSkill}`
> 等等。

---

## 2. TUI 能力测试清单

### 2.1 启动模式

| ID | 能力 | 怎么测 | 期望 |
|----|------|--------|------|
| T-Mode-1 | Ink TUI (默认) | 在 Windows Terminal 跑 `ac-tui-standalone.exe` | alt-buffer + 彩色框 + 顶部 Header / 底部 StatusBar |
| T-Mode-2 | Line mode | 加 `--line` | 简单行级, 适合 cmd.exe / CI / piped |
| T-Mode-3 | Print mode (one-shot) | `ac-tui-standalone.exe --print "2+2?"` | 不进入交互, 直接打到 stdout, 然后退出 |
| T-Mode-4 | 强制 Ink | `ac-tui-standalone.exe --tui` | 即使在非 TTY 宿主也尝试 Ink |
| T-Mode-5 | 指定 jar | `--jar <path>` 或 `AETHERCODE_JAR=<path>` | 走指定 jar 启 daemon |
| T-Mode-6 | 强制不染色 | `--no-color` | 纯文本, 适合 log 抓取 |

### 2.2 布局 (Ink TUI)

```
┌────────────────────────────────────────────────────────┐
│ Header · AetherCode · model · mode · conn · session    │  1 行
├────────────────────────────────────────────────────────┤
│                                                        │
│  Scrollback (assistant / user / tool / plan)           │  flex
│                                                        │
├────────────────────────────────────────────────────────┤
│ ▸ input                                                │  1 行
├────────────────────────────────────────────────────────┤
│ StatusBar · tokens · cost · mode · jar                 │  1 行
└────────────────────────────────────────────────────────┘
```

### 2.3 快捷键 (Ink TUI)

| ID | 能力 | 怎么测 | 期望 |
|----|------|--------|------|
| T-Key-1 | Enter 发送 | 输入 + Enter | 提交 query |
| T-Key-2 | Shift+Enter 换行 | 输入时 Shift+Enter | 输入框内换行 |
| T-Key-3 | ↑ / ↓ 历史 | 按上下 | 翻 200 条最近 prompt |
| T-Key-4 | Ctrl-R 反向搜索 | Ctrl-R | 进入历史搜索模式, 实时过滤 |
| T-Key-5 | Tab 补全 | 输入 `/mod` + Tab | 补到 `/model`, 多个候选时显示 alternatives |
| T-Key-6 | Ctrl-C 取消 | 流式时 Ctrl-C | 终止当前 query (输入空时退出) |
| T-Key-7 | Ctrl-L 清屏 | Ctrl-L | 重绘 scrollback (line mode 走 ANSI 清屏) |
| T-Key-8 | Ctrl-? / F1 帮助 | Ctrl-? | 弹 /help 等价 overlay |
| T-Key-9 | Esc 关闭 overlay | 弹 help 时按 Esc | overlay 消失 |

### 2.4 主题

| ID | 能力 | 怎么测 | 期望 |
|----|------|--------|------|
| T-Theme-1 | 内置三主题 | `/theme default` / `solarized` / `monokai` | 配色立即切换 |
| T-Theme-2 | Theme picker | `/theme-pick` | 弹出 picker, 选完回 TUI |
| T-Theme-3 | aethercode-themes 桥接 | 装了 aethercode-themes 额外主题后 `/theme-pick` | 列出所有 user/global 主题 |

### 2.5 TUI slash 命令完整清单 (R31 + 6 轮增量, 50+ 条)

**通用**

| 命令 | 行为 |
|------|------|
| `/help` / `/?` | 列出所有命令 (还显示 50+ 命令) |
| `/clear` | 清 scrollback |
| `/exit` / `/quit` / `/q` | 退出 TUI |
| `/history` | 弹输入历史 |
| `/stats` | 当前 session 的 token / cost |
| `/state` | 引擎 state (debug 视图) |
| `/ping` | liveness check (RPC) |

**模型 / 模式**

| 命令 | 行为 |
|------|------|
| `/model <name>` | 切模型 |
| `/mode <name>` | 切 permission mode (DEFAULT / ACCEPT_TASK / ACCEPT_EDITS / BYPASS_PERMISSIONS / PLAN / AUTO_READ_ONLY) |
| `/no-confirm` | 快捷切到 BYPASS_PERMISSIONS (一键全授权) |

**工具 / 项目**

| 命令 | 行为 |
|------|------|
| `/tools` | 列出可用工具 (RPC) |
| `/tool-actions` | 每个工具的默认 permission action (safe/ask/deny) |
| `/sessions` | 列出已存 session |
| `/tasks` | 列出最近 tool 调用 (R32-E) |
| `/projects` | 列出所有已知 cwd |
| `/cwd <path>` | 切到别的项目 |
| `/metrics` | 引擎指标快照 (R77) |
| `/trace` | 最近 trace 列表; `/trace tr-xxx` 看一个 trace tree (R78/R79) |
| `/budget <usd>` | 本 session 成本预算; `/budget off` 关闭 (R44) |
| `/rewind <n>` | 回滚 transcript 到第 n 条 (R50) |
| `/snippet save\|load\|list\|delete <name>` | 命名 prompt 片段 (R51) |
| `/export <path>` | 导 scrollback 为 markdown (R61) |
| `/tutorial` | 重显 welcome overlay (R59) |
| `/lastplan` | 上次 plan 视图 |
| `/layout <full\|minimal\|focus>` | 切布局 (R43) |

**Memory / Skill / Agent**

| 命令 | 行为 |
|------|------|
| `/memory [tab]` | 弹 MemoryPanel (global / project / session) (T-080) |
| `/memory-edit` | 改当前 focus 的条目 (T-083) |
| `/memory-compact` | 强 project-memory compact (T-084) |
| `/agent-pick` | 弹 agent 选择器 (T-420) |
| `/effort-pick` | 选 reasoning-effort (low/med/high/xhigh) (T-421) |
| `/cwd-pick` | 弹 cwd 切换器 (T-423) |
| `/mcp` | MCP server / tool viewer (T-428) |
| `/mcp-login <name>` | 启 OAuth (T-428) |
| `/mcp-reconnect` | 强重连 MCP registry (T-428) |
| `/threads` | 切到别的 thread (T-429) |

**长任务 (Phase 6.3, T-6-17)**

| 命令 | 行为 |
|------|------|
| `/continue` | 续跑 task (task/resume) |
| `/pause` | 暂停 task (task/pause) |
| `/stop` | 强杀 task (task/kill) |
| `/todos` | 弹 TodoBoard (j/k 翻) |
| `/tokens` | 显当前 token 用量 |
| `/consents` / `/grants` | 弹 GrantsManager (revoke / preset) |
| `/workflow <name>` | 跑 workflow; 不带名打开 picker |
| `/update` | 显更新提示 (如果有新 release) |
| `/update-deps` | 确认依赖刷新 |
| `/notifications` | 通知中心 (T-431) |
| `/skip <N\|off>` | 跳过后续 N 个 confirmation (R99) |
| `/skip-stats` | skip 采纳统计 (R106) |
| `/prompt` | 显 system prompt sections (R94-E) |
| `/prompt <name>` | 钻到某 section (R95-G) |
| `/phase` | 显当前 phase + budget (R95-F) |
| `/phase <name>` | 切 phase (plan / explore / implement / verify) |
| `/budget <p> <calls> [usd]` | 重配某 phase 的 cap (R95-F) |
| `/sessions-list` | 列 daemon 所有 session (R95-E) |
| `/session <id>` | 切 active session |
| `/session-new <id>` | 建 + 切到新 session |
| `/session-del <id>` | 删 session |

**Agent registry (R109-3-sync)**

| 命令 | 行为 |
|------|------|
| `/agents` | 列 Mavis agents + 关联 model |
| `/agent <name>` | 显 agent body + frontmatter |

### 2.6 TUI Markdown / 渲染 (R213–R220)

| ID | 能力 | 怎么测 | 期望 |
|----|------|--------|------|
| T-Render-1 | Markdown 标题 / 列表 / 表格 | 模型发带 # / - / \| \| 的回复 | 正确渲染 |
| T-Render-2 | 代码块高亮 | 模型发 ` ```js ... ``` ` | 按语言染色 |
| T-Render-3 | 内联代码 (R218) | 模型发 `foo()` | 用 inline-style 染色 |
| T-Render-4 | 语言推断 (R219) | 没写语言但内容明显是 Python | 自动识别 + 染色 |
| T-Render-5 | 链接预览 (R220) | 模型发 `https://...` | 卡片预览 + title |
| T-Render-6 | Box border (R88-C) | 长代码块 | box border + language tag |

### 2.7 思考折叠 / 侧栏 (R86)

| ID | 能力 | 怎么测 | 期望 |
|----|------|--------|------|
| T-Think-1 | 折叠 thinking | 跑一个会 thinking 的模型 | 思考文字折叠为 dim 子区 |
| T-Think-2 | Ctrl-I 切换 thinking | Ctrl-I | 显示/隐藏 thinking |
| T-Think-3 | Permission 决策卡 | 触发权限 | 弹出内联决策卡, 不再全屏模态 |
| T-Think-4 | ACCEPT_TASK 模式 | `/mode ACCEPT_TASK` 后跑多步 | 单 task 内不卡, 跨 sub-task 才弹一次 |

---

## 3. 跨 surface 能力 (Desktop + TUI 共有)

这些能力在两个 UI 下行为一致,只是入口不同。

### 3.1 Memory 系统 (R-MEM-1/2/3/4/5)

| ID | 能力 | 怎么测 | 期望 |
|----|------|--------|------|
| X-Mem-1 | 三 scope 记忆 | 写一条 USER / PROJECT memory, 开新 session | USER 自动 recall; PROJECT 跟 cwd 走 |
| X-Mem-2 | 向量检索 | `memory/find "我的 Python 习惯"` | top-K 命中, 排序合理 |
| X-Mem-3 | Evolution (consolidate) | 故意写 5 条近重复, 调 `memory/consolidate` | 合并, 源标记 `consolidated_into` |
| X-Mem-4 | Evolution (forget) | 调 `memory/forget` with `expired: true` | soft-delete, 30 天后 vacuum |
| X-Mem-5 | Team memory (子 agent) | 子 agent 写 shared change, 主 agent 查 | `memory/readTeamMemory` 命中 |
| X-Mem-6 | Skill memory | 装 / 调一个 skill 几次 | `memory/findSkill` 按 success_count 排序返回 |
| X-Mem-7 | 6 个新 memory RPC | daemon log / RPC 列表 | `memory/find`, `memory/consolidate`, `memory/forget`, `memory/stats`, `memory/shareToSubagent`, `memory/promoteFromSubagent`, `memory/upsertSkill`, `memory/recordSkillOutcome`, `memory/findSkill` |

### 3.2 Workflow 系统 (R102–R107)

| ID | 能力 | 怎么测 | 期望 |
|----|------|--------|------|
| X-Flow-1 | 列出 workflow | TUI `/workflow` 或 Desktop Workflows tab | 列 `.aethercode/workflows/*.yaml` |
| X-Flow-2 | 创建 | `/workflow create` (Desktop) / 写 yaml (TUI) | 5 步: 弹编辑器 / 写 yaml, 实时校验, 存盘 |
| X-Flow-3 | 运行 | `/workflow <name>` 或 Picker | 每步一个 child session, 输出 capture |
| X-Flow-4 | 5 种 step | yaml 写 `kind: llm/agent/skill/shell/http` | 都跑通 |
| X-Flow-5 | @-mention (R104) | yaml 写 `@agent code-reviewer` | 解析 + spawn agent |
| X-Flow-6 | 错误捕获 (R105) | step 故意失败 | workflow UI 显示 error pill, 不崩溃 |
| X-Flow-7 | Live editor (R110-4) | Desktop 改 yaml, 立刻重新解析 | 解析错误红条, 步列表实时 |
| X-Flow-8 | 嵌套 progress (R110-2) | 长 step | 跑动 pill 下 FIFO activity list (6 cap) |
| X-Flow-9 | Step 详情 (R110-5) | 点 running pill | 全事件历史 modal |

### 3.3 Agent 系统 (R107-B, R109-2/3, R173)

| ID | 能力 | 怎么测 | 期望 |
|----|------|--------|------|
| X-Agent-1 | 列 agents | TUI `/agents` 或 Desktop Agents tab | 列 `~/.minimax/agents/<name>/agent.md` |
| X-Agent-2 | 看 agent 详情 | `/agent <name>` | 显 frontmatter + body |
| X-Agent-3 | 创建 agent | Desktop: Agents → "+", 编辑 name/model/desc/body | 走 `createAgent` RPC |
| X-Agent-4 | 编辑 agent | Desktop: 点 agent 行 | AgentEditor modal |
| X-Agent-5 | 删 agent | Desktop: 行内删除 | 走 `deleteAgent` RPC |
| X-Agent-6 | Per-agent model (R109-3) | agent.md frontmatter 写 `model: glm/glm-4-flash` | 该 agent 起子 session 用指定 model |

### 3.4 Provider / Model 切换 (R109-1)

| ID | 能力 | 怎么测 | 期望 |
|----|------|--------|------|
| X-Prov-1 | 列 provider | Desktop Settings 弹窗 | 列 minmax / anthropic / openai / glm / qwen / deepseek / gemini |
| X-Prov-2 | 切 provider | Settings → provider dropdown | 模型列表刷新 |
| X-Prov-3 | 切模型 (runtime) | `/model <name>` 或 Settings 模型下拉 | 立即生效, 下条 query 用新模型 |
| X-Prov-4 | 模型 mismatch 处理 (R176) | localStorage 的 model 跟 daemon 不一致 | 启动时弹 "switch / keep" 提示 |

### 3.5 Permission 决策 (R86, R88-C, R200, R203, R204, R206, R207)

| ID | 能力 | 怎么测 | 期望 |
|----|------|--------|------|
| X-Perm-1 | 4 档 mode | `/mode` | DEFAULT / ACCEPT_TASK / ACCEPT_EDITS / BYPASS_PERMISSIONS / PLAN / AUTO_READ_ONLY |
| X-Perm-2 | 决策卡 (R86) | 触发权限 | 内联卡, 4 选项: allow / always / deny |
| X-Perm-3 | ACCEPT_TASK 模式 | 设 ACCEPT_TASK, 跑多步 | 跨 sub-task 才弹一次 |
| X-Perm-4 | 矩阵策略 (R207) | 设 policy + mode 组合 | 三种 preset (permissive / cautious / strict) |
| X-Perm-5 | Grants (T-6-17) | `/consents` 或 Desktop Settings | 列表 + revoke + preset |

### 3.6 Hook 系统

| ID | 能力 | 怎么测 | 期望 |
|----|------|--------|------|
| X-Hook-1 | user-tier hook | 在 `~/.aethercode/hooks/` 放 hook yaml | 启动时加载, 列表里看到 |
| X-Hook-2 | project-tier hook | 在 `<cwd>/.aethercode/hooks/` 放 | 同上, 跟 cwd 走 |
| X-Hook-3 | 触发 hook | 跑对应事件 | 走 PreToolUse / PostToolUse 等 |

### 3.7 MCP (Model Context Protocol, R93)

| ID | 能力 | 怎么测 | 期望 |
|----|------|--------|------|
| X-MCP-1 | 列 MCP servers | `/mcp` 或 Desktop MCP 视图 | 列 `~/.aethercode/mcp.json` 配的 server |
| X-MCP-2 | 看 MCP tools | 进 server 详情 | 列该 server 提供的 tools |
| X-MCP-3 | 启 OAuth | `/mcp-login <name>` | 走 device flow, 完成后 server 变 connected |
| X-MCP-4 | 强重连 | `/mcp-reconnect` | daemon 主动 reconnect registry |

### 3.8 Session 管理

| ID | 能力 | 怎么测 | 期望 |
|----|------|--------|------|
| X-Sess-1 | 自动持久化 | Desktop 上跑几个 query, 关闭再开 | session 列表里都在 |
| X-Sess-2 | 跨 cwd 隔离 (R197, R199) | 切到别的 cwd | 列表按 cwd 分组 |
| X-Sess-3 | Trash 恢复 | 删 session → 进 Trash | 一键恢复 |
| X-Sess-4 | 多 daemon 并存 (R199) | 同时启 2 个 daemon (不同 cwd) | 各自 session 独立 |
| X-Sess-5 | Session rename | Desktop session 行右键重命名 | 列表里显示新名 |

### 3.9 Loop Guard & 自动 Compact (R92, R101)

| ID | 能力 | 怎么测 | 期望 |
|----|------|--------|------|
| X-Loop-1 | Loop detect tier 1 | 故意让模型重复 tool | 弹 LoopGuardBanner warn |
| X-Loop-2 | Tier 2 硬停 | 继续不 ack | 跑到 3 次 engine stopReason=loop_detected |
| X-Loop-3 | "继续" 按钮 | 弹 banner 时点 | reset tier, 继续 |
| X-Loop-4 | Auto compact (R92) | 上下文到 80% | 引擎自动 compact + 提示 |
| X-Loop-5 | SideNote compact (R91) | 跟 compact 联动 | UI 显示 compact 状态 |

### 3.10 Trace / Metrics / Token 统计

| ID | 能力 | 怎么测 | 期望 |
|----|------|--------|------|
| X-Trace-1 | Trace list | `/trace` 或 Desktop Trace tab | 列最近 trace span |
| X-Trace-2 | Trace tree | `/trace tr-xxx` | 树形展开 |
| X-Trace-3 | Span 详情 | 点 trace 行 | inputs / outputs |
| X-Trace-4 | Metrics 快照 | `/metrics` 或 RpcDiagnostics | token / cost / latency |
| X-Trace-5 | Token chart (R97) | Desktop Telemetry | 实时折线图 |

### 3.11 多协议 daemon (headless)

| ID | 能力 | 怎么测 | 期望 |
|----|------|--------|------|
| X-Head-1 | stdio JSON-RPC | `java -jar aethercode-0.2.57.jar --daemon` + 另一进程 pipe | 双向 JSON-RPC 2.0 通 |
| X-Head-2 | WebSocket (R-MEM-5 之后) | `ws://localhost:<port>` 推 stream_event | 实时收 text_delta / tool_use_start / sub_task_xxx |
| X-Head-3 | 完全无 UI | 跑 headless, 不开 Desktop / TUI | 仍能完整走通 query → tool → result → reply |

---

## 4. 已知约束 / 注意事项

| 项 | 说明 |
|----|------|
| TUI 在 cmd.exe | Ink 渲染退化, 强制 `--line` 才稳 |
| TUI 的 daemon 路径 | marker-driven, 找 `aethercode-0.2.1.jar` (兼容) / `aethercode-0.2.57.jar` (canonical) / `AETHERCODE_JAR` env / `--jar` flag; 找不到会**自动启动**最近一次 canonical jar |
| Desktop 内嵌 jar | tauri `bundle.resources` 嵌 `aethercode.jar`, 改了 dist jar 后必须重 tauri build |
| TUI 内嵌 jar | TUI 是独立进程, 不嵌 jar, 通过 RPC 调 daemon |
| Permission mode 持久化 | daemon 写 `<cwd>/.aethercode/permissions.json` (project-tier) 和 `~/.aethercode/permissions.json` (user-tier), 跨 session 保留 |
| Skill 路径 | `~/.aethercode/skills/<name>/SKILL.md` (R210 改名自 `~/.minimax/skills/`) |
| Agent 路径 | `~/.minimax/agents/<name>/agent.md` (frontmatter + body) |
| Workflow 路径 | `<cwd>/.aethercode/workflows/<name>.yaml` |
| Hook 路径 | `<cwd>/.aethercode/hooks/*.yaml` 或 `~/.aethercode/hooks/*.yaml` |
| MCP 配置 | `~/.aethercode/mcp.json` (R210 改名自 `~/.minimax/mcp.json`) |
| Memory 路径 | USER: `~/.aethercode/agent-memory/<agentType>/`; PROJECT: `<cwd>/.aethercode/agent-memory/<agentType>/`; LOCAL: `<cwd>/.aethercode/agent-memory-local/<agentType>/` |

---

## 5. 推荐测试顺序 (30 分钟过一遍)

如果想最短时间覆盖主要 surface 能力:

1. **0. 启动** — Desktop + TUI 同时开
2. **D-Header / D-Left-1~5** — 切项目 + 切 session
3. **D-Chat-1, 3, 6, 7** — 跑 query, 看 chat 流式 + tool card + plan card
4. **D-Right-Plan** — 切 Plan tab, 看 TODO 实时刷新
5. **D-Modal-5 / 6** — Ctrl+P / Ctrl+K 命令面板
6. **D-Settings-1, 2, 8** — 切 permission / provider / theme
7. **D-Mem-1, 2, 5** — Memory 面板 + 搜索
8. **T-Key-1, 3, 5, 8** — TUI 基本操作
9. **T-Theme-1** — 切主题
10. **X-Mem-2 / X-Flow-3 / X-Perm-3** — 各 surface 都能验证
11. **X-Loop-1** — 故意循环一下, 看 LoopGuardBanner

---

## 6. 出问题的排错入口

| 症状 | 建议 |
|------|------|
| Desktop 起不来 | 删 `%TEMP%\aethercode-desktop\*` 缓存; 改 `aethercode.cwd` system prop |
| TUI 报 "no jar found" | 跑 `python scripts/promote-jar.py` 检查 marker, 或 `java -jar <jar> --daemon` 先起 daemon |
| 内存涨到 88% | 切 Settings → Concurrency profile → Low |
| 性能慢 | 切 Concurrency profile → High (beefy 机器) |
| daemon 挂了 | 桌面会自动重启 (R155); TUI 重启 TUI 进程 |
| 模型不对 | 显式 `/model <provider>/<model>`; 删 localStorage `enginePrefs.model` 触发 mismatch 提示 (R176) |
| Permission 永远弹 | `/mode BYPASS_PERMISSIONS` 或 `/no-confirm`; 或 `/skip 50` 临时跳过 50 次 |
| Loop banner 一直弹 | 切 Settings → Loop detector 阈值调高, 或拖到 0 关闭 |

---

## 7. 进一步阅读

| 文档 | 路径 | 说明 |
|------|------|------|
| R237 报告 | `aethercode-workflows/docs/R237-SSD-UX-PROMOTE-FIX.md` | 当前 0.2.57 是 R237 final |
| R236 报告 | `aethercode-workflows/docs/R236-SSD-INTERNALIZATION.md` | SSD 内部化 |
| R-MEM 5 frontiers | `aethercode-desktop/docs/R-MEM-5-FRONTIERS-2026-09-08.md` | Memory 5 大 frontier |
| R-MEM 优化设计 | `doc/项目文档/AetherCode-Memory-Optimization-Design.md` | Memory P0+P1 设计 |
| Memory 能力与使用 | `doc/帮助文档/Memory-能力与使用指南.md` | Memory 用户视角 |
| R 轮次全景 | `aethercode-desktop/docs/R<NNN>-*.md` (80+ 篇) | 每轮都有测试 + 行为变化 |
| TUI 文档 | `aethercode-tui/README.md` | TUI 命令 + 快捷键 |
| USER-GUIDE | `doc/USER-GUIDE.md` | 历史 daily use (R109 时代, 部分过时) |
| CHANGELOG | `doc/CHANGELOG.md` | R 轮次列表 |
| PACKAGING | `doc/PACKAGING.md` | 出包流程 |

---

**最后更新**: 2026-09-09 (R237 final, jar SHA 7B41A116...)
