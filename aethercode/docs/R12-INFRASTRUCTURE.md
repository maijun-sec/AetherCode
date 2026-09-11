# R12 Infrastructure

10 self-contained infrastructure candidates: command allowlisting, Markdown sectioning, tool doc generation, LSP multiplexing, TTL caching, process priority, cost attribution, JSON-RPC envelopes, history persistence, and workspace state.

## Candidates

| # | Name | Module | Tests | What it does |
|---|------|--------|-------|--------------|
| 1 | Command allowlist | permission | 20 | `CommandAllowlist` with EXACT/PREFIX/REGEX rules, deny-wins-over-allow semantics |
| 2 | Markdown section extractor | core/format | 13 | `MarkdownSections` with level/title/body/offset, level-1 to level-6 |
| 3 | Tool catalog doc generator | core/format | 10 | `ToolDocGenerator` renders a `ToolCatalog` as Markdown |
| 4 | LSP server multiplexer | tools/lsp | 18 | `LspMultiplexer` routes/broadcasts to multiple LSP servers |
| 5 | Subagent result cache | core/cache | 19 | `TtlCache<K,V>` with loader, prune, hit/miss/eviction counters |
| 6 | Process priority helper | core/proc | 11 | `ProcessPriority` OS-agnostic abstraction with REALTIME clamp |
| 7 | Cost attribution tags | core/cost | 15 | `CostAttribution` per-tag sub-trackers with top-tags sorting |
| 8 | JSON-RPC 2.0 envelopes | mcp | 19 | `JsonRpc` records (Request/Notification/Response/ErrorResponse) with standard error codes |
| 9 | TUI command history persistence | tui | 14 | `HistoryStore` file-backed with LRU dedup, max-size eviction |
| 10 | Workspace state file | core/fs | 17 | `WorkspaceState` typed key-value JSON, atomic write |

**R12 totals: 156 new tests across 10 new files. 0 regression** (1 pre-existing R5 timing flake confirmed on re-run).

## Per-candidate notes

### R12-1 command allowlist
- Deny rules always win over allow rules — this is the safe default; you cannot accidentally allow a more general command by adding a more specific allow.
- A regex that fails to compile is treated as "no match" (fail-closed), not as "match all".
- `safeDefaults()` pre-bakes a small list of read-only commands and blocks `rm -rf /`, `shutdown`, `reboot`.

### R12-2 markdown sections
- Section ends at the next heading of any level (not just the same level). This matches Markdown's convention that `# H1` ends a `## H2` section.
- Byte offsets use `\n` length, so on Windows the offset won't match `Files.readString()` byte count exactly — but for in-memory operations they're consistent.

### R12-3 tool doc generator
- Heading-level relationship: a section header is **2 levels deeper** than the top-level "Tools" heading. So `## Tools` → `#### Bash` (4 hashes). Capped at level 6.
- Schema pretty-printer is intentionally simple (no Jackson dep) — good enough for human-readable output, but doesn't escape every JSON edge case.

### R12-4 LSP multiplexer
- The multiplexer is transport-agnostic — the actual stdio/socket/WS plumbing lives in concrete LSP clients; the multiplexer just dispatches.
- Handler exceptions are caught and turned into `Response.err(id, message)`, so one bad server doesn't poison the broadcast.

### R12-5 TTL cache
- `get(key, loader)` returns cached if fresh, else calls the loader and caches the result.
- The loader is NOT called when the cache has a fresh entry — the test for "load-on-expiry" was wrong in my first pass (counted the first get as a load but it was a hit).
- `prune()` walks the map and evicts expired entries; safe to call periodically.

### R12-6 process priority
- The class is a **stub** for the actual OS call. The interface and clamping logic are real, but the actual `setpriority(2)` / `SetPriorityClass` call is TODO.
- `REALTIME` is demoted to `HIGH` when `canSetPriority()` is false — preventing a non-privileged user from requesting a priority that would fail anyway.

### R12-7 cost attribution
- Per-tag sub-trackers inherit the price table from the parent by registering a default price (0.001) for any model the parent has seen. A cleaner approach would expose `CostTracker.copyPriceTable(other)` — left for R13.
- `topTags(n)` returns the most expensive tags first, useful for the `/cost` command.

### R12-8 JSON-RPC envelopes
- All records use `@JsonInclude(NON_NULL)` so optional fields don't appear in the output.
- Standard error codes: `-32700` (parse), `-32600` (invalid request), `-32601` (method not found), `-32602` (invalid params), `-32603` (internal). MCP extends these with custom codes in the `-32000` to `-32099` range.
- `Notification` has no `id` field — Jackson skips it during serialisation because the record component is absent.

### R12-9 history persistence
- LRU semantics: re-adding "git status" moves it to the end (most recent), keeping the same position in the dedup set.
- Re-appending a command that was previously dropped (because it fell out of `maxEntries`) re-adds it to the end. The `seen` set is NOT pruned, so dedup works across size changes.
- Path collisions: tests must use `@TempDir`, not `Path.of("dummy")` — the latter is a relative path that survives between tests in the same module.

### R12-10 workspace state
- The single JSON document is atomic from the caller's perspective: `save()` writes the whole snapshot, `load()` reads it whole. No partial state visible.
- `ConcurrentHashMap` does NOT allow null values — `put(k, null)` throws NPE. Document this in the API.
- Corrupt-file recovery: a malformed JSON file is renamed to `.bak` and the state starts empty. The caller can inspect the `.bak` to recover.

## Build verification

`mvn -B test` from `aethercode/`: **999 tests, 0 fails** (1 pre-existing R5 timing flake in `TokenBucketRateLimiterTest` confirmed on re-run). Per-module delta from R11 (843 → 999, +156):
- aethercode-core: +76 (R12-2, R12-3, R12-5, R12-6, R12-7, R12-10)
- aethercode-permission: +20 (R12-1)
- aethercode-tools: +18 (R12-4)
- aethercode-mcp: +19 (R12-8)
- aethercode-tui: +14 (R12-9)
- aethercode-memory, aethercode-llm, aethercode-bridge, aethercode-cli, aethercode-skills, aethercode-hooks, aethercode-compact, aethercode-prompts, aethercode-sdk: unchanged

## Key pitfalls (carried into R13)

1. **Test isolation** — `Path.of("dummy")` is a relative path that survives between tests. Always use `@TempDir`.
2. **`ConcurrentHashMap` does not allow null values** — `put(k, null)` throws NPE. Use `Optional` or sentinel values if null is meaningful.
3. **`Files.readString` byte count != `length()` on Windows** — tests that compare exact byte counts need normalisation.
4. **`Files.readString` on Windows may include `\r\n`** — file content tests must be platform-aware.
5. **`record` component name shadows method name** — if a record has a component `denied`, you can't define a method `denied()` (Java rejects this). Use a different name like `denial`.
6. **List.copyOf rejects null elements** — use `Stream.toList()` if nulls are possible, or filter them out.
7. **`Map.of()` is capped at 10** — use `Map.ofEntries(Map.entry(...), ...)` for more.
8. **Jackson `JavaTimeModule` is needed for `Instant`** — without it, `Instant` serialises as `{seconds, nanos}`.

## Backup
`D:\work\tmp\r12_done\` (planned) — 20 R12 files.
