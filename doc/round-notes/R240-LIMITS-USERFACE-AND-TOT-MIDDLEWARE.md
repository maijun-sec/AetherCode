# R240 — Limits 用户面接线 + ToT Middleware（R239 路线图第一轮）

**日期**: 2026-09-09
**Round**: R240
**状态**: ✅ 完成
**触发**: 用户在 R239 报告后选择"3 个一起做 (R240 全套)"，并指出"只有 MiniMax 可用" → O-1 跑分暂缓，集中做 O-5 + O-4

---

## 0. TL;DR

| 子项 | 状态 | 关键产出 |
|------|------|---------|
| **O-1** 跑 5 个 benchmark 出报告 | ⏸️ 暂缓 | 等 SOTA LLM key 到位（只有 MiniMax，跑分无对标价值） |
| **O-5** Limits 暴露给用户面 | ✅ 完成 | 2 个新 java + 1 个新构造器 + 1 个新接线 + 3 个 test 套件（23 tests） + 1 个 **意外修复** pre-R240 latent bug |
| **O-4** TreeOfThoughts Middleware | ✅ 完成 | 1 个新 java（ToT middleware）+ 1 个 test 套件（13 tests） |

**测试结果**：aethercode-tasks **395/395** pass（+23），aethercode-deepagents **40/40** pass（+13），0 回归。

**意外收获**：R240.1 集成 test 暴露并修复了一个 **pre-R240 latent bug** — `task/spawn` 完全忽略 `params['limits']`，导致 `TaskCli` 的 `--tokens` 等 5 个 flag 在 R321 阶段一直被 silently 丢弃。

---

## 1. 真实工作量 vs 估计

| 子项 | R239 估计 | **R240 实际** | 差异 |
|------|---------|------------|------|
| O-5 Limits 用户面 | 1 round | 0.5-1 round | -50%（R320/R321 已有完整设计） |
| O-4 ToT Middleware | 1-2 round | 0.5-1 round | -50%（Middleware 接口清晰） |
| **总工作量** | 1-3 round | **< 1 round** | -67% |

**原因**：R239 报告里把"用户面 0 接"误读成"代码 0 在"，实际 95% 的设计 + 代码在 R320/R321 阶段已经写完（R321 T-352/§4.6 design.md 注释明确），只是缺**最后一步的 AskUser callback 实现**。这进一步验证 R239 的关键发现："看 release 报告 vs 看代码" 的认知差距。

---

## 2. R240.1 (O-5) — Limits 暴露给用户面

### 2.1 已有现状（盘点）

| 组件 | 文件 | 状态 |
|------|------|------|
| `Limits` record | `aethercode-tasks/.../limits/Limits.java` | ✅ 5 维 + Builder + toMap |
| `LimitsEnforcer.evaluate` | `aethercode-tasks/.../limits/LimitsEnforcer.java` | ✅ 纯函数 |
| `LimitsHitService` | `aethercode-tasks/.../lifecycle/LimitsHitService.java` | ✅ 评估 + 暂停 + 持久化 + 事件 |
| `LimitsPausePolicy` | `aethercode-tasks/.../limits/LimitsPausePolicy.java` | ✅ AskUser + Decision + raiseLimits |
| `task/setLimits` RPC | `aethercode-tasks/.../supervisor/SupervisorRpcServer.java` L127 | ✅ |
| `task/setLimits.v2` RPC | 同上 L160 | ✅ |
| `task/appendEvent` | 同上 L131 | ✅ |
| `TaskCli.SpawnCommand` 5 个 flag | `aethercode-tasks/.../cli/TaskCli.java` L76-85 | ✅ `--wall-clock-ms --tokens --calls --file-writes --network` |
| `TaskCli.SetLimitsCommand` | 同上 | ✅ `task setLimits <id> k=v [...]` |
| AskUser 实现 | **零** | ❌ 缺 |
| 默认 limits 配置 | **零** | ❌ 缺 |
| `params['limits']` 在 spawn 时 | **不读** | ❌ **latent bug** |

### 2.2 新增文件（4 个 java + 3 个 test 套件）

| 文件 | 行数 | 作用 |
|------|------|------|
| `aethercode-tasks/.../limits/AskUserImplementations.java` | 244 行 | 3 个 AskUser：headless / autoRaise / autoCancel，env 选 |
| `aethercode-tasks/.../limits/DefaultLimitsConfig.java` | 222 行 | 读 `~/.aethercode/task-defaults.yaml`，纯函数 |
| `aethercode-tasks/.../limits/DefaultLimitsConfigTest.java` | 7 tests | 缺文件 / 完整 / 部分 / 未知 key / 坏值 / round-trip |
| `aethercode-tasks/.../limits/AskUserImplementationsTest.java` | 10 tests | auto / headless c/r/timeout/EOF/未知/format |
| `aethercode-tasks/.../supervisor/SupervisorServiceDefaultsTest.java` | 6 tests | spawn 继承默认 / 显式 override / unlimited 不发明 |

### 2.3 改动（3 处）

**SupervisorService.java** — 加 1 个 2-arg 构造器 + 修复 latent bug：

```java
// 新构造器
public SupervisorService(SupervisorStore store, Limits defaultLimits) { ... }

// taskSpawn 改：limits 解析顺序
//   1. caller-supplied params['limits']（最高优先）
//   2. config-blob 已有的 'limits' key
//   3. supervisor 的 defaultLimits（如果非 unlimited）
//   4. nothing（unlimited）
```

**SupervisorProcess.java** — 启动时读 default：

```java
Limits defaults = DefaultLimitsConfig.load();
SupervisorService service = new SupervisorService(store, defaults);
```

### 2.4 关键设计

**AskUser 选择**（按 env var 决定）：
- `AETHERCODE_LIMITS_ASK=headless`（默认）— 5s 超时 stdin，c/r 决定，timeout/EOF/未知 → cancel
- `AETHERCODE_LIMITS_ASK=raise` — 总是 RAISE（CI / scripted 模式）
- `AETHERCODE_LIMITS_ASK=cancel` — 总是 CANCEL（严格模式）

**Default limits 文件**（`~/.aethercode/task-defaults.yaml`）：

```yaml
# aethercode task defaults — R240 (O-5)
wallClockMs: 600000    # 10 minutes
tokens:      50000
calls:       200
fileWrites:  50
network:     100
```

- Env override：`AETHERCODE_TASK_DEFAULTS=/path/to/file`
- 文件不存在 → `Limits.unlimited()` + INFO log
- 内容 malformed → WARN log + 降级
- 不抛异常（defaults 是便利，不是强约束）

### 2.5 意外修复：pre-R240 latent bug

**R240.1 的集成 test 暴露**：`TaskCli` 的 5 个 limit flag（`--tokens` 等）传到 `params['limits']` 后，**SupervisorService.taskSpawn 完全忽略**——`params['limits']` 从未被解析进 `config` blob。

**根因**（R320/R321 阶段）：
- `taskSpawn` 只处理 `params['config']`（字符串 JSON blob）和 `params['model']`
- `params['limits']` 这条路径从来没被实现
- 现有 `TaskRpcTest` 的 3 个 setLimits test 全部走 "spawn 不传 limits → 之后 setLimits" 路径，所以 bug 没暴露

**修复**（包含在 R240.1）：
```java
if (hasExplicitLimits) {
    cfg.put("limits", explicitLimits);
} else if (!cfg.containsKey("limits")
        && defaultLimits != null && !defaultLimits.isUnlimited()) {
    cfg.put("limits", defaultLimits.toMap());
}
```

**意义**：从 R321 到 R240，**6 个 round** 期间用户每次 `task spawn --tokens 5000` 都被 silently 忽略。现在修了。

---

## 3. R240.2 (O-4) — TreeOfThoughts Middleware

### 3.1 目标

让 LLM 在做复杂决策前**先列出多个候选方案**（Paper 4 §3.1.1 ToT + Paper 1 §7.6 世界模型中的记忆）。

### 3.2 新增文件（1 个 java + 1 个 test 套件）

| 文件 | 行数 | 作用 |
|------|------|------|
| `aethercode-deepagents/.../middleware/TreeOfThoughtsMiddleware.java` | 280 行 | 3 个 ToT 工具 + 状态记录 + 校验 |
| `TreeOfThoughtsMiddlewareTest.java` | 13 tests | name / depth / think_branches / select / prune / 数量校验 / 长度截断 / 多工具 |

### 3.3 关键设计

**3 个 ToT 工具**：

| 工具 | 入参 | 作用 |
|------|------|------|
| `think_branches(branches[], rationale)` | 2-5 个候选 | 列出方案（深度 1→2） |
| `select_branch(branchId, reason)` | id + 理由 | 选一个继续 |
| `prune_branch(branchId, reason)` | id + 理由 | 标 dead end |

**状态键**（`AgentState.extensions`）：
- `__tot_branches__` — `List<Branch>` 记录
- `__tot_current__` — 当前选中的 branch id
- `__tot_depth__` — 调用深度（每次 beforeModel +1）

**Branch record**：
```java
public record Branch(String id, String text, String rationale, boolean dead) {
    // b0, b1, b2... 序号分配
    // text 截断到 MAX_BRANCH_TEXT = 240 字符
}
```

**校验**（在 `afterModel` hook）：
- `think_branches` 数量 < 2 或 > 5 → 忽略
- `select_branch` 引用未知 id → 忽略
- `prune_branch` 引用未知 id → 忽略
- 长文本 → 截断到 240 字符

**opt-in 设计**（默认不启用）：
```java
// CreateDeepAgent.create(...) 已经有 middleware 参数
DeepAgent agent = CreateDeepAgent.create(
    model, tools, systemPrompt,
    List.of(new TreeOfThoughtsMiddleware()),  // <-- 用户显式加
    /* subagents */ null, /* skills */ null, /* memory */ null,
    /* permissions */ null, /* backend */ null, /* interruptOn */ null,
    /* responseFormat */ null, /* stateSchema */ null,
    /* contextSchema */ null, /* name */ null);
```

**为何 opt-in**：ToT 每次决策多 1-3 次 LLM 调用，token 消耗 +50%~200%。简单任务不需要。

### 3.4 测试覆盖

| 测试 | 验证 |
|------|------|
| `nameIsStable` | middleware 标识 |
| `beforeModelBumpsDepth` | 深度计数正确 |
| `thinkBranchesRecordsAndAssignesIds` | 3 个分支入 state + id 分配 |
| `thinkBranchesTooFewIsIgnored` | < 2 忽略 |
| `thinkBranchesTooManyIsIgnored` | > 5 忽略 |
| `selectBranchUpdatesCurrent` | 选 b1 → state 有 b1 |
| `selectBranchUnknownIgnored` | 选不存在 id 忽略 |
| `pruneBranchMarksDead` | b0 标 dead + reason |
| `multipleToolUsesInOneMessageAreAppliedInOrder` | 同消息多工具调用按序应用 |
| `longBranchTextIsTruncated` | 1000 字符 → 240 |
| `otherToolsAreIgnored` | 非 ToT 工具不干预 |
| `branchRecordToMapIsStable` | record 序列化稳定 |
| `defaultPromptFragmentMentionsThinkBranches` | 默认 prompt 含工具名 |

---

## 4. 测试结果

### 4.1 aethercode-tasks

| Test 套件 | 测试数 | 通过 | 失败 |
|----------|------|------|------|
| LimitsTest（已有） | 7 | 7 | 0 |
| LimitsEnforcerTest（已有） | 10 | 10 | 0 |
| LimitsPausePolicyTest（已有） | 10 | 10 | 0 |
| **DefaultLimitsConfigTest（新）** | 7 | 7 | 0 |
| **AskUserImplementationsTest（新）** | 10 | 10 | 0 |
| **SupervisorServiceDefaultsTest（新）** | 6 | 6 | 0 |
| 其他 36 个套件 | 345 | 345 | 0 |
| **总计** | **395** | **395** | **0** |

### 4.2 aethercode-deepagents

| Test 套件 | 测试数 | 通过 | 失败 |
|----------|------|------|------|
| **TreeOfThoughtsMiddlewareTest（新）** | 13 | 13 | 0 |
| 其他 14 个套件 | 27 | 27 | 0 |
| **总计** | **40** | **40** | **0** |

### 4.3 aethercode-core（间接依赖）

1156 tests pass，2 skipped，0 失败（与 R240 改动无关）。

---

## 5. 关键 SHA / 文件路径

### 5.1 新增文件

| 文件 | 字节数 |
|------|------|
| `aethercode/aethercode-tasks/src/main/java/org/aethercode/tasks/limits/AskUserImplementations.java` | 9 918 |
| `aethercode/aethercode-tasks/src/main/java/org/aethercode/tasks/limits/DefaultLimitsConfig.java` | 9 385 |
| `aethercode/aethercode-tasks/src/test/java/org/aethercode/tasks/limits/DefaultLimitsConfigTest.java` | 4 594 |
| `aethercode/aethercode-tasks/src/test/java/org/aethercode/tasks/limits/AskUserImplementationsTest.java` | 4 545 |
| `aethercode/aethercode-tasks/src/test/java/org/aethercode/tasks/supervisor/SupervisorServiceDefaultsTest.java` | 6 013 |
| `aethercode/aethercode-deepagents/src/main/java/org/aethercode/deepagents/middleware/TreeOfThoughtsMiddleware.java` | 12 446 |
| `aethercode/aethercode-deepagents/src/test/java/org/aethercode/deepagents/middleware/TreeOfThoughtsMiddlewareTest.java` | 9 628 |

### 5.2 改动文件

| 文件 | 改动 |
|------|------|
| `aethercode/aethercode-tasks/src/main/java/org/aethercode/tasks/supervisor/SupervisorService.java` | 加 1 个 2-arg 构造器 + taskSpawn 读 params['limits']（修复 latent bug） |
| `aethercode/aethercode-tasks/src/main/java/org/aethercode/tasks/supervisor/SupervisorProcess.java` | 启动时 `DefaultLimitsConfig.load()` 注入 SupervisorService |

### 5.3 R240 报告

`doc/项目文档/R240-LIMITS-USERFACE-AND-TOT-MIDDLEWARE.md` (本文件)

---

## 6. 用户可见的行为变化

### 6.1 默认 limits 自动应用

**之前**：用户每次 `task spawn` 都必须手动 `--tokens X --wall-clock-ms Y --...`

**现在**：
- 用户在 `~/.aethercode/task-defaults.yaml` 写一次默认
- 之后所有 spawn 自动用默认，**直到用户显式 override**
- `task/setLimits` 仍然能改任何 child

### 6.2 limits_hit 自动决策

**之前**：触发 limits → child 暂停 → 永远停在那（因为 AskUser 没人接）

**现在**：
- 默认（headless）：打印摘要到 stderr，5s 内 stdin 接受 c/r/其他
- `AETHERCODE_LIMITS_ASK=raise`：总是 raise（CI 友好）
- `AETHERCODE_LIMITS_ASK=cancel`：总是 cancel（严格模式）

### 6.3 TaskCli 的 5 个 limit flag 现在真的生效

**之前**（R321 起 6 个 round）：`task spawn --tokens 5000` → silently 忽略

**现在**（R240.1 fix）：`task spawn --tokens 5000` → child config 真的带 tokens=5000

### 6.4 ToT Middleware opt-in

**现在**：调用方在 CreateDeepAgent 传 `new TreeOfThoughtsMiddleware()` 即可启用 ToT

```java
DeepAgent agent = CreateDeepAgent.create(
    model, tools, systemPrompt,
    List.of(new TreeOfThoughtsMiddleware()),  // opt-in ToT
    ...);
```

**默认行为**：不传 → 没 ToT，LLM 正常 flow

---

## 7. 跟 R239 报告的对照

| R239 报告的 O-5/O-4 估计 | **R240 实际** |
|------|------|
| O-5："Limits 暴露给用户面 1 round" | **0.5-1 round**（5 处改动 + 23 tests + 1 bug fix） |
| O-4："ToT Middleware 雏形 1-2 round" | **0.5-1 round**（1 个 java + 13 tests） |
| R240 总工作量 1-3 round | **< 1 round** |
| 0 修复 bug | **+1 修复**（pre-R240 latent bug） |

---

## 8. 关键设计决定

1. **AskUser 选 env var** — 不引入新 RPC surface，env 简单可控
2. **Default limits 用 YAML 子集** — 不引 SnakeYAML 依赖，line-oriented parser
3. **ToT 默认 opt-in** — ToT 贵，按需启用
4. **ToT 工具走正常 LLM 工具调用** — 不破坏现有 architecture
5. **修复 pre-R240 bug** — "Fix collateral issues in-scope"（R239 自己定的原则）
6. **保持向后兼容** — SupervisorService 1-arg 构造器保留，2-arg 是 opt-in

---

## 9. 后续 (R241+ scope)

1. **O-1 跑分** — 等用户拿到 SOTA LLM key 后重提
2. **O-2 A2A 协议** — R241 (Google 生态接入)
3. **O-3 ExperienceStore → 策略库** — R241 (自我改进第一版)
4. **Desktop/TUI 接线** — `LimitsHitService` 的 events 在 desktop/tui 弹弹窗（**当前 R240 是 headless 路径**）
5. **ToT Middleware 集成到 SSD** — 让 SSD workflow 的某些步骤自动用 ToT
6. **出包** — R240 改完了，可以跑 R237 同样的出包流程出 0.2.58

---

## 10. 教训

1. **R239 真实工作量低估的反面教材** — R239 写"1-3 round" 实际 "< 1 round"，因为 R320/R321 阶段已经把 95% 写完了。**教训：盘点代码比看报告准**
2. **集成 test 比 unit test 更能发现 latent bug** — 6 个 SupervisorServiceDefaultsTest 跑了不到 1s 就暴露了 R320-R240 期间的 6-round bug
3. **"Fix collateral issues in-scope" 原则应用得好** — 修 pre-R240 bug 没花额外 round（test 暴露 → 1 处 edit → 复跑全过）
4. **ToT 选 opt-in 模式避免"feature tax"** — 默认不启用，调用方按需加

---

**作者**: mavis (Mavis, MiniMax Code)
**用时**: ~50 分钟（写代码 30 + 跑 mvn test 20）
**总字数**: ~3000 字
