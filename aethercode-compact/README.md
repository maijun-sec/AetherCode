# aethercode-compact

> Context compression for the AetherCode agent runtime — 3-layer compaction
> pipeline (Microcompact, Session-memory, Full LLM).

AetherCode is a long-running coding agent. The conversation can easily exceed
the model's effective context window, so we compress the prompt in three
layers, exactly mirroring the Claude Code tiered approach (because it is the
most cost-aware).

This module owns the pipeline. It is **dependency-free** at the type level:
it accepts plain TypeScript objects (`Message`, `ToolResult`, `ModelInfo`,
…) and an injectable `LlmClient` interface. Callers wire it into the
runtime (`aethercode-core` / `aethercode-tui`) by translating their own
shapes into these.

## Tasks covered (Phase 2 — T-100 to T-131)

| ID | Title | Status |
|---|---|---|
| T-100 | `aethercode-compact` module skeleton | ✅ |
| T-101 | `CompactPipeline` class with options | ✅ |
| T-102 | `CompactInput` / `CompactResult` types | ✅ |
| T-103 | `tsconfig.json` (strict) + lint config | ✅ |
| T-104 | Vitest config + first test | ✅ |
| T-110 | Identify read-class tool results | ✅ |
| T-111 | Replace body with placeholder when over T bytes (4 KB) | ✅ |
| T-112 | Cache-aware branch (no local edit when warm) | ✅ |
| T-130 | 4-shot LLM prompt (8-segment schema) | ✅ |
| T-131 | JSON schema validation of the 8 fields | ✅ |

Layer 2 (session-memory compact, T-120..T-124) is deferred to Phase 4
because it requires the supervisor in `aethercode-tasks` to be in place.
Layer 3 history replacement (T-135), per-segment token cap (T-136), and
the 2-step draft/finalize (T-132) follow the same design but are scheduled
for the next round.

## Usage

```ts
import { CompactPipeline, type LlmClient } from "aethercode-compact";

const pipeline = new CompactPipeline({
  ...CompactPipeline.defaults(),
  layer3RecentKeep: 5,
});

const llm: LlmClient = {
  complete: async (prompt, opts) => callYourModel(prompt, opts),
};

const result = await pipeline.compact(input, { llm });
// result.layer ∈ {1, 3}
// result.summary is populated when layer === 3
```

## API surface

```ts
class CompactPipeline {
  static defaults(): CompactPipelineOptions;   // { cacheWarmWindowMs, layer3RecentKeep, layer3MaxOutputTokens, layer1ClearThresholdBytes }
  compact(input: CompactInput, opts?: CompactOptions): Promise<CompactResult>;
}

function microCompact(input: CompactInput, opts: Layer1Options): CompactResult;
function llmCompact(input: CompactInput, llm: LlmClient, opts: Layer3Options): Promise<CompactResult>;
function buildPrompt(history: Message[], opts?: { promptTemplate?: string }): string;
function extractJsonFromResponse(text: string): string;
function parseAndValidate(raw: string): CompactSummary;
```

## 8-segment schema

| # | Field | Description |
|---|---|---|
| 1 | `userTaskIntent` | The original goal |
| 2 | `projectContext` | Which files, modules, concepts matter |
| 3 | `approachTaken` | What was tried and what worked |
| 4 | `bugsAndFailures` | Concrete errors and their fixes |
| 5 | `toolOutputsRetained` | Tool results we explicitly want to keep |
| 6 | `decisionsAndTradeoffs` | Choices the model made and why |
| 7 | `openTodos` | What is still pending |
| 8 | `nextStepPlan` | What the next turn should do |

Each field is a non-empty string capped at ~1 K tokens (4 000 chars). The
schema is **strict** — unknown fields are rejected. See `src/schema.ts`.

## Scripts

```bash
npm install        # install deps
npm run typecheck  # tsc --noEmit
npm test           # vitest run
npm run build      # tsc → dist/
```

## Design references

- `spec.md §2` — what context compression must do
- `design.md §2.1` — module shape and `CompactPipeline` class
- `design.md §2.2` — Layer 1 microcompact (this module)
- `design.md §2.4` — Layer 3 8-segment summary (this module)
