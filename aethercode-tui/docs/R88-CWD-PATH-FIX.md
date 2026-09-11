# R88 — CWD + Path Sandbox Fix

> **Status:** SHIPPED 2026-08-15
> **Scope:** file_write regression + 3 UX follow-ups from R86/R87 user feedback.
> **Headline:** the user's "以前可以写，现在不能写了" mystery is solved — a Windows
> case-sensitive path comparison in `FileWriteTool` was rejecting every
> `D:\tmp\abc\pom.xml`-style path the model emitted, and three related
> TUI/daemon plumbing bugs were hiding behind the same user-visible symptom.

## The bug (user-visible)

The user ran:
```
java -jar aethercode-0.2.1.jar tui --cwd d:\tmp\abc
```
and asked the model to scaffold a Java/Maven project. The TUI happily
showed long tool-call plans (`file_write ... 3m29s...`), but `d:\tmp\abc`
ended up with **only** the one early `a.txt` the user wrote manually —
**zero** `pom.xml`, **zero** `src/main/java/.../BubbleSort.java`, nothing.
Same prompt had worked on the 0.2.0 build, so the user reasonably read it
as a regression.

## Root cause #1 — Windows case-sensitive `Path.startsWith`

R87 added `-Daethercode.cwd=<user's --cwd>` to the `java` spawn so the
daemon could resolve relative paths against the user's intended cwd
(`aethercode-tui/src/jsonrpc.ts`). Before that, the tool fell back to
`Path.of("").toAbsolutePath()` and everything happened to match.

`FileWriteTool.isPathAllowed` then ran:
```java
return p.startsWith(cwd);
```
where:
- `cwd` = `Path.of("d:\\tmp\\abc")` — picocli preserves the user's lowercase `d`.
- `p` = `Path.of(model-emitted-path).toAbsolutePath()` — Java's
  `Path.toAbsolutePath()` on Windows canonicalises the drive letter to
  uppercase, so `d:\tmp\abc\pom.xml` becomes `D:\tmp\abc\pom.xml`.

`Path.startsWith` is **case-sensitive on every OS**, even on Windows.
So `D:\tmp\abc\pom.xml.startsWith(d:\tmp\abc)` is `false` and the tool
returns `"write refused: ... is outside the working directory"`. The
model then keeps planning but nothing lands on disk.

Why a.txt worked: that one write had the user typing the model prompt
to write "abcde" and the model emitted a relative path `"a.txt"`.
`Path.of("a.txt").toAbsolutePath()` joins the JVM cwd, which on Windows
is the spawn cwd (`d:\tmp\abc` lowercased → `D:\tmp\abc` uppercased),
and **happens to compare equal** to the `aethercode.cwd` system property
the JVM was launched with. So the comparison succeeded for that one
case and the user saw "a.txt got created" — but the comparison's luck
ran out the moment the model produced any other path form.

## Root cause #2 — `AetherCodeEngine` ignored `aethercode.cwd` for `b.cwd`

The CLI's `Main.buildEngine` passes the user's `--cwd` (or
`Path.of("").toAbsolutePath()` as default) to `AetherCodeEngine.builder()`.
`AetherCodeEngine` then `System.setProperty("aethercode.cwd", b.cwd.toAbsolutePath())`,
**overwriting** the `-D` flag set by the TUI's `jsonrpc.ts`. In the
TUI scenario, the user's `--cwd` reaches the JVM, but is then **clobbered**
by the JVM's own `user.dir` (the directory the user ran `java -jar` from)
inside the engine constructor. So even the `FileWriteTool` fix in
isolation would not have helped — the engine's appState would still
hold the wrong cwd.

## Root cause #3 — `bundle.mjs` shipped to the wrong directory

`aethercode-tui/scripts/bundle.mjs` ran
`shipDir = resolve(projectRoot, "../dist/ac-tui")` expecting
`aethercode-tui` to be a direct child of the repo root. It isn't —
`aethercode-tui` and `aethercode` are siblings. So the script wrote
the new bundle to `<repo>/dist/ac-tui/ac-tui.js` (one level too high)
while leaving the **stale** 21:12:39 bundle at `<repo>/aethercode/dist/ac-tui/ac-tui.js`
that the TUI actually loads. Every R87 build was a no-op for users
running the pre-shipped jar + TUI bundle combo.

## Fixes

| File | Change |
|---|---|
| `aethercode/aethercode-tools/.../file/FileWriteTool.java` | `isPathAllowed` now case-insensitively compares on Windows via `String.toLowerCase`. Both sides `normalize()`d first. |
| `aethercode/aethercode-sdk/.../AetherCodeEngine.java` | When the builder's `cwd` is the JVM default, fall back to `System.getProperty("aethercode.cwd")` (the TUI's `-D` flag). Always overwrite the system property with the canonical (normalized) value. |
| `aethercode-tui/src/tui.tsx` | `setPermissionMode` failure no longer silent — surfaces a `warn` toast with a hint to run `/mode ACCEPT_TASK` or `/mode BYPASS_PERMISSIONS`. |
| `aethercode-tui/src/components/ToolCard.tsx` | Collapsed cards running >30s get a `· still running` hint; >90s tints the duration amber so a stuck tool stands out in the scrollback. |
| `aethercode-tui/scripts/bundle.mjs` | `shipDir` now resolves to `../aethercode/dist/ac-tui` (one extra `..` to reach the sibling `aethercode/`). |
| `aethercode/aethercode-tools/.../test/.../FileWriteToolTest.java` (new) | 3 regression tests: case-mismatched absolute path, absolute path with mismatched drive letter, sandbox escape still rejected. |

## End-to-end verification

Built `aethercode-0.2.1.jar` from the post-R88 source, compiled a
mini Java program that simulates the TUI's path, ran it with
`aethercode.cwd=d:\tmp\test-r88-cwd` (lowercase) and target
`D:\tmp\test-r88-cwd\test.txt` (uppercase). Pre-R88 it would return
`"write refused: ... is outside the working directory"`; post-R88:

```
aethercode.cwd = d:\tmp\test-r88-cwd
target path    = D:\tmp\test-r88-cwd\test.txt
isError: false
output:  wrote 9 bytes to D:\tmp\test-r88-cwd\test.txt
```

## Test status

- aethercode-tools: 109 tests (was 106, +3 new FileWriteToolTest) — 0 failures
- aethercode-sdk: passes, 0 regressions
- Full Java suite: 426/0/3 across all 15 modules
- TUI build: clean (`tsc + esbuild bundle` produces 1.52 MB bundle, now
  correctly ships to `aethercode/dist/ac-tui/ac-tui.js`)
- TUI tests: 252/280 pass. The 28 failures are all **pre-existing**
  (R77/R78/R79 setup scripts look for Java sources at the wrong path
  — they expect `<repo>/aethercode-core/...` but the actual layout is
  `<repo>/aethercode/aethercode-core/...`). The R86/R87/R88 tests
  themselves all pass.

## Out of scope (intentionally not fixed here)

- **"TUI 频繁停"** — the model really does end_turn after each
  tool_use, and the user has to type "继续" to keep it going.
  This is a model-side behaviour, not a TUI bug. R86's ACCEPT_TASK
  mode only suppresses *permission* prompts, not *turn-end* events.
  The real fix needs an engine-side change to either (a) auto-continue
  the same run after a tool_use_end when in ACCEPT_TASK mode, or
  (b) re-prompt the model with "continue" when the user issues
  `/continue`. Both are multi-day R-rounds; deferred.
- **Visual layout** — the user said the scrollback is "比之前强，但
  还是有点儿乱". The R86/R87 changes (collapse-all default, dim
  thinking sub-section, left gutter line, Markdown in tool results)
  address the worst of it, but a full redesign (R89 candidate) would
  group related turns under collapsible headers instead of one card
  per text_delta. Deferred.
