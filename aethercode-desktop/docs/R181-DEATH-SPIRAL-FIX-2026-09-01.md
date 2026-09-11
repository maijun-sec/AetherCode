# R181 — Death-spiral 三件套修复 (2026-09-01)

## 1. 背景

用户在 v0.2.28 桌面上发了一个 prompt（"在当前目录下生成一个 java maven 项目..."），模型跑了 2.5 小时
没产生任何用户可见输出，**整段对话变成"白板"**。用户后续发"继续执行"被
`[busy] session is busy with run run-1` 拒了。session 永远 busy。

排查 daemon 真实状态：
- `getMetrics`: `turnsStarted: 1, turnsCompleted: 0, toolCalls: 37→74, toolErrors: 33→56, errorRate: 0.89, uptime: 2.6h`
- `getTranscript`: 74 → 148 条消息，绝大多数是 `tool_use (file_write / bash)` + `tool_result isError=true content="timeout: null"`
- `getTraces`: 最近的 5 条 trace 全是 `tool.bash / tool.file_read`，但 `runId: "run-1"` 一直是同一个，永远不结束

死循环的根因有**三个**，每个单独存在都会卡死；R181 一次性全修了。

## 2. 三个根因

### 2.1 R98 matrix 把 `java -version` / `dir /s` 误判为 DELETE

**位置**: `aethercode/aethercode-config/src/main/java/org/aethercode/config/OpKindDetector.java`

**Pre-R181 bug**:
```java
// DESTRUCTIVE_FLAGS 模式:
Pattern.compile("-[a-z]*[rf][a-z]*\\b")    // 误伤 -version (因为 "version" 含 'r')
Pattern.compile("/[sqf]\\b")                 // 误伤 dir /s / findstr /I (因为 /s 是 dir 的递归 LIST, 不是 del)
```

结果：
- `cd /d D:\tmp\abc_1 && dir && java -version 2>&1 && mvn -version 2>&1` → op=DELETE → R98 matrix 直接拒了，连 prompter 都到不了
- `cmd /c "dir /s /b C:\*.exe 2>nul | findstr /I \"java mvn javac\""` → op=DELETE → R98 直接拒

模型一开头就被 R98 拒掉了 diagnostic 命令，不能 `where java` / `mvn --version`，只能瞎试。

**R181 修复**:
1. **先 tokenize 拿到 first token**，再分类
2. **READ 命令（cat / dir / find / findstr / where / ...）一律返回 READ**，不做 destructive flag 升级
3. **shell wrapper (cmd / powershell / pwsh / bash / sh / zsh)** 抽出内层命令后递归分类
4. destructive flag 升级只对**真实可能破坏的命令**（rm / del / erase / shred / mkfs 等）做
5. `cd` / `set` / `pushd` / `popd` / `export` 等 shell 内建命令一律 EXEC，不被 `/d` 这类 flag 升级

### 2.2 Permission prompter timeout 返回 "timeout: null"

**位置**: `aethercode/aethercode-protocol/src/main/java/org/aethercode/protocol/permissions/JsonRpcPermissionPrompter.java`

**Pre-R181 bug**:
```java
.exceptionally(ex -> {
    return new PermissionDecision(
            AetherCodeMethods.DECISION_DENY, "timeout: " + ex.getMessage());
    //                            ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
    //                            Java TimeoutException.getMessage() returns null
    //                            -> "timeout: null"
})
```

模型调 `file_write`，R98 走 ASK 分支 → prompter 发 `permission_request` 通知桌面 → **用户没在键盘前**
→ 5 分钟后 `orTimeout(300_000ms)` 触发，`ex.getMessage()` 返回 `null` → DENY 理由变成 `"timeout: null"`。
模型看到这个糊里糊涂的拒绝，不知道是用户没响应，就重试 → 同一命令再被拒 → 死循环。

**R181 修复**:
```java
if (ex instanceof java.util.concurrent.TimeoutException) {
    reason = "permission ask for '" + tool.name()
            + "' timed out after " + (timeoutMs / 1000) + "s with no user response. "
            + "DO NOT retry this tool call — the user is not at the keyboard. "
            + "Either: (1) tell the user what you wanted to do and let them re-run, "
            + "or (2) pick a different tool / approach that does not require permission.";
}
```

理由包含：工具名 + 实际秒数 + **明确的"DO NOT retry"** 指令 + 给模型两条出路（告诉用户 / 换工具）。模型收到这个会立刻停止重试而不是死循环。

### 2.3 Loop detector 在 post-check 不硬停

**位置**: `aethercode/aethercode-core/src/main/java/org/aethercode/core/engine/QueryEngine.java`

**Pre-R181 (R171 留下的)**:
- Post-check 只发 SideNote，**不再 `if (postInfo.shouldStop()) return false;`**
- 理由：用户说"ending the run is the user's call (Ctrl-C → user_interrupt)"
- 问题：headless / unattended 场景下用户**不在键盘前**，Ctrl-C 不可能

结果：`same_error` 5 次连发，detector 已经升到 `loop_detected` tier 3，但引擎继续跑。

**R181 修复**: 在 post-check 加一个**只针对 `same_error` 的硬停**分支：
```java
if (postInfo != null && "same_error".equals(postInfo.kind())
        && postInfo.shouldStop()) {
    LOG.warn("R181: hard-stopping run on same_error death spiral ...");
    action.accept(new StreamEvent.SideNote(
            "same_error_hard_stop",
            "R181: same tool returned the same error 3+ times in a row — "
                    + "the run is in a death spiral and is being hard-stopped. "
                    + ...));
    stopReason = "same_error_hard_stop";
    finished = true;
    return false;
}
```

只对 `same_error` 硬停；`same_fingerprint` / `long_output` / `research_mode` 仍走 R171 软处理。
理由：`same_error` 意味着**工具坏了**，再想也没用；其它模式可能只是模型探索期，硬停会误伤。

## 3. 改动

| 文件 | 改动 |
|------|------|
| `aethercode-config/.../OpKindDetector.java` | 重构 detectBash：先按 first-token 分类；READ 命令直接返回 READ；shell wrapper 递归 |
| `aethercode-config/.../OpKindDetectorTest.java` | +12 个 R181 回归测试（`r181_*` 前缀） |
| `aethercode-protocol/.../JsonRpcPermissionPrompter.java` | 替换 `"timeout: " + ex.getMessage()` 为带工具名 / 秒数 / STOP 指令的清晰消息 |
| `aethercode-protocol/.../JsonRpcPermissionPrompterR181TimeoutTest.java` | 新建 (6.3 KB) — 3 个 timeout 消息回归测试 |
| `aethercode-core/.../QueryEngine.java` | post-check 加 `same_error` 硬停分支 |
| `aethercode-core/.../ProgressLoopDetectorTest.java` | +1 个 `r181_sameErrorEscalatesToLoopDetected` 测试 |

## 4. 测试

| 套件 | 改动前 | 改动后 |
|------|--------|--------|
| aethercode-config OpKindDetectorTest | 29 | **41** (+12) |
| aethercode-core ProgressLoopDetectorTest | 18 | **19** (+1) |
| aethercode-protocol JsonRpcPermissionPrompterR181TimeoutTest | 0 | **3** (new) |
| 3 模块合计 (aethercode-config, -core, -protocol) | 250 | **265** |
| 桌面 vitest (74 文件) | 885 | **885** (无变化，R181 是后端) |

mvn BUILD SUCCESS 全部 3 模块。`tsc --noEmit` clean。

## 5. 构建产物

| 文件 | 大小 | SHA256 |
|------|------|--------|
| `release/aethercode-0.2.29/AetherCode.exe` | 3,950,080 (3.95 MB) | `E8DF51E95787E2DBBA0622A95BDF3DCE4D3C7E346A4F70F2300D4637C009677E` |
| `release/aethercode-0.2.29/aethercode-0.2.29.jar` | 55,303,626 (53 MB) | `324F9E83A7820BD521173C6FE4A8E21B007B4EED326D13EDD4E82974D792E6A0` |
| `aethercode/dist/aethercode-0.2.29.jar` | 55,303,626 | `324F9E83A7820BD521173C6FE4A8E21B007B4EED326D13EDD4E82974D792E6A0` |

对比 v0.2.28 jar (55,301,281 bytes)：新 jar 大 2,345 bytes，多了 R181 三个 Java 改动。

## 6. 卡死 session 处理

v0.2.28 死锁的 session（id `2026-09-01T08-04-23.599963600Z_8bdfacd5`，74→148 条 transcript）已被
**强制 kill 两个 daemon (PIDs 8236, 25816)** 处理。Session 持久化在 `D:\tmp\abc_1\.aethercode\sessions\`
里没被破坏。用户下次启动 v0.2.29 desktop 可以重新连这个 session 继续工作，或者开新 session。

## 7. 验证步骤

1. 关闭当前 v0.2.28 桌面窗口
2. 启动 `D:\work\workspace\idea\engine\AetherCode\release\aethercode-0.2.29\AetherCode.exe`
3. 新 session，发 prompt `在当前目录下生成一个 java maven 项目，支持至少 5 种排序算法，支持 int、short、long 三类数组排序，UT 完整`
4. 预期：
   - 模型 `where java` / `mvn -version` 不再被 R98 误判拒绝（R181 修 #1）
   - 写文件时桌面会弹 consent modal；用户 5min 不响应时，模型收到清晰的 "DO NOT retry" 拒绝信息（R181 修 #2）
   - 如果模型陷入"同 tool 同错 5 次"的死循环，run 会被 R181 硬停（不再 turnCount 卡住）（R181 修 #3）

## 8. 教训 (2026-09-01)

1. **"诊断命令被 R98 误伤" 是 R98 设计上从未充分测试的边界**。`Pattern.compile("-[a-z]*[rf][a-z]*\\b")`
   表面看是 "-rf/-fr/-fR" 的安全检查，实际是 `-version` / `-force` / `--recursive` 都被打中。
   教训：**regex 防御性扫描要在长尾诊断命令上做 fuzz**——用户的真实命令集合远比 "rm/del/erase" 丰富。
2. **"timeout: null" 是 Java 生态的常见反模式**。`ex.getMessage()` 在 `TimeoutException` 上返回 `null`，
   任何 `"prefix: " + ex.getMessage()` 都会产生 `"prefix: null"` 给最终用户。教训：**给模型（或任何下游
   消费者）的错误消息**要么自己构造完整字符串，要么用 Objects.toString(ex) 兜底。
3. **"ending the run is the user's call" 在 headless 场景下不成立**。R171 移除了 post-check 硬停
   是个合理的 UX 决定（让用户有更多控制权），但**没考虑无人值守场景**。教训：**任何"软化自动干预"的决定
   都要有"硬应急"机制**。R181 加 `same_error` 硬停就是这个应急：模型死循环时强制停，胜过让用户来按 Ctrl-C。
4. **daemon 卡死时 cancel() 没用**。R97-B 的 cancel 调 done.cancel(true)，但工具调用卡在
   prompter 的 `orTimeout` 上时，线程被挂起，cancel 不会真正释放。教训：**给 cancel 路径加个
   `force-cancel-after-deadline` 兜底**：cancel 调 5s 后还没释放就强制 interrupt + 释放 session lock。
   留作 R182 候选。
5. **"白板"诊断第一步**：永远先看 daemon 的 `getMetrics` + `getTraces`，看 toolCalls/toolErrors/
   errorRate/lastLoopKind。这能 5 秒内定位是 "模型没在工作" 还是 "模型在工作但输出看不到"。
   renderer 的 `isStreaming=null` 不可信，daemon 的 `turnsStarted - turnsCompleted` 才是真相。

## 9. R182 候选

1. **cancel() 强制释放** — 5s 后还没完就 interrupt + 释放 sessionRunLock，让用户能立刻发新 prompt
2. **R98 matrix 测试覆盖** — 把 R98 真正用在 daemon 默认配置上，并加 `dir /s /b`、`findstr /I`、
   `mvn -version` 等诊断命令的回归测试
3. **StatusBar 显式显示 "session 卡死" 警告** — 当 `turnsStarted - turnsCompleted > N` 时显示
   "Run 1 has been running 5min, no progress"，让用户知道是引擎卡了不是模型在思考
4. **Permission ask 自动 cancel** — 桌面检测到用户关闭 consent modal 时，发 `permissionResponse(cancel)`
   立刻结束 prompter future，不要等 5min timeout
