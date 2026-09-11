# Testing Conventions

## Test layers

| Layer | File pattern | Runner | Purpose |
|---|---|---|---|
| Java unit | `*Test.java` in `aethercode-*/src/test/java/...` | Maven Surefire | Verify backend classes in isolation |
| TUI unit | `scripts/test/rXX-name.test.mjs` | `node --test` | Verify reducer + pure helpers + source-code wiring |
| TUI bundle | `scripts/bundle.mjs` | esbuild | Verify the Ink bundle compiles |
| TUI E2E | `scripts/test/e2e-rXX-name.mjs` | `node` | Verify the live TUI against the real daemon |

## Java tests

```bash
cd aethercode
mvn -pl aethercode-core test -Dtest=MetricsCollectorTest
```

The Java tests are pure JUnit 5. They live next to the classes they
test. `aethercode-core/src/test/java/.../metrics/MetricsCollectorTest.java`
is a good template.

Excluded tests (run with `-Dsurefire.excludes=...` from
`build.ps1`): the 9 known-flake tests. We exclude them rather than
mark `@Disabled` so the failure log is preserved.

## TUI tests

```bash
cd aethercode-tui
node scripts/test/r77-metrics.test.mjs
```

TUI tests are plain Node `node:test` modules. They use three patterns:

### Pattern 1: pure helper test (no React, no TSC)

When the source has a pure function (e.g. `categorizeTool`,
`formatDuration`, `rankCommands`), re-implement the algorithm
in the test file and assert the output. This isolates the test
from the React runtime.

```javascript
test("R34: categorizeTool maps file_read to 'read'", () => {
  assert.equal(categorizeTool("file_read"), "read");
});
```

### Pattern 2: reducer test (compile state.ts, import)

When the test needs the real reducer, compile `state.ts` to a tmp
dir and dynamic-import it.

```javascript
import { spawnSync } from "node:child_process";
const tmp = join(root, "tmp-rXX-tsc");
const tscArgs = [...];
spawnSync(`"${tscBin}"`, [...tscArgs, `"${join(root, "src", "state.ts")}"`], ...);
const stateMod = await import("file:///" + join(tmp, "state.js").replace(/\\/g, "/"));
const { reducer, INITIAL } = stateMod;

test("R50: setRewindTarget sets the target", () => {
  const s = reducer(INITIAL, { type: "setRewindTarget", target: 3 });
  assert.equal(s.rewindTarget, 3);
});
```

### Pattern 3: source-code assertion

When the test needs to verify that `tui.tsx` imports a new
component, has a new key binding, or dispatches a new action, just
read the source file and assert on its content.

```javascript
test("R33: tui.tsx passes cwd to Header", () => {
  const tui = readFileSync(join(root, "src", "tui.tsx"), "utf-8");
  assert.match(tui, /<Header\s+state=\{state\}\s+cwd=\{cwd\}\s*\/>/);
});
```

This catches accidental removal / reformatting of wiring. It's
brittle (whitespace-sensitive) but very effective for "the
function I added is still called" guarantees.

### Common pitfalls

- **`spawnSync` shell quirks** on Windows: use absolute paths wrapped
  in double quotes, pass `shell: true`, and use `process.execPath`
  (the running node binary) rather than `"node"` (which can fail
  on Windows when PATH isn't propagated).
- **ESM dynamic import on Windows**: the path must start with
  `file:///` and use forward slashes. `path.join` returns
  backslashes on Windows; use `.replace(/\\/g, "/")`.
- **`await` in test callbacks**: node:test needs the test function
  to be `async` if you use `await`. The error is "await is only
  valid in async functions".
- **TypeScript-only syntax in .mjs files**: no `as` casts, no
  non-null assertions (`!`), no `: type` annotations. If you need
  them, you're better off writing the test in TypeScript and
  compiling it.

## E2E tests

```bash
cd aethercode-tui
node scripts/test/e2e-r77-metrics.mjs
```

An E2E test:
1. Spawns `java -jar dist/aethercode-0.2.1.jar tui --line`
2. Pipes a real query through stdin
3. Waits a fixed time (5-15s) for the daemon to respond
4. Pipes a verification command
5. Reads the captured stdout
6. Asserts the output contains the expected state

A common pattern: use a regex to pull the JSON out of the daemon's
output and parse it with `JSON.parse`.

### When to write an E2E

- **Always**: any new RPC method (proves the daemon actually serves
  the method, not just the SDK thinks it should).
- **Always**: any new keybinding or slash command that has an end-
  to-end effect (e.g. `/metrics`, `/export`).
- **Optional**: pure UI changes that only touch Ink (e.g. a new
  visual style). Bundle the smoke test (verify the bundle
  compiles) and call it done.

## Build smoke test

Every TUI test file ends with:

```javascript
test("RXX: TypeScript compile of new code is clean", () => {
  const tmp = join(root, "tmp-rXX-tsc");
  // rm -rf tmp
  const tscArgs = [
    "--outDir", tmp, "--target", "ES2022", "--module", "ES2022",
    "--moduleResolution", "bundler", "--jsx", "react",
    "--esModuleInterop", "true", "--skipLibCheck", "true",
    "--rootDir", join(root, "src"),
  ];
  const r = spawnSync(`"${tscBin}"`, [...tscArgs, "src/tui.tsx"], { shell: true });
  assert.equal(r.status, 0);
});
```

This catches missing imports (R59 lesson). esbuild is lenient;
tsc is strict. Run both.

## Running everything

```powershell
# TUI tests
cd aethercode-tui
for f in scripts/test/r*.test.mjs; do node "$f" 2>&1 | tail -3; done

# Java tests
cd ..
mvn -pl aethercode-core test

# Full build (Java + TUI)
.\build.ps1 -SkipTests
```
