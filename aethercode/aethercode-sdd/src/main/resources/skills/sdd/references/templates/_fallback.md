<!--
Fallback templates — used only when the bundled spec-kit templates
(constitution / specify / plan / tasks) are unavailable (missing file,
truncated, corrupted). Keep these minimal — one section per phase output.
-->

# Minimal fallback templates

Use these ONLY when `references/templates/<phase>-template.md` is missing
or corrupted. The bundled templates are authoritative — pull new copies
from `github/spec-kit/templates` if they go stale.

## constitution (项目原则) — fallback

# Project Constitution — <feature>

**Slug**: `<slug>` | **Date**: <YYYY-MM-DD>

## Core Principles

### I. Code Quality
<bulleted list of non-negotiables, e.g. naming, file-size, lint rules>

### II. Testing Standards
<coverage threshold, required test types, CI gate>

### III. User Experience Consistency
<error handling, i18n, accessibility>

### IV. Performance & Reliability
<latency targets, availability, observability>

### V. Security
<input validation, secrets, dependency audit>

## Governance
- Authority: principles above are binding gates
- Amendments: require PR with rationale + maintainer approval
- Versioning: SemVer (MAJOR = breaking governance, MINOR = new principle, PATCH = clarification)

**Version**: 1.0.0 | **Ratified**: <DATE>

---

## specify (需求分析) — fallback

# Feature Specification — <feature>

**Slug**: `<slug>` | **Date**: <YYYY-MM-DD>

## Summary
<1-2 sentences>

## Functional Requirements
- FR-1: <requirement>
- FR-2: <requirement>
- ...

## Non-Functional Requirements
- NFR-1: <requirement with measurable target>
- NFR-2: <requirement with measurable target>

## Acceptance Criteria
- AC-1: Given <precondition>, when <action>, then <observable result>
- AC-2: ...

## Out of Scope
<explicit list of what this spec does NOT cover>

---

## plan (详细设计) — fallback

# Implementation Plan — <feature>

**Slug**: `<slug>` | **Date**: <YYYY-MM-DD>

## Architecture
<high-level architecture, key modules, data flow>

## Module Breakdown
- `<module>` — <responsibility> — <file(s)>
- ...

## Interface Contracts
- `<API>` — <signature> — <semantics>

## Data Model
- `<entity>` — <fields> — <relations>

## Risks
- <risk> — <mitigation>

## Constitution Check
<cross-check each plan decision against constitution principles>

---

## tasks (任务分析) — fallback

# Tasks — <feature>

**Slug**: `<slug>` | **Date**: <YYYY-MM-DD>

## T-01: <title>
- depends_on: (none / [T-NN])
- files_to_create_or_modify: <list>
- acceptance_check: <verifiable check>

## T-02: <title>
- depends_on: [T-01]
- ...