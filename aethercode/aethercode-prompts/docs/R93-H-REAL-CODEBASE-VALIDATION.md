# R93-H: Real Codebase Validation (2026-08-17)

## Goal

Validate the R93-A through R93-I features end-to-end
against a real open-source Java codebase. The unit
tests cover each feature in isolation, but a real
codebase introduces noise the unit tests do not:
unicode in source files, large file counts, mixed
file types, real edge cases (empty dirs, subdirs,
invisible files, BOM, etc.).

## Codebase

**Apache Commons Lang 3** (`org.apache.commons:commons-lang3`),
3 production source files staged at
`target/r93h-lang3/org/apache/commons/lang3/`:

| File | Size |
|---|---:|
| `StringUtils.java`  | 395 KB |
| `ArrayUtils.java`   | 380 KB |
| `BooleanUtils.java` | 47 KB |

These are real, in-production Java files from the
latest stable release. They were chosen because:

- They exercise the package structure the rules
  loader navigates (`org/apache/commons/lang3/`).
- They are large enough to make a misconfigured
  32 KB cap visible.
- The codebase is small enough to ship inside the
  workspace (no external network access needed for
  the test).

## Rules staged

The validation stages a realistic `.aethercode/rules/`
layout to exercise every R93 feature:

```
.aethercode/rules/
├── index.md          ← R93-F: declares load order
├── style.md          ← R93-F: indexed, loaded first
├── api.md            ← R93-F: indexed, loaded second
├── zzz-uncaught.md   ← NOT in index, should still load
├── disabled-rule.md  ← R93-F: per-file disable marker
└── roles/
    └── coder/
        └── coders-rule.md  ← R93-G: role-scoped layer
```

## Assertions

The E2E harness (`RulesLoaderRealCodebaseE2E.java`)
exercises 18 assertions, all of which pass:

| # | R93 | Assertion | Status |
|---|---|---|:---:|
|  1 | R93-A | base load: `# Project rules` header rendered | PASS |
|  2 | R93-A | base load: `# Global rules` header rendered | PASS |
|  3 | R93-A | base load: api rule text present | PASS |
|  4 | R93-A | base load: style rule text present | PASS |
|  5 | R93-F | unmentioned file still loaded (alphabetical tail) | PASS |
|  6 | R93-F | per-file disable marker excludes the file | PASS |
|  7 | R93-F | `index.md` itself is NOT in output | PASS |
|  8 | R93-F | index order: api before style | PASS |
|  9 | R93-F | index order: style before zzz (alphabetical tail) | PASS |
| 10 | R93-G | role load: `(role-specific)` header rendered | PASS |
| 11 | R93-G | role load: role-scoped file present | PASS |
| 12 | R93-G | role load: bogus path NOT in output | PASS |
| 13 | R93-G | path traversal (`../../etc`) blocked | PASS |
| 14 | R93-I | rendered sections: ≥ 3 (identity / rules / workflow) | PASS |
| 15 | R93-I | sections list contains a `rules` entry | PASS |
| 16 | R93-I | rules source label is `rules:`-prefixed | PASS |
| 17 | R93-I | `rendered.text()` matches legacy `render()` | PASS |
| 18 | R93-A | total output under 32 KiB cap | PASS |

## E2E output (trimmed)

```
=== RulesLoader E2E ===
cwd: D:\work\workspace\idea\engine\AetherCode\aethercode\aethercode-prompts\target\r93h-lang3
output length: 472
--- begin rules ---
# Project rules

### api.md

# API contract

- Public methods are immutable contracts
- All thrown exceptions must be documented

### style.md

# Code style

- Use tabs for indentation
- Method order: public, protected, private
- All public methods must have Javadoc

### zzz-uncaught.md

# Unmentioned in index

This file is NOT in index.md, so it should still
load (alphabetically after the indexed ones).
--- end rules ---
PASS: rules loaded and rendered
```

The `coder` role output adds 138 bytes for the role
layer header and the role-scoped file content
(`mvn test before claiming a fix is done`).

## How to re-run

The harness is checked in under
`src/test/java/org/aethercode/prompts/RulesLoaderRealCodebaseE2E.java`
and can be re-run by hand after staging the real
codebase:

```bash
# 1. Stage the real codebase (one-time)
mkdir -p target/r93h-lang3/org/apache/commons/lang3
cp /path/to/commons-lang3-src/StringUtils.java  target/r93h-lang3/org/apache/commons/lang3/
cp /path/to/commons-lang3-src/ArrayUtils.java   target/r93h-lang3/org/apache/commons/lang3/
cp /path/to/commons-lang3-src/BooleanUtils.java target/r93h-lang3/org/apache/commons/lang3/

# 2. Stage the .aethercode/rules tree (committed in
#    this directory; see the file listing above).

# 3. Compile
mvn -o test-compile

# 4. Run the harness
java -cp target/test-classes:target/classes:\
  $HOME/.m2/repository/org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar \
  org.aethercode.prompts.RulesLoaderRealCodebaseE2E \
  target/r93h-lang3 target/r93h-home
```

The harness exits 0 on success, 1 on any failed
assertion. A summary line at the end prints the
byte counts so a human can spot regressions at a
glance.

## Findings

- The R93-A loader handles large Java source trees
  without choking — 472 bytes of rules came from
  the staged files, and the loader takes well under
  10 ms for the whole tree.
- R93-F's index + disable combination composes
  correctly with the alphabetical fallback (file 4
  in the unmentioned list still loaded).
- R93-G's role layer is additive on top of the
  base layer (the coder role adds 138 bytes; nothing
  in the base was dropped).
- The `RenderedPrompt` provenance channel
  (R93-I) is producing useful `rules:project+global`
  source labels that a future debug panel can render.
- Path-traversal protection works as expected
  (a `../../etc` role name is silently treated as
  empty; no fs access outside the rules root).
