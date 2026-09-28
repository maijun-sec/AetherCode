# R373 — Per-agent Dashboard UI (2026-09-28)

The R370+R371+R372 rounds shipped the **plumbing** for per-agent
observability: the registry tracks running/completed/failed counts,
token usage, circuit-breaker state, and concurrency quota. R373
puts a UI on top of that plumbing so the user can actually see
what's happening across all the agents in the workspace.

This is a usage document — for design rationale + commit history,
see `release/RELEASE-NOTES-r370.txt` (R373 section).

---

## What you get

A new **Dashboard** tab inside the existing SubagentPanel (the
panel that lives in the right-hand sidebar of the desktop). When
you click it, you see:

1. **A header row of global totals** ("running / completed /
   failed / tokens / agents / circuit") so a glance tells you
   the workspace's overall state.
2. **A grid of per-agent cards** — one card per agent that has
   ever run in the workspace, sorted by agent name.
3. **Live polling at ~1.5s** so the dashboard reflects new
   jobs as they start, and the OPEN-circuit countdown ticks
   down second-by-second.

---

## Where to find it

The Dashboard lives inside the existing SubagentPanel. Steps:

1. Open the desktop app (Tauri window).
2. Look at the right-hand sidebar — there's a panel header
   that currently shows "Subagents (N)" with a tab toggle.
3. Click **Dashboard** (the right tab in the header).
4. Done. To go back to the per-job list, click **Jobs**.

The tab is opt-in — the default view is still the per-job list,
so users who never look at the dashboard pay no polling cost.
Switching back to Jobs unmounts the dashboard component and
the 1.5s polling stops automatically.

---

## How to read the cards

Each card represents one agent (by `name`, e.g. `pm`, `coder`,
`z3-expert`). The card shows:

| Field            | What it means                                          |
|------------------|--------------------------------------------------------|
| **Header**       | Agent name (left) + circuit chip (right).              |
| **Stat tiles**   | 4 numbers:                                              |
|  ↳ *run*         |   Subagents currently RUNNING for this agent name.     |
|  ↳ *done*        |   Lifetime COMPLETED count for this agent.             |
|  ↳ *fail*        |   Lifetime FAILED count.                                |
|  ↳ *tokens*      |   Lifetime token usage, exact (no rounding).            |
| **Ratio bar**    | OK vs fail proportions as a stacked bar (lifetime %).  |
| **Footer left**  | "X× fail in a row" or "no recent failures".             |
| **Footer right** | "quota 1" — concurrency quota for this agent.           |

### Circuit chip

| Colour | Text              | Meaning                                                   |
|--------|-------------------|-----------------------------------------------------------|
| 🟢    | CLOSED            | Healthy — no recent failures, breaker is closed.         |
| 🟡    | HALF-OPEN         | Breaker tripped, now probing whether the cause fixed      |
|        |                   | itself. The next job will either re-close or re-open.     |
| 🔴    | OPEN `Ns`         | Breaker tripped, refusing new subagents for `Ns` more     |
|        |                   | seconds. New jobs of this agent name will fail fast until |
|        |                   | the countdown elapses. The `Ns` ticks down live.         |

The threshold for tripping is `3` consecutive failures (configurable
in `SubagentCircuitBreaker`). The OPEN duration is `60s` (also
configurable). Both reset on the first successful job.

### "X× fail in a row"

This is the consecutive-failure counter. Two patterns to watch for:

- `1× fail in a row` — a single hiccup, breaker is still CLOSED.
- `2× fail in a row` — one more failure and the breaker trips.
- `3× fail in a row` — breaker is OPEN (or just tripped).

### "quota N"

How many concurrent subagent jobs of this agent name are allowed
in flight. Default is `1` (serial). Currently the spawn path doesn't
yet consult this value — Future round.

---

## How to test it (5-min smoke test)

1. **Open the desktop app** and click the **Dashboard** tab in
   the right-hand SubagentPanel.
2. **You should see** "No agents have run yet. Once the model
   spawns a subagent..." in the panel area.
3. **Trigger a subagent** by typing into the chat box:
   > "Spawn a subagent to summarize the README and bring the
   > result back."
4. **Watch the Dashboard** — within ~1.5s a card appears for
   the agent name (e.g. `general-purpose` or whatever the
   spawn used), with `run: 1`.
5. **Wait for it to finish** — `run: 1` becomes `done: 1`
   and `run: 0`. Tokens counter increments.
6. **Trigger 3 failures in a row** to see the breaker trip:
   > "Spawn a subagent to read a file that doesn't exist."
   > "Spawn a subagent to read a file that doesn't exist."
   > "Spawn a subagent to read a file that doesn't exist."
7. **Watch the card's circuit chip** turn yellow → red, with
   the OPEN countdown ticking. While OPEN, new spawns of
   the same agent name should fail fast.
8. **Wait 60s** — the chip turns HALF-OPEN (yellow, no
   countdown), then a successful spawn turns it green again.

---

## Pairing with R370 workflows

The Dashboard is most useful when you have R370 workflow YAMLs
running multi-agent pipelines. Each workflow step that uses
`type: agent` will populate its agent's card.

Example workflow (run with the workflow editor or via
`workflow_run` RPC):

```yaml
- id: feature-flow
  type: pipeline
  steps:
    - id: spec
      type: agent
      name: pm
      prompt: "draft a one-paragraph product spec"
    - id: review
      type: reflection
      executor: coder
      critic: pm
      prompt: |
        Implement it. Spec:
        {{steps.spec.stdout}}
      max_rounds: 3
      accept_score: 0.85
```

While it's running, the Dashboard shows:

- `pm` card with `run: 1` (or higher if other pm spawns are queued)
- `coder` card with `run: 1` during the reflection's executor phase
- Both cards' `tokens` counters climb as the agents consume tokens
- If a step fails twice in a row, the `X× fail in a row` counter
  warns you before the breaker trips

---

## Pairing with R371 init / memory

Agents with `init:` (one-shot first-turn reminder) and `memory:`
(every-turn system prompt prefix) configured in their `agent.md`
work exactly as before — the Dashboard doesn't change their
behaviour, only exposes their aggregated state.

A multi-agent session with shared context will show all involved
agents on the Dashboard, with shared-context contributions visible
indirectly via higher token counts (the child agent sees more
prompt context, so its per-call token count is higher).

---

## What you should NOT use it for

- **Per-job debugging**: use the **Jobs** tab. The Dashboard is
  an aggregate view; per-job detail (cancel / insert-result / retry
  buttons) is only on Jobs.
- **Token budgeting enforcement**: the Dashboard shows usage but
  doesn't let you adjust limits. The token budget is set at
  spawn time (per-job cap). Future round will surface this.
- **Real-time debugging of running jobs**: the 1.5s poll is for
  summary view, not for live-tracing. Live events stream to the
  store via the existing `subagent_event` notification path —
  the StatusBar indicator at the bottom of the screen shows
  per-event status.

---

## Known limitations (out of scope, future rounds)

These are listed in the commit message; reproduced here for
user convenience:

1. **No "reset circuit" button** — circuits reset automatically
   after the 60s cooldown or when the half-open probe succeeds.
   A manual reset RPC is a future round.
2. **Polling doesn't pause when panel is hidden** — if the user
   switches to another app section, the dashboard still polls.
   This is wasteful but harmless; future round can wire it to
   the store's active-viewport observer.
3. **Concurrency quota is always 1** — the spawn layer doesn't
   consult the quota; future round needs to wire `setQuota` into
   `spawn_agent`.

---

## Where the code lives

```
aethercode-protocol/src/main/java/.../AetherCodeMethods.java
  + subagentDashboard(Object params) -> Map { ok, asOfMs,
                                              totals, agents }
  + dispatcher.register("subagentDashboard", this::subagentDashboard)

aethercode-protocol/src/test/java/.../AetherCodeMethodsR373Test.java
  (5 new tests covering the wire contract)

aethercode-desktop/src/lib/methods.ts
  + SubagentDashboardSnapshot / SubagentDashboardTotals /
    SubagentAgentMetric types
  + AetherCodeRpc.subagentDashboard(): Promise<...>

aethercode-desktop/src/components/SubagentDashboard.tsx
  (NEW, ~310 lines) — per-agent card grid, 1.5s polling,
                       stable sort, shallowEq re-render skip

aethercode-desktop/src/components/SubagentDashboard.css
  (NEW, ~190 lines) — colour tones matching the StatusBar's
                       subagent vocabulary, responsive grid

aethercode-desktop/src/components/SubagentPanel.tsx
  + Jobs / Dashboard tab toggle in the header
  + extracted `header` JSX so layout is identical across tabs
```

---

## Quick reference card

For the visual learner: the card looks like this in ASCII.

```
┌─────────────────────────────────┐
│ pm                       [CLOSED]│   ← name + circuit chip
├─────────────────────────────────┤
│  ┌────┐ ┌────┐ ┌────┐ ┌───────┐ │
│  │ 1  │ │ 12 │ │ 0  │ │ 18,420 │ │   ← run / done / fail / tokens
│  │run │ │done│ │fail│ │ tokens │ │
│  └────┘ └────┘ └────┘ └───────┘ │
│                                 │
│ ████████████░░░░░░░░░░░░░░░░░░░  │   ← OK/fail ratio bar
│                                 │
│ no recent failures    quota 1   │   ← footer
└─────────────────────────────────┘
```

If the breaker is OPEN, the card border turns red and the chip
shows `OPEN 47s` ticking down. If HALF-OPEN, border is yellow and
chip shows `HALF-OPEN`. CLOSED = neutral border, green chip.

---

## FAQ

**Q: The dashboard shows 0 tokens even after a long job. Bug?**

A: The token counter only updates when the spawn layer calls
`SubagentRegistry.addTokens(jobId, delta)` after each LLM
response. If the LLM is invoked outside the spawn layer (e.g.
a manual `chat` call), its tokens are tracked by the cost
tracker, not the dashboard. Future round will surface cost-tracker
totals on the same card.

**Q: I see a "general-purpose" agent on the dashboard but never
created one.**

A: The default spawn role when the LLM doesn't pick one is
`general-purpose`. This is normal — the LLM chose not to
specialise. To give an agent a custom name, configure it via
`agent.md` and reference it as `spawn_agent(agent_name="<name>")`.

**Q: The circuit opens for "coder" but my "pm" is fine. Why?**

A: Breakers are per-agent-name. A spike in `coder` failures
doesn't affect `pm`. This is intentional isolation — if your
reflection critic keeps rejecting coder's work, only coder
gets paused; pm can still run.

**Q: Can I see the actual audit log (what jobs ran, when, why)?**

A: Yes — open a JSON-RPC terminal and call
`subagent_list` (existing method) to get the full job list,
or check the daemon log at `%TEMP%/aethercode-daemon-port<PORT>.log`
where the registry's `BUDGET_BREACH` / `circuit breaker tripped`
audit lines land.

**Q: After R373, what's next?**

A: The commit message lists three future-round items:
1. circuit breaker reset button
2. viewport-aware polling pause
3. wire setQuota into spawn_agent

Also R374+ ideas (pre-existing, not R373 scope):
- subagent tree view (parent-child relationship visualisation)
- per-agent cost budget UI
- spawn history / replay

---

*End of R373 usage doc. Last updated 2026-09-28.*