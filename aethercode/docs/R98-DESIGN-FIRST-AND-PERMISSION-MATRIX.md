# R98: Design-First Workflow + Per-Tool-Type Permission Matrix + Skip Confirmation

**Status**: SHIPPED 2026-08-18  
**Type**: AetherCode platform-level capability (project-agnostic)  
**Build**: 4054 Java tests, 0 failures, 0 errors.  

## Goal

AetherCode gets three new project-level capabilities that any project
can opt into by dropping a `.aethercode/config.json` into its root:

1. **Design-first workflow** — for non-trivial tasks the model proposes a
   design (objective, approach, rounds, risks, confirmation point) BEFORE
   calling any other tool. Each round ends with a checkpoint. Users can
   chain rounds by saying "no confirmation needed for next N rounds".
2. **Per-tool-type permission matrix** — `.aethercode/config.json`
   declares a three-dimensional matrix: `tool × path × op-kind → ALLOW /
   ASK / DENY`. Resolution order is `deny rules → matrix → ask rules →
   allow rules → mode fallback`. The matrix wins over session mode for
   `DENY` and `ALLOW`; `ASK` falls through to the existing
   rules/mode chain.
3. **Skip confirmation** — a per-session counter (RPC
   `setSkipConfirmation`) suppresses the prompt for the next N
   tool calls. The counter is decremented per call and resets to 0
   automatically. Two trigger paths: explicit RPC, and
   prompt-text auto-detection (e.g. "no confirmation needed for next
   5 rounds").

All three are platform-level features. No RAG, no D:\tmp, no
project-specific work. Any project the user opens can use them.

## What's new

### 1. New module: `aethercode-config`

`aethercode-config/src/main/java/org/aethercode/config/`:

- `OpKind` — enum: READ / LIST / CREATE / MODIFY / DELETE / EXEC
- `Action` — enum: ALLOW / ASK / DENY
- `PermissionMatrix` — the three-dimensional matrix with first-match-wins
  path-glob resolution
- `AetherCodeConfig` — parsed top-level schema
- `ConfigEngine` — loader (`loadFrom`, `loadFromProjectRoot`)
- `PathBucket` — logical path classification (SRC_MAIN, SRC_TEST,
  BUILD, CACHE, CONFIG, DOCS, EXTERNAL, OTHER)
- `OpKindDetector` — classifies a tool + input into an OpKind
- `SkipConfirmationRegistry` — per-session counter
- `SkipConfirmationDetector` — parses "no confirmation needed for next
  N rounds" patterns in user prompts
- `defaults/DefaultMatrix` — built-in safe defaults covering 8 standard
  tools

### 2. New: `MatrixPermissionPolicy` in `aethercode-permission`

Extends `ProjectPermissionPolicy`. Consults the matrix BEFORE the
existing deny/ask/allow rule list. Short-circuits on DENY (immediate
deny) and ALLOW (immediate allow). On ASK, falls through to the
inner policy. Trust read-only tools (`tool.isReadOnly(input) == true`)
and short-circuit them to Allow without consulting the matrix.

The wrapper supports `withMatrix(newMatrix)` (immutable swap, like
`withMode`).

### 3. System prompt: `designFirst` section

A new section inserted between `tooling` and `workflow`. Defines when
to design-first, when to skip, the round template, and the
"no confirmation needed for next N rounds" escape hatch. Default
content is in `SystemPrompt.defaultDesignFirst()`. Renderable via
`renderWithSources()` (the `designFirst` section appears between
`tooling` and `workflow`).

The section is auto-disabled when `.aethercode/config.json` has
`"workflow": "legacy"`.

### 4. JSON-RPC: `setSkipConfirmation`

```
Request:  {"jsonrpc":"2.0","id":N,"method":"setSkipConfirmation",
           "params":{"sessionId":"...","rounds":5}}
Response: {"jsonrpc":"2.0","id":N,"result":{"ok":true,
            "sessionId":"...","rounds":5,"remaining":5}}
```

`rounds <= 0` clears the counter. The engine resolves
`sessionId` via `resolveRpcTarget(sid)` (R97-G pattern).

### 5. Auto-detect hook on `QueryEngine`

A new `onUserPrompt(Consumer<String>)` hook fires on every user
prompt BEFORE the LLM stream starts. The engine installs a hook
that calls `SkipConfirmationDetector.detect(prompt)` and, if a
positive count is found, arms the registry for the active session.
Hook failures are logged at WARNING and the query continues.

### 6. Wiring in `Main.java`

```java
AetherCodeEngine.Builder b = AetherCodeEngine.builder()
        ...
        .permissionMode(permissionMode)
        .permissions(perms)
        .permissionMatrix(
            org.aethercode.config.ConfigEngine.loadFromProjectRoot(cwd)
                    .permissionMatrix)   // <-- new
        ...
```

`ConfigEngine.loadFromProjectRoot(cwd)` reads
`<cwd>/.aethercode/config.json` and falls back to `DefaultMatrix` if
the file is missing or invalid. **Never throws** — a broken config is
logged at WARN and the safe default is used.

## Resolution order (full picture)

```
For each tool call:

1. OpKindDetector.detect(tool, input, projectRoot) -> OpKind
2. extractPath(tool, input) -> path
3. matrix.lookup(tool, path, opKind) -> Action
4. If tool.isReadOnly(input)           -> ALLOW (defence in depth)
5. If matrix == DENY                    -> DENY (return immediately)
6. If matrix == ALLOW                   -> ALLOW (return immediately)
7. If matrix == ASK
   7a. If skipRegistry.consumeOne(sid)  -> ALLOW (decrement counter)
   7b. Else fall through to ProjectPermissionPolicy.check:
        i.   deny rules
        ii.  ask rules
        iii. allow rules
        iv.  mode fallback (BYPASS, ACCEPT_EDITS, AUTO_READ_ONLY
             -> ALLOW; DEFAULT, PLAN -> ask; ACCEPT_TASK -> ask
             on sub-task boundary)
```

## Schema

`.aethercode/config.json`:

```json
{
  "version": 1,
  "permissionMatrix": {
    "file_write": {
      "src/main/**":    { "CREATE": "ASK",   "MODIFY": "ASK",   "DELETE": "DENY" },
      "src/test/**":    { "CREATE": "ALLOW", "MODIFY": "ALLOW", "DELETE": "ASK"  },
      "*.md":           { "CREATE": "ASK",   "MODIFY": "ASK" },
      "package.json":   { "*":      "ASK" },
      ".aethercode/**": { "*":      "DENY" },
      "*":              { "*":      "ASK" }
    },
    "file_edit": { ... },
    "bash":      { "*": { "DELETE": "DENY", "CREATE": "ASK", "MODIFY": "ASK", "READ": "ALLOW", "EXEC": "ASK" } },
    "file_read": { "*": { "*": "ALLOW" } },
    "web_fetch": { "*": { "*": "ALLOW" } }
  },
  "skipConfirmation": false,
  "skipConfirmationRounds": 0,
  "workflow": "design-first"
}
```

- `permissionMatrix`: tool → path-glob → op-kind (or `*`) → Action.
  First match wins. Missing fields fall back to ASK.
- `skipConfirmation`: `true` arms the engine for infinite
  skip-on-ask. Equivalent to `rounds: Integer.MAX_VALUE`.
- `skipConfirmationRounds`: initial counter applied at engine boot.
- `workflow`: `"design-first"` (default) includes the design-first
  prompt section. `"legacy"` omits it.

## Test counts

| Module | Tests | New in R98 |
|---|---|---|
| aethercode-config | 87 | +87 (OpKindDetector, PathBucket, PermissionMatrix, AetherCodeConfig, ConfigEngine, SkipConfirmationRegistry, SkipConfirmationDetector) |
| aethercode-permission | 79 | +8 (MatrixPermissionPolicy) |
| aethercode-prompts | 106 | +7 (DesignFirstPromptTest), 2 existing tests updated |
| aethercode-sdk | 168 | +8 (MatrixIntegrationE2ETest) |
| All others | unchanged | 0 |
| **Total** | **4054** | **+110 R98** |

(Last full reactor test run: 4054 / 0 fails / BUILD SUCCESS)

## Files

### New (R98)

```
aethercode/aethercode-config/
  pom.xml
  src/main/java/org/aethercode/config/
    OpKind.java
    Action.java
    PermissionMatrix.java
    AetherCodeConfig.java
    ConfigEngine.java
    PathBucket.java
    OpKindDetector.java
    SkipConfirmationRegistry.java
    SkipConfirmationDetector.java
    defaults/DefaultMatrix.java
  src/test/java/org/aethercode/config/
    AetherCodeConfigTest.java
    ConfigEngineTest.java
    PermissionMatrixLookupTest.java
    OpKindDetectorTest.java
    PathBucketTest.java
    SkipConfirmationRegistryTest.java
    SkipConfirmationDetectorTest.java
```

### Modified (R98)

- `aethercode/pom.xml` — added aethercode-config module +
  dependencyManagement entry
- `aethercode/aethercode-permission/pom.xml` — depends on aethercode-config
- `aethercode/aethercode-permission/.../ProjectPermissionPolicy.java` —
  added package-private `rules()` accessor
- `aethercode/aethercode-permission/.../MatrixPermissionPolicy.java` —
  new file
- `aethercode/aethercode-sdk/pom.xml` — depends on aethercode-config
- `aethercode/aethercode-sdk/.../AetherCodeEngine.java` — new Builder
  field `permissionMatrix`; new field `skipConfirmationRegistry`; wires
  registry into policy at construction; installs
  `onUserPrompt` hook; defaultSystemPrompt reads
  `ConfigEngine.loadFromProjectRoot(b.cwd).workflow` to gate the
  design-first section
- `aethercode/aethercode-cli/pom.xml` — depends on aethercode-config
- `aethercode/aethercode-cli/.../Main.java` — calls
  `ConfigEngine.loadFromProjectRoot(cwd).permissionMatrix`
- `aethercode/aethercode-core/.../QueryEngine.java` — new
  `onUserPrompt` Consumer hook, fired at the start of `query()`
- `aethercode/aethercode-prompts/.../SystemPrompt.java` — new
  `designFirst` section, `Builder.designFirst(String)` setter,
  `defaultDesignFirst()` method
- `aethercode/aethercode-prompts/.../RenderedPromptTest.java` —
  updated two tests to include the new `designFirst` slot
- `aethercode/aethercode-prompts/.../DesignFirstPromptTest.java` — new
- `aethercode/aethercode-protocol/.../AetherCodeMethods.java` —
  registers `setSkipConfirmation` RPC; helper `intOrThrow`
- `aethercode/aethercode-sdk/.../MatrixIntegrationE2ETest.java` — new

## Design notes (R98)

1. **The "the? \\s+" pattern bug** — the original pattern used
   `the?\\s+next` which fails to match "for next" because `the?` is
   optional and then `\\s+` requires whitespace after the
   empty match. Fixed by moving the optional `the` to
   `(?:the\\s+)?next`. Lesson: when an optional group is followed by
   `\\s+`, the `\\s+` must be inside the optional or after the
   consumed character. Captured as a test regression in
   `SkipConfirmationDetectorTest`.
2. **withMatrix insertion order** — overrides are inserted at the
   FRONT of the LinkedHashMap so the override wins first-match
   against the default globs. Tested by `withOverride_*` tests.
3. **Trust the tool's read-only flag** — `MatrixPermissionPolicy`
   short-circuits on `tool.isReadOnly(input)` to avoid an
   accidentally-deny entry on a read-only tool causing a denial of
   service.
4. **Hook failure must not break a query** — `QueryEngine.onUserPrompt`
   is wrapped in try/catch and logs at WARNING. A broken detector is
   better than a stuck query.
5. **No core → config dependency** — QueryEngine (aethercode-core)
   doesn't depend on aethercode-config. The hook is a `Consumer<String>`
   so the engine layer can pass a lambda that uses config types
   without polluting the core.

## Open follow-ups (post-R98)

- **R99**: Surface skip-confirmation state in the TUI / Desktop
  (status bar: "skip: 3 rounds remaining").
- **R100**: Per-tool-type allow-list with a "safe" badge in the
  tool description (e.g. "this tool is auto-allowed for `src/test/**`").
- **R101**: Hook the RulesWatcher to also re-render the design-first
  section if `.aethercode/config.json` changes mid-session.
- **R102**: Per-engine persistence of the skip-confirmation counter
  (currently in-memory only; lost on engine restart).
