# R243.3 — DRIFT (Dynamic Rule Induction From Traces)

**Status**: shipped
**Date**: 2026-09-10
**Parent**: R241.2 + R241.3 + R243.1 + R243.2 + R243.2B (O-3 完整闭环) + R239 roadmap
**Tests**: aethercode-deepagents **195/195 pass, 0 regressions** (was 184 in R243.2B; +11 new)

---

## Why R243.3

R243 O-3 完整闭环里有 4 个子项，到 R243.2B 已经做了 3 个：

1. ✅ R241.2: ReasoningBank + SelfReflect + BankRecall (失败反思)
2. ✅ R241.3: 持久化 + utility decay
3. ✅ R243.1: Success reflection + Bank growth cap
4. ✅ R243.2 + R243.2B: Wire 到 talon runtime
5. **R243.3 (本 round)**: DRIFT — 把高频反思沉淀到 `AGENTS.md`

DRIFT 解决 R243 闭环的最后一公里：**让反思真的能改 agent 行为**。

- 现在: bank 里的反思只通过 `BankRecallMiddleware` 在新 session 注入 system prompt
- 加上 DRIFT: 高 utility 反思会**自动沉淀到 `AGENTS.md`**，下次 session 启动时跟手写内容一起被加载（无需 bank 介入）

这是 Voyager / AWM (paper 1 §4.2.2) 的 "技能库" 思路的最终落地：高频策略 → 可编辑规则 → 下次 session 看到。

### 论文 reference 映射

| 概念 | 来源 | R243.3 实现 |
|---|---|---|
| 技能沉淀成规则 | Voyager / AWM (paper 1 §4.2.2) | `Drift.runOnce` 写 `AGENTS.md` 的 `## Learned strategies` section |
| Utility 阈值过滤 | ReasoningBank 2025 paper §4.2.2 | `minUtility=0.7` (默认), `strict()` 0.85 |
| Top-K per kind | Voyager skill library | `topKPerKind=3` (默认) |
| Marker block 保护 | 工程 best practice (跟 ci script 共享一段文件) | `<!-- DRIFT:START --> ... <!-- DRIFT:END -->` |
| Idempotent run | 综述 arXiv:2512.13564 §5.2.4 (memory consolidation) | 重复跑同 bank + 同 config 不改文件 |

---

## What shipped

### A. Drift (3 java 新增)

#### `DriftConfig.java` (3.2 KB)
- `record DriftConfig(double minUtility, int topKPerKind, String sectionTitle, String startMarker, String endMarker, Duration staleAfter)`
- 静态工厂: `defaults()` (0.7 / 3 / "Learned strategies") + `strict()` (0.85 / 2)
- 校验: minUtility∈[0,1], topK>0, marker 非空且不同

#### `DriftResult.java` (0.8 KB)
- `record DriftResult(int unitsScanned, int unitsBelowThreshold, int unitsWritten, List<String> sectionTitles, String driftPath, boolean changed)`
- `DriftResult.noop(unitsScanned, below, path)` 静态 helper

#### `Drift.java` (10.2 KB)
- `Drift.runOnce(ReasoningBank bank, Path agentsMdPath, DriftConfig config) -> DriftResult`
- 算法: filter by utility → topK per kind → render markdown → diff replace (or append) marker block → 原子写
- **Never throws**: 文件不存在自动创建;读失败 log warn 返回 noop
- Idempotent: 重复跑不改文件 (内容相等 → `changed=false`)

#### Marker block 格式
```markdown
<!-- DRIFT:START -->
## Learned strategies

### file_edit
- ensure dir exists first
- import Path
- check cwd before ls

### build
- use --no-daemon

<!-- DRIFT:END -->
```

### B. ReasoningBank 加 `all()` 方法 (R243.3 小改)

- `public List<ReasoningUnit> all()` — 返回所有 unit 的 defensive copy
- 之前 R241.2/R241.3 没有这个方法;DRIFT 需要
- 加这一个方法**不改** R241.2/R241.3 公开 API (向后兼容)

### C. `TalonSelfReflectWiring.driftOnce()` helper

- 静态方法: `TalonSelfReflectWiring.driftOnce(Result wiring, Path agentsMdPath, DriftConfig driftConfig) -> DriftResult`
- wiring 不 enabled → 返回 noop
- wiring 不带 bank → 返回 noop
- 否则转发到 `Drift.runOnce(wiring.bank(), agentsMdPath, driftConfig)`

### D. DriftTest (11 tests)

| Test | 覆盖 |
|---|---|
| `emptyBankProducesNoop` | 空 bank 不报错, returned noop |
| `missingFileIsCreated` | AGENTS.md 不存在自动创建 |
| `preservesHostContentOutsideMarkers` | marker 外的 host 内容保留; 旧 strategy 被替换 |
| `topKPerKindCapsOutput` | 5 个同 kind unit → topK=3 只写 3 |
| `minUtilityFiltersOutLowUtilityUnits` | 0.4 utility unit 不写入, unitsBelowThreshold=1 |
| `multipleKindsAreGrouped` | 多 kind 分别 `### kind` section |
| `idempotentRunLeavesFileUnchanged` | 重复跑 unchanged, 文件大小不变 |
| `customMarkersCoexistWithDefaults` | 自定义 `<!-- AUTOGEN:BEGIN/END -->` 不污染默认 marker |
| `appendsBlockWhenNoMarkerPresent` | 无 marker 时 append 到末尾, host preamble 在前 |
| `blankFixStrategyIsSkipped` | 空白 strategy 不产生 list item |
| `talonSelfReflectWiringDriftOnceConvenience` | TalonSelfReflectWiring.driftOnce 端到端通 |

---

## Tests (11 new)

| 套件 | tests | 覆盖 |
|---|---:|---|
| `DriftTest` | 11 | empty bank / 文件创建 / 保留 host 内容 / topK cap / utility 过滤 / 多 kind / 幂等 / 自定义 marker / append 模式 / 空白策略 / wiring 集成 |

### Cumulative (aethercode-deepagents module)

```
Before R243.3:  184 tests  (R241.2 + R241.3 + R243.1 + R243.2 + 之前累积)
+ R243.3:        11 tests
──────────────────────────
Total:          195 tests, 0 failures, 0 errors, 0 regressions
```

Surefire 跑约 4.0 秒（跟 R243.2B 一样），drift test 主要时间在 file IO。

### Build artifacts

- `aethercode-deepagents-0.1.0-SNAPSHOT.jar` (含 Drift / DriftConfig / DriftResult / ReasoningBank.all())
- `aethercode-talon-0.1.0-SNAPSHOT.jar` (含 R243.2B 的 wiring + R243.3 的 driftOnce helper)
- mvn install 已完成，jar 在 m2 repo

---

## Files

### NEW (R243.3)

- `aethercode-deepagents/.../selfimprove/DriftConfig.java` (3.2 KB)
- `aethercode-deepagents/.../selfimprove/DriftResult.java` (0.8 KB)
- `aethercode-deepagents/.../selfimprove/Drift.java` (10.2 KB)
- `aethercode-deepagents/.../test/.../selfimprove/DriftTest.java` (8.3 KB, 11 tests)

### MOD (R243.3)

- `aethercode-deepagents/.../selfimprove/ReasoningBank.java` (+1 方法 `all()`)
- `aethercode-deepagents/.../selfimprove/TalonSelfReflectWiring.java` (+1 静态方法 `driftOnce()`)

---

## 设计决定 (R243.3)

1. **半自动 opt-in, 不在 hot path** — DRIFT 写 host 文件不在 agent loop 同步触发;host 自己决定时机 (cron / 每 N 次 / session 结束)
2. **Marker block 保护 (`<!-- DRIFT:START/END -->`)** — host 写的内容在 marker 之外,DRIFT 永远不动;用户可换 marker 名避免跟其他工具冲突
3. **Top-K per kind (默认 3)** — 防止 DRIFT 段无限膨胀;只保留"被验证有用"的 top
4. **Utility 阈值 (默认 0.7)** — 0.5 初始 + 4 次 touch (0.05/次) = 0.7,只采"被 recall 4 次以上"的反思
5. **`ReasoningBank.all()` 用 `List.copyOf(byId.values())`** — defensive copy,不影响现有 `byId` 并发安全
6. **`Drift` 静态方法 + `TalonSelfReflectWiring.driftOnce` 转发** — 单一权威 (Drift) + wiring 一行 helper
7. **写文件用 tmp+rename 原子写** — POSIX `ATOMIC_MOVE` + Windows `REPLACE_EXISTING` fallback (跟 R241.3 bank 持久化一致)
8. **Idempotent 写判断 (updated.equals(original))** — 重复跑不污染文件 mtime;`changed=false` 让 host 知道不必关心
9. **Never throws** — DRIFT 写失败 log warn 返回 noop,host 不用 try/catch
10. **Default `staleAfter=null` (不启用)** — 暂不实现 staleness filter,留 R244+ (decay + stale 双重保险)

---

## What did NOT ship in R243.3 (deferred to R244+)

1. **DRIFT 触发器自动 idle tick** — host 仍需手动调 `Drift.runOnce`;R244+ 接 agent runtime idle tick 自动跑
2. **O-8 全局审计** — bank growth rate / utility distribution / failure pattern trending 没接 `MemoryAudit`
3. **`staleAfter` 实现** — config 字段已留,未实装
4. **DRIFT 反向同步** — 现在 DRIFT 写 bank → file,反向 file → bank 留 R244 (用户编辑 AGENTS.md 后, agent 怎么知道)
5. **多 section** — 现在 DRIFT 写一个 section;未来支持多 section (per-assistant, per-project) 留 R244
6. **CLI command** — `talon drift run` CLI 入口留 R244 (bank() getter 已 expose 但没 CLI wrapper)

---

## 实战 (R243.3 之后)

**用法 1: 手动调 (host-driven)**
```java
TalonSelfReflectWiring.Result wiring = TalonSelfReflectWiring.build(
    assistantDir, chatClient, env);
Path agentsMd = assistantDir.resolve("AGENTS.md");
DriftResult result = TalonSelfReflectWiring.driftOnce(
    wiring, agentsMd, DriftConfig.defaults());
log.info("drift: scanned={} written={} changed={}",
    result.unitsScanned(), result.unitsWritten(), result.changed());
```

**用法 2: 周期 cron**
```java
// Every 6 hours
scheduler.scheduleAtFixedRate(() -> {
    DriftResult r = TalonSelfReflectWiring.driftOnce(
        runtime.bank().orElse(null),
        runtime.assistantDir().resolve("AGENTS.md"),
        DriftConfig.defaults());
}, 0, 6, TimeUnit.HOURS);
```

**用法 3: session 结束 (host-managed)**
```java
// Before session close
TalonSelfReflectWiring.driftOnce(
    wiring, agentsMd, DriftConfig.defaults());
```

**AGENTS.md 输出示例 (after DRIFT)**
```markdown
# My Assistant

Hand-written rules here.

<!-- DRIFT:START -->
## Learned strategies

### file_edit
- ensure dir exists first
- import Path before use

### build
- use --no-daemon for faster build

<!-- DRIFT:END -->

More hand-written content below.
```

**Opt-in via env**:
```bash
DEEPAGENTS_TALON_DRIFT=true java -jar aethercode-talon-0.1.0-SNAPSHOT.jar
# (host-managed: only enable when host calls Drift.runOnce)
```

---

## 教训 (R243.3)

1. **PowerShell `Set-Content -Encoding UTF8` 加 BOM** — DriftTest 第一版用 PowerShell `Set-Content` 写文件，加了 BOM (`EF BB BF`) 头导致 javac 编译失败 (import 块报"需要 class/interface")。修: 用 `[System.Text.UTF8Encoding]::new($false)` 写 no-BOM。**结论: Windows 写 .java 文件用 no-BOM UTF-8**
2. **`(Get-Content -Raw) -replace` 不可控** — 第一版想批量加 `throws Exception`，结果连 `void class test` 都被误替换，文件被破坏。修: 重写整个文件。**结论: PowerShell `-replace` 在精确匹配失败时宁可重写**
3. **`ReasoningBank.all()` 公开方法补完** — R241.2/R241.3 没提供 `all()`,DRIFT 需要时补 1 个;保持向后兼容 (新方法)
4. **Idempotent 写的 value** — `updated.equals(original)` 检查让 host 不必担心 "DRIFT 写了吗";`changed=false` 表明无变化
5. **DRIFT 静态方法** — 不需要 instance, 跟 Drift.runOnce 风格一致; host 写 `Drift.runOnce(bank, path, config)` 一行
6. **`Drift.runOnce` never throws** — host 在 cron / finally 块调不用 try/catch;IO 错误降级 noop
7. **Top-K 默认 3** — Voyager 论文没明说,这是工程经验: top-3 足够覆盖"最常用的 3 个策略",又不至于淹没 AGENTS.md
8. **Marker 用 `<!-- -->` HTML comment** — markdown 渲染时不可见,host 编辑器可折叠;不是所有 markdown 都支持, 但 GitHub / IntelliJ 都支持
9. **`appendToSystemMessage` 风格 (R241.2) 跟 DRIFT 共享同一设计** — 都是"原子写 + 保留 host 内容";一致性降低 host 学习成本

---

## 关键文件 SHA / 路径

**新增 4 java** (在 `aethercode-deepagents/.../selfimprove/`):
- `DriftConfig.java` (3.2 KB)
- `DriftResult.java` (0.8 KB)
- `Drift.java` (10.2 KB)
- `DriftTest.java` (8.3 KB, 11 tests)

**修改 2 java**:
- `ReasoningBank.java` (+1 方法 `all()`)
- `TalonSelfReflectWiring.java` (+1 静态方法 `driftOnce()`)

**报告**:
- `doc/项目文档/R243-3-DRIFT-BANK-TO-AGENTS.md` (本文)
- 父报告: `doc/项目文档/R243-2B-TALON-WIRE-ACTIVE.md`
- 祖: `doc/项目文档/R241-EXPERIENCESTORE-STRATEGY-LIBRARY.md`

---

## Backups

- R241.2/R241.3/R243.1/R243.2/R243.2B 报告保持原样
- R243.3 是 R243 O-3 完整闭环的最后一个子项
- 出包策略：R243.3 跟前 6 round 一起下次 release 时整体出 0.2.58

---

## Next (R243 收尾完成, 路线图剩余)

按 R239 路线图剩:
1. **R244 O-6 binary self-eval + metric** (2-3 round)
2. **R244 O-10 跨 surface** (1-2 round)
3. **出包 0.2.58** (1 round, 工程里程碑)
