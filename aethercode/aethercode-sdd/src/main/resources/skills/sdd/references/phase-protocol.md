# SDD Phase Protocol

The complete wire-protocol reference for the SDD skill. SKILL.md describes user-facing behavior; this file describes the strict contract each SDD run follows.

## State machine

```
IDLE
  ↓ user message contains SDD keyword OR /sdd command
INIT (derive <sdd-task-preset>, create dir, do PRE-FLIGHT)
  ↓
PHASE_1_constitution ──read references/phase-1-constitution.md──write──> PAUSE_1
PHASE_2_specify ──read references/phase-2-specify.md──write──> PAUSE_2
PHASE_3_clarify [opt] ──read references/phase-3-clarify.md──write──> PAUSE_3
PHASE_4_plan ──read references/phase-4-plan.md──write──> PAUSE_4
PHASE_5_analyze [opt] ──read references/phase-5-analyze.md──write──> PAUSE_5
PHASE_6_tasks ──read references/phase-6-tasks.md──write──> PAUSE_6
PHASE_7_implement ──read references/phase-7-implement.md──exec──> PAUSE_7
PHASE_8_converge [opt] ──read references/phase-8-converge.md──write──> PAUSE_8
DONE
```

## Required vs Optional phases

| # | Phase | Required? | User can skip via |
|---|---|---|---|
| 1 | constitution | REQUIRED | cannot skip — 反问确认 |
| 2 | specify | REQUIRED | cannot skip — 反问确认 |
| 3 | clarify | OPTIONAL | "跳过 clarify" in initial prompt OR `⏭️` at PAUSE_2 |
| 4 | plan | REQUIRED | cannot skip — 反问确认 |
| 5 | analyze | OPTIONAL | "跳过 analyze" in initial prompt OR `⏭️` at PAUSE_4 |
| 6 | tasks | REQUIRED | cannot skip — 反问确认 |
| 7 | implement | REQUIRED | cannot skip — 反问确认 |
| 8 | converge | OPTIONAL | "跳过 converge" in initial prompt OR `⏭️` at PAUSE_7 |

## Per-phase artifact

| Phase | File | Created on |
|---|---|---|
| 1 | `constitution.md` | first run |
| 2 | `spec.md` | first run |
| 3 | `clarify.json` OR `{skipped: true}` sentinel | if user opts in |
| 4 | `design.md` | first run |
| 5 | `analyze.json` OR `{skipped: true}` sentinel | if user opts in |
| 6 | `tasks.md` | first run |
| 7 | `dev.log` + 源代码文件 | first run |
| 8 | `convergence.json` OR `{skipped: true}` sentinel | if user opts in |

All paths: `<cwd>/.aethercode/sdd/<sdd-task-preset>/<file>` (lowercase filenames only).

## Pause message shape (严格遵守)

**Required phase completion**:
```markdown
✅ **第 N 阶段完成 — <phase 中文 title>**

产物：`<abs-path>/.aethercode/sdd/<sdd-task-preset>/<file>`
摘要：<一句话核心决定>

请回复：
  ✅ 继续下一阶段
  ✏️ 修改 <具体意见>
  ⏭️ 跳过下一阶段（仅对可选阶段生效）
```

**Optional phase completion (actually ran)**:
```markdown
✅ **第 N 阶段完成 — <phase 中文 title>**

产物：`<abs-path>/.aethercode/sdd/<sdd-task-preset>/<file>`
摘要：<一句话核心决定>

请回复：
  ✅ 继续下一阶段
  ✏️ 修改 <具体意见>
  ⏭️ 跳过下一阶段
```

**Optional phase skipped (initial prompt opt-out)**:
```markdown
⏭️ **第 N 阶段 — <phase 中文 title>（可选）** — 已跳过

如需执行此阶段，回复 "跑 <phase>"。否则回复 ✅ 继续下一阶段。
```

**Final completion (after phase 8 or last required)**:
```markdown
✅ **第 N 阶段完成 — <phase 中文 title>**

产物：`<abs-path>/.aethercode/sdd/<sdd-task-preset>/<file>`

🎉 **SDD 流程完成**

所有产物位于：`<cwd>/.aethercode/sdd/<sdd-task-preset>/`
源代码位于：`<cwd>/`
```

## User reply mapping (严格遵守)

| User reply | Behavior |
|---|---|
| `✅` / `继续` / `next` / `ok` | Advance to next phase |
| `✏️ <text>` / `改 <text>` / `修改 <text>` | **Re-run CURRENT phase** with `<text>` as feedback |
| `⏭️` / `跳过` (on required phase) | **DO NOT skip** — ask "这是必需阶段，确认跳过吗？回复 yes 才跳" |
| `⏭️` / `跳过` (on optional phase) | Write `{skipped: true, reason: 'user-opted-out'}` sentinel, advance |
| `abort` / `取消` / `quit` | Write `abort.md`, exit cleanly |
| `status` / `进度` | Report completed phases + current phase |
| `跑 <phase>` (e.g. `跑 clarify`) | Run a previously skipped optional phase |
| Other text | Treat as feedback for CURRENT phase, re-run it |

## Failure recovery

- **Pre-flight 发现 cwd 散落旧文件**（SPEC.md / DESIGN.md / pom.xml 等）→ 告诉用户"检测到散落旧文件，将忽略"，不删除，按新 run 写到 `.aethercode/sdd/<sdd-task-preset>/`
- **`<sdd-task-preset>/constitution.md` 存在** → 询问用户：覆盖重跑？续跑？abort？
- **Phase 7 implement 跑不下去**（测试失败 / build 错）→ 写 dev.log，不许自动回滚 spec。把错误 surface 给用户，等用户决策
- **User 中途说 "skip everything"** → 写 `abort.md`（不是 convergence.json），记下 abort 原因
- **User 在 phase 1 之后改 intent** → abort 当前 run，重启 SDD
- **Templates 缺失 / 损坏** → 用 `references/templates/_fallback.md`，按 fallback 章节标题写

## File naming rules (must be exactly these)

| Allowed | NOT allowed |
|---|---|
| `constitution.md` | `Constitution.md`, `CONSTITUTION.md` |
| `spec.md` | `SPEC.md`, `Spec.md` |
| `clarify.json` | `Clarify.json`, `CLARIFY.JSON` |
| `design.md` | `DESIGN.md`, `Design.md`, `plan.md` |
| `analyze.json` | `Analyze.json`, `ANALYZE.JSON` |
| `tasks.md` | `Tasks.md`, `TASKS.md` |
| `dev.log` | `DEV.LOG`, `Dev.log` |
| `convergence.json` | `Convergence.json`, `CONVERGENCE.JSON` |
| `.java` | `.JAVA`, `.Java` |
| `<sdd-task-preset>/` (lowercase) | `<SddTaskPreset>/`, `SddTaskPreset/` |

## Slug derivation rules

`<sdd-task-preset>` is derived from user intent:

- ≤10 ASCII chars
- kebab-case, lowercase
- alphanumeric only (a-z, 0-9, hyphen)
- conflicts append `-2`/`-3` (e.g. `java-maven-2` if `java-maven` exists)
- empty intent → `sd-<timestamp36>`

Examples:
- "Build a java maven project with 5 sorting algorithms" → `java-maven`
- "Add user authentication" → `user-auth`
- "Fix the OAuth bug" → `fix-oauth-bug`

The slug is fixed at phase 1 and **never changes** during a run.