# R20-D: 持久化 TaskRegistry (2026-08-06)

## 目标

让 TaskRegistry 不再是「进程死了就全丢」—— 每次 create / updateStatus 都写到 JSONL 文件，崩溃后能 `restore()` 重建。

## 变更

### 新增类

- `PersistentTaskRegistry` — 包装 `TaskRegistry`（组合，不是继承，因为 `TaskRegistry` 是 final），加上 JSONL 文件追加 + 重建功能。同一 API：`create` / `updateStatus` / `get` / `list` / `listChildren` / `onChange`。静态方法 `open(path)`（新建或打开）和 `restore(path)`（从文件恢复）。
- `JsonCodec`（内部类）— 手写 JSON 序列化（不引 Jackson）。`parseCreate` / `parseUpdate` / `renderCreate` / `renderUpdate`。支持字符串转义 (`\"` / `\\` / `\n` / `\t` / `\r`) 和 `\u00xx` 控制字符。

### `TaskRegistry` 改动

- 构造器从 `private` 改 `package-private`（让 `PersistentTaskRegistry` 能 new 出来）。
- 加 3 个 package-private 方法：`tasksPut(Task)` / `tasksGet(String)` / `notifyDirect(Task)`，让持久化包装能直接操作 map 而不触发 listener（restore 时是批量回放，不应触发 N 次 listener）。

### JSONL 文件格式

每行一个 JSON 对象，两个 op：

```json
{"op":"create","ts":1722938400123,"id":"u-abc12345","type":"USER",
 "status":"PENDING","description":"...","parent":null,
 "createdAtMs":1722938400123,"endedAtMs":0}
{"op":"update","ts":1722938400456,"id":"u-abc12345","status":"RUNNING",
 "endedAtMs":0}
```

写时 append 模式 (StandardOpenOption.CREATE | APPEND)；读时两遍扫描（先 create 注入 map，再 update 应用状态变化）。

## 测试

新增 11 tests，0 regression：

| Test class | Tests |
|---|---|
| `PersistentTaskRegistryTest` | 11 (open 父目录/create 写一行/update 写一行/restore 重建/listener 触发/容错/空文件/空行/terminal sticky/父子链接/file getter) |
| **合计** | **11** |

总测试数：**1306** (R20-C: 1295 → R20-D: 1306, +0.8%)

## 关键 pitfall

1. **TaskRegistry 是 final + 私有构造器** — 起初想继承，被迫改成组合 (`PersistentTaskRegistry` 持有一个 `TaskRegistry delegate`)。需要给 `TaskRegistry` 加 package-private 钩子 (`tasksPut` / `tasksGet` / `notifyDirect`) 让 wrapper 能直接操作 map 不触发 listener。
2. **测试顺序假设** — 写测试时以为 `list().get(0)` 是 user root task，agent task 的 parent 写了 null 而不是 root.id()。修复后用 `listChildren(root.id())` 显式查。
3. **JSON 转义反向** — 写时 escape `\n` → `\\n`，读时 `\\n` → `\n`。手写 regex `"key":"((?:[^"\\\\]|\\\\.)*)"` 支持 `\\.` 任意转义字符。
4. **JSONL 写并发** — `appendLine` 走 `synchronized(writeLock)` 序列化所有写。读不持锁。
5. **terminal sticky 在 restore 时** — 反向 update（试图把 COMPLETED 改回 RUNNING）必须 no-op。`applyUpdateDirect` 显式检查 `prev.status().isTerminal()`。

## 验证

```
$ mvn -B test          # BUILD SUCCESS, 1306 tests, 0 fail, 0 error
```

## 关联

- 备份目录：`docs/backups/r20d/`
- 上一轮：`docs/R20C-THEME-BANNER.md`
- 路线图：`docs/R20-ROADMAP.md`
- 下一轮：R20-E PlanExecutor (按 plan 步骤自动执行)
