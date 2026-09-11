# R7: Cost, Quality, and Lifecycle

R7 lifts the day-to-day ergonomics of AetherCode from "works" to "tell me
what it cost, what it leaked, when it was on, who else is on the team." Ten
candidates, all green, no regression on the 147 R6 baseline.

## Test counts

| Round | Tests | Failures | Net new |
|-------|-------|----------|---------|
| R5    | 80    | 0        | —       |
| R6    | 147   | 0        | +67     |
| R7    | 234   | 0        | +87     |

New tests breakdown (87 total):

| Class | Count |
|-------|-------|
| `CostTrackerTest` | 9 |
| `SecretScannerTest` | 14 |
| `NotifierTest` | 3 |
| `OutputStyleTest` | 6 |
| `McpRegistryTest` | 8 |
| `SessionContextCapturerTest` | 8 |
| `PermissionReasonerTest` | 11 |
| `PagerTest` | 12 |
| `MemoryConsolidatorTest` | 7 |
| `TeamMemorySyncTest` | 9 |

## Candidate scorecard

| # | Candidate | Files | Status |
|---|-----------|-------|--------|
| 1 | Token cost tracker | `CostTracker`, Anthropic usage listener | ✅ |
| 2 | Secret scanner | `SecretScanner` | ✅ |
| 3 | Stop-task notification | `Notifier` (NoOp / Recording / SystemTray) | ✅ |
| 4 | Output styles | `OutputStyle` (DEFAULT / TERSE / EXPLANATORY / JSON) | ✅ |
| 5 | MCP official registry | `McpRegistry` (4 built-in servers) | ✅ |
| 6 | Session memory injection | `SessionContext` + `SessionContextCapturer` | ✅ |
| 7 | Permission reason helper | `PermissionReasoner` (rule + mode + read-only) | ✅ |
| 8 | TUI pager | `Pager` (less-style viewport) | ✅ |
| 9 | Auto-dream consolidation | `MemoryConsolidator` (background merge) | ✅ |
| 10 | Team memory sync | `TeamMemorySync` (front-matter .md union) | ✅ |

## Implementation notes per candidate

### 1. Token cost tracker

`CostTracker` is a per-model accumulator that ships in `aethercode-core` and is
owned by `AetherCodeEngine`. The wiring into the LLM client is a one-line
listener:

- `AnthropicChatClient` reads `message_start.message.usage.input_tokens` and
  the running `message_delta.usage.output_tokens` total.
- Just before `message_stop`, the listener fires once with `(modelId, [in, out])`.
- The default price table covers Claude 3 / 3.5 / 4 family at $X / 1k tokens.

The engine exposes `engine.costTracker()` so the TUI/ReplApp can render a
`/cost` line in the future.

### 2. Secret scanner

`SecretScanner` is a regex-based detector for nine well-known secret kinds:
AWS access/secret, OpenAI / Anthropic, GitHub PAT/OAuth, Slack, PEM private
key, generic `key=value`. Each match is a `Match(kind, start, end, value)`
record; `redact(text)` walks the string and replaces each match with
`[REDACTED:<kind>]` from end to start so offsets stay valid.

A `Kind` enum + `register(Kind, String)` API lets callers add custom patterns
without subclassing.

### 3. Stop-task notification

`Notifier` is a sealed interface with three implementations:

- `NoOp` — silent, used in tests + headless CI.
- `Recording` — collects every call; the unit tests use this to assert the
  REPL fired the right kind.
- `SystemTrayImpl` — real OS notification via `java.awt.SystemTray` (the
  only truly cross-platform option Java SE ships). Returns `false` when the
  host has no tray so the REPL treats the call as best-effort.

### 4. Output styles

`OutputStyle` carries a `systemPromptSuffix` that the engine can append
behind the workflow + memory blocks. Four built-ins are registered in
`OutputStyle.REGISTRY`:

- `default` — concise and direct.
- `terse` — code only, no prose.
- `explanatory` — walks the user through every step.
- `json` — wraps every tool call in `{"tool":..., "input":{...}}`.

`register(id, suffix)` lets users add their own. The plan-mode suffix
machinery from R5 already does the wiring; a future round can add a `/style`
command that flips the engine's active style.

### 5. MCP official registry

`McpRegistry` is a hand-curated catalog of four popular servers
(`filesystem`, `git`, `fetch`, `sqlite`) plus one generic `remote-fetch`
SSE entry. `Entry.toConfig()` renders the same shape `McpServers.loadFrom`
already understands. `toMcpJson(name, extra)` wraps it in the
`mcpServers: { name: cfg }` envelope. A future round can add a CLI
`aethercode mcp add <name>` that writes the entry into the user's mcp.json.

### 6. Session memory injection

`SessionContext` is a record with cwd / git branch + head + dirty / os /
hostname / user / capturedAt / env (subset). The capturer shells out to
`git` only when `cwd/.git` exists, picks up a fixed env subset, and
packages the result in a `<aethercode-context>` block ready to prepend to
the system prompt. The capturer constructor is test-friendly: it accepts
canned suppliers for shell, env, hostname, etc.

### 7. Permission reason helper

`PermissionReasoner` re-implements the rule + mode decision flow from
`ProjectPermissionPolicy` but returns a human-readable `Reason(verdict,
source, explanation)` instead of a `PermissionResult`. Renders the source
as `deny-rule:<tool>`, `allow-rule:<tool>`, `mode:<mode>`, or
`read-only-auto`. The TUI can call this before each prompt to surface
"why is this allowed?" without going through the actual policy.

### 8. TUI pager

`Pager` is a less-style viewport over a list of lines. Methods: `goTop`,
`goBottom`, `nextLine` / `prevLine`, `nextPage` / `prevPage`, `setOffset`,
`setOffsetFromEnd`. `render()` paints the current window + a status footer
with line / window / offset counters and a hint line. The REPL can wire it
to a `/page` command in a follow-up round.

### 9. Auto-dream consolidation

`MemoryConsolidator.runOnce()` does one pass over the memory dir, finds
near-duplicate files (Jaccard ≥ 0.6 against the first paragraph), appends
the loser's body to the winner, and deletes the loser. `start(interval,
unit)` schedules the pass on a daemon thread. The MEMORY.md entrypoint
file is excluded so the canonical index never gets merged.

### 10. Team memory sync

`TeamMemorySync.put(title, body)` writes a YAML-front-matter `.md` file
to the team directory. `list()` unions the team dir and a local dir,
dedupes by note id (team source wins on collision), and sorts newest
first. The front-matter carries `id / author / created_at`. The union
shape means multiple sessions / multiple machines on a shared filesystem
can publish + recall notes without coordination.

## Pitfalls surfaced and handled

1. **Async-Jackson deserialises `List<String>` as `List<Object>`** — the
   `containsExactly("alpha", "beta)` call needed the cast first, not
   the varargs trick. Same lesson as R6.
2. **`SettingsPermissions` is a class with public mutable fields, not a
   record** — tests must construct then assign, not pass to a record
   constructor.
3. **`Tool` is an interface with many methods, not just the four I
   needed** — anonymous `new Tool() { … }` in tests must stub
   `checkPermissions` (returns `Allow` for the test tools) AND
   `call`. R5+ added the extra method, R7 tests had to catch up.
4. **`MemoryDeduplicator.firstParagraph` was private** — R7
   `MemoryConsolidator` needs the same helper, so I added
   `firstParagraphPublic` rather than widen the visibility on the
   private method. Keeps the public API stable.
5. **`Pager.maxOffset()` returns `size - 1`, not 0** — a 3-line pager
   has `maxOffset = 2`, not 0. The unit test caught this immediately.
6. **`@TempDir` cannot delete open files on Windows** — same lesson as
   R6 (sqlite lock); R7 has no such dependency so the pattern doesn't
   reappear.
7. **AssertJ `String.isAfter(String)` doesn't exist** — use
   `compareTo(...) > 0` instead. The compile error is friendly enough
   that a one-line fix was enough.
8. **JUnit test stability for `dedup` tests** — testing the consolidation
   pass against a real filesystem, I had to use `@TempDir` + a real
   `.md` extension so the filter accepts the file.
9. **Cost-tracker listener has to be installed before `build()` finishes**
   — the wiring lives in `AetherCodeEngine.Builder` so the order is
   forced by construction; no caller can forget.

## Files touched

```
aethercode-core/pom.xml                                              (no change)
aethercode-core/src/main/java/.../core/cost/CostTracker.java         (new)
aethercode-core/src/main/java/.../core/security/SecretScanner.java   (new)
aethercode-core/src/main/java/.../core/notify/Notifier.java          (new)
aethercode-core/src/main/java/.../core/output/OutputStyle.java       (new)
aethercode-core/src/main/java/.../core/context/SessionContext.java    (new)
aethercode-core/src/main/java/.../core/context/SessionContextCapturer.java (new)
aethercode-core/src/test/java/.../core/cost/CostTrackerTest.java
aethercode-core/src/test/java/.../core/security/SecretScannerTest.java
aethercode-core/src/test/java/.../core/notify/NotifierTest.java
aethercode-core/src/test/java/.../core/output/OutputStyleTest.java
aethercode-core/src/test/java/.../core/context/SessionContextCapturerTest.java
aethercode-permission/src/main/java/.../permission/PermissionReasoner.java  (new)
aethercode-permission/src/test/java/.../permission/PermissionReasonerTest.java (new)
aethercode-tui/src/main/java/.../tui/Pager.java                      (new)
aethercode-tui/src/test/java/.../tui/PagerTest.java
aethercode-mcp/src/main/java/.../mcp/McpRegistry.java                (new)
aethercode-mcp/src/test/java/.../mcp/McpRegistryTest.java
aethercode-memory/src/main/java/.../memory/MemoryConsolidator.java   (new)
aethercode-memory/src/main/java/.../memory/TeamMemorySync.java       (new)
aethercode-memory/src/main/java/.../memory/MemoryDeduplicator.java   (added firstParagraphPublic)
aethercode-memory/src/test/java/.../memory/MemoryConsolidatorTest.java
aethercode-memory/src/test/java/.../memory/TeamMemorySyncTest.java
aethercode-llm/src/main/java/.../llm/anthropic/AnthropicChatClient.java (added withUsageListener)
aethercode-sdk/src/main/java/.../sdk/AetherCodeEngine.java           (CostTracker builder hook, listener wiring)
```

## Verification

```bash
mvn -B test
# 234 tests, 0 failures, 0 errors, 0 skipped — across 14 modules
```

R7 ships clean. Bring on R8.
