# R388i — The list and the action disagreed about what exists

> Round: R388i
> Date: 2026-11-06
> Touches: `aethercode/aethercode-sdk/src/main/java/org/aethercode/sdk/WorktreeManager.java`,
> `aethercode/aethercode-sdk/src/test/java/org/aethercode/sdk/WorktreeManagerR388iTest.java`

---

## How it was found

Not by a test. By the **end-to-end probe** of the packaged jar — the
same probe that had already proven the TUI reaches `✓ ready`.

The probe drove the daemon over HTTP with a **real** git repo
(`D:\work\workspace\idea\engine\RAG`) as the worktree source:

```
worktreeStatus  → available: true, sourceRepo: <the RAG repo>
addWorktree     → ok, branch aethercode/r388-e2e, isolated: true
listWorktrees   → the new worktree IS listed
mergeWorktree   → "no such worktree: r388-e2e"
```

The daemon had just **shown** the worktree, and then refused to act on
it by name.

## Why the unit tests did not catch it

`WorktreeManagerR388Test` (21 cases) and `WorktreeManagerR97MTest`
(5 cases) both pass. Every one of them creates its worktree **through
the manager** (`addWorktree`), so the worktree is always in the
registry. The failing case is the one the tests never made: a
worktree that exists in **git** but not in the registry.

## The two sources

`listWorktrees` returns the **union** of two sources:

| source | what it holds | why it exists |
|---|---|---|
| `states` (the manager's registry) | worktrees **this manager** created | carries `sourceRepo` |
| `git worktree list --porcelain` | worktrees **git** knows about | the user may have created one outside AetherCode (`git worktree add`) and wants to attach a session to it |

That second source is deliberate and correct — the TUI panel shows
those rows so the user can pick one.

And both actions looked only at the first:

```java
// mergeWorktree
WorktreeState st = states.get(name);
if (st == null) {
    return new MergeResult(false, targetBranch, null, null,
            "no such worktree: " + name, false, false);
}
```

```java
// removeWorktree
WorktreeState st = states.get(name);
if (st == null) return false;
```

## Why this is the R377 shape one level up

R377 was: the TUI's refusal table claimed a method was missing, while
the daemon actually served it under a **synonym**. A refusal is a
claim about *another system's vocabulary*, and it could not verify
itself.

R388i is the same shape one level up:

- the **list** is a claim about what exists, sourced from two places;
- the **action** resolves from one of them;
- so the list can promise something the action will refuse.

**The user is the one who finds out**, because the gap only opens on
the worktrees the manager did not create.

## The fix

Both actions now resolve through one helper:

```java
private WorktreeState resolveForMerge(String name) {
    if (name == null || name.isBlank()) return null;
    WorktreeState st = states.get(name);
    if (st != null) return st;

    String repo = resolvedSourceRepo();
    if (repo == null || repo.isBlank()) return null;

    String wanted = name.trim();
    for (WorktreeState git : listGitWorktrees()) {
        boolean nameMatches = wanted.equals(git.name());
        boolean pathMatches = wanted.equals(git.path().getFileName().toString());
        if (!nameMatches && !pathMatches) continue;
        // git's record has no sourceRepo; the worktree
        // belongs to the repo we just asked.
        return new WorktreeState(git.name(), git.path(), git.branch(),
                repo, git.isolated(), git.reason());
    }
    return null;
}
```

Two details that are not optional:

1. **The name is matched two ways.** git's record's `name` is the
   directory's file name — that is what the panel shows — but the user
   may also type the path. Both resolve to the same record.

2. **`sourceRepo` is FILLED IN.** `fromPorcelain` builds the state
   with `sourceRepo = null`, because git does not know the worktree
   belongs to a repo — it *is* that repo's worktree. Without filling
   it in, the merge would fail a second time with a different
   message ("not a git worktree — nothing to merge"), and the fix
   would have moved the symptom instead of removing it.

## The regression test

`WorktreeManagerR388iTest` — 6 cases:

| case | what it pins |
|---|---|
| `listWorktrees_showsAGitSideWorktree` | the git-side worktree IS visible, and is NOT in the registry (the precondition) |
| `mergeWorktree_mergesAGitSideWorktree` | the merge lands, and the file really appears on the target branch |
| `mergeWorktree_refusesADirtyGitSideWorktree` | the dirty-worktree refusal still applies, and names the file |
| `removeWorktree_removesAGitSideWorktree` | removal works, and git's list agrees afterwards |
| `mergeWorktree_stillRefusesAnUnknownName` | the "no such worktree" refusal still exists for a genuinely unknown name |
| `mergeWorktree_stillWorksForAManagerCreatedWorktree` | the pre-existing path does not regress |

**Proven to catch the bug**: with the fallback removed, 3 of the 6 go
red (`mergeWorktree_mergesAGitSideWorktree`,
`mergeWorktree_refusesADirtyGitSideWorktree`,
`removeWorktree_removesAGitSideWorktree`). The other 3 stay green —
they are the cases that were never broken, and they are what keeps
the fix from over-reaching.

## Lessons

- **Lesson 1308** — a "list" and its "action" that read **different
  data sources** make every listed item a future failure. If the list
  is a union, the action must be able to resolve **every** member of
  the union.
- **Lesson 1309** — a record imported from another source is missing
  the fields that source does not have. "The record exists" is not
  "the record is complete" — fill the gap yourself, or the fix
  relocates the symptom instead of removing it.
- **Lesson 1310** — the tests that passed were not evidence of
  correctness. They exercised the **one** path that worked. The gap
  was on a path no test made. **A test suite that only creates state
  through the code under test cannot see a defect in how that state
  was obtained elsewhere.**
