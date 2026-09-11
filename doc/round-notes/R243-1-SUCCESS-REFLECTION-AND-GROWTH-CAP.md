# R243.1 — Success Reflection + Bank Growth Cap

**Status**: shipped
**Date**: 2026-09-10
**Parent**: R241.2/R241.3 (O-3 ExperienceStore → 策略库)
**Tests**: aethercode-deepagents **174/174 pass, 0 regressions** (was 153 in R241.3; +21 new)

---

## Why R243.1

R241.2 + R241.3 让 ReasoningBank 具备"失败反思 + 持久化 + 衰减"三件套，但留了 R241 报告里列的两个未做项：

1. **Success reflection** — 工具成功时只走默认路径，没沉淀"为什么成功"作为 strategy。Reflexion (paper 4 §11.1) 只反思失败，但 ReasoningBank (paper 1 §4.2.2) 明确说"success and failure are both abstracted into reusable reasoning units" — 没有 success 路径，bank 只能当 mistake catalogue，不能当 strategy library。
2. **Bank growth cap** — bank 可以无限增长。综述 arXiv:2512.13564 §5.2.4 提到"memory consolidation / bounded growth" 是必备，否则 bank 会 dominate the recall slot。

R243.1 一次补齐。

### 论文 reference 映射

| 概念 | 来源 | R243.1 实现 |
|---|---|---|
| 成功/失败对称反思 | ReasoningBank 2025 paper §4.2.2 | `SuccessReflectMiddleware` 跟 `SelfReflectMiddleware` 对称 |
| 策略 prompt 模板 | ReasoningBank 2025 paper §4.2.2 描述 | `SuccessReflectPrompts.DEFAULT_SYSTEM_PROMPT` (提"why it worked") |
| Success classification | Reflexion paper §11.1 (失败版) 镜像 | `SuccessClassifier.always()` / `never()` |
| Bank growth cap | Memory survey (arXiv:2512.13564) §5.2.4 | `BankGrowthPolicy` + `NoGrowthCap` / `UtilityBasedEviction` / `LruEviction` |
| LRU eviction 索引 | 经典 OS 概念 | 内存 `Map<id, Instant> lastTouchedAt` (不持久化) |
| 弱 utility 裁剪 | Forgetting curve 综合 (综述 §5.2.3) | `UtilityBasedEviction` 用 effective utility (带 decay) 排序 |

---

## What shipped

### A. Success reflection (3 java)

#### `SuccessClassifier.java` (2.0 KB)
对称 `FailureClassifier`：
- `SuccessClassifier.always()` — 每次成功都反思，kind `tool_success`
- `SuccessClassifier.never()` — 关闭 success reflection

#### `SuccessReflectPrompts.java` (1.4 KB)
独立 system prompt，让模型输出"为什么成功"的 strategy。**关键**: prompt 跟 `SelfReflectPrompts` 共用同一 KV 解析格式 (`error_pattern` / `fix_strategy` / `example`)，所以 `ReasoningBank.parse()` 不需要改。

#### `SuccessReflectMiddleware.java` (11.1 KB)
跟 `SelfReflectMiddleware` 对称：
- `wrapToolCall` 成功时调 `reflectOnSuccess()`
- 失败时直接 rethrow（**不**做成功反思 — failure path 归 SelfReflectMiddleware）
- `SuccessClassifier.always() / never()`
- `maxSuccessesPerTurn` cap（默认 1；0 = 全关）
- `recentSuccesses()` accessor (bounded MAX_RECENT=8)
- 4 个 metric counter: `totalReflections / skippedDedup / skippedReflectorError / skippedDisabled`

**关键设计决定**: **新建独立 Middleware，不并入 SelfReflectMiddleware**。理由：
- 失败反思 mandatory (默认开)，成功反思 opportunistic (默认开但 cap=1)
- 独立后 host 可以挑不同 Reflector（成功反思用便宜模型）
- host 可以只 wire 失败反思而完全跳过成功反思

#### `SuccessReflectMiddlewareTest.java` (10 tests)
- successfulToolCallStoresReflection
- failingToolCallIsNotReflectedAsSuccess
- maxSuccessesZeroDisablesReflection
- duplicateSuccessesAreDeduped
- differentToolsEachStoreOneReflection
- emptyReflectorResponseIsSkipped
- reflectorExceptionIsSwallowed
- neverClassifierStoresNothing
- recentSuccessesBoundedByMax
- successPromptMentionsStrategyShape

### B. Bank growth cap (4 java)

#### `BankGrowthPolicy.java` (2.5 KB)
interface：
```java
List<ReasoningUnit> selectEvictions(int currentSize, List<ReasoningUnit> snapshot, Instant now);
```
默认 `NO_GROWTH_CAP = (cs, snap, now) -> List.of()`。

#### `NoGrowthCap.java` (0.8 KB)
显式 "no cap" 实现。`R241.2` 行为保留。

#### `UtilityBasedEviction.java` (2.8 KB)
按 effective utility 排序裁剪最弱的：
- 选 N 个 effective utility 最低的（带 `UtilityDecay`，跟 recall 排序一致）
- 严格 evict `overflow` 个（不 batch 强制放大）
- tie-break: createdAt ASC → id ASC（确定性）
- safety: 至少保留 1 个 unit

**修法说明**: 第一版用了 `evictionBatch` floor，导致 overflow=1 时强制 evict 10 个（over-evict）。改成严格 `target = overflow`，batch 留给 caller 自己 batch 调 `evictIfNeeded()`。

#### `LruEviction.java` (3.2 KB)
按 lastTouchedAt 裁剪最久没用的：
- 需要 bank 维护 in-memory `Map<id, Instant> lastTouchedAt`（不持久化）
- `add()` 时 lastTouchedAt[id] = now
- `touch()` 时更新
- 冷启动时 map 空 → fallback `createdAt`
- 同样严格 evict `overflow` 个

**关键设计决定**: `lastTouchedAt` **不入 `ReasoningUnit` record**（保 schema 兼容 R241.3 per-file JSON 格式）。Map 是 in-memory only，重启后用 createdAt 作 fallback。这意味着冷启动后第一批 LRU 行为是"按创建时间"，第二次 add/touch 之后才正常。这个权衡比改 schema 安全。

#### `BankGrowthTest.java` (11 tests)
- noGrowthCapAllowsUnboundedGrowth
- utilityBasedEvictionTrimsAboveCap
- utilityBasedEvictionUsesEffectiveUtilityUnderDecay
- utilityBasedEvictionRejectsNonPositiveMax
- utilityBasedEvictionKeepsAtOrBelowCap
- lruEvictionDropsOldestTouchedFirst
- lruEvictionRejectsNonPositiveMax
- lruEvictionFallsBackToCreatedAtWhenNoTouchRecord
- withFileStorageSupportsGrowthPolicy
- storageSeesEviction
- defaultConstructorIsNoGrowthCap

### C. `ReasoningBank` 内部接入 (R243.1)

`ReasoningBank.java` 从 13.7 KB 涨到 13.9 KB（+0.2 KB — 主要是 `growthPolicy` 字段、`evictIfNeeded()`、`lastTouchedAt` 维护、构造器重载）。

**保留 API 兼容**:
- `new ReasoningBank()` ✓ — InMemoryBankStorage + NO_DECAY + NoGrowthCap（R241.2 行为）
- `add / get / contains / size / sizeForKind / kinds` ✓
- `recallFor(taskKind) / recallFor(taskKind, n) / recallFor(taskKind, n, now)` ✓
- `touch / clear / parse / histogram / listener` ✓
- `decayPass(now)` ✓
- `withFileStorage(dir)` / `withFileStorage(dir, decay)` ✓ — 默认 NoGrowthCap

**新加 API**:
- `new ReasoningBank(BankStorage, UtilityDecay, BankGrowthPolicy)` — 完整构造
- `withFileStorage(dir, decay, growthPolicy)` — JsonFile + decay + growth 工厂
- `growthPolicy()` getter
- `evictIfNeeded()` — 显式触发，返回 evict 数量

**内部行为**:
- `add()` 末尾调 `evictIfNeeded()`，同步写盘删除被裁的 unit
- `touch()` 更新 `lastTouchedAt[id] = Instant.now()`
- `evictIfNeeded()` 调 `growthPolicy.selectEvictions()`，对 `LruEviction` 传 `lastTouchedAt` 快照

---

## Tests (21 new)

| 套件 | tests | 覆盖 |
|---|---:|---|
| `SuccessReflectMiddlewareTest` | 10 | success 路径存、failure 路径不存、max=0 关、dedup、不同 tool 各存、empty/exception 跳过、never classifier 静默、recentSuccesses bounded、prompt shape |
| `BankGrowthTest` | 11 | no cap、UtilityBased cap、decay 影响排序、参数校验、batch 后精确 cap、LRU 排序、参数校验、createdAt fallback、withFileStorage + growth、storage 看到 evict、default = NoGrowthCap |

### Cumulative (aethercode-deepagents module)

```
Before R243.1:  153 tests  (R241.2 + R241.3 + 之前累积)
+ R243.1:        21 tests
──────────────────────────
Total:          174 tests, 0 failures, 0 errors, 0 regressions
```

Surefire 跑约 3.9 秒（R241.3 时 3.4 秒，多 0.5 秒主要是 file storage + LRU 时间）。

---

## Files

### NEW (R243.1)

- `aethercode-deepagents/.../selfimprove/SuccessClassifier.java` (2.0 KB)
- `aethercode-deepagents/.../selfimprove/SuccessReflectPrompts.java` (1.4 KB)
- `aethercode-deepagents/.../selfimprove/SuccessReflectMiddleware.java` (11.1 KB)
- `aethercode-deepagents/.../selfimprove/BankGrowthPolicy.java` (2.5 KB)
- `aethercode-deepagents/.../selfimprove/NoGrowthCap.java` (0.8 KB)
- `aethercode-deepagents/.../selfimprove/UtilityBasedEviction.java` (2.8 KB)
- `aethercode-deepagents/.../selfimprove/LruEviction.java` (3.2 KB)

### NEW (R243.1 tests)

- `aethercode-deepagents/.../test/.../selfimprove/SuccessReflectMiddlewareTest.java` (10 tests)
- `aethercode-deepagents/.../test/.../selfimprove/BankGrowthTest.java` (11 tests)

### MOD (R243.1)

- `aethercode-deepagents/.../selfimprove/ReasoningBank.java` (13.7 → 13.9 KB) — +growthPolicy 字段、+1 构造器、+1 工厂、+lastTouchedAt 维护、+evictIfNeeded、+growthPolicy() getter

---

## 设计决定 (R243.1)

1. **独立 SuccessReflectMiddleware，不并入 SelfReflectMiddleware** — failure 反思 mandatory、success 反思 opportunistic，host 选择灵活；不同 Reflector 也方便
2. **maxSuccessesPerTurn 默认 1，0 = 关** — 跟 maxReflectionsPerTurn (默认 1) 对称；避免 chatter；opt-in 调高
3. **SuccessReflect 跟 SelfReflect 共用同一 KV 解析格式** — `parse()` 不用改；`error_pattern: n/a` 让 success 路径走"无 error pattern" fallback
4. **bank growth policy 用 interface 不只用 int maxUnits** — 不同部署用不同策略（offline pipeline vs chat assistant vs edge device），interface 留扩展点
5. **lastTouchedAt 内存维护，不入 ReasoningUnit schema** — 保持 R241.3 per-file JSON schema 兼容；冷启动 fallback createdAt 可接受
6. **evict 严格 `overflow` 个，不 batch 强制放大** — 简单、可调试；batch 留给 caller 调 `evictIfNeeded()` 实现
7. **NoGrowthCap 用 singleton (INSTANCE)** — 因为它是真正的 no-op，singleton 友好；`UtilityBasedEviction` / `LruEviction` 不用 singleton 因为有 state
8. **evict 同步触发（add 后立即 evict）** — 不写 background scheduler；高频 add 场景下 <1ms 同步开销可接受；R244+ 接 scheduler 优化
9. **`evictIfNeeded()` 公开** — 给 host 提供显式触发（periodic decay + evict 一起跑）
10. **UtilityBasedEviction 用 effective utility (decay-aware)** — 跟 recall 排序一致，避免"recall 看不见但仍占位"的废 unit

---

## What did NOT ship in R243.1 (deferred to R243.2+)

1. **Decay 触发器** — 当前 `decayPass()` / `evictIfNeeded()` 必须显式调用。R243.2 接 agent runtime idle tick
2. **O-8 全局审计** — bank growth rate / utility distribution / failure pattern trending 没接 `MemoryAudit`
3. **DRIFT 动态规则** — bank → AGENTS.md 转换器没做
4. **Success reflection 默认关闭 opt-in** — 跟 failure 默认开相反；R243.2 看真实使用数据决定要不要翻转
5. **Batch eviction 提前** — 当前 1:1 evict；R243.2 看高频 add 场景后再加 "size > 80% cap 时主动 evict" 模式
6. **LRU 跨 session 持久化** — `lastTouchedAt` 重启就丢；R243.2+ 决定要不要入 schema
7. **Cross-session 高级排序** — 当前跨实例 OK，但没"按时间窗口过滤"或"按 session 距离衰减"

---

## 教训 (R243.1)

1. **`null` 参数被悄悄替换成 never-classifier** — 第一版 `SuccessReflectMiddleware(reflector, bank, null, null, 100)` 测试全 fail，根因是构造器 `classifier == null ? SuccessClassifier.never() : classifier`。Default-to-safe 是好习惯但要写在 javadoc + 测试用 explicit `SuccessClassifier.always()`。**结论：optional 字段不要让 null 等价于 "no-op"，要么 @Nullable 要么 explicit factory**
2. **batch 强制放大是反直觉** — 第一版 `target = max(overflow, evictionBatch=10)` 导致 overflow=1 也 evict 10 个。`BankGrowthTest.lruEvictionDropsOldestTouchedFirst` 期望 2 但得 1。**结论：evict 数量 = overflow 严格，batch 概念如果需要就显式给 caller**
3. **`ConcurrentLinkedDeque` 的 `contains` 慢** — `recentSuccesses.contains(desc)` 是 O(n)。MAX_RECENT=8 限住了 n 不会太大，但 1000+ calls/turn 时仍可优化；R244+ 换 `Set`+insertion-order
4. **`@FunctionalInterface` + 多个 overload 会引发歧义** — `LruEviction.selectEvictions(int, List, Instant)` 内部 `return selectEvictions(cs, snap, null)` 编译歧义。修：内联实现不调 overload。**结论：内部实现不要为了 DRY 而调同名 overload**
5. **`instanceof NoGrowthCap` 是 quick-reject** — `evictIfNeeded()` 头部用 `if (growthPolicy instanceof NoGrowthCap) return 0;` 跳过 snapshot 拷贝 + sort 成本
6. **@TempDir Path 用 real subdirectory** — R230/R241.3 已踩过；R243.1 沿用
7. **Surefire `--%` 模式** — 逗号分隔多个 test class (R241.2 已踩)
8. **Prompt 兼容** — success reflection 跟 failure reflection 共用同一 KV 格式让 `parse()` 不破；这是早期 R241.2 把 prompt 设计成"canonical key/value"的红利
9. **`Instant.now()` 在 add/touch 路径** — `lastTouchedAt[id] = Instant.now()` 不是 caller-supplied；保持现有 API 简单，test 用 `Thread.sleep(5)` 制造时间差
10. **`MAX_RECENT=8` 是常数而不是 config** — 跟 R241.2 SelfReflectMiddleware 保持一致；用户可写子类覆盖

---

## 关键文件 SHA / 路径

**新增 7 java** (在 `aethercode-deepagents/.../selfimprove/`):
- `SuccessClassifier.java` (2.0 KB)
- `SuccessReflectPrompts.java` (1.4 KB)
- `SuccessReflectMiddleware.java` (11.1 KB)
- `BankGrowthPolicy.java` (2.5 KB)
- `NoGrowthCap.java` (0.8 KB)
- `UtilityBasedEviction.java` (2.8 KB)
- `LruEviction.java` (3.2 KB)

**修改 1 java**:
- `ReasoningBank.java` (13.7 → 13.9 KB) — +growthPolicy 字段、+lastTouchedAt 维护、+evictIfNeeded

**新增 2 test 套件** (在 `aethercode-deepagents/.../test/.../selfimprove/`):
- `SuccessReflectMiddlewareTest.java` (10 tests)
- `BankGrowthTest.java` (11 tests)

**报告**:
- `doc/项目文档/R243-1-SUCCESS-REFLECTION-AND-GROWTH-CAP.md` (本文)
- 父报告: `doc/项目文档/R241-EXPERIENCESTORE-STRATEGY-LIBRARY.md`
- 父增量: `doc/项目文档/R241-3-PERSISTENCE-UTILITY-DECAY.md`

---

## Backups

- R241.2/R241.3 报告保持原样
- R243.1 是 R243 O-3 完整闭环的第一批（按用户问卷决策）
- 出包策略：R243.1 跟 R241.2/R241.3 一起下次 release 时整体出 0.2.58

---

## Next (R243.2 scope 候选)

按 R239 路线图剩 3 个 R243 子项：

1. **Decay 触发器 + O-8 全局审计** — 接 agent runtime idle tick + MemoryAudit hook bank 写
2. **DRIFT 动态规则 (bank → AGENTS.md)** — 高频触发的反思沉淀成可编辑规则

R243.2 估 1-2 round。R243 全部完成后再走 R244 (O-6 持续学习 + O-10 跨 surface)。
