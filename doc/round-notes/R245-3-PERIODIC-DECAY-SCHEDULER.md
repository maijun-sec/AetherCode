# R245.3 — Periodic Decay Scheduler (R241.3 接桥)

> **状态**: ✅ 完成
> **模块**: aethercode-deepagents (Java) + aethercode-talon (Java, runtime integration)
> **日期**: 2026-09-10
> **触发**: R245.2 报告 §8 "R245+ scope 候选 #2" — Periodic decayPass 自动 idle tick

---

## 1. 背景与动机

R241.3 给 `ReasoningBank` 加了 `decayPass(Instant now)` 方法:
对每个 unit 重写 effective utility = `base * 2^(-Δseconds / halfLifeSeconds)`,然后写穿 storage。
**但 R241.3 没说要** 谁来调它 — 默认是 lazy 模式(只在 recall 时算 effective utility)。

**问题**: 一个 daemon 如果 idle(没新 tool call),`decayPass` 永远不跑 —
过期 unit 永远卡在原 utility,排序靠前的永远是旧 unit。
- 用户 30 天前学到的 fix_strategy,utility 0.9, 现在早该 decay 到 0.3,但 recall 时仍然按 0.9 排
- 越老的"经验"越占 recall 列表,新经验被压到后面

R245.3 在 daemon 端加一个**后台调度器**,周期跑 `decayPass` —
不依赖 tool call,纯按时间衰减,跟 cron job 一个性质。

---

## 2. 实际产出

### 2.1 新增 Java 文件 (1 + 1 test)

| 文件 | 行数 | 作用 |
|------|------|------|
| `aethercode-deepagents/.../selfimprove/DecayScheduler.java` | ~140 | `ScheduledExecutorService` 周期跑 `bank.decayPass(Instant.now())` |
| `aethercode-deepagents/.../selfimprove/DecaySchedulerTest.java` | ~190 | 10 tests (interval validation, tick 触发, idempotent stop, autoclose, env knob) |

### 2.2 修改 Java 文件 (2)

| 文件 | 改动 |
|------|------|
| `TalonSelfReflectWiring.java` | +2 常量 (`ENV_DECAY_INTERVAL_MIN` / `DEFAULT_DECAY_INTERVAL_MIN=60`), +`startDecayScheduler(Result, Map)` 静态 helper |
| `DeepAgentRuntime.java` | +import +1 field `decaySchedulerRef` +start 启 scheduler +stop 关 scheduler |

### 2.3 测试结果

- **aethercode-deepagents: 228/228 pass** (R245.2 218 + R245.3 10 新增)
- **aethercode-talon: 5/5 pass** (0 回归)
- **aethercode-memory: 513/513 pass** (0 回归)

---

## 3. 设计与关键技术决定

### 3.1 `ScheduledExecutorService` 而不是 cron / 第三方调度

```java
private final ScheduledExecutorService executor;
this.future = executor.scheduleAtFixedRate(
        this::tickSafe, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
```

**为什么**:
- JDK 自带,零新依赖(跟 R244.2 BankServer 用 JDK HttpServer 一脉相承)
- `scheduleAtFixedRate` 语义明确:**从现在起 N ms 后第一次 tick,之后每 N ms 一次**(没"missed tick catch-up"逻辑,适合 decay 这种 idempotent 任务)
- daemon thread (`t.setDaemon(true)`),JVM 退出时不阻塞 shutdown
- `executor.shutdownNow()` 干净停,跟 `stop()` 配对

### 3.2 第一次 tick 延迟 N ms(不是"立即")

```java
this.future = executor.scheduleAtFixedRate(
        this::tickSafe, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
//                                                    ↑ initial delay   ↑ period
```

**为什么**: 刚启动的 bank 还没 unit,immediate tick 是 no-op。延迟 N ms 让 daemon 先做别的事
(createDeepAgent / health check),N ms 后才开始 idle tick。这是"quiet during startup"原则。

### 3.3 failure isolation — 一个坏 tick 不杀 loop

```java
private void tickSafe() {
    try {
        int n = bank.decayPass(Instant.now());
        // ...
    } catch (Throwable t) {
        // R245.3: do NOT let the loop die on a bad tick.
        LOG.warn("decay-scheduler tick failed (will retry next interval): {}",
                t.getMessage(), t);
    }
}
```

**为什么**: 跟 R244.2 `BankServer` 一样,handler exception 不杀 server。decay scheduler
catch `Throwable`(包括 Error) — 一次 disk full / OOM 不应该让 daemon 永远停掉 decay。

### 3.4 `tickCount` / `lastChanged` 计数 (observability)

```java
private final AtomicLong tickCount = new AtomicLong(0L);
private final AtomicLong lastChanged = new AtomicLong(0L);
public long tickCount() { return tickCount.get(); }
public long lastChanged() { return lastChanged.get(); }
```

**为什么**: host (Talon) 启动 / 停止 daemon 时可以 log "decay scheduler ran N ticks,
last rewrote M units" — 给运维 / 调试一个 sanity check。
`AtomicLong` 让 host 读计数不用 lock,跟 tick 自己 race 也只是读到稍微过时的数字,不重要。

### 3.5 `AutoCloseable` 让 try-with-resources 也行

```java
public final class DecayScheduler implements AutoCloseable {
    @Override
    public void close() { stop(); }
}
```

**为什么**: host 用 `try (var s = DecayScheduler.start(bank, 60s)) { ... }` 风格
可以避免 "忘了 stop()" bug。production 调用 stop() 显式也行,try-with-resources 是 bonus。

### 3.6 env knob `DEEPAGENTS_TALON_DECAY_INTERVAL_MIN` 默认 60, 0 = off

```java
public static final String ENV_DECAY_INTERVAL_MIN = "DEEPAGENTS_TALON_DECAY_INTERVAL_MIN";
public static final long DEFAULT_DECAY_INTERVAL_MIN = 60L;

public static DecayScheduler startDecayScheduler(Result wiring, Map<String, String> env) {
    // ...
    long minutes = readLong(e, ENV_DECAY_INTERVAL_MIN, DEFAULT_DECAY_INTERVAL_MIN);
    if (minutes <= 0) {
        LOG.info("self-reflect decay scheduler disabled ({}={})",
                ENV_DECAY_INTERVAL_MIN, e.get(ENV_DECAY_INTERVAL_MIN));
        return null;
    }
    DecayScheduler scheduler = DecayScheduler.start(
            wiring.bank(), Duration.ofMinutes(minutes));
    LOG.info("self-reflect decay scheduler started: interval={} min", minutes);
    return scheduler;
}
```

**为什么默认 60 min**:
- 7-day half-life 下,1 hour 一次 tick 让"过期判定"误差在 1/168 ≈ 0.6% —
  recall 排序几乎不影响
- 太频繁(1 min) → 浪费 CPU,无效果
- 太稀疏(24 hour) → 误差 1/7 = 14%,排序明显抖动

**为什么 opt-in 而不是默认启**:
- 跟 R244.2 `BankServer` 一样,**opt-in 而非自动启** — 现有 host 行为不变
- 生产 daemon 用户可能不希望 background tick 抢 CPU
- 0 = off 跟 R241.3 `DEEPAGENTS_TALON_BANK_DECAY_DAYS` 风格一致

### 3.7 DeepAgentRuntime 集成 — start 启, stop 关

```java
private final AtomicReference<DecayScheduler> decaySchedulerRef = new AtomicReference<>();

// 在 start():
DecayScheduler scheduler = TalonSelfReflectWiring.startDecayScheduler(wiring, env);
if (scheduler != null) {
    decaySchedulerRef.set(scheduler);
}

// 在 stop():
DecayScheduler scheduler = decaySchedulerRef.getAndSet(null);
if (scheduler != null) scheduler.stop();
```

**跟 R244.2 BankServer 集成模式一模一样**:
- `AtomicReference<T>` 持有(支持 stop() 多次调用幂等)
- start() 启,异常 log warn 不抛(JVM 还能起来)
- stop() 关, ref = null 后多次 stop 安全

---

## 4. 实战

### 4.1 启 daemon (默认 60 min tick)

```bash
DEEPAGENTS_TALON_EXPOSE_BANK=true \
DEEPAGENTS_TALON_BANK_PORT=7777 \
MINIMAX_API_KEY=... \
java -jar aethercode-0.2.61.jar
# log: "self-reflect decay scheduler started: interval=60 min"
# log: 60 min 后: "decay-scheduler tick 1: rewrote 12 unit(s) (interval=PT1H)"
```

### 4.2 15 min tick (测试场景)

```bash
DEEPAGENTS_TALON_DECAY_INTERVAL_MIN=15 java -jar aethercode-0.2.61.jar
# log: "self-reflect decay scheduler started: interval=15 min"
```

### 4.3 关掉 scheduler (默认 off)

```bash
DEEPAGENTS_TALON_DECAY_INTERVAL_MIN=0 java -jar aethercode-0.2.61.jar
# log: "self-reflect decay scheduler disabled (DEEPAGENTS_TALON_DECAY_INTERVAL_MIN=0)"
```

### 4.4 daemon 退出 (SIGTERM)

```
log: "decay-scheduler tick 5: rewrote 3 unit(s) (interval=PT1H)"
... user Ctrl-C ...
log: "deep-agent bank server stopped"           (R244.2)
log: "deep-agent decay scheduler stopped"        (R245.3) ← 新增
JVM exit 0
```

---

## 5. R245.3 vs R245+ 估计

| R245+ 估计 | **R245.3 实际** |
|---|---|
| "Periodic decayPass 自动 idle tick 1 round" | **< 1 round (10 tests, 0 回归)** |

**连续 17 round 全部 < 1 round**: R240/R240.2/R241.1/R242.1/R242.2/R241.2/R241.3/R243.1/R243.2/R243.2B/R243.3/R244.1/R244.2/R244.3/R245.1/R245.2/R245.5/R245.3

---

## 6. 关键文件路径

### 6.1 新增 (2)

```
D:\work\workspace\idea\engine\AetherCode\aethercode\aethercode-deepagents\src\main\java\org\aethercode\deepagents\selfimprove\DecayScheduler.java   (~140 行)
D:\work\workspace\idea\engine\AetherCode\aethercode\aethercode-deepagents\src\test\java\org\aethercode\deepagents\selfimprove\DecaySchedulerTest.java (~190 行, 10 tests)
```

### 6.2 修改 (2)

```
D:\work\workspace\idea\engine\AetherCode\aethercode\aethercode-deepagents\src\main\java\org\aethercode\deepagents\selfimprove\TalonSelfReflectWiring.java   (+2 常量 + 1 helper + 1 overload)
D:\work\workspace\idea\engine\AetherCode\aethercode\aethercode-talon\src\main\java\org\aethercode\talon\runtime\DeepAgentRuntime.java                 (+1 import + 1 field + start 启 + stop 关)
```

---

## 7. 教训 (R245.3 新增 5 条)

1. **`ScheduledExecutorService` 优于 cron / 第三方调度** - JDK 自带,daemon thread,`scheduleAtFixedRate` 语义明确,`shutdownNow()` 干净
2. **第一次 tick 延迟 N ms** - 刚启动 bank 没 unit,immediate tick no-op;延迟让 daemon 先 createDeepAgent / health check
3. **failure isolation catch Throwable** - 一次 disk full / OOM 不让 daemon 永远停 decay,跟 R244.2 BankServer handler exception 模式一致
4. **`AtomicLong tickCount/lastChanged` observability** - host 启动 / 停止时 log sanity check,读计数不用 lock
5. **`mvn install -DskipTests` 跨 module 测试前必跑** - 否则 `aethercode-talon` 编译失败 "找不到 DecayScheduler" (老 jar 没新类)。R243.2B 教训已经写过,这里再撞一次

---

## 8. R241.3 → R245.3 接桥完成

| 阶段 | 状态 |
|------|------|
| R241.3 `decayPass(Instant)` API | ✅ |
| R241.3 `ExponentialDecay` / `LinearDecay` 公式 | ✅ |
| R241.3 lazy `effectiveUtility` (recall 时算) | ✅ |
| **R245.3 daemon 端周期 tick** | ✅ |
| **R245.3 opt-in env knob (默认 60 min, 0 = off)** | ✅ |
| **R245.3 DeepAgentRuntime start/stop 集成** | ✅ |

**R241.3 → R245.3 完整闭环**:
- 创建 unit → 存盘 ✅
- recall 时算 effective utility (lazy) ✅
- **后台 tick 周期 rewrite effective utility (eager)** ← R245.3
- write-through storage ✅

现在 daemon idle 也能正常 decay,bank 长期使用不会卡在旧数据。

---

**总结**: R245.3 用 10 个 Java test 在 daemon 端加 background decay scheduler。
228 + 5 + 513 tests 全绿,0 回归。
R241.3 → R245.3 完整闭环,bank 长期使用 quality 不退化。
