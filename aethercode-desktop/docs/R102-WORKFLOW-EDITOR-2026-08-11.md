# R102: Workflow Editor + UI Polish (2026-08-11)

R102 ships two related slices:

- **R102a: workflow picker (earlier today)** — input-bar
  picker, `WorkflowProgressBar`, 4-section `SubTaskCard`,
  three sample workflows.
- **R102b (this doc)**: `/workflow` slash commands,
  `WorkflowEditorModal`, `writeWorkflow` / `deleteWorkflow`
  RPCs, and a UI 比例调优 pass on the input bar.

## Pain points

- User could select a workflow from the picker but had no way
  to **create** or **modify** one from inside the app.
- `Quick-access` was a one-time `pickCwd` — no way to install
  a new skill.
- The Send button was 90×18 / 14px text — too loud for an
  "ok, go" affordance next to a 14px input field.
- The config bar (Model / Perm / Tools / cwd) was 11px text,
  which felt small next to a 14px input box.

## R102b design

### `/workflow` family

| Command | Behaviour |
|---------|-----------|
| `/workflow list` | refresh the cached workflow list and surface the names + step counts in the help channel |
| `/workflow create` | open the `WorkflowEditorModal` in create mode with a starter template (the user fills in name / description / steps) |
| `/workflow modify <name>` | read the workflow's raw YAML and open the editor in modify mode with the existing content |
| `/workflow delete <name>` | `window.confirm` → daemon `deleteWorkflow` RPC (idempotent) → refresh the cache |

All four use a custom event (`aethercode:open-workflow-editor` /
`aethercode:show-help`) so the slash-command's sync `run()`
can hand off to async UI / RPCs.

### `WorkflowEditorModal`

- Self-mounting: listens for `aethercode:open-workflow-editor`
  with `{action, name?, raw?}`.
- 1-line name field (kebab-case validator).
- Monospace textarea for the raw YAML (no syntax highlighting
  in R102b; R103 could swap in Monaco / CodeMirror).
- "保存" / "取消" footer with a live status row (char count
  + name-collision warning + error message).
- On save: `rpc.writeWorkflow(name, body)` → on success,
  `store.refreshWorkflows()` to keep the picker's cache in sync.

### `writeWorkflow` / `deleteWorkflow` RPCs

Mirror the R92 memory RPCs' shape and path-scope check:

- `writeWorkflow({name, content})` → `{ok, path, name}`.
  Refuses names with `..` / `/` / `\` / `\0` and empty content.
- `deleteWorkflow({name})` → `{ok, removed, name}`.
  Idempotent: missing file returns `removed: false`.

Both are written to `<cwd>/.aethercode/workflows/<name>.yaml`.
The path-scope check in `WorkflowPaths.workflowFile()` (refuses
`..` etc.) is the same one R102a uses; R102b reuses it.

### UI 比例调优

| Surface | Before | After |
|---------|--------|-------|
| `message-input-container` padding | `12 24 10` | `14 28 12` |
| `input-config-bar` font | `11px` | `12px` |
| `input-config-bar` gap | `10px` | `12px` |
| `config-select / config-toggle` font | `11px` | `12px` |
| `config-select` padding | `4 8` | `6 10` |
| `config-toggle` padding | `4 10` | `5 12` |
| `message-input` min-height | `72px` | `96px` |
| `message-input` font | `14px` | `15px` |
| `message-input` line-height | `1.5` | `1.6` |
| `message-input` padding | `12 16` | `14 18` |
| `message-input-btn` min-width | `90px` | `72px` |
| `message-input-btn` padding | `0 18` | `0 14` |
| `message-input-btn` font | `14px` | `13px` |
| `input-hint` font | `10px` | `11px` |
| Container max-width | `880px` | `920px` |

The user-facing effect: the input bar reads as one coherent
unit, the Send button is a sibling not a hero, and the
config bar's text matches the input box's text size so the
eyeline doesn't bounce between 11 and 14.

### `/skill add <url-or-path>`

The renderer can't directly `git clone` (no shell-exec
permission), so `/skill add` shows a copy-pasteable command
in the help channel:

```bash
# git URL
git clone https://github.com/foo/bar.git ~/.minimax/skills/bar

# or local path
Copy-Item -Path "D:\path\to\skill" -Destination "~/.minimax\skills\skill-name" -Recurse -Force
```

The user runs the command in their terminal, restarts the
app, and the new skill shows up in `<available_skills>` on
the next session. The status pill says "已把安装命令推送到
消息流 (target: ~/.minimax/skills/<name>)" so the user
sees where the new skill is going.

## Files

- `aethercode/aethercode-core/src/main/java/org/aethercode/core/workflow/WorkflowPaths.java`
  (R102a, reused)
- `aethercode/aethercode-protocol/src/main/java/org/aethercode/protocol/methods/AetherCodeMethods.java`
  — `writeWorkflow` + `deleteWorkflow` (R102b) + `registerAll` entries
- `aethercode/aethercode-protocol/src/main/java/org/aethercode/protocol/http/HttpJsonRpcServer.java`
  — dispatch switch + `/api/methods` list
- `aethercode-desktop/src/lib/methods.ts` — `writeWorkflow` / `deleteWorkflow` wrappers
- `aethercode-desktop/src/components/commandCommands.ts`
  — `/workflow list` / `/workflow create` / `/workflow modify` / `/workflow delete` / `/skill add`
- `aethercode-desktop/src/components/WorkflowEditorModal.tsx + .css` (new)
- `aethercode-desktop/src/components/MessageInput.css` — UI 比例调优
- `aethercode-desktop/src/App.tsx` — `<WorkflowEditorModal />` mounted at the app root
- `aethercode/aethercode-core/src/test/java/org/aethercode/core/workflow/WorkflowReaderTest.java`
  (R102a, 9/9 pass)

## Build / deploy

- Backend: `aethercode-0.2.5.jar` (39.6 MB) at 3 locations.
  Daemon PID 9316 on port 17888.
- Frontend: 448.7 KB JS / 69.4 KB CSS (R102a was 441.4 / 66.1;
  +7.3 / +3.3 KB for the editor + 4 slash commands + UI 调优).

## Test results (WS roundtrip via wstest_wf2.cjs, 9 checks)

```
[1] /api/methods: writeWorkflow: true, deleteWorkflow: true
[2] writeWorkflow -> {ok, path, name}
[3] getWorkflow roundtrip: ok, 1 step, description preserved
[4] file on disk: true at .aethercode/workflows/r102b-smoketest.yaml
[5] writeWorkflow path-traversal -> {ok: false, reason: "invalid name"}
[6] writeWorkflow empty content -> {ok: false, reason: "content is empty"}
[7] deleteWorkflow -> {ok, removed: true}
[8] deleteWorkflow idempotent -> {ok, removed: false}
[9] file gone after delete: true
```

The path-traversal refusal is the same one R102a's
`WorkflowPaths.workflowFile` enforces; the empty-content
check is R102b's defence-in-depth (the user could otherwise
wipe a workflow by clicking save on a blank edit buffer).

## Out-of-scope / future work

- **R103 — workflow executor**: replace the `runWorkflow`
  stub with a real step-walker (shell / skill / agent /
  parallel / gate / delay). Today the daemon emits
  `workflow_step pending:` for each declared step; the
  executor will move them to `running` / `ok` / `error`
  as it advances.
- **R103 — workflow editor syntax highlighting**: swap the
  plain `<textarea>` for Monaco / CodeMirror with a YAML
  grammar. The current monospace textarea is good enough
  for the 5-20 line typical workflow; a real editor pays off
  when users start writing 200-line files.
- **R103 — `/skill add` shell exec**: when the Tauri Rust
  toolchain is back online, expose a `shell_exec` IPC and
  have `/skill add` call it directly (one click instead of
  copy-paste). For now the help-channel command is the
  right trade-off.
- **Tauri plugin shell**: `tauri-plugin-shell` would let
  the renderer call `git clone` directly (with a
  permission scope). The current copy-paste path is a
  known fallback.
- **Multi-tab workflow editor**: a future cycle could
  let the user open multiple workflows in tabs (like
  the R99 multi-tab MemoryPanel). Not on the immediate
  roadmap — most workflows are short and edits are
  bursty.
