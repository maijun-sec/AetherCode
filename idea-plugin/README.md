# AetherCode IDEA Plugin

IntelliJ Platform plugin that hosts the AetherCode agent inside the IDE. Built with
`gradle-intellij-plugin`, Kotlin 1.9, IntelliJ Platform 2024.3.

## Build

```bash
cd idea-plugin
./gradlew :buildPlugin
# produces build/distributions/aethercode-idea-plugin-0.1.0.zip
```

## Install locally

In IDEA: `Settings → Plugins → ⚙ → Install Plugin from Disk…` and pick the zip from
`build/distributions/`. Restart. Then `View → Tool Windows → AetherCode` (or press `Alt+A`).

## What you get

- A `AetherCode` ToolWindow on the right with a chat panel.
- The panel uses the same `AetherCodeEngine` as the CLI — same tools, same permission policy.
- The API key is read from `ANTHROPIC_API_KEY`.
- Project permission rules at `<project>/.aethercode/settings.json` apply.

## R7 — Integration foundation (current round)

R7 turns the chat panel from a static text box into an IDE-aware
surface. The four integration pillars you asked for are all
delivered as named services with IntelliJ MessageBus topics:

| Pillar | Service | Topic | Notes |
|--------|---------|-------|-------|
| Current project | `ProjectContext` | `ContextBus.PROJECT_CHANGED` | basePath / modules / VCS root / Maven-vs-Gradle |
| Open files | `OpenFilesContext` | `ContextBus.OPEN_FILES_CHANGED` | active editor + cursor + selection + enclosing method, auto-injected into prompts |
| Installed plugins | `PluginIntegrations` | `ContextBus.PLUGIN_INTEGRATIONS_CHANGED` | probes Git / Terminal / Database / Docker / HTTP / LSP / AI-Assistant / Markdown |
| Theme | `ThemeManager` | `ContextBus.THEME_CHANGED` | LaF listener, named palette, automatic repaint |

### Backend unification (R7)

The chat panel talks to a new `AetherCodeBackend` interface. Two
implementations live behind `BackendManager`:

- `InProcessBackend` — wraps the legacy `AetherCodeEngineHolder`
  path. Always available. Default fallback.
- `DaemonBackend` — talks to a running `aethercode-tasks`
  supervisor over JSON-RPC 2.0 / TCP loopback. This is the same
  wire protocol the TUI and Desktop use. A 90-line Kotlin client
  mirrors the Java `SupervisorClient` so we don't add
  `aethercode-tasks` as a Gradle dependency.

Selection rules (per `BackendSettings.mode`):
- `auto` (default) — try the daemon, fall back to in-process.
- `daemon` — force daemon, fail if not reachable.
- `in-process` — always use the embedded engine.

`Alt+Shift+B` resets the cache (re-runs selection).

## Module map

| File | Role |
|------|------|
| `plugin.xml` | ToolWindow + action registration + postStartupActivity |
| `AetherCodeToolWindowFactory.kt` | Wires the chat panel into the window |
| `AetherCodeChatPanel.kt` | Swing UI: transcript, input, send/stop, auto-context inject |
| `OpenPanelAction.kt` | Menu / `Alt+A` shortcut action |
| `backend/AetherCodeBackend.kt` | Backend interface |
| `backend/InProcessBackend.kt` | Legacy engine-backed implementation |
| `backend/DaemonBackend.kt` | JSON-RPC client + event conversion |
| `backend/BackendManager.kt` | Selection + caching |
| `backend/ResetBackendAction.kt` | `Alt+Shift+B` cache reset |
| `engine/AetherCodeEngineHolder.kt` | Project-scoped SDK engine cache |
| `engine/BackendSettings.kt` | Mode + daemon lock path persistence |
| `engine/AetherCodeSettings.kt` | Provider / API key / model persistence |
| `context/ProjectContext.kt` | Project snapshot + change events |
| `context/OpenFilesContext.kt` | Editor / selection / enclosing method + change events |
| `context/PluginIntegrations.kt` | Capability detection + change events |
| `context/ThemeManager.kt` | Named palette + LaF listener + change events |
| `context/ContextBus.kt` | Centralised topic catalogue |
| `context/IntegrationStartupActivity.kt` | postStartup hook |
| `diff/DiffViewer.kt` | Inline diff through platform's DiffManager (R2) |
| `input/FileReferenceCompletionContributor.kt` | `@`-file completion (R2) |
| `lsp/LspAutoConnectListener.kt` | LSP auto-start (R3) |
| `bridge/RemoteAgentService.kt` | Cross-agent WebSocket bridge (R6) |

## Known gaps vs. Claude Code's IDE bridge

- R7 ships the integration foundation; settings UI, slash
  command palette, session picker, and inline diff attachments
  arrive in R8+.

## Tests

```bash
./gradlew :test
```

Coverage today:
- `DaemonBackendWireTest` — round-trips a stub supervisor, asserts event conversion.
- `ProjectContextTest` — snapshot equality + build-system detection.
- `PluginIntegrationsTest` — capability enum + id table.
- `ContextBusTest` — topic catalogue.
- `ThemeManagerTest` — palette slot pairs.
