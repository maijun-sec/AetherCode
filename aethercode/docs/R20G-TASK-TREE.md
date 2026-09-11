# R20-G: 任务树可视化 (2026-08-06)

## 目标

把 TUI 的 `renderTasksPanel()` 从 R17 的扁平列表升级成 box-drawing 树（parent/child 嵌套 + elapsed 计时）。

## 变更

### 新增类 (`aethercode-tui/.../tui/`)

- `TaskTreeRenderer` — 纯渲染器（无 I/O），用 box-drawing 字符（├── │ └──）画嵌套树。`render(TaskRegistry)` 返回完整字符串。`MAX_DEPTH=8` 防止恶意/意外深链死循环。
- 静态方法 `formatElapsed(Task)` — 计时格式化：
  - `< 1s` → `Nms` (e.g. `500ms`)
  - `< 60s` → `N.Ns` (e.g. `5.4s`)
  - `>= 60s` → `NmSSs` (e.g. `5m30s`)
- Running 任务用 `now - createdAtMs`；terminal 状态用 `endedAtMs - createdAtMs`。

### `ReplApp.renderTasksPanel()` 改造

从 ~60 行内联渲染逻辑简化为 1 行 delegate：

```java
private String renderTasksPanel() {
    return TaskTreeRenderer.render(org.aethercode.tasks.TaskRegistry.instance());
}
```

## 输出格式

```
Tasks (5)
  ├── ▶ u-abc user  summarise the README   1.4s
  │   ├── ✓ a-def agent  read auth/Session  230ms
  │   │   └── ✗ a-ghi agent  write tests    failed
  │   └── ▶ a-jkl agent  write tests       running
  └── ✓ u-mno user  done                    5.2s
```

每行：状态 icon + 任务 id + 类型 + 截断描述 + elapsed。根任务无连接器，嵌套任务用 `├──` / `└──`；子任务的 prefix 携带 `│   `（后续有兄弟节点）或 `    `（父节点是末位）。

## 测试

新增 19 tests，0 regression：

| Test class | Tests |
|---|---|
| `TaskTreeRendererTest` | 19 (空 / 单根 / 双根 / 连接器 / 末位 / 兄弟节点 / 深度上限 / 状态 icon / elapsed / 描述截断 / 换行处理 / null 仓库) |
| **合计** | **19** |

总测试数：**1350** (R20-F: 1331 → R20-G: 1350, +1.4%)

## 关键 pitfall

1. **单子节点无 ├──** — 起初测试 `render_rootWithChild_usesBoxDrawingConnectors` 期望 1 个 root + 1 child 同时出现 `├──` 和 `└──`。但单子节点直接是末位，只用 `└──`。改成 2 个子节点测试 (├── + └──) 或拆成 2 个独立测试。
2. **`│` 只在有后续兄弟节点时出现** — 起初 `render_deepNesting_carriesPrefix` 用单链 a→b→c→d 测 `│`，但单链每层都是末位，无 `│`。改成 root 有 2 个子节点，每个子节点各自有 grandchild — 这时第一个子节点需要 `│` 前缀。修复后过。
3. **Task 不接受 createdAtMs=0** — `formatElapsed_msRange` 测试用 `0L` 作 createdAtMs 抛 `IllegalArgumentException`。改用 `now - 500` 真实时间戳。
4. **aethercode-sdk 没装到本地 m2** — `mvn -pl aethercode-tui test` 单跑不构建依赖，需要 `mvn -B -pl aethercode-tui -am test` (reactor build) 或先 `mvn -pl aethercode-sdk install -DskipTests`。

## 验证

```
$ mvn -B -pl aethercode-sdk install -DskipTests
$ mvn -B test          # BUILD SUCCESS, 1350 tests, 0 fail, 0 error
```

## 关联

- 备份目录：`docs/backups/r20g/`
- 上一轮：`docs/R20F-SLIDING-WINDOW-COMPACTOR.md`
- 路线图：`docs/R20-ROADMAP.md`
- 下一轮：R20-H 自动规划 + 简单 plan 自动批准
