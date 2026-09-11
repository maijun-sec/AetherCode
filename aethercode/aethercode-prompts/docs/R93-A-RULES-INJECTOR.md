# R93-A: Rules Injector (2026-08-16)

## Goal

Let the user drop Markdown files into `.aethercode/rules/` (project) or
`~/.aethercode/rules/` (global) and have them auto-injected into the system
prompt on every engine boot. The feature ships without any new tool surface,
any new JSON-RPC method, or any UI change — the rules are simply baked into
the prompt that the model already sees.

## Why now

`aethercode-prompts/SystemPrompt` already had a clean builder with
`identity / environment / tooling / workflow / planMode / memory` slots, and
`.aethercode/workflows/` was already a recognized config directory. The
missing piece was a "user policy" slot for the model. Adding it as a new
prompt section — rather than bolting it onto `memory` or `workflow` — keeps
each existing slot semantically clean and makes the new behaviour testable
in isolation.

## Design

### File discovery

For every engine boot, `RulesLoader.load(projectCwd, userHome)` reads two
directories in order, each independent of the other:

1. `<projectCwd>/.aethercode/rules/*.md` (and `*.markdown`, `*.txt`)
2. `<userHome>/.aethercode/rules/*.md`  (and `*.markdown`, `*.txt`)

Both lists are sorted by file name (case-sensitive, lexicographic) so the
order is deterministic across platforms. Subdirectories and unknown
extensions are silently skipped; empty / whitespace-only files are
skipped. A file that fails to read (permission, transient IO) is logged at
`warn` and the loader continues with the rest of the layer.

### Rendering

The two layers are concatenated with a small section header:

```
# Project rules

### a.md
contents of a.md

### b.md
contents of b.md

# Global rules

### shared.md
contents of shared.md
```

The total size is hard-capped at `RulesLoader.MAX_RULES_CHARS = 32 KiB`.
When the cap is hit, the output is truncated and a `... (truncated, total
rules content exceeds 32768 chars) ...` marker is appended so the model
knows the cap was applied rather than treating the cutoff as a logical
ending.

### Prompt placement

`SystemPrompt.render()` now writes the sections in this order:

1. identity (default agent role)
2. **rules (R93-A) — user policy**
3. environment (cwd / OS / date)
4. tooling (tool list + schemas)
5. workflow (general guidance)
6. planMode (when in plan mode)
7. memory (per-query recall)

Rules go right after identity and before environment on purpose: the
model should see user policy before it sees any platform context, so the
policy is the first thing that constrains the model. An empty rules
value produces no section at all (no orphan blank lines, no header).

### Engine integration

`AetherCodeEngine.defaultSystemPrompt` now does:

```java
String rules = org.aethercode.prompts.RulesLoader.load(b.cwd);
return SystemPrompt.builder()
        .environmentFrom(b.cwd, System.getProperty("os.name"))
        .toolingFrom(b.tools)
        .rules(rules)
        .build()
        .render();
```

This is the only call site that changed. The rules string is computed
once per engine build and folded into the cached prompt that
`QueryEngine` keeps for the lifetime of the session, so there is no
per-turn cost.

### Failure mode

`RulesLoader` is intentionally side-effect free. Any of these is silent
(no exception thrown to the engine):

- `projectCwd` is null or not a directory
- `userHome` is null or not a directory
- The rules directory exists but is empty
- A file is unreadable (logged at `warn`, that file is skipped)
- Total content exceeds the cap (truncated with marker)

The only error path is the in-memory `renderForTest` helper, which throws
`IllegalArgumentException` when its file list and content list lengths
disagree — that is a programming error in test code, never a runtime
condition.

## Files

### New
- `aethercode-prompts/src/main/java/org/aethercode/prompts/RulesLoader.java`
  (9471 bytes)
- `aethercode-prompts/src/test/java/org/aethercode/prompts/RulesLoaderTest.java`
  (9195 bytes, 17 tests)
- `aethercode-prompts/src/test/java/org/aethercode/prompts/SystemPromptTest.java`
  (3281 bytes, 7 tests)
- `aethercode-prompts/docs/R93-A-RULES-INJECTOR.md` (this file)

### Modified
- `aethercode-prompts/src/main/java/org/aethercode/prompts/SystemPrompt.java`
  — new `rules` field on `Builder`, new `rules(String)` setter, new
  `rules()` accessor, render-order updated to insert rules between
  identity and environment, class-level doc comment updated
- `aethercode-sdk/src/main/java/org/aethercode/sdk/AetherCodeEngine.java`
  — `defaultSystemPrompt` now calls `RulesLoader.load(b.cwd)` and passes
  the result to the builder

## Tests

- `mvn -o test` on `aethercode-prompts`: **24/24 pass** (17 RulesLoader
  + 7 SystemPrompt)
- `mvn -o test` on `aethercode-sdk`: **108/108 pass**, no regression

Test coverage of the `RulesLoader` API:

| Scenario | Test |
|---|---|
| Both directories missing | `load_returnsEmptyWhenNoDirsExist` |
| Both directories present but empty | `load_returnsEmptyWhenDirsAreEmpty` |
| Project cwd is null | `load_nullCwd_skipsProjectLayer` |
| User home is null | `load_nullHome_skipsGlobalLayer` |
| Both null | `load_bothNull_returnsEmpty` |
| Single project file | `load_singleFile_isWrappedInHeader` |
| Multiple files (sorted by name) | `load_multipleFiles_areSortedByName` |
| Project + global layer | `load_projectAndGlobalAreBothRenderedInOrder` |
| Extension filter (.md / .markdown / .txt) | `load_filtersByExtension` |
| Whitespace-only files skipped | `load_skipsEmptyFiles` |
| Subdirectories skipped | `load_skipsSubdirectories` |
| 32 KiB cap + marker | `renderForTest_truncatesAtCap` |
| Under cap, no marker | `renderForTest_doesNotTruncateWhenUnderCap` |
| Mismatched lists | `renderForTest_mismatchedLengths_throws` |
| Empty content skipped in renderForTest | `renderForTest_skipsEmptyContent` |
| Both layers in renderForTest | `renderForTest_layersBothProjectAndGlobal` |
| Empty both in renderForTest | `renderForTest_emptyBoth_returnsEmpty` |

Test coverage of the `SystemPrompt` rules integration:

| Scenario | Test |
|---|---|
| Default rules is empty, no section in render | `rules_defaultIsEmpty_andRenderOmitsSection` |
| Null clears the section | `rules_nullClearsSection` |
| Inserted between identity and environment | `rules_isRenderedAfterIdentity_andBeforeEnvironment` |
| Inserted before memory | `rules_isRenderedBeforeMemory` |
| Blank value does not appear in render | `rules_blankValue_doesNotAppearInRender` |
| Last setter wins | `rules_multipleValues_lastOneWins` |
| Identity default preserved | `rules_doesNotDisturbIdentityAccessor` |

## Usage

```bash
# Drop a rule into the current project
mkdir -p .aethercode/rules
cat > .aethercode/rules/style.md <<'EOF'
# Code style

- Use tabs for indentation
- Prefer explicit types over `var` in public APIs
- Always include a copyright header
EOF

# Drop a global rule that applies to every project
mkdir -p ~/.aethercode/rules
cat > ~/.aethercode/rules/safety.md <<'EOF'
# Safety

- Never run `rm -rf` on `/`
- Never commit secrets, even when asked
EOF

# Run any engine / TUI / desktop / daemon. The next system prompt
# will include both files (project first, then global), and the
# model will follow them on every turn.
java -jar aethercode-0.2.1.jar
```

## Build

- `aethercode-0.2.1.jar` 42 MB, 2026-08-16 21:41, contains
  `org/aethercode/prompts/RulesLoader.class` and the updated
  `SystemPrompt$Builder.class`
- `dist/aethercode-0.2.1.jar` already replaced; downstream TUI /
  desktop / CLI will pick it up on next launch

## Out of scope (deferred)

- **Live reloading**: rules are loaded once at engine boot. Hot-reload
  is non-trivial because the prompt is cached for prompt-cache warmth;
  deferred to a future round.
- **`.aethercode/rules/index.md` TOC file**: would let the user
  declare load order across many small rule files. Skipped because the
  current alphabetical sort is usually what users want.
- **Per-rule enable / disable flags**: a header like
  `<!-- aethercode: disabled -->` to skip a file. Not yet asked for.
- **Per-agent-role rules**: a `role/rules/` subdirectory picked up
  only when running that agent role. The model already has
  `aethercode-tasks` per-task scopes, but the rules pipeline is
  process-global right now. Defer until a real ask lands.
- **Secret scanning**: rules files are loaded verbatim and inserted
  into the prompt. A user who pastes a secret into a rule file is
  trusting the model not to leak it. We do not scan rule files for
  this — it is the same risk model as `~/.aethercode/memory/`, which
  has the same shape.
