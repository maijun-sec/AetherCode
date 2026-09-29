# Upstream: github/spec-kit

AetherCode SDD reuses phase definitions and templates from
[github/spec-kit](https://github.com/github/spec-kit).

## What AetherCode reuses from spec-kit

- **Phase names**: constitution / specify / clarify / plan / analyze / tasks / implement / converge
- **Phase order**: required phases (constitution → specify → plan → tasks → implement) + optional quality gates (clarify / analyze / converge)
- **Templates for constitution / specify / plan / tasks** (in `references/templates/`)

## What AetherCode does NOT integrate

- ❌ The `specify` Python CLI (we drive everything from chat via the Mavis agent)
- ❌ Slash command files (`.claude/commands/*.md`)
- ❌ Helper scripts (`setup-plan.sh`, `update-claude-md.sh`, etc.)
- ❌ Spec Kit's default `.specify/specs/<NNN>-<slug>/` path layout (we use `.aethercode/sdd/<slug>/`)

## AetherCode additions

- ✅ 8-phase flow (added `converge` as phase 8 for post-implementation review)
- ✅ Chinese phase titles (项目原则 / 需求分析 / 需求澄清 / 详细设计 / 一致性分析 / 任务分析 / 执行实现 / 收敛验证)
- ✅ `<cwd>/.aethercode/sdd/<slug>/` path layout (R294 起)
- ✅ `design.md` / `dev.log` file naming (R236 SSD naming convention, kept for compatibility with existing AetherCode tooling)
- ✅ **Hard pause after each phase** — Spec Kit's CLI is one-shot; AetherCode's SDD requires explicit user ✅ / ✏️ / ⏭️ between phases (per user feedback R312)
- ✅ Skip-aware artifacts — optional phases write `{skipped: true, reason}` when user opts out

## Attribution

If you publish or share SDD outputs, please credit both:

> AetherCode SDD built on [github/spec-kit](https://github.com/github/spec-kit).
> Phase definitions and templates adapted from the spec-kit templates under MIT.