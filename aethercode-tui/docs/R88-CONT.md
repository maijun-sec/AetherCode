# R88 (continued) — Permission policy actually wired + model uses tools

> **Status:** SHIPPED 2026-08-15 (R88-B/C/D/A)
> **Scope:** three follow-ups to the R88 path-sandbox fix — fixing the
> long-standing "ACCEPT_TASK mode is dead code" bug, adding a
> `/no-confirm` shortcut, tightening the system prompt, and improving
> the markdown code-block visual.

## The bug

After R88's path-sandbox fix, the user could once again write a single
file from the TUI. But the same prompt that asked the model to "create
a Maven project with 5 sort algorithms + tests" still landed nothing in
the working directory. Three things were wrong, and the third one was
the only one actually caused by R87:

1. The model pasted the file contents as fenced ``` blocks in the
   chat reply instead of emitting `tool_use` blocks. The fenced
   blocks rendered fine in the TUI but never created any files on
   disk — they were markdown, not tool calls.
2. The TUI's `setPermissionMode("ACCEPT_TASK")` returned OK and the
   status bar updated to `mode ACCEPT_TASK`, but the live
   `ProjectPermissionPolicy` was still constructed in DEFAULT mode.
   Every `file_write` call therefore routed through the
   JSON-RPC prompter and surfaced a `permission_request` card to the
   TUI. The user saw "frequent confirmation prompts" and assumed
   the mode switch had no effect.
3. The `StreamingToolExecutor` (the actual production path that
   `QueryEngine` uses) called `tool.checkPermissions(...)` directly,
   skipping the engine's `PermissionPolicy` entirely. The R86
   `ACCEPT_TASK` semantics — auto-allow inside one sub-task, ask at
   the sub-task boundary — were therefore dead code in the live
   streaming path. `ToolOrchestrator` (the old R1 path) was
   likewise dead code, so even the legacy fallback didn't help.

## The fixes

### R88-A — system prompt teaches the model to actually call tools

`aethercode-prompts/.../SystemPrompt.java#defaultWorkflow` now spells
out "Actually creating files":

- "When the user asks you to CREATE / SCAFFOLD / GENERATE files …
  you MUST call the actual `file_write` tool for every file you
  produce. Pasting code blocks in a Markdown reply does NOT create
  files on disk."
- "Multi-file requests (e.g. `scaffold a project with pom.xml,
  src/main/..., src/test/...`) require a `file_write` for EACH
  file. One tool call per file."
- Permission-mode guidance: the default daemon is in ACCEPT_TASK
  mode, so the model is explicitly told "you do NOT need to ask
  the user before each `file_write` / `bash` / `file_edit`. Just
  call the tool."

### R88-B — `StreamingToolExecutor` consults the policy first

`aethercode-core/.../StreamingToolExecutor.java#runOneBackpressured`
and `runOneLegacy` now call `policy.check(tool, input, ctx)` *before*
`tool.checkPermissions`. If the policy is null or throws, they fall
through to the tool's own check (so existing tools with a
hand-rolled `checkPermissions` still work). Ask results still fail
with "ask not supported in streaming executor" — the streaming
executor can't pause a batch to wait for the user; the policy
layer is responsible for auto-resolving Ask to Allow/Deny when the
mode permits (ACCEPT_TASK does this via `resolveAcceptTask`).

`AetherCodeEngine.setPermissionMode(PermissionMode)` was added; it
calls `ProjectPermissionPolicy.withMode(newMode)` to produce a
like-for-like copy (same rules, same prompter reference, same
in-flight sub-task id) and swaps the live policy in via the
existing `swapPolicy`. `AetherCodeMethods.setPermissionMode` now
calls this in addition to updating `AppState.permissionMode`, so the
user's TUI `/mode` and `/no-confirm` commands actually take effect.

`ProjectPermissionPolicy.withMode` is a 6-line helper; covered by a
new test `withModeSwapChangesVerdictOnLaterCheck` that confirms the
old policy reference still denies under DEFAULT while a new copy
auto-allows under ACCEPT_TASK (and vice versa).

### R88-C — `/no-confirm` shortcut

`aethercode-tui/src/commands.ts` adds a `/no-confirm` (alias
`/noconfirm`, `/yes-do-it`) slash command that wires straight to
`setPermissionMode` with `mode: "BYPASS_PERMISSIONS"`. The TUI's
`handleSubmit` then pushes a confirmation toast that names the new
mode and a one-line blurb ("no prompts will be shown for any tool
call"), so the user has visible confirmation that the switch
landed (not just the status-bar pill on the next event).

`/mode <name>` now also gets the same toast on success. The set of
modes listed in `/mode`'s help line now includes `ACCEPT_TASK`.

### R88-D — code block border + language tag

`aethercode-tui/src/components/Markdown.tsx` parses the language
out of the opening ``` fence and stashes it on the block. The
renderer wraps every fenced block in a `borderStyle="round"` box
with `borderColor={t.dim}` and shows the language as a dim first
line (e.g. " java"). The user complained the scrollback "looked
like a wall of grey" — the box gives every block a clear visual
boundary, the language tag makes XML / Java / bash / etc.
distinguishable at a glance, and the `t.code` colour is the
existing magenta used elsewhere so the brand palette stays
consistent.

### Belt-and-braces: `ToolSafeList` extended

`aethercode-permission/.../ToolSafeList.java` now lists
`file_write`, `file_edit`, `file_create`, `todo_write` as
"safe" (auto-allowed by `PermissionDialog.ask`). This matters for
the legacy `--print` / REPL path where the prompter is the JLine
`PermissionDialog` rather than `JsonRpcPermissionPrompter`: the
headless process has no TTY, so `BufferedReader.readLine()` returns
`null` immediately and the dialog previously returned Deny for
*every* tool call. With the safe-list expanded, the dialog
short-circuits to Allow for the four common case tools and the
headless daemon no longer deadlocks.

## Test status

- aethercode-tools: 109 (incl. 3 FileWriteToolTest R88 regressions)
- aethercode-permission: 71 (incl. 1 new withModeSwap test)
- aethercode-sdk: 108
- aethercode-core: 851 (1 flaky ToolOrchestratorTest, pre-existing —
  ToolOrchestrator is dead code in production, see R22 retrospective)
- aethercode-tui: tsc + esbuild clean, TUI tests 252/280 pass (28
  pre-existing R77/R78/R79 setup-path failures, unrelated to R88)

## End-to-end verification

Real Juliet-style harness was not relevant here (these are UX
changes, not detection work), so the verification is end-to-end
manual:

1. **Single file_write** — `java -jar aethercode-0.2.1.jar --print
   "Create a file at d:\tmp\test-r88-cwd\from-cli.txt with content
   hello."` → `from-cli.txt` (5 bytes "hello") on disk. R88 path
   sandbox + R88-A prompt + R88-B policy first-time-right.
2. **setPermissionMode swap** — `withModeSwapChangesVerdictOnLaterCheck`
   in `aethercode-permission`'s test suite. Old policy still
   denies under DEFAULT; the `withMode`-rebuilt copy auto-allows
   under ACCEPT_TASK without invoking the prompter.
3. **Visual** — the Markdown component was exercised through the
   R86 / R87 / R88 / R88-CONT TUI test suites; all 8 R86
   `<think>` / `</think>` parser tests still pass.

## Known still-unfixed (intentionally deferred)

- **"TUI 频繁停"** — see R88 first doc. The model really does
  end_turn after each tool_use; ACCEPT_TASK still requires the
  user to send the next prompt. The fix needs engine-side
  auto-continue on `tool_use_end` in ACCEPT_TASK mode, which is a
  multi-day R-round of its own.
- **Bundled default model is `MiniMax-Text-01`** — see
  `aethercode-core/.../ProviderRegistry.java#bundledDefaults`.
  Text-01 is much more "paste markdown" than M3 and even with
  R88-A it still sometimes skips the tool_use step. Workaround
  for now: `ac-tui model M3` (or whatever model the user prefers
  in their `~/.aethercode/providers.yaml`).
