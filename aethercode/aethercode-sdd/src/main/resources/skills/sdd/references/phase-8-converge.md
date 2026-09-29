# Phase 8 — converge (收敛验证, OPTIONAL)

## Phase role
Cross-check that implementation matches the spec: run validation + check FR coverage + check edge cases. Output ONE file: `convergence.json`.

## Trigger condition

This phase runs when:
- desktop sends `[sdd-task: <slug>, phase: 8, action: run]`
- User did NOT opt out of converge
- Required inputs: `spec.md` + `design.md` + `tasks.md` + `dev.log` + 所有源代码

## Inputs (MUST read_file)

1. **`<cwd>/.aethercode/sdd/<sdd-task-preset>/spec.md`** (FR-NN / AC-NN 列表)
2. **`<cwd>/.aethercode/sdd/<sdd-task-preset>/design.md`** (interface contracts)
3. **`<cwd>/.aethercode/sdd/<sdd-task-preset>/tasks.md`** (T-NN 任务列表)
4. **`<cwd>/.aethercode/sdd/<sdd-task-preset>/dev.log`** (per-task 实现记录)
5. **所有源代码文件** (tasks.md 中 files_to_create_or_modify 列表)

## Output (唯一)

**绝对路径**: `<cwd>/.aethercode/sdd/<sdd-task-preset>/convergence.json`

JSON shape:
```json
{
  "validation": {
    "tool": "mvn",
    "pass": 12,
    "fail": 0,
    "skipped": 0,
    "command": "mvn test"
  },
  "requirements_coverage": {
    "covered": ["FR-1", "FR-2"],
    "missing": ["FR-3"]
  },
  "edge_case_coverage": {
    "covered": ["empty array", "single element"],
    "missing": ["already sorted"]
  },
  "recommendation": "ready-to-merge|fix-X|extend-tests"
}
```

## Allowed tools

| Tool | Use |
|---|---|
| `bash` | `mvn test` / `pytest` 等 |
| `read_file` | 读所有 input files + 源代码 |
| `write_file` | 写 `convergence.json` |

## Forbidden behaviors

| ❌ Forbidden | Why |
|---|---|
| 写源代码（phase 7 已结束） | 不允许 implement |
| 修改任何 spec 文件 | spec 是 frozen |

## Pause message

```markdown
✅ **第 8 阶段完成 — 收敛验证**

产物：`<abs-path>/.aethercode/sdd/<sdd-task-preset>/convergence.json`

测试结果：mvn test 通过 X 个，失败 Z 个
需求覆盖：X / Y FR 覆盖
建议：<ready-to-merge / fix-X / extend-tests>

🎉 **SDD 流程完成**

所有产物位于：`<cwd>/.aethercode/sdd/<sdd-task-preset>/`
源代码位于：`<cwd>/`

<!-- choices:start
A: ✅ 确认完成并退出 SDD 模式 → action=approve
B: ✏️ 修改 — 在下方输入反馈后按 Ctrl/⌘+Enter → action=modify
C: ⏭️ 跳过收尾（SDD 已结束，源码未变化）→ action=skip
D: 🔁 重新跑收敛验证 → action=rerun
E: ✋ 暂停 → action=pause
choices:end -->
```

**写完 pause message 后立即 STOP**。SDD 流程结束。

## Action: modify

When `[phase: 8, action: modify]`:
1. Read existing `convergence.json`
2. Apply feedback（通常是 "重新跑 mvn test" / "增加 edge case 检查"）
3. Re-run validation, rewrite
5. Output pause message

## Action: skip

User opt-out → write `{skipped: true, reason: "user-opted-out"}`。
desktop 检测到 `phase-status: "skipped"` 后关闭 SddPhaseBar。

## Final completion

After pause + user 接受 / 不再继续 → desktop 把 SddPhaseBar 收起。
如果 desktop 没自动收起，agent 可以再发一条"🎉 SDD 流程完成"消息，但不需要重跑 phase。