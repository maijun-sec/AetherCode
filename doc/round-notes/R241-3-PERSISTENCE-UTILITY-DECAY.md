# R241.3 — ReasoningBank 持久化 + Utility Decay

**Status**: shipped
**Date**: 2026-09-10
**Parent**: R241.2 (O-3 ExperienceStore → 策略库)
**Tests**: aethercode-deepagents **153/153 pass, 0 regressions** (was 116 in R241.2; +37 new)

---

## Why R241.3

R241.2 把 ReasoningBank 做成了"in-memory + 单次 session"的形态。R241.2 的设计稿里就标过三个未做项：

1. **R230 ExperienceStore schema 接入** — 复用 R230 风格持久化（per-file JSON）
2. **跨 session 共享** — 重启后能 recall 之前的 unit
3. **Utility decay** — 长时间未 uses → utility 衰减（避免 stale 单元霸榜）

R241.3 把这三个一次补齐，同时保持 R241.2 公共 API **零破坏**：`new ReasoningBank()` 仍然是 in-memory + no decay 行为，50 个 R241.2 测试一字不改全 pass。

### 论文 reference 映射

| 概念 | 来源 | R241.3 实现 |
|---|---|---|
| Storage backend 抽象 | Memory in the Age of AI Agents (arXiv:2512.13564) §3.2 — 多级存储 | `BankStorage` interface (InMemory / JsonFile) |
| Forgetting curve | 同综述 §5.2.3 + Ebbinghaus | `UtilityDecay.exponential(halfLife)` |
| 简单 linear drop | 综述 §5.2.3 备选模型 | `UtilityDecay.linear(perDay)` |
| 写时持久化 | ReasoningBank 2025 paper §4.2.2 "persisted reasoning traces" | `JsonFileBankStorage.save()` write-through |
| 冷启动 reload | R230 ExperienceStore `reload()` 模式 | `new ReasoningBank(dir)` 自动 `loadAll()` |

---

## What shipped

### 1. `BankStorage` 接口 (R241.3)

`aethercode-deepagents/src/main/java/org/aethercode/deepagents/selfimprove/BankStorage.java` (2.9 KB)

```java
public interface BankStorage extends AutoCloseable {
    List<ReasoningUnit> loadAll();
    void save(ReasoningUnit unit);
    void remove(String id);
    void flush();
    @Override void close();
}
```

5 个方法：冷启动 loadAll、write-through save/remove、flush/close 资源管理。线程安全由实现负责。

### 2. `InMemoryBankStorage` — 默认实现 (R241.3)

`InMemoryBankStorage.java` (2.3 KB)

`ConcurrentHashMap` + `ReentrantReadWriteLock`，跟 R241.2 的 ReasoningBank 内部存储同语义。**不再用 `INSTANCE` 单例**（R241.3 早期版本踩坑：单例导致 13 个 R241.2 测试串味，全 28 个 unit 累积到一个 map 上）。改回每次 `new InMemoryBankStorage()` 拿独立 map。

### 3. `JsonFileBankStorage` — 文件持久化 (R241.3)

`JsonFileBankStorage.java` (6.7 KB)

**布局**: `{dir}/{id}.json`（per-record JSON，跟 R230 `ExperienceStore` 同款）

**实现要点**:
- Jackson + `JavaTimeModule` (项目已有依赖，不新增)
- 写时 tmp + rename，POSIX `ATOMIC_MOVE`，Windows fallback `REPLACE_EXISTING`
- `loadAll()` 容错：损坏 JSON 跳过 + warn log，不让一个坏文件毁掉整个 bank
- `.tmp` sibling 文件不读（crash 后残留）
- `ReentrantReadWriteLock` 串行写、并行读

**使用**:
```java
ReasoningBank bank = ReasoningBank.withFileStorage(
    Path.of(System.getProperty("user.home"), ".aethercode", "reasoning-bank"));
```

### 4. `UtilityDecay` 接口 (R241.3)

`UtilityDecay.java` (3.2 KB)

```java
@FunctionalInterface
public interface UtilityDecay {
    double effectiveUtility(ReasoningUnit unit, Instant now);
    UtilityDecay NO_DECAY = (u, now) -> u.utility();
    static UtilityDecay exponential(Duration halfLife) { ... }
    static UtilityDecay linear(double perDay) { ... }
}
```

3 个静态工厂 + 一个 `NO_DECAY` 默认值。Decay 是**懒计算**：`effectiveUtility` 在 recall 排序时调用，不修改存储值。

### 5. `ExponentialDecay` — 半衰期 (R241.3)

`ExponentialDecay.java` (2.2 KB)

```
u_eff = u * 2^(-Δseconds / halfLifeSeconds)
```

- zero age → base utility
- one half-life → 半
- 配 Ebbinghaus forgetting curve（综述 §5.2.3 引用）
- 边界：`now < createdAt` 视为 0 elapsed（防 clock skew），`utility == 0` 短路返回 0

### 6. `LinearDecay` — 每天固定衰减 (R241.3)

`LinearDecay.java` (2.1 KB)

```
u_eff = clamp(u - perDay * Δdays, 0, 1)
```

适合"14 天内有效"这种窗口化场景，比 exponential 更可解释。

### 7. `ReasoningBank` 内部接入 storage + decay (R241.3)

`ReasoningBank.java` 从 8.7 KB 扩到 13.7 KB（增 5 KB）。

**保留 API 兼容**:
- `new ReasoningBank()` ✓ — 默认 InMemoryBankStorage + NO_DECAY（R241.2 行为）
- `add / get / contains / size / sizeForKind / kinds` ✓
- `recallFor(taskKind)` / `recallFor(taskKind, n)` ✓ — 排序时改为用 `decay.effectiveUtility(u, now)`，但 NO_DECAY 下与 `u.utility()` 等价
- `touch / clear / parse / histogram / listener` ✓

**新加 API**:
- `new ReasoningBank(BankStorage storage)` — 任意 storage + NO_DECAY
- `new ReasoningBank(BankStorage storage, UtilityDecay decay)` — 完整配置
- `static withFileStorage(Path dir)` — JsonFile + NO_DECAY 工厂
- `static withFileStorage(Path dir, UtilityDecay decay)` — JsonFile + decay 工厂
- `storage()` / `decay()` — getter
- `recallFor(taskKind, n, Instant now)` — R241.3 重载（决定性测试 + replay 用）
- `decayPass(Instant now)` — 显式持久化衰减后的值，返回 changed count

**内部行为**:
- 构造时 `storage.loadAll()` 冷启动填充 in-memory cache
- `add()` 后立即 `storage.save(unit)`（write-through）
- `touch()` 后立即 `storage.save(updated)`
- `decayPass(now)` 重写 in-memory utility 到 effective 值，write-through

---

## Tests (37 new)

| 套件 | tests | 覆盖 |
|---|---:|---|
| `JsonFileBankStorageTest` | 11 | save/load round-trip、overwrite、remove、loadAll 空/坏 JSON/`.tmp` 跳过、close idempotent、null dir 拒绝、null unit no-op、auto-mkdir |
| `UtilityDecayTest` | 18 | NO_DECAY (2)、Exponential (7: zero age, 1×half, 2×half, zero utility, future-createdAt clamp, 0/负 halfLife 拒绝)、Linear (7: zero age, 1 day, clamp 0, 0.5 day prorate, 0/负/NaN rate 拒绝)、Factory sanity (2) |
| `ReasoningBankPersistenceTest` | 8 | 默认 in-memory 不持久、withFileStorage 跨实例、touch 持久化、Exponential decay 改 recall 顺序、decayPass 持久化、NoDecay decayPass 0 changed、Exponential 跨实例 reload、add 自动落盘 |

### Cumulative (aethercode-deepagents module)

```
Before R241.3:  116 tests  (R241.2 + 之前累积)
+ R241.3:        37 tests
──────────────────────────
Total:          153 tests, 0 failures, 0 errors, 0 regressions
```

Surefire 跑约 3.4 秒（R241.2 时约 2.8 秒，多出来的 0.6 秒主要是文件持久化 + decay 时间计算）。

---

## Files

### NEW (R241.3)

- `aethercode-deepagents/.../selfimprove/BankStorage.java` (2.9 KB) — interface
- `aethercode-deepagents/.../selfimprove/InMemoryBankStorage.java` (2.3 KB) — 默认实现
- `aethercode-deepagents/.../selfimprove/JsonFileBankStorage.java` (6.7 KB) — per-file JSON 持久化
- `aethercode-deepagents/.../selfimprove/UtilityDecay.java` (3.2 KB) — interface + NO_DECAY + 2 factory
- `aethercode-deepagents/.../selfimprove/ExponentialDecay.java` (2.2 KB) — half-life 实现
- `aethercode-deepagents/.../selfimprove/LinearDecay.java` (2.1 KB) — per-day 实现

### NEW (R241.3 tests)

- `aethercode-deepagents/.../test/.../selfimprove/JsonFileBankStorageTest.java` (11 tests)
- `aethercode-deepagents/.../test/.../selfimprove/UtilityDecayTest.java` (18 tests)
- `aethercode-deepagents/.../test/.../selfimprove/ReasoningBankPersistenceTest.java` (8 tests)

### MOD (R241.3)

- `aethercode-deepagents/.../selfimprove/ReasoningBank.java` (8.7 → 13.7 KB) — +2 构造器、+2 工厂、+2 getter、+1 recallFor 重载、+1 decayPass、内部 storage 字段替换裸 ConcurrentHashMap

---

## 设计决定 (R241.3)

1. **不直接依赖 aethercode-memory.ExperienceStore** — 避免跨模块依赖 + ReasoningUnit schema 跟 ExperienceRecord 不完全一致（没 tags/links/sourceSessionId 等字段，引入 ExperienceStore 反而要适配）
2. **per-file JSON**（跟 R230 一致）— 而不是单文件：diff/inspect 友好，文件粒度小
3. **decay 默认 NoDecay** — 兼容 R241.2；opt-in 通过 `withFileStorage(dir, ExponentialDecay.halfLife(7d))`
4. **Decay 懒计算 + decayPass 显式持久化** — recall 排序用 effective，但写盘按需；显式 `decayPass` 让用户决定何时 commit
5. **不存 `lastUsed` 字段** — 衰减用 `createdAt` 算（Ebbinghaus 形式不需要 lastUsed）；未来 R243 引入 access-based decay 再加字段
6. **BankStorage 抽象不引入新依赖** — Jackson 已在 aethercode-deepagents pom.xml（验证过），不新增任何 dep
7. **in-memory 不用单例** — R241.3 第一版踩坑：13 个 R241.2 测试串味（unit 数从 1 涨到 28）。改回 `new InMemoryBankStorage()` 每次独立
8. **write-through + tmp+rename** — 不写 batch：高频 add 场景下 write-through 简单可靠，R243 再考虑 WAL/batch

---

## What did NOT ship in R241.3 (deferred to R243+)

1. **Success reflection** — 工具成功后也写"为什么成功"（失败/成功 balance）
2. **Cross-session recall 高级排序** — 当前 recall 跨实例 OK，但没做"按时间窗口过滤"或"按 session 距离衰减"
3. **Decay 触发器** — 现在 decayPass 必须显式调用，没 hook 进 agent loop；R243 接 agent runtime 的 idle tick
4. **Bank growth 上限** — 没设 hard cap；理论可以无限增长；R243 加 LRU 或 utility 阈值裁剪
5. **DRIFT (Dynamic Rule Induction From Traces)** — bank 内容写回 AGENTS.md 作为可编辑规则（O-8 完整）
6. **向量召回** — 当前 recall 是 utility sort；语义相似度召回留 R244 O-6
7. **Bank 远程同步** — 多人协作场景；留 R244+ 接入 aethercode-a2a

---

## 教训 (R241.3)

1. **单例要慎用** — `InMemoryBankStorage.INSTANCE` 第一版让 13 个 R241.2 测试串味（累积 28 个 unit 跑一个 bank）。R241.2 的 `ReasoningBank` 自己持有 map 不共享，所以无此问题。**结论：抽 storage 时默认每次 new，不要做 INSTANCE 单例**
2. **`TimeUnit.MILLIS` 不存在** — 实际是 `TimeUnit.MILLISECONDS`。第一次编译 2 处都错。**结论：写时间单位常量化**
3. **decay 公式选型** — 选了 `Math.pow(2.0, -x)` 而不是 `Math.exp(-x * LN2)`，可读性更高，recall 排序时本来就在 N=3 循环里，nanosecond 级别差异忽略
4. **per-file vs 单文件 JSONL** — 选 per-file。理由跟 R230 ExperienceStore 一致（小 cardinality，diff 友好）。**如果未来 bank 涨到 10k+ units 切单文件**
5. **`Math.max(0L, ...)` 防 clock skew** — 节点时间回拨时 elapsed 为负，decay 会"加 utility"违反单调性。强制 clamp 到 0
6. **`Instant.now()` 兜底 null now** — 测试时可以传 `null`，但生产代码永远有真值。给 null 的 fallback 让测试不用每次构造 `Instant.now()`
7. **Surefire `--%` 模式 `+` 不解析** — 一次跑 3 个 test 套件用逗号即可（R241.2 学到的）
8. **`@TempDir` 真实子目录** — R230 已踩过：Windows 路径大小写不敏感会让 magic string 命中其他测试残留。R241.3 一律用 `@TempDir Path`
9. **Jackson `@JsonProperty` 不需要** — `ReasoningUnit` record 字段都是基本类型 + String + List<String>，Jackson 默认能反序列化；省了一堆 annotation
10. **写一次 commit，不写 batch** — `add()` 写一次到 disk 简单可靠；高频 add 场景下用户可以 wrap 自己 batch 调用（未来 R243+ 接 LazyWriteMiddleware）
11. **跟 R230 ExperienceStore 字段名对齐** — 都用 `id / utility / uses / createdAt`，未来接 ExperienceStore 互转时不用改字段名

---

## 关键文件 SHA / 路径

**新增 6 java** (在 `aethercode-deepagents/.../selfimprove/`):
- `BankStorage.java` (2.9 KB)
- `InMemoryBankStorage.java` (2.3 KB)
- `JsonFileBankStorage.java` (6.7 KB)
- `UtilityDecay.java` (3.2 KB)
- `ExponentialDecay.java` (2.2 KB)
- `LinearDecay.java` (2.1 KB)

**修改 1 java**:
- `ReasoningBank.java` (8.7 → 13.7 KB) — 接入 storage + decay 字段

**新增 3 test 套件** (在 `aethercode-deepagents/.../test/.../selfimprove/`):
- `JsonFileBankStorageTest.java` (11 tests)
- `UtilityDecayTest.java` (18 tests)
- `ReasoningBankPersistenceTest.java` (8 tests)

**报告**:
- `doc/项目文档/R241-3-PERSISTENCE-UTILITY-DECAY.md` (本文)
- 父报告: `doc/项目文档/R241-EXPERIENCESTORE-STRATEGY-LIBRARY.md`

---

## Backups

- R241.2 报告保持原样（18.9 KB），R241.3 是 sibling 增量报告
- 父报告末尾的"R243 范围候选"已经覆盖了 R241.3 留待 R243 的工作
- 出包策略：R241.3 与 R241.2 + R242 + R240 一起下次 release 时整体出 0.2.58（可选）

---

## Next (R243 scope 候选)

按 ROI 排序：

1. **Success reflection** — 工具成功后也写反思，跟失败平行
2. **decayPass 触发器** — 接 agent runtime idle tick，自动衰减
3. **Bank growth 上限** — 设 max size + LRU / utility 阈值裁剪
4. **DRIFT 动态规则** — 把高频触发的反思沉淀到 AGENTS.md 作为可编辑规则（O-8 完整）
5. **O-8 全局审计** — bank growth rate / utility distribution / failure pattern trending

预计 R243 总耗时 2-3 round。
