# R109 — Auto-Suggest PermissionMode for New Projects

**Date**: 2026-08-18
**Status**: SHIPPED
**Round count**: 1
**Test delta**: +16 Java tests (12 `PermissionModeSuggesterTest` + 4 `PermissionModeSuggesterEngineTest`)

## Why R109

A new user on a fresh project (no `.aethercode/config.json`) is presented with the full set of `PermissionMode` values: `DEFAULT`, `ACCEPT_EDITS`, `BYPASS_PERMISSIONS`, `PLAN`, `AUTO_READ_ONLY`, `ACCEPT_TASK`. Without context, choosing between them is a guess. R109 adds a heuristic suggester that reads the project root and proposes a starting `PermissionMode` based on what it sees. The user is free to override via the existing `setPermissionMode` RPC.

## What R109 ships

1. **`PermissionModeSuggester.suggest(Path root)`** — three core heuristics:
   - **CI / automation project** — has `.git/` AND `.github/workflows/`, `.gitlab-ci.yml`, or `.circleci/config.yml`. Suggests `ACCEPT_TASK`: the user is iterating on a project that's already running in CI, so they want minimal mid-flow interruptions.
   - **Mature source project** — has `src/` AND `tests/` (or `test/`). Suggests `ACCEPT_EDITS`: the user is editing an established codebase with tests; auto-allow file writes so they can focus on review, but keep bash / network gated.
   - **Empty / unrecognised** — no `src/`, no tests. Suggests `DEFAULT`: the user is exploring, and prompts are cheap when there's no source to corrupt.
2. **`PermissionModeSuggester.Suggestion`** — record with `mode` (PermissionMode) and `reasons` (List<String>). Reasons are non-empty, immutable, and ordered by descending priority (the strongest signal first).
3. **`AetherCodeEngine.permissionModeSuggestion()`** — cached `Suggestion` computed at construction and re-computed on every `.aethercode/config.json` reload. May be null if the suggester threw.
4. **`AetherCodeMethods.getPermissionModeSuggestion(Object params)`** — new RPC. Returns the cached suggestion + the user's current mode. UI can render "💡 suggested: ACCEPT_TASK" next to the current mode and let the user accept via the existing `setPermissionMode` RPC.
5. **`getState.permissionModeSuggestion`** — surfaced in the state snapshot so polling clients see the suggestion without a dedicated RPC.

## Numerical results

| Module | Tests | Pass | Fail | Error | Skip |
|---|---:|---:|---:|---:|---:|
| aethercode-config | 126 (+12) | 126 | 0 | 0 | 0 |
| aethercode-permission | 105 | 105 | 0 | 0 | 0 |
| aethercode-sdk | 182 (+4) | 182 | 0 | 0 | 0 |
| aethercode-protocol | 89 | 89 | 0 | 0 | 0 |
| aethercode-bridge | 24 | 24 | 0 | 0 | 0 |
| aethercode-cli | 12 | 12 | 0 | 0 | 0 |
| (other modules) | ~3670 | 3670 | 0 | 0 | 0 |
| **Total (17 modules)** | **4208** | **4208** | **0** | **0** | **0** |

## New tests (16)

### `PermissionModeSuggesterTest` (12)

1. `suggest_nullRoot_returnsDefault` — null root -> DEFAULT with reason "no project root".
2. `suggest_emptyDirectory_returnsDefault` — empty dir -> DEFAULT with reason "empty directory".
3. `suggest_justReadme_returnsDefault` — README only -> DEFAULT with reason "no src/ or test/ directory".
4. `suggest_matureSourceProject_returnsAcceptEdits` — src/ + tests/ -> ACCEPT_EDITS.
5. `suggest_matureSourceProjectWithTestDir_returnsAcceptEdits` — src/ + test/ (singular) -> ACCEPT_EDITS.
6. `suggest_gitRepoWithCI_returnsAcceptTask` — .git/ + .github/workflows/ -> ACCEPT_TASK.
7. `suggest_gitLabCi_returnsAcceptTask` — .gitlab-ci.yml alone -> ACCEPT_TASK.
8. `suggest_matureProjectWithCI_prefersAcceptTask` — both heuristics match; ACCEPT_TASK wins; CI reason listed first.
9. `suggest_circleCi_returnsAcceptTask` — .circleci/config.yml -> ACCEPT_TASK.
10. `suggest_onlySrcNoTests_returnsDefault` — src/ but no tests/ -> DEFAULT (heuristic 2 needs both).
11. `suggest_isDeterministic_sameInputSameOutput` — two consecutive calls return equal mode + reasons.
12. `suggest_reasonsListIsImmutable` — the returned `reasons` list is `List.copyOf`'d (unmodifiable).

### `PermissionModeSuggesterEngineTest` (4)

1. `suggestion_matureProjectWithCI_isAcceptTask` — engine on src/ + tests/ + .github/workflows/ caches ACCEPT_TASK.
2. `suggestion_matureProjectNoCI_isAcceptEdits` — engine on src/ + tests/ (no CI) caches ACCEPT_EDITS.
3. `suggestion_emptyProject_isDefault` — engine on a project with only the .aethercode/ config caches DEFAULT.
4. `suggestion_isDeterministicAcrossEngines` — two engines on the same project return equal suggestions.

## Cross-cutting design lessons (R109)

1. **Mode-selection is separate from reason-collection.** The suggester first walks the heuristics to build a `reasons` list (pure data), then picks a mode based on which heuristics fired (pure logic). Decoupling these lets the UI render all the reasons (e.g. "CI project, mature source") even if the mode is determined by just one of them. The implementation reads top-to-bottom: collect reasons, then map heuristics to mode.

2. **CI is the strongest single signal.** A project with `.github/workflows/` is already in a CI loop — gating every tool call is friction. The 2-reason case (CI + mature source) is even stronger, but CI alone is enough to suggest `ACCEPT_TASK`. This matches the user's mental model: "if I've already set up CI, I'm not in an exploratory phase".

3. **Reasons are listed in descending priority.** The CI reason is listed before the src+tests reason when both apply. The UI can render the first reason as the headline ("CI project") and the rest as supporting context. Without the ordering, the UI would have to do the prioritisation itself, leaking the suggester's design into the UI.

4. **The suggester is conservative — DEFAULT is the safe fallback.** The suggester never suggests `BYPASS_PERMISSIONS` or `AUTO_READ_ONLY`. These are user choices that should not be auto-recommended from a directory listing. Even a "mature project with CI" gets `ACCEPT_TASK`, not `BYPASS_PERMISSIONS`, because the user can still be surprised by what their tooling does.

5. **The cached suggestion re-runs on config reload.** A config reload usually means the user is reshaping the project, so the suggestion may have shifted. Without the re-run, the suggestion would be frozen at engine boot, which is wrong for a long-running daemon.

6. **`null` suggestion is a valid state.** If the suggester throws (e.g. cwd is a non-existent path), the engine stores null and the UI shows no suggestion. We don't fail engine construction over a missing project root — the user might be in a transient state (cloning a repo, etc.).

7. **The list is `List.copyOf`ed in the record's compact constructor.** This makes the returned `reasons` list immutable without forcing every caller to wrap it. The `PermissionModeSuggesterTest.suggest_reasonsListIsImmutable` test pins this behavior.

## Files (R109)

- NEW: `aethercode-config/.../PermissionModeSuggester.java` (~5.4 KB, 3 heuristics)
- NEW: `aethercode-config/.../PermissionModeSuggesterTest.java` (12 tests)
- EDIT: `aethercode-sdk/.../AetherCodeEngine.java` (+35 lines: cached field, accessor, recompute helper, constructor call, config-reload call)
- NEW: `aethercode-sdk/.../PermissionModeSuggesterEngineTest.java` (4 tests)
- EDIT: `aethercode-protocol/.../AetherCodeMethods.java` (+50 lines: getPermissionModeSuggestion RPC, getState.permissionModeSuggestion field, dispatcher registration)

## Cumulative R1-R109 (AetherCode)

- 4208 Java tests (+16 R109)
- 12 vitest in R104
- 9 node `--test` in R103
- 17 reactor modules
- 0 known regressions
- New capabilities: heuristic permission-mode suggestion, R99's pre-existing setter bug fixed, skip-low waterline warning, per-tool skip adoption stats

## R109+ follow-up candidates

- **R97-J**: Tauri App multi-session picker UI
- **R97-K**: `/prompt` 加 diff
- **R97-L**: Session resume
- **R97-N**: per-session cwd override
- **R97-O**: 修 `aethercode-prompts/.../SystemPrompt.java` line 184 em-dash 还原
- **ListenerChain refactor**: lift the 3-listener footprint (R99 + R107 + R108) into a single "install all engine listeners" method
- **TUI/Desktop wiring for skip-low**: badge `⏩ skip: 2 (low!)` based on `lastSkipLow.remaining <= lowWaterline`
- **TUI/Desktop wiring for permissionModeSuggestion**: render "💡 suggested: ACCEPT_TASK" next to the current mode with an "accept" button
- **More heuristics**: monorepo detection (multiple `package.json` / `pom.xml` at different levels), Docker detection (`Dockerfile`), language detection (Go, Rust, Python) — but the 3-heuristic core is a more stable foundation to extend
- **Confidence score**: each reason could carry a weight (0.0-1.0), and the final mode is a function of the total weight. R109's simple boolean-reason approach is more transparent but doesn't expose the relative strength of each signal.
