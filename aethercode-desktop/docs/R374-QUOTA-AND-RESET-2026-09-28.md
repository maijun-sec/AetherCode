# R374 — Per-agent Quota + Circuit Reset UI (2026-09-28)

R373 shipped the dashboard view. R374 makes the
dashboard **controllable**: you can configure the per-agent
concurrency quota from the card footer, and you can
short-circuit the 60s circuit-breaker cooldown from the
card header when you've fixed the underlying cause.

Plus a side bonus: a pile of pre-existing TypeScript
duplicate-declaration errors that had been silently blocking
the first stage of `npm run build` (tsc -b) are gone, so
the Tauri build pipeline is now clean end-to-end.

This is a usage document. For commit history, see
`release/RELEASE-NOTES-r370.txt` (R374 section).

---

## What's new in the dashboard

### Card footer — click-to-edit concurrency quota

Every per-agent card has a small `quota N` label in the
footer. In R374 that label is now a **button**:

1. Click the pill (e.g. `quota 1`) → it turns into an
   inline number input, auto-focused.
2. Type a new value (or `0` to reset to the default of 1).
   Maximum accepted is 32; anything bigger is silently
   clamped to 32 (a typo guard).
3. Press **Enter** or click elsewhere to commit. The pill
   flashes `✓ saved` briefly; the next 1.5s dashboard poll
   replaces the input back with the new `quota N` label.
4. Press **Escape** to cancel without sending.

#### What the quota controls

The quota is the **max concurrent in-flight subagents of
the same agent name**. Default is 1 (serial). When you spawn
a second `pm` job while one is already RUNNING, the
`spawn_agent` tool errors with:

```
concurrent subagent quota exceeded for role 'pm' (0/1 in
flight); wait for one to finish or raise the quota via
subagentSetQuota RPC
```

#### When to raise the quota

- **Multiple summarisation tasks** — spawn 3 `pm` jobs
  to summarise 3 files in parallel. Set quota=3.
- **Pipeline fork** — R370's pipeline-of-agents step uses
  one role per step, so the default quota=1 is correct.
  Raising the quota only matters when the *same* role
  name is doing parallel work.
- **Speculative dispatch** — fire 2 `coder` jobs against
  the same prompt and pick the first to finish.

#### When to keep the quota at 1

- **Most reflective / critic loops** — the executor and
  critic should run sequentially so the critic has the
  executor's full output to grade.
- **Token-budgeted agents** — a chat-client-bound agent
  is faster single-threaded anyway; a higher quota
  doesn't speed things up but it does let you blow the
  budget faster if both spawns chat at once.

---

### Card header — Reset circuit button

When the chip shows **OPEN** (`OPEN 47s` ticking down) or
**HALF-OPEN**, a small dashed `Reset circuit` button
appears next to the chip. Click it:

1. Fires `subagentResetCircuit({ role })` to the daemon.
2. The next dashboard poll (≤ 1.5s) shows the chip turn
   **CLOSED**.
3. The pill flashes `✓ reset` briefly so you can see your
   click took effect.

#### When to use Reset circuit

- You've fixed the root cause (e.g. re-read a config
  file the agent was failing on, restarted an upstream
  service).
- You don't want to wait 60s for the natural cooldown.
- You want to test a fix without a 1-minute wait between
  iterations.

#### When NOT to use Reset circuit

- If the underlying cause is **still failing**, clicking
  Reset lets one more job through, which will fail again,
  which will re-trip the breaker (3 consecutive failures).
  So Reset is for "I've fixed it" moments, not "I'm feeling
  lucky" moments.
- The button is **only available on OPEN / HALF_OPEN**
  cards. CLOSED cards don't render it (nothing to reset).

#### What "cleared=false" means

If you click Reset on a role that was never tripped
(no breaker slot exists), the RPC returns `cleared=false`
but `ok=true` — the dashboard treats this as a silent
no-op, not an error. You probably won't hit this in
practice (the button only renders on tripped cards).

---

## How to test the new dashboard controls (5-minute smoke)

1. **Open the desktop app** → right sidebar → SubagentPanel →
   **Dashboard** tab.
2. **Raise a quota**: Click the `quota 1` pill on any
   agent card. Type `4`. Press Enter. The pill flashes
   `✓ saved`; next poll shows `quota 4`.
3. **Trigger 3 failures** for that agent (e.g. "spawn a
   subagent to read a file that doesn't exist" three
   times). The card border turns red and the chip shows
   `OPEN 60s` ticking down.
4. **Click Reset circuit**. The chip flips to `CLOSED`
   on the next poll. The pill flashes `✓ reset`.
5. **Spawn one more** of the same agent. It works (slot
   is free). It fails again. After 3 more failures
   the breaker trips again — this is the expected
   behaviour, demonstrating the breaker is healthy.
6. **Type `0`** into the quota pill and press Enter. The
   quota resets to 1 (the default). Verify by trying to
   register 2 of that role at once — second one fails.

---

## Pairing with R370 workflows

Quotas + circuits are most useful when the workflow YAML
includes `type: agent` steps for multiple agents:

```yaml
- id: parallel-summary
  type: agent
  name: pm
  prompt: "summarise {{step.input}}"
  parallelism: 2          # future R-round: per-step parallelism
```

To make a parallel summary fire 3 `pm` jobs at once, raise
the pm quota to 3 via the dashboard card. Until R-round
adds per-step parallelism config, the R374 quota IS the
concurrency knob for fan-out.

For R370 reflection loops, leave the quota at 1: executor
and critic run sequentially so the auditor has the
executor output to grade against.

---

## What's NOT in R374 (out of scope)

The commit message lists three items, reproduced here for
user convenience:

1. **Reset all circuits** button — a single button in
   the dashboard header that resets every tripped
   breaker. Useful for fleet-wide recovery after a bad
   deploy. Easy add — same `subagentResetCircuit` RPC
   with a `role: "*"` special-case.
2. **Quota persistence** — quota overrides live in
   memory only. Restart the daemon → back to defaults.
   Future round reads from `~/.aethercode/agents.yaml`.
3. **Per-step parallelism in workflows** — the R370
   workflow YAML doesn't have a `parallelism:` field on
   agent steps. The R374 quota is the workaround: raise
   the quota and the steps fan out automatically.

---

## TypeScript cleanup bonus

`npm run build` (which runs `tsc -b && vite build`) used
to fail on the first stage because of pre-existing
duplicate-declaration errors in `methods.ts` (two
`listAgents` / `getAgentBody` signatures) and
`store/index.ts` (two `refreshAgents` / `agents`
declarations). R374 deletes the duplicates. The build now
passes cleanly:

- aethercode-core: 1263 tests, 0 fail, 2 skip
- aethercode-protocol: 331 tests, 0 fail
- aethercode-tools: 434 tests, 0 fail, 2 skip
- aethercode-evals: 667 tests, 0 fail

---

## Quick reference card

```
┌────────────────────────────────────────────────────────┐
│ pm                  [OPEN 47s] [Reset circuit]          │  ← new button
├────────────────────────────────────────────────────────┤
│   ┌────┐  ┌────┐  ┌────┐  ┌───────┐                      │
│   │ 1  │  │ 12 │  │ 0  │  │18,420 │                      │
│   │run │  │done│  │fail│  │tokens │                      │
│   └────┘  └────┘  └────┘  └───────┘                      │
│ ████████████░░░░░░░░░░░░░░░░░░░                         │
│ 1× fail in a row                  [quota 1] ← click!    │  ← new click
└────────────────────────────────────────────────────────┘
```

Both new controls follow the same design language as the
rest of the dashboard: small, dashed border until focused,
solid border on hover, brief flash for state change.

---

## FAQ

**Q: I raised the quota to 5 but the existing in-flight job
still occupies one slot. Does it auto-promote?**

A: No — the new quota applies to future register() calls
only. The 5 in-flight jobs (when they start) all see the
same 5-cap. If you have 1 in-flight and raise to 5,
you can spawn 4 more.

**Q: Reset circuit vs waiting for the 60s cooldown?**

A: Same effect on the breaker. Reset just skips the
wait. Useful when you're iterating on a fix and don't
want a 1-minute pause between attempts.

**Q: I clicked Reset but the chip still shows OPEN. Bug?**

A: Probably the dashboard hasn't polled yet (1.5s
interval). Wait one cycle. If still OPEN, check the
console for `subagentResetCircuit failed` — the RPC may
have hit an error.

**Q: Can the quota be set to 0?**

A: Yes — quota=0 is the "remove the custom override"
sentinel, which falls back to DEFAULT_QUOTA (= 1). The
dashboard sends 0 when you click the pill and the input
shows 0.

**Q: After R374, what's next?**

A: Three items from the commit message:
1. Reset all circuits (one button in the dashboard header)
2. Quota persistence in `~/.aethercode/agents.yaml`
3. Per-step parallelism in workflow YAML

Or any of the pre-existing backlog items from the
release notes:
- spawn_agent with `agent_name` from runtime context
- shared-context tracing panel (R371 internals)
- per-agent cost budget UI

---

*End of R374 usage doc. Last updated 2026-09-28.*