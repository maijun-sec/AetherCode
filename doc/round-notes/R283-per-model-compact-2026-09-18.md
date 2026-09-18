# R283 — per-model compaction configuration (2026-09-18)

## TL;DR

Per-model compaction config. Each `ModelSpec` (and its provider's
fallback) now carries an explicit `compact` block with
`contextWindow` / `compactAt` / `preserveTail` / `strategy`. The
engine consults the registry every turn — switching models mid-session
flips the gate without a daemon restart. No desktop changes; the
backend now returns a `compact` block per model in
`listAvailableModels` so a future picker UI can show "this model
compacts at 115k tokens" right next to the model name.

---

## What changed

### New types

- **`org.aethercode.core.compact.CompactConfig`** — runtime record
  with `contextWindow` / `compactAt` / `preserveTail` / `Strategy`
  (`summary8` / `summary7` / `summarySliding` / `disabled`).
  `forContextWindow(int)` returns a tier default:
  - ≤ 80k → `summary7` + 5k buffer (clamped to ctx/4)
  - ≤ 200k → `summary8` + 16k buffer
  - > 200k → `summary8` + 80k buffer
  `compactAt = Math.max(1, ctx - buffer)` so very small contexts
  (test fixtures) still resolve a positive gate.
- **`org.aethercode.core.providers.CompactSpec`** — YAML-shape record
  with **nullable** `compactAt` / `preserveTail` / `strategy` so
  users can override one knob at a time. `toConfig()` fills the
  missing fields from the tier default.

### Provider registry

- `ProviderSpec` adds a 7th constructor arg `CompactSpec compact`.
  Legacy 6-arg overloads delegate with `compact=null`. New
  `compactFor(modelId)` walks:
  1. `ModelSpec.compact`
  2. `ProviderSpec.compact`
  3. `CompactConfig.forContextWindow(contextWindow())` — per-provider
     tier default derived from the largest model on the spec
  4. `CompactConfig.DEFAULT` (200k tier) — last resort
- `ModelSpec` adds a 7th constructor arg `CompactSpec compact`.
  Legacy 5/6-arg overloads delegate with `compact=null`.
- `ProviderRegistry` gains `ProviderYaml.compact` / `ModelYaml.compact`
  plus a `CompactYaml` inner class with `merge(...)` so provider-level
  + model-level blocks can be combined at load time.

### Engine plumbing

- `QueryEngine` gets `compactRegistry` / `currentProviderName` /
  `currentModelId` volatile fields, `setCompactRegistry(...)` /
  `setCurrentModel(...)` setters, and a `resolveCompactConfig()`
  three-way fallback that prefers the registry, then
  `appState.contextWindow()` (legacy boot path), then returns
  `null` to skip compact.
- `runPreFlightCompact()` now reads `cfg.compactAt()` (absolute
  token count) as the gate, with `AETHERCODE_COMPACT_THRESHOLD`
  acting as a *fraction override* (e.g. `0.7` → compact at 70% of
  `compactAt` → earlier compaction). When the env var is unset, the
  per-model `compactAt` is the gate — matching the YAML declaration
  rather than the legacy 90% default.
- Side-note log line now shows `strategy=summary8 preserveTail=4`
  so the user can see exactly which knobs fired.
- `AetherCodeEngine` exposes `setCompactRegistry(...)` and
  `mainLoopModelName(...)` propagation; the SDK hands the registry
  to `QueryEngine` on the next compact call.
- `AetherCodeMethods.listAvailableModels` now returns a `compact`
  block per model (`contextWindow`, `compactAt`, `preserveTail`,
  `strategy`) so the renderer's model picker can show the
  compaction behaviour without an extra round-trip.
- `AetherCodeMethods.switchProvider` propagates the new model to
  the engine via `mainLoopModelName(...)` so the gate flips the
  next time `runPreFlightCompact()` runs.

### Verification

`scripts/verify_jar_r277_fix.py` gains 11 R283 bytecode markers
(31 total). Run after `mvn package` and before `zip-r280.py`.

---

## YAML schema example

```yaml
providers:
  - name: glm
    type: openai-compat
    baseUrl: https://open.bigmodel.cn/api/paas/v4
    apiKeyEnv: GLM_API_KEY
    defaultModel: glm-4-plus
    models:
      - id: glm-4-flash
        context: 128000
        maxOutput: 128000
        # compact block optional — falls back to provider-level,
        # then to the tier default for a 128k window
      - id: glm-4-plus
        context: 128000
        maxOutput: 128000
        compact:
          contextWindow: 128000
          compactAt: 115000       # optional; falls back to tier default
          preserveTail: 4         # optional
          strategy: summary8      # optional
    compact:                     # provider-level default
      contextWindow: 128000
      compactAt: 110000
      preserveTail: 3
      strategy: summary7
```

---

## Tests

- **QueryEnginePreFlightCompactR283Test** (7 tests, all pass)
  - `perModelCompactAt_isTheGate_notNinetyPercentOfWindow` — 200
    tokens well under GLM's 115k gate → no compact
  - `perModelCompactAt_overThresholdTriggersCompact` — pump to
    116k → compact fires once, summary message has
    `kind=summary-stub`
  - `switchingProviderMidStreamFlipsTheGate` — same 60k transcript
    stays below GLM's 115k but crosses DeepSeek's 58k → compact
    fires only after the switch
  - `providerLevelCompactIsFallbackForModelWithoutOwnBlock` —
    sibling model with no `compact` block picks up the provider's
  - `unknownModelFallsBackToTierDefault` — querying an unregistered
    model returns `CompactConfig.DEFAULT` (200k tier)
  - `compactConfigValidationRejectsInvertedPair` —
    `CompactSpec(100k, 120k, ...)` rejected with message
    containing "compactAt"
  - `compactConfigValidationRejectsNonPositiveWindow` —
    `CompactSpec(0, 1, ...)` rejected with message containing
    "contextWindow"
- **Full regression** — 1168 core + 285 protocol tests pass.
- **Fix during build** — initial test draft had a bogus
  `assertEquals(0, ((RecordingCompactor) app.transcript().get(0).content().get(0).text().length() == 0 ? 0 : 0), ...)`
  (the `ContentBlock` interface has no `.text()` accessor —
  only the `TextBlock` variant does, and the cast was wrong).
  Replaced with `assertEquals(0, compactor.callCount, ...)` so the
  test actually exercises the gate rather than passing
  unconditionally.

---

## Deploy

- **jar** — 56,616,235 B (R283, +8,689 B vs R282)
- **zip** — SHA `68C662A2D9400C1D61BFC73CFE57B4D3D71BF6BC56AE32A04A8A5A0A41E9A8F5`,
  112,346,725 B (+15.9 KB vs R282)
- **exe** — unchanged from R282 (4,161,024 B; no desktop changes
  in R283)
- **verify_jar_r277_fix.py** — 31/31 bytecode markers pass
  (3 R277 + 8 R280 + 5 R281 + 4 R282 + 11 R283)
- **commit** — TBD; push expected to need cron retry based on
  recent rounds.

---

## Key decisions

1. **Three-tier fallback** — model → provider → DEFAULT. Lets a
   provider declare a default for every model it exposes (glm-4
   family all sharing 128k) while still letting a single model
   override (e.g. GLM-4-plus using summary8 + 4 tail, while its
   siblings fall back to the provider's summary7 + 3 tail).
2. **Absolute token `compactAt`, not fraction** — Claude Code
   exposes `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE` (fraction); we expose
   absolute tokens so the safety margin can be tuned per-model
   (e.g. leave 13k headroom on a 200k Claude, 13k on a 128k GLM).
3. **`AETHERCODE_COMPACT_THRESHOLD` as fraction override** — when
   unset, the per-model `compactAt` is the gate. When set, the
   env value multiplies against the model's `compactAt` so power
   users can dial compaction earlier (0.7) or later (0.95) without
   touching providers.yaml.
4. **Strategy enum on the wire** — `summary8` / `summary7` /
   `summarySliding` / `disabled`. The `wire()` string is the YAML
   key AND the RPC field, so a typo at the YAML level silently
   falls back to `summary8` (in `fromWire`) rather than crashing
   the daemon.
5. **Tier defaults instead of forcing every provider to fill the
   block** — `forContextWindow(int)` returns a sensible default
   for each window size, so a brand-new provider doesn't need to
   know the precise buffer math. The provider just declares
   `contextWindow`.
6. **`Strategy.DISABLED` opt-out** — for very-small models
   (ollama, tiny contexts) where the compactor summary would cost
   more tokens than it saves. The model is left to fail on its
   own when context overflows; user is opting in knowingly.
7. **`CompactSpec.compactAt` is `Integer` (nullable)** — so users
   can override just `preserveTail` or just `strategy` without
   having to repeat `compactAt`. The inverted-pair guard only
   fires when both fields are present.

---

## What's next (R284+)

- **R284**: snapshot the pre-compact context to
  `<sessionId>.jsonl.snapshot.<N>` so users can view the original
  context that produced a summary (existing `SnapshotStore`
  already provides the JSONL persistence — just need to call it
  from `CompactGate.compact()`).
- **R285**: model variants (claude-code-style low/medium/high/xhigh
  + opencode-style alias) so each model can declare multiple
  pre-configured option sets and the user picks one via
  `/model y:y` syntax.
- **R286+**: Settings "Show all providers" toggle (R282 follow-up),
  per-model pricing display, providers.yaml in-app editor, live
  env-var refresh button.

---

## Lessons (R283)

- **CompactSpec must validate inverted pair up-front** — deferring
  to the runtime record means tests like
  `new CompactSpec(100k, 120k, ...)` never throw, leaving the
  gate BEYOND the window. The compact spec is the YAML entry
  point; fail at boot, not at the first compact call.
- **Java record-component field names shadow local vars** — using
  `_compactAt` / `_preserveTail` for the locals and `this.compactAt`
  for the field avoids the compile error about `int != <null>` on
  the `Integer` type when the auto-generated accessor is in scope.
- **`ContentBlock.text()` doesn't exist** — the sealed interface
  has only `type()`. The `.text()` accessor lives on the
  `TextBlock` variant. Drafting tests without that knowledge leads
  to a copy-paste that compiles-looking-OK in the IDE and fails
  on the first test run; the safer fix is to assert on observable
  side effects (`callCount`, `transcript.size()`) rather than
  peeking at message internals.