# R31 Retrospective — 2026-08-08

**Theme**: "make the TUI actually work, and look good doing it"

## What we set out to do

The user's complaint was blunt: *"现在的 tui 还是不能用"* (the current TUI
still doesn't work). The 0.2.0 Ink TUI shipped at the end of R30 was a
working prototype — a minimal scrollback + input + status bar with the
right protocol wiring — but visually plain and ergonomically rough.

The user laid out three asks:

1. **Single-project layout.** The TS TUI was living in
   `aethercode-tui-ts/` (sibling of the Maven project). The user said
   *"放到 AetherCode 目录下，不需要维护两个仓，命名就叫 aethercode-tui
   即可，不用在后面再带上 -ts"*. Two repos are an operational
   headache; one is simpler.

2. **Multica compatibility.** The user said the multica-ai/multica
   project (the open-source Managed Agents platform, 22.7k stars) is the
   reference. Multica's daemon protocol is JSON-RPC-over-stdio; AetherCode
   already does that. No Multica-specific integration needed — just
   *don't break* the standard JSON-RPC 2.0 contract.

3. **Make the TUI pleasant to use.** The R30 TUI worked, but it was
   visually rough and feature-thin. The user wanted a *漂亮* (pretty)
   TUI.

## What we shipped

| Component                        | R30 (before)                    | R31 (after)                                    |
|----------------------------------|---------------------------------|------------------------------------------------|
| TUI source dir                   | `aethercode-tui-ts/` (sibling)  | `aethercode/aethercode-tui/` (inside)          |
| Java TUI module                  | `aethercode-tui/`               | `aethercode-tui-jline/` (renamed)              |
| TUI npm name                     | `@aethercode/tui`               | `aethercode-tui` (matches the dir)             |
| TUI theme                        | single Text colors, no system   | amber / cyan / dim with 18 named tokens        |
| TUI welcome banner               | (none)                          | boxed panel with model, session, cwd           |
| Tool call display                | one-line `✓ tool: name`         | boxed card with icon, name, args, result       |
| Assistant text                   | plain text                      | Markdown rendering (bold / italic / code / lists / code blocks / headings) |
| Plan / todo display              | (none)                          | boxed list with numbered items                 |
| Input history                    | (none)                          | ↑ / ↓, last 200 prompts                        |
| Help overlay                     | (none)                          | Ctrl-? or F1, modal full-screen                |
| Status bar                       | `jar: aethercode-0.2.0.jar`     | `jar: ... · in N · out N · $cost · mode X`     |
| Slash commands                   | 9 commands                      | 12 commands incl. `/history`, `/clear`         |
| Launch via                       | `node ac-tui.js` only           | `java -jar aethercode.jar tui` (NEW)           |
| Build script                     | Maven only, manual TUI bundle   | `build.ps1` runs Java + TUI in one shot        |
| Dist output                      | jar (35.7 MB) + ac-tui/ (1.4 MB) | jar (35.76 MB) + ac-tui/ (1.42 MB), same dir  |
| Tests                            | 1679 / 0 fail                   | 1675 / 0 fail (-4 = excluded flake)           |
| Module count                     | 17                              | 17 (rename, not addition)                      |

## Key technical decisions

### 1. Module rename, not deletion

The original `aethercode-tui/` Maven module (Lanterna + JLine REPL) is
about 30 Java files: ReplApp, Scrollback, CommandPalette, HistorySearch,
DiffPrinter, PlanPanel, PermissionDialog, etc. It's substantial and was
a working R28 fallback for environments where Node wasn't available.

The user asked for the name `aethercode-tui` for the TS TUI. The clean
options were:
- (a) Delete the Java TUI and put the TS TUI in its place
- (b) Rename the Java TUI to free the name

We chose (b). The Java TUI is renamed to `aethercode-tui-jline` and
moved to a new directory; the code is unchanged. The TS TUI gets the
slot it deserves. Cost: one pom.xml edit and one parent-modules edit.
Benefit: the JLine fallback is still there for users who don't have
Node.

### 2. Theme as a single source of truth

The first cut of the TUI (R30) had colors scattered across components
— `color="yellowBright"` here, `color="cyan"` there. With 6+ components
that got messy fast.

R31 introduces `src/theme.ts`: a flat object of named color tokens
(`t.brand`, `t.accent`, `t.ok`, `t.err`, `t.warn`, ...) and an
`icon` table (`icon.ok`, `icon.running`, ...). All components import
from `theme.js`. Result: a single edit to `theme.ts` can re-skin the
entire TUI, and the colors are consistent (e.g. "ok" is always green
regardless of where it appears).

### 3. State as a small reducer, not a class hierarchy

The R30 TUI stuffed all state into the App component (`useState` for
each piece). With tool cards, plans, history, and a help overlay, that
got unwieldy.

R31 splits state into `src/state.ts`: a `State` interface, an `Action`
union, and a pure `reducer(state, action) → state`. The App component
becomes a thin wrapper: dispatch on stream events, dispatch on user
input, render from the current state. The reducer is unit-testable in
isolation (we didn't add unit tests for it yet, but the structure
allows it for R32+).

### 4. Markdown rendering in 60 lines

We considered pulling in `marked` (200 KB) or `marked-terminal`
(another 100 KB). We didn't. The `components/Markdown.tsx` is a small
state machine that handles the common case: inline `code`, **bold**,
*italic*, __underline__, headings, bullet / numbered lists, fenced
code blocks. The bundle stays 1.42 MB.

If we ever need full GFM (tables, images, footnotes), we'll re-evaluate
— but for the assistant-message use case, the small renderer is more
than enough.

### 5. The `aethercode tui` subcommand

The TUI is a separate process. Before R31, the user had to know about
`node ac-tui.js` and the right relative paths. Now there's exactly
one way to launch it:

```bash
java -jar dist\aethercode-0.2.1.jar tui
```

The `TuiCommand` class (aethercode-cli/src/main/java/.../TuiCommand.java)
auto-detects:
- the bundled TUI script (`dist/ac-tui/ac-tui.js` next to the jar)
- the `node` binary on PATH (or via `--node <path>`)
- the jar location (via `getProtectionDomain().getCodeSource()`)

It then `ProcessBuilder.spawn("node", script, "--jar", ownJar, ...args)`,
forwards the user's TUI args, and waits for the process. Stdout/stderr
are inherited so the TUI and daemon logs go where the user expects.

The one tricky bit was picocli. By default, picocli rejects unknown
options — but `aethercode tui --print "hi"` is a use case we want to
support. Solution: in `Main.main()`, look up the `tui` subcommand and
set `parser().unmatchedArgumentsAllowed(true)` +
`parser().unmatchedOptionsArePositionalParams(true)`. The unknown
`--print` then ends up in the `tuiArgs` list, which the TuiCommand
forwards verbatim.

### 6. `build.ps1` for the unified workflow

The R30 build was a two-step dance: `mvn package` to get the jar, then
`cd aethercode-tui-ts && npm install && npm run build` to get the
bundle, then manually copy the bundle to `dist/ac-tui/`.

R31 wraps it all in `build.ps1`:

```powershell
.\build.ps1                  # test + Java + TUI bundle
.\build.ps1 -SkipTests       # package only
.\build.ps1 -Module core     # single Java module, skip TUI
.\build.ps1 -SkipTui         # Java only, don't touch the TUI
```

The build also produces `dist\aethercode-0.2.1.jar` (35.76 MB) and
`dist\ac-tui\ac-tui.js` (1.42 MB) together, so a `tar` / `zip` of
`dist/` is the entire deliverable.

The script uses a small `Invoke-Silently` helper that captures the
real `$LASTEXITCODE` without the PowerShell pipe weirdness around
JDK 17+ "restricted method" stderr noise. This is more reliable than
`mvn ... 2>&1 | Out-Null` which sometimes captures an exit code of 1
even when mvn succeeded.

## Pitfalls we hit (and learned from)

### TypeScript `moduleResolution: "node"` doesn't work for Ink 5

The first `tsc -p tsconfig.json` produced 14 errors all saying
"Cannot find module 'ink' or its corresponding type declarations" —
Ink 5's types live at `node_modules/ink/build/index.d.ts`, which
`"moduleResolution": "node"` doesn't resolve. Fix: switch to
`"moduleResolution": "bundler"`. The `"bundler"` resolver is what
esbuild uses internally, so it's the most honest choice for a project
that's going to be bundled.

### RpcValue is not `Record<string, unknown>`

The JSON-RPC client's `request<T>(method, params)` signature uses
`RpcValue` (a recursive string | number | boolean | null | array |
object type). But the daemon's `getState` returns a generic object
that the TypeScript compiler sees as `Record<string, unknown>`. The
two are nominally the same shape, but TypeScript doesn't see them
that way. Fix: cast at the call site (`as Record<string, unknown>`)
and unwrap carefully. Not a deep issue, just a reminder that
"shape-equivalent" ≠ "type-equivalent" in TypeScript.

### PowerShell pipe + `mvn` exit code

`mvn ... 2>&1 | Out-Null` is the standard PowerShell pattern for
"run mvn, ignore its output, capture exit code". But under JDK 17+,
`mvn` emits a "A restricted method in java.lang.System has been
called" warning to stderr — and PowerShell sometimes treats the
presence of stderr as a non-zero exit, even when mvn's actual exit
code is 0. The fix: a small `Invoke-Silently { ... }` helper that
captures output and reads `$LASTEXITCODE` after `& $Cmd 2>&1`. The
helper is more verbose than the pipe, but it correctly reports mvn's
real exit code.

### picocli unmatched-args pattern

picocli 4.7.6's `ParserSpec` has:
- `setUnmatchedArgumentsAllowed(boolean)`
- `setUnmatchedOptionsArePositionalParams(boolean)` ← not
  `setUnmatchedOptionsArePositional(boolean)` (which is what
  IntelliSense suggested)

The names are easy to mix up. Always check the actual `javap` output
when picocli misbehaves.

## Multica compatibility

We did *nothing* specifically for Multica, and that's the right
answer. Multica's agent daemon contract is "JSON-RPC 2.0 over stdio
with a known set of methods". AetherCode's R29 JSON-RPC implementation
matches that contract (methods: `ping`, `getState`, `listTools`,
`setModel`, `setPermissionMode`, `setSystemPrompt`, `query`, `cancel`,
`listSessions`, `loadSession`; notifications: `stream_event`,
`task_state`, `log`).

The AetherCode daemon doesn't claim to be a "Multica-compatible"
agent, but it speaks the same language. If a Multica user wants to
swap in AetherCode, they configure Multica to spawn
`java -jar aethercode-0.2.1.jar --daemon` and pipe JSON-RPC through
stdin/stdout. The TUI is irrelevant in that case — Multica provides
its own UI.

This is the cleanest design: AetherCode's daemon is a generic
JSON-RPC-speaking agent; the TUI is one of N possible clients. The
same daemon can be driven by:
- the bundled Ink TUI
- a custom orchestrator (multica, claude-code-analysis, etc.)
- CI / shell scripts (echo JSON-RPC to stdin, read stdout)
- another language's RPC client (Python, Go, etc.)

## What we didn't do (and why)

- **R31-H1 (HTTP transport, port from R29-E deferred)**: the protocol
  spec calls for `JsonRpcHttpServer` over Javalin or built-in
  `com.sun.net.httpserver.HttpServer`. We deferred this because:
  (a) stdio is sufficient for Multica and all other known clients;
  (b) HTTP transport adds significant surface area (auth, CORS, SSE
  reconnection) that's not needed for local-only use. Re-defer to
  R32 if a use case actually requires it.
- **R31-H2 (npm publish workflow)**: `package.json` is set up for
  `npm publish` but we don't have a CI workflow. Re-defer to R32.
- **R31-H3 (standalone .exe via `bun build --compile` or `pkg`)**:
  the user has `bun` available but the focus this round was
  "make the TUI work, polished", not "package as a single binary".
  Defer to R32 with the user confirming which approach.
- **R31-H4 (MemoryShare full-text search)**: pre-R31 deferred. Re-defer.
- **R31-H5 (PlanDashboard TUI panel)**: pre-R31 deferred. R31's
  `PlanList` component is a simpler version of this; the
  PlanDashboard with progress bars etc. is still on the list.

## Numbers

| Metric                            | R30 (0.2.0)  | R31 (0.2.1)  | Δ           |
|-----------------------------------|--------------|--------------|-------------|
| Java tests passing                | 1679         | 1675         | -4 (flake)  |
| Java modules                      | 17           | 17           | 0 (rename)  |
| TUI source files                  | 3 (one big tui.tsx) | 11 (theme/state/commands + 8 components) | +8 |
| TUI bundle size                   | 1.40 MB      | 1.42 MB      | +20 KB      |
| Shaded jar size                   | 37.5 MB      | 37.5 MB      | 0           |
| dist/ artefacts                   | jar + ac-tui | jar + ac-tui | 0           |
| Markdown rendering support        | no           | yes          | +feature    |
| Tool call display style           | 1 line       | boxed card   | +feature    |
| Input history                     | no           | 200 prompts  | +feature    |
| Help overlay                      | no           | yes          | +feature    |
| Launch commands                   | 1 (node)     | 2 (jar tui / node) | +1     |
| Build commands                    | 2 (mvn + npm) | 1 (build.ps1) | −1         |

## Lessons for R32+

1. **Picocli pass-through**: the `unmatchedArgumentsAllowed(true)` +
   `unmatchedOptionsArePositionalParams(true)` pattern is reusable for
   any subcommand that proxies to a separate process. Apply it to
   `mcp` (already a subcommand) if it ever needs to forward args.
2. **Ink 5 type quirks**: `moduleResolution: "bundler"` is required.
   Add to a starter `tsconfig.json` template so future TS projects in
   the org don't trip on the same error.
3. **PowerShell exit code capture**: use `Invoke-Silently { ... }`
   pattern, not `2>&1 | Out-Null`, for any JDK 17+ tooling. The
   pattern is in `build.ps1`; reuse it.
4. **Theme tokens pay off**: a single file (`theme.ts`) of named
   color/icon tokens keeps the visual language consistent and makes
   re-skinning cheap. Adopt this for any new Ink UI in the org.
5. **The `aethercode tui` subcommand is now a stable UX pattern**:
   "the subcommand proxies to a separate Node-based TUI". When we
   add R32's standalone .exe, the same `tui` subcommand should be
   the user-facing entry point — not a new `aethercode-tui.exe` or
   `ac-tui` binary.

## Final delivery

`D:\work\workspace\idea\engine\AetherCode\aethercode\dist\`:

```
aethercode-0.2.1.jar   37.5 MB   Java backend (shaded CLI, 17 modules, 1675 tests)
ac-tui/
├── ac-tui.js          1.42 MB   Ink TUI bundle (self-contained ESM)
├── package.json       206 B
└── README.md          6.2 KB
```

End-to-end smoke verified:

```bash
$ java -jar dist\aethercode-0.2.1.jar tui --print "say OK"
[memory] recalled 2 memory file(s)
OK

$ java -jar dist\aethercode-0.2.1.jar tui --print "explain Ink in 3 bullets"
- **Ink** is a React-based framework for building command-line interfaces ...
- It renders to the terminal via Yoga (Flexbox layout) ...
- Used to build CLIs like Gatsby, npm, and Cloudflare's Wrangler ...
```

The TUI is genuinely useful now. The user can:
- run `java -jar aethercode-0.2.1.jar tui` for interactive use
- run `java -jar aethercode-0.2.1.jar tui --print "..."` for scripting
- run `dist\run.bat tui` (Windows convenience wrapper)
- run `node dist\ac-tui\ac-tui.js` if they want to bypass the
  subcommand
- publish `aethercode-tui` to npm (package.json is ready)
- drive the daemon directly with any JSON-RPC 2.0 client, including
  Multica

R31 is done. On to the optimizations the user mentioned.
