# R19-E — Session Search + `/sessions` + `/search`

**Date**: 2026-08-06
**Status**: DONE — 1193 tests (+5 SessionStore search tests, +3 from R19-D), 0 net regression
**Goal**: Add commands that let the user (a) list all sessions in
the current store and (b) grep across every session for a query
string. The session store has existed since R6 but had no
search/list surface beyond the in-`--resume` flow.

---

## Why

Multi-session support landed in R6 — sessions live as JSONL files
under `.aethercode/sessions/`, and `/resume <id>` lets you switch
to an older one. But there was no way to *find* the right session
to resume. Users with 50+ sessions had to guess IDs or scroll
through a directory listing.

Claude Code's `/sessions` + `/search <q>` (and OpenCode's
`/sessions` panel) are the answer.

## What changed

### `SessionStore.search(query, maxPerSession, maxFileBytes)`

New method on `SessionStore`. Greps every `.jsonl` file in the
directory for a case-insensitive substring match. Returns at most
`maxPerSession` hits per session, with the matching line truncated
to 200 chars. Sessions larger than `maxFileBytes` are skipped
(produces a synthetic `"skipped: file too large"` hit so the user
knows we tried).

Returns `List<SearchHit>` where each hit carries the
`SessionInfo` (so the UI can show the session ID) and the snippet.

### TUI: `/search <query>`

New `/search` command. Usage: `/search hello world`. Searches
across every session in the current store, up to 5 hits per
session, skips files > 1 MB. Caps display at 30 hits with a
"… more results, narrow your query" hint.

### TUI: `/sessions` (already existed, R6)

Already implemented in R6 — kept as-is. The pre-existing handler
prints session ID, message count, and last activity as a table.

### `formatAge(long epochMs)` helper

New static helper in `ReplApp` that renders a "5m ago" / "3h ago"
/ "2d ago" string for any timestamp. Falls back to a `YYYY-MM-DD`
date stamp past 30 days. Currently only used by `formatAge`-based
date display (the existing `/sessions` table uses the raw
`Instant.toString()`); can be wired into `/sessions` later.

## Tests

- `aethercode-core/.../transcript/SessionStoreTest.java` (+5 new):
  - `search_findsMatchingLinesAcrossSessions` — 2 sessions, 1 hit
    each, both surfaces
  - `search_isCaseInsensitive` — `MIXEDCASE` and `mixedcase` both
    match `MixedCase Query`
  - `search_respectsMaxPerSession` — 5 lines, max=2 → 2 hits
  - `search_skipsFilesLargerThanLimit` — 0-byte limit forces every
    file to be skipped, yielding a "skipped" hit
  - `search_emptyQueryReturnsEmpty` — `""` and `null` both return
    empty list

## Files

- `aethercode-core/.../transcript/SessionStore.java` — new
  `search(...)` method + `SearchHit` record
- `aethercode-core/.../transcript/SessionStoreTest.java` — 5 new
  tests
- `aethercode-tui/.../ReplApp.java` — `/search` command, help text
  update, `formatAge` helper

## Pitfalls (R19-E)

1. **Substring match is noisy** — the raw JSONL line includes
   structural fields (role, id, content, etc.). Searching for
   common words like "id" or "role" matches every line. The current
   implementation accepts this as a known limitation; a future
   round could parse the JSON and search inside the `content`
   field only, or use a more sophisticated scoring.
2. **Max file size = 1 MB** — chosen to be small enough to keep
   the search fast (worst case: scan 1 MB per file in O(N) with a
   String.contains) but large enough to cover ~10k messages.
   Sessions above this threshold are skipped silently with a
   "skipped: file too large" hit so the user knows we tried.
3. **`SearchHit.snippet` is a truncated raw JSON line** — the UI
   displays the line as-is. For a 5-line hit, you see JSON, not
   the agent's prose. Acceptable for `claude-code` parity (which
   also shows raw snippets) but a future round could pretty-print.
4. **No `--case-sensitive` flag** — always case-insensitive. The
   TS original lets you prefix a search with `c:` for case
   sensitivity. Skip for now; can add in a follow-up.

## Backups

`D:\work\workspace\idea\engine\AetherCode\aethercode\docs\backups\r19e\`
(planned)

## Next

R19-F: Custom agent definitions. Read `~/.aethercode/agents/*.md`
to load user-defined custom agents, each with their own system
prompt + tool pool. `/agents` lists them, `/agent <name>` switches
the active agent.
