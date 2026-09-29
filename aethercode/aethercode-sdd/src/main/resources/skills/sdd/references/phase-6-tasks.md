# Phase 6 — tasks (任务分析, REQUIRED)

## Phase role
Decompose `design.md` into ordered executable tasks. Output ONE file: `tasks.md`.

## Trigger condition

This phase runs when:
- desktop sends `[sdd-task: <slug>, phase: 6, action: run]`
- Required input: `design.md`

## Inputs (MUST read_file)

1. **`<cwd>/.aethercode/sdd/<sdd-task-preset>/design.md`** (REQUIRED — phase 4)
2. **`<cwd>/.aethercode/sdd/<sdd-task-preset>/spec.md`** (helpful for FR coverage)

## Output (唯一)

**绝对路径**: `<cwd>/.aethercode/sdd/<sdd-task-preset>/tasks.md`

每条任务格式:
```markdown
## T-NN: <title>
- depends_on: (none / [T-MM])
- files_to_create_or_modify: <list>
- acceptance_check: <verifiable>
```

## Allowed tools

| Tool | Use |
|---|---|
| `bash` | 仅 `mkdir -p` |
| `read_file` | 读 input files + `references/templates/tasks-template.md` |
| `write_file` | 写 `tasks.md` |
| `chat_llm` | 调 LLM 拆任务 |

## Forbidden behaviors

| ❌ Forbidden | Why |
|---|---|
| 写源代码 / 创建项目骨架 | phase 7 才允许 |
| 大写文件名 | 必须小写 |
| 写到 `<cwd>/tasks.md` | 必须 `<cwd>/.aethercode/sdd/<slug>/tasks.md` |
| 跳过此 phase | 必须 phase 1-6 顺序跑 |

## Pause message

```markdown
✅ **第 6 阶段完成 — 任务分析**

产物：`<abs-path>/.aethercode/sdd/<sdd-task-preset>/tasks.md`
摘要：<一句话，如 "15 个 T-NN 任务：5 算法 × 3 数组类型 = 15 实现 + 1 单元测试 + 1 mvn test">

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

When `[phase: 6, action: modify]`:
1. Read existing `tasks.md`
2. Apply feedback (通常是 "T-03 拆细" / "增加 T-NN 测试覆盖")
3. Rewrite
4. Output pause message

## Action: skip

Phase 6 is REQUIRED → 反问确认。

## Trigger to next phase

After pause + user ✅ → desktop 派发 phase 7 (implement)。