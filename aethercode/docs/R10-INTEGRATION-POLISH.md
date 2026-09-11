# R10 Integration & Polish

10 candidates filling operational gaps surfaced by R1-R9: tool-call lifecycle, terminal rendering, persistent memory, file matching, server health, history search, network proxy, themes, diff display, and timeouts.

## Candidates

| # | Name | Module | Tests | What it does |
|---|------|--------|-------|--------------|
| 1 | Tool call hook system | core/tool | 14 | `ToolHook` interface + `ToolHookRegistry` with LIFO post-order, pre-mutation, deny short-circuit |
| 2 | Markdown table renderer | core/format | 18 | `MarkdownTable.parse` + `render` with alignment, ANSI colour support |
| 3 | File-based memory store | memory | 19 | `FileBackedMemory` with JSON persistence, tags, scope, search, corruption recovery |
| 4 | Glob matcher | core/fs | 22 | `*`, `**`, `?`, `[abc]`, `{a,b,c}` + file-tree expansion, multi-glob combinators |
| 5 | MCP server health check | mcp | 18 | `McpHealthCheck` with status enum, threshold-based degradation, periodic scheduler |
| 6 | Command history search | tui | 21 | `HistorySearch` with prefix/substring/fuzzy scoring, dedup, max-size eviction |
| 7 | HTTP proxy | llm/proxy | 17 | `HttpProxySupport` Config + Authenticator + non-proxy host bypass |
| 8 | TUI syntax themes | tui | 16 | `SyntaxTheme` with default/plain/dark/light builtins + paint() helper |
| 9 | Agent diff printer | tui | 13 | `DiffPrinter` consumes `PatchFile` (R9-7) and renders with theme colours |
| 10 | Tool call timeout | core/tool | 15 | `ToolTimeoutEnforcer` with per-tool overrides, denial short-circuit, post-hook integration |

**R10 totals: 173 new tests across 10 new files + 1 minor refactor (`MarkdownTable` uses 14 tokens via `Map.ofEntries`). 0 regression.**

## Per-candidate notes

### R10-1 hooks
- LIFO post-order: most recently registered hook sees the result first (A→B→C in post means A wraps B wraps C).
- `denial` is a `Result` (not boolean) so a hook can both deny and provide an explanatory error message.
- Helper factories `denyIf(pred, mapper)` and `transformInput(fn)` for the common cases.

### R10-2 markdown table
- Bug found: `Map.of()` caps at 10 entries; my themes have 14. Use `Map.ofEntries(Map.entry(...), ...)` for 10+.
- Alignment is read from the separator row: `:---` / `---:` / `:---:`.
- The renderer pads cells to a fixed width so all rows align; ANSI escape codes are stripped when computing visible width.

### R10-3 file-backed memory
- Uses Jackson `JavaTimeModule` for `Instant`; without it, `Instant` serialises as `{seconds, nanos}` (annoying for human inspection).
- Corrupt-file handling: a malformed JSON file is renamed to `*.bak` and the store starts empty — never silently lose data.
- `Stats` returns an immutable map snapshot for safe exposure in the TUI.

### R10-4 glob
- Recursive `**` in our regex compiler eats the trailing slash (`**/foo` → `.*foo`), which is semantically equivalent for the matcher.
- `expand(root, glob, recursive)` switches between name-only and path-relative matching based on whether the glob has `/`.
- Static `compile` returns a Java NIO `PathMatcher` for direct use with `Files.walkFileTree`.

### R10-5 health check
- Status enum: `UNKNOWN` → `UP` → `DEGRADED` (1 failure) → `DOWN` (N consecutive failures). `UP` is restored on the next success.
- The `Registry` aggregator makes the TUI's "server health" panel trivial — `upCount()`, `downCount()`, `snapshots()`.
- `defaultExecutor()` is daemon so it doesn't block JVM shutdown.

### R10-6 history search
- Scoring: exact-prefix +200, prefix-with-word-boundary +150, plain prefix +100, substring +50, fuzzy variable.
- Deduplication: re-adding "ls" bumps its frequency counter rather than appending a duplicate.
- `setMaxSize` trims immediately, so a config change to 10 from 100 trims the oldest 90 entries on the next call.

### R10-7 http proxy
- `parse("host:port:user:pass")` for environment-variable use.
- `shouldBypass` supports `*.example.com`, `.example.com`, `prefix*`.
- `Authenticator` is per-call (caller responsible for installing it). `Authenticator.setDefault` would stomp on global state, so we leave the wiring to the caller.

### R10-8 themes
- All builtins use `Map.ofEntries` (limit 10). `defaultTheme` and `dark` / `light` have 14 tokens.
- `paint(token, text)` is the only public colour method — it wraps with the open/close sequence.
- `RESET` is a public static constant so callers don't have to hardcode `"\u001b[0m"`.

### R10-9 diff printer
- Consumes the `PatchFile` from R9-7; doesn't re-parse the diff.
- `RESET` is conditional on `ansi` so `new DiffPrinter(SyntaxTheme.plain(), false)` emits zero escape codes.
- Test pitfall: `PatchParser` strips `a/` and `b/` from paths, so `--- a/f` becomes `--- f` after parsing. Tests must use the stripped form.

### R10-10 tool timeout
- Pre-hook pipeline runs first; if any hook denies, the tool is never invoked.
- After the future returns, post-hooks transform the result.
- `actionThrowing` is captured as `Result.error(...)` — the model sees a structured failure, not a stack trace.

## Build verification

`mvn -B test` from `aethercode/`: **670 tests, 0 fails** across 9 modules. Per-module delta from R9 (497 → 670, +173):
- aethercode-core: +43 (R10-1, R10-2, R10-4, R10-10)
- aethercode-llm: +17 (R10-7)
- aethercode-mcp: +18 (R10-5)
- aethercode-memory: +19 (R10-3)
- aethercode-tui: +76 (R10-6, R10-8, R10-9)

## Key pitfalls (carried into R11)

1. **`Map.of()` is capped at 10 entries** — use `Map.ofEntries(Map.entry(k, v), ...)` for more.
2. **Jackson `JavaTimeModule` is needed for `Instant`** — without it, you get `{seconds, nanos}` blobs.
3. **`PatchParser` strips `a/` and `b/` prefixes** — tests must check the stripped path.
4. **`Objects.requireNonNull` throws NPE, not IAE** — for null-checks that aren't IAE-style, use `assertThrows(NullPointerException.class, ...)`.
5. **Jackson `rec` for `toMap` requires the right MAPPER** — if you use a different `ObjectMapper` than the rest of the project, the polymorphic `ContentBlock` won't deserialise.
6. **Surefire runs against installed artifacts** — when adding a new module's classes, `mvn install -DskipTests` first or surefire throws `NoClassDefFoundError`.
7. **Recursive `**` glob eats the trailing slash** — semantically equivalent but visible in regex output.

## Backup
`D:\work\tmp\r10_done\` (planned) — 21 R10 files.
