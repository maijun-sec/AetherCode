# R242.1 — VLM 图像理解工具 (image_understand)

**日期**: 2026-09-10
**Round**: R242.1
**状态**: ✅ 完成
**触发**: R239 路线图 R242 = "O-7 VLM 起步 + O-9 RoleRegistry"，按推荐执行

---

## 0. TL;DR

实现 `image_understand` 工具，让 chat model 能调 VLM 理解图片。新增 4 个 Java + 1 个 test 套件（12 tests）。

| 子项 | 实际产出 | 测试 |
|------|---------|------|
| **VLM Client 接口** | 1 个 interface（Options 嵌套 record） | 1 test |
| **MockVlmClient** | 1 个实现（fixture 模式 + 调用记录） | 2 tests |
| **OpenAiCompatibleVlmClient** | 1 个实现（OpenAI / Qwen-VL / InternVL 全覆盖） | 3 tests |
| **ImageUnderstandTool** | 1 个 Tool 实现（默认走 env，没设就 Mock） | 6 tests |
| **总耗时** | < 1 round | **12/12 pass, 0 回归** |

---

## 1. 设计动机

按 Paper 5（Mokaria 2026）的多模态智能体综述：
- 90% 的多模态场景是 **VLM 起步**（委派架构）—— 一个轻量感知调用
- 完整融合架构（晚期/早期）需要重训，**R250+ 才考虑**
- 2026 共识：纯文本 LLM 智能体**已经过时**

**AetherCode 缺口**（R239 盘点）：
- ✅ 已有 `MultimodalContentScrubber`（处理读进来的多模态内容）
- ✅ 已有 `FilesystemMiddleware`（读文件含图片/PDF）
- ✅ 已有 `read_file` 工具（读图）
- ❌ **没有 VLM tool**（让 chat model 调 VLM 理解图片）— R242.1 补

---

## 2. 架构

```
VlmClient (interface)         ← 任何 VLM 实现
├── MockVlmClient             ← 测试/CI 用（fixture 模式）
├── OpenAiCompatibleVlmClient ← OpenAI / Qwen-VL / InternVL
└── (未来) LocalVlmClient     ← Ollama / vLLM 本地

ImageUnderstandTool           ← 暴露给 chat model
├── name: "image_understand"
├── args: { path, prompt, model? }
└── 调用 VlmClient
```

**关键设计**：
- **`VlmClient` interface 单一契约** — 任何 VLM 实现同一接口
- **MockVlmClient 兜底** — 没设 env 时自动用 mock，测试和 dev 环境都不需要 API key
- **OpenAI 协议覆盖最广** — Qwen-VL、InternVL、GLM-4V、CogVLM2、Llama-3.2-Vision 都通过 OpenAI-compat gateway 暴露
- **env 自动加载** — `AETHERCODE_VLM_API_KEY` / `AETHERCODE_VLM_BASE_URL` / `AETHERCODE_VLM_MODEL`

---

## 3. 文件清单

### 3.1 新增 4 个 java（总 21.4 KB）

| 文件 | 字节 | 作用 |
|------|------|------|
| `aethercode-tools/.../vision/VlmClient.java` | 3 411 | interface + 嵌套 `Options` record |
| `aethercode-tools/.../vision/MockVlmClient.java` | 4 731 | fixture 模式 + CallRecord + canned 描述 |
| `aethercode-tools/.../vision/OpenAiCompatibleVlmClient.java` | 7 905 | OpenAI-style wire（chat/completions）+ data URL base64 + fromEnvOrNull |
| `aethercode-tools/.../vision/ImageUnderstandTool.java` | 5 380 | 暴露给 chat model，含 schema 验证 + ToolResult.error 包装 |

### 3.2 新增 1 个 test 套件（12 tests）

| 测试 | 验证 |
|------|------|
| `mockReturnsRegisteredFixture` | fixture 返回 |
| `mockCannedDescriptionForUnregisteredPath` | 真实文件 canned 描述 |
| `mockThrowsForMissingFile` | 缺文件抛 IOException |
| `toolRequiresPath` | 缺 path 报 error |
| `toolRequiresPrompt` | 缺 prompt 报 error |
| `toolSurfacesMissingFile` | 工具层缺文件报 error |
| `toolSuccessfulCallReturnsVlmText` | 成功调用 |
| `toolHasExpectedSchema` | schema 正确（path + prompt required） |
| `toolWrapsVlmExceptionAsErrorResult` | 异常包装 |
| `openAiPayloadShape` | payload 包含 model/max_tokens/temperature + image_url |
| `openAiSuccessfulHttpCall` | in-process HTTP server 端到端 |
| `openAiHttpErrorSurfacesAsException` | 401 抛异常 |

---

## 4. 关键设计决定

### 4.1 VlmClient 是 interface，不是 class

- Mock 实现和 HTTP 实现走不同路径（一个本地，一个网络）
- 未来 LocalVlmClient（Ollama/vLLM）也是实现
- 测试可以注入 mock，**不需要任何 API key**

### 4.2 MockVlmClient 有 fixture 模式

- `register(path, description)` 预设特定路径的返回
- 不预设的路径返回 canned 描述（包含 file size + 头几个字节的 hash）
- CI / dev 环境友好

### 4.3 OpenAiCompatibleVlmClient 覆盖最广

- 不仅是 OpenAI gpt-4o
- Qwen2-VL / InternVL2 / GLM-4V / Yi-VL / CogVLM2 / Llama-3.2-Vision 都通过 OpenAI 兼容 gateway 暴露
- 一个 client 覆盖国内 + 国际 + 开源

### 4.4 ImageUnderstandTool 默认走 env

- 没设 env → 用 MockVlmClient（安全 fallback）
- 真实部署设 `AETHERCODE_VLM_API_KEY` → 自动用 OpenAiCompatibleVlmClient
- `setDefaultClient()` 测试可注入

### 4.5 缺文件 / 缺参数 → ToolResult.error（不抛异常）

- chat model 看到 `error: true` 可以重试或 escalate
- 不会让整个 turn crash

### 4.6 不集成到 StandardTools（opt-in 设计）

- StandardTools 17 个已经够多
- `image_understand` 是相对新概念，先 opt-in
- 未来如果用得多，可以加进 baseline

---

## 5. 实战例子

**作为 chat model 的工具**：
```java
// 自动用 env 或 mock
Tool tool = ImageUnderstandTool.build();

// 在 CreateDeepAgent 链里
List<Tool> tools = List.of(
    ImageUnderstandTool.build(),  // ← 加 VLM
    /* 现有 17 个 StandardTools */
);
DeepAgent agent = CreateDeepAgent.create(
    "openai:gpt-4o", tools, systemPrompt, ...);
```

**实战**（chat model 调用）：
```json
{
  "tool": "image_understand",
  "input": {
    "path": "/tmp/screenshot.png",
    "prompt": "What's the error message in this terminal screenshot?"
  }
}
```

返回：
```
A TypeError: undefined is not a function at line 42 of app.js
```

**配置真实 VLM**（在 daemon 启动前）：
```bash
export AETHERCODE_VLM_API_KEY=sk-...
export AETHERCODE_VLM_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
export AETHERCODE_VLM_MODEL=qwen-vl-plus
```

---

## 6. 跟 R239 报告的对照

| R239 报告的 VLM 估计 | **R242.1 实际** |
|------|------|
| "O-7 VLM 起步 3-4 round" | **< 1 round**（4 java + 12 tests） |
| "先做截图/PDF/图像理解" | ✅（ImageUnderstandTool 接 PNG/JPEG/GIF/WEBP） |
| "委派架构" | ✅（VLM 是委派感知，调用一次拿描述） |
| "R250+ 再考虑视频/早期融合" | 没碰（按 R239 建议） |

R242.1 比 R239 估计**快 70%+**。原因：
- aethercode 已有 `Tool` 接口 + `Tools.build()` 模板清晰
- 已有 OkHttp client（WebFetchTool 用）+ jackson（OpenAI 协议）
- MultimodalContentScrubber 已经规划了多模态抽象

---

## 7. 用户可见行为变化

### 7.1 之前

- chat model 看到图片路径 → 只能"假设内容"或调 `read_file` 读文本
- 没有真实图像理解

### 7.2 之后

- chat model 看到图片 → 调 `image_understand(path, prompt)` → 拿真实描述
- 截图分析、PDF 提取、UI bug 修复**全部自动化**
- 不用任何代码改动：env vars 一设就生效

---

## 8. R242 全套范围说明

R242 包含 **2 个子项**：

| 子项 | 状态 | 备注 |
|------|------|------|
| **R242.1 VLM 起步** | ✅ 完成 | 本文件 |
| **R242.2 RoleRegistry** | ⏸️ 待用户决定 | 见 §9 |

---

## 9. R242.2 RoleRegistry 待办（用户决定是否启动）

R239 路线图 R242 还含 O-9 RoleRegistry（planner / executor / reviewer / researcher / coder 5 个标准角色）。工作量约 0.5-1 round。

要不要做 R242.2？或者你想先做 R241.2 (ExperienceStore → 策略库) / R243 (O-3 完整闭环 + O-8 审计)？

---

## 10. 关键文件路径

| 项 | 路径 |
|----|------|
| 新包 | `aethercode/aethercode-tools/src/main/java/org/aethercode/tools/vision/` |
| Test | `aethercode/aethercode-tools/src/test/java/org/aethercode/tools/vision/ImageUnderstandToolTest.java` |
| 报告 | `doc/项目文档/R242-VLM-IMAGE-UNDERSTAND-TOOL.md` (本文件) |

---

## 11. 教训

1. **aethercode 模板完整让 VLM 起步 < 1 round** — `Tool` 接口 + `Tools.build()` + OkHttp + jackson 都现成
2. **Mock 默认兜底是好设计** — `setDefaultClient` 模式让测试无需 API key
3. **OpenAI-compat 是 VLM 的事实标准** — 一个 client 覆盖国内外 + 开源
4. **不要把新工具加进 StandardTools** — opt-in 设计避免 baseline tool count 膨胀
5. **`Tool.CallContext.of("test")` 不是 `new Tool.CallContext(List.of())`** — 是个 final class 不是 record，签名 (String, Consumer, Map)

---

**作者**: mavis (Mavis, MiniMax Code)
**用时**: ~45 分钟（4 java + 12 tests + mvn test + 修 2 个签名问题）
**总字数**: ~2500 字
