# R110-4: workflow editor UX 升级 (2026-08-14)

## TL;DR

The `/workflow create` and `/workflow modify` editor (R102b) was
a raw `<textarea>` with a name field. Functional, but it didn't
feel like an editor — no live feedback on what's in the body, no
way to add a step without typing YAML from scratch, no way to
jump to a step once you had a dozen. R110-4 turns it into a
proper editor: live parse preview, step list with click-to-jump,
an "insert step" palette with the 5 most common step types, and
Cmd/Ctrl+S to save.

The body is still raw YAML (the daemon's `WorkflowReader` is the
source of truth at save time). The "preview" is a best-effort
regex/indent walker that catches the common cases — it is NOT
a full YAML parser, and the editor does not pretend to be one.
The user's save either succeeds (daemon validated) or fails with
the daemon's parse error; the preview is a usability hint, not a
contract.

## Before / after

**Before (R102b):**

```
┌─ 编辑 workflow · ship-it ─────────────────┐
│ name [ship-it                  ]           │
│ yaml                                        │
│ ┌────────────────────────────────────┐    │
│ │ name: ship-it                      │    │
│ │ description: ...                   │    │
│ │ steps:                             │    │
│ │   - id: pull                       │    │
│ │     type: shell                    │    │
│ │     cmd: "git pull ..."            │    │
│ │   ...                              │    │
│ └────────────────────────────────────┘    │
│ 837 字符                                    │
│                            [取消]   [保存] │
└─────────────────────────────────────────────┘
```

The user has no way to:
- See the parsed step list without scrolling the YAML
- Add a new step without typing the full YAML skeleton
- Jump to a specific step to copy/edit it
- Tell at a glance whether the YAML is well-formed (until
  they hit Save and the daemon rejects it)
- Save without using the mouse

**After (R110-4):**

```
┌─ 编辑 workflow · ship-it ─────────────────┐
│ name [ship-it                  ]           │
│ yaml                                        │
│ [+ shell] [+ agent] [+ skill] [+ parallel] │
│ [+ gate]                                     │
│ ┌────────────────────────────────────┐    │
│ │ name: ship-it                      │    │
│ │ description: ...                   │    │
│ │ steps:                             │    │
│ │   - id: pull                       │    │
│ │     type: shell                    │    │
│ │     cmd: "git pull ..."            │    │
│ │   ...                              │    │
│ └────────────────────────────────────┘    │
│ 步骤预览 (点击跳转)                          │
│  1. pull         shell     L16              │
│  2. test         parallel  L22              │
│  3. review       skill     L34              │
│  4. commit       gate      L41              │
│  5. notify       shell     L53              │
│ 837 字符 · ✓ 5 steps · ● unsaved            │
│           Cmd/Ctrl+S 保存 · Esc 关闭         │
│                            [取消]   [保存] │
└─────────────────────────────────────────────┘
```

The user can now:
- See the parsed step count + a clickable list (one per step,
  with `id` / `type` / `L<line>` columns) without scrolling
  the YAML
- Click any step in the list → the textarea scrolls to that
  line and selects the whole step block (so Cmd+C copies the
  step, Delete removes it, etc.)
- Click any palette button to insert a well-formed step
  template at the cursor; the cursor lands on the `id:` field
  so typing replaces `step-name` immediately
- See an `● unsaved` indicator in the status row when the
  body has been edited, so they know Save is needed
- See a `⚠ 已存在` warning if the name collides with an
  existing workflow (informational — Save still overwrites)
- Use Cmd/Ctrl+S to save without leaving the keyboard
- Use Esc to close (with a discard-changes confirmation if
  dirty)

## What ships in this round

### Frontend (aethercode-desktop)

- `components/WorkflowEditorModal.tsx`:
  - New `extractWorkflow(body)` helper — a tiny best-effort
    YAML walker that pulls `name`, `description`, `version`,
    and a `steps: ExtractedStep[]` list (each entry with
    `id`, `type`, and the line number of the `- id:` row).
    Walks the body line-by-line, tracks the indent stack,
    handles `type:` on the same line OR on the next
    child line. NOT a full YAML parser; the daemon is still
    the source of truth.
  - New "insert step" palette with 5 buttons:
    `+ shell`, `+ agent`, `+ skill`, `+ parallel`, `+ gate`.
    Each inserts a well-formed snippet (with realistic
    placeholder values) at the cursor. The cursor lands on
    the first `id:` row, just after `id: `, so the user can
    type the step name immediately.
  - New "step list" preview below the textarea. Each row
    is `1. <id>  <type>  L<line>`. Clicking jumps the
    textarea scroll to the matching `- id:` line and
    selects the whole step block.
  - New `Cmd/Ctrl+S` save binding (in addition to the
    footer button).
  - New `Esc` close binding (with a "放弃未保存的修改？"
    confirmation if the body is dirty).
  - New `dirty` flag and `● unsaved` status indicator.
  - New "indent guides" on the textarea (via CSS background
    pattern, 2ch + 4ch columns at low alpha).

- `components/WorkflowEditorModal.css`:
  - `.workflow-editor-palette` — a row of small monospace
    buttons above the textarea.
  - `.workflow-editor-steplist` — a vertically-scrolling
    list of step rows, each colour-coded by `type` (matches
    the WorkflowProgressBar's type badge palette so the two
    views read as the same data).
  - `.workflow-editor-textarea` — added `tab-size: 2` and
    a two-layer `linear-gradient` background for indent
    guides.
  - `.workflow-editor-stat-dirty` — a yellow `● unsaved`
    badge in the status row.

### Backend

No backend changes. The daemon's `WorkflowReader` was the
schema contract; the editor now exposes the user's view of
that contract more directly, but the source of truth is
unchanged.

## Trade-offs

### Why a regex walker, not a YAML parser?

Two reasons:

1. **No new dep** — adding `js-yaml` (or anything similar) is
   200KB+ of bundle for one modal. The walker is 50 lines
   of TS, ships in 1.5KB, and handles the common cases.
2. **Best-effort is the right contract** — the editor's
   parse is a usability hint. The user gets a "5 steps" pill
   while typing; if they get it wrong, the daemon's
   `WorkflowReader` is the next line of defence and its
   error is what they see on Save. A full YAML parser
   would also catch all the corner cases the user might
   write (anchors, multi-line scalars, flow style), but
   the daemon catches those too — and the daemon's
   contract is the one the user has to satisfy.

The walker handles:

- Top-level scalars (`name:`, `description:`, `version:`)
- `steps:` block with `- id: ...` children (2-space indent)
- `type:` on the same line OR on the next child line
- Quoted / unquoted values (`"foo"` → `foo`)

The walker does NOT handle:

- `inputs:` map (we just count it as "exists" — the user
  has to read the body to know what's in it)
- Nested `parallel` branches (they show as "branch-a /
  branch-b" rows; the user has to know which parallel they
  belong to)
- Multi-line scalars (the `|` and `>` block scalars are
  treated as a single string with embedded newlines, which
  is fine for the preview but means the line number is the
  line of `description:` itself, not the body)
- YAML anchors and aliases

If a workflow uses the unsupported features heavily, the
step count will be off. The save still works; the user
just doesn't get a step count.

### Why not a real drag-drop visual editor?

The "drag-drop workflow editor" idea (a visual canvas where
steps are nodes and you drag to reorder) was raised as a
follow-up to R102b. After discussion with the team, we
decided against it for R110-4 because:

1. **The YAML is the contract** — the daemon's
   `WorkflowReader` reads the YAML. A visual editor that
   produces YAML is a translator; a visual editor that
   bypasses YAML is a new contract. Adding the translator
   is a lot of work for a niche feature.
2. **The user's workflow files are 20-60 lines** — the
   5-step `ship-it.yaml` is 56 lines. A visual editor is
   great for 50-step workflows; for 5, the YAML is faster
   to read and edit.
3. **The step list preview already does the navigation**
   — clicking a row jumps to the line, which is the
   "jump to a step" use case from drag-drop. Reordering
   via click + drag would still be nice, but it's a
   follow-up, not a blocker.

If a future round wants true drag-drop reordering, the
right shape is:

- Render the step list as a sortable list (drag handles
  on each row)
- On drop, splice the step in the YAML (read the block,
  remove from old line range, paste at new line range)
- Save the new YAML

That's about 200 lines of TS + a drag library
(`@dnd-kit/sortable` is the standard). It's a clean
follow-up; R110-4 ships the 80% case first.

## Tests

- aethercode-core: 851/851 (no backend changes)
- aethercode-desktop: tsc clean, vite build clean
  - CSS: 95.41 KB (+8.6 KB for the new palette / steplist
    / dirty badge styles)
  - JS: 498.42 KB (+9.9 KB for the new extractWorkflow
    helper, the 5 snippet templates, and the palette /
    jump / dirty logic)

## Files

- `aethercode-desktop/src/components/WorkflowEditorModal.tsx`
  (rewritten — same exports, new internals + new step list
  + new palette)
- `aethercode-desktop/src/components/WorkflowEditorModal.css`
  (extended with palette / steplist / dirty badge styles)
