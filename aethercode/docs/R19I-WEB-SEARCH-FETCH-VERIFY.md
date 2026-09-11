# R19-I — WebSearch / WebFetch Verify

**Date**: 2026-08-06
**Status**: DONE — 1213 tests (+5 WebFetchToolTest, +3 FileReadToolTest from R19-H), 0 net regression
**Goal**: Audit and harden the existing `web_fetch` and
`web_search` tools. Both were working but had rough edges
(missing User-Agent, error messages without the URL, no
timeout override, no upper bound on result count).

---

## Why

A user testing `web_fetch https://example.com` would have
succeeded, but a user testing `web_fetch https://some-blog.com`
might have hit a 403 because OkHttp's default User-Agent is
flagged by some WAFs. A user asking for "100 results" would have
burned API quota with no upper bound. Error messages that didn't
include the URL or backend made debugging harder.

Claude Code's web tools are well-behaved. We needed to match.

## What changed

### `WebFetchTool` hardening

- **User-Agent** — set to `AetherCode/0.1 (https://github.com/aethercode; agent)`.
  Identifies us to servers that block default OkHttp UAs.
- **Accept header** — `text/html,application/xhtml+xml,application/json,text/plain;q=0.9,*/*;q=0.5`.
  Asks for HTML when both are offered (some servers return JSON
  by default without an explicit preference).
- **`timeout_s` parameter** — overrides the 60s read timeout.
  Useful for a quick health check that should fail fast.
- **Per-call client when timeout is overridden** — we share the
  default `HTTP` client for the common case (keeps the connection
  pool warm) but build a new client when a different timeout is
  requested.
- **Better error messages** — `HTTP 403 Forbidden for https://x.com`
  instead of `HTTP 403 Forbidden`. `fetch failed for https://x.com: timeout`
  instead of `fetch failed: timeout`.

### `WebSearchTool` hardening

- **`num_results` cap** — clamped to `[1, 20]`. A runaway model
  that asks for `num_results: 1000` gets 20, not 1000.
- **No-more-results message includes backend and query** —
  `(no results for "java streams" via brave)`. Easier to
  diagnose "is this a backend problem or a query problem".
- **Per-result null guards** — old code would NPE on a result
  with a null title or url. Now prints `"(no title)"` / `"(no url)"`.
- **Error message includes query and backend** — `search failed
  for "java streams" (brave): 429 rate limit exceeded`.

## Tests

- `aethercode-tools/.../net/WebFetchToolTest.java` (5 new):
  - `emptyUrlReturnsError` — `""` → error
  - `invalidSchemeReturnsError` — `file:///etc/passwd` → error
  - `invalidUrlReturnsError` — `"not a url at all"` → error
  - `readOnlyFlag` — pin `isReadOnly` so a future refactor doesn't
    accidentally make it destructive
  - `liveFetch_exampleCom` — REAL network test, gated by system
    property `aethercode.test.network=true`. Hits
    `https://example.com/` and verifies we get HTML containing
    "Example Domain". Skipped by default so CI without outbound
    HTTP doesn't fail.

## Files

- `aethercode-tools/.../net/WebFetchTool.java` — User-Agent +
  Accept headers, `timeout_s` parameter, per-call client
  override, better error messages
- `aethercode-tools/.../net/WebSearchTool.java` — `num_results`
  cap, null guards, better error messages
- `aethercode-tools/src/test/.../net/WebFetchToolTest.java` (new, 5 tests)

## Pitfalls (R19-I)

1. **Network test is gated** — the live `liveFetch_exampleCom` test
   requires outbound HTTP. We check the system property
   `aethercode.test.network=true` and skip otherwise. CI
   pipelines that want to run the network test can pass `-DargLine`
   to set the property.
2. **No retry on transient errors** — a 503 from the search
   backend is surfaced as a hard error, not retried. Could add
   a single retry with backoff in a follow-up.
3. **`User-Agent` is fixed, not configurable** — some servers
   reject `AetherCode/0.1` and want a real browser UA. A future
   round could add a `--user-agent` CLI flag.
4. **`web_search` returns 0 results as `(no results)`** — that's
   fine for "nothing matched" but doesn't distinguish from
   "search backend returned empty for some other reason". A
   follow-up could differentiate.
5. **No HTTPS cert validation override** — if a user is on a
   corporate proxy with a MITM cert, the fetch will fail. We
   could add a `--insecure` flag but that's a security
   footgun. Skip for now.

## Backups

`D:\work\workspace\idea\engine\AetherCode\aethercode\docs\backups\r19i\`
(planned)

## Next

R19-J: Performance. Real token counting (replace the 4-chars-per-
token heuristic with a real tokenizer) + prompt caching (cache
the system prompt hash so unchanged prompts don't re-pay the
preamble cost on every turn).
