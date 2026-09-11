# R114 — User-Reported 3-Fix Round (2026-08-19)

## What the user reported (post-R113 ship)

After R113 shipped (model dropdown + session defensive rendering), the
user opened a fresh project and reported three independent symptoms:

1. **"上面 tools 显示为空"** — the Tools button / 🔧 ToolsPanel was
   empty.
2. **"workflow 也没得选"** — the workflow picker had nothing to select.
3. **"loop 还会中途结束,没有将整个任务执行完"** — a run terminated
   mid-task with the engine reporting a loop stop.

## Root cause analysis (desktop state, port 17888)

A direct probe of the user's running daemon
(`ws://127.0.0.1:17888/ws`) showed:

| Field | Value | Implication |
|---|---|---|
| `listTools` | 15 tools | Backend OK — issue is render-side |
| `listWorkflows` | 0 workflows (cwd `D:\tmp\abc_1` had no `.aethercode/workflows/`) | Real empty state |
| `listToolActions` | 15 actions | Backend OK |
| `loopStops` (engine metrics) | 1 | Loop detector fired on the user's most recent query |
| `cwd` | `D:\tmp\abc_1` | First-launch project, no workflow YAMLs yet |

The three issues broke down as:

* **Issue 1** — the renderer's `tools` field was a one-shot snapshot
  taken inside `initialize()`. If the round-trip failed, there was no
  recovery path: the Tools button was `disabled={tools.length === 0}`
  and the ToolsPanel rendered "No tools registered". The StatusBar hid
  the `X tools` badge when `tools.length === 0`. There was no manual
  refresh.
* **Issue 2** — a fresh project has no `.aethercode/workflows/` and
  no UI affordance to bootstrap one. The picker just showed the
  passive "没有可用 workflow" message.
* **Issue 3** — the `LoopGuardBanner` auto-dismissed after 8 s. The
  user's typical pattern (read the transcript after a warn) outlasted
  the 8 s window, so the banner was gone by the time they looked up.
  The engine then escalated to the next tier and stopped the run.

## Design (3 rounds, approved by user)

### R114-A — Tools refresh on demand

* New `refreshTools()` store action: `listTools` + `listToolActions`
  in parallel, with a per-RPC `.catch` that returns `null` instead
  of throwing. The store patches only the fields that got a
  non-null response, so a transient blip on one RPC doesn't blank
  the other.
* New `toolsRefreshedAt` field on `AppState`. The ToolsPanel
  renders "最后刷新: 5s 前" so the user can see staleness.
* `MessageInput`'s Tools button no longer has the
  `disabled={tools.length === 0}` gate — the empty case is
  reachable, and the popup shows a ↻ button.
* `ToolsPanel` header adds a ↻ button with a 1.2 s spin animation
  during the round-trip. The empty state copy changed from
  "daemon is starting up…" to "正在尝试拉取…如果长时间为空,点 ↻ 重试".
* New periodic timer (30 s, like `skipStatsTimer`'s 5 s) wired into
  `initialize()`. Self-heals daemon-restart scenarios.
* Auto-trigger when the popup / panel opens with empty data, so
  the first-launch case recovers without the user clicking ↻.

### R114-B — Starter workflows + one-click import

* New `src/components/workflowExamples.ts` with 3 inline YAML
  examples covering the main step types (shell / agent / gate):
  * `hello-shell` — 1 step, a `shell` echo. Trivial sanity check.
  * `file-summary` — 3 steps, read a file → summarise → gotchas.
  * `safe-commit` — 3 steps, `git status` + `git diff` + a `gate`
    that only commits when there's a diff.
* New `importExampleWorkflows(writeWorkflow)` helper: serial
  `writeWorkflow` calls, per-example try/catch, returns
  `{ imported: string[]; failed: { name; reason }[] }` so the UI
  can show a partial-success pill.
* `MessageInput`'s workflow picker empty state now has a
  "📥 导入示例 (3 个)" button. The button calls the helper and
  refreshes the cached list. The result pill shows
  `✅ 全部成功` / `⚠️ 部分成功` / `❌ 全部失败` with per-failure
  reasons.
* The workflow button's `disabled` gate is also removed — the user
  must be able to open the picker to see the import button.

### R114-C — Persistent loop banner + Ctrl+L hotkey + StatusBar badge

* `AUTO_DISMISS_MS` changed from `8_000` to `0` (disabled). The
  banner stays put until the user clicks 继续 or 停止.
* New `Ctrl+L` / `Cmd+L` keydown listener mounted only while a warn
  is active. The handler calls `acknowledgeLoop(loopWarn.kind)` and
  is gated on `closingRef` so a double-tap doesn't fire two RPCs.
* Hint text updated to point at the new hotkey: "持续显示,直到你点
  「继续」或「停止」。继续: <kbd>Ctrl</kbd>+<kbd>L</kbd>".
* New StatusBar badge: `⚠ loop 1/2` (tier 1, amber) or
  `⚠ loop 2/2` (tier 2, red + pulse). The badge is a button that
  scrolls the LoopGuardBanner into view via the new
  `id="loop-guard-banner"` on its root element.

## Files changed (R114)

* `aethercode-desktop/src/store/index.ts` — `refreshTools` action,
  `toolsRefreshedAt` field, periodic timer
* `aethercode-desktop/src/components/MessageInput.tsx` — Tools
  popup ↻ + empty state; workflow picker 导入示例 button
* `aethercode-desktop/src/components/MessageInput.css` — popup
  header, refresh button, import button + result pill
* `aethercode-desktop/src/components/ToolsPanel.tsx` — header ↻,
  last-refresh meta, auto-refresh on mount
* `aethercode-desktop/src/components/ToolsPanel.css` — refresh
  button + spin animation, meta line, empty-state block
* `aethercode-desktop/src/components/LoopGuardBanner.tsx` —
  `AUTO_DISMISS_MS = 0`, Ctrl+L hotkey, `id="loop-guard-banner"`,
  updated hint text
* `aethercode-desktop/src/components/LoopGuardBanner.css` — kbd
  styling for the Ctrl+L hint
* `aethercode-desktop/src/components/StatusBar.tsx` — `loopWarn`
  pulled from store, ⚠ loop N/2 badge
* `aethercode-desktop/src/components/StatusBar.css` —
  `status-loop-warn` + tier-1 / tier-2 colour ramps
* `aethercode-desktop/src/components/workflowExamples.ts` (NEW) —
  3 inline YAMLs + `importExampleWorkflows` helper
* `aethercode-desktop/src/components/ToolsPanelR114.test.ts` (NEW)
* `aethercode-desktop/src/components/workflowExamplesR114.test.ts` (NEW)
* `aethercode-desktop/src/components/LoopGuardBannerR114.test.ts` (NEW)

## Test counts (R114)

| Round | New tests | Cumulative |
|---|---|---|
| R86-R113 (baseline) | — | 89 vitest |
| R114-A | +22 | 111 |
| R114-B | +19 | 130 |
| R114-C | +16 | **146** |

TypeScript typecheck: ✓
Java backend tests: not re-run (no Java code touched in R114)

## Cross-cutting design lessons (R114)

1. **One-shot snapshots in `initialize()` are a recovery trap.**
   Pre-R114-A, the `tools` field was set exactly once. Any
   subsequent failure (daemon restart, WS blip, RPC rejection)
   left the store permanently out-of-date with no way to recover.
   The fix — a `refreshX()` action + periodic timer + lazy
   trigger on UI open — is the standard pattern. Future round
   candidates: `refreshEngineState()`, `refreshSessions()`,
   `refreshTasks()` (the latter two already exist; the first
   doesn't).
2. **Auto-dismiss is user-hostile for state-changing prompts.**
   The 8s auto-dismiss on the loop banner was technically
   "non-blocking" but functionally invisible — by the time the
   user looked up, the decision had been made for them. The new
   pattern (persistent banner + explicit hotkey) puts the user
   in control. Future candidates: any other auto-dismissed UI
   affordance (the status-toast, the subagent terminal toast,
   the "permission was auto-allowed" notification).
3. **Status bar is the always-visible surface for state
   transitions.** A loop warning is exactly the kind of thing
   the user might miss while focused on the transcript. Putting
   a clickable badge in the StatusBar means the user sees the
   problem even if they never look at the banner.
4. **Inline starter data beats bundled resources for small
   payloads.** The 3 workflow examples total < 4 KB; bundling
   them inline in a `.ts` file avoids an IPC hop and a
   `tauri.conf.json` resource entry. The same pattern is used
   for `workflowCreateStub()` and the slash-command templates
   in `commandCommands.ts`.
5. **Per-example failure tracking beats a single boolean.**
   `writeWorkflow` can fail for a single example (e.g. disk
   full, permission denied) without the others failing too.
   The `ImportResult` shape (`{ imported, failed }`) lets the
   UI show partial-success — a single `ok: boolean` would
   either mask the partial success (always false) or be too
   coarse (only one of three succeeded is a different signal
   than none succeeded).

## R114+ follow-up candidates

* **R115**: setLoopDetectorThresholds RPC + Settings slider.
  The user picked "持久 banner + 快捷键" for the loop UX but
  a future round could let power users widen the window /
  raise the threshold from the Settings panel without
  touching the engine CLI.
* **R116**: a "Last 10 RPC responses" diagnostic panel.
  The R114-A investigation relied on direct `ws` probes; an
  in-app panel would let the user self-diagnose the next
  "tools 显示为空" without external help. Candidate wires:
  `store/index.ts` already logs RPC errors via
  `console.warn`; piping them into a `recentRpcEvents: []`
  array would be a small change.
* **R117**: pre-fill the workflow picker with the user's most
  recently used workflow. Today the active selection is
  cleared on send (R102 design); a "recently used" list
  (LRU of last 5) would let the user repeat a flow without
  re-picking it from the popup.
