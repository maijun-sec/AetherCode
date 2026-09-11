# R-perf-1 Cost Ceiling + Cache + Token Counter (2026-09-12)

## 触发

R-orch-1/2 集成了 V → self-correct → ensemble, 但每层都直接调底层组件,
没有 budget 控制. R-radar-7 self-correct 默认 N 次 attempt, R-radar-8 ensemble
默认 N agents — 总成本是 N × M × verify, 容易跑飞.

R-perf-1 引入 3 个新组件:
- **CostCeiling** — 跨层 budget (calls / millis / tokens), 早停
- **ActionCache** — V 校验结果 LRU 缓存, 避免重复验证
- **TokenCounter** — pluggable token 估算, 默认 char/4 heuristic

3 个组件集成进 AgentRuntime, R-orch-3 (E2E) 跟 reference/papers/ 8 篇 paper
跑时能控住成本.

## 实际产出 (4 main + 4 test, 1 round)

- `perf/CostCeiling.java` (135 lines, 5.0 KB):
  - 3 轴 budget: maxCalls / maxMillis / maxTokens
  - `recordCall(tokenCost)` book-keeping, returns exceeded()
  - `Usage` record: calls / tokens / elapsed + 比例
  - Builder 模式 + `>=` 语义 (call 第 N 次即超)
  - synchronized methods
- `perf/ActionCache.java` (98 lines, 3.2 KB):
  - LRU 缓存, action → VerificationResult
  - `getOrCompute(action, loader)` 短路径
  - `hitRate()` 命中率统计
  - `clear()` 重置
- `perf/TokenCounter.java` (60 lines, 2.0 KB):
  - functional interface `(T) -> long`
  - `charQuotient()` 静态默认 (4 chars/token)
  - `zero()` 静态, 关闭 token axis
  - `orElse(primary, fallback)` 链
  - `of(ToLongFunction)` 适配
- `orchestration/AgentRuntime.java` 改 (3 个新 builder 字段 + 集成):
  - `costCeiling(CostCeiling)` / `actionCache(ActionCache)` /
    `tokenCounter(TokenCounter)` builder methods
  - `verifyWithCache(T)` 私有 helper: cache hit 直接返回, miss 调 V
    + charge ceiling + cache put
  - `run()` 在 self-correct / ensemble 之前 check `budgetExceeded()`,
    超额 return "budget-exhausted" outcome
  - `runEnsemble()` 同样: 每个 candidate 之前 check budget, 超 break
  - `RuntimeResult.budgetExhausted()` method + `summary()` 加
    `budget_exhausted` 字段
- 4 test class (33 tests):
  - `CostCeilingTest` (10 tests): builder / recordCall / exceeded / Usage /
    equals
  - `ActionCacheTest` (9 tests): get/put/getOrCompute / LRU 淘汰 / hitRate /
    clear / equals
  - `TokenCounterTest` (8 tests): zero / charQuotient / orElse / of
  - `AgentRuntimeTest` (+6 tests): costCeiling 短路 / cache 命中 /
    cache 区分 / tokenCounter 充电 / null 默认 / budgetExhausted 不 pass
- **aethercode-evals: 395/395 Java tests pass** (362 + 33 new), 0 回归

## 关键技术决定 (8 条)

1. **3 轴 budget 统一一个 ceiling** - calls + millis + tokens 都是 ceiling
   维度, 任何一轴超就停. 论文 2508.17281 §5.2 + 2601.01743 §III.1.1 都用
   这种 shared budget.
2. **`>=` 而不是 `>`** - call 第 N 次 = 命中预算, 防止 caller 多用一次.
   测试断言已改.
3. **ActionCache 是 LRU 限定大小** - maxSize 上限, LRU 淘汰, 避免
   无限增长.
4. **cache.get + cache.put 在 verifyWithCache 内** - cache 命中直接 return
   (不 charge ceiling, 因为 verify 没跑).
5. **TokenCounter 默认 zero()** - 不强制每个 runtime 配 token 估算,
   byte-compatible. 配 charQuotient() 才有 token axis.
6. **budget check 在每层之前** - runtime.run 路径: V (charge 1) →
   self-correct (check) → ensemble (check). 每层 check 早停.
7. **ActionCache 是 LRU 强一致** - 同一 action 第二次 put 替换第一次值.
8. **VerifyWithCache 是 private helper** - runtime 唯一入口, 不暴露
   给 caller, 保持封装.

## API shape

```java
// 旧调用方 (byte-compatible)
AgentRuntime<String> r = AgentRuntime.<String>builder()
    .verifier(v)
    .selfCorrect(sc)
    .ensemble(ens)
    .build();

// 新调用方 (R-perf-1)
CostCeiling ceiling = CostCeiling.builder()
    .maxCalls(50).maxMillis(30_000L).maxTokens(100_000L).build();
ActionCache cache = new ActionCache(256);
TokenCounter<String> counter = TokenCounter.charQuotient();
AgentRuntime<String> r = AgentRuntime.<String>builder()
    .verifier(v).selfCorrect(sc).ensemble(ens)
    .costCeiling(ceiling)
    .actionCache(cache)
    .tokenCounter(counter)
    .build();
RuntimeResult<String> result = r.run(action);
if (result.budgetExhausted()) {
    // 重试 / 降级 / 跳过
}
```

## Budget 决策树

```
run(action):
  V(verifyWithCache) → charge 1 call
  if V passed → "passed" (success)
  if V failed:
    if budgetExceeded() → "budget-exhausted" (early stop)
    selfCorrect.run(action) → charge N calls
    if SC passed → "self-corrected" (success)
    if SC failed:
      if budgetExceeded() → "budget-exhausted" (early stop)
      ensemble.run() → charge M calls
      V(winner) → charge 1 call
      if V passed → "ensemble-passed" (success)
      else → "ensemble-failed" (failure)
```

## 累计测试 (R-perf-1 后)

- aethercode-deepagents: 253/253 Java
- aethercode-talon: 5/5 Java
- aethercode-a2a: 33/33 Java
- aethercode-a2a-deepagent-bridge: 8/8 Java
- aethercode-cli: 48/48 Java
- aethercode-tools vision: 25/25 Java
- aethercode-workflows: 63/63 Java
- **aethercode-evals: 395/395 Java** (362 R-orch-2 + 33 R-perf-1)
- aethercode-tools: 322/324 (2 pre-existing)
- aethercode-desktop (TS): 1042/1042 pass
- aethercode-desktop (Rust): 14/14 pass
- **0 回归**

## 教训 (新增 8 条, 累计 222+)

215. **3 轴 budget 比 1 轴强** - calls + millis + tokens, 任何一轴超就
     早停, 防止 cost 失控
216. **`>=` 而不是 `>`** - call 第 N 次 = 命中预算, 防止 caller 多用一次
217. **LRU 是默认 cache 策略** - LinkedHashMap 改 removeEldestEntry 一行,
     比 ConcurrentHashMap + 自己写 LRU 简单
218. **cache hit 不 charge ceiling** - verify 没跑, 不该 charge. cache 命中
     节省 cost.
219. **TokenCounter 默认 zero()** - 不强制配, byte-compatible, 配了开 token
     axis
220. **budget check 在每层前** - 短路早停, 不让 budget 超了之后还跑
221. **VerifyWithCache 是 private** - 封装, caller 不知道 verifyWithCache
     存在
222. **budgetExhausted 是单独 outcome** - 跟 passed / self-corrected
     区分, audit log 能 grep

## 后续

- R-mod-1: 抽 `aethercode-orchestration` 独立 module
- R-orch-3: 跟 `reference/papers/` 8 篇 paper 一起跑 end-to-end, 用上
  cost ceiling 控成本
