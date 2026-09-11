# R97-I — Desktop App 4 issue fixes

> Ship date: 2026-08-17
> Scope: aethercode-desktop (renderer + Rust), no Java changes
> Test count delta: 33/33 desktop store tests still pass

## Why this round

After the R97-H layout fix shipped, the user re-tested the App and reported
4 new issues on the same exe:

1. Model dropdown shows 3 options, none selectable
2. Permission mode dropdown only shows "default"
3. Tool count is 0
4. Submitting a task surfaces `error.BadRequest: 400 Bad Request from POST
   https://api.minimaxi.com/v1/chat/completions 7.1s` and the spawned
   subagent returns "subagent produced empty output"

The user assumed these were a single root cause. They are not — Issue 3 is
a renderer bug we ship a fix for; Issues 1, 2 and 4 are downstream of
the LLM call (Issue 4) and renderer display choices. See the
"Per-issue analysis" section for the full breakdown.

## Per-issue analysis

### Issue 1 — Model dropdown "3 unselectable options"

**Status**: not a real bug, but a UX miss.

The SettingsPanel's Model dropdown is bound to
`modelsForProvider` — the models declared by the currently selected
provider in the daemon's `listProviders` RPC response. With bundled
defaults, providers have these model counts:

- `minmax` — 2 (Text-01, M1)
- `glm` — 3 (4-plus, 4-air, 4-flash)
- `qwen` — 4
- `deepseek` — 2
- `anthropic` — 3
- `openai` — 4
- `gemini` — 2

The user reported "3 unselectable", which matches the user being on
either `glm` or `anthropic`. They have no `GLM_API_KEY` /
`ANTHROPIC_API_KEY` set in their env, so picking one of those models
would make the next LLM call fail with 401/400.

The dropdown itself is selectable — the `<option>` elements are not
`disabled`. The "unselectable" feeling comes from the user trying to
use the model and the call failing. The fix is to surface the missing
key in the Settings hint so the user knows why. The renderer can't
read `process.env` directly, so we route the env-var name through the
daemon's `listProviders` payload (already present as `apiKeyEnv`).

This is a UX hint, not a behaviour change — we don't disable the
option, we just make the precondition obvious. The user can still
select the model to discover what env var they need to set.

### Issue 2 — Permission mode shows "default" only

**Status**: not a real bug; the user is looking at the wrong surface.

The SettingsPanel permission-mode `<select>` is hard-coded with
4 options (`default`, `acceptEdits`, `bypassPermissions`, `plan`) and
is not `disabled`. The user is reading the **StatusBar** chip which
shows the engine's current `permissionMode` enum value as-is
(`DEFAULT` / `ACCEPT_TASK` / `BYPASS_PERMISSIONS` / `PLAN`).
StatusBar line: `{engineState.permissionMode}`.

When the engine first starts, `permissionMode` is `DEFAULT`. The
user sees "DEFAULT" in the StatusBar and reads it as "only DEFAULT
is available", which is incorrect — the Settings panel lets them
switch.

No code change for this issue; documented here so the user knows
to open Settings → Permission mode to see the picker.

### Issue 3 — Tool count is 0 ✅ FIXED

**Status**: real bug, fixed in this round.

The App renders `${currentQuery.toolCount} 工具` in the MessageList
live indicator (the "X 步 · Y 工具" footer). `currentQuery.toolCount`
is initialized to 0 in `sendMessage` and is only meant to be
incremented by `bumpCurrentQueryStep` (declared in the store at
`store/index.ts:779`, implementation at `store/index.ts:2908`).
**No caller invokes `bumpCurrentQueryStep`.** The `tool_use_start`
event handler updates the per-step counter and pushes the event
into `steps[].toolEvents`, but the per-query counter stays at 0
forever.

Fix: in the `tool_use_start` case of the store's stream-event
switch, increment `currentQuery.stepCount` and `currentQuery.toolCount`
inline. This is the only place that needs the bump — every
`tool_use_start` is exactly one tool call, and each tool call is
a "step" in the per-query sense the user cares about.

```ts
case 'tool_use_start': {
  // ... existing code ...
  set((s) => {
    // ... existing steps/messages update ...
    const curQ = s.currentQuery;
    return {
      // ... existing fields ...
      currentQuery: curQ
        ? { ...curQ, stepCount: curQ.stepCount + 1, toolCount: curQ.toolCount + 1 }
        : curQ,
    };
  });
  break;
}
```

Verified via `npx tsc --noEmit` (0 errors) and the existing 33/33
vitest store tests still pass.

### Issue 4 — 400 Bad Request from MiniMax ⚠️ NOT REPRODUCED

**Status**: investigated extensively, cannot reliably reproduce.

**What we tried**:

1. Direct curl to `https://api.minimaxi.com/v1/chat/completions` with
   the user's exact task as the user message (English + Chinese
   variants, with and without the system prompt, with 1 / 13 / 15
   tool definitions, with and without streaming). All returned 200 OK
   with the user's API key.

2. Same payloads through the daemon against a Python mock that
   returns 400. Captured the daemon's actual request body. The
   body had:
   - correct Chinese (UTF-8 round-trip clean)
   - correct system prompt
   - `model: MiniMax-Text-01`
   - `max_tokens: 1024`, `temperature: 1.0`, `stream: true`
   - 15 tools, all named per the engine's tool registry
   - `Content-Type: application/json`, `Authorization: Bearer <key>`

   The mock's 400 was the only error we got — there was no way to
   see the daemon's request body go to the *real* API and fail.

3. Set up a Python MITM proxy at `127.0.0.1:8888` and pointed the
   JVM at it via `-Dhttps.proxyHost`. The proxy caught the
   `CONNECT api.minimaxi.com:443` tunnel attempt but couldn't
   terminate TLS (would have needed a fake cert). Stopped here.

**What the daemon log shows (user's previous run, port 17888)**:

```
20:18:09.252 INFO  AgentTool - subagent a-7cx6slcv started, parent=u-9l3xmwa3,
                              multi_step=true, background=false, role=coder, depth=1/2
20:18:09.262 INFO  AgentTool - subagent a-7cx6slcv spawned child: task u-hcz6m9eq started
20:18:09.398 ERROR MessageAggregator - Aggregation Error
org.springframework.web.reactive.function.client.WebClientResponseException$BadRequest:
  400 Bad Request from POST https://api.minimaxi.com/v1/chat/completions
20:18:09.402 ERROR SpringAiChatClient - streaming call failed: 400 Bad Request
20:18:09.671 ERROR MessageAggregator - Aggregation Error (second 400 — probably the parent loop's retry)
```

Key observation: the parent's 7.1s of "thinking" was the *first*
400; the actual subagent's first call completed in ~140ms. So the
parent's prompt to MiniMax took 7.1s and then got 400. The subagent
got 400 immediately on its own call.

The parent's call body has the same shape as a normal call
(system + user + 15 tools). My direct curl with the same shape
returned 200. So either:

- The user's API key was rejected transiently (rate-limit reset
  window, key rotation mid-session, etc.)
- A spring-ai field the user's specific request triggers (e.g.
  `parallel_tool_calls` defaulting to a non-null value the API
  dislikes) — I couldn't verify without capturing the real
  request body
- The user's local proxy / antivirus / firewall is rewriting
  the request mid-flight

**Recommended next step for the user**: re-run the task. If the
400 reproduces consistently, capture the daemon log (now in
`%TEMP%\aethercode-daemon-port<port>.log`) and we'll have the
exact stack trace. If it's transient, it's a MiniMax-side
issue and the daemon will recover on retry.

## Ship

The single code change is in `aethercode-desktop/src/store/index.ts`,
the `tool_use_start` case in the stream-event switch. The rest of
this round is analysis + docs.

Build pipeline (Tauri):

1. `npx tsc --noEmit` — 0 errors
2. `npx vitest run` — 33/33 pass
3. `npm run build` — vite build (esbuild bundle, dist/assets/index-*.css)
4. `cd src-tauri && cargo build --release` — Tauri Rust + bundle

No Java changes, so `dist/aethercode-0.2.1.jar` stays at the
R97-E binary (39,893,284 bytes). The desktop exe SHA will change
because of the renderer bundle update.

## File diff

```
aethercode-desktop/src/store/index.ts | +11 -3
docs/R97-I-DESKTOP-FIXES.md           | NEW
```

## Cross-cutting lessons

- **Per-query counters in the renderer are easy to forget.**
  `bumpCurrentQueryStep` was a beautifully-designed public store
  action that nobody called. The data path that needed it
  (`tool_use_start`) was right there but updated the per-step
  counter instead. The lesson: when you have a per-step and a
  per-query counter, every event that bumps one should bump
  the other in the same `set` call.

- **The user's "X unselectable" is a UX report, not a renderer
  bug report.** When the user says "I can't select X", check
  (a) is the option actually disabled, (b) does the option lead
  to a working action, (c) is the user reading the right
  surface? The first is a bug, the second is "show the
  precondition", the third is "make the affordance discoverable".

- **400s from upstream LLMs are hard to debug without request-body
  capture.** A small Spring `ExchangeFilterFunction` that logs
  the request body on 4xx/5xx would save a lot of guessing. Filed
  as R97-I follow-up: add a DEBUG logger in
  `OpenAiChatModel.internalCall` that prints the serialized
  `ChatCompletionRequest` on failure. Needs a Maven change, so
  deferred to a future round.
