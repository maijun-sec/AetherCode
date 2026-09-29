# Phase 7 — implement (执行实现, REQUIRED) ← 唯一可以写源代码的阶段

## Phase role
Execute `tasks.md` in dependency order. Write source code + `dev.log` log.

## Trigger condition

This phase runs when:
- desktop sends `[sdd-task: <slug>, phase: 7, action: run]`
- Required input: `tasks.md`

## Inputs (MUST read_file)

1. **`<cwd>/.aethercode/sdd/<sdd-task-preset>/tasks.md`** (REQUIRED — phase 6)
2. **`<cwd>/.aethercode/sdd/<sdd-task-preset>/design.md`** (helpful — interface contracts)
3. **`<cwd>/.aethercode/sdd/<sdd-task-preset>/spec.md`** (helpful — FR / AC)

## Output

| Output | 路径 |
|---|---|
| 源代码 + 项目骨架 | `<cwd>/<由 tasks.md 的 files_to_create_or_modify 决定>` |
| 任务日志 | `<cwd>/.aethercode/sdd/<sdd-task-preset>/dev.log` |

dev.log 格式:
```
[T-01] 2026-09-22T14:30:00 — 2026-09-22T14:31:23 — <summary>
[T-02] 2026-09-22T14:31:24 — ...
```

## Allowed tools

| Tool | Use |
|---|---|
| `bash` | `ls` / `mkdir` / `mvn` / `pytest` / `cargo test` 等 |
| `read_file` | 读 input files + 模板 |
| `write_file` | 写源代码 + dev.log |
| `chat_llm` | 调 LLM 写代码 |
| `edit` | 修代码 |

## Forbidden behaviors (absolute)

| ❌ Forbidden | Why |
|---|---|
| 修改 `constitution.md` / `spec.md` / `design.md` / `tasks.md` | spec 是 source of truth |
| 跳过 T-NN (用户没让跳就不许跳) | 必须按依赖全部执行 |
| 把源代码写到 `<cwd>/.aethercode/sdd/<slug>/` | 该目录只放 spec 文档 |
| 大写扩展名（`.JAVA`、`.PY`） | 必须正常后缀 |

## Pause message (在所有 T-NN 完成 + 测试通过后输出)

```markdown
✅ **第 7 阶段完成 — 执行实现**

产物：
- 源代码：`<cwd>/<path>`
- 任务日志：`<abs-path>/.aethercode/sdd/<sdd-task-preset>/dev.log`

完成任务：T-01 到 T-NN，共 NN 个
测试结果：mvn test 通过 X 个，失败 0 个

请回复：
  ✅ 继续下一阶段
  ✏️ 修改 <具体意见>
  ⏭️ 跳过下一阶段（仅对可选阶段生效）

<!-- choices:start
A: ✅ 接受实现 → action=approve
B: ✏️ 修改 — 在下方输入修改意见后按 Ctrl/⌘+Enter → action=modify
C: ⏭️ 跳过实现（agent 会标记为 skipped 但源码已落地）→ action=skip
D: 🔁 重新跑实现 → action=rerun
E: ✋ 暂停 → action=pause
choices:end -->
```

**写完 pause message 后立即 STOP**。

## Action: modify

When `[phase: 7, action: modify]`:
1. Read existing `dev.log` + 已写的代码文件
2. Apply user feedback（通常是 "T-03 的边界检查不对，改成..." / "增加 T-NN 处理 XX edge case"）
4. Re-execute 必要的 T-NN，append 到 `dev.log`
5. Output pause message

## Action: skip

User opt-out → write `dev.log` 一行 "用户已跳过实现阶段"，不写实际代码。

## Trigger to next phase

After pause + user ✅ → desktop 派发 phase 8 (converge)。