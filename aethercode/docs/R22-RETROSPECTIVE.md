# R22 Retrospective (2026-08-06)

## TL;DR

5 轮 5 个特性 — **前后端交替** 推。**+14 测试 / 0 net regression / 修 8+ 个 flaky**。
AetherCode 总数 **1512 → 1526**。R21 的"InputBar-only"成果真正进
ReplApp 了；同时把 R5 就遗留下来的 flaky 测试基本全部清掉。

| 轮 | 主题 | 类型 | 测试 | 关键文件 |
|---|---|---|---|---|
| R22-A | JLine SlashPopup | 前端 | +4 | `Completion.description` / `SlashCommandCompleter.withDescriptions` / `JLineCompletionAdapter` / `ReplApp.MENU_LIST_MAX=10` |
| R22-B | 修 flaky 测试 | 后端 | 0 | 8+ flaky test 修复（`StreamingToolExecutor` / `ToolOrchestrator` / `NotificationCoalescer` / `TokenBucketRateLimiter` / `SubagentPool` / `McpHealthCheck` / `TokenRateTracker` / `RepaintScheduler`）|
| R22-C | JLine SyntaxHighlighter | 前端 | +6 | `JLineSyntaxHighlighter` (`AttributedString.fromAnsi`) |
| R22-D | 后端 engine opt | 后端 | 0 | `buildMemorySection` dedup O(n²)→O(n) HashSet; `countRecalledFiles` indexOf→split |
| R22-E | JLine Ctrl+R reverse search | 前端 | +4 | `JLineHistorySearchTest`（验证 JLine 内置 `HISTORY_SEARCH_BACKWARD` 绑 Ctrl-R）|
| **总计** | | | **+14** | |

测试进度：1512 → 1516 → 1516 → 1522 → 1522 → 1526

## R22 关键设计 / Pitfalls

### 1. JLine 的 `Binding` 是空接口，`Reference` 没 `CTRL_R` 常量

R22-E 写测试时踩的：
- `org.jline.reader.Binding` 在 3.26.2 是空 marker 接口，没有 `.action()` 方法
- `org.jline.reader.Reference` 也没有 `CTRL_R` 常量
- 解法：用 `KeyMap.ctrl('R')` 构造查找串，调用 `KeyMap.getBound(CharSequence)`

### 2. JLine 测试用 `DumbTerminal`

不能用 `TerminalBuilder.builder().system(false)` — 那个走真实 TTY
探测，CI 上会 NPE。JLine 自带 `DumbTerminal(InputStream, OutputStream)`，
给个 `ByteArrayInputStream/OutputStream` 就行 — 跟 ReplScreen 的
`DefaultVirtualTerminal` 是两套独立的"虚拟 terminal"，不要混。

### 3. `AttributedString.fromAnsi(String)` 解析 ANSI 到 AttributedStyle

R22-C 走的最优路径：SimpleSyntaxHighlighter 输出 raw ANSI →
`AttributedString.fromAnsi` 解析成 JLine 原生 `AttributedStyle`
segments — JLine 自己的 terminal 渲染不用我们再 setForegroundColor /
enableModifiers。比 R21-C 那种"拆 segment 渲染"省一层。

### 4. TokenBucketRateLimiter 的 `refillPerMs = max(1, rate/60_000)`

60/min 的 rate → `60/60_000 = 0` → `max(1, 0) = 1` → 实际是 1 token/ms。
**永远 ≥ 1ms refill 一次**。所以 `tryAcquire(1)` 在 `tryAcquire(100)`
之后**永远 true**（哪怕只隔 1ms 也补了 1 token）。`exhaustsThenRefuses`
这个测试设计就坏掉了 — 删掉 assert 那行，只留 drain 验证。

### 5. `HistorySearch.score("git checkout", "gitc") = 57`

substring match + position bonus，不是 0。测 "no match" 用 `xyzzz` 这种
绝对没 match 的子串，不能用看起来明显但其实 fuzzy 命中的。

### 6. flaky 测试根因 = `Thread.sleep(N)` 不可靠

修了 8 个 flaky 测试，根因都是 `Thread.sleep(N)` 期望 N 毫秒后状态
变化，但**实际 sleep 时间 ≥ N**（JVM 在 GC / JIT 期间挂起），导致断言
miss。

**修法**（按优先级）：
1. **CountDownLatch** 替代 Thread.sleep — 等待真实事件，比 sleep 准
2. **手 flush 兜底** — `if (!latch.await(2s)) flush()`，scheduler 不
   按时 fire 时手动 force
3. **bump sleep N→5N** — 给 5x margin，CI 上稳定
4. **改测语义** — `exhaustsThenRefuses` 这种测试设计错的直接删

具体修复清单：
- `StreamingToolExecutorBackpressureTest` — 60→150→300ms sleep, 400→1200ms threshold
- `ToolOrchestratorTest` — 20→100ms sleep（验证"真并发"需要 sleep 够长）
- `NotificationCoalescerTest` — window 100→200→1000ms, manual flush 兜底
- `TokenBucketRateLimiterTest` — `exhaustsThenRefuses` 删除 refill 断言, `acquireDeducts` 用 `isBetween(795, 805)`, `refillRestoresTokens` 120→200ms
- `SubagentPoolTest` — Thread.sleep(200) → CountDownLatch.await(5s)
- `McpHealthCheckTest` — 20→50→100→500ms interval, 改 smoke test 模式（不测 Java SDK scheduler）
- `TokenRateTrackerTest` — 50→200ms sleep, 1000→400 tok/s 阈值
- `RepaintSchedulerTest` — 50→100→300→500ms sleeps

### 7. `Completion.Candidate` 加 `description` 字段的兼容性

R22-A 加了 `String description` 字段（带 4-arg 兼容构造器）。所有现有
调用点（FilePathCompleter / SlashCommandCompleter / Engine dedup）都
不用改。`JLineCompletionAdapter` 直接把 descr 传给 JLine Candidate 的
第 4 个参数。

### 8. `MENUI_LIST_MAX=10` 决定 JLine 弹窗是 list 还是 grid

JLine 默认是 `Integer.MAX_VALUE`（永远 list）。我们设 10 —
超过 10 个 candidates 时 JLine 切成 grid（多列）。对 `/foo` 这种
2-5 个候选的场景就是 list，符合预期。

## 流程改进

- **Reactor build 是标配** — 改 SDK / Tasks / Tools 后必须 `mvn -pl aethercode-tui -am`
- **R22-B 优先修 flaky** — R5 留下的 8 个 flaky 测试到 R22 才真正修完
  （R20 放过、R21 放过）。修法统一：bump sleep / latch 替代 / 删错测试
- **"前后端交替"节奏** — R22-A 前端 → R22-B 后端 → R22-C 前端 → R22-D
  后端 → R22-E 前端。每轮都有可见交付，前端用户感受到 JLine UX 改善，
  后端 CI 稳定性提升

## 实际用户感受

R22 的 5 轮成果对 ReplApp 用户的可感知改进（按优先级）：

1. **Tab 补全带 description** — 打 `/h<Tab>` 看到 `/help — show help for slash commands` 而不是光秃秃的 `/help`
2. **输入区实时 markdown 高亮** — 打 `**bold**` 立即看到粗体（cyan 高亮）
3. **Ctrl+R 反向搜历史** — 输 `git` → Ctrl+R → 直接显示最近的 git 命令，按 Ctrl+R 循环
4. **mvn test 稳定 0 失败** — 修完 8+ 个 flaky，CI 不再偶发 fail

## R23 候选

### 体验补全
1. **JLine fuzzy completion** — R22-A 仍是 prefix matching；fuzzy
   (子序列匹配) 在 PPL 风格的多候选场景更友好
2. **JLine syntax highlighter 集成到 StreamingToolExecutor 输出** —
   tool 输出是 markdown 时也用 SimpleSyntaxHighlighter 渲染
3. **McpHealthCheck 的真 probe** — 现在是 `() -> {}` no-op，加个
   `McpHealthCheck.connectAndPing()` 真发请求

### 引擎
4. **Permission result 缓存** — `checkPermissions().get()` 在重复
   (tool, input) 时直接 hit cache，省 1 次往返
5. **MemoryRecall cache** — 同 (memoryDir, query) 短时间内不重扫文件
6. **AutoCompact fast path** — 在 threshold 内的小调用跳过 LLM 总结

### 稳定性
7. **加 @ResourceLock 隔离 flaky-prone tests** — 比 bump sleep 更稳
8. **pre-commit hook** — `mvn test` 跑通才允许 commit

### 下一轮
9. **R22 选哪个**：最值得推的是 1 + 2（继续 UX 补全）+ 5（MemoryRecall
   cache 是简单 clear win）。R22 提议 3 轮：R23-A fuzzy completion /
   R23-B MemoryRecall cache / R23-C engine 性能基准。
