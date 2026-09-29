# Phase 1 — constitution (项目原则, REQUIRED)

## Phase role
Establish binding project governance principles. Output ONE file: `constitution.md`.

## Trigger condition

This phase runs when:
- desktop sends `[sdd-task: <slug>, phase: 1, action: run]`
- NO prior phase output files exist

## Inputs (Phase 1 has NO input files — it's the first)

None. Phase 1 is the entry point. The user intent is in the chat message that triggered this phase.

## Output (唯一)

**绝对路径**: `<cwd>/.aethercode/sdd/<sdd-task-preset>/constitution.md`

**绝对禁止写到**:
- `<cwd>/constitution.md` (cwd 根目录)
- `<cwd>/SPEC.md` / `<cwd>/DESIGN.md` 等散落位置
- `<cwd>/pom.xml` / `<cwd>/src/...` (源代码路径 — phase 7 才允许)

**严格小写**: `constitution.md` (NOT `Constitution.md` / `CONSTITUTION.md`)

## Template

Read `references/templates/constitution-template.md`. Fill 5 sections:
1. **Code Quality** — naming conventions, file size limits, lint rules
2. **Testing Standards** — coverage threshold (e.g. ≥80%), required test types, CI gate
3. **UX Consistency** — error handling, i18n, accessibility
4. **Performance & Reliability** — latency targets (e.g. p95 < 200ms), observability
5. **Security** — input validation, secrets management, dependency audit

每条原则**可验证**（不是"高质量"这种空话，而是"命名遵循 X 模式"、"测试覆盖率 ≥ 80%"）。

## Allowed tools

| Tool | Use |
|---|---|
| `bash` | `mkdir -p <cwd>/.aethercode/sdd/<slug>` (如果 dir 不存在) |
| `read_file` | 读 `references/templates/constitution-template.md` |
| `write_file` | 写 `<cwd>/.aethercode/sdd/<slug>/constitution.md` |
| `chat_llm` | 调 LLM 生成内容 |

## Forbidden behaviors (absolute)

| ❌ Forbidden | Why |
|---|---|
| 写源代码（`.java`/`.py`/`.ts`/`.xml`/`.gradle`/`.toml`） | phase 7 才允许 |
| 创建项目骨架（`pom.xml`、`mkdir src/main/java`、`git init`） | phase 7 才允许 |
| 写 `SPEC.md` / `DESIGN.md` / `spec.md` / `plan.md` | 越权写其他 phase 产物 |
| 写到 `<cwd>/constitution.md` (cwd 根目录) | 必须 `.aethercode/sdd/<slug>/constitution.md` |
| 用大写文件名 (`Constitution.md`) | 必须小写 |
| 一次跑多个 phase | 必须只跑 phase 1，然后 HARD PAUSE |
| 跳过此 phase（即使数据看起来"已经有部分"） | 必须先 phase 1 |

## Pause message (MUST output exactly this format)

```markdown
✅ **第 1 阶段完成 — 项目原则**

产物：`<abs-path>/.aethercode/sdd/<sdd-task-preset>/constitution.md`
摘要：<一句话核心原则，如 "代码可读性 / 测试覆盖率 ≥80% / p95 < 200ms">

请回复：
  ✅ 继续下一阶段
  ✏️ 修改 <具体意见>
  ⏭️ 跳过下一阶段（仅对可选阶段生效）

<!-- choices:start
A: ✅ 接受并进入下一阶段 → action=approve
B: ✏️ 修改当前阶段 — 在下方输入具体意见后按 Ctrl/⌘+Enter → action=modify
C: ⏭️ 跳过下一阶段（仅当下一阶段是 OPTIONAL 才生效，否则 agent 会反问）→ action=skip
D: 🔁 重新生成当前阶段 → action=rerun
E: ✋ 暂停（agent 不动，等用户进一步指示）→ action=pause
choices:end -->
```

**写完 pause message 后立即 STOP**。不许调任何 tool。等用户回复。

## User reply mapping

- User sends `✅` / `继续` / `next` / `ok` → desktop 派发 phase 2
- User sends `✏️ <text>` → desktop 派发 phase 1 modify (你收到的是 `[phase: 1, action: modify]`)

- User clicks lettered button A/B/C/D/E in the SddPhaseBar (R322) — desktop 派发对应 action
- User types free text into "其他" textarea and presses Ctrl/⌘+Enter → desktop 派发 modify
- User sends `⏭️` → 反问: "phase 1 是 REQUIRED，确认跳过吗？回复 yes 才跳"

## Action: modify

When desktop sends `[sdd-task: <slug>, phase: 1, action: modify]`:
1. Read existing `<cwd>/.aethercode/sdd/<slug>/constitution.md` (如果存在)
2. Apply user's feedback `<text>`
3. Rewrite the file (overwrite)
4. Output pause message (重跑)

## Action: skip (rare)

When desktop sends `[phase: 1, action: skip]`:
1. Write `<cwd>/.aethercode/sdd/<slug>/constitution.md: {"skipped": true, "reason": "user-opted-out"}` (or similar sentinel)
2. Output pause message

## Trigger to next phase

After pause, user sends ✅ → desktop 派发 `[phase: 2, action: run]`。
本 skill 这次不继续。"stop" is the end.