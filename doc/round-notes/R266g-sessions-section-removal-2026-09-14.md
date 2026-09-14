# R266g: drop the redundant "SESSIONS" header from the LeftPanel inner list

## Trigger
The user said the left rail shows "SESSIONS" with a count + a single
"新会话" row nested under the project group, and asked to remove the
sessions list section entirely. The 9h · 85 msg session was rendered
as "新会话" because the desktop was still running the 9/13 23:02
`aethercode.jar` (the R266d fixes hadn't shipped yet — see R266f
follow-up). The screenshot:

  abc_1           1 +
  SESSIONS        1
    ● 新会话
      9h · 85 msg

## Root cause
`LeftPanel` renders the project grouping via `ProjectGroupList`, which
embeds `SessionList` inside each project's `<details>` body. The
inner `SessionList` (added in R223 to give the LeftPanel a flat-filter
view as well) was still rendering its own `<div class="section-header">`
+ count + the "+ 新会话" button. The project group's `<summary>`
already shows the project name + the same session count, so the inner
header was a redundant second-level label the user explicitly did not
want.

## Fix (commit pending — staged on top of 0f9e628)
- `SessionList.tsx`:
  - new `hideSectionHeader?: boolean` prop (default `false`,
    preserves the existing flat-filter / standalone use case)
  - the inner `<div class="section-header">Sessions</div>` is wrapped
    in `{!hideSectionHeader && (...)}`
  - the "+ 新会话" button stays — it's the per-group create
    action and the user did not ask to remove it
- `ProjectGroup.tsx`:
  - passes `hideSectionHeader` to the inner `SessionList` (no value,
    defaults to `true`)

## Result
- the inner "SESSIONS" header + count row is gone when the parent
  already provides its own summary
- the "+ 新会话" button stays at the bottom of each project group
  (per-project create is the canonical pattern, not the
  global "+ 新会话")
- the flat-filter view in `LeftPanel` (`isFilterActive` branch) is
  unaffected — it still shows the `Sessions` header because there's
  no project group wrapping it

## Tests
none new. `SessionList` rendering is exercised by the existing
`session/__tests__/LeftPanelWire.test.tsx` source-pin (regex matches
"TaskSummary + ProjectList + TaskList"). The new prop is purely
additive and defaults to the old behaviour, so the source-pin
remains green.

## Risk
zero. the change is purely additive: a new prop with a safe
default. Even if a future caller forgets to pass the prop in a
nested context, the inner header still renders — same as before.
