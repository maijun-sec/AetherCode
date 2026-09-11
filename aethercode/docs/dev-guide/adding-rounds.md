# Adding a New R-Round

Every improvement to AetherCode (R33+) follows the same workflow.
This page is the canonical recipe.

## Anatomy of a round

A "round" is a self-contained improvement that:
- Adds 1 user-facing feature (or fixes 1 bug)
- Has 5-20 unit tests
- Has 1 E2E test where applicable
- Touches 1-5 source files
- Ships in 1 commit

Rounds are 5-15 minutes each, end-to-end.

## Workflow

### 1. Plan (1 minute)

Answer these 4 questions before writing code:

- **What** is the user-facing change? (e.g. "side panel that shows tasks")
- **How** is it invoked? (e.g. "Ctrl-B toggle")
- **What** is the test? (e.g. "Ctrl-B flips state.sidebarVisible")
- **What** docs need updating? (`features.md` / `keybindings.md` / `slash-commands.md` / `jsonrpc.md` / `state-model.md`)

If the change is "backend + TUI", pick a small RPC to add and a small
TUI surface to consume it. One round = one pair.

### 2. Code (3-5 minutes)

- **State**: add the new field(s) to `State` + default in `INITIAL`.
- **Action**: add a new `Action` variant + reducer case.
- **Component**: build the new UI (or wire the existing one).
- **tui.tsx**: register the key binding / slash command / RPC.
- **No copy-paste from other rounds**: each round adds a new
  combination; if you're tempted to copy a lot, the rounds should
  be split.

### 3. Test (3-5 minutes)

- **Unit test** in `scripts/test/rXX-name.test.mjs`:
  - Compile `state.ts` to a tmp dir, import the helpers.
  - Pure-function tests for `categorizeTool` / `formatDuration` /
    `classifyStopReason` / `rankCommands` / `searchTurns` etc.
  - Reducer tests: `reducer(INITIAL, { type: ... })` → expected
    state.
  - Source-code assertions: the new file exists, exports the
    right symbols, and the wiring is in `tui.tsx`.
- **TypeScript build check**: compile the new file with `tsc
  --jsx react --moduleResolution bundler` to catch import errors
  esbuild might miss.
- **Bundle smoke test**: `node scripts/bundle.mjs` should succeed.

### 4. E2E (2-3 minutes, optional but recommended for backend+TUI rounds)

A `scripts/test/e2e-rXX-name.mjs` script that:
- Spawns `java -jar dist/aethercode-0.2.1.jar tui --line`
- Pipes a real query that triggers the new code path
- Pipes a verification command (e.g. `/metrics`)
- Asserts the output contains the expected state

The E2E is the *only* test that proves the change works against the
real daemon. The unit tests are necessary but not sufficient.

### 5. Docs (1-2 minutes)

- `user-guide/features.md`: 1 row in the table.
- `user-guide/keybindings.md` if a new chord was added.
- `user-guide/slash-commands.md` if a new `/cmd` was added.
- `api/jsonrpc.md` if a new RPC method/notification was added.
- `api/state-model.md` if the state shape changed.
- `changelog/RXX-RYY.md`: append a paragraph.

### 6. Build (30 seconds)

```powershell
cd D:\work\workspace\idea\engine\AetherCode\aethercode
.\build.ps1 -SkipTests       # Java + TUI bundle
```

This regenerates `dist/aethercode-0.2.1.jar` and `dist/ac-tui/ac-tui.js`.

### 7. Verify (30 seconds)

```powershell
# All TUI tests should pass
cd aethercode-tui
for f in scripts/test/r*.test.mjs; do node "$f" 2>&1 | tail -3; done

# E2E (if applicable)
cd ..
.\dist\aethercode-0.2.1.jar tui --line   # manually verify
```

## Example: adding R78 "getContext" RPC + `/context` TUI command

This is the actual workflow for the next round. Pre-write the plan:

- **What**: new RPC `getContext` returns the size of the model's current
  context (transcript size in tokens, available headroom). TUI surfaces
  it as `/context` (side note with a progress bar).
- **How**: `/context` slash command → calls `getContext` RPC.
- **Test**: reducer test for any new state fields + RPC contract test
  (E2E).
- **Docs**: `slash-commands.md` (+1 row), `jsonrpc.md` (+1 method),
  `features.md` (+1 row), `changelog/R78.md` (+1 paragraph).

## Anti-patterns

- **Bundle churn**: a round that touches 10+ files is suspicious. Split it.
- **Async reducer side effects**: the reducer is **pure**. RPC calls
  live in `useEffect` or event handlers.
- **Hardcoded state**: a round that hardcodes a value that should be
  configurable (e.g. "the model is always MiniMax-M3") is not user-facing.
- **Missing E2E**: a backend round without an E2E test is just
  self-deceiving unit tests.
- **Bumping the bundle without bumping tests**: a 100KB bundle
  delta with no test count delta means we added code but no contract.

## Test file naming

- `scripts/test/rXX-name.test.mjs` for unit tests.
- `scripts/test/e2e-rXX-name.mjs` for E2E (lower-case `e2e` so it
  sorts first in directory listings).
- `aethercode-core/src/test/java/.../RXXNameTest.java` for backend
  Java tests.

## Round numbering

- R1-R32: original work (pre-TUI redesign)
- R33-R62: TUI UX rounds (planned: 30)
- R63-R82: backend capability rounds (planned: 20)
- R83+: post-roadmap

The roadmap is in `ROADMAP-R33-R82.md` and the
`changelog/` directory has the per-round retros.
