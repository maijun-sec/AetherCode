# R211 — aethercode-deepagents 整合重构 (2026-09-04)

## 背景

`aethercode-deepagents` 是 deepagents Python 库的 Java 21 移植版，R1/R2 阶段从
`aethercode-core` 拆出来做成了独立孤儿模块。半年下来没人调用，但一直占着 build
matrix，源码跟核心模块有大量重叠。

## 现状摸底

| 问题 | 详情 |
|---|---|
| 1. `aethercode-runtime` 是幽灵模块 | `<skipMain>true</skipMain>` + `<skipTestCompile>true</skipTestCompile>`，所有源码从未编译，外部 import 0 命中 |
| 2. `aethercode-core.runtime.llm.*` 14 个文件 package 声明写错 | 声明成 `org.aethercode.runtime.llm`（属于死代码模块），但物理上住在 `aethercode-core` 目录里 |
| 3. `aethercode-deepagents` 是孤儿 | 自己反向依赖 `aethercode-core.runtime.X`，没有任何外部模块 import 它 |
| 4. `core.fs.GlobMatcher` vs `backends/GlobMatcher` 重复 | 前者简单 NIO glob（0 引用），后者 wcmatch 风格（严格超集） |
| 5. `core.tool.Tool` vs `deepagents.tools.Tool` 不重复 | 接口签名不同（前者有 `isReadOnly`/`checkPermissions`，后者有 `ainvoke`/`withDescription`），互补，都保留 |

## 用户诉求

> "将 aethercode-deepagents 整合到不同的模块，如果有重复能力，就挑选合适的保留。
> 当前对接的如果是 deepagents 的 agent，那么就保留能够和 deepagents 整合的那部分，
> 能力不能有弱化。"

## 决策与执行

### A. ~~删 `aethercode-runtime` 模块~~ → **已撤销，恢复原状**

最初按"0 外部 import = 幽灵代码"判定删除。用户提醒"runtime 保障长周期任务"，
我重新摸了一遍：

- 真正的长周期任务执行在 `aethercode-tasks` R300（SQLite + JSON-RPC socket +
  state machine + checkpoint，372 tests pass），跟 `aethercode-runtime` 0 引用
  关系
- `aethercode-runtime` 内容是 langchain_core + langgraph runtime 基础类型
  （Message / Tool / Store / LLMRequest / StateReducer / ContextOverflowException），
  R1 移植时配套上的 `<skipMain>` 占位模块，**不是**长周期任务模块
- 但 **设计意图不能光靠 import 数判断**——可能以后要把 langchain_core 风格
  状态/消息统一到 runtime 上

**恢复步骤**：
- 从 `_trash_runtime_r211/` 还原回 `aethercode/aethercode-runtime/`
- parent pom 重新加 `<module>aethercode-runtime</module>` + dependencyManagement
- `aethercode-core` / `aethercode-examples` / `aethercode-partner-quickjs` /
  `aethercode-deepagents` 重新加 `<dependency>` 引用
- `aethercode-deepagents/pom.xml` description 同步更新
- 27 个 module 编译 BUILD SUCCESS（多了一个 AetherCode Runtime）
- `aethercode-core` 1156 tests + `aethercode-deepagents` 27 tests 全过

**学到的**：R211 整体推进 OK，但 Step A"删 runtime"这步**操之过急**。教训
见底部"教训 8"。

### B. 改 `aethercode-core.runtime.llm.*` 的 package 声明 ✅

- 14 个源文件 + 20 个 test 文件：`package org.aethercode.runtime.llm(.X)?;` →
  `package org.aethercode.core.runtime.llm(.X)?;`
- 外部 import 17 个文件（core 内部 + partner-quickjs 2 + acp 1 + deepagents 2）
- FQN-in-body / javadoc 引用全 7 个文件一并改

### C. 拆 `aethercode-deepagents.middleware.*` ✅

**搬到 `aethercode-core.middleware.*`（39 个文件）**：
- Filesystem 套件（Middleware / Operation / PathValidator / PathTraversalException
  / Permission / PermissionChecker / PermissionDeniedException / Permissions /
  State / ToolNames / Toolset / InterruptGlue）
- Skill 套件（SkillFrontmatterParser / SkillMetadata / SkillNameValidator /
  SkillsMiddleware / SkillSource / SkillSourceLabel / SkillsPrompts）
- Summarization 套件（Cutoff / Event / EventApplier / History / Media /
  OverflowClip / Prompts / TokenCounter / TruncateArgs / Summarizer /
  SummarizerRegistry / SummarizerUnavailableError）
- 基础设施（ContextSize / MiddlewareUtils / MiddlewarePackage /
  PrivateStateAttr / StateFieldIntrospector / SystemMessageUtils /
  ToolMessageEviction / MediaResultReorderer / MultimodalContentScrubber /
  TaskToolSchema / OverflowClip）

**留在 `aethercode-deepagents.middleware.*`（52 个文件，deepagents 特有）**：
- Async task 协议套件（AsyncAgentProtocolClient / AsyncAgentProtocolRegistry /
  HttpAsyncAgentProtocolClient / AsyncTask / AsyncTaskReducer /
  AsyncSubAgent* 5 件 / Schema 5 件）
- Sub-agent 配置（SubAgent / SubAgentModel / SubAgentResponseFormat /
  SubAgentResult / SubAgentTool / CompiledSubAgent）
- Middleware 基类 + 依赖基类的具体类（Middleware / FilesystemMiddleware /
  FilesystemToolset / TodoListMiddleware / ToolExclusionMiddleware /
  ToolFilterHelper / FunctionalToolOf / SubAgentMiddleware / SubAgentPrompts /
  SkillsMiddleware / HumanInTheLoopMiddleware / MemoryMiddleware /
  PatchToolCallsMiddleware / PromptCachingMiddleware / PromptCachingProvider /
  PromptCachingProviderRegistry / SummarizationMiddleware / SummarizationToolMiddleware
  / CreateSummarizationMiddleware / CreateSummarizationToolMiddleware /
  LangChainModelSummarizer / WrapModelCallResult）
- Rubric 评分（RubricMiddleware / RubricEvaluation / RubricPrompts /
  RubricResult / RubricState / CriterionAliases / CriterionEval /
  GraderResponse / GraderVerdict）

**留下的标准**："depends on `deepagents.tools.Tool` / `langchain_compat.*` / 
`Middleware` base / `SubAgent` 配置类"的，全部留在 deepagents，因为 core 不能
反向依赖 deepagents（会产生 cycle）。

### D. 拆 `aethercode-deepagents.backends.*` ✅

**全部 38 个文件搬到 `aethercode-core.fs.backend.*`**：
`BackendProtocol` / `BackendSupport` / `BackendUtils` / `BaseSandbox` /
`CompositeBackend` / `ContextLine` / `DeleteResult` / `EditResult` /
`ExecuteOffloadResult` / `ExecuteResponse` / `FileData` / `FileDownloadResponse` /
`FileInfo` / `FileOperationError` / `FileOperationErrorMapper` /
`FilesStateStore` / `FilesystemBackend` / `FileUploadResponse` /
`GlobMatchFunction` / `GlobResult` / `GrepMatch` / `GrepResult` /
`InMemoryFilesStateStore` / `InMemoryStore` / `Item` / `LineMatch` /
`LocalShellBackend` / `LsResult` / `MiniJson` / `PutOp` / `ReadResult` /
`SandboxBackendProtocol` / `SandboxCmds` / `StateBackend` / `Store` /
`StoreBackend` / `WriteResult` + `GlobMatcher`

### E. `GlobMatcher` 去重 ✅

- `aethercode-core.fs.GlobMatcher`（简单 NIO glob，0 引用）→ 删
- `aethercode-deepagents.backends.GlobMatcher`（wcmatch 风格，strict superset）→
  搬到 `aethercode-core.fs.backend.GlobMatcher`
- 老 `core.fs.GlobMatcherTest`（21 个 test）→ 删（测的是已删的 API）
- 新写 `aethercode-core.fs.backend.GlobMatcherTest`（25 个 test，覆盖
  wcmatch 语义：brace / globstar / dotfile / leading-slash / MAX_EXPANSIONS 等）

### F. 留 `aethercode-deepagents` 哪些东西

- `graph/*`（CreateDeepAgent / DeepAgent / DeepAgentState / DeepAgentEvent /
  DeepAgentPrompts / MockChatModel / CompiledStateGraph）— langgraph4j 上的
  deepagents 风格 agent 循环
- `langchain_compat/*`（langchain_anthropic / langgraph / language_models /
  messages / middleware / runnables / tools）— Python langchain 兼容层
- `chat/*`（AetherCodeChatModelAdapter）— chat-model 适配到 deepagents graph
- `tools/*`（Tool / FunctionalTool / ToolHelper / Tools / MiddlewareExclusion）
  — langchain4j 风格 Tool 接口，**跟 `core.tool.Tool` 是不同接口**，都保留

## 最终模块拓扑

```
aethercode-parent
├── aethercode-core                  ← 核心引擎
│   ├── org.aethercode.core.runtime  (AgentState, Message, ContentBlock, MessagesReducer)
│   ├── org.aethercode.core.runtime.llm   (HarnessProfile*, ModelProfile, ProviderProfile*)
│   ├── org.aethercode.core.middleware    (39 files, ex-deepagents)
│   ├── org.aethercode.core.fs.backend    (38 files, ex-deepagents)
│   ├── org.aethercode.core.engine        (QueryEngine, AetherCodeAgent, ...)
│   ├── org.aethercode.core.tool          (Java-native Tool)
│   ├── org.aethercode.core.agent         (Subagent* — runtime spawn_agent)
│   ├── ...（其它 core 子包：fs / llm / cost / message / patch / ...）
│
├── aethercode-runtime               ← 幽灵状态 (skipMain=true) — langchain_core 基础类型
│   └── org.aethercode.runtime      (Message, Tool, Store, LLMRequest, StateReducer, ...)
│                                         实际不被编译, 留着给将来整合 langchain_core 风格留口子
│
├── aethercode-deepagents            ← deepagents 风格 agent runtime
│   ├── org.aethercode.deepagents.graph   (langgraph4j-based agent loop)
│   ├── org.aethercode.deepagents.langchain_compat  (Python langchain 兼容)
│   ├── org.aethercode.deepagents.middleware   (52 files: deepagents 特有)
│   ├── org.aethercode.deepagents.chat     (AetherCodeChatModelAdapter)
│   └── org.aethercode.deepagents.tools    (langchain4j-style Tool)
│
├── aethercode-tasks                 ← R300 长周期任务执行 (SQLite + JSON-RPC)
│   └── TaskStateMachine / PersistentTaskRegistry / CheckpointScheduler /
│       TaskHeartbeat / StateCheckpointCodec / TaskSupervisor / ...
│
├── aethercode-engine-springai       ← spring-ai ChatModel → AetherCode ChatClient
├── aethercode-permission, aethercode-memory, aethercode-mcp, aethercode-skills,
│   aethercode-hooks, aethercode-compact, aethercode-prompts, aethercode-tools,
│   aethercode-sdk, aethercode-bridge, aethercode-protocol, aethercode-models,
│   aethercode-workflows, aethercode-config, aethercode-acp,
│   aethercode-talon, aethercode-code, aethercode-evals, aethercode-examples,
│   aethercode-partner-quickjs
```

## 验证结果

**编译**：26 个 module 全部 `BUILD SUCCESS` ✅

**测试**：

| Module | Tests | Pass | Fail | 备注 |
|---|---|---|---|---|
| aethercode-core | 1156 | 1156 | 0 | +25 新 GlobMatcherTest |
| aethercode-deepagents | 27 | 27 | 0 | 全部 graph + middleware 集成测试 |
| aethercode-config | 113 | 113 | 0 | |
| aethercode-permission | ~340 | ~340 | 0 | R203-R207 全部 |
| aethercode-skills | (included in core) | | | |
| aethercode-workflows | 26 | 26 | 0 | |
| aethercode-tasks | 372 | 372 | 0 | |
| aethercode-tools | 249 | 235 | 14 | 14 个是 R181 起的 pre-existing AgentToolTest |
| aethercode-memory | 113 | 113 | 0 | |
| aethercode-mcp | (passed) | | | |
| aethercode-hooks | (passed) | | | |
| aethercode-compact | (passed) | | | |
| aethercode-prompts | (passed) | | | |
| aethercode-sdk | (passed) | | | |
| aethercode-bridge | (passed) | | | |
| aethercode-protocol | 269 | 268 | 1 | 1 个 R183 起的 pre-existing R126Test |
| aethercode-models | (passed) | | | |

**0 regression** — 唯一 2 个失败都是 pre-existing（AgentToolTest + R126Test），
跟 R211 重构无关。

## 教训 (2026-09-04)

1. **"0 import ≠ 死代码" — 这是 R211 最大的教训** — 第一次重构时我 grep
   `org.aethercode.runtime.*` 外部 import 数 = 0，立刻判定"幽灵模块"删了。
   用户提醒"runtime 保障长周期任务"后我重新摸底，发现：
   - 长周期任务确实在 `aethercode-tasks` R300（372 tests pass），但跟
     `aethercode-runtime` 0 引用关系——是独立实现的 SQLite + JSON-RPC
   - `aethercode-runtime` 实际是 langchain_core + langgraph runtime 基础
     类型（Message / Tool / Store / Reducer / LLM 等），R1 移植时的
     配套占位（`<skipMain>`）
   - 即使这样，**设计意图不能光靠当前 import 数判断**。可能以后会有人想
     把 langchain_core 风格状态/消息统一到这个 runtime 上；删了就堵死了
     未来的整合路径
   - 教训：**删任何模块前先 `ask_user`**，即使"看着像"也没关系。模块的
     `pom description` + git log 注释 + 周边模块的"expected dependency"
     都要看一遍，不要看到 0 import 就当垃圾
2. **"幽灵模块"是代码考古的 worst case** — `<skipMain>true</skipMain>` +
   `<skipTestCompile>true</skipTestCompile>` 让 `aethercode-runtime` 源码骗了
   所有人：它看起来在用，但实际从未编译，外部 import 0 命中。**应该承认
   "幽灵"是它的当前状态，不是它的设计意图**——保留比删除更安全，因为
   设计意图可能比当前使用情况更"真实"。

2. **"package 声明和目录不一致"是 hack 信号** — 14 个文件物理住在
   `core/runtime/llm/` 但 package 声明是 `org.aethercode.runtime.llm`，
   让死模块的 package 名"看起来能用"。这种 hack 短期让 build 通过，
   长期让架构师没法 grep 到真相。修法：要么把文件挪到 package 匹配的目录，
   要么把 package 声明改成跟目录匹配 — 选后者更小动作。

3. **"整合" ≠ "把 aethercode-deepagents 整个砍掉"** — 用户的诉求是 "将
   aethercode-deepagents 整合到不同的模块"，意思是"把它的内容按能力归属
   散开"。"保留能够和 deepagents 整合的那部分"是说：凡是用来跟 deepagents
   整合的代码（graph agent loop、langchain compat、deepagents-style Tool），
   要保留。区分的标尺是 "depends on langchain_compat / deepagents.tools / 
   deepagents-style Middleware base" — 这些是 deepagents 特有，core 不能
   反向依赖。

4. **"反向依赖"会成 cycle** — 一开始想 "把 aethercode-core depends on
   aethercode-deepagents" 来解决 middleware 引用问题，但 deepagents 的 pom
   已经在 depends on core，会成 cycle。解法是按"是否依赖 deepagents 特有类型"
   拆成两堆，让 deepagents 显式 import core.middleware（单方向）。

5. **"wcmatch 风格"是 strict superset** — `core.fs.GlobMatcher`（NIO glob）
   全仓 0 引用；`backends.GlobMatcher`（wcmatch）功能 = {NIO glob} ∪
   {brace expansion} ∪ {dotfile 规则} ∪ {globstar 终位规则} ∪
   {MAX_EXPANSIONS 防护}。把简单的删了、留下超集，能力只增不减。

6. **"同包简单名"是隐藏的地雷** — 之前 5 个 HIL/Memory 等 middleware 跟
   `Middleware` 基类同包，不写 import 直接用。Middleware 迁到 deepagents 后
   那些 core.middleware 里的实现全部编译失败，编译期才发现。教训：跨
   module 重构时，先 grep `\bsimple-name\b` 找同包引用，一个个补 import。

7. **"BOM 不要混 UTF8/UTF8BOM"** — `langchain_compat/middleware/PrivateStateAttr.java`
   有 UTF-8 BOM，`Set-Content -Encoding UTF8` PowerShell 行为会加 BOM，
   `javac` 报错 "illegal character '\ufeff'"。修法：用
   `[System.IO.File]::WriteAllText($path, $content, $utf8NoBom)` 显式
   写 no-BOM UTF-8。
8. **"0 import ≠ 死代码"** — 第一次重构时我 grep `org.aethercode.runtime.*`
   外部 import 数 = 0，立刻判定"幽灵模块"删了。用户提醒"runtime 保障长周期
   任务"后我重新摸底，发现：
   - 长周期任务确实在 `aethercode-tasks` R300（372 tests pass），**但跟
     `aethercode-runtime` 0 引用关系**——是独立实现的 SQLite + JSON-RPC
   - `aethercode-runtime` 实际是 langchain_core + langgraph runtime 基础
     类型（Message / Tool / Store / Reducer / LLM 等），R1 移植时的
     配套占位（`<skipMain>`）
   - 即使这样，**设计意图不能光靠当前 import 数判断**。可能以后会有人想把
     langchain_core 风格状态/消息统一到这个 runtime 上；删了就堵死了
     未来的整合路径
   - 教训：删任何模块前先 `ask_user`，即使"看着像"也没关系。模块的
     `pom description` + git log 注释 + 周边模块的"expected dependency"
     都要看一遍，不要看到 0 import 就当垃圾
