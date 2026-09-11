# R07 — IDEA Plugin Integration Foundation

**Date**: 2026-09-09
**Author**: mavis
**Scope**: idea-plugin

## TL;DR

R7 turns the IDEA plugin's chat panel from a static text box
into an IDE-aware surface. Four integration services (project,
files, plugins, theme) are wired through IntelliJ's MessageBus
and the chat panel auto-injects the active editor's context
into every prompt. A new `AetherCodeBackend` interface lets
the plugin talk to the same daemon that powers the TUI and
Desktop, with the legacy in-process engine kept as a
fallback.

## What changed

### Backend abstraction (`backend/`)

| File | Purpose |
|------|---------|
| `AetherCodeBackend.kt` | Interface: `id`, `label`, `isAvailable()`, `query(prompt): Sequence<StreamEvent>`, `abort()`, `dispose()`. |
| `InProcessBackend.kt` | Wraps `AetherCodeEngineHolder`. Always available. |
| `DaemonBackend.kt` | ~90-line line-delimited JSON-RPC client to a running `aethercode-tasks` supervisor. Mirrors `SupervisorClient` without taking `aethercode-tasks` as a Gradle dep. Polls `task/attach` every 200 ms; converts supervisor `ChildEventRecord` → core `StreamEvent`. |
| `BackendManager.kt` | Selection + caching; respects `BackendSettings.mode` (`auto` / `daemon` / `in-process`). |
| `ResetBackendAction.kt` | `Alt+Shift+B` re-runs selection. |

### Settings (`engine/`)

- `BackendSettings.kt` — PersistentStateComponent for mode
  (auto/daemon/in-process) and the daemon lock path.

### Context services (`context/`)

- `ProjectContext.kt` — project snapshot (basePath / modules /
  VCS root / Maven-vs-Gradle heuristic). Reacts to
  `ProjectRootManager` changes.
- `OpenFilesContext.kt` — `FileEditorManagerListener` +
  PSI walk for the enclosing method/class. Used by the chat
  panel to auto-inject `@<activeFile>:<cursorLine>` and a
  `# context: <enclosing>` hint.
- `PluginIntegrations.kt` — `PluginManagerCore` probe. Stable
  `Capability` enum (GIT, TERMINAL, MARKDOWN, DATABASE,
  DOCKER, HTTP_CLIENT, AI_ASSISTANT, LSP_FRAMEWORK).
- `ThemeManager.kt` — LaF listener + named palette slots
  (user / assistant / tool / ok / error / hint / header).
- `ContextBus.kt` — Centralised `Topic` catalogue so all four
  services publish on a discoverable channel.
- `IntegrationStartupActivity.kt` — `postStartupActivity` that
  eagerly instantiates the four services so their listeners
  attach before the user opens a file or switches LaF.

### Chat panel

- `AetherCodeChatPanel.kt` — now uses `BackendManager` (not
  `AetherCodeEngineHolder` directly), `ThemeManager` (not
  hard-coded `JBColor` literals), and `OpenFilesContext` for
  auto-context. Subscribes to `ContextBus.THEME_CHANGED` and
  `ContextBus.OPEN_FILES_CHANGED` to stay in sync with the IDE.

### plugin.xml

- Registers `IntegrationStartupActivity` as a
  `postStartupActivity`.
- Registers `ResetBackendAction` (Alt+Shift+B).

### Tests (`test/`)

| File | Asserts |
|------|---------|
| `DaemonBackendWireTest` | Lock-file probe + round-trip against a stub supervisor. |
| `ProjectContextTest` | Snapshot equality, build-system heuristics. |
| `PluginIntegrationsTest` | Capability enum surface. |
| `ContextBusTest` | Topic catalogue completeness. |
| `ThemeManagerTest` | Palette slot pairs. |

## Reference: how Alibaba Lingma and ByteDance Trae handle this

The user asked us to "充分参考阿里和字节的插件实现". The two
products have slightly different shapes:

**Alibaba Lingma** (通义灵码)
- 3-layer architecture: Core (Graph / Memory / Tool) →
  Extension (LangChain + LangGraph chain) → Manager
  (agent registry, multi-agent orchestration).
- Native session-level "模式切换" (Q&A / Edit / Agent).
- Heavy server-side: the IDE plugin is a thin client. All
  tool calls route through a server-side agent.

**ByteDance Trae** (MarsCode)
- "Builder 模式" — autonomous agentic mode where the user
  describes a task and the agent does the multi-file work.
- "Code 补全 Pro" — predicts the next edit point based on
  the prior commit, like an NES (next edit suggestion).
- Plugin/rest-model architecture: third-party models via
  REST API.

AetherCode's R7 leans Lingma-style on the backend side
(server-routed, multi-agent aware) and Trae-style on the UX
side (command palette, slash commands, autonomous mode).
The `AetherCodeBackend` interface in R7 is the seam that
keeps both paths reachable.

## Reference: AetherCode "自己风格"

Three design choices that distinguish this from a Lingma/Trae
clone:

1. **Shared backend** — the IDEA plugin, TUI, and Desktop all
   talk to the same `aethercode-tasks` supervisor. A user can
   have a long-running task in the TUI, switch to IDEA, and
   continue from the same session. The desktop's project picker
   becomes the IDEA plugin's project picker.
2. **Local-first fallback** — when no daemon is reachable,
   the in-process engine still works. Lingma requires a
   server; we don't.
3. **Theme-table** — a small but explicit named-palette
   table that future "AetherCode dark" themes can target
   without re-painting the world.

## Follow-ups (R8+)

R7 is the foundation. R8 brings the user-visible polish:

- **R8**: Settings UI, slash command palette, provider/model picker.
- **R9**: Session list / project switcher.
- **R10**: Permission prompt banner, memory panel.
- **R11**: Status bar widget, theme switching, full LaF re-paint.
- **R12**: Plugin ecosystem depth (Git/Terminal/Database/LSP).
- **R13**: Polish — inline diff, gutter icons, shortcut set.

## Open questions

- The 200 ms poll in `DaemonBackend.query` is fine for short
  outputs but introduces visible latency on long agent runs.
  R7.5 should switch to the supervisor's push channel
  (`ChildEventRecord` append notifications) once we add a
  server-push socket to the wire protocol.
- The settings UI for `BackendSettings` lands in R8; for R7
  the user has to edit `.idea/AetherCodeBackendSettings.xml`
  by hand (or use the `Alt+Shift+B` action to toggle the
  cache).
- `mvn install aethercode-tasks` is still a follow-up: pulling
  the full Java client in would let us delete the duplicated
  Kotlin client in `DaemonBackend`.
