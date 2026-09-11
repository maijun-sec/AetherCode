# Providers — LLM Provider 接入

> AetherCode 通过可插拔 `ChatClient` 接口接 LLM provider。默认实现用 Spring AI,支持任意 OpenAI-compatible transport (OpenAI、Anthropic via OpenAI compat、GLM、vLLM、Ollama 等)。在 `~/.aethercode/settings.yaml` 声明即接入新 provider。
>
> **整合自 `doc/PROVIDERS.md`** (R250+7 doc 重构),已翻译为中文。

---

## 1. 配置

```yaml
providers:
  - name: anthropic
    type: openai-compatible
    baseUrl: https://api.anthropic.com
    apiKey: ${ANTHROPIC_API_KEY}
    defaultModel: claude-sonnet-4
    models:
      - id: claude-sonnet-4
        inputPer1k: 0.003
        outputPer1k: 0.015
      - id: claude-opus-4
        inputPer1k: 0.015
        outputPer1k: 0.075

  - name: glm
    type: openai-compatible
    baseUrl: https://open.bigmodel.cn/api/paas/v4
    apiKey: ${GLM_API_KEY}
    defaultModel: glm-4-flash
    models:
      - id: glm-4-flash
        inputPer1k: 0.0001
        outputPer1k: 0.0001

  - name: local-ollama
    type: openai-compatible
    baseUrl: http://localhost:11434/v1
    apiKey: ollama                  # ignored, but required by the schema
    defaultModel: qwen2.5-coder:32b
    models:
      - id: qwen2.5-coder:32b
        inputPer1k: 0
        outputPer1k: 0              # 本地, 无成本
```

### 1.1 Provider 字段

| 字段 | 必填 | 说明 |
|---|---|---|
| `name` | ✅ | Provider id; 在 `model: <name>/<id>` 引用中用到 |
| `type` | ✅ | 目前只支持 `openai-compatible` |
| `baseUrl` | ✅ | Provider API 根 URL (无尾斜杠) |
| `apiKey` | ✅ | API key; 支持 `${ENV_VAR}` 引用 |
| `defaultModel` | ✅ | engine 启动时用的 model id |
| `models` | ✅ | model 条目列表 |

### 1.2 Model 字段

| 字段 | 必填 | 说明 |
|---|---|---|
| `id` | ✅ | API 请求里发的 model id |
| `inputPer1k` | ✅ | USD / 1000 input token (成本跟踪用) |
| `outputPer1k` | ✅ | USD / 1000 output token |
| `default` | ❌ | 每个 provider 只能有一个 model 标 default |

---

## 2. 切换 Provider

### 2.1 配置文件

编辑 `~/.aethercode/settings.yaml` 重启 daemon。Desktop 和 TUI 下次启动读到新值。

### 2.2 TUI

```
/model glm/glm-4-flash
```

这调 `switchProvider({provider: "glm", model: "glm-4-flash"})`,行为 (R109-3 修):

1. 通过 `chatClientResolver` (CLI 注入) 解析 provider/model → `ChatClient`
2. 调 `engine.setChatClient(newClient)`,主循环下次 query 用新 client
3. 调 `engine.mainLoopModelName(modelId)`,`getState` 报告新 model

### 2.3 Desktop

`Settings → Models` tab。点 model 行,改 hot 生效 (R109-3 修),下次 query 用新 model。

---

## 3. `model: <provider>/<id>` 怎么解析

Engine 看到 `model: glm/glm-4-flash` 字符串时:

1. 按第一个 `/` 切,得 `(providerName, modelId)`
2. 在 registry 里查 provider
3. 在 provider 的 `models:` 列表里查 model
4. 返回对应 `ChatClient` (由 CLI 的 `chatClientResolver` 构建)

如果 model 找不到,engine 回退到主循环 default + 警告。**如果 provider 找不到,engine 返回错误,Agent step 跳过**。

---

## 4. Per-Agent Model (R109-3)

AetherCode Agent 可以 pin 特定 provider/model:

```markdown
---
name: code-reviewer
model: glm/glm-4-flash
---
```

当 workflow executor 的 `kind: agent` 步骤派生这个 Agent 时,LLM call 走对应 `ChatClient` via `engine.query(prompt, override)`。**Engine 的主循环 `chatClient` 字段不会被改**,并发的主循环 query 和子 session 不会竞争。

详细 Agent 编写看 [`../user-guide/agents.md`](../user-guide/agents.md)。

---

## 5. 定价准确性

`inputPer1k` 和 `outputPer1k` 数字喂给 `CostTracker.record(...)`,在每次 LLM 响应后调。Desktop 的 `StatusBar` 和 TUI 的状态行显示累计成本;`/metrics` 报快照。

这些数字应该跟 provider 公布定价对齐 (写文件时的日期)。定价页参考:

- OpenAI: <https://openai.com/api/pricing/>
- Anthropic: <https://www.anthropic.com/pricing>
- GLM (Zhipu): <https://open.bigmodel.cn/pricing>

**本地 model** (Ollama、vLLM) 把两个都设 `0`,让成本计数器保持零。

---

## 6. 跨参考

- [`./architecture.md`](./architecture.md) — engine 的 `ChatClient` 接口在系统里的位置
- [`../user-guide/agents.md`](../user-guide/agents.md) — per-agent model 绑定 (R109-3)
- [`./workflow-engine.md`](./workflow-engine.md) — per-step model override (R110+)
- `aethercode-desktop/docs/R109-1-MULTI-PROVIDER-2026-08-13.md` — multi-provider 引入
