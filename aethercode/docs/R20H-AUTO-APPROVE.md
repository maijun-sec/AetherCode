# R20-H: 自动规划 + 简单 plan 自动批准 (2026-08-06)

## 目标

让引擎能根据 plan 复杂度自动决定是否需要用户批准。简单 plan（≤3 步 + 无破坏性工具）引擎直接执行；复杂 plan 走原 R4 提示用户流程。

## 变更

### 新增类 (`aethercode-sdk/.../sdk/`)

- `PlanClassifier` — 分类器。`Verdict` 枚举：`TRIVIAL` / `NEEDS_APPROVAL`。
- `PlanClassifier.Step` (record) — SDK 层的最小步骤定义 (`title`)，避免 SDK 反向依赖 TUI。
- 分类规则（保守默认）：
  - 步数 ≤ `DEFAULT_MAX_STEPS` (3)
  - 任一步骤 title（lowercased）不含破坏性工具名（substring match, case-insensitive）
  - 默认破坏性工具集：`bash` / `shell` / `file_write` / `file_edit` / `file_delete` / `web_fetch` / `process_kill`
- `classify(List<Step>)` → `Verdict`
- `isTrivial(...)` 布尔便捷
- `rationale(...)` 解释为什么这样分类（用于 TUI 提示）
- 构造器允许自定义 `maxSteps` 和 `destructiveTools`

### `PlanPanel.approve()` 改造

- 加 `autoApprove` 字段（默认 false）+ `withAutoApprove(boolean)` 钩子
- approve 时调 PlanClassifier：trivial + autoApprove=true → 直接执行；否则保留原 R4 行为（显示 /approve / /reject 提示）
- 抽出共享 `runExecutor(approved)` helper（被 auto-approve 路径和显式 /approve 路径复用）

## 设计要点

- **SDK 不依赖 TUI** — `PlanClassifier.Step` 是 SDK 内的 record，不是 `StructuredPlan.Step` 的代理。TUI 在调用时适配。
- **保守默认** — 任何破坏性工具（哪怕只在一句话里出现）都触发 NEEDS_APPROVAL。这是 R20 阶段的正确选择：宁可让用户多按一次 /approve，也不能误执行 rm -rf。
- **可定制** — 构造器接受 `destructiveTools` Set，host 程序可以收紧或放宽。maxSteps 同理。

## 测试

新增 18 tests，0 regression：

| Test class | Tests |
|---|---|
| `PlanClassifierTest` | 18 (null / empty / 单步 / 3 步 / 4 步 / bash / file_write / 混合 / 大小写 / 自定义破坏集 / 自定义 maxSteps / null title / 一致性 / rationale / 不可变) |
| **合计** | **18** |

总测试数：**1368** (R20-G: 1350 → R20-H: 1368, +1.3%)

## 关键 pitfall

1. **SDK 不能依赖 TUI** — 起初 `PlanClassifier` 直接用 `StructuredPlan`，编译失败。引入 SDK 层 `PlanClassifier.Step` record，TUI 调用时适配。
2. **依赖方向错了** — `aethercode-sdk` 在 maven 依赖图里比 `aethercode-tui` 更靠下。R20-E 时也踩过这个坑（PlanExecutor 不能用 StructuredPlan）。这是 AetherCode 当前的硬约束，R20 后面的轮次要继续注意。

## 验证

```
$ mvn -B test          # BUILD SUCCESS, 1368 tests, 0 fail, 0 error
```

## 关联

- 备份目录：`docs/backups/r20h/`
- 上一轮：`docs/R20G-TASK-TREE.md`
- 路线图：`docs/R20-ROADMAP.md`
- 下一轮：R20-I Subagent 池 + 失败重试 + 指数退避
