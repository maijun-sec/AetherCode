# R292 — SDD (Spec-Driven Development) built-in workflow. Mirrors Spec Kit's
# 6-phase Spec-Driven Development process (github/spec-kit, MIT).
#
#   0. constitution  →  .specify/memory/constitution.md   (project governance)
#   1. specify       →  .specify/specs/<NNN>-<slug>/spec.md
#   2. clarify       →  (in-place edits to spec.md)
#   3. plan          →  .specify/specs/<NNN>-<slug>/plan.md
#   4. analyze       →  (cross-artifact consistency check)
#   5. tasks         →  .specify/specs/<NNN>-<slug>/tasks.md
#   6. implement     →  .specify/specs/<NNN>-<slug>/logs/implement.log
#   7. converge      →  .specify/specs/<NNN>-<slug>/convergence.json
#
# Default hard rules below are concatenated with each phase's per-phase
# system prompt at LLM-call time. Edit project-locally by dropping a
# sdd.yaml at <cwd>/.specify/sdd.yaml with a `hardRules:` key.
#
# These rules were discovered during R236/R235 and are the single most
# important reason the produced artefacts come out clean. Without them
# the model emits <think>…</think> blocks, chatty preambles, and tool
# calls mid-phase — all of which would corrupt the artefact.

version: 1
name: sdd
description: |
  R292 — SDD (Spec-Driven Development) built-in workflow. Mirrors
  Spec Kit's 6-phase Spec-Driven Development process (github/spec-kit,
  MIT). Per-phase human-in-the-loop confirmation. Constitution is
  project-scoped (one file at .specify/memory/constitution.md, reused
  across all features in the project).

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
    - Keep all checklist / acceptance / [NEEDS CLARIFICATION] markers
      exactly as defined in the template so downstream tooling can
      parse them.

maxWaitMs: 240000
idleEndMs: 2500
artefactRoot: .specify
slugPolicy: sequential
enableClarify: true
enableAnalyze: true
enableConverge: true