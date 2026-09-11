# R113 — Model Dropdown Fix + SessionList Defensive Rendering

**Date**: 2026-08-19
**Status**: SHIPPED
**Round count**: 1
**Test delta**: +13 vitest tests (76 → 89)

## Why R113

User-reported issues from the desktop app:

1. **Model dropdown only shows 3 unknown models.** The desktop's `MessageInput.tsx` had a hard-coded `MODELS = ['claude-opus-4-1', 'claude-sonnet-4-5', 'claude-haiku-4-5']` array — completely ignoring the daemon's `listProviders` RPC that returns every model across every provider (minmax, anthropic, openai, glm, qwen, deepseek, gemini + custom `providers.yaml`).
2. **No minmax support visible.** The default provider is minmax, but the user couldn't pick it from the dropdown because the list was hard-coded to 3 anthropic models. The store had a separate `model` field defaulting to `'claude-sonnet-4-5'` in 3 places.
3. **Session list shows count=1 but renders empty.** The `SessionList.tsx` calls `s.id.slice(-8)` on every entry. Entries with `id: null` (or undefined) silently rendered an empty string. The user saw the count badge say "1" but no row below it — confusing.

## What R113 ships

1. **`MessageInput.tsx` uses `availableProviders` from the store.**
   - `useEffect(() => { if (isConnected) void refreshProviders(); }, [...])` refreshes the provider list on connect.
   - `modelEntries = useMemo(...)` flattens the providers into `{id: "provider/modelId", label: "provider / modelId", provider}` triples, with the current provider sorted first.
   - The `<select>` renders `modelEntries.map(...)` instead of a hard-coded array.
   - `onChange` splits `provider/modelId`, calls `switchProvider(newProvider, newModel)` if the provider changed, otherwise just `setModel(newModel)`.
2. **`MessageInput.tsx` imports `ProviderInfo` from `lib/methods` for type safety.**
3. **`store/index.ts` removes 3 hard-coded `'claude-sonnet-4-5'` defaults.** All 3 places now default to `''` (empty string) and let the daemon's `getState` populate the real value on first connect.
4. **`store/index.ts` types `availableProviders` as `ProviderInfo[]`** (was `any[]`).
5. **`SessionList.tsx` is defensive against bad entries.**
   - `sessionLabel(s)` checks `typeof id === 'string' && id.length > 0` before calling `.slice()`. Returns `'session'` for entries without an id.
   - The `sorted.map(...)` body checks `hasId` first; entries without an id render a placeholder row `(invalid session entry)` so the user can see *something* is in the list (matches the count badge).

## Numerical results

| Test runner | Tests | Pass | Fail | Error | Skip |
|---|---:|---:|---:|---:|---:|
| vitest (R113 new files) | 13 | 13 | 0 | 0 | 0 |
| vitest (R104, R111, R112 preserved) | 76 | 76 | 0 | 0 | 0 |
| **vitest total** | **89** | **89** | **0** | **0** | **0** |
| TypeScript typecheck | — | — | 0 | 0 | 0 |
| Desktop exe re-built | 3.5 MB | — | — | — | — |
| BUILD SUCCESS for 17 Java modules | — | — | — | — | — |

## New tests (13)

### `MessageInputR113.test.ts` (9)

1. `MessageInput.tsx no longer hardcodes 3 claude models`
2. `MessageInput.tsx wires availableProviders + refreshProviders from the store`
3. `MessageInput.tsx calls refreshProviders on connect`
4. `MessageInput.tsx renders the dropdown from modelEntries (flattened providers)`
5. `MessageInput.tsx splits the value on slash and calls switchProvider when provider changes`
6. `MessageInput.tsx imports ProviderInfo type`
7. `store types availableProviders as ProviderInfo[] (not any[])`
8. `store no longer hardcodes claude-sonnet-4-5 as default model`
9. `store reads model from getState (no hardcoded fallback)`

### `SessionListR113.test.ts` (4)

1. `SessionList.tsx sessionLabel defends against non-string id`
2. `SessionList.tsx renders an invalid-entry placeholder when id is missing`
3. `SessionList.tsx filters by hasId before calling handleSwitch / handleDelete`
4. `SessionList.tsx still has a sessionLabel function`

## Cross-cutting design lessons (R113)

1. **Hard-coded lists are a maintenance trap.** `MODELS = ['claude-opus-4-1', 'claude-sonnet-4-5', 'claude-haiku-4-5']` was added pre-R109 when there was no listProviders RPC. R109 added the RPC, but the renderer wasn't updated. The user couldn't see minmax / glm / qwen / deepseek / gemini at all — only 3 anthropic models. **Lesson**: every hard-coded list of "things the daemon knows about" is a code smell after a `list*` RPC ships. A grep for `const.*=.*\[` in the renderer should be a release-blocker check.

2. **The daemon is the source of truth for the model list.** The desktop app should NEVER carry its own model list. The wire format (provider + model id) is the daemon's domain; the renderer just displays it. R113 fixes this by reading from `availableProviders` (populated by `listProviders`).

3. **Defensive rendering prevents silent failures.** A session entry with `id: null` rendered an empty `<li>` — invisible to the user but counted in the badge. R113's `hasId` check renders a placeholder so the user can see *something* is there. **Lesson**: when a count badge is "1" but the list is empty, that's a "you have a defensive-rendering bug" signal.

4. **Three places had `'claude-sonnet-4-5'` hard-coded.** The store's model default was repeated in 3 different paths (engineState default, init state, getState fallback). R113 fixes all 3 in one round. A grep for `'claude-sonnet-4-5'` would have caught the other 2 — but we only saw the user-facing bug in the dropdown. **Lesson**: when you find a hard-coded value, grep the whole codebase for it before declaring the fix done.

5. **Slash-separated `provider/model` in the dropdown value is the right pattern.** The user picks "anthropic/claude-sonnet-4-5" from the dropdown; we split on `/` to get the provider and the model. The provider change triggers `switchProvider` (which rebuilds the chat client); the model-only change just calls `setModel`. This separates the two RPCs cleanly.

6. **`availableProviders` should be typed as `ProviderInfo[]`, not `any[]`.** Pre-R113 the store typed it as `any[]` because the developer didn't have the type imported. R113 fixes this — TypeScript then catches any drift between the wire format and the renderer's expectations.

## Files (R113)

- EDIT: `aethercode-desktop/src/components/MessageInput.tsx` (-12 lines hardcoded `MODELS`, +30 lines `useMemo`/`useEffect`/dynamic `<select>`)
- EDIT: `aethercode-desktop/src/store/index.ts` (3 hardcoded `'claude-sonnet-4-5'` removed, `availableProviders: ProviderInfo[]`)
- EDIT: `aethercode-desktop/src/components/SessionList.tsx` (defensive `hasId` check, `sessionLabel` type guard)
- NEW: `aethercode-desktop/src/components/MessageInputR113.test.ts` (9 tests, source-only)
- NEW: `aethercode-desktop/src/components/SessionListR113.test.ts` (4 tests, source-only)

## Cumulative R1-R113 (AetherCode)

- 4216 Java tests (no change in R113)
- 89 vitest (+13 R113)
- 23 node `--test`
- 17 reactor modules
- 0 known regressions
- New capabilities: model dropdown shows every provider's models; session list renders defensive placeholder for bad entries

## R113+ follow-up candidates

- **R114**: TUI/Desktop wire the skip-low + permission suggestion UI to the engine (already R112; verify the R113 model dropdown doesn't break the wiring)
- **R115**: Loop-detection diagnostics — the user's "file_read repeated 3 times" is a model-side issue, not AetherCode's. A counter for "tool X called Y times in last N turns" would surface this in the StatusBar.
- **Hard-coded grep CI check**: add a CI lint that fails if `claude-sonnet-4-5` or any other hard-coded model name appears in the renderer code. Caught 2 of the 3 hard-coded places by hand; CI would catch the third on the first commit.
- **sessionStore.list() shape drift**: `lastModified` returns ms epoch; `messageCount` is a file-size proxy (`sizeBytes / 800`). A future round could compute the real count by reading the JSONL header.
