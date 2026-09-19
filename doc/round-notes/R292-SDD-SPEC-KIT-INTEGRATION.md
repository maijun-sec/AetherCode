# R292 — Spec Kit 集成实现 SDD 能力

> **触发**: 当前 `aethercode ssd` 命令（R236-R289 多轮迭代）被判断为错误方向。用户决定:
> (1) 集成 GitHub Spec Kit (github/spec-kit, MIT, 103k+ stars) 作为 SDD 能力的实现;
> (2) 走**嵌入式**集成 (daemon 内嵌 Spec Kit 模板, 跑自己的 LLM 编排);
> (3) 旧的 `aethercode ssd` 直接**迁移到 `aethercode sdd`** 并删除 (用户原话: "sdd 才是规范驱动开发, 之前的 ssd 都是错误的");
> (4) 验收标准: **TUI + Desktop + IDEA plugin + supervisor 4 端 e2e 跑通**.
>
> **目标**: 把 daemon 从 4 阶段 (spec/design/tasks/dev) 升级为 Spec Kit 标准的 6 阶段 (constitution/specify/plan/tasks/implement/converge), 在 TUI / Desktop / IDEA plugin 三个客户端都能驱动 SDD 流程.

---

## 1. 架构现状 (R291 收尾)

### 1.1 daemon 端 SSD 实现 (R236 → R289)
- `aethercode-cli/.../SsdCommand.java` — CLI 入口, `aethercode ssd <feature> "<intent>"` 命令
- `aethercode-workflows/.../ssd/SsdRunner.java` — 4 阶段 orchestrator (spec/design/tasks/dev)
- `aethercode-workflows/.../ssd/SsdConfig.java` — YAML 加载器, 模板替换 (`{{feature}}`, `{{intent}}`, `{{priorContent}}`)
- `aethercode-workflows/.../ssd/InteractiveRepl.java` — NDJSON 协议, `phase-list / phase-start / phase-draft / phase-confirm-required / phase-accepted / phase-skipped / complete / abort / log` 事件
- `aethercode-workflows/resources/ssd/ssd-defaults.yaml` — 4 阶段 prompt 模板 (9650 字节)
- 制品路径: `<cwd>/.aethercode/ssd/<feature>/{spec,design,tasks}.md` + `dev.log`

### 1.2 Desktop 端 (R281/R287)
- `aethercode-desktop/src/components/ssd/SsdPanel.tsx` — TODO chip + 确认面板
- `aethercode-desktop/src/components/ssd/driver.ts` — `SsdDriver` 接口 + `MockSsdDriver` 测试桩
- `aethercode-desktop/src/components/ssd/tauriSsdDriver.ts` — `TauriSsdDriver` spawn jar 子进程
- `aethercode-desktop/src/components/SddPhaseBar.tsx` (R288) — 输入框上方的 4 phase pill
- 集成位置: SettingsPage 的 SDD tab → SsdPanel (R281)

### 1.3 TUI 端 (aethercode-tui)
- 走 supervisor RPC (`aethercode-tui/src/rpc/client.ts`), JSON-RPC over Unix socket / Windows named pipe
- 没有 SDD 专用面板, 通用 TodoBoard + TranscriptEnricher 组件可用
- 现有 RPC 方法: `task/spawn`, `task/list`, `task/attach`, `task/kill` (SupervisorRpcServer.java)

### 1.4 IDEA Plugin 端
- `idea-plugin/src/main/kotlin/.../DaemonBackend.kt` — 90 行 JSON-RPC 客户端, 复用 supervisor 协议
- 现有调用方法: `task/spawn`, `task/attach` (R7/R300 接入)
- 没有 SDD 专用面板

---

## 2. Spec Kit 集成方案

### 2.1 集成模式: A 嵌入式

**核心**: daemon 把 Spec Kit 的 6 阶段模板 + constitution + slash 命令模板作为资源打包进 jar; SddRunner 加载这些模板, 用 AetherCodeEngine.query() 驱动 LLM, 生成 Spec Kit 兼容的 `.specify/` 目录制品.

**为什么不是 B (subprocess)**:
- B 要求用户机器装外部 agent CLI (Claude Code/Gemini CLI/Cursor), 加重用户依赖
- daemon 失去 LLM 编排自主权, 跟现有架构 (daemon 是 LLM 编排器) 冲突

**为什么不是 C (混合式)**:
- C 不能直接被任何 Spec Kit agent 复用 `.specify/` 制品, 失去"集成"价值
- 既然要打包 Spec Kit 模板, 跟 A 的差异只剩下命名/路径, 优势不明显

### 2.2 阶段映射 (6 阶段, 含 2 个可选质量门)

| Spec Kit 阶段      | 阶段 ID       | Order | 制品路径                                                | 可选 |
|-------------------|--------------|-------|---------------------------------------------------------|------|
| constitution       | `constitution`| 0     | `.specify/memory/constitution.md`                       | 否 (项目级, 一次性)|
| clarify             | `clarify`     | 1.5   | (修改 `spec.md`)                                         | 是 |
| specify             | `specify`     | 1     | `.specify/specs/<NNN>-<slug>/spec.md`                   | 否 |
| plan                | `plan`        | 2     | `.specify/specs/<NNN>-<slug>/plan.md`                   | 否 |
| analyze             | `analyze`     | 2.5   | (修改 `plan.md` + `tasks.md`)                            | 是 |
| tasks               | `tasks`       | 3     | `.specify/specs/<NNN>-<slug>/tasks.md`                  | 否 |
| implement           | `implement`   | 4     | `.specify/specs/<NNN>-<slug>/logs/implement.log`        | 否 |
| converge            | `converge`    | 5     | `.specify/specs/<NNN>-<slug>/convergence.json`         | 否 |

**slug 规则**: `001-<kebab-case-name>`, sequential 编号 (跟 Spec Kit 默认对齐). 切换 timestamp 模式走 `--branch-numbering timestamp` (跟 Spec Kit 对齐).

### 2.3 制品路径迁移

| 旧 SSD 路径 (R291)                              | 新 SDD 路径 (R292)                                |
|------------------------------------------------|--------------------------------------------------|
| `<cwd>/.aethercode/ssd/<feature>/spec.md`     | `<cwd>/.specify/specs/<NNN>-<feature>/spec.md`  |
| `<cwd>/.aethercode/ssd/<feature>/design.md`   | `<cwd>/.specify/specs/<NNN>-<feature>/plan.md`  |
| `<cwd>/.aethercode/ssd/<feature>/tasks.md`    | `<cwd>/.specify/specs/<NNN>-<feature>/tasks.md` |
| `<cwd>/.aethercode/ssd/<feature>/dev.log`     | `<cwd>/.specify/specs/<NNN>-<feature>/logs/implement.log` |
| `<cwd>/.aethercode/ssd/ssd.yaml`              | `<cwd>/.specify/memory/constitution.md` (项目级, 单一) |

旧路径下残留的产物用户自己处理 (本轮不写迁移工具, 用户决定丢弃还是手动复制 constitution).

---

## 3. 改动清单

### 3.1 daemon 端 (核心, ~60% 工作量)

#### 3.1.1 资源 (新增)
- `aethercode/aethercode-workflows/src/main/resources/spec-kit/templates/spec-template.md`
- `aethercode/aethercode-workflows/src/main/resources/spec-kit/templates/plan-template.md`
- `aethercode/aethercode-workflows/src/main/resources/spec-kit/templates/tasks-template.md`
- `aethercode/aethercode-workflows/src/main/resources/spec-kit/templates/checklist-template.md`
- `aethercode/aethercode-workflows/src/main/resources/spec-kit/memory/constitution-template.md`
- `aethercode/aethercode-workflows/src/main/resources/spec-kit/scripts/autoformat.sh` (占位)
- `aethercode/aethercode-workflows/src/main/resources/spec-kit/AGENTS.md` (daemon 自己用, 解释 .specify/ 约定)

来源: `github/spec-kit` 仓库, MIT. 用 `curl` 或 `web_fetch` 拉下来, 内容作为 jar 资源.

#### 3.1.2 包结构变更
- 旧 `org.aethercode.workflows.ssd.*` (SsdRunner, SsdConfig, InteractiveRepl, SsdCommand) — **删除**
- 新 `org.aethercode.workflows.sdd.*` (SddRunner, SddConfig, InteractiveRepl, SddCommand) — 新增

#### 3.1.3 SddRunner.java (新增)
- 6 阶段 orchestrator + 2 可选质量门
- 阶段命名用 `PhaseId` enum (CONSTITUTION, SPECIFY, PLAN, TASKS, IMPLEMENT, CONVERGE, CLARIFY, ANALYZE)
- 复用 R236 的 LLM 调用模式 (`LlmFn`, `ReplFn`, `Logger` 接口)
- phase 4 (implement) 用 Spec Kit 的 TDD 任务表 parser (`[P]` 并行标记, `T-NNN` ID)
- phase 5 (converge) 循环到 convergence 报告 "Converged"

#### 3.1.4 SddConfig.java (新增)
- 从 `.specify/memory/constitution.md` 加载项目级 constitution (项目里没有则用 jar 里的模板)
- 从 jar 资源加载阶段 prompt 模板 (跟当前 SsdConfig 同形)
- 加 `SlugPolicy` (SEQUENTIAL / TIMESTAMP), 默认 SEQUENTIAL 跟 Spec Kit 对齐
- 加 `enableClarify`, `enableAnalyze` 标志 (默认都开)

#### 3.1.5 InteractiveRepl.java (新增, 替换旧版)
NDJSON 事件升级, 兼容 Spec Kit 阶段命名:

```json
// 旧 (R291):
{"event": "phase-list", "phases": [{"id":"spec", "order":1, "title":"Spec"}, ...]}
{"event": "phase-start", "phase":"spec", "order":1, "title":"Spec"}
{"event": "phase-draft", "phase":"spec", "path":"...", "bytes":4321, "preview":"..."}
{"event": "phase-confirm-required", "phase":"spec"}
{"event": "phase-accepted", "phase":"spec", "revisionCount":0}
{"event": "phase-skipped", "phase":"spec", "reason":"already-accepted"}
{"event": "phase-error", "phase":"spec", "message":"..."}
{"event": "complete", "results":[...]}
{"event": "abort", "reason":"user-quit"}
{"event": "log", "level":"info", "message":"..."}

// 新 (R292):
{"event": "phase-list", "phases":[{"id":"constitution","order":0,...}, {"id":"specify","order":1,...}, ...], "slug":"001-photo-albums"}
{"event": "phase-start", "phase":"specify", "order":1, "title":"Specify"}
{"event": "phase-draft", "phase":"specify", "path":".../spec.md", "bytes":4321, "preview":"..."}
{"event": "clarify-question", "phase":"specify", "id":"q1", "question":"...", "header":"...", "options":[{"label":"...", "description":"..."}]}
{"event": "converge-check", "phase":"converge", "iteration":1, "converged":false, "issues":["..."]}
{"event": "phase-accepted", "phase":"specify", "revisionCount":0}
{"event": "phase-skipped", "phase":"specify", "reason":"already-accepted"}
{"event": "phase-error", "phase":"specify", "message":"..."}
{"event": "complete", "results":[...]}
{"event": "abort", "reason":"user-quit"}
{"event": "log", "level":"info", "message":"..."}
```

NDJSON 命令升级 (driver → daemon):

```json
// 旧:
{"action":"accept"}
{"action":"revise", "text":"..."}
{"action":"skip"}
{"action":"quit"}

// 新 (新增):
{"action":"accept"}
{"action":"revise", "text":"..."}
{"action":"skip"}
{"action":"quit"}
{"action":"clarify-answer", "id":"q1", "answer":"..."}      // R292 新增
{"action":"converge-iterate", "text":"..."}                  // R292 新增, 让用户提反馈再 implement
```

#### 3.1.6 SddCommand.java (新增, 替换 SsdCommand.java)
- 命令名 `sdd` (不是 `ssd`)
- 参数 `<feature> "<intent>"` + `--cwd`, `--from-phase`, `--to-phase`, `--auto`, `--interactive`, `--force`
- 新增 `--branch-numbering` (sequential/timestamp)
- 新增 `--no-clarify` / `--no-analyze` (跳过质量门)
- 新增 `--enable-converge` (默认开, 跟 Spec Kit 对齐)
- 注册到 `Main.subcommands` 替换 `SsdCommand.class`

#### 3.1.7 删除旧文件
- `SsdCommand.java`
- `SsdRunner.java`
- `SsdConfig.java`
- `InteractiveRepl.java` (旧版, 改名 + 升级)
- `ssd-defaults.yaml`
- `SsdRunnerTest.java`, `SsdConfigTest.java`, `InteractiveReplR281Test.java` (重写为 SDD 测试)

### 3.2 Desktop 端 (~15% 工作量)

#### 3.2.1 driver.ts 升级
- 重命名 `SsdDriver` → `SddDriver`, `SsdDriverEvent` → `SddDriverEvent`, `SsdInboundCommand` → `SddInboundCommand`
- 加 `clarifyAnswer`, `convergeIterate` 命令
- 阶段状态 `pending | running | confirm-pending | clarify-pending | converge-pending | done | skipped | failed`
- `MockSsdDriver` → `MockSddDriver` (测试桩同步重命名)

#### 3.2.2 tauriSsdDriver.ts → tauriSddDriver.ts
- spawn 命令改 `ssd` → `sdd`
- 加 `enableConverge`, `branchNumbering` 透传
- 文件路径由 `.aethercode/ssd/<feature>/` 改为 `.specify/specs/<NNN>-<feature>/`

#### 3.2.3 SsdPanel.tsx → SddPanel.tsx
- 阶段列表从 4 个 (spec/design/tasks/dev) 改为 6 个 + 2 可选 (constitution/specify/plan/tasks/implement/converge + clarify/analyze)
- 新增 `ClarifyAnswerDialog` (回答 clarify 问题)
- 新增 `ConvergeResult` 面板 (显示 convergence report)
- TODO chip 渲染逻辑适配新阶段命名

#### 3.2.4 SddPhaseBar.tsx 升级 (R288)
- 4 phase pill → 6 phase pill + 可选 phase 渲染
- 标题中文 (跟 R288 一致): "项目原则 → 定义需求 → 制定计划 → 拆解任务 → 执行实现 → 收敛验证"

#### 3.2.5 SettingsPage.tsx (R281 入口)
- 路由 `/settings/sdd` 不变 (R281 已有)
- 改用 SddPanel 替换 SsdPanel
- 删除 R288 后的 `SddPhaseBar` 重复入口 (现在统一在 SettingsPage 内)

### 3.3 TUI 端 (~10% 工作量)

#### 3.3.1 新增 `aethercode-tui/src/commands/sdd.ts`
- `/sdd` 命令入口 (类似 `/task` 现有命令)
- 显示当前 feature 状态 (phase list + 当前阶段)
- 提供 accept/revise/quit 操作

#### 3.3.2 复用 `TodoBoard.tsx` (现有)
- 适配 6 phase 渲染
- 加可选 phase (clarify/analyze) 的视觉降级 (灰显)

#### 3.3.3 复用 `TranscriptEnricher.ts` (现有)
- 加 `phase-draft` 事件 → 在 chat transcript 渲染 markdown draft preview
- 加 `clarify-question` 事件 → 在 chat 渲染 Q&A 卡片

#### 3.3.4 加 supervisor RPC 调用 (走 RPC 而非 spawn 子进程)
- `sdd/start {feature, intent, cwd, options}` → {sddId}
- `sdd/attach {sddId, since}` → {sdd, events}
- `sdd/cancel {sddId, reason}` → {ok}

### 3.4 IDEA Plugin 端 (~15% 工作量)

#### 3.4.1 DaemonBackend.kt 加 SDD RPC 方法
```kotlin
fun sddStart(feature: String, intent: String, cwd: String, ...): SddStartResult
fun sddAttach(sddId: String, since: Long): SddAttachResult
fun sddCancel(sddId: String, reason: String): RpcResult
```

#### 3.4.2 supervisor 加 SDD service
- 镜像 `SupervisorService.java` 的 taskSpawn/taskAttach/taskKill 模式
- 新增 `SddService.java` 处理 sdd/start, sdd/attach, sdd/cancel
- 注册到 `SupervisorRpcServer.java` 的 case 分支

#### 3.4.3 ChatPanel 加 SDD mode
- 新增菜单入口 `Tools → Spec-Driven Development`
- 弹窗输入 feature + intent → 调 sddStart
- 监听 sdd events → 渲染 phase chip + draft preview

---

## 4. 测试策略

### 4.1 daemon 单元测试
- `SddConfigTest.java` — 验证 jar 资源加载, 默认值, 项目 override
- `SddRunnerTest.java` — 6 阶段 mock LLM 走完整流程
- `InteractiveReplR292Test.java` — NDJSON 协议 + 新增命令 (clarify-answer, converge-iterate)
- `SpecKitTemplatesTest.java` — 验证 jar 内嵌的 6 个模板文件非空, 包含关键 markers

### 4.2 supervisor 单元测试
- `SddServiceTest.java` — sdd/start/attach/cancel 三方法的 happy path + 异常

### 4.3 Desktop 单元测试
- `SddPanelR292.test.tsx` — 6 phase 渲染 + accept/revise/clarify/converge
- `TauriSddDriverR292.test.ts` — spawn 参数 + 协议解析

### 4.4 TUI 单元测试
- `sdd.test.mjs` — RPC 调用 + 事件流渲染

### 4.5 IDEA Plugin 单元测试
- `SddRpcTest.kt` — 镜像 DaemonBackend SDD 方法

### 4.6 e2e (R292 验收)
- 跑一个 demo feature (例如 "R292-demo-photo-albums") 在 4 端各跑一次
- 验证制品落在 `.specify/specs/001-r292-demo-photo-albums/` 6 个文件
- 验证: constitution 改后再跑能复用 (--from-phase 1)

---

## 5. 关键技术决定

1. **daemon 内嵌 Spec Kit 模板** — 不打包整个 `specify-cli` (Python), 只打包 markdown 模板 + constitution 模板 + 必要的 schema. 理由: 我们用 AetherCodeEngine 编排, 不需要 Spec Kit 的 agent-specific 命令.
2. **slug 默认 SEQUENTIAL** — 跟 Spec Kit 默认行为对齐 (`001-feature-name`). `--branch-numbering timestamp` 可切.
3. **constitution 项目级单例** — 不随 feature 走, 在 `.specify/memory/constitution.md` 单文件. 复用条件: 用户明确要求重做 (--from-phase 0).
4. **converge 默认开** — 跟 Spec Kit `converge` 阶段对齐. 用户可用 `--no-converge` 跳到终点.
5. **clarify / analyze 默认开** — Spec Kit 推荐在 specify 后 + tasks 后跑. 提供 `--no-clarify` / `--no-analyze` 跳过.
6. **NDJSON 事件兼容向后** — 旧客户端拿到新事件会 drop unknown kind (R281 safeParseSsdEvent 已有该行为), 不会崩. 但旧的 4 phase UI 渲染新 6 phase 数据会显示空白 — 必须 4 端同步升级.
7. **TUI / IDEA 走 supervisor RPC** — 跟现有 task/* 模式对齐, 不引入新的子进程管理. Supervisor 加 SddService 镜像 TaskService.
8. **Desktop 继续走 Tauri shell 子进程** — 不改架构, 改 spawn 命令. Settings page 保留 SddPanel 入口.
9. **彻底删除旧 ssd 代码** — 用户原话 "之前的 ssd 都是错误的", 旧类全部删除不留 backup. 但 round-notes 文档保留供历史查阅.

---

## 6. 风险 + 回退

| 风险 | 缓解 |
|------|------|
| Spec Kit 模板格式升级后我们嵌入的版本过期 | 锁定一个 commit SHA, 升级时人工 review diff |
| TUI/IDEA RPC 加 SDD 后 supervisor 体积增大 | SddService 是镜像 TaskService 的 thin shim, 代码量可控 (~150 LOC) |
| Desktop SddPanel 6 phase 渲染复杂 | 复用 R281 的 PhaseChipView, 加 4 个新 chip + 可选 chip 灰显样式即可 |
| 用户从 R291 SSD 迁过来找不到旧制品 | 在 `aethercode sdd --migrate` 提供一次性迁移工具 (从 .aethercode/ssd/ 复制到 .specify/specs/, 并 rename files). 这是 nice-to-have, 主体 work 不依赖它. |
| 老的 `aethercode ssd` 命令删了, 但 daemon 启动脚本/RT UI 内可能还有引用 | grep 全仓做一遍引用清除 |

---

## 7. 不在本轮范围 (follow-up)

- R293: 从 `.aethercode/ssd/` 旧制品一次性迁移到 `.specify/specs/` 的工具
- R294: Spec Kit 的 `bug-fix` 和 `idea-assessment` 扩展集成 (R292 只做 SDD 核心)
- R295: Spec Kit 的 bundles / presets / extensions (项目级 governance) 集成
- R296: SDD run 与现有 daemon hook 系统集成 (ContinueWithResultMultiField 之类的 hook 触发 SDD)

---

## 8. 完成定义 (DoD)

### R292 (本轮)
- [ ] daemon 端: SddCommand/SddRunner/SddConfig/InteractiveRepl 6 阶段 orchestrator 跑通, jar 内嵌 Spec Kit 模板
- [ ] daemon 端: 旧 SsdCommand/SsdRunner/SsdConfig/ssd-defaults.yaml 全部删除
- [ ] Desktop 端: TauriSddDriver + SddPanel + SddPhaseBar 6 phase 渲染, NDJSON 协议升级
- [ ] 测试: daemon 单元测试全过, Desktop SddPanel + TauriSddDriver 测试全过
- [ ] 制品: demo feature (Daemon CLI) 落在 `.specify/specs/001-r292-demo-photo-albums/` 6 个文件
- [ ] 制品: 同一 demo feature 在 Desktop 端跑通
- [ ] 文档: R292 round-notes (本文档) + 各子模块 javadoc/tsdoc 同步更新
- [ ] 部署: jar + Desktop exe + zip 包, commit + push

### R293 (下一轮, 不在本轮范围)
- [ ] supervisor: SddService + SupervisorRpcServer 加 sdd/start, sdd/attach, sdd/cancel 三方法
- [ ] TUI 端: `/sdd` 命令 + RPC 客户端, 复用 TodoBoard + TranscriptEnricher
- [ ] IDEA Plugin 端: DaemonBackend 加 sdd/* RPC 方法, ChatPanel 加 SDD mode
- [ ] 跨 4 端 e2e: 同一 demo feature 在 TUI + IDEA Plugin 跑通

---

## 9. Round 编号

**R292 — Spec Kit SDD 集成 (Phase 1: daemon + Desktop)**. R293 接 TUI + IDEA + supervisor.

前序: R291 (compact snapshot view), R290 (agent variant settings), R289 (SSD mock driver eventDelay), R288 (SDD inline phase bar), R287 (Tauri SSD driver), R286 (agent variant settings publishers), R285 (model variants).