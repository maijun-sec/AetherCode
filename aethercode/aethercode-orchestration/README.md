# aethercode-orchestration

R-mod-1: AI agent orchestration infrastructure, extracted from
`aethercode-evals` into a standalone Maven module so it can be
reused by other modules (deepagents, tools, etc.) without pulling
in the full eval benchmark suite.

## Status (as of R-mod-1)

**Scaffolded only.** The Maven module + parent `pom.xml` registration
are in place, but the source code still lives under
`aethercode-evals/src/main/java/org/aethercode/evals/{verifier,selfcorrect,multiagent,orchestration,perf}/`.
A future R-mod-2 round will physically move the five packages into
this module and rewrite the import paths. See `doc/round-notes/R-mod-1-ORCHESTRATION-MODULE.md`
for the full plan, the package mapping, the import path delta, and
the migration script.

## Why this split

The five packages above are **infrastructure** (V → self-correct →
ensemble → runtime + cost ceiling/cache/token counter), not eval
benchmark glue. Once they live in a dedicated module:

- Other modules (e.g. `aethercode-deepagents`, `aethercode-tools`,
  `aethercode-workflows`) can depend on orchestration primitives
  without depending on the `aethercode-evals` benchmark runner,
  schema registry, or shell-runner.
- The benchmark runner can iterate on eval-benchmark-specific code
  without churn leaking into shared infrastructure.
- The two can version independently: a verifier bug fix can ship
  without a benchmark suite re-release.

## Module structure (target)

```
aethercode-orchestration/
├── pom.xml
└── src/
    ├── main/java/org/aethercode/orchestration/
    │   ├── verifier/        (was aethercode-evals.verifier)
    │   ├── selfcorrect/     (was aethercode-evals.selfcorrect)
    │   ├── multiagent/      (was aethercode-evals.multiagent)
    │   ├── runtime/         (was aethercode-evals.orchestration)
    │   └── perf/            (was aethercode-evals.perf)
    └── test/java/org/aethercode/orchestration/...
```

## Public API surface (unchanged after move)

The public class names + method signatures stay the same; only the
package prefix changes (`org.aethercode.evals.X` →
`org.aethercode.orchestration.X`). The migration script in the
R-mod-1 plan doc handles the mechanical rewrite across the
monorepo.
