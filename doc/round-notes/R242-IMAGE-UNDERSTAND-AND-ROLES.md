# R242 — VLM image_understand 工具 + RoleRegistry & SharedBlackboard

**日期**: 2026-09-10
**Round**: R242 (R242.1 + R242.2)
**状态**: ✅ 完成
**触发**: R239 路线图 R242 = "O-7 VLM 起步 + O-9 RoleRegistry"

---

## 0. TL;DR

| 子项 | 实际产出 | 测试 |
|------|---------|------|
| **R242.1 O-7 VLM** | 4 java (VlmClient + MockVlmClient + OpenAiCompatibleVlmClient + ImageUnderstandTool) | 12/12 pass |
| **R242.2 O-9 RoleRegistry** | 4 java (Role + RoleRegistry + StandardRoles + SharedBlackboard) | 26/26 pass |
| **总耗时** | < 1 round | 0 回归（aethercode-deepagents 66/66 全过） |

---

## 1. R242.1 — O-7 VLM 起步

### 1.1 已有 vs 缺什么

| 能力 | R242 启动时 |
|------|---------|
| `MultimodalContentScrubber` (aethercode-core) | ✅ 处理读进来的多模态内容 |
| `FilesystemMiddleware` + `read_file` | ✅ 读文件含图片/PDF |
| `Tool` 接口 + `Tools.build()` 模板 | ✅ |
| OkHttp + jackson | ✅ |
| **VLM tool**（让 chat model 调 VLM） | ❌ R242.1 补 |

### 1.2 架构

```
VlmClient (interface)
├── MockVlmClient             ← 测试 / dev 兜底
├── OpenAiCompatibleVlmClient ← OpenAI / Qwen-VL / InternVL / GLM-4V / CogVLM2
└── (未来) LocalVlmClient     ← Ollama / vLLM

ImageUnderstandTool
├── name: "image_understand"
├── args: { path, prompt, model? }
└── 调用 VlmClient
```

### 1.3 实战（env 一设就生效）

```bash
export AETHERCODE_VLM_API_KEY=sk-...
export AETHERCODE_VLM_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
export AETHERCODE_VLM_MODEL=qwen-vl-plus
```

chat model 调用：
```json
{"tool": "image_understand", "input": {"path": "/tmp/shot.png", "prompt": "What's the error?"}}
```

### 1.4 关键文件

- `aethercode-tools/.../vision/VlmClient.java` (3.4 KB) — interface + Options record
- `aethercode-tools/.../vision/MockVlmClient.java` (4.7 KB) — fixture 模式 + CallRecord
- `aethercode-tools/.../vision/OpenAiCompatibleVlmClient.java` (7.9 KB) — OpenAI-style wire + fromEnvOrNull
- `aethercode-tools/.../vision/ImageUnderstandTool.java` (5.4 KB) — 暴露给 chat model

---

## 2. R242.2 — O-9 RoleRegistry & SharedBlackboard

### 2.1 设计

按 Paper 6 (Brahmi 2025) §IV 和 Paper 8 (Peykani 2026) §3.2，**角色驱动的多 agent 分解**是处理复杂任务的标准方式。AetherCode 已有：
- `aethercode-tasks.AsyncSubAgentSpec` — async 子 agent 描述
- `aethercode-deepagents.SubAgent` + `SubAgentMiddleware` — 子 agent + 接入 CreateDeepAgent
- `SubAgentPrompts.GENERAL_PURPOSE_SUBAGENT` — 1 个通用角色

**缺**：
- 角色抽象（5 个标准角色）
- RoleRegistry（多角色管理 + listener）
- SharedBlackboard（跨角色状态共享）

### 2.2 5 个标准角色（按 CrewAI / AutoGen 惯例）

| 角色 | 职责 | 工具限制 |
|------|------|---------|
| **planner** | 分解目标成有序计划 | 仅 read（不写不执行） |
| **researcher** | 收集外部上下文（web / 文件 / 历史） | 仅 read |
| **coder** | 实现计划（写代码） | 文件工具（不含 rm/sudo） |
| **reviewer** | 审计 bug / style / 安全 | 仅 read |
| **executor** | 跑测试 / 脚本 / 部署 | shell 工具 |

### 2.3 3 个核心类

```java
Role          // name + description + systemPrompt + tool allow-list
RoleRegistry  // 5 个标准角色 + register / replace / listener / asSubAgents()
SharedBlackboard // key-value + seq + role tag + listener + since(seq) + withLock
```

### 2.4 一行集成到 CreateDeepAgent

```java
RoleRegistry registry = new RoleRegistry();
CreateDeepAgent.create(
    "openai:gpt-4o",
    tools,
    systemPrompt,
    null,  // middleware
    registry.asSubAgents(),  // ← 5 个 subagent 自动接进链
    null,  // skills
    ...);
```

### 2.5 SharedBlackboard 用法

```java
SharedBlackboard bb = new SharedBlackboard("task-1");
bb.put("plan", "1. read\n2. edit", RoleRegistry.standardRoles().get(0));  // planner
bb.put("diff", "patched", RoleRegistry.standardRoles().get(2));  // coder
List<Entry> recent = bb.since(0L);  // reviewer / executor 拿最新
```

### 2.6 关键文件

- `aethercode-deepagents/.../roles/Role.java` (4.5 KB) — record + Builder
- `aethercode-deepagents/.../roles/RoleRegistry.java` (6.2 KB) — 5 个标准 + listener + asSubAgents
- `aethercode-deepagents/.../roles/StandardRoles.java` (5.9 KB) — planner / researcher / coder / reviewer / executor
- `aethercode-deepagents/.../roles/SharedBlackboard.java` (6.3 KB) — Map + seq + role + listener

---

## 3. 测试结果

### 3.1 aethercode-tools（R242.1）

```
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.032 s
       -- in org.aethercode.tools.vision.ImageUnderstandToolTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
```

### 3.2 aethercode-deepagents（R242.2 + 历史 0 回归）

```
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
       -- in org.aethercode.deepagents.roles.RoleRegistryTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
       -- in org.aethercode.deepagents.roles.SharedBlackboardTest
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
       -- in org.aethercode.deepagents.middleware.TreeOfThoughtsMiddlewareTest   (R240.2)
[INFO] Tests run: 66, Failures: 0, Errors: 0, Skipped: 0
```

### 3.3 aethercode-core 间接依赖

1156 tests pass，0 回归。

---

## 4. 关键设计决定

### 4.1 VlmClient interface（不是 class）

- Mock / HTTP / 未来 LocalVLM 都走同一接口
- 测试可以注入 mock，**不需要任何 API key**
- 从 env 自动加载真实 client，env 缺则降级到 mock

### 4.2 OpenAI 协议覆盖最广

- 不只是 OpenAI gpt-4o
- Qwen2-VL / InternVL2 / GLM-4V / Yi-VL / CogVLM2 / Llama-3.2-Vision 全走 OpenAI-compat gateway
- 一个 client 覆盖国内 + 国际 + 开源

### 4.3 image_understand opt-in

- 不集成到 StandardTools（避免 baseline tool count 膨胀）
- 调用方显式 add

### 4.4 5 个标准角色按 CrewAI / AutoGen 惯例

- 团队协作的成熟 pattern
- 每个角色有独立 systemPrompt（≤ 200 字）
- tool allow-list 防止 reviewer 删文件 / executor 改代码

### 4.5 RoleRegistry 编译到 SubAgent[]

- 一行集成：`registry.asSubAgents()`
- 不破坏 CreateDeepAgent 现有 API
- 自定义角色 + 标准角色 一起返回

### 4.6 SharedBlackboard 独立于 AgentState

- 跨角色通信专用
- 不在 model context 里（避免污染）
- `since(seq)` 让 reviewer 只看 coder 的最近输出

---

## 5. 跟 R239 报告的对照

| R239 估计 | **R242 实际** |
|------|------|
| "O-7 VLM 起步 3-4 round" | **< 1 round** |
| "O-9 RoleRegistry (中等 ROI) 2-3 round" | **< 1 round** |
| **R242 总工作量 5-7 round** | **< 1 round** |

R242 **又低估 70%+**（连续 4 round：R240/R241/R242.1/R242.2 全部 < 1 round）。

**原因**：
- aethercode 已有完整 SubAgent / SubAgentMiddleware / SubAgentPrompts（DeepAgents 120 java 模板清晰）
- Tool 接口 + Tools.build() 让 VLM tool 一行集成
- `aethercode-deepagents` 测试套件已经覆盖 12 个 Middleware 模式（ToT 走的就是这模式）

---

## 6. 用户可见行为变化

### 6.1 之前

- chat model 看到图片路径 → 只能"假设内容"或 `read_file` 读文本
- 复杂任务只能"独奏"（一个 agent 硬扛）
- 角色分工靠 prompt 写死，不能复现

### 6.2 之后

- chat model 看到图片 → 调 `image_understand(path, prompt)` → 拿真实描述
- 5 个标准角色按需组合（planner 分解 → researcher 收集 → coder 实现 → reviewer 审计 → executor 跑测试）
- 角色可注册 / 替换 / 监听，**可复现 + 可观测**
- SharedBlackboard 让多角色高效协作（不污染 context）

---

## 7. R239 路线图进展

| Round | 子项 | 状态 | 实际 |
|------|------|------|------|
| R240.1 | O-5 Limits 用户面 | ✅ | < 1 round |
| R240.2 | O-4 ToT Middleware | ✅ | < 1 round |
| R241.1 | O-2 A2A 协议 | ✅ | < 1 round |
| **R242.1** | **O-7 VLM** | ✅ | **< 1 round** |
| **R242.2** | **O-9 RoleRegistry + SharedBlackboard** | ✅ | **< 1 round** |
| R241.2 | O-3 ExperienceStore → 策略库 | ⏸️ | — |
| R243 | O-3 完整闭环 + O-8 审计 | 📋 | — |
| R244 | O-6 持续学习 + O-10 跨 surface | 📋 | — |
| R250+ | O-11/12/13/14 神经-符号等 | 📋 | — |

---

## 8. 后续 (R243+ scope)

1. **O-3 ExperienceStore → 策略库**（R241.2 / R243）— 自我改进闭环
2. **O-8 全局审计 + DRIFT 动态规则**（R243）— 安全合规
3. **O-6 持续学习 + 知识编辑**（R244）— 持续对齐
4. **O-10 跨 surface 状态共享**（R244）— TUI/Desktop/IDEA 接续
5. **出包 0.2.58**（可选）— R240-R242 改动完了
6. **VLM 集成到 deepagents** — 当前 image_understand 是 opt-in tool，未来可以让 ChatModel 直接处理 image content blocks
7. **RoleRegistry 从 YAML 加载** — 跨工程复用角色定义

---

## 9. 关键文件路径

| 项 | 路径 |
|----|------|
| R242.1 新包 | `aethercode/aethercode-tools/src/main/java/org/aethercode/tools/vision/` |
| R242.1 Test | `aethercode/aethercode-tools/src/test/java/org/aethercode/tools/vision/ImageUnderstandToolTest.java` |
| R242.2 新包 | `aethercode/aethercode-deepagents/src/main/java/org/aethercode/deepagents/roles/` |
| R242.2 Tests | `aethercode/aethercode-deepagents/src/test/java/org/aethercode/deepagents/roles/{RoleRegistry,SharedBlackboard}Test.java` |
| R242.1 报告 | `doc/项目文档/R242-VLM-IMAGE-UNDERSTAND-TOOL.md` |
| R242 报告 | `doc/项目文档/R242-IMAGE-UNDERSTAND-AND-ROLES.md` (本文件) |

---

## 10. 教训

1. **aethercode 模板完整让 VLM + Role 都 < 1 round** — Tool 接口 + SubAgent 抽象都已经成熟
2. **5 个标准角色按行业惯例** — CrewAI / AutoGen / LangGraph 都用类似模式，**不重新发明**
3. **SharedBlackboard 不污染 model context** — 跨 agent 状态专用，独立于 AgentState
4. **Role.allows() 工具 allow-list** — 防止 reviewer 删文件 / executor 改代码
5. **HashMap iteration order 不保证** — `asSubAgents()` 强制按 `StandardRoles.all()` 顺序返回
6. **测试 lambda 内部不能 modify local var** — 用 `AtomicReference` / `AtomicInteger`
7. **Surefire 3.3.1 不支持 comma 分割 test names** — 一次跑一个 test class

---

**作者**: mavis (Mavis, MiniMax Code)
**用时**: ~50 分钟（R242.1 ~25 + R242.2 ~25 + 测试 + 报告）
**总字数**: ~2500 字
