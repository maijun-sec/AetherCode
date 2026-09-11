# R20-F: 滑动窗口 + 摘要链 (2026-08-06)

## 目标

10 小时不爆上下文。当前 `AutoCompact` 单次摘要会无限增长，最终自己也爆上下文；改成「最近 N 条原文 + 链式摘要」结构。

## 变更

### 新增类 (`aethercode-compact/.../compact/`)

- `SlidingWindowCompactor` — 链式摘要器，实现 `Compactor` 接口
- 构造器接受内层 `Compactor`（typically `AutoCompactAdapter`）+ `contextWindow` + `bufferTokens` + `keepRecent`（默认 10）
- 内部状态：`List<Message> chain`（摘要链）+ `compactionCount` + `totalSummarisedChars`
- `compact(messages)`:
  1. `shouldCompact`：估算 `messages` 字符数 ÷ 4 > (contextWindow - bufferTokens) 时返回 true
  2. 切片：`head = messages[0..n-keepRecent]` / `tail = messages[n-keepRecent..n]`
  3. 把 head 喂给内层 summariser
  4. 把新摘要 append 到 chain
  5. 返回 `[chain..., tail...]`
- `reset()` 清空 chain（用于新 session）
- `chainSize()` / `compactionCount()` / `totalSummarisedChars()` / `chainSnapshot()` 诊断方法

### `AetherCodeEngine` builder 默认值

R20-F 起，`b.contextWindow > 0` 时默认用 `SlidingWindowCompactor(AutoCompactAdapter(ac), ctx, buffer)`。以前是直接 `AutoCompactAdapter(ac)`。

## 设计要点

- **链式 + 滑动窗口**：每次 compact 只把 head 喂给 summariser，tail 永远保留原文。新摘要 append 到 chain。Transcript 大小 = `chain.length * summarySize + keepRecent * avgMessage`，跟总轮数无关。
- **错误隔离**：内层 summariser 抛异常 → catch → return null（不污染 chain）。summariser 返回空 → 同样 return null。
- **状态管理**：chain 在 compactor 实例内。同一 session 复用同一 compactor，chain 持续增长；新 session 调 `reset()`。

## 测试

新增 13 tests，0 regression：

| Test class | Tests |
|---|---|
| `SlidingWindowCompactorTest` | 13 (阈值 / 太小跳过 / 第一遍 / 第二遍 / 链式增长 / 不爆 / summariser 失败 / summariser 空 / reset / snapshot copy / chars 累加 / 构造 null) |
| **合计** | **13** |

总测试数：**1331** (R20-E: 1318 → R20-F: 1331, +1.0%)

## 关键 pitfall

1. **测试阈值要算对** — 起初测试用 `contextWindow=200_000, buffer=13_000`，threshold=187_000 tokens=748_000 chars，但测试消息只有 100×2000=200K chars → 50K tokens < threshold，`shouldCompact` 返回 false → `compact` 返回 null → 测试 fail。改用 `contextWindow=100, buffer=10`（threshold=90 tokens=360 chars），消息 20×50=1000 chars=250 tokens > threshold。
2. **头尾切片 vs 全部摘要** — 起初测试期望 20 条全被摘要，但实际上只 head（前 10）被摘要，tail（后 10）保留原文。修了断言为 500 chars（10×50）。
3. **AetherCodeEngine 构造路径** — `compactor` 是 final 字段，遵循 R18 已有的「resolve into local then assign」模式，避免编译器拒绝。
4. **R5 flaky tests** — `TokenBucketRateLimiterTest` / `StreamingToolExecutorBackpressureTest` 在并行 load 下偶发 timeout，单跑过；pre-existing issue，与本轮无关。

## 验证

```
$ mvn -B test          # BUILD SUCCESS, 1331 tests, 0 fail, 0 error
```

## 关联

- 备份目录：`docs/backups/r20f/`
- 上一轮：`docs/R20E-PLAN-EXECUTOR.md`
- 路线图：`docs/R20-ROADMAP.md`
- 下一轮：R20-G 任务树可视化（box-drawing + 嵌套 + 计时）
