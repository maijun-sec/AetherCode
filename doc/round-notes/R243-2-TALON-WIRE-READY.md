# R243.2 — Wire O-3 to Talon Runtime (Wire-Ready)

**Status**: wire-ready (not yet wire-active)
**Date**: 2026-09-10
**Parent**: R241.2 + R241.3 + R243.1 (O-3 ExperienceStore → 策略库 + 持久化 + 完整闭环)
**Tests**: aethercode-deepagents **184/184 pass, 0 regressions** (was 174 in R243.1; +10 new)

---

## Why R243.2

R241.2 / R241.3 / R243.1 把 ReasoningBank + SelfReflectMiddleware + SuccessReflectMiddleware + BankRecallMiddleware 全写在 `aethercode-deepagents` 模块里，**但 aethercode-talon（前端 runtime）当前根本没 wire 这些**：

```java
// aethercode-talon/.../DeepAgentRuntime.java line 115-129
DeepAgent agent = CreateDeepAgent.createDeepAgent(
        resolveModel(),
        List.of(),
        resolveSystemPrompt(),
        List.of(),
        (List<?>) (subagents == null ? List.of() : subagents),
        resolveSkillSources(),
        resolveMemory(),
        List.of(),  // ← middleware 是空的! O-3 完全没接
        backend, ...);
```

加上 `aethercode-talon/pom.xml` 当时**根本没依赖 `aethercode-deepagents` 或 `aethercode-engine-springai`**，就算想 wire 也拿不到。

这就是用户说的"前端没有把 aethercode-deepagents 应用起来"的根本原因。

R243.2 把"wire 能力"准备好：
- `aethercode-deepagents` 模块提供 `TalonSelfReflectWiring` 一键 builder
- `aethercode-talon` 加 `aethercode-deepagents` + `aethercode-engine-springai` 依赖
- **真正接通**留 R243.2B（修 talon 的 stale imports + 让 talon 真编）

R243.2B 之所以分两步：talon 当前的 `skipMain=true` 是 stub 状态，修 stale imports 是独立技术债（`org.aethercode.backends.*` 用了不存在的顶级 package，实际在 `core.fs.backend`），不适合跟 wire O-3 混在一个 round。

### 论文 reference 映射

| 概念 | 来源 | R243.2 实现 |
|---|---|---|
| 默认 best practice wiring | Reflexion/ReasoningBank 论文未规定 → 实务 | `TalonSelfReflectWiring` 集中默认（7d decay + 1000 LRU cap + 1 success/turn） |
| Fail-safe 反射器 | 任何 survey 都没明说，但生产环境必备 | `null ChatClient` → `StubReflector`；`null assistantDir` → in-memory bank |
| 显式 opt-out | Memory survey (arXiv:2512.13564) §7.7 提到 user control | `DEEPAGENTS_TALON_SELFREFLECT=false` 全部关掉 |

---

## What shipped

### 1. `TalonSelfReflectWiring` (9.7 KB) — R243.2 新增

放在 `aethercode-deepagents/.../selfimprove/TalonSelfReflectWiring.java`（在 deepagents 模块里，让任何 host — 不止 talon — 都能复用）。

**API**:
```java
TalonSelfReflectWiring.Result r =
    TalonSelfReflectWiring.build(assistantDir, chatClient, env);
// r.middlewares() → 传给 CreateDeepAgent.create(..., middleware, ...)
// r.bank() → 暴露给 CLI/inspect
// r.bankDir() → 给 ops 看的实际路径
// r.enabled() → boolean, 反映 opt-out
// r.reason() → String, "ok" / "opt-out env var set" / etc
```

**默认配置**:
- bank 路径: `${assistantDir}/.aethercode/reasoning-bank/`
- decay: `ExponentialDecay(7d)` half-life
- cap: `LruEviction(1000)`
- max success reflection per turn: 1
- 3 个 middlewares: `SelfReflectMiddleware` + `SuccessReflectMiddleware` + `BankRecallMiddleware`

**env knobs (all optional)**:
- `DEEPAGENTS_TALON_SELFREFLECT=false` → opt-out (返回空 list + no-op bank)
- `DEEPAGENTS_TALON_BANK_DECAY_DAYS=N` → 自定义半衰期
- `DEEPAGENTS_TALON_BANK_MAX=N` → 自定义 LRU cap

**Fail-safe 路径**:
- `assistantDir == null` → in-memory bank + log warn
- `chatClient == null` → `StubReflector` + log warn
- 任何 RuntimeException 走 `catch (Exception)` + 降级 in-memory
- 任何 env 解析错误（"not-a-number"）走 default + log warn

### 2. `aethercode-talon/pom.xml` — 加依赖

```xml
<dependency>
    <groupId>org.aethercode</groupId>
    <artifactId>aethercode-deepagents</artifactId>
    <version>${project.version}</version>
</dependency>
<dependency>
    <groupId>org.aethercode</groupId>
    <artifactId>aethercode-engine-springai</artifactId>
</dependency>
```

注意 `aethercode-talon` 仍然 `skipMain=true` (stub 模式)，因为 talon 当前有 stale imports（`org.aethercode.backends.*` 等），等 R243.2B 修了 stale imports 才能真编。R243.2 只让 talon 知道 deepagents 存在。

### 3. `TalonSelfReflectWiringTest.java` (10 tests) — R243.2 新增

覆盖：
- `defaultBuildReturnsThreeMiddlewares` — 默认 build 返回 3 个 middlewares + bank 路径创建
- `middlewaresContainExpectedKinds` — name() 包含 `SelfReflectMiddleware` / `SuccessReflectMiddleware` / `BankRecallMiddleware`
- `optOutEnvReturnsEmptyMiddlewares` — `DEEPAGENTS_TALON_SELFREFLECT=false` 关掉
- `optOutEnvAcceptsMultipleFalsyStrings` — false/0/no/off/FALSE 都行
- `nullAssistantDirFallsBackToInMemoryBank` — assistantDir=null 走 in-memory
- `nullChatClientFallsBackToStubReflector` — chatClient=null 不崩
- `customDecayAndCapAreHonoured` — env 配置生效 (cap=5, add 6 → 留 5)
- `invalidDecayAndCapFallBackToDefaults` — 错误配置 (非数字/负数) 走 default
- `bankPersistsAcrossInstances` — 两次 build 同 dir, 第二次能 recall 第一次
- `resultIsImmutableMiddlewaresList` — 返回的 list 不可变

---

## Tests (10 new)

| 套件 | tests | 覆盖 |
|---|---:|---|
| `TalonSelfReflectWiringTest` | 10 | default wiring、env opt-out (多种 falsy)、null assistantDir、null chatClient、env-driven cap、env parsing 错误处理、跨实例持久化、immutable result |

### Cumulative (aethercode-deepagents module)

```
Before R243.2:  174 tests  (R241.2 + R241.3 + R243.1 + 之前累积)
+ R243.2:        10 tests
──────────────────────────
Total:          184 tests, 0 failures, 0 errors, 0 regressions
```

Surefire 跑约 4.0 秒（R243.1 时 3.9 秒，多 0.1 秒主要是 file storage 路径 + JsonFile bank 初始化）。

---

## Files

### NEW (R243.2)

- `aethercode-deepagents/.../selfimprove/TalonSelfReflectWiring.java` (9.7 KB)

### NEW (R243.2 tests)

- `aethercode-deepagents/.../test/.../selfimprove/TalonSelfReflectWiringTest.java` (7.4 KB, 10 tests)

### MOD (R243.2)

- `aethercode-talon/pom.xml` — 加 `aethercode-deepagents` + `aethercode-engine-springai` 依赖（保 skipMain stub）

---

## 设计决定 (R243.2)

1. **Wiring utility 放 deepagents 不放 talon** — deepagents 是 owner of SelfReflect/SuccessReflect/BankRecall，wiring 逻辑跟 deepagents 强绑定。其他 host (e.g. R242 的 VLM, R244 的 runtime) 也能复用同一个 utility
2. **不修 talon stale imports** — `org.aethercode.backends.*` 等用的是不存在的顶级 package，是独立技术债，scope 隔离避免爆炸
3. **保留 talon skipMain=true** — 让 R243.2 干净 (只加依赖 + 写 utility)；等 R243.2B 修 stale imports 后再真接通
4. **Fail-safe 全开** — `null chatClient` 走 StubReflector；`null assistantDir` 走 in-memory；所有 env 解析错走 default + log warn。**Host 永远能启动**
5. **默认 7d decay + 1000 LRU cap + 1 success/turn** — 跟 R241.3/R243.1 实战经验一致 (中等强度，可调 env)
6. **env opt-out 而非开关** — `DEEPAGENTS_TALON_SELFREFLECT=false` 一个变量关全部；不想 "部分关" 因为 partial disable 让 bank 写出但 recall 不读，状态不一致
7. **`Result.bankDir` 可能为 null** — 反映"降级到 in-memory"是真实状态；让 host 能 log 给 ops 知道
8. **`Result.middlewares` 是 immutable** — host 拿到后不会被 builder 内部状态污染

---

## R243.2 留待 R243.2B (真接通)

R243.2 只是 wire-ready，**还没 wire-active**。talon `DeepAgentRuntime.start()` 还是 `List.of()`。

R243.2B scope 估 1 round：
1. 修 talon stale imports (5+ classes: `BackendProtocol` / `LocalShellBackend` / `CreateDeepAgent` / `DeepAgent` / `SkillSource` / `InterruptOnConfig` 等)
2. 取消 talon `skipMain=true`
3. 改 `DeepAgentRuntime.start()`：调 `TalonSelfReflectWiring.build(assistantDir, chatClient, env)` + 把 `result.middlewares()` 传给 `createDeepAgent`
4. 改 `Main.java`：把 `config.manifestDir()` 透传给 runtime
5. 加 `DeepAgentRuntimeWiringTest` 验证 wire 起效

R243.2B 之后 O-3 才真正"上线"到前端 runtime。

---

## What did NOT ship in R243.2 (deferred)

1. **talon 真接通** — 留 R243.2B
2. **Decay 触发器自动 idle tick** — R243.2A 留候选
3. **O-8 全局审计 hook bank 写** — 留 R243.2A
4. **DRIFT (bank → AGENTS.md)** — 留 R243.3
5. **aethercode-engine-springai 真接通** — `TalonSelfReflectWiring.resolveReflector` 接受 `ChatClient`，但当前 host 传 null；等 R244+ 接 `SpringAiChatClient` 默认 MiniMax-M3
6. **CLI inspection command** — `Result.bank()` 已 expose，但还没 CLI (`talon bank list` / `talon bank show <id>`) — 留 R244

---

## 教训 (R243.2)

1. **Wiring utility 应该在 owner 模块，不在 consumer 模块** — SelfReflect 在 deepagents，wiring 也应该在 deepagents。放 talon 容易跟 talon 的其他 wiring 混
2. **talon stale imports 是技术债** — 当时 port 时用了 `org.aethercode.backends.*` 等不存在的顶级 package，实际在 `core.fs.backend` / `deepagents.graph` 等子包下。R230-R242 累积没修。这次 R243.2 主动分两步避免爆炸
3. **`catch (RuntimeException re)` 漏 checked exception** — `Files.createDirectories` 抛 `IOException`，编译错。修：catch 改成 `Exception`。**结论：catch 广一点比漏掉安全**
4. **null ChatClient 必须有降级** — 否则生产环境 ChatClient 不可用时整个 Talon 启动失败 → 所有 agent 都不能用。StubReflector + log warn 让运行时仍能起来
5. **env-driven opt-out 优于 opt-in** — 默认开是"新特性应该默认让用户体验"的体现，opt-out 让担心的人能关掉。R243.2 选默认开
6. **Result 用 record** — 中间数据 shape 一次定义清楚，host 调用方零歧义
7. **Path 永远不 throw** — wiring 是 startup helper，抛异常 host 启动失败。降级到 in-memory + log warn 是"宁可降级也不崩"
8. **测试覆盖 null 参数路径** — `null assistantDir` / `null chatClient` 两个 case 都测，避免生产环境被这两个 null 击中

---

## 关键文件 SHA / 路径

**新增 1 java** (在 `aethercode-deepagents/.../selfimprove/`):
- `TalonSelfReflectWiring.java` (9.7 KB)

**修改 1 pom**:
- `aethercode-talon/pom.xml` (加 2 依赖)

**新增 1 test 套件** (在 `aethercode-deepagents/.../test/.../selfimprove/`):
- `TalonSelfReflectWiringTest.java` (7.4 KB, 10 tests)

**报告**:
- `doc/项目文档/R243-2-TALON-WIRE-READY.md` (本文)
- 父报告: `doc/项目文档/R241-EXPERIENCESTORE-STRATEGY-LIBRARY.md`
- 兄弟: `doc/项目文档/R241-3-PERSISTENCE-UTILITY-DECAY.md`
- 兄弟: `doc/项目文档/R243-1-SUCCESS-REFLECTION-AND-GROWTH-CAP.md`

---

## Backups

- R241.2/R241.3/R243.1 报告保持原样
- R243.2 是 R243 O-3 完整闭环的 wire-ready 部分
- R243.2B (下一 round) 才真正接通到 talon runtime
- 出包策略：R243.2 跟之前 R 系列一起下次 release 时整体出 0.2.58

---

## Next (R243.2B scope)

1. 修 talon 5+ 个 stale imports (BackendProtocol / DeepAgent / SkillSource / InterruptOnConfig 等)
2. 取消 talon `skipMain=true`
3. 改 `DeepAgentRuntime.start()` 调 `TalonSelfReflectWiring.build(...)` + 传 middlewares
4. 改 `Main.java` 透传 `config.manifestDir()`
5. 加 `DeepAgentRuntimeWiringTest` 验证 wire 起效

R243.2B 估 1 round。R243.2B 完成后 O-3 才真正"上线"前端 runtime。
