# R243.2B — Talon Wire-Active (O-3 真正"上线"到前端 runtime)

**Status**: shipped
**Date**: 2026-09-10
**Parent**: R243.2 (wire-ready utility) + R241.2/R241.3/R243.1 (O-3 策略库 + 持久化 + 完整闭环)
**Tests**: aethercode-talon **5/5 pass** (was 0 in R243.2) + aethercode-deepagents **184/184 pass, 0 regressions**

---

## Why R243.2B

R243.2 把 `TalonSelfReflectWiring` 写好了，但**只是 wire-ready**：talon runtime 还在传空 middleware list，O-3 没真正"上线"到前端。

R243.2B 解决：**O-3 真正打到前端能调用的地方** — 打成 jar 后，部署 aethercode-talon 时，agent loop 就会真的经过 SelfReflect / SuccessReflect / BankRecall 三个 middleware，文件持久化到 `${assistantDir}/.aethercode/reasoning-bank/`，跨 session 共享 reflection。

### 前置技术债

R243.2B 顺手清理了 talon 的 stale imports 累积（5+ classes，3 个文件），这些 stale imports 让 talon 之前根本 `skipMain=true` 编译不通过：

| 文件 | stale import | 实际位置 |
|---|---|---|
| `DeepAgentRuntime.java` | `org.aethercode.backends.BackendProtocol` | `org.aethercode.core.fs.backend.BackendProtocol` |
| `DeepAgentRuntime.java` | `org.aethercode.backends.LocalShellBackend` | `org.aethercode.core.fs.backend.LocalShellBackend` |
| `DeepAgentRuntime.java` | `org.aethercode.graph.CreateDeepAgent` | `org.aethercode.deepagents.graph.CreateDeepAgent` |
| `DeepAgentRuntime.java` | `org.aethercode.graph.DeepAgent` | `org.aethercode.deepagents.graph.DeepAgent` |
| `DeepAgentRuntime.java` | `org.aethercode.middleware.SkillSource` | `org.aethercode.core.middleware.SkillSource` |
| `DeepAgentRuntime.java` (line 234) | `org.aethercode.graph.DeepAgent` (instanceof 引用) | `org.aethercode.deepagents.graph.DeepAgent` |
| `AsyncSubagents.java` | `org.aethercode.middleware.AsyncSubAgent` | `org.aethercode.deepagents.middleware.AsyncSubAgent` |
| `Mcp.java` | `org.aethercode.langchain_compat.tools.BaseTool` | `org.aethercode.deepagents.langchain_compat.tools.BaseTool` |
| `InterruptOnConfigHelper.java` | `org.aethercode.langchain_compat.middleware.InterruptOnConfig` | `org.aethercode.deepagents.langchain_compat.middleware.InterruptOnConfig` |

这些 stale imports 是 R230-R242 累积的，talon 当时用 `skipMain=true` 编译 stub 跳过。R243.2B 全部修复 + 取消 `skipMain` + 取消 `skipTests` — talon 真编真测。

---

## What shipped

### 1. talon stale imports 全部修复 (3 文件)

见上表。

### 2. `aethercode-talon/pom.xml` 取消 stub

- **取消 `skipMain=true`** — talon main code 真编译
- **取消 `skipTestCompile=true`** — talon test code 真编译
- **取消 `skipTests=true`** — talon tests 真跑
- **保留 `aethercode-deepagents` + `aethercode-engine-springai` 依赖**（R243.2 加的）

### 3. `DeepAgentRuntime.start()` 调 wiring (R243.2B 关键改动)

**Before**:
```java
DeepAgent agent = CreateDeepAgent.createDeepAgent(
        resolveModel(),
        List.of(),
        resolveSystemPrompt(),
        List.of(),                    // ← middleware 是空的! O-3 完全没接
        (List<?>) (subagents == null ? List.of() : subagents),
        resolveSkillSources(),
        resolveMemory(),
        List.of(),
        backend, ...);
```

**After**:
```java
TalonSelfReflectWiring.Result wiring = TalonSelfReflectWiring.build(
        assistantDir, resolveChatClient(), env);
if (wiring.bank() != null) {
    bankRef.set(wiring.bank());
}
if (wiring.enabled()) {
    log.info("deep-agent self-reflect wiring active (bankDir={})", wiring.bankDir());
}
DeepAgent agent = CreateDeepAgent.createDeepAgent(
        resolveModel(),
        List.of(),
        resolveSystemPrompt(),
        new ArrayList<>(wiring.middlewares()),  // ← O-3 真正接通了
        (List<?>) (subagents == null ? List.of() : subagents),
        resolveSkillSources(),
        resolveMemory(),
        List.of(),
        backend, ...);
```

### 4. `DeepAgentRuntime.resolveChatClient()` (新方法)

构造 `SpringAiChatClient` 包装 resolveModel() 结果，fail-safe：
- `MINIMAX_API_KEY` 缺失 → `IllegalStateException` → catch + 返回 `null` → `TalonSelfReflectWiring` 走 `StubReflector` 降级
- `null` model → 默认 `MiniMax-M3`

### 5. `DeepAgentRuntime.bank()` (新 getter)

`Optional<ReasoningBank> bank()` 暴露 bank 引用给 CLI / tests。`Optional` 是因为 start() 前是 empty。

### 6. `DeepAgentRuntimeWiringTest` (5 tests) — 新增

`aethercode-talon/src/test/java/org/aethercode/talon/runtime/DeepAgentRuntimeWiringTest.java`

- `startExposesReasoningBank` — start() 后 `bank()` 非空
- `bankIsFunctionalAddThenRecall` — bank 真的能 add / recall (验证 O-3 端到端通)
- `bankPersistsAcrossInstances` — 同 dir 第二次 start, 第一次写的 unit 还在
- `optOutEnvDisablesBankWiring` — `DEEPAGENTS_TALON_SELFREFLECT=false` 不创建 `.aethercode/reasoning-bank/`
- `assistantDirMissingDoesNotPreventStart` — `null assistantDir` 走 in-memory fallback，runtime 仍启动

---

## Tests (5 new, talon 第一次真跑)

| 套件 | tests | 覆盖 |
|---|---:|---|
| `DeepAgentRuntimeWiringTest` | 5 | bank() 暴露、add/recall 通、跨实例持久化、opt-out env 生效、null assistantDir fallback |

### Cumulative

```
aethercode-deepagents:  184 tests  (R241.2 + R241.3 + R243.1 + R243.2 + 之前累积), 0 fail
aethercode-talon:         5 tests  (全新 — 之前是 stub skipTests=true), 0 fail
```

Surefire 跑 aethercode-talon 约 1.7 秒 (5 tests), aethercode-deepagents 约 5.8 秒 (184 tests)。

### Build artifacts

- `aethercode-deepagents/target/aethercode-deepagents-0.1.0-SNAPSHOT.jar` (含 TalonSelfReflectWiring + SelfReflect + SuccessReflect + BankRecall)
- `aethercode-talon/target/aethercode-talon-0.1.0-SNAPSHOT.jar` (含 DeepAgentRuntime 真接通)
- mvn install 已完成，jar 在 `~/.m2/repository/org/aethercode/`

**前端 jar 真正包含 O-3 wiring**：部署 aethercode-talon-0.1.0-SNAPSHOT.jar 时，agent loop 自动经过 3 个 O-3 middleware。

---

## Files

### MOD (R243.2B)

- `aethercode-talon/src/main/java/.../runtime/DeepAgentRuntime.java` (line 3-15 imports, line 73 bankRef field, line 115-156 start() with wiring, line 480-509 resolveChatClient, line 569-579 bank() getter)
- `aethercode-talon/src/main/java/.../runtime/InterruptOnConfigHelper.java` (line 3 import)
- `aethercode-talon/src/main/java/.../AsyncSubagents.java` (line 5 import)
- `aethercode-talon/src/main/java/.../Mcp.java` (line 3 import)
- `aethercode-talon/pom.xml` (取消 skipMain, skipTestCompile, skipTests)

### NEW (R243.2B)

- `aethercode-talon/src/test/java/.../runtime/DeepAgentRuntimeWiringTest.java` (5 tests)

---

## 设计决定 (R243.2B)

1. **3 个文件 stale imports 一次修完** — 之前积累 5+ 个独立 stale import，分散改风险高。一次性 `replace_all` + 编译跑通
2. **取消 talon `skipMain=true` 而非保留 stub** — 用户明确说"前端真正能调用"，stub 模式完全做不到
3. **`resolveChatClient` 走 `SpringAiChatClient` + fail-safe** — 默认 `MiniMax-M3`，跟 AetherCode 标准 LLM 一致；缺 API key 降级 null → StubReflector
4. **`bank()` 暴露为 `Optional<ReasoningBank>` getter** — Optional 因为 start() 前是 empty；host (CLI) 可以 list bank 内容
5. **Main.java 不用改** — `config.manifestDir()` 早就传给 DeepAgentRuntime 构造器（assistantDir 参数），wiring 直接用
6. **`new ArrayList<>(wiring.middlewares())` 防御性拷贝** — `wiring.middlewares()` 已经是 immutable，但 `new ArrayList<>` 显式给 CreateDeepAgent 喂 mutable list (它可能内部 add)
7. **`log.info` 写 "wiring active"** — 部署时一眼看到 O-3 是否启用；debug 必看 log
8. **测试用 `start().get()` 而不是 `start()`** — start() 是 async，必须等 future 完成才能验 bank() 有值

---

## 实战 (R243.2B 之后真正能跑)

**部署**:
```bash
mvn -pl aethercode-talon -am install
java -jar aethercode-talon-0.1.0-SNAPSHOT.jar
```

**自动行为** (无需改任何配置):
1. Talon 启动 → `DeepAgentRuntime.start()`
2. → `TalonSelfReflectWiring.build(assistantDir, chatClient, env)`
3. → 自动建 `${assistantDir}/.aethercode/reasoning-bank/`
4. → 3 个 middleware (SelfReflect + SuccessReflect + BankRecall) 注入到 `createDeepAgent`
5. agent loop 每个 tool 调用: 失败 → 反射写入 bank; 成功 → 反思策略写入 bank
6. 下次 agent loop: BankRecall 从 disk 加载过去的 unit，注入 system prompt 的 `<prior_reflections>` block
7. 跨 session: 第二个 talon 启动加载同一个 assistantDir, 第一个 session 写的 unit 立即可用
8. decay (7d half-life exponential) + LRU cap (1000) 自动应用

**Opt-out**:
```bash
DEEPAGENTS_TALON_SELFREFLECT=false java -jar aethercode-talon-0.1.0-SNAPSHOT.jar
```

**调整配置**:
```bash
DEEPAGENTS_TALON_BANK_DECAY_DAYS=14 \
DEEPAGENTS_TALON_BANK_MAX=500 \
java -jar aethercode-talon-0.1.0-SNAPSHOT.jar
```

---

## 教训 (R243.2B)

1. **stub 模式是技术债的"安全阀"** — talon 长期 `skipMain=true` 让 stale imports 累积到没人敢动。R243.2B 顺手清掉 5+ 个，否则 O-3 接通会被 stale imports 二次阻塞
2. **修 stale import 要先确认实际位置** — grep 当前模块的 package，不要靠记忆。R243.2B 误以为 `BackendProtocol` 在 `org.aethercode.backends`，实际在 `core.fs.backend.backend.BackendProtocol`
3. **import 错了 `Middleware`** — 第一版用 `core.middleware.Middleware`，但 `Middleware` interface 在 `deepagents.middleware`。R243.2B 编译错 → 改 import
4. **14-arg `createDeepAgent` 参数 4 是 middleware 不是 permissions** — DeepAgentRuntime 第一版按 "顺序" 猜，把 `wiring.middlewares()` 放参数 8 (实际是 permissions)，编译报 `List<Middleware> ≠ List<FilesystemPermission>`。修：把 `wiring.middlewares()` 移到参数 4
5. **CronJobStore 构造器是 `(String assistantId, Path cronDir)` 不是 `(Path)`** — 测试第一版传 `Path` 编译错。修：传 `(assistantId, cronDir)`
6. **Surefire `-Dtest=X` 在多模块下需要 `-Dsurefire.failIfNoSpecifiedTests=false`** — 否则 aethercode-core 模块会因为没匹配 test 而 fail。`-am` 模式必备
7. **`-am` 一起跑才能 cross-module 编译** — 单跑 `mvn -pl aethercode-talon test` 找不到 `aethercode-deepagents` class（因为 deepagents 没 install 到 m2）。`-am` 让 mvn 按依赖顺序构建
8. **Test 测 wiring "通"必须验 add/recall** — `bank().isPresent()` 不够，要验 bank 真的能 add 然后 recall 出来（中间件 / 持久化 / decay 都活的）。R243.2B `bankIsFunctionalAddThenRecall` 这个 test 抓到了"bank() 返回了但不可用"的潜在 bug

---

## 关键文件 SHA / 路径

**修改 5 文件**:
- `aethercode/aethercode-talon/src/main/java/.../runtime/DeepAgentRuntime.java` (主要改动)
- `aethercode/aethercode-talon/src/main/java/.../runtime/InterruptOnConfigHelper.java`
- `aethercode/aethercode-talon/src/main/java/.../AsyncSubagents.java`
- `aethercode/aethercode-talon/src/main/java/.../Mcp.java`
- `aethercode/aethercode-talon/pom.xml`

**新增 1 文件**:
- `aethercode/aethercode-talon/src/test/java/.../runtime/DeepAgentRuntimeWiringTest.java` (5 tests)

**报告**:
- `doc/项目文档/R243-2B-TALON-WIRE-ACTIVE.md` (本文)
- 父: `doc/项目文档/R243-2-TALON-WIRE-READY.md`
- 父: `doc/项目文档/R243-1-SUCCESS-REFLECTION-AND-GROWTH-CAP.md`
- 父: `doc/项目文档/R241-3-PERSISTENCE-UTILITY-DECAY.md`
- 父: `doc/项目文档/R241-EXPERIENCESTORE-STRATEGY-LIBRARY.md`

**Build artifacts**:
- `~/.m2/repository/org/aethercode/aethercode-deepagents/0.1.0-SNAPSHOT/aethercode-deepagents-0.1.0-SNAPSHOT.jar`
- `~/.m2/repository/org/aethercode/aethercode-talon/0.1.0-SNAPSHOT/aethercode-talon-0.1.0-SNAPSHOT.jar`

---

## Backups

- R241.2/R241.3/R243.1/R243.2 报告保持原样
- R243.2B 是 R243 O-3 完整闭环的 **真接通** 部分
- 出包策略：R243.2B 完成后，R240-R242 + R241.2 + R241.3 + R243.1 + R243.2 + R243.2B 全部改动可整体出 0.2.58

---

## Next (R243.3+ scope 候选)

1. **R243.3 DRIFT (bank → AGENTS.md)** — 高频触发的反思沉淀成可编辑规则 (1-2 round)
2. **R244 O-6 持续学习 + O-10 跨 surface** — binary self-eval + metric (2-3 round)
3. **出包 0.2.58** — R240-R242 + R241.2 + R241.3 + R243.1 + R243.2 + R243.2B 整体 (1 round, 可选)
