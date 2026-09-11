# R20-J: 监督定时器 + Checkpoint + 恢复 (2026-08-06)

## 目标

10 小时长任务最后一公里 — 监督（发现 hang）+ 持久化（崩溃可恢复）。

## 变更

### 新增类

- `aethercode-sdk/.../sdk/Watchdog.java` — 后台 watchdog，60s 无事件触发回调
  - `DEFAULT_POLL_MS = 5000` / `DEFAULT_TIMEOUT_MS = 60000`
  - `TimeoutHandler` 接口（接收 silenceMs）
  - `start()` / `stop()` / `kick()` / `isTripped()` / `close()`
  - 后台线程 daemon 化；kick 是 non-blocking
  - 边 `tripped.set(true)` 边调 handler 一次

- `aethercode-tasks/.../tasks/Checkpoint.java` — JSONL 引擎状态序列化
  - `Checkpoint.of(taskId, transcript)` 构造内存对象
  - `write(path)` 写到文件（含 meta line + 每条 message 一行）
  - `load(path)` 读回 `Checkpoint`
  - `list(dir)` 列出目录里所有 `*.jsonl` 的 task ID
  - 文件格式：`{"kind":"meta",...}` + N× `{"kind":"message","role":...}`
  - tool_use 用 `role: "tool_use"` 区分（assistant message 里嵌入 tool use 块）

## 设计要点

- **Watchdog 极简** — 不搞状态机，就是 polling + 比较。`kick()` 让 event handler 重置计时器。
- **Checkpoint 跟 PersistentTaskRegistry 风格统一** — 都用 JSONL，escape 规则一样（`\"` `\\` `\n` `\t`）。
- **tool_use 不复用 Role.TOOL** — 实际 `Role` 枚举只有 `USER/ASSISTANT/SYSTEM/TOOL_RESULT`，tool use 是嵌在 ASSISTANT 消息里的 ContentBlock。Checkpoint 用 `role: "tool_use"` 区分。
- **可插拔 Sleeper 复用 R20-I 经验** — Watchdog 的 `TimeoutHandler` 接口保持极简，方便未来 TUI 接入。

## 测试

新增 21 tests，0 regression：

| Test class | Tests |
|---|---|
| `WatchdogTest` | 10 (null 参数 / 边界 / idempotent / 默认常量) |
| `CheckpointTest` | 11 (round-trip / 空 / 父目录 / 覆盖 / 缺失文件 / 无 meta / 转义 / tool_use / list / 空目录 / 不存在目录) |
| **合计** | **21** |

总测试数：**1413** (R20-I: 1392 → R20-J: 1413, +1.5%)

## 关键 pitfall

1. **Role 没有 TOOL** — 起初用 `Role.TOOL`，编译失败。实际只有 `USER/ASSISTANT/SYSTEM/TOOL_RESULT`；tool use 是 ASSISTANT 消息里的 ContentBlock.ToolUseBlock。改用 `role: "tool_use"` 字符串区分。
2. **Watchdog 缺 import** — 用 `AtomicBoolean` 但漏 `import java.util.concurrent.atomic.AtomicBoolean`。补上。

## 验证

```
$ mvn -B test          # BUILD SUCCESS, 1413 tests, 0 fail, 0 error
```

## 关联

- 备份目录：`docs/backups/r20j/`
- 上一轮：`docs/R20I-RETRY-POOL.md`
- 路线图：`docs/R20-ROADMAP.md`
- R20 整体回顾：`docs/R20-RETROSPECTIVE.md`
