# Providers — supported LLM brands and how to wire them

> Last verified: R340 (2026-09-24).

AetherCode treats every provider as an **OpenAI-compatible** HTTP
endpoint. Differentiation lives in `baseUrl` + `apiKeyEnv` per
provider, not in the chat-client code — see
`ProviderRegistry.bundledDefaults()` for the canonical
catalogue and `aethercode-core/src/main/java/org/aethercode/core/providers/`
for the schema.

The Settings → Provider picker reads this catalogue via the
`listProviders` JSON-RPC; the Settings panel is filtered by
"is `apiKeyEnv` non-blank in the daemon's env?" so users only
see brands they can actually call (toggle "Show all providers"
to see everything).

---

## Built-in catalogue (R340)

| Provider   | baseUrl                                  | apiKeyEnv          | defaultModel     | free / cheap tiers                |
| ---------- | ---------------------------------------- | ------------------ | ---------------- | ---------------------------------- |
| `minmax`   | `https://api.minimaxi.com/v1`            | `MINIMAX_API_KEY` | `MiniMax-M3`    | — (project's original target)     |
| `glm`      | `https://open.bigmodel.cn/api/paas/v4`   | `GLM_API_KEY`      | `glm-4-flash`   | `glm-4-flash`, `glm-4.5-flash`, `glm-z1-air` (often free in promos) |
| `qwen`     | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `DASHSCOPE_API_KEY` | `qwen-turbo` | `qwen-turbo`                       |
| `deepseek` | `https://api.deepseek.com`               | `DEEPSEEK_API_KEY` | `deepseek-chat` | `deepseek-chat` (~¥0.27/M input)  |
| `anthropic`| `https://api.anthropic.com/v1`           | `ANTHROPIC_API_KEY`| `claude-sonnet-4-5` | —                                  |
| `openai`   | `https://api.openai.com/v1`              | `OPENAI_API_KEY`   | `gpt-4o`        | —                                  |
| `google`   | `https://generativelanguage.googleapis.com/v1beta/openai` | `GEMINI_API_KEY` | `gemini-2.5-pro` | `gemini-2.5-flash` |

The Settings panel shows each provider's model list when picked.
Switching provider goes through `switchProvider` RPC which calls
`SpringAiChatClient.forProvider(spec, model)` to build a fresh
chat client.

## Quick-start: enable GLM / DeepSeek / Qwen

The daemon reads the env var matching the provider's `apiKeyEnv`
**at startup**. If you add `GLM_API_KEY` to your shell after
launching the desktop, you have to restart the desktop (so the
spawned daemon picks up the new env). To avoid that dance, set
the env var before launching.

**macOS / Linux (`~/.zshrc` or `~/.bashrc`):**

```sh
export GLM_API_KEY="sk-..."
export DEEPSEEK_API_KEY="sk-..."
export DASHSCOPE_API_KEY="sk-..."
```

**Windows (PowerShell, persistent):**

```powershell
[Environment]::SetEnvironmentVariable("GLM_API_KEY", "sk-...", "User")
[Environment]::SetEnvironmentVariable("DEEPSEEK_API_KEY", "sk-...", "User")
[Environment]::SetEnvironmentVariable("DASHSCOPE_API_KEY", "sk-...", "User")
# (the old session won't see it; start a new PowerShell or restart the desktop)
```

**Windows (cmd.exe, persistent):**

```cmd
setx GLM_API_KEY "sk-..."
setx DEEPSEEK_API_KEY "sk-..."
setx DASHSCOPE_API_KEY "sk-..."
```

After restarting the desktop, the Settings panel shows the
three providers with `hasApiKey=true` (toggle "Show all
providers" off to filter the view).

## Switch provider / model

Open **Settings → Model** (the section that lists "Provider
(current)"). Pick a provider in the first dropdown; the second
dropdown reloads with that provider's models. Pick a model and
click **Save**. The daemon rebuilds the chat client; the next
query uses the new endpoint.

For one-off switches without going through Settings, send the
JSON-RPC manually:

```sh
curl -X POST http://127.0.0.1:17888/jsonrpc \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"switchProvider","params":{"provider":"glm","model":"glm-4-flash"}}'
```

## Per-brand notes

### `glm` (智谱 / ZhipuAI)

Default `glm-4-flash` is the cheapest tier (¥0.1/M input +
¥0.1/M output, free in many promos). The R340 refresh adds the
late-2025 `glm-4.5` family (flagship + air + flash) and the
`glm-z1-air` reasoning model. All are tool-call capable.

Useful models:

- `glm-4-flash` — default; fastest, often free
- `glm-4.5-flash` — late-2025 flash; cheap + 1M context
- `glm-4.5` — flagship; use when the others drop a tool call
- `glm-z1-air` — free reasoning; matches `deepseek-reasoner`

### `qwen` (通义千问 / Alibaba DashScope)

Default `qwen-turbo` is the cheapest (¥0.3/M input + ¥0.6/M
output, 1M context). The R340 refresh adds the `qwen3`
family: `qwen3-max` (flagship reasoning), `qwen3-coder-plus`
(code completion), `qwen3-vl-plus` (vision).

Useful models:

- `qwen-turbo` — default; very cheap
- `qwen3-coder-plus` — best for code-heavy tasks
- `qwen3-max` — best for reasoning-heavy tasks
- `qwen3-vl-plus` — when you need vision input

### `deepseek` (深度求索 / DeepSeek)

Default `deepseek-chat` is the cheapest (cache miss ¥0.27/M
input + ¥1.1/M output). The R340 refresh adds the V3.x
family (`deepseek-v3`, `deepseek-v3.1`) which supports tool
calls + JSON mode. `deepseek-reasoner` is the R1 reasoning
model (separate endpoint, different pricing).

Useful models:

- `deepseek-chat` — default; cheap general chat
- `deepseek-v3.1` — newest; same price as chat
- `deepseek-reasoner` — R1 reasoning; slower but more accurate

## Override the catalogue per-project

Drop a `providers.yaml` at `<userHome>/.aethercode/providers.yaml`
or `<cwd>/.aethercode/providers.yaml` to add / replace entries.
**Note**: a user yaml **completely replaces** the bundled
defaults — there's no merge-by-name for the provider list
(only for top-level model name merge, see
`ModelRegistryLoader`). The legacy schema in
`aethercode-models/src/main/resources/providers.yaml` (the one
shipped inside the jar) is for the renderer-side
ModelCard / ModelPicker and is not the live list the daemon
uses for `listProviders`.

Example user-side `providers.yaml`:

```yaml
providers:
  - name: glm
    type: openai-compat
    baseUrl: https://open.bigmodel.cn/api/paas/v4
    apiKeyEnv: GLM_API_KEY
    defaultModel: glm-4-flash
    models:
      - id: glm-4-flash
        inputPer1k: 0.0
        outputPer1k: 0.0
        context: 1000000
        maxOutput: 1000000
        default: true
```

The daemon will log `[R81] jar: …` on startup showing which jar
it picked; the next `listProviders` RPC will return the
user-overridden list.

## Troubleshooting

**"requires env var X (unset)" when switching provider.** The
daemon's env didn't have `X` at startup. Restart the desktop
after exporting the env var (the daemon inherits from the
desktop's process env — there's no way to inject env into a
running daemon).

**GLM / Qwen / DeepSeek works locally but the Settings panel
shows them as `(no API key)`.** Same root cause — the desktop
process didn't have the env var. Use `Get-Process | Where
{$_.ProcessName -eq 'aethercode-desktop'} | Select Id, StartTime`
to see when the desktop started; if it was before you exported
the var, restart it.

**Switched provider but the next query still uses the old
model.** Make sure you clicked **Save** in Settings — the
panel is two-way and changes only persist on save. To force
the change over RPC, send `switchProvider` with the explicit
model id (not just the provider name).

**Provider appears in the picker with a name you don't
recognise.** Toggle "Show all providers" — AetherCode lists
every bundled brand by default, including the foreign ones
without your key. The picker's default view hides them so you
don't waste time on rows you can't actually call.

## Why the two yaml schema exist (a known smell)

`providers.yaml` is consumed by **two readers** with
**different shapes**:

1. `ProviderRegistry` (aethercode-core) — used by the daemon's
   `listProviders` RPC + Settings panel. Expects `name`,
   `type`, `baseUrl`, `apiKeyEnv`, `defaultModel` + `models[].id`,
   `inputPer1k`, `outputPer1k`, `context`, `maxOutput`,
   `default`.
2. `ModelRegistryLoader` (aethercode-models) — used by the
   renderer's ModelCard / ModelPicker. Expects the legacy
   shape with `models[].name`, `contextWindow`,
   `maxOutput`, `capabilities`, `pricing.*PerMTokensUsd`.

Both readers look at the **same file** (`aethercode-models/src/main/resources/providers.yaml`),
which is why the resource file still uses the legacy shape —
it has to satisfy both. The live provider catalogue the daemon
uses at startup is `ProviderRegistry.bundledDefaults()`
hard-coded in Java, not this resource file. The yaml is only
read when `<userHome>/.aethercode/providers.yaml` exists.

A future round should harmonise the two schemas into a single
source of truth; for now, see
`bundledDefaults()` for the actual R340+ list.