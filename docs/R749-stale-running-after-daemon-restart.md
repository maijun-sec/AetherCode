# R749 — daemon 重启后 UI 卡在假 `Running`

日期: 2026-10-21
触发: 用户发来截图，desktop 顶部显示 `RUNNING` / `Running`，任务卡片写着 `7m · 704 msg`，右侧 Plan 显示 `0/7 done · ▶1 running`
用户要求: 「确认一下还有没有这个问题」→「修了吧，所有的实现，都需要保证用户体验，功能完整可靠」

---

## 症状

桌面端持续显示「运行中」，但引擎侧早已完全空闲。

## 证据：四层交叉验证

| 证据 | 观测 |
|------|------|
| daemon 日志 | 17:05 之后**零输出**，从未处理过任何 query |
| `jstack` 全部线程 | 只有 watcher / HTTP / 文件监听，**零个 engine 线程** |
| daemon CPU | 启动至今仅 19.3 秒 |
| 两个 session 最后活动 | 分别 33 分钟前 / 107 分钟前 |

而 UI 显示 `Running`、`7m · 704 msg`。**引擎早就不动了，UI 停在上一个任务的状态。**

---

## 根因：UI 在猜，而不是在读

### 之前的设计

renderer 判断「有没有任务在跑」只有一个信号：

> 「我最近有没有收到 chunk？」

这个推断在**重连之后必然出错**：重连到的新 daemon 是一条健康的 WS 连接，它安静但完全正常 —— 所有 liveness 信号都是绿的，而 `isStreaming` 还停留在 `true`。

### 为什么没有任何东西来纠正

1. `store/index.ts:4144` 的 `daemon.disconnected` 处理器写了 `isStreaming: false`，但它只在 **WS ping 失败 / read 报错 / 收到 close / stream end** 时才触发。
2. supervisor（desktop）在旧 daemon 被 kill 后**重连到了新 daemon**，连接是健康的 —— 于是那个通知**永远不会到达**。
3. stream-stale watchdog 的守卫是 `if (!s.isStreaming || s.lastChunkTs <= 0) return` —— 它确实会清 `isStreaming`，但那要等 **90 秒**，而且如果 `lastChunkTs` 为 0（首个 chunk 还没来）它**直接失明**。

### 引擎侧根本没有这个信息

```
$ grep 'AtomicBoolean|currentlyRunning|inFlight' QueryEngine.java
No matches found
```

`getState` 返回的 19 个字段里**没有任何一个表示运行态**。UI 只能猜，因为**没人告诉它真相**。

---

## 修法：让引擎暴露权威状态，UI 去读（而不是猜）

这正是用户定的原则：**确定性逻辑走确定性路径，不要靠猜。**

### R749a — 引擎暴露 `isRunning`

`AetherCodeMethods.getState()` 新增两个字段：

```java
java.util.List<String> liveRunIds = List.copyOf(inFlight.keySet());
r.put("isRunning",  !liveRunIds.isEmpty());
r.put("liveRunIds", liveRunIds);
```

**`inFlight` 是唯一可信的真相来源**，因为它的生命周期是完备的：

| 时机 | 动作 |
|------|------|
| `query()` 入口 | `inFlight.put(runId, done)` —— 在任何事件流出之前 |
| `finally` 块 | `inFlight.remove(runId)` —— **每一条**退出路径：成功、hard-stop、cancel、抛异常 |

所以「这个 runId 在不在」直接回答「这个 run 是否真的在执行」，**不含任何启发式**。

### R749b — 两个位置做校对

1. **`initialize()`（重连路径）** — 立刻校对，不等 15 秒定时器
2. **`refreshEngineState()`（15 秒周期）** — 兜底

两处的守卫都是 `state?.isRunning === false && get().isStreaming`，即**只纠正那一个可证明的方向**：

> 反方向（引擎说在跑但本地没在跑）**必须**是 no-op —— 一个在我们快照之后才启动的 run 合法地显示 `isRunning=false`，当成 desync 会误杀正在跑的任务。

校对时不只清 `isStreaming`，还清 `currentStepId` / `currentSubTaskId` / `currentQuery`，否则右侧 Plan 会一直显示 `▶1 running`（用户截图里正是这个症状的一半）。

同时设置 `lastFailedStopReason: 'daemon_lost_run'` + `lastFailedPrompt`，让 ↻ 重试按钮可用 —— 用户得有办法恢复，不能只是让幻影凭空消失。

### R749c — watchdog 不再在首 chunk 前失明

```ts
const startedAt = s.currentQuery?.startedAt ?? 0;
const lastActivity = s.lastChunkTs > 0 ? s.lastChunkTs : startedAt;
if (lastActivity <= 0) return;
```

之前 `lastChunkTs <= 0` 直接 return，而那**恰好是**「`isStreaming=true` 之后、首个 chunk 之前**的状态**。一个在这个窗口里被 kill 的 run 是 watchdog 完全看不见的。

---

## 三层防御

| 层 | 触发 | 性质 |
|----|------|------|
| 重连即校对 | WS 重连完成 | 确定性，**立即** |
| 15s 周期校对 | 定时器 | 兜底 |
| stream-stale watchdog | 90s 无活动 | 兜底（且修好了首 chunk 前的盲区） |

三层都读**同一个权威源**，所以结论一致、不会互相打架。

---

## 测试

`runStateReconcileR749.test.ts` — 8 个 source-pin case：

1. `EngineState` 声明 `isRunning` / `liveRunIds`
2. `initialize()` 重连路径有校对
3. `refreshEngineState()` 周期路径有校对
4. 校对同时清 run-scoped UI 状态（否则 Plan 永远显示 running）
5. 校对给出重试入口而非静默失败
6. **反方向必须是 no-op**（守卫是 `=== false` 显式比较，不是真值判断）
7. watchdog 回退到 `startedAt`
8. stale 比较用 `lastActivity`

**变异验证**：删掉重连路径的校对（退回 pre-R749 行为），case 2 和 case 6 转红 —— 测试确实在守护这个修复。

---

## 部署验证状态

- aethercode-desktop **1373 passed / 1 failed** — 唯一失败是 `R104: Header.tsx has a tools button (🔧)`，**pre-existing**（R748 轮已用 stash 验证过与本次无关）
- `tsc -b --noEmit` exit 0
- 变异测试确认有效

---

## Lessons

- **Lesson 1081** — **「任务卡在运行中」的第一嫌疑是 UI 在猜，而不是引擎真的在跑**。诊断顺序：`jstack`（有没有 engine 线程）→ daemon 日志（有没有新行）→ session `lastUsedAt`。三处都指向空闲时，那就是 UI 状态陈旧，不要去改引擎。
- **Lesson 1082** — **重连会掩盖所有 liveness 信号**。一条新建立的健康 WS 连接是「安静但正常」的，所以「最近没收到数据」这类启发式在重连后**永远为真**。任何基于「静默时长」的状态推断，在重连路径上都是失效的。
- **Lesson 1083** — **修这类问题的正解是让权威源说话，不是加更多兜底**。引擎本来就有 `inFlight` 这个完备生命周期的注册表；UI 猜不到，是因为没人把它暴露出来。**当 UI 只能推断时，先问「真相在哪里」，再考虑加 watchdog。**
- **Lesson 1084** — **watchdog 的守卫条件本身也是 bug 面**。`lastChunkTs <= 0 → return` 让它在「已声明 streaming 但还没说话」这个窗口里完全失明 —— 而那正是 run 最脆弱的时刻。
- **Lesson 1085** — **只纠正可证明的那一个方向**。`isRunning === false && isStreaming` 是单向守卫；反向（引擎说在跑、本地没跑）合法存在，不能当成 desync 处理，否则会误杀刚启动的任务。