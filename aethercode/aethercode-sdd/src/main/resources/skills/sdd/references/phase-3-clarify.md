# Phase 3 — clarify (需求澄清, OPTIONAL)

## Phase role
Identify 3–7 ambiguities in `spec.md` that need user resolution before plan. Output ONE file: `clarify.json`.

## Trigger condition

This phase runs when:
- desktop sends `[sdd-task: <slug>, phase: 3, action: run]`
- User did NOT opt out of clarify in initial prompt
- Required input: `<cwd>/.aethercode/sdd/<slug>/spec.md`

## Inputs (MUST read_file)

1. **`<cwd>/.aethercode/sdd/<sdd-task-preset>/spec.md`** (REQUIRED — phase 2 输出)
2. **`<cwd>/.aethercode/sdd/<sdd-task-preset>/constitution.md`** (helpful for context)

如果 spec.md 不存在 → 报错 "phase 2 没跑，先跑 phase 2"。

## Output (唯一)

**绝对路径**: `<cwd>/.aethercode/sdd/<sdd-task-preset>/clarify.json`

JSON shape:
```json
[
  {"id": "C-1", "question": "...", "why_it_matters": "...", "blocking": true}
]
```

**严格小写**: `clarify.json` (NOT `Clarify.json` / `CLARIFY.JSON`)

## Allowed tools

| Tool | Use |
|---|---|
| `bash` | 仅 `mkdir -p` |
| `read_file` | 读 `spec.md` + 可选 `constitution.md` |
| `write_file` | 写 `clarify.json` |
| `chat_llm` | 调 LLM 识别歧义点 |

## Forbidden behaviors

| ❌ Forbidden | Why |
|---|---|
| 写源代码 / 创建项目骨架 | phase 7 才允许 |
| 写 `design.md` / `tasks.md` | 越权写其他 phase |
| 大写文件名 (`Clarify.json`) | 必须小写 |
| 写到 `<cwd>/clarify.json` (cwd 根目录) | 必须 `<cwd>/.aethercode/sdd/<slug>/clarify.json` |
| 跳过此 phase (用户最初说跳过除外) | 必须写 `{skipped: true}` sentinel |

## Pause message

```markdown
✅ **第 3 阶段完成 — 需求澄清**

产物：`<abs-path>/.aethercode/sdd/<sdd-task-preset>/clarify.json`
摘要：<一句话，如 "识别 5 个歧义点，需用户澄清（性能 target、算法 stability、并发、排序方向、错误处理）">

请逐条回答以上问题。回答后回复 ✅ 继续下一阶段 / ✏️ 修改 spec.md 的第 N 节。

<!-- choices:start
A: ✅ 接受澄清问题 → action=approve
B: ✏️ 修改 — 在下方回答问题 / 提意见后按 Ctrl/⌘+Enter → action=modify
C: ⏭️ 跳过澄清阶段（OPTIONAL）→ action=skip
D: 🔁 重新生成澄清问题 → action=rerun
E: ✋ 暂停（等用户进一步指示）→ action=pause
choices:end -->
```

**写完 pause message 后立即 STOP**。

如果用户最初说跳过此 phase：

```markdown
⏭️ **第 3 阶段 — 需求澄清（可选）** — 已跳过

如需执行此阶段，回复 "跑 clarify"。否则回复 ✅ 继续下一阶段。
```

## Action: modify

When `[phase: 3, action: modify]`:
1. Read existing `clarify.json`
2. Apply user feedback (通常是 "spec.md 第 N 节改成 ...")
3. Rewrite `clarify.json` (and optionally modify spec.md if feedback says so)
4. Output pause message

## Action: skip

User says "跳过 clarify" 在最初 prompt → 写 sentinel:
```json
{"skipped": true, "reason": "user-opted-out"}
```

User 在 PAUSE 3 说 `⏭️` → 同上。

## Trigger to next phase

After pause + user ✅ → desktop 派发 phase 4 (plan)。