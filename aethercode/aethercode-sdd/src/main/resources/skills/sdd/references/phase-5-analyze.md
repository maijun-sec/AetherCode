# Phase 5 — analyze (一致性分析, OPTIONAL)

## Phase role
Cross-check constitution + spec + design for gaps, conflicts, coverage holes. Output ONE file: `analyze.json`.

## Trigger condition

This phase runs when:
- desktop sends `[sdd-task: <slug>, phase: 5, action: run]`
- User did NOT opt out of analyze
- Required inputs: `constitution.md` + `spec.md` + `design.md`

## Inputs (MUST read_file)

1. **`<cwd>/.aethercode/sdd/<sdd-task-preset>/constitution.md`** (REQUIRED)
2. **`<cwd>/.aethercode/sdd/<sdd-task-preset>/spec.md`** (REQUIRED)
3. **`<cwd>/.aethercode/sdd/<sdd-task-preset>/design.md`** (REQUIRED — phase 4 输出)

如果任何缺失 → 报错。

## Output (唯一)

**绝对路径**: `<cwd>/.aethercode/sdd/<sdd-task-preset>/analyze.json`

JSON shape:
```json
{
  "items": [
    {
      "type": "gap|conflict|coverage",
      "severity": "high|medium|low",
      "location": "<指向 spec.md / design.md 的章节>",
      "recommendation": "<建议怎么改>"
    }
  ]
}
```

## Allowed tools

| Tool | Use |
|---|---|
| `bash` | 仅 `mkdir -p` |
| `read_file` | 读 3 个前序文件 |
| `write_file` | 写 `analyze.json` |
| `chat_llm` | 调 LLM 交叉检查 |

## Forbidden behaviors

| ❌ Forbidden | Why |
|---|---|
| 写源代码 | phase 7 才允许 |
| 大写文件名 | 必须小写 |
| 写到 `<cwd>/analyze.json` | 必须 `<cwd>/.aethercode/sdd/<slug>/analyze.json` |

## Pause message

```markdown
✅ **第 5 阶段完成 — 一致性分析**

产物：`<abs-path>/.aethercode/sdd/<sdd-task-preset>/analyze.json`
摘要：<一句话，如 "3 项 coverage 缺口，无冲突">

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

如有 severity=high 项，必须在 pause message 里 surface 给用户决策。

## Action: modify

When `[phase: 5, action: modify]`:
1. Read existing `analyze.json`
2. Apply feedback (通常是 "重新检查 FR-X 是否覆盖" / "增加 edge case Y")
3. Rewrite
4. Output pause message

## Action: skip

User opt-out → write `{skipped: true, reason: "user-opted-out"}`.

## Trigger to next phase

After pause + user ✅ → desktop 派发 phase 6 (tasks)。