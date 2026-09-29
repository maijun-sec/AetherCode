# Phase 2 — specify (需求分析, REQUIRED)

## Phase role
Write the feature specification. Output ONE file: `spec.md`.

## Trigger condition

This phase runs when:
- desktop sends `[sdd-task: <slug>, phase: 2, action: run]`
- `<cwd>/.aethercode/sdd/<slug>/constitution.md` exists (REQUIRED input)

## Inputs (MUST read_file these before writing)

1. **`<cwd>/.aethercode/sdd/<sdd-task-preset>/constitution.md`** — 项目原则 (phase 1 输出)
2. **User intent** — 在 chat message 中 (desktop 注入)

如果 constitution.md 不存在 → 报错 "phase 1 没跑，先跑 phase 1"。**不写 spec.md**。

## Output (唯一)

**绝对路径**: `<cwd>/.aethercode/sdd/<sdd-task-preset>/spec.md`

**绝对禁止写到**:
- `<cwd>/spec.md` (cwd 根目录)
- `<cwd>/SPEC.md` (大写)
- 任何其他 phase 的输出路径

**严格小写**: `spec.md` (NOT `Spec.md` / `SPEC.md`)

## Template

Read `references/templates/specify-template.md`. Fill sections:
1. **Summary** — 1-2 句话说 feature
2. **Functional Requirements** — FR-NN 列表，每条可验证
3. **Non-Functional Requirements** — NFR-NN 列表（含可测量 target）
4. **Acceptance Criteria** — AC-NN 列表（Given/When/Then）
5. **Out of Scope** — 明确不做什么

每条 FR/NFR/AC 引用 constitution.md 的对应原则。

## Allowed tools

| Tool | Use |
|---|---|
| `bash` | 仅 `mkdir -p` (子目录) |
| `read_file` | 读 `constitution.md` + `references/templates/specify-template.md` |
| `write_file` | 写 `spec.md` |
| `chat_llm` | 调 LLM 生成内容 |

## Forbidden behaviors (absolute)

| ❌ Forbidden | Why |
|---|---|
| 写源代码 / 创建项目骨架（`pom.xml` / `src/main/java`） | phase 7 才允许 |
| 写 `design.md` / `tasks.md` | 越权写 phase 4/6 产物 |
| 写 `SPEC.md` (大写) | 必须 `spec.md` 小写 |
| 写到 `<cwd>/spec.md` | 必须 `<cwd>/.aethercode/sdd/<slug>/spec.md` |
| 跳过此 phase 直接写 tasks | 必须 phase 1-6 顺序跑 |
| 一次跑多个 phase | 必须只跑 phase 2，然后 HARD PAUSE |

## Pause message

```markdown
✅ **第 2 阶段完成 — 需求分析**

产物：`<abs-path>/.aethercode/sdd/<sdd-task-preset>/spec.md`
摘要：<一句话核心需求，如 "5 种排序算法 × 3 种数组类型 = 15 个功能点 + p95 < 10ms">

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

**写完 pause message 后立即 STOP**。

## Action: modify

When desktop sends `[phase: 2, action: modify]`:
1. Read existing `<cwd>/.aethercode/sdd/<slug>/spec.md`
2. Apply user feedback
3. Rewrite (overwrite)
4. Output pause message

## Action: skip

Phase 2 is REQUIRED → 反问确认。

## Trigger to next phase

After pause + user ✅ → desktop 派发 phase 3 (clarify)。