# R294 — SDD (Spec-Driven Development) built-in workflow. Reuses the
# 6-phase Spec-Driven Development process from github/spec-kit (MIT) as
# the orchestrator + LLM-prompt backbone, but writes products to
# AetherCode's internal directory layout (R236 SSD convention):
#
#   0. constitution  →  .aethercode/ssd/<slug>/constitution.md
#   1. specify       →  .aethercode/ssd/<slug>/spec.md
#   2. clarify       →  .aethercode/ssd/<slug>/clarify.json
#   3. plan          →  .aethercode/ssd/<slug>/design.md
#   4. analyze       →  .aethercode/ssd/<slug>/analyze.json
#   5. tasks         →  .aethercode/ssd/<slug>/tasks.md
#   6. implement     →  .aethercode/ssd/<slug>/dev.log
#   7. converge      →  .aethercode/ssd/<slug>/convergence.json
#
# `<slug>` is derived from the user's prompt (lowercase, ASCII,
# hyphen-joined, ≤10 chars). Constitution is per-feature (R236 layout),
# not project-level (Spec Kit's `.specify/memory/` convention was
# dropped because it leaks governance across features and forces the
# user to pick where to put it).
#
# Default hard rules below are concatenated with each phase's per-phase
# system prompt at LLM-call time. Edit project-locally by dropping a
# sdd.yaml at <cwd>/.aethercode/ssd/sdd.yaml with a `hardRules:` key.
#
# These rules were discovered during R236/R235 and are the single most
# important reason the produced artefacts come out clean. Without them
# the model emits <think>…</think> blocks, chatty preambles, and tool
# calls mid-phase — all of which would corrupt the artefact.

version: 1
name: sdd
description: |
  R294 — SDD (Spec-Driven Development) built-in workflow. Reuses the
  6-phase Spec-Driven Development process from github/spec-kit (MIT) as
  the orchestrator + LLM-prompt backbone, but writes products to
  AetherCode's internal directory layout (R236 SSD convention). The
  feature directory `<slug>` is derived from the user's intent (≤10
  ASCII chars, hyphen-joined).

hardRules: |
  [HARD RULES FOR THIS TURN]
    - Do NOT call any tools (file_read, bash, ask_user_question, …).
    - Do NOT emit <think>…</think> blocks, "Draft:" / "Note:" / "I will"
      / "Let me" / "Sure," / "Here is" preamble lines.
    - Do NOT reference prior conversation, the user's intent verbatim,
      or any meta-commentary about being an AI.
    - Output ONLY the artefact markdown, starting with the H1 heading
      defined in the phase template (no leading whitespace, no
      pre-title commentary).
    - Keep all `[NEEDS CLARIFICATION: ...]` markers EXACTLY as
      defined in the template so the user's /speckit.clarify
      phase can collect them. Do not silently drop them.
    - FILL IN the user-supplied placeholders that are NOT
      `[NEEDS CLARIFICATION]` markers, e.g. `[FEATURE NAME]`,
      `[DATE]`, `[$ARGUMENTS]`, `[Brief Title]`,
      `[Describe this user journey in plain language]`,
      `[Why this priority]`, `[Independent Test]`,
      `[Given initial state], When [action], Then [expected outcome]`,
      `[Specific capability, e.g. "allow users to create accounts"]`,
      `[Measurable metric, e.g. "Users can complete account creation
      in under 2 minutes"]`. Replace these with concrete content
      derived from the user's intent / today's date / the current
      feature slug.
    - Acceptance-criteria / success-criteria placeholders ARE
      user-supplied: fill them in. `[NEEDS CLARIFICATION]` blocks
      are NOT user-supplied in this turn — keep them verbatim.

maxWaitMs: 240000
idleEndMs: 2500
artefactRoot: .aethercode/ssd
slugPolicy: sequential
enableClarify: true
enableAnalyze: true
enableConverge: true