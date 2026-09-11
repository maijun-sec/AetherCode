# R245.4 — Confidence-Aware DRIFT (R243.3 + R244.1 接桥)

> **状态**: ✅ 完成
> **模块**: aethercode-deepagents (Java)
> **日期**: 2026-09-10
> **触发**: R245.3 报告 §8 "R245+ scope 候选" — Confidence-aware DRIFT (R243.3 + R244.1 排序公式升级)

---

## 1. 背景与动机

R243.3 写了 `Drift.runOnce` — 把 bank 里高 utility 的 unit 写进 `AGENTS.md` 的 marker block,
让下一 session 启动时通过 `AGENTS.md` 读到 bank 经验。**排序公式用 `utility desc`**(只 utility 一个因子)。

R244.1 升级了 `BankRecallMiddleware` 排序公式 — 用 `confidence × utility` 双因子:
- `confidence` = Laplace-smoothed `okCount / (okCount + notOkCount + 1)` (R244.1)
- 高 utility 但 0 obs 的 unit (confidence=0) 自动被压低

**不一致的代价**:
- DRIFT 写进 `AGENTS.md` 的 "top 经验" 跟 recall 给 LLM 看的 "top 经验" 是**两套不同排名**
- user 看 `AGENTS.md` 觉得 "这条 fix_strategy 0.95 utility 肯定很强" → 下一 session recall 看到另一条 0.75 utility 但 3 ok 排前面 — 困惑
- 一个高 utility 错误 unit 在 `AGENTS.md` 永远占着坑,实际 recall 早就靠后了

R245.4 让 DRIFT 跟 `BankRecallMiddleware` 用**同一套排序公式**:
**`confidence × utility desc, uses desc, createdAt desc, id asc`**。

---

## 2. 实际产出

### 2.1 修改 Java 文件 (2)

| 文件 | 改动 |
|------|------|
| `Drift.java` | +8 行 doc comment + 改排序 comparator (`utility desc` → `confidence × utility desc`) |
| `DriftTest.java` | +2 confidence-aware test (高 utility 0 obs 应该输给低 utility 高 obs; tie 用 uses 决) |

### 2.2 测试结果

- **aethercode-deepagents: 230/230 pass** (R245.3 228 + R245.4 2 新增)
- **aethercode-talon: 5/5 pass** (0 回归)
- **aethercode-memory: 513/513 pass** (0 回归)

---

## 3. 设计与关键技术决定

### 3.1 DRIFT 跟 BankRecall 排序公式严格一致

R243.3 (旧) DRIFT:
```java
list.sort(Comparator
        .comparingDouble(ReasoningUnit::utility).reversed()
        .thenComparing(Comparator.comparingLong(ReasoningUnit::uses).reversed())
        .thenComparing(Comparator.comparing(ReasoningUnit::id)));
```

R244.1 BankRecall:
```java
merged.sort(Comparator
        .comparingDouble((ReasoningUnit u) -> u.confidence() * u.utility()).reversed()
        .thenComparing(Comparator.comparingLong(ReasoningUnit::uses).reversed())
        .thenComparing(Comparator.comparing(ReasoningUnit::createdAt).reversed()));
```

R245.4 (新) DRIFT:
```java
list.sort(Comparator
        .comparingDouble((ReasoningUnit u) -> u.confidence() * u.utility()).reversed()
        .thenComparing(Comparator.comparingLong(ReasoningUnit::uses).reversed())
        .thenComparing(Comparator.comparing(ReasoningUnit::id)));
```

**差异**:
- 主因子: `utility` → `confidence × utility` (跟 BankRecall 对齐)
- 第三 tie-break: `createdAt desc` (BankRecall) vs `id asc` (DRIFT 旧)
  - 保持 DRIFT 的 `id asc` 因为**渲染 Markdown bullet 列表时,稳定顺序对 diff 友好** — 同 bank 同一内容,DRIFT 重写不产生 noise diff

### 3.2 排序公式是 single source of truth

```java
// BankRecallMiddleware.java line 165
.sort by (confidence × utility) desc, uses desc, createdAt desc

// Drift.java line 114 (R245.4 改)
.sort by (confidence × utility) desc, uses desc, id asc
```

**没有提取到公共类** — 写两份的成本低,且两份不同 module 不共享代码。
**未来 R246+ 如果加第三种排序** (e.g. CLI report),再抽到 `UnitRanking` 工具类。

### 3.3 `DriftConfig` 不动

DriftConfig 只控 minUtility (默认 0.7) + topKPerKind (默认 3) + 标记文本 —
**排序公式跟 config 无关**。`confidence × utility` 永远是这个公式,
config 只能控制 "什么 unit 有资格上榜" (minUtility gate) 跟 "每个 kind 上多少" (topKPerKind)。
**不加 `useConfidence` boolean 开关** — 跟 R244.1 BankRecall 一样,confidence-aware 是默认行为,不 opt-out。

### 3.4 test 1: 高 utility 0 obs 应该输给低 utility 高 obs

```java
@Test
void confidenceAware_highConfidence_beats_higherUtilityZeroObs(@TempDir Path tmp) {
    // u_a: utility 0.95, 0 obs → confidence 0/1 = 0, score = 0
    // u_b: utility 0.75, 3 ok / 0 notOk → confidence 3/4 = 0.75, score = 0.5625
    // R245.4: u_b ranks FIRST
}
```

这是 R245.4 的核心断言 — **没改前 u_a 会赢,改了后 u_b 赢**。
"pin 住" 排序行为,让未来 refactor 不能静默退化回 utility-only。

### 3.5 test 2: tie 用 uses desc 决

```java
@Test
void confidenceAware_breaks_ties_by_uses(@TempDir Path tmp) {
    // 两个 unit 都有 score 0.6, tie
    // 旧用 id asc tie break
    // 期望: high-uses 排前 (跟 score 同序)
}
```

Tie-break 测试 — 排序 key 链 `score > uses > id` 中间一环验证。

---

## 4. 实战

### 4.1 DRIFT 写进 AGENTS.md 的内容

```markdown
<!-- DRIFT:START -->
## Learned strategies

### file_edit
- trusted quiet strategy        ← R245.4 排序第一 (高 confidence)
- zero-obs loud strategy        ← R245.4 排序第二 (utility 高但 0 obs)

### build
- verified reliable build

<!-- DRIFT:END -->
```

### 4.2 跟 recall 对齐的 UX

```
下一 session 启动 → 读 AGENTS.md:
  "trusted quiet strategy" 出现在 [经验] 段
  recall() 返回同一组 "trusted quiet strategy" 排第一
  → user 看到 AGENTS.md 跟 LLM 看到的 "top 经验" 一致
```

### 4.3 没 R245.4 之前的混乱

```markdown
<!-- 旧 DRIFT (R243.3): -->
### file_edit
- zero-obs loud strategy        ← 旧排序第一 (utility 0.95)
- trusted quiet strategy        ← 旧排序第二 (utility 0.75)

下一 session 读 AGENTS.md: "zero-obs loud" 第一
但 recall() 真正给 LLM: "trusted quiet" 第一  ← 不一致!
```

R245.4 消除这种 UX 撕裂。

---

## 5. R245.4 vs R245+ 估计

| R245+ 估计 | **R245.4 实际** |
|---|---|
| "Confidence-aware DRIFT 1 round" | **< 0.5 round (2 改, 2 新 test, 0 回归)** |

**连续 18 round 全部 < 1 round**: R240/R240.2/R241.1/R242.1/R242.2/R241.2/R241.3/R243.1/R243.2/R243.2B/R243.3/R244.1/R244.2/R244.3/R245.1/R245.2/R245.5/R245.3/R245.4

R245.4 scope 最小 — 排序 comparator 一行 + 2 个新 test。零踩坑。

---

## 6. 关键文件路径

### 6.1 修改 (2)

```
D:\work\workspace\idea\engine\AetherCode\aethercode\aethercode-deepagents\src\main\java\org\aethercode\deepagents\selfimprove\Drift.java        (+8 行 doc + 1 行 comparator)
D:\work\workspace\idea\engine\AetherCode\aethercode\aethercode-deepagents\src\test\java\org\aethercode\deepagents\selfimprove\DriftTest.java   (+2 confidence-aware test)
```

---

## 7. 教训 (R245.4 新增 4 条)

1. **DRIFT 跟 BankRecall 排序必须一致** — user 看到 AGENTS.md 跟 LLM 看到的 "top 经验" 必须同序, 否则 UX 撕裂
2. **R244.1 引入 confidence 公式是排序的"双因子扩展点"** — 凡是 R243.3 之前用 `utility` 排序的地方, R245.4 都要对齐到 `confidence × utility`
3. **Tie-break 故意保留 `id asc` 而不是 `createdAt desc`** — 渲染 Markdown bullet 列表时稳定顺序对 diff 友好, AGENTS.md 多次重写不产生 noise diff
4. **Test 1 pin 住核心断言** — "高 utility 0 obs 输给低 utility 高 obs" 是 R245.4 跟 R243.3 的**唯一行为差异**, 必须有 test 锁住防止未来回退

---

## 8. R243.3 → R245.4 接桥完成

| 阶段 | 排序公式 | 状态 |
|------|---------|------|
| R243.3 DRIFT | `utility desc` | ✅ |
| R244.1 BankRecall | `confidence × utility desc` | ✅ |
| **R245.4 DRIFT 对齐** | `confidence × utility desc` | ✅ |

**R245.4 之后,O-3 (DRIFT) + O-6 (confidence) + O-6 (self-eval) 三个 round 的成果在 bank / DRIFT / recall 三处都保持同序**:
- `AGENTS.md` 写的高 quality 经验 (DRIFT 排序) = LLM 真正 recall 看到的 (BankRecall 排序) = TUI `/memory-audit` 报告的最强/最弱 kind (R245.2)

---

**总结**: R245.4 用 1 行 comparator 改动 + 2 个新 test 让 DRIFT 跟 BankRecall 排序公式对齐。
230 + 5 + 513 tests 全绿,0 回归。
bank 经验在 bank / DRIFT / recall 三处保持**一致排名**。
