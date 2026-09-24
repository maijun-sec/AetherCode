# Providers — supported LLM brands and how to wire them

> Last verified: R341 (2026-09-24).
>
> R341 changes:
> - the bundled catalogue is now a YAML resource
>   (`aethercode-core/src/main/resources/aethercode-providers.yaml`)
>   rather than hardcoded Java; `bundledDefaults()` is the
>   last-resort fallback only.
> - the daemon reads env vars across **Process → User →
>   Machine** scopes via `RegistryHelper.readEnv(name)`
>   (previously just `System.getenv()` — Machine-level
>   `HKLM\...\Session Manager\Environment` entries were
>   invisible to the spawned daemon, which made
>   `DEEPSEEK_API_KEY` "missing" for users who set it via
>   `setx /m`).
> - new provider spec fields: `apiKey` (inline override),
>   `enabled` (hide from picker), `headers`, `timeout`,
>   `connectTimeout`.
> - the Settings panel switched to a 2-level picker
>   (`ProviderModelPicker`) with provider chips + a
>   case-insensitive model filter input — 30+ models in a
>   single dropdown was the user complaint that triggered
>   the round.

AetherCode treats every provider as an **OpenAI-compatible** HTTP
endpoint. Differentiation lives in `baseUrl` + `apiKeyEnv` per
provider, not in the chat-client code. The schema is the
`ProviderSpec` record in
`aethercode-core/src/main/java/org/aethercode/core/providers/`.

The Settings → Provider picker reads this catalogue via the
`listProviders` JSON-RPC; the Settings panel is filtered by
"is `apiKeyEnv` non-blank in the daemon's env?" so users only
see brands they can actually call (toggle "Show all providers"
to see everything). R341 calls `RegistryHelper.readEnv()` which
walks **Process → User → Machine** scope so a key set with
`setx /m` (Machine scope) is visible to the daemon too —
previously it wasn't, and the Settings panel reported
`(no API key)` for users who'd correctly configured their
account.

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

Open **Settings → Model**. The new R341 picker shows a row of
provider chips (current provider has a filled dot, others an
outline dot). Click a chip to switch the model list to that
brand; type in the filter input to narrow the model list (case-
insensitive substring match). Click a model row to set it as the
pending pick; the **Save** button commits the change (the
daemon rebuilds the chat client and the next query uses the
new endpoint).

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

**R341 schema** (`aethercode-core/src/main/resources/aethercode-providers.yaml`):

```yaml
providers:
  - name: glm
    type: openai-compat            # optional, default openai-compat
    baseUrl: https://...           # required
    apiKeyEnv: GLM_API_KEY        # env var name to resolve the key from
    # apiKey: sk-xxx               # R341: optional inline override (NOT recommended)
    # enabled: true                # R341: default true; false hides from picker
    # headers:                     # R341: extra HTTP headers per request
    #   X-Trace-Id: aethercode
    # timeout: 60000               # R341: Spring AI timeout (ms), null = default
    # connectTimeout: 10000        # R341: TCP connect timeout (ms)
    defaultModel: glm-4-flash      # optional
    models:
      - id: glm-4-flash
        context: 1000000
        maxOutput: 1000000
        inputPer1k: 0.0
        outputPer1k: 0.0
        default: true
```

API key resolution chain (R341):

1. `apiKey` field (if set)
2. `apiKeyEnv` + `RegistryHelper.readEnv()` — Process → User → Machine scope
3. Derived `<UPPER_NAME>_API_KEY` (e.g. `GLM_API_KEY`) via the same 3-scope lookup
4. Nothing → `hasApiKey: false`, picker hides the brand

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
shows them as `(no API key)` even though the env var is set.**
R341: `RegistryHelper.readEnv()` walks Process → User → Machine
scope — most cases resolve correctly. If you set the var
via `setx /m` (Machine scope) **before** logging into Windows,
it should now be visible. If you set it via `setx` (User scope)
the existing PowerShell session won't see it; restart the
desktop. If neither scope shows the var, check
`[Environment]::GetEnvironmentVariable('VAR_NAME', 'Machine')`
to confirm it's actually persisted (Windows sometimes
truncates Machine-scope env values > 1024 chars).

**Use `Get-Process | Where {$_.ProcessName -eq
'aethercode-desktop'} | Select Id, StartTime`** to see when
the desktop started; if it was before you exported the var,
restart it.

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