# R11 Misc Utilities

10 self-contained utility candidates that fill small operational gaps: schema validation, patch application, notification batching, session metadata, logging context, tool catalog, permission auditing, workspace trees, secret redaction, and snapshot persistence.

## Candidates

| # | Name | Module | Tests | What it does |
|---|------|--------|-------|--------------|
| 1 | JSON schema validator | core/config | 23 | `SchemaValidator` for type/required/properties/enum/pattern/minLength/min/max/items/oneOf |
| 2 | Patch applier | core/patch | 14 | `PatchApplier` writes `PatchFile` (R9-7) to disk, supports strict/lenient, backup mode, new file/deletion |
| 3 | Notification coalescer | core/notify | 16 | `NotificationCoalescer<T>` batches notifications by key with configurable window |
| 4 | Session metadata | core/transcript | 20 | `SessionMetadata` with title/tags/attributes, JSON persistence, deep equality |
| 5 | Logger MDC context | core/log | 14 | `LogContext` ThreadLocal with `with(key, value)` scope, supports nested scopes |
| 6 | Tool catalog | core/tool | 16 | `ToolCatalog` indexes tools by name + category, supports filters, typed lookup |
| 7 | Permission audit log | permission | 18 | `PermissionAuditLog` JSONL-append of Allow/Deny/Ask decisions with replay |
| 8 | Workspace tree printer | core/fs | 15 | `WorkspaceTree` classic `tree` output with depth/entry limits, hidden filter |
| 9 | Settings redactor | core/config | 19 | `Redactor` masks sensitive keys in maps and JSON-ish strings, custom pattern set |
| 10 | Compact snapshot store | core/compact | 18 | `SnapshotStore` disk-backed JSON snapshot registry with reload/cache |

**R11 totals: 173 new tests across 10 new files + 1 new exception class. 0 regression.**

## Per-candidate notes

### R11-1 schema
- Empty `Map.of()` schema accepts everything (lenient default).
- `oneOf` requires exactly 1 match — uses a sub-validator (not the parent error list) so the parent's `errors` stays clean.
- `Map.of()` caps at 10 entries; for schemas with 10+ keys, prefer `Map.<String, Object>ofEntries(Map.entry(k, v), ...)`.

### R11-2 patch applier
- Critical pitfall: `Files.write(Path, List<String>, ...)` joins with the **system line separator** (CRLF on Windows), so on-disk output differs from `"\n"`-joined input. Always use `String.join("\n", ...) + "\n"` for portable output.
- `oldStart=0` in a hunk header means "this is a new file" — the applier short-circuits and inserts all `+` lines at position 0.
- Multiple hunks in the same file: process in **reverse** so earlier hunks' insertions don't shift later hunks' anchors. The applier does this internally.
- Per-line insertion: when processing a hunk body, each `+` line is inserted at the current cursor (not deferred). This makes `-a, +x, " b", -c, +y` produce `x, b, y` (not `x, y, b`).

### R11-3 coalescer
- Per-key grouping: `add("tool", "Bash")` and `add("tool", "Read")` are flushed together; `add("server", "x")` is independent.
- Consumer exceptions are caught and swallowed — they never break the scheduler loop.
- `flush()` is idempotent: flushing an empty pending set is a no-op.

### R11-4 session metadata
- Uses Jackson `JavaTimeModule` for `Instant` serialisation; without it, you'd get `{seconds, nanos}` blobs.
- `attributes()` and `tags()` return **immutable** snapshots — modifying the returned list throws.
- `updatedAt` is touched on every mutation; `createdAt` is set once at construction.

### R11-5 log context
- `with(key, value)` returns an `AutoCloseable` `Scope` — use try-with-resources.
- Nested scopes restore the previous value on close, not remove the key. This means a parent scope's value is preserved even if a child scope overrides it.
- Rejecting null values (NPE) prevents `Map.put` from silently dropping the entry.

### R11-6 tool catalog
- Distinct from `ToolHookRegistry` (R10-1): the catalog answers "what's available", the registry answers "what runs around the call".
- `addFilter(Predicate<Tool>)` composes — all filters must pass for a tool to be included.
- `getAs(name, Class<T>)` is typed; if the registered tool is not an instance of the class, returns empty.

### R11-7 permission audit
- JSONL append-only: every `record()` writes one line, so an interrupted session never corrupts the log.
- Decision types: `ALLOW` (Allow), `DENY` (Deny with reason), `ASK` (Ask with question).
- `reason` is normalised: for `Allow` it's `"allowed"`, for `Deny` it's the `decisionReason` (or `message` fallback), for `Ask` it's the `question`.
- Corrupt-log handling: malformed file is renamed to `.bak` and the log starts empty.

### R11-8 workspace tree
- `Files.list` is not recursive — we descend manually with a counter for `maxEntries`.
- `maxDepth` is exclusive of the root: `maxDepth=2` means root + 1 level of children.
- `showFiles=false` means directories only. `directoriesOnly=true` is a stricter version that never recurses into files.
- `showHidden=false` (default) skips any name starting with `.`.

### R11-9 redactor
- Substring match (not exact): `password`, `user_password`, `DB_PASSWORD` all match.
- Case-insensitive: keys are lowercased before comparison.
- `redactString` uses regex on `"key":"value"` pairs — it's best-effort, not a full JSON parser. For complex JSON, parse with Jackson and call `redactMap` instead.
- The original input map is **not** mutated (a defensive copy is made).

### R11-10 snapshot store
- One JSON file per snapshot under the configured `dir` — the `id` (UUID) is the file name.
- Corrupt snapshot files are silently skipped on load (so a partial write doesn't take down the whole store).
- `reload()` rebuilds the in-memory cache from disk; useful after an external process writes new snapshots.

## Build verification

`mvn -B test` from `aethercode/`: **843 tests, 0 fails** across 9 modules. Per-module delta from R10 (670 → 843, +173):
- aethercode-core: +123 (R11-1, R11-2, R11-3, R11-4, R11-5, R11-6, R11-8, R11-9, R11-10)
- aethercode-permission: +18 (R11-7)
- aethercode-tui: unchanged
- aethercode-llm: unchanged
- aethercode-mcp: unchanged
- aethercode-memory: unchanged
- aethercode-bridge: unchanged
- aethercode-cli: unchanged
- aethercode-skills: unchanged

## Key pitfalls (carried into R12)

1. **`Files.write(Path, List<String>, ...)` uses `System.lineSeparator()`** — use `String.join("\n", ...)` for portable output.
2. **`Map.of()` is capped at 10 entries** — use `Map.ofEntries(Map.entry(...), ...)` for larger.
3. **`Instant` needs `JavaTimeModule`** for Jackson serialisation.
4. **`Files.readString` on Windows preserves `\r\n`** — tests that compare exact strings need to normalise.
5. **`AutoCloseable` returned from `with()` should be used in try-with-resources** — the manual pattern of `var s = with(...); ...; s.close();` is error-prone.
6. **`appendReplacement` needs `Matcher.quoteReplacement`** for `\` and `$` in the replacement string.

## Backup
`D:\work\tmp\r11_done\` (planned) — 21 R11 files.
