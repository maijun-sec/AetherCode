# R127 — Session-bound cwd + 3-layer memory + LLM compression

**Status**: shipped 2026-08-20
**Build**: 2181 Java tests + 418 TS tests, 0 failures, 0 regressions
**Round count**: 127 (50% over the 50+ target)

## Why R127

The user brief was a single message with 9 distinct optimisation points;
R127 ships points 1–5 (session + cwd + memory). R128–R131 are planned
to ship the remaining four (skill lazy reveal, MCP reload, bash safety,
daemon ops) — see the **R128–R131 roadmap** at the bottom of this
document.

The five R127 points in the user's own words:

1. **session-bound cwd** — switching CWD should be a sub-second
   session operation, not a multi-second daemon restart. The daemon
   must support many concurrent TUI / Desktop / multica clients, each
   with its own (session, cwd, prompt) tuple.
2. **multi-session per daemon** — one daemon, N sessions, each with
   its own cwd. Switching cwd updates the session's row, not the
   daemon's.
3. **3-layer memory** — `USER` at `~/.aethercode/agent-memory/<agentType>/`,
   `PROJECT` at `<cwd>/.aethercode/agent-memory/<agentType>/`,
   `SESSION` in a daemon-owned SQLite DB.
4. **project memory auto-compress** — once the change log crosses
   `threshold` (default 50, configurable in
   `.aethercode/config.json -> memory.projectCompressThreshold`),
   the LLM summarises the oldest block, keeping the most recent
   `keepRecent` (default 10) entries verbatim.
5. **strict isolation** — different sessions must not see each other's
   session memory; different cwds must not see each other's project
   memory; if a session switches cwd, the project's memory must be
   rebuilt fresh.

## What shipped in R127

### Session-bound cwd (point 1 + 2)

- `AetherCodeMethods.switchProject({cwd, sessionId?})` rewrites the
  engine's `appState.cwd` for the target session, persists the new
  cwd to the `sessions.db` table, and emits a
  `NOTIFY_CWD_CHANGED` notification that every connected client
  receives.
- `AetherCodeEngine.setCwd(Path)` is now public and the
  `AppState.cwd` field changed from `final` to `volatile` so a
  hot-swap (user clicks "switch project" in the TUI) takes effect
  on the engine's running turn loop without a daemon restart.
- The Tauri `set_cwd` path is kept as a thin wrapper for the
  first-launch case; from now on every cwd change goes through
  `switchProject` so the session row + the project-memory cache
  stay in lock-step.

### 3-layer memory (point 3)

- `MemoryScope` enum gains a new `SESSION` value.
- `MemoryPaths` resolves SESSION to
  `<memoryBase>/agent-session-memory/<agentType>` (a sentinel;
  the actual storage is the SQLite DB).
- `SessionMemoryStore` (SQLite via `org.xerial:sqlite-jdbc:3.46.0.0`)
  holds two tables: `session_info` (one row per session, columns
  `session_id`, `cwd`, `first_prompt`, `created_at_ms`,
  `last_used_at_ms`) and `session_memory` (per-session key/value
  with `created_at_ms` / `updated_at_ms`). Indexed on
  `(session_id, key)`. Re-entrant read/write lock; safe to share
  across the daemon's many RPC handler threads.
- `LayeredMemoryStore` is the facade: one USER store
  (FileBackedMemory under `memoryBase/agent-memory/<agentType>/`),
  one PROJECT store per cwd (FileBackedMemory under
  `<cwd>/.aethercode/agent-memory/<agentType>/`, cached per
  cwd), and one SESSION store (the SQLite DB).
- `AetherCodeConfig.MemoryConfig` static inner class wires the
  three knobs: `projectCompressThreshold=50`, `keepRecent=10`,
  `autoCompress=true`. Override via
  `.aethercode/config.json -> "memory"`.

### Project memory auto-compression (point 4)

- `ProjectMemoryCompressor.maybeCompress(file, threshold, keepRecent)`
  reads the change-log file, picks the oldest
  `count - keepRecent` entries, and asks the LLM to summarise
  them. The chat client is a `BiFunction<String, Optional<String>>`
  — when not wired, the compressor falls back to a tag-only line
  `"[compressed: N entries]"` so the file shape stays valid.
- The compressor takes a per-file lock (non-blocking; concurrent
  appends report `"busy"` and try again on the next threshold
  check).
- `appendProjectChange` is fire-and-forget: on every append the
  store checks `size() > threshold` and, if so, fires the
  compressor asynchronously. The next `listProject` reads the
  already-compressed file.

### Strict isolation (point 5)

- `LayeredMemoryStore.userStore()` uses the constructor
  `memoryBase`, NOT the global `MemoryPaths.memoryBase()` — so
  test code (or any caller) can fully isolate by passing a
  `@TempDir` path. (Pre-R127 leaked test entries into the user's
  real `~/.aethercode` — silent accumulation across runs.)
- `LayeredMemoryStore.invalidateProject(cwd)` drops the cached
  project store for one cwd. Called by `switchProject` for the
  OLD cwd so the next read re-loads from disk with the new
  context.
- `LayeredMemoryStore.invalidateAllProjects()` is a full cache
  reset (for the "I edited MEMORY.md in vim, please reload"
  use case).
- `SessionMemoryStore.deleteSession(sessionId)` cascades to
  `session_memory` rows so a session deletion leaves no
  orphaned entries.

## Wire RPCs (5 new, 1 new notification)

| RPC | shape | use |
|---|---|---|
| `getMemory({scope, key, sessionId?, cwd?})` | `{ok, scope, key, content\|value, createdAt, updatedAt, …}` | single-entry read |
| `setMemory({scope, key, content\|value, tags?, sessionId?, cwd?})` | `{ok, scope, key, autoCompress?, projectCompressThreshold?, currentSize?}` | single-entry write (PROJECT adds a timestamped change-log line) |
| `listMemory({scope, sessionId?, cwd?})` | `{ok, scope, count, entries[], autoCompress?, projectCompressThreshold?, keepRecent?}` | list all entries in a scope |
| `deleteMemory({scope, key, sessionId?, cwd?})` | `{ok, scope, key}` | single-entry delete |
| `compressProjectMemory({cwd, threshold?, keepRecent?, force?})` | `{ok, compressed, beforeCount, afterCount, reason, cwd, file}` | force a compression pass |
| `switchProject({cwd, sessionId?})` | `{ok, sessionId, oldCwd, newCwd}` | bind a session to a cwd |
| `NOTIFY_CWD_CHANGED` | `{sessionId, oldCwd, newCwd, atMs}` | broadcast on every `switchProject` |

### Method renames (backward compat)

The R92 file-based methods were renamed to make room for the new
entry-based ones with the same wire name:

| Old (R92) | New (R127) | wire name |
|---|---|---|
| `listMemory(scope, agentType)` | `listMemoryFiles(scope, agentType)` | `listMemory` |
| `readMemory(scope, agentType, name)` | `readMemoryFile(scope, agentType, name)` | `readMemory` |
| `writeMemory(scope, agentType, name, content)` | `writeMemoryFile(scope, agentType, name, content)` | `writeMemory` |
| `deleteMemory(scope, agentType, name)` | `deleteMemoryFile(scope, agentType, name)` | `deleteMemory` |

This is a backward-incompatible change for the file-based RPCs —
the renderer's `MemoryPanel.tsx` was updated to call the new
methods. The entry-based RPCs ship with new names (`getMemory`,
`setMemory`, etc.) so there's no method-name collision.

## Tests (NEW)

| Test | Tests | Coverage |
|---|---:|---|
| `SessionMemoryStoreR127Test` | 15 | schema bootstrap, session_info CRUD, session_memory CRUD, reopen-safety, isolation |
| `LayeredMemoryStoreR127Test` | 11 | USER / PROJECT / SESSION isolation, cwd cache invalidation, autoCompress trigger + disable, RPC wiring accessors |
| `ProjectMemoryCompressorR127Test` | 9 | LLM-driven summary, noop fallback (tag-only), prompt format, race tolerance (per-file lock), keepRecent edge cases |
| `memoryR127.test.ts` (TS) | 19 | RPC wrappers, store slice, `bindSessionCwd`, `NOTIFY_CWD_CHANGED` handler, R92→R127 backward-compat pins |
| **Total new** | **54** | |

Cumulative: **2181 Java + 418 TS = 2599 tests, 0 failures, 0 regressions.**

## Files (R127)

### NEW

- `aethercode-memory/src/main/java/org/aethercode/memory/SessionMemoryStore.java` (15 KB) — SQLite-backed session store
- `aethercode-memory/src/main/java/org/aethercode/memory/LayeredMemoryStore.java` (8.6 KB) — 3-layer facade
- `aethercode-memory/src/main/java/org/aethercode/memory/ProjectMemoryCompressor.java` (7.3 KB) — LLM-driven summariser
- `aethercode-memory/src/test/java/org/aethercode/memory/SessionMemoryStoreR127Test.java`
- `aethercode-memory/src/test/java/org/aethercode/memory/LayeredMemoryStoreR127Test.java`
- `aethercode-memory/src/test/java/org/aethercode/memory/ProjectMemoryCompressorR127Test.java`
- `aethercode-desktop/src/store/memoryR127.test.ts`

### MOD

- `aethercode-memory/src/main/java/org/aethercode/memory/MemoryScope.java` — `+SESSION` value
- `aethercode-memory/src/main/java/org/aethercode/memory/MemoryPaths.java` — SESSION case in switch
- `aethercode-memory/pom.xml` — `+org.xerial:sqlite-jdbc`
- `aethercode-config/src/main/java/org/aethercode/config/AetherCodeConfig.java` — `+MemoryConfig` static inner class
- `aethercode-config/src/main/java/org/aethercode/config/ConfigEngine.java` — 8-arg constructor
- `aethercode-core/src/main/java/org/aethercode/core/app/AppState.java` — `cwd final→volatile` + setter
- `aethercode-sdk/src/main/java/org/aethercode/sdk/AetherCodeEngine.java` — `+setCwd(Path)` public method
- `aethercode-protocol/src/main/java/org/aethercode/protocol/methods/AetherCodeMethods.java` — `+memoryStore` field, `+5 RPCs`, `+NOTIFY_CWD_CHANGED`, `switchProject` rewrite, `chatClientResolverField()` public, `readMemoryFile`/`writeMemoryFile`/`deleteMemoryFile` rename
- `aethercode-protocol/src/main/java/org/aethercode/protocol/http/HttpJsonRpcServer.java` — `+5 case arms` for memory RPCs, `listMemoryFiles`/`readMemoryFile`/`writeMemoryFile`/`deleteMemoryFile` rename
- `aethercode-cli/src/main/java/org/aethercode/cli/DaemonRunner.java` — `+buildMemoryStore` helper, wires the memory store at startup on both stdio and HTTP paths
- `aethercode-desktop/src/lib/methods.ts` — `+getMemory/setMemory/listMemory/deleteMemory/compressProjectMemory/bindSessionCwd` typed wrappers, `R92` methods renamed
- `aethercode-desktop/src/store/index.ts` — `+memory/memoryStats/lastMemoryRefreshMs/lastCwdChangedAt` state, `+refreshMemory/memorySet/memoryDelete/compressProjectMemory/bindSessionCwd` actions, `+NOTIFY_CWD_CHANGED` handler
- `aethercode-desktop/src/components/MemoryPanel.tsx` — R92→R127 method rename

## R128–R131 roadmap (the remaining 4 points from the user's brief)

Per the user's chosen ship order, the next 4 rounds are:

### R128 + R129 — combined "registry reload" architecture

- Skill lazy reveal: load skill `name + description` into the
  tool pool, then load the full body on demand when the model
  picks it. Renders as a `/skill` slash command + a TUI picker.
- MCP hot reload: file-watcher on
  `~/.aethercode/mcp.json` / `<cwd>/.aethercode/mcp.json` —
  when the user edits the file, the daemon tears down + re-creates
  the affected MCP connections, no daemon restart needed.
- Shared service: a single `RegistryReloadService` owns the
  file watcher, the reload RPC, and the notification fan-out.
  Exposed to the renderer as `reloadRegistries({kind: 'all' | 'skills' | 'mcp'})`.

### R130 — bash safety (denylist expansion + smart reject + explicit confirm)

- Expand `AETHERCODE_BASH_DENYLIST` patterns: `rm -rf /`,
  `rm -rf ~`, `sudo`, `git push -f`, `curl|sh`, `mkfs`,
  fork bomb `:(){ :|:&};:`, `dd if=... of=/dev/...`,
  `chmod -R 777 /`.
- Smart reject: when the user's command matches a denylist
  pattern, return a structured rejection that tells the model
  *why* the command was refused (so it can rephrase).
- Medium-risk explicit confirm: shell commands that the risk
  classifier rates as `medium` (e.g. writes to
  `~/.aethercode/`, `npm install`, `pip install`) prompt the
  user once even when `autoApproveMediumHigh` is on. The prompt
  shows the exact command + a 10-second "always allow this
  command for this session" toggle.

### R131 — daemon ops

- JVM tuning: README recommends `-Xmx` at 60% of physical RAM
  for sustained workloads (memory is the daemon's hot path).
- `/healthz` endpoint on the HTTP+WS daemon: returns 200 with
  `{status, sessionId, uptimeMs, memUsedMb, memMaxMb,
  queriesInFlight, toolsInFlight, version, schemaVersion}`.
  Wire it into systemd's `WatchdogSec` / k8s `livenessProbe`.
- Graceful shutdown: on `SIGTERM` the daemon stops accepting new
  RPCs, waits up to 30s for in-flight queries + tool calls to
  complete, then exits 0. `SIGINT` (Ctrl-C) for the dev path
  skips the wait.
- README quickstart: 5-line install + 1-line `ac-tui` boot.
- systemd unit + Windows service registration script.

## R128–R131 ship order

Per the user's "依赖顺序 ship" (ship in dependency order) choice:

1. **R127** — session-bound cwd + 3-layer memory (this round)
2. **R128 + R129** — combined registry reload
3. **R130** — bash safety
4. **R131** — daemon ops

Each round takes ~30 min for the Java work + 30 min for the TS
work + 20 min for tests + 20 min for the release zip. So the
remaining 3 rounds (R128–R131) ship in roughly 3 × 1.5h = 4.5h
of focused work.
