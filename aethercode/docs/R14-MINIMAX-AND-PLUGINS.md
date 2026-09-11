# R14 — MiniMax Coding Plan + TUI Smoke + IDEA Plugin Wiring

**Status**: DONE — 5/5 candidates, **1216 tests (+44)**, 0 fail, 15/15 modules BUILD SUCCESS
**Backup**: `D:\work\tmp\r14_done\` (TBD after doc)
**Date**: 2026-08-04

R14 wires AetherCode to the MiniMax Coding Plan (highest paid tier, M3 series) on
the OpenAI-compatible protocol, then proves the existing TUI boots cleanly through
that path, then teaches the IDEA plugin to use the same provider system. R14-4
finally gives `PluginManifest` a real loader so third-party extensions can be
discovered and instantiated without restart.

## 1. MinimaxChatClient (aethercode-llm/minimax) — 18 tests

OpenAI-compatible chat client. Endpoints:

- Base URL: `https://api.minimaxi.com/v1` (filled in by the factory if blank)
- Chat: `POST /chat/completions` with `Authorization: Bearer <MINIMAX_API_KEY>`
- Streaming: SSE, terminates with `data: [DONE]`
- Tools: `[{type:"function", function:{name, description, parameters:json-schema}}]`
- Tool results: separate `role:"tool"` messages keyed by `tool_call_id`

The request body is built from internal `Message` / `ContentBlock` / `Tool` records:

- System prompt becomes a `role:"system"` message prepended to the array
- A user message containing only `ToolResultBlock`s expands to N `role:"tool"` messages
  (OpenAI requires one tool message per `tool_call_id`)
- Assistant turns get a `content` string + optional `tool_calls` array

Streaming parser:

- Each chunk's `choices[i].delta.content` → `TextDelta` event
- `choices[i].delta.tool_calls` deltas are accumulated by `index` until the chunk's
  `finish_reason` is non-null; then a `ToolUseStart` is emitted for each completed
  call
- `usage: {prompt_tokens, completion_tokens}` (when present) is captured for the
  cost-tracker callback
- `[DONE]` triggers `RunEnd` with assembled `finalBlocks` (text + tool_use)

`done` guard prevents `RunEnd` from being emitted twice when the server closes
the SSE stream after sending `[DONE]`. The early-flush path (on `finish_reason`)
only emits events, never mutates `finalBlocks`; assembly happens exactly once
in the final pass to keep block order correct.

Fail-fast: if `Options.apiKey()` is null/blank at construction, the client throws
`IllegalStateException` with a clear message — saves the 120-second read-timeout
hang on misconfigured CLI invocations.

## 2. ChatClientFactory (aethercode-llm) — 10 tests

Single entry point that builds the right `ChatClient` for a provider name:

- `create("anthropic", model, options)` → `AnthropicChatClient`, fills in
  `https://api.anthropic.com` baseUrl and `ANTHROPIC_API_KEY` env var
- `create("minimax", model, options)` → `MinimaxChatClient`, fills in
  `https://api.minimaxi.com/v1` baseUrl and `MINIMAX_API_KEY` env var
- Default provider is `"anthropic"` (preserves R1-R13 behaviour)
- Provider name is case-insensitive
- Unknown provider throws `IllegalArgumentException` with a helpful message

The factory is the single switch-point in `AetherCodeEngine.Builder` — the engine
no longer imports `AnthropicChatClient` directly for instantiation, just for the
usage-listener hookup (which is provider-specific).

CLI flag: `--provider anthropic|minimax` (default: `anthropic`). When the user
passes `--provider minimax` without explicitly setting `--model`, the CLI
substitutes the provider's default (`MiniMax-M3`).

## 3. TUI smoke (aethercode-cli) — fail-fast verified

The shaded jar is runnable, the new flags parse, and the SDK builds cleanly.
Specifically verified:

- `aethercode --version` → `aethercode 0.1.0`
- `aethercode --help` → shows `--provider`, `--api-key`, `--base-url`, `--model`
- `aethercode --provider minimax --print "hi"` with no API key → fails fast at
  `MinimaxChatClient` construction with the clear error
  `MiniMax API key is required. Set MINIMAX_API_KEY or pass --api-key to the CLI.`
  (exit code 1, no 120s hang)

Real-key smoke is pending the user-provided `MINIMAX_API_KEY`. With a key set:

```bash
MINIMAX_API_KEY=... java -jar aethercode-cli-...-shaded.jar --provider minimax --print "say hi"
```

should hit `https://api.minimaxi.com/v1/chat/completions` with
`Authorization: Bearer ...` and stream a response. To be confirmed in this
session if the user provides a key.

## 4. PluginLoader (aethercode-tools/plugin) — 12 tests

Discover and load third-party plugins from a directory tree. Default layout:

```
.aethercode/
  plugins/
    hello/
      plugin.json         # required
      HelloPlugin.class   # optional, alongside
      lib/                # optional, *.jar
```

`plugin.json` schema is unchanged from R13-7 (`PluginManifest`):

```json
{
  "name": "hello",
  "version": "1.0.0",
  "entryPoint": "com.example.HelloPlugin",
  "description": "...",
  "tools": ["..."],
  "dependencies": ["..."],
  "permissions": ["..."]
}
```

Loading sequence per plugin:

1. Resolve manifest path: prefer `plugin.json`, fall back to
   `.aethercode-plugin.json`
2. Parse + validate (`PluginManifest.isValid`)
3. Build a `URLClassLoader` rooted at the plugin dir + `lib/*.jar` (parent is
   `PluginLoader`'s own classloader so plugins can see `Plugin` and other host
   types)
4. `Class.forName(entryPoint, true, cl)` + check `Plugin.class.isAssignableFrom`
5. `klass.getDeclaredConstructor().newInstance()`
6. `instance.init(PluginContext)` — passes the manifest, the plugin dir, a
   per-plugin SLF4J logger, and the host's services map
7. Register in the loader's `loaded` list

`shutdownAll()` is idempotent and tears down in reverse-load order, closing
classloaders. Per-plugin init failure does not block other plugins — the loader
logs the issue and moves on. Tests cover: valid load + lifecycle, missing
manifest, invalid manifest, malformed JSON, entry point that doesn't implement
`Plugin`, sort order, missing root, missing entry-point class, classloader
field, services map propagation, and idempotent shutdown.

## 5. IDEA plugin — provider-aware engine + settings (idea-plugin)

`AetherCodeEngineHolder` is now provider-aware:

- Reads `AetherCodeSettings.getInstance(project).provider`
- Reads `effectiveApiKey()` from the settings (falls back to the provider's
  env-var name when the settings apiKey is blank)
- Picks a provider-default model (`MiniMax-M3` for minimax,
  `claude-sonnet-4-5` for anthropic)
- Caches the engine and invalidates the cache when the provider/key changes —
  switching providers in the settings panel triggers a rebuild on the next
  panel access

`AetherCodeSettings` is a `PersistentStateComponent<State>` scoped to the
project. State is persisted under `AetherCodeSettings.xml` in the project's
`.idea/` folder:

- `provider` — `anthropic` | `minimax`
- `apiKey` — overrides the env-var lookup
- `modelOverride` — empty means "use provider default"
- `customBaseUrl` — advanced override

The IDEA chat panel (`AetherCodeChatPanel`) is unchanged — it goes through
`AetherCodeEngineHolder.getEngine(project)` and so transparently picks up the
new provider plumbing.

The IDEA plugin module is a separate Gradle sub-project; no `gradlew` is
checked in, so the Kotlin compile is verified by inspection against the
standard IntelliJ `PersistentStateComponent` / `Service` pattern. The user
can `./gradlew :idea-plugin:buildPlugin` in their own environment to produce
the final `.zip` for IDE install.

## R14-1 key pitfalls (recap)

1. **System prompt is a `messages[]` entry in OpenAI, not a top-level field.**
   In the Anthropic client the system prompt is `root.put("system", ...)`; for
   OpenAI it becomes a `role:"system"` message prepended to the array.
2. **Tool results are N separate `role:"tool"` messages**, not a single user
   message with N content blocks. The internal model uses Anthropic's compact
   form; the wire format is one-tool-per-message.
3. **`flushToolCalls` must only emit events, not assemble `finalBlocks`.**
   The early flush (on `finish_reason`) and the late flush (in `finishAndPoison`)
   both need to coexist without producing duplicate blocks. The fix is: emit
   events early, but always assemble in `finishAndPoison` from `currentText` +
   the tool-call builders in their natural order.
4. **`[DONE]` and `onClosed` both trigger `finishAndPoison`.** A `done` boolean
   guards the second call so `usageListener` and the `RunEnd` are emitted
   exactly once per response.
5. **`URLClassLoader` with `null` parent can't see host types.** The plugin
   loader uses `PluginLoader.class.getClassLoader()` as the parent so plugins
   can see `Plugin`, `Logger`, etc.
6. **Test plugin fields need to be `public` for `getField` to find them.**
   `static String` is package-private; reflection across packages requires
   `public static`. Use `getDeclaredField` + `setAccessible` if you want to
   keep them package-private.

## Test progression

| Round | Tests | Δ | Status |
|------:|------:|---:|--------|
| R5    |    80 |  +15 | Production readiness |
| R6    |   147 |  +67 | Usability layer |
| R7    |   234 |  +87 | Cost, quality, lifecycle |
| R8    |   351 | +117 | SDK and polish |
| R9    |   497 | +146 | Developer experience |
| R10   |   670 | +173 | Integration and polish |
| R11   |   843 | +173 | Misc utilities |
| R12   |   999 | +156 | Infrastructure |
| R13   |  1172 | +173 | Final polish |
| **R14** | **1216** | **+44** | **MiniMax + plugins** |

## R14 deliverables by module

| Module | New files (main) | New files (test) | Tests |
|--------|------------------|------------------|------:|
| aethercode-llm | MinimaxChatClient, MinimaxModels, ChatClientFactory | MinimaxChatClientTest (18), MinimaxModelsTest (4), ChatClientFactoryTest (10) | 32 |
| aethercode-cli | (CLI flag additions to Main.java) | — | 0 |
| aethercode-sdk | (Builder.provider() addition) | — | 0 |
| aethercode-tools | Plugin, PluginLoader | PluginLoaderTest (12) | 12 |
| idea-plugin (Kotlin) | AetherCodeSettings, AetherCodeEngineHolder (updated) | — | 0 |
| **R14 totals** | **6 main + 1 updated** | **3 tests** | **+44** |

## What's NOT in R14 (carry-forward)

- Real-key smoke test against the live MiniMax endpoint (needs user-supplied
  `MINIMAX_API_KEY`; can be done in the same session on request)
- IDEA plugin `Settings` UI panel — the `AetherCodeSettings` storage is in
  place, but the Swing form to edit it is not yet built
- Gradle wrapper (`./gradlew`) for the `idea-plugin` sub-project so it can be
  built without a system Gradle install
- A reference plugin project showing how a third-party developer would
  structure a plugin (manifest, entry point, build, lib jars)

## Sign-off

R14 is the first post-port round. The system can now talk to both Anthropic
and MiniMax from a single engine, the TUI passes `--print` smoke (fail-fast
path verified; real-key smoke pending), the IDEA plugin's chat panel goes
through the same provider plumbing, and a real third-party plugin system
finally has a loader that can instantiate extension classes.
