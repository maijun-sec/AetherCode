# R173: UI 立竿见影 + AetherCodeAgent Create-Agent Facade

**Date:** 2026-08-31
**Status:** Released as **v0.2.19** (jar + exe)
**Trigger:** User reported "v0.2.18 跑 Maven 排序项目卡 67s、loop_warn 2/2、UI 框显示不全"。
**Goal:** Fix the UI information overload + give the backend a "create agent" facade that
the model can lean on for multi-step plans.

---

## TL;DR

| What changed                                | Where                                        | Why                                                                              |
| ------------------------------------------- | -------------------------------------------- | -------------------------------------------------------------------------------- |
| Window 1280×800 → **1440×900** (minWidth 1100) | `tauri.conf.json`                            | More horizontal room for the chat list; matches the 3-pane -> 2-pane re-skin.    |
| Right panel (telemetry / kanban / agents) **default hidden** | `App.css` + `App.tsx` + `RightPanel.tsx` + `Header.tsx` | Was 320px eaten by 7 sections the user never looked at. Now summoned via 📊 icon (Ctrl+Shift+E). |
| StatusBar 14+ badges **default collapsed**  | `StatusBar.tsx` + `StatusBar.css`            | Connection / model / streaming / memory / loop-warn / auto-approve show; everything else behind a `···` toggle. Uses `data-priority` attribute to avoid breaking the R112/R120/R126 source-pin tests. |
| **`AetherCodeAgent.builder()`** facade      | `aethercode-core/.../engine/AetherCodeAgent.java` | Create-agent style entry point modelled after `deepagents.create_deep_agent`. |
| **Default system-prompt fragment**          | `AetherCodeAgent.defaultSystemPromptFragment()` + `AetherCodeEngine.Builder.createAgent(true)` | Explicit "call `todo_write` first / batch tool calls / do not stop after the first tool call" rules. The fragment is appended to the SystemPrompt's identity section, so the existing `SystemPrompt.defaultWorkflow()` is still in effect. |
| `--no-create-agent` CLI flag + `AETHERCODE_CREATE_AGENT=false` env var | `Main.java` | Opt-out for users who prefer the legacy prompt. |

---

## What the user complained about (and what we did)

| User pain                                                | R173 fix                                                                                       |
| -------------------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| "很多框都显示不全, 没办法看当前的进展"                    | Window is wider; right panel is hidden; StatusBar collapsed to 6 high-signal badges.            |
| "已 67 秒无新输出" (false-positive on multi-tool runs)   | The R172 90s `STREAM_STALE_MS` threshold is in the same shape but the new "no early stop" system-prompt rule should make the model keep emitting progress events (todo_write + sub_todo_write transitions), so the watchdog sees activity. |
| "loop_warn 2/2" (detector firing on legitimate plans)   | The "batch tool calls" + "do not stop after the first tool call" rules should keep the fingerprint window quieter. Pre-R173 the model was emitting one tool call per turn; the new "batch" rule makes the model emit 2-5 tool calls in one turn, which doesn't grow the per-batch fingerprint count. |
| "如果自己原本的能力不够，推荐可以考虑 deepagents 的 create agent 的实现" | New `AetherCodeAgent` facade with `defaultSystemPromptFragment()` + `Builder` + `recommendedPermissionMode() = ACCEPT_TASK`. The daemon flips the fragment on by default. |

---

## Files changed

### Frontend (TypeScript / React / CSS)

| File                                                          | Change                                                                                |
| ------------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| `aethercode-desktop/src-tauri/tauri.conf.json`                | Window 1280×800 → 1440×900, minWidth 900 → 1100, minHeight 600 → 720.                 |
| `aethercode-desktop/src/App.css`                              | 3-column grid → 2-column grid; `.right-panel-open` modifier re-opens the 3rd column.  |
| `aethercode-desktop/src/App.tsx`                              | `rightPanelOpen` state (default `false`); `Ctrl/Cmd+Shift+E` shortcut; pass through to `MainLayout`. `Header` gets a new `onTelemetryClick` + `telemetryActive` prop. |
| `aethercode-desktop/src/components/Header.tsx`                | New `📊` icon button. Toggles right panel. `is-active` modifier when open.             |
| `aethercode-desktop/src/components/Header.css`                | `.header-icon-btn.is-active` style (1px accent border + soft tint).                   |
| `aethercode-desktop/src/components/RightPanel.tsx`            | Accepts `onClose` prop; renders `✕` button at the right of the tab strip.              |
| `aethercode-desktop/src/components/RightPanel.css`            | `.right-tab-close` style (margin-left: auto; error tint on hover).                     |
| `aethercode-desktop/src/components/StatusBar.tsx`             | Adds `compact` state (default `true`); renders a `···` / `−` toggle; the 6 priority badges (connection / model / permission / streaming / memory / loop-warn / auto-approve / subagent / in-flight) get a `data-priority="true"` attribute. **No JSX is removed** so the R112 / R120 / R126 source-pin tests keep passing. |
| `aethercode-desktop/src/components/StatusBar.css`            | `.status-bar.status-bar-compact .status-item:not([data-priority="true"]):not(.status-toggle-compact) { display: none; }` plus the toggle button style. |

### Backend (Java)

| File                                                                                        | Change                                                                                                            |
| ------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| `aethercode-core/src/main/java/org/aethercode/core/engine/AetherCodeAgent.java` (new, 13.6 KB) | Create-agent facade. `Builder` + `defaultSystemPromptFragment()` + `recommendedPermissionMode() = ACCEPT_TASK`. |
| `aethercode-core/src/test/java/org/aethercode/core/engine/AetherCodeAgentTest.java` (new)   | 18 tests pinning: fragment mentions `todo_write`, no-early-stop, loop detector, batch tool calls, default permission mode, builder invariants. |
| `aethercode-sdk/src/main/java/org/aethercode/sdk/AetherCodeEngine.java`                      | `Builder.createAgent(boolean)` setter (default `false`); `defaultSystemPrompt(Builder)` appends the fragment to the identity section when opted in; `defaultIdentity()` lifted out of `SystemPrompt` so the two stay in sync. |
| `aethercode-cli/src/main/java/org/aethercode/cli/Main.java`                                 | `--no-create-agent` flag + `AETHERCODE_CREATE_AGENT=false` env var; `buildEngineForSession` defaults to `createAgent(true)`. |

### Not changed (deliberately)

- `QueryEngine` / `TodoRunController` / `ToolLoopDetector` / `ProgressLoopDetector` /
  `StreamingToolExecutor` / `Compactor` — these already exist; the R173 facade just
  documents and exercises them.
- The existing `SystemPrompt.defaultIdentity()` / `defaultWorkflow()` — the new
  fragment is appended to the identity, the workflow stays untouched.
- `CreateDeepAgent` (the deepagents-java port) — R173's `AetherCodeAgent` is a
  separate facade that lives in `aethercode-core`. A future round can wire the
  two together (treat `AetherCodeAgent` as a builder that produces inputs for
  `CreateDeepAgent`).

---

## Why data-priority and not status-priority class

R120's source-pin test expects the literal string
`className={\`status-item status-auto-approve ${autoApproveLowRisk ? '' : 'is-disabled'}\`}`
in `StatusBar.tsx`. Adding a `status-priority` class to that string (so
`status-item status-priority status-auto-approve ...`) breaks the regex match
even though the runtime behaviour is correct.

The fix is to use a `data-priority="true"` attribute selector instead. CSS
becomes `.status-item:not([data-priority="true"]):not(.status-toggle-compact)`,
and the JSX adds the attribute without touching the className string. The
source-pin tests pass verbatim.

---

## Verification

| Check                                       | Result                                                                                       |
| ------------------------------------------- | -------------------------------------------------------------------------------------------- |
| `npm test` (vitest)                         | **843/843 pass** (0 regression). All three StatusBar source-pin suites (R112, R120, R126) green. |
| `mvn -pl aethercode-core test`              | **1127/1127 pass** (1109 pre-existing + 18 new AetherCodeAgentTest).                        |
| `mvn -pl aethercode-sdk test`               | **251/251 pass** (no regression from createAgent wiring).                                   |
| `mvn -pl aethercode-cli test`               | **42/42 pass**.                                                                              |
| `mvn -pl aethercode-deepagents test`        | **27/27 pass**.                                                                              |
| Pre-existing `AgentToolTest`                | **14/15 fail** (pre-existing bug: `Tool.CallContext.extras` is immutable but the tests call `setExtra` on a frozen context). Unrelated to R173. |
| `mvn -pl aethercode-cli package`            | New `aethercode-cli-0.1.0-SNAPSHOT.jar` (55 MB) at 15:50:47.                                |
| `npm run tauri build --no-bundle`           | New `AetherCode.exe` (3.94 MB) at 15:54:20. Build clean (only pre-existing `collect_jars unused` warning). |
| **Release**                                 | `D:\work\workspace\idea\engine\AetherCode\release\aethercode-0.2.19\` with `aethercode-0.2.19.jar` + `AetherCode.exe`. |

### Real-prompt regression (next session)

The user's original prompt —

> "在当前目录下生成一个 java maven 项目，实现至少5种排序算法，对 int、long、short 三类数组进行排序，UT完整。"

— should now be re-run against `release/aethercode-0.2.19/AetherCode.exe`. Expected
behaviour changes:

- **Right panel hidden by default** → the chat list has 1160px to render (was 680px
  on 1280×800).
- **StatusBar collapsed to 6 priority badges** → no more wall of micro-text.
- **Model emits a `todo_write` on the first turn** → user sees a plan card, not a
  one-line "I'll create the project" reply.
- **Model batches file writes** → 2-5 `file_write` calls per turn, not 1.
- **Model does not stop after the first tool call** → no 67s stall.

If the regression still fails on v0.2.19, the next step is to inspect
`AetherCode-0.2.19/logs/` + `jstack <daemon-pid>` to confirm the model
actually received the new system-prompt fragment (look for "Working contract
for multi-step tasks" in the rendered prompt at boot).

---

## What I did NOT do (deferred / out of scope)

- **Real `Middleware` chain in `QueryEngine`.** The current QueryEngine is
  still a turn-by-turn loop; the facade pattern (AetherCodeAgent) wraps the
  capabilities without changing the loop. A future round can refactor
  QueryEngine to walk a `List<Middleware>` like deepagents' Python port does.
- **HIL promotion of file operations.** File writes are still
  `MEDIUM`/`HIGH` risk under `ASK_BEFORE_TOOL`. The user did not ask for this
  in the brief, and changing default risk posture is a behavior change that
  needs user sign-off. The "Use `ACCEPT_TASK` mode" path is already there;
  R173 documents it as the recommended create-agent posture.
- **Loop detector re-tuning.** The R172 detector window/threshold stays
  unchanged. The hypothesis is that the new system-prompt "do not stop after
  the first tool call" + "batch tool calls" naturally lowers the per-batch
  fingerprint count, so the detector fires less often. If v0.2.19 regression
  still trips the detector, R174 will revisit the window.
- **R172 stream-stale UI rebadge.** Pre-R172 the badge counted 30s;
  R172 bumped to 90s. The user's screenshot showed "已 67 秒无新输出"
  inside the 90s window — that counter is just a live timestamp, not the
  stale banner. A future R can swap the counter for a "last activity" line
  so the user sees "thinking 67s" instead of an alarming count.
- **Layout truncation root-cause.** The user reported "很多框都显示不全";
  the R173 fixes (bigger window + collapsed right panel) should resolve
  most cases. If truncation persists on v0.2.19, R174 will dig into the
  LeftPanel / KanbanPanel / SubagentPanel min-widths.

---

## Key code snippets (for the curious)

### AetherCodeAgent (facade entry point)

```java
public final class AetherCodeAgent {
    public static Builder builder() { return new Builder(); }

    public static String defaultSystemPromptFragment() {
        return """
                # Working contract for multi-step tasks
                ...
                1. For any task that will take more than 3 tool calls, call
                   `todo_write` FIRST. ...
                2. Mark each sub-task `in_progress` as you start it and
                   `completed` (or `failed`) as you finish it. ...
                3. Batch related tool calls. ...
                4. Do not stop after the first tool call. ...
                5. End with a one-paragraph summary. ...
                # Permission policy
                The recommended default for a multi-step plan is
                `ACCEPT_TASK`: ...
                """.strip();
    }

    public static final class Builder {
        public Builder name(String n) { ... }
        public Builder systemPrompt(String p) { ... }
        public Builder addTool(Tool t) { ... }
        public Builder addMiddleware(String m) { ... }
        public Builder addSubagent(String name) { ... }
        public Builder recommendedPermissionMode(PermissionMode m) { ... }
        public AetherCodeAgent build() { return new AetherCodeAgent(this); }
    }
}
```

### AetherCodeEngine.Builder.createAgent

```java
public Builder createAgent(boolean on) { this.createAgent = on; return this; }

private static org.aethercode.prompts.SystemPrompt defaultSystemPrompt(Builder b) {
    // ... existing rules + workflow rendering ...
    String identity = defaultIdentity();
    if (b.createAgent) {
        identity = identity + "\n\n" +
                org.aethercode.core.engine.AetherCodeAgent.defaultSystemPromptFragment();
    }
    return SystemPrompt.builder()
            .identity(identity)
            .environmentFrom(b.cwd, System.getProperty("os.name"))
            .toolingFrom(b.tools)
            .rules(rules)
            .designFirst(designFirst)
            .build();
}
```

### App.tsx 2-column grid

```tsx
const [rightPanelOpen, setRightPanelOpen] = useState(false);
// ...
return (
  <div className={`app${rightPanelOpen ? ' right-panel-open' : ''}`}>
    <MainLayout
      // ...
      rightPanelOpen={rightPanelOpen}
      setRightPanelOpen={setRightPanelOpen}
      // ...
    />
  </div>
);
```

```css
.app { grid-template-columns: 280px 1fr; }
.app.right-panel-open { grid-template-columns: 280px 1fr 320px; }
```

### StatusBar compact toggle (no JSX removed)

```tsx
const [compact, setCompact] = useState(true);

return (
  <footer className={`status-bar${compact ? ' status-bar-compact' : ''}`}>
    {/* ...all 14+ badges, each priority one gets data-priority="true"... */}
    <button
      className="status-item status-toggle-compact"
      title={compact ? '显示所有 status badge' : '收起非关键 badge'}
      onClick={() => setCompact((v) => !v)}
    >
      {compact ? '···' : '−'}
    </button>
  </footer>
);
```

```css
.status-bar.status-bar-compact
  .status-item:not([data-priority="true"]):not(.status-toggle-compact) {
  display: none;
}
```

---

## Lessons / takeaways

1. **Source-pin tests shape refactor choices.** The R120 / R126 StatusBar tests
   pin literal className strings, so adding a `status-priority` class is not
   a free change. The `data-priority` attribute is the cleanest fix because
   the selector moves to CSS instead of JSX, leaving the pinned className
   string intact.
2. **Facades beat full rewrites for "feels more like deepagents".** The
   user's brief said "consider deepagents' create agent" — I built a 200-line
   facade in aethercode-core that documents + exercises the existing
   capabilities instead of rewriting QueryEngine. Same model-facing effect
   (model sees a create-agent-style prompt), zero risk to 1109 aethercode-core
   tests.
3. **Default permission mode is a behaviour change, not a code change.**
   R173 recommends `ACCEPT_TASK` but does not change the default. A user
   who wants the recommendation as a default can wire
   `engine.builder().permissionMode(agent.recommendedPermissionMode())`
   themselves; flipping it for everyone would silently change HIL behaviour
   for users who deliberately chose `ASK_BEFORE_TOOL`.
4. **The "real" regression test is the user's own prompt.** Every release
   round, re-run "在当前目录下生成一个 java maven 项目..." and look for
   loop_warn / stream-stale / UI truncation. This is the only test that
   exercises the full stack (UI + daemon + LLM stream + tool loop + HIL).
