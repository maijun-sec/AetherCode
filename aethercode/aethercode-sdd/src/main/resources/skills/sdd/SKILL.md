---
name: sdd
description: |
  Spec-Driven Development — 严格 spec-kit 8 阶段规格化工作流，**per-phase handler 模式**。

  当用户说 "SDD" / "用规格化流程" / "/sdd" / "spec-kit" 时触发，**但每次只跑一个 phase**：
  - desktop 在用户点 SDD toggle 时预创建 `<cwd>/.aethercode/sdd/<sdd-task-preset>/` 目录 + 写 `phase-state.json` 记录当前该跑哪个 phase
  - 用户回 ✅ → desktop 推 phase-state.json + 派发 phase N+1 指令
  - 用户回 ✏️ → desktop 推 feedback + 重跑当前 phase
  - 用户回 ⏭️ → 写 skip sentinel，跳到下个 required phase

  ★ ABSOLUTE INVARIANTS ★
  1. **每个 chat message 只跑一个 phase**。绝对不许一次跑 8 phase。Phase 切换由 desktop 控制，不许 agent 自己决定。
  2. **每个 phase 的输入 = 之前所有 phase 的输出文件路径**（desktop 在派发时注入 `<sdd-task-preset>/constitution.md` 等列表）。不许在 phase 内部用记忆或上下文推断，必须 read_file 读实际文件。
  3. **每个 phase 只写自己的 output 文件**（phase N 写 `<sdd-task-preset>/<phase-N>.md`，**绝对不**写 `pom.xml` / `src/...` / `<cwd>/SPEC.md` 等）。
  4. **Phase 1-6 禁止写源代码**（包括 `pom.xml` / `build.gradle` / `src/main/java/...`）。源代码只在 phase 7 (implement) 写。
  5. **写完 output 后 HARD PAUSE**：禁止调任何 tool。只能输出 pause message 等用户回复 ✅ / ✏️ / ⏭️。
  6. **文件路径严格小写**：`constitution.md` / `spec.md` / `design.md` / `tasks.md` / `dev.log`。绝不允许 `SPEC.md` / `DESIGN.md` / `Plan.md`。

  ★ COMMON FAILURE TO AVOID ★
  - ❌ 一次跑完 8 phase → 必须一个一个来
  - ❌ 写 `pom.xml` / `src/...` 在 phase 1-6 → 必须 phase 7 才写
  - ❌ 写 `<cwd>/SPEC.md` 而不是 `<cwd>/.aethercode/sdd/<slug>/spec.md` → 必须严格路径
  - ❌ 跳过 constitution.md 直接写 spec.md → 必须按 1→2→4→6→7 顺序
  - ❌ 用大写文件名 `SPEC.md` → 必须小写
  - ❌ 在 phase 内部"我先看看其他文件" → 必须用 desktop 注入的 input file list

  Do not use for: 单行修改 / bug fix / "帮我加个测试" / "重构这个函数"。
displayNames:
  zh-Hans: "SDD 规格化开发"
  en: "SDD Spec-Driven Development"
---

# SDD — Spec-Driven Development (Per-Phase Handler Mode)

## 架构 (R317 起)

**单一 SDD skill 每次只跑一个 phase**。Phase 调度由 desktop `SddPhaseBar` 控制，agent 不跑完整 8 phase flow。

```
desktop 端 (SddPhaseBar)                     agent 端 (Mavis sdd skill)
─────────────────────                       ─────────────────────
[user 点 SDD toggle]                          
       ↓                                      
[desktop mkdir <slug>/ + phase-state.json]    
       ↓                                      
[user 输入 intent + Enter]                    
       ↓                                      
[chat: "[phase: 1] <intent>"] ──────────────→  [agent load sdd skill]
                                                       ↓
                                              [read phase-1-constitution.md]
                                                       ↓
                                              [write constitution.md]
                                                       ↓
[chat: "✅ 第 1 阶段完成 — 项目原则"] ←────────  [emit pause message]
       ↓                                      
[user 点 ✅ 接受]                             
       ↓                                      
[desktop update phase-state.json]             
       ↓                                      
[chat: "[phase: 2] continue"] ──────────────→  [agent read constitution.md]
                                                       ↓
                                              [read phase-2-specify.md]
                                                       ↓
                                              [write spec.md]
                                                       ↓
[chat: "✅ 第 2 阶段完成 — 需求分析"] ←────────  [emit pause message]
       ↓                                      
... (8 phase 同样循环) ...
       ↓                                      
[user 点 ✅ 第 8 phase 完成]                  
       ↓                                      
[chat: "🎉 SDD 流程完成"] ─────────────────→  [agent write convergence.json]
```

## desktop 派发给 agent 的消息格式

每次 phase 切换时，desktop 发到 chat 的 message 包含：

```
[sdd-task: <sdd-task-preset>, phase: <N>, action: <run|modify|skip>]

## Inputs (之前 phase 的输出文件，全部 read_file 加载)
- <cwd>/.aethercode/sdd/<sdd-task-preset>/constitution.md
- <cwd>/.aethercode/sdd/<sdd-task-preset>/spec.md
- ...

## 当前 phase 指令
<引用 phase-N reference 的核心指令>

## Output
<必须写到 <cwd>/.aethercode/sdd/<sdd-task-preset>/<phase-N-output>.md>
```

## SKILL.md per-phase decision tree

当 sdd skill 被调用时，按 `phase: <N>` 决定行为：

```
if phase == 1:
  - read references/phase-1-constitution.md
  - 写 constitution.md
  - 输出 pause message "第 1 阶段完成"
elif phase == 2:
  - read_file inputs.constitution.md
  - read references/phase-2-specify.md
  - 写 spec.md
  - 输出 pause message
elif phase == 3:
  - read_file inputs.spec.md
  - read references/phase-3-clarify.md
  - 写 clarify.json (或 skip sentinel)
  - 输出 pause message
... (4/5/6 类似)
elif phase == 7:
  - read_file inputs.tasks.md
  - read references/phase-7-implement.md
  - 写代码 + dev.log
  - 输出 pause message (含 task completion table)
elif phase == 8:
  - read_file 所有前序文件 + dev.log
  - read references/phase-8-converge.md
  - 写 convergence.json
  - 输出 "🎉 SDD 流程完成"
elif action == 'modify':
  - 留在当前 phase
  - 用 user feedback 重新生成当前 phase output
  - 输出 pause message (重跑)
elif action == 'skip':
  - 写 `{skipped: true, reason: 'user-opted-out'}` sentinel
  - 输出 pause message
```

## Output contract (per phase)

| Phase | Output file | 路径 |
|---|---|---|
| 1 (REQUIRED) | `constitution.md` | `<cwd>/.aethercode/sdd/<sdd-task-preset>/constitution.md` |
| 2 (REQUIRED) | `spec.md` | `<cwd>/.aethercode/sdd/<sdd-task-preset>/spec.md` |
| 3 (OPT) | `clarify.json` | `<cwd>/.aethercode/sdd/<sdd-task-preset>/clarify.json` |
| 4 (REQUIRED) | `design.md` | `<cwd>/.aethercode/sdd/<sdd-task-preset>/design.md` |
| 5 (OPT) | `analyze.json` | `<cwd>/.aethercode/sdd/<sdd-task-preset>/analyze.json` |
| 6 (REQUIRED) | `tasks.md` | `<cwd>/.aethercode/sdd/<sdd-task-preset>/tasks.md` |
| 7 (REQUIRED) | `dev.log` + 源代码 | `<cwd>/.aethercode/sdd/<sdd-task-preset>/dev.log` + `<cwd>/<...>` |
| 8 (OPT) | `convergence.json` | `<cwd>/.aethercode/sdd/<sdd-task-preset>/convergence.json` |

`<sdd-task-preset>` ≤10 ASCII chars, kebab-case, lowercase, 冲突 append `-2`/`-3`。
**所有文件名严格小写**。

## Pause message shape (per phase)

每 phase 写完 output 后输出：

```markdown
✅ **第 N 阶段完成 — <phase 中文 title>**

产物：`<abs-path>/<file>`
摘要：<一句话核心决定>

请回复：
  ✅ 继续下一阶段
  ✏️ 修改 <具体意见>
  ⏭️ 跳过下一阶段（仅对可选阶段生效）
```

最终完成：

```markdown
✅ **第 8 阶段完成 — 收敛验证**

产物：`<abs-path>/convergence.json`

🎉 **SDD 流程完成**
```

## References

- `references/phase-1-constitution.md` — phase 1 详细指令 (REQUIRED)
- `references/phase-2-specify.md` — phase 2 详细指令 (REQUIRED)
- `references/phase-3-clarify.md` — phase 3 详细指令 (OPTIONAL)
- `references/phase-4-plan.md` — phase 4 详细指令 (REQUIRED)
- `references/phase-5-analyze.md` — phase 5 详细指令 (OPTIONAL)
- `references/phase-6-tasks.md` — phase 6 详细指令 (REQUIRED)
- `references/phase-7-implement.md` — phase 7 详细指令 (REQUIRED)
- `references/phase-8-converge.md` — phase 8 详细指令 (OPTIONAL)
- `references/phase-protocol.md` — 完整 state machine + payload shape
- `references/templates/constitution-template.md` — phase 1 模板
- `references/templates/specify-template.md` — phase 2 模板
- `references/templates/plan-template.md` — phase 4 模板
- `references/templates/tasks-template.md` — phase 6 模板
- `references/templates/_fallback.md` — bundled templates 缺失时用
- `references/upstream-credits.md` — github/spec-kit attribution

## Failure recovery

- **phase-state.json 不存在** → 报错 "必须先由 desktop 启动 SDD run"
- **input file 不存在** → 报错 "phase 2 需要 constitution.md，先回 phase 1"
- **output file 写到错误路径** → 报错并 abort（违反 path 约定）
- **agent 一次跑多个 phase** → 检测到 output 文件数 > 1，abort
- **Templates 缺失** → 退回 `references/templates/_fallback.md`