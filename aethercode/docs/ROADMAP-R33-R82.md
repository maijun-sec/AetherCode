# R33-R82 Roadmap — UX & Capability Optimization

**Date**: 2026-08-09
**Owner**: Mavis
**Status**: starting R33

## Goal
50+ focused rounds covering:
- **TUI UX** (R33-R62, 30 rounds): page aesthetics, feature completeness, comfortable fonts, sensory differentiation per step
- **Backend capability** (R63-R82, 20 rounds): task planning, multi-step, sync/async, long-running task execution
- Bundled/combined where natural

Per-round discipline: small change + thorough tests + retrospective.

## Round Plan

### TUI UX (R33-R62)

| # | Round | Title | Key idea |
|---|---|---|---|
| 33 | R33 | Header pills + status | replace flat header with chips (model / mode / session / conn), each pill has distinct color |
| 34 | R34 | Tool card evolution | duration timer, retry count, expand details panel, status trail |
| 35 | R35 | Animation polish | spinner variants per tool type, success pulse, error shake (visual hint) |
| 36 | R36 | Improved Markdown | table support, code highlighting (via chalk bg), blockquote, lists, task lists |
| 37 | R37 | Side panel | left rail showing recent projects / recent tasks / history (deferred from R32-E) |
| 38 | R38 | Smart input | autocomplete for slash commands + file paths via Tab |
| 39 | R39 | Multi-line input | backslash continuation, paste handling, soft-wrap preview |
| 40 | R40 | Search | `/search` finds past turns, highlights matches, jump-to |
| 41 | R41 | Toast notifications | transient side notes that float and fade |
| 42 | R42 | Themes | selectable color schemes (default, solarized, monokai) |
| 43 | R43 | Layout presets | `--layout minimal/full/focus` switches panel density |
| 44 | R44 | Progress bars | for known-duration operations (model "thinking" with hint) |
| 45 | R45 | Tooltips | key combo hints inline in tool cards |
| 46 | R46 | Welcome v2 | better first impression: recent projects, last model, hints |
| 47 | R47 | Command palette | Ctrl+P — fuzzy search over all slash commands + recent queries |
| 48 | R48 | Token chart | sparkline of input/output tokens over the session |
| 49 | R49 | Better help | categorized shortcuts, key combo, slash commands, model ops |
| 50 | R50 | Edit/redo | rewind to a prior user message, re-issue from there |
| 51 | R51 | Snippets | `/snippet` save + reuse prompt templates (persistent) |
| 52 | R52 | Error display | error boundaries with stack + suggested action |
| 53 | R53 | Diff view | show file_edit / file_write diff inline with color |
| 54 | R54 | Responsive | adapt to terminals < 80 cols (compact mode) |
| 55 | R55 | Mouse | click-to-focus, scroll, select text in scrollback |
| 56 | R56 | Log viewer | separate panel for daemon log (toggle with Ctrl+L) |
| 57 | R57 | Icons++ | add icon set for tool categories (read / write / search / run) |
| 58 | R58 | Step differentiation | assign each step a unique color hint (e.g. tool call = green pulse, edit = blue) |
| 59 | R59 | Tutorial | first-run guided tour (3-step walkthrough) |
| 60 | R60 | Bookmark | `/bookmark <id>` save a turn for quick re-display |
| 61 | R61 | Export | `/export` save scrollback to .md / .json |
| 62 | R62 | UX retrospective | collect feedback, fix top-5 rough edges |

### Backend capability (R63-R82)

| # | Round | Title | Key idea |
|---|---|---|---|
| 63 | R63 | Plan execution | explicit plan mode: `Plan` event → user approves → step-by-step execution |
| 64 | R64 | Parallel tool calls | execute independent tool calls concurrently (batches) |
| 65 | R65 | Subagents | spawn isolated context windows for subtasks |
| 66 | R66 | Background tasks | long-running tools (build, test) run async with progress events |
| 67 | R67 | Checkpoint/resume | session state persisted to disk; auto-recover on crash |
| 68 | R68 | Task DAG | explicit task dependency graph; topological execution |
| 69 | R69 | Retry policies | per-tool exponential backoff with circuit breaker |
| 70 | R70 | Rate limiting | per-tool token bucket; global session quota |
| 71 | R71 | Caching | memoize idempotent read tools (file_read, glob, grep) per file mtime |
| 72 | R72 | Cost budget | `--max-cost N` enforces per-session spend cap, warns at 80% |
| 73 | R73 | Plugin loader | external jar/skills hot-reload at runtime |
| 74 | R74 | Tool chains | `$prev` reference to pipe one tool's output to the next |
| 75 | R75 | Context pruning | smart truncation: keep system + recent + summary of middle |
| 76 | R76 | Stream compression | gzip JSON-RPC lines on the wire for big payloads |
| 77 | R77 | Metrics endpoint | `--metrics :9090` Prometheus scrape target |
| 78 | R78 | Tracing | OpenTelemetry spans for query / tool / model |
| 79 | R79 | Audit log | append-only JSONL of every state transition |
| 80 | R80 | Multi-session | daemon handles N concurrent sessions, keyed by sessionId |
| 81 | R81 | Session handoff | `--attach <id>` to a running daemon, share live state |
| 82 | R82 | Distributed workers | optional: tools can run on remote workers (RPC) |

## Per-round template

1. **Plan**: what's the change, what's the test, what's the doc
2. **Code**: minimal change to satisfy the round's title
3. **Test**: at least 1 unit test + 1 E2E or visual test where applicable
4. **Doc**: update README / CHANGELOG / USAGE
5. **Bundle / build**: ensure dist/ and ac-tui.js are current
6. **Retrospective**: 1-paragraph "what worked, what didn't"

## Conventions
- One round = one commit-worthy unit
- Each round's branch name = `R33`, `R34`, ...
- Each round's test file = `scripts/test/round-NN-*.test.mjs`
- Each round's doc = `docs/R{NN}-RETROSPECTIVE.md` (or append to existing)
- Don't break existing tests
- TUI changes → e2e-line-*.mjs if line mode is affected
- Backend changes → Java mvn test

## Progress

| Round | Status | Tests | Notes |
|---|---|---|---|
| R33 | in progress | TBD | Header pills + status |
| R34 | pending | | |
| ... | | | |
| R82 | pending | | |
