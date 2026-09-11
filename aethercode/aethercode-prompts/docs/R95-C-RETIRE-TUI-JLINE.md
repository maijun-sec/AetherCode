# R95-C — Retire `aethercode-tui-jline` (2026-08-17)

## Why

The Java JLine + Lanterna REPL was the first interactive surface the
project shipped (R20-A through R28). It was a single-threaded, line-
or alt-buffer REPL, with a custom paint loop, status bar, command
palette, vim mode, and a permission dialog that asked interactively.

The Ink TUI (R30, R31 v2) has been the recommended interactive
surface for a long time — it has parity on every R86+ feature
(markdown rendering, permission cards, subagent panel, slash
commands, sidebar, search, toast) and is materially nicer to use.
The cli's `aethercode tui` subcommand (R31-F) has been the way to
launch it for a year.

What changed:

1. The user-confirmed clean-up moment: in 2026-08-16 the user noted
   that the Ink TUI is the supported surface and `aethercode-tui-jline`
   "都可以去掉" (can all be removed).
2. R95-C turns that note into a change. Keeping the module around
   was costing us: 426 JLine tests to maintain, two `TerminalPalette`
   references to keep in sync, a stale `aethercode-tui` artifactId
   alias in the m2 cache, and a `--fullscreen` / `--no-fullscreen`
   flag set that no longer made sense (the Ink TUI is always
   full-screen).

## What ships

### Code

- **`aethercode/`** parent pom — drops the `<module>aethercode-tui-jline</module>` line.
- **`aethercode/aethercode-cli/pom.xml`** — drops the dangling
  `<dependency>aethercode-tui</dependency>` (which had been pulling
  a stale cached jar from m2).  The cli is now the leanest module
  in the reactor: it depends on `core`, `engine-springai`, `tools`,
  `permission`, `memory`, `mcp`, `prompts`, `compact`, `hooks`,
  `skills`, `protocol`, `sdk`, plus `picocli`, `logback`,
  `junit-jupiter`, `assertj-core`.
- **`aethercode/aethercode-cli/Main.java`** — the big one:
  - `runRepl()` no longer touches JLine. It instantiates `TuiCommand`
    and delegates to it, forwarding `--no-color` and `--cwd`.
  - `JavawAutoRelaunch.shouldRelaunch` and the `main()` no-op
    fallback are removed.
  - `--fullscreen` / `--no-fullscreen` options removed (no longer
    meaningful).
  - The `repl_closeScreenSafe` helper is gone.
  - The `rebuildEngine` helper is gone (it was only called from
    `repl.withSessions(store, sid -> rebuildEngine(sid, store))`,
    which is itself gone).
  - `org.aethercode.tui.TerminalPalette.DIM` and `RESET` are
    replaced with two inline `ANSI_DIM` and `ANSI_RESET` constants.
  - `.prompter(new PermissionDialog(settingsFile))` removed. The
    engine now has a null prompter; `ProjectPermissionPolicy` already
    documents that null means "ask is auto-denied" (line 24 of the
    source). This is the right behaviour for headless `--print`.
- **`aethercode/aethercode-cli/JavawAutoRelaunch.java`** — deleted.
- **`.idea/compiler.xml`** and **`.idea/encodings.xml`** — drop the
  `aethercode-tui-jline` lines so IntelliJ stops complaining.

### Module

- **`aethercode/aethercode-tui-jline/`** — entire directory deleted
  (`src/main/java/`, `src/test/java/`, `pom.xml`, `target/`).
- **`D:\cache\mvn_repo\org\aethercode\aethercode-tui-jline/`** — purged
  from the local m2 cache. The stale `aethercode-tui` cache directory
  (which held an older version of the same classes) is also purged,
  so the next `mvn install` rebuilds from source.

### Docs

- **`README.md`** — "Interactive REPL" section now documents the Ink
  TUI as the default. Mentions R95-C explicitly. The old
  JLine/Lanterna paragraph (with `--fullscreen` opt-in) is gone.
- **`docs/dev-guide/architecture.md`** — module map updated.
- **`docs/CHANGELOG.md`** — `0.3.1` entry above the `0.3.0` one with
  the summary of changes.

## Behavioural changes

| Old | New | Impact |
|---|---|---|
| `java -jar aethercode.jar` (no args) | starts the line-mode Java REPL (Windows) or fullscreen JLine REPL (macOS/Linux, with `javaw` auto-relaunch on Windows if you passed `--fullscreen`) | now launches the Ink TUI directly (cross-platform, no `javaw` weirdness) |
| `java -jar aethercode.jar --fullscreen` | forces JLine fullscreen | flag is removed; pass `--print "..."` for one-shot or `aethercode.jar tui` for the Ink TUI |
| `java -jar aethercode.jar --no-fullscreen` | forces line mode | flag is removed; `--no-color` and `--print` still work |
| `java -jar aethercode.jar tui` | launches the Ink TUI | unchanged |
| `java -jar aethercode.jar --print "..."` | headless one-shot | unchanged |
| `java -jar aethercode.jar --daemon` | JSON-RPC stdio daemon | unchanged |
| `java -jar aethercode.jar --http-port 5820` | HTTP+WebSocket daemon | unchanged |

## Tests

- Reactor tests before R95-C: **2193** (15 modules).
- Reactor tests after R95-C: **1767** (15 modules).
- The 426-test drop is exactly the `aethercode-tui-jline` test
  classpath (we verified the per-module counts in the test log).
- Zero regressions in the remaining 15 modules. TUI scripts
  (`aethercode-tui/scripts/test/*.test.mjs`) and Desktop vitest
  (`aethercode-desktop/src/store/subagentReducer.test.ts`) are
  unaffected.

## What is *not* in R95-C

- **`aethercode-tui` (the directory)** still exists as a sibling
  of `aethercode/` — that's the **TypeScript** Ink TUI. The name
  was freed up by renaming the Java TUI to `aethercode-tui-jline`
  back in R31; R95-C deletes the Java module but the directory
  `aethercode-tui/` (next to `aethercode/`) is exactly what we
  want to keep. No conflict.
- **A replacement for the line-mode REPL** — users without Node.js
  who need an interactive REPL should install Node (the Ink TUI
  bundle is 1.6 MB and works on stock Node 18+), or use `--print`
  for one-shot, or `--daemon` for batch. The cli's REPL mode was
  the only thing tying users to a Java runtime for an interactive
  surface; the LLM client and engine itself are still pure Java.

## How to verify manually

```bash
# 1. Build (R95-C is in main, no flag needed).
cd aethercode
mvn -B install -DskipTests

# 2. Verify the Ink TUI is what the cli delegates to.
java -jar dist/aethercode-0.2.1.jar --help
# → should mention `tui` as a subcommand
java -jar dist/aethercode-0.2.1.jar tui --help
# → should show the TUI proxy's options
java -jar dist/aethercode-0.2.1.jar --print 'hello'
# → should still run headlessly

# 3. Verify no JLine / Lanterna / JavawAutoRelaunch class survives.
& 'D:\development\jdk-25.0.1\bin\jar.exe' tf dist/aethercode-0.2.1.jar |
    Select-String -Pattern 'tui/jline|lanterna|JavawAutoRelaunch|PermissionDialog|ReplApp|TerminalPalette'
# → should print nothing
```

## Lessons (cross-project)

- **Dual interactive surfaces always lose.** The Ink TUI gained
  features faster than the JLine REPL could keep up. R95-C
  embraces the single-surface rule: one TUI, one set of
  features, one bug tracker.
- **Stale m2 cache + dangling artifact alias** is a real failure
  mode. The cli was pulling `aethercode-tui-0.1.0-SNAPSHOT.jar`
  from the cache even though the only module producing
  `aethercode-tui-jline` had replaced it. The build only worked
  by accident (the two jars shared `org.aethercode.tui.*` package
  classes). R95-C removes the alias from the parent pom's
  dependencyManagement so the next build only has one source of
  truth.
- **Drop a feature in one round, not piecemeal.** We could have
  removed the JLine tests one by one, or stripped the JLine
  import sites one by one, across multiple R-rounds. R95-C does
  it all at once: the test count drops by 426, the cli loses
  3 helper methods, the parent pom loses 2 sections, and the
  behavior change (default `java -jar` launches the TUI) is
  documented in the same CHANGELOG entry. Future contributors
  don't have to triangulate.
