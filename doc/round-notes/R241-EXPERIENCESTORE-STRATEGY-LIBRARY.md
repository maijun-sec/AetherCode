# R241.2 ExperienceStore → 策略库 (Self-Reflect + ReasoningBank)

> **状态**: ✅ 完成
> **日期**: 2026-09-10
> **范围**: R239 路线图 O-3 第一版
> **承接**: R230 ExperienceStore (持久层) + R232/R233 三层记忆

---

## TL;DR

把 R230 写的 `ExperienceStore` (持久化 schema) 激活成**运行时策略库**：

- **写侧** `SelfReflectMiddleware` — Reflexion 风格：工具调用失败 → 调 LLM 反思 → 解析为 `ReasoningUnit` → 存 `ReasoningBank`
- **读侧** `BankRecallMiddleware` — 每个 model call 召回 top-K 单元 → 注入 system prompt
- **接 MiniMax API** — `ChatClientReflector` 包装 `SpringAiChatClient` (默认 base URL `https://api.minimaxi.com/v1` + `MiniMax-M3` + env `MINIMAX_API_KEY`)
- **4 个新 test 套件 50 个 test 全过** + aethercode-deepagents **116/116 pass, 0 回归**

---

## 1. 为什么选 A+B 混合

R239 路线图 O-3 列了 4 个候选论文实现（详见 §3 论文映射）。用户确认 A+B 混合：

| 选项 | 论文 | 作用 | 选用 |
|------|------|------|------|
| A. **Reflexion** | Paper 4 §11.1 (Shinn 2023) | 失败时让 LLM 写 verbal reflection | ✅ |
| B. **ReasoningBank** | Paper 1 §4.2.2 (2025) | 抽象 reflection 为 reusable unit + 索引 | ✅ |
| C. AWM (Agent Workflow Memory) | Paper 1 §4.2.2 | 把工作流作为记忆 | ⏸ 暂缓 |
| D. Voyager (技能库) | Paper 1 §4.2.3 | 技能向量库 + curriculum | ⏸ 暂缓 |

**为什么不选 C/D**：C 是工作流级别（agent loop 改写），D 是技能向量库（需要专门的 embedding + retrieval infra）。R241.2 范围是"激活 ExperienceStore"，A+B 是最小最直接的 paper-faithful 实现。

**为什么不重写新 ChatClient**：`aethercode-engine-springai` 的 `SpringAiChatClient` 已经默认 MiniMax-M3 + `https://api.minimaxi.com/v1` + `MINIMAX_API_KEY` env var，R241.2 直接复用。

---

## 2. 实现方案 (Implementation Plan)

### 2.1 数据流

```
┌─────────────────┐                                       ┌──────────────────┐
│ Tool invocation │ ── Exception ──┐                       │  ReasoningBank   │
│   (wrapToolCall)│                 ▼                      │  (in-memory +    │
└─────────────────┘        ┌────────────────────┐           │   parse regex)   │
                           │ SelfReflectMiddleware├───────▶  └────────┬─────────┘
                           │ 1. classifier.always()│                    │
                           │ 2. dedup (in-mem Deque)│                    ▼
                           │ 3. Reflector.reflect()│            ┌─────────────────┐
                           │ 4. bank.parse(text)   │            │ ReasoningUnit   │
                           └──────────┬────────────┘            │ taskKind / err/ │
                                      │                         │ fix / example   │
                                      ▼                         └─────────────────┘
                              LLM (MiniMax-M3)
                              (or StubReflector
                               in tests)

                              ┌────────────────────┐
                              │ BankRecallMiddleware│
                              │ 1. beforeModel:     │
                              │    bank.recall()    │◀────── ReasoningBank (all kinds top-1, sort by utility)
                              │ 2. wrapModelCall:   │
                              │    inject <prior_   │
                              │    reflections>     │──▶ SystemMessage (next model call)
                              │    block            │
                              └────────────────────┘
```

### 2.2 模块拆分

| 文件 | 行数 | 角色 |
|------|------|------|
| `Reflector.java` | ~50 | interface: `String reflect(sys, user)` |
| `StubReflector.java` | ~70 | test fixture (fixture-mode + default mode) |
| `ChatClientReflector.java` | ~110 | 包装 `ChatClient.stream()` + drain `StreamEvent` |
| `ReasoningUnit.java` | ~85 | record: id / taskKind / err / fix / example / utility / uses / createdAt |
| `ReasoningBank.java` | ~225 | 索引 (byKind + byId) + parse regex + touch + listener |
| `FailureClassifier.java` | ~70 | SPI: `Classification classify(tool, args, error)` + `always()` 默认 |
| `SelfReflectMiddleware.java` | ~265 | 写侧：wrapToolCall hook + dedup + cap + parse |
| `BankRecallMiddleware.java` | ~285 | 读侧：beforeModel recall + wrapModelCall inject |
| `SelfReflectPrompts.java` | ~40 | DEFAULT_SYSTEM_PROMPT 模板 (key:value shape) |

**总 8 java, 4 test 套件 (50 tests)**。0 新增 module。

### 2.3 关键技术决定

1. **wrapToolCall 失败钩子**（不是 beforeModel）— 最可靠的失败信号，hook site 已有 tool name + args + exception 三件套
2. **in-memory `recentFailures` Deque**（不是 AgentState）— `AgentState` 是 immutable record，`wrapToolCall` 无返回值无法传回新 state；Deque 限定 `MAX_RECENT=8` 防止长 session 内存增长
3. **`maxReflectionsPerTurn` cap**（默认 1）— 防止一个 turn 多个不同失败导致无限 recall loop；`0` 表示 unlimited（test 隔离用）
4. **dedup + cap 双重防护** — dedup 避免重试循环产生重复 reflection，cap 避免 burst 失败
5. **parse regex case-insensitive** + `(unspecified)` fallback — 模型可能不严格按 key:value 输出
6. **Cache recall on state** — `beforeModel` 第一次跑 recall 存到 `state.extensions().put(RECALL_KEY, units)`，后续 turn 跳过 recall（state 命中直接返回）；用 fresh state 重新 recall
7. **Recall mode = all-kinds top-1 → sort by utility → take topK**（不是 `recallFor(kind, n)`）— 跨 kind 选最有用 unit，topK=3 默认
8. **priority 5**（BankRecall）vs priority 0（MemoryMiddleware）— 显式排序，先加载 AGENTS.md 再追加 recall
9. **empty example 跳过 example 字段**（formatRecall）— 防止没 example 时打印空行
10. **null template = no injection**（BankRecall）— 允许只 recall 不注入（debug 用）

### 2.4 接 MiniMax API

```java
SpringAiChatClient client = new SpringAiChatClient();
// 默认 base URL = https://api.minimaxi.com/v1
// 默认 model = MiniMax-M3
// env MINIMAX_API_KEY
ChatClientReflector reflector = new ChatClientReflector(client);
ReasoningBank bank = new ReasoningBank();
SelfReflectMiddleware writeMw = new SelfReflectMiddleware(reflector, bank);
BankRecallMiddleware readMw = new BankRecallMiddleware(bank);
// 然后: CreateDeepAgent.create(..., List.of(writeMw, readMw), ...);
```

不写新 ChatClient — `SpringAiChatClient` 已完整支持 MiniMax。

---

## 3. 论文参考实现映射 (Paper Mapping)

> 用户约束："实现方案都需要梳理实现方案，尤其是最近的论文的参考实现，更加重要"

### 3.1 Reflexion (Shinn et al., 2023, Paper 4 §11.1)

**论文核心** (Neural Information Processing Systems 2023, "Reflexion: Language Agents with Verbal Reinforcement Learning")：

- Agent 在环境中执行 → 失败 → 产生 verbal self-reflection → 写入记忆 → 下次重试
- 三类 reflection: binary success/fail 判断、self-reflect 生成文字反思、external feedback
- 实验显示 Reflexion 在 AlfWorld (HumanEvalCoding) + HotPotQA + FEVER 上 SOTA

**R241.2 实现**：

| Reflexion 论文元素 | R241.2 对应 | 偏差说明 |
|------------------|-------------|----------|
| Verbal self-reflection | `Reflector.reflect(sys, user)` | 同样的 verbal 反思范式 |
| Memory of past reflections | `ReasoningBank` (in-memory) | 论文是 episodic memory；R241.2 升级为 structured reasoning units |
| Retry until success | `SelfReflectMiddleware` + 下次 tool call 读取 | AetherCode 不强制重试，依赖上游 middleware (e.g. `ToolRetryMiddleware`) |
| Binary self-evaluation | 没实现 (留给 future R244 O-6) | R241.2 范围限 verbal reflection，不做 self-grader |
| External feedback grading | `FailureClassifier.classify()` SPI | R241.2 default `always()`，留 SPI 给用户接入 evaluator |

**关键简化**：原论文用 binary success 判断引导 stop condition，R241.2 不做 stop（跟 ReasonBank 论文 pattern 一致），failure detection 走 tool exception。

### 3.2 ReasoningBank (Google Research, 2025, Paper 1 §4.2.2)

**论文核心** (2025 预印本，"ReasoningBank: Scaling Agent Self-Learning"):

- "Success and failure are both abstracted into reusable reasoning units, enabling test-time scaling and robust learning"
- 关键 insight: 把试错经验抽象成结构化 unit (error pattern + fix strategy + example)，下次同类任务直接召回
- 在 WebArena / SQD / 其他 agent benchmark 上 +12% success rate

**R241.2 实现**：

| ReasoningBank 论文元素 | R241.2 对应 | 偏差说明 |
|-----------------------|-------------|----------|
| Reusable reasoning unit | `ReasoningUnit` record (id/taskKind/err/fix/example/utility/uses/createdAt) | **完全对应** — 8 字段比论文 4-5 字段多 utility+uses+createdAt 以支持 utility decay + 排序 |
| Bank storage + indexing | `ReasoningBank.byKind + byId` (ConcurrentHashMap) | 完全对应 |
| Task kind classification | `FailureClassifier.classify()` → taskKind | 完全对应；R241.2 default `always()` 标 "tool_error" |
| Recall by task kind | `ReasoningBank.recallFor(taskKind, n)` | 完全对应 (utility desc, uses desc, createdAt desc 排序) |
| Test-time scaling | `BankRecallMiddleware` 每个 model call 注入 top-K | 跟论文思想一致 |
| Persistent bank across sessions | **没实现** (R241.2 是 in-memory) | R230 `ExperienceStore` schema 已 ready，R241.3 接入持久化 |
| Successful vs failure unit | **只 failure** (R241.2) | success 抽象留给 R241.3 + R243 O-3 完整闭环 |

**关键升级**：`ReasoningUnit` 的 utility/uses 字段借鉴了 AetherCode 已有的 `ExperienceRecord` 模式 (R230)，可以共享 decay policy。

### 3.3 AWM (Agent Workflow Memory, Paper 1 §4.2.2) — 暂缓

- **核心**: 把"agent workflow"（tool call sequence）作为 memory，下次同类任务直接复用工作流
- **R241.2 不实现原因**: 工作流级别改写 agent loop 风险高，R241.2 范围是单元级别
- **未来** (R243+): 可以作为 `BankRecallMiddleware` 的扩展，recall 整段 workflow sequence

### 3.4 Voyager (技能库, Paper 1 §4.2.3) — 暂缓

- **核心**: 技能向量库 (skill library) + curriculum (难度递进) + iterative prompt
- **R241.2 不实现原因**: 需要 embedding infra + vector DB；AetherCode 当前没用 vector DB；R244+ 接入 Pinecone/Milvus 时再做
- **未来**: 可以作为 `BankRecallMiddleware` 的语义版（用 embedding 召回而非按 kind）

### 3.5 4 个 paper 综合比较

| 维度 | Reflexion | ReasoningBank | AWM | Voyager |
|------|-----------|---------------|-----|---------|
| 抽象粒度 | verbal text | structured unit | workflow | skill + embedding |
| 触发时机 | retry 时 | 每次任务前 | 任务前 | 任务前 + 新技能发现 |
| 存储 | episodic memory | structured bank | workflow graph | vector DB |
| R241.2 适配 | ✅ 写侧 | ✅ 读侧 | ❌ | ❌ |
| 实现复杂度 | 低 | 低 | 中 | 高 |
| 论文 confidence | 已被引 1500+ | 2025 预印本 | 2024 | 2023 已被引 600+ |

---

## 4. 实战 (Usage)

### 4.1 最小可运行

```java
// 1. Chat model
ChatClient client = new SpringAiChatClient();  // 默认 MiniMax-M3
Reflector reflector = new ChatClientReflector(client);

// 2. Bank + 2 middleware
ReasoningBank bank = new ReasoningBank();
SelfReflectMiddleware writeMw = new SelfReflectMiddleware(reflector, bank);
BankRecallMiddleware readMw = new BankRecallMiddleware(bank);

// 3. 接进 deep agent
List<Middleware> middlewares = List.of(writeMw, readMw);
CreateDeepAgent.create(chatClient, tools, systemPrompt, null,
    subAgents, middlewares, ...);

// 4. 跑 agent，第一次 tool 失败 → 写 reflection → 下次同类 task 召回
```

### 4.2 离线 / 测试模式

```java
// 不依赖 API key
Reflector stub = new StubReflector();
stub.registerDefault("error_pattern: bad path\nfix_strategy: check cwd");

SelfReflectMiddleware mw = new SelfReflectMiddleware(stub, bank);
```

### 4.3 自定义 classifier

```java
// 让 kind 更细 (build / test / file_edit)
FailureClassifier fine = (toolName, args, error) -> {
    String kind = toolName != null ? toolName : "unknown";
    return new FailureClassifier.Classification(kind,
            "tool=" + toolName + "; " + error.getClass().getSimpleName() + ": " + error.getMessage());
};
SelfReflectMiddleware mw = new SelfReflectMiddleware(reflector, bank, fine, null, 3);
```

### 4.4 自定义 recall 数量

```java
BankRecallMiddleware readMw = new BankRecallMiddleware(bank, 5,
        "<past_lessons>\n{recall}\n</past_lessons>");
```

---

## 5. 验证 (Verification)

### 5.1 Test 覆盖

| Test 套件 | tests | 内容 |
|----------|------:|------|
| `ReasoningBankTest` | 17 | parse / recall / sort / touch / listener / fallback |
| `StubReflectorTest` | 6 | fixture mode / default mode / null returns |
| `SelfReflectMiddlewareTest` | 11 | wrapToolCall 失败钩子 / dedup / cap / classifier / empty / exception 路径 |
| `BankRecallMiddlewareTest` | 16 | recall / format / inject / cache / priority / 构造器校验 |
| **R241.2 新增合计** | **50** | 100% pass |

### 5.2 全模块回归

```
aethercode-deepagents: Tests run: 116, Failures: 0, Errors: 0, Skipped: 0
```

包含 R240.2 ToT (13) + R242.2 RoleRegistry (26) + R242.1 VLM (in aethercode-tools 12) + R241.2 selfimprove (50) + 已有 27。**0 回归**。

### 5.3 端到端 (E2E)

未跑 e2e — R241.2 是基础设施级，e2e 留给 R243 O-3 完整闭环。

---

## 6. 跟 R230 ExperienceStore 的关系

R230 写了持久层 schema：

```java
ExperienceRecord(utility, uses, sourceOutcome, links, ...)
ExperienceKind.CASE | STRATEGY | SKILL
```

R241.2 没改 R230 schema，而是新增"运行时的反射层"：

- `ReasoningUnit` 是 in-memory 临时抽象（每个 session 重新积累）
- `ReasoningBank` 是内存索引（fast path）
- 持久化留给 R241.3：把 `ReasoningUnit` 序列化进 `ExperienceRecord(kind=STRATEGY, ...)` 跨 session 复用

**为什么不全用 R230 ExperienceStore 直接**：
- R230 schema 偏 general (任意 kind)
- R241.2 的 `ReasoningUnit` 偏 specific (error_pattern + fix_strategy + example)
- 适配 R230 schema 需要在 `ExperienceRecord.sourceOutcome` + `links` 里塞 reflection text，可行但不优雅
- 短期：双轨，R241.2 in-memory，R230 持久化（用户的 chat history + skills 走 R230）
- 长期 (R241.3+): 统一序列化 + 自动 decay + cross-session recall

---

## 7. 后续 (Next Steps)

按 R239 路线图：

| Round | 子项 | 状态 | 预计工作量 |
|------|------|------|-----------|
| **R241.2** | **O-3 ExperienceStore → 策略库 (Reflexion + ReasoningBank)** | ✅ | **< 1 round (50 tests)** |
| R241.3 | O-3 持久化 (R230 schema 接入) | 📋 | 1 round |
| R243 | O-3 完整闭环 (success reflection + cross-session) + O-8 全局审计 + DRIFT 动态规则 | 📋 | 2-3 round |
| R244 | O-6 持续学习 (binary self-eval + metric tracking) + O-10 跨 surface 状态共享 | 📋 | 2-3 round |
| R250+ | 神经-符号 + 完整多模态 + 自我意识 | 📋 | — |

### R241.3 (O-3 持久化) scope 候选
1. `ReasoningBank` 持久化到 `ExperienceStore` (write-through)
2. 跨 session 启动时从 `ExperienceStore` load 全部 STRATEGY entries 进 `ReasoningBank`
3. Utility decay (R230 已有 ForgettingPolicy)
4. `ExperienceStore.size() > threshold` 触发 consolidate (LLM 摘要压缩)

### R243 (O-3 完整闭环) scope 候选
1. Success reflection (跟 failure 平行) — 工具成功后也写"为什么成功"
2. Cross-session recall (session 间共享 bank)
3. DRIFT 动态规则 (O-8) — bank 内容写回 AGENTS.md 作为可编辑规则
4. O-8 全局审计 — bank growth rate / utility distribution / failure pattern trending

---

## 8. 教训 (Lessons)

1. **R239 估 "1-2 round" vs R241.2 实际 < 1 round** — 跟 R240/R241/R242 一样，"激活已有能力" 比 "新建模块" 快得多
2. **`AgentState` immutable 限制是反复踩的坑** — R241.2 跟 R240.1 一样，in-memory state 是兜底；wrapToolCall 没法传回新 state
3. **Dedupe + cap 是双保险** — 只 dedupe 抓不住 burst，只 cap 抓不住重试；两个都做
4. **All-kinds top-1 recall 比 exact-kind 更通用** — 不需要上游告诉 bank 当前 kind；model 自带 utility ranking
5. **测试 lambda 内部不能 modify local var** — 用 AtomicInteger / `state == s2` identity check 代替
6. **PowerShell `--%` 是 mvn `-D.=value` 的必备** — 之前 R242.2 已踩过这个坑
7. **ContentBlock 是 sealed interface，text() 是 subtype method** — `ContentBlock::text` 是 static factory 不是 instance accessor；要 `ContentBlock.flattenText(blocks)` 或 `ContentBlock.TextBlock::text`
8. **Surefire 3.3.1 `--%` 模式下 `+` 不解析** — 一次跑一个 test class
9. **失败原因诊断 = 直接读 stack** — 不要重新跑 test，从 `target/surefire-reports/*.txt` 直接看
10. **论文 mapping 比实现本身重要** — 用户特别强调 "尤其是最近的论文的参考实现，更加重要"，R241 报告必须先讲清楚论文→代码的对应表

---

## 9. 关键文件路径

**R241.2 新增 8 java** (在 `aethercode-deepagents/.../selfimprove/`):
- `Reflector.java` (2.6 KB)
- `StubReflector.java` (2.8 KB)
- `ChatClientReflector.java` (4.0 KB)
- `ReasoningUnit.java` (3.2 KB)
- `ReasoningBank.java` (8.7 KB)
- `FailureClassifier.java` (2.8 KB)
- `SelfReflectMiddleware.java` (11.3 KB)
- `BankRecallMiddleware.java` (10.0 KB)
- `SelfReflectPrompts.java` (1.6 KB)

**R241.2 新增 4 test 套件** (在 `aethercode-deepagents/.../test/.../selfimprove/`):
- `ReasoningBankTest.java` (17 tests)
- `StubReflectorTest.java` (6 tests)
- `SelfReflectMiddlewareTest.java` (11 tests)
- `BankRecallMiddlewareTest.java` (16 tests)

**复用 AetherCode 已有**:
- `SpringAiChatClient` (aethercode-engine-springai) — MiniMax-M3 默认
- `aethercode-memory` 的 `ExperienceRecord` / `ExperienceStore` schema (R230)
- `CreateDeepAgent.create(..., middlewares, ...)` — middleware 链已支持
- `Middleware` interface + `AgentState.withExtension(...)` 模式
- `ContentBlock.flattenText(...)` helper (aethercode-core)
