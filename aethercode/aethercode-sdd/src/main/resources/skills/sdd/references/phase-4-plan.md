# Phase 4 — plan (详细设计, REQUIRED)

## Phase role
Write the implementation plan. Output ONE file: `design.md` (NOT `plan.md`).

## Trigger condition

This phase runs when:
- desktop sends `[sdd-task: <slug>, phase: 4, action: run]`
- Required input: `constitution.md` + `spec.md`

## Inputs (MUST read_file)

1. **`<cwd>/.aethercode/sdd/<sdd-task-preset>/constitution.md`** (REQUIRED — phase 1)
2. **`<cwd>/.aethercode/sdd/<sdd-task-preset>/spec.md`** (REQUIRED — phase 2)
3. **`<cwd>/.aethercode/sdd/<sdd-task-preset>/clarify.json`** (OPTIONAL — phase 3 输出，含 user 答案)

如果任何 REQUIRED input 缺失 → 报错 "phase X 没跑，先跑 phase X"。

## Output (唯一)

**绝对路径**: `<cwd>/.aethercode/sdd/<sdd-task-preset>/design.md`

**严格小写**: `design.md` (NOT `plan.md` / `DESIGN.md` / `Design.md`)

**绝对禁止写到**:
- `<cwd>/design.md` (cwd 根目录)
- `<cwd>/plan.md` (历史别名)
- 任何其他 phase 的输出路径

## Template

Read `references/templates/plan-template.md`. Fill sections:
1. **Architecture** — 整体架构 (单模块 vs 多模块、接口/实现分离、泛型策略)
2. **Module Breakdown** — 模块/包结构 + 职责
3. **Interface Contracts** — 公开 API (接口名 + 签名 + 语义)
4. **Data Model** — 数据结构 (数组类型、泛型、辅助类)
5. **Risks** — 风险 + 缓解
6. **Constitution Check** — 与 constitution 5 大原则对齐情况

## Allowed tools

| Tool | Use |
|---|---|
| `bash` | 仅 `mkdir -p` |
| `read_file` | 读所有 input files + `references/templates/plan-template.md` |
| `write_file` | 写 `design.md` |
| `chat_llm` | 调 LLM 生成内容 |

## Forbidden behaviors

| ❌ Forbidden | Why |
|---|---|
| 写源代码（`pom.xml` / `src/main/java/...`） | phase 7 才允许 |
| 写 `tasks.md` | 越权写 phase 6 产物 |
| 写 `plan.md` (历史别名) | 必须 `design.md` |
| 大写文件名 (`DESIGN.md`) | 必须小写 |
| 写到 `<cwd>/design.md` | 必须 `<cwd>/.aethercode/sdd/<slug>/design.md` |
| 跳过此 phase | 必须 phase 1-6 顺序跑 |

## Pause message

```markdown
✅ **第 4 阶段完成 — 详细设计**

产物：`<abs-path>/.aethercode/sdd/<sdd-task-preset>/design.md`
摘要：<一句话核心架构，如 "Maven 多模块 + 接口 SortAlgorithm<T> + 实现类分离 + SortUtils 静态门面">

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

When `[phase: 4, action: modify]`:
1. Read existing `design.md`
2. Apply user feedback
3. Rewrite (overwrite)
4. Output pause message

## Action: skip

Phase 4 is REQUIRED → 反问确认。

## Trigger to next phase

After pause + user ✅ → desktop 派发 phase 5 (analyze)。