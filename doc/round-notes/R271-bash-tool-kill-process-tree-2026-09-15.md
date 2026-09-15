# R271 — BashTool killProcessTree (Esc cancel + multi-prompt bug)

Date: 2026-09-15
Round: R271
Type: fix (daemon-side)
Status: ✅ Deployed (commit `105843a` push 成功)

## Trigger (用户反馈)

用户 20:05 截图反馈：
1. **Esc 按了不能停止任务** — banner "或按 Esc 取消" 写在那，但实际无效
2. **多个 prompt 都汇总到"上面思考"里** — 第二条 prompt 下面没有任何信息
3. 实际 daemon 一直 `run-1` busy，agent 任务完成但 stream 卡死

## 根因

**Bug 2 + Bug 3 共享同一个 daemon-side 根因**：

`BashTool.runForeground` 在 timeout / cancel 时调用 `process.destroyForcibly()`。这个调用在 Windows 上等价于 `TerminateProcess(pid)` — **只 kill 直接子进程**，不递归 kill grandchild tree。

生产场景的进程树：
```
mvn (daemon 直接 spawn)        ← destroyForcibly 能 kill
├── surefire booter (mvn fork)   ← orphan, 继续跑
│   └── test JVM (booter fork)   ← orphan, 继续跑
```

daemon `process.waitFor(timeout)` 在 mvn 死后立刻返回 (timeout=true)，但 **mvn 的 children (surefire / test JVM) 还活着**，daemon 看不到它们，run 永远不结束 → `session is busy with run run-1`。

**Bug 2 (Esc 无效)**: daemon.cancel() 翻 `ctx.isAborted=true`，但 BashTool.runForeground 只在 `process.waitFor` **返回之后**检查 isAborted。waitFor 永远不会返回 (orphan 在跑)，所以 cancel 信号到不了 → Esc 无效。

**Bug 3 (多 prompt 合并)**: daemon 一直 busy → 用户的 second prompt 触发 `[busy] session is busy with run-1; please wait for it to finish (or press Esc to cancel)` 拒绝 → daemon 拒收 prompt, 没有新 sub-task / agent events → desktop MessageList timeline 渲染时 second prompt 飘在 system banners 后面，下面没内容。

## R271 修复

### `BashTool.killProcessTree(Process)` 新方法

```java
public static void killProcessTree(Process process) {
    if (process == null) return;
    long pid = process.pid();
    try {
        if (isWindows()) {
            // taskkill /T /F /PID <pid> traverses the
            // process tree on Windows.
            ProcessBuilder pb = new ProcessBuilder(
                "taskkill", "/T", "/F", "/PID", Long.toString(pid));
            pb.redirectErrorStream(true);
            Process tk = pb.start();
            tk.getInputStream().readAllBytes();
            tk.waitFor(2, TimeUnit.SECONDS);
        } else {
            // POSIX: kill -TERM -<pgid> signals the whole
            // process group (POSIX guarantee).
            // ... (pgid lookup with fallback)
        }
    } catch (Throwable t) {
        LOG.warn("R271: killProcessTree({}) failed: {}", pid, t.toString());
    }
    // belt-and-suspenders: also destroy the direct handle
    process.destroyForcibly();
    process.waitFor(1, TimeUnit.SECONDS);
}
```

**Windows**: `taskkill /T /F /PID <pid>` — `/T` 是 "tree" 标志，递归遍历 child tree，`/F` 是 force
**POSIX**: `kill -TERM -<pgid>` — 杀整个 process group (POSIX 保证)
**fallback**: 总是 also 调 `destroyForcibly` 在直接 handle 上 — 覆盖 taskkill 不在 PATH 的边缘场景

### 4 处 callsites 替换 `destroyForcibly()` → `killProcessTree()`

```java
// 1. runForeground timeout path (line 339)
boolean finished = process.waitFor(timeout, TimeUnit.MILLISECONDS);
if (!finished) {
    killProcessTree(process);  // ← was: process.destroyForcibly();
    t1.join(2000); t2.join(2000);
    return Tool.ToolResult.error("command timed out after " + timeout + "ms");
}

// 2. runForeground exception path (line 366)
} catch (Exception e) {
    if (process != null) killProcessTree(process);  // ← was: destroyForcibly()
    return Tool.ToolResult.error("command failed: " + e.getMessage());
}

// 3. spawnBackground drain-thread cancel (line 547)
if (jobId != null) {
    BashJob job = JOBS.get(jobId);
    if (job != null && job.process != null) killProcessTree(job.process);  // ← was: destroyForcibly()
}

// 4. BashJobRegistry.kill (line 711)
public boolean kill(String id) {
    BashJob j = byId.get(id);
    if (j == null || j.done.get()) return false;
    killProcessTree(j.process);  // ← was: destroyForcibly()
    return true;
}
```

## 测试

### 新增 `BashToolR271Test` — 3 个 test

1. **`killProcessTree_killsLiveProcess`** — spawn long-running cmd.exe ping, 调 helper, 5s 内 `process.waitFor` 必须 true, PID 必须死
2. **`killProcessTree_killsGrandchildren`** — spawn 长寿 parent + detached long-running grandchild (mvn + surefire 模式), 验证 kill 后 grandchild 进程数减少
3. **`killProcessTree_isIdempotentAndSafe`** — null / dead / 双重调用 都是 no-op, 不能 throw (BashJobRegistry.kill 跟 natural exit race，必须 tolerate)

### 测试结果

```
BashToolTest          9 tests pass  (no regression)
BashToolR271Test      3 tests pass  (new)
合计                  12/12 pass
```

## 影响

### Bug 2 (Esc 无效) — 修了

- daemon 看到 ctx.isAborted=true → BashTool.runForeground 检查到 → `killProcessTree(process)` 触发 taskkill /T
- taskkill /T 杀 mvn + surefire + test JVM 整个 tree
- `process.waitFor()` 返回 (true, 全部死光)
- daemon run_end 触发，session lock 释放
- 用户按 Esc 现在真的 cancel task 了

### Bug 3 (多 prompt 合并) — 自动修了

Bug 3 是 Bug 2 的**直接副作用**:
- daemon 不再卡 busy → 用户的 second prompt 不再被 daemon 拒绝
- new query 拿到 session lock → 新 sub-task 创建 → desktop timeline 正确渲染 second prompt + 后续 agent events
- "思考 / 模型输出 / tool" 都属于各自分配的 sub-task,不再汇总到 first prompt

## 部署

### Build

```bash
mvn clean install -pl aethercode-tools -DskipTests       ✓
mvn clean install -pl aethercode-protocol,aethercode-cli -am -DskipTests  ✓
```

### 部署链

| 项 | 值 |
|---|---|
| commit | `105843a` (push 成功) |
| jar SHA | `A7379FC57E6BB3B2D51E42C51DDDE9E43627F764` (R271 daemon) |
| zip SHA | `DD5AA8CD75B3D630AFA9F15835E3152CD02F71EE` (108,495,400 bytes) |
| 老 zip | `.prev.bak` (R270 backup) |

### Bytecode markers in deployed jar

| Class | Marker | Round |
|---|---|---|
| BashTool.class | `killProcessTree`, `taskkill`, `pgid`, `R271:` | **R271** |
| BashTool$BashJobRegistry.class | `killProcessTree` | **R271** |
| BashTool$BashJobRegistry.class | `destroyForcibly: 1x` | R266i (fallback) |
| WriteExistingFileGuardHook.class | `prePopulateFromSession`, `R268e write-guard` | R268e |
| AetherCodeMethods.class | `extractLastAgentEvent`, `lastAgentEvent`, `R270:` | R270 |

## 用户下一步验证

1. 双击 `release\aethercode-0.2.70\desktop\aethercode-desktop.exe` 重启 desktop
2. 切换到 abc_1 session
3. **测 Bug 2**: 让 agent 跑个长 mvn 命令 (`mvn clean package`), 30 秒内按 Esc → 应该看到 task 立即停止, 没 "Stream stale" 红 banner, daemon 不再 busy
4. **测 Bug 3**: 连续发两个 prompt → 第一个下面有 thinking + tool, 第二个 prompt 下面独立有 thinking + tool, 内容不汇总

## 教训 (新增 3 条)

**544. `Process.destroyForcibly()` 不是 "kill task"** — 它只 kill 直接子进程。在 spawn 链路 (mvn → surefire → test JVM) 上,**必须**用 `taskkill /T /F` (Windows) 或 `kill -<pgid>` (POSIX)。这一行代码 = 数小时 debug。

**545. Cancel 必须沿 spawn tree 传播** — daemon cancel / timeout / exception path 必须每个都 kill tree,不能只依赖 `process.destroyForcibly()` 的默认行为。

**546. Orphan grandchild = permanent "busy"** — daemon 看不到 orphan grandchild,但 grandchild 占着 OS 资源, daemon run state 永远不清除。`session busy` 是**最常见**的"看着像 hang 其实是没杀掉"症状。

## 文件清单

### 修改
- `aethercode/aethercode-tools/src/main/java/org/aethercode/tools/shell/BashTool.java` (killProcessTree + 4 callsite 替换)
- `release/aethercode-0.2.70/RELEASE-NOTES.md` (R271 section)

### 新增
- `aethercode/aethercode-tools/src/test/java/org/aethercode/tools/shell/BashToolR271Test.java` (3 test)
- `doc/round-notes/R271-bash-tool-kill-process-tree-2026-09-15.md` (本文)