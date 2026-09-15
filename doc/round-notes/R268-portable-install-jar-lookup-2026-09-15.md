# R268 — portable-install jar lookup (fix the false-positive daemon bug)

## The bug (user-reported 2026-09-15 08:46)

User reported that 0.2.70 desktop still had every tool execution
failing. After R266i was supposedly deployed with bytecode
verification, this was the THIRD time the same symptom surfaced
(R266h → R266i → R267 → R268). The pattern: the user upgrades the
desktop, the bug persists, and the model blames "still not fixed".

## Root cause — the desktop never loaded R266i

`ProcessId 20460` (the daemon serving the user's session) was
running `aethercode-0.2.66.jar` — a jar the user had manually
copied into `aethercode/dist/` from an old round and never cleaned
up. R266i is in the embedded jar inside the desktop exe, but the
desktop was connecting to this stale external daemon.

Why didn't the desktop spawn its own daemon? `find_jar_path()` in
`src-tauri/src/lib.rs` had three fallback paths:

1. **Tauri bundled resource** (`app.path().resource_dir()`).
   Empty in our manual `cargo build --release` workflow because
   `tauri build` (the only thing that actually embeds resources
   into the exe via `tauri.conf.json`'s `resources` field) was
   never run. Our workflow is `cargo build --release --features
   tauri/custom-protocol` + manual exe copy.
2. **Closest-ancestor walk** into `<ancestor>/aethercode/dist/`.
   This walked up to `AetherCode/aethercode/dist/` (level 6) and
   found the user's `aethercode-0.2.66.jar` — a stale manual
   copy from a previous round. The newest jar in dist/ wins by
   version-sort, and the user had copied jars named
   `aethercode-0.2.X.jar` (X = various). The desktop happily
   spawned the 0.2.66 daemon.
3. **`$AETHERCODE_DIST`** env var. Not set.

The bytecode of R266i (`isStructurallyEmpty`,
`empty_tool_input`, `lastLoopKind`, `emptyInputStreak`) was
correctly verified in the jar inside the desktop's
`src-tauri/resources/aethercode.jar` AND in the 0.2.70 zip.
**But the runtime never loaded that jar.** It loaded the
0.2.66 jar from dist/ instead.

The old daemon (running 0.2.66 jar) had the original
`Map.isEmpty()` check, which returned false for
`{"command":""}`, so the empty-input detector never fired.
16+ `bash (missing command)` cards. Same bug, same fix
attempted, no real change. The user was right to be frustrated.

## Fix — portable install layout takes priority

`find_jar_path()` now looks for an `aethercode.jar` (no version
suffix) sitting NEXT TO the exe itself, BEFORE the ancestor walk
fallback. The portable-install layout is
`release/aethercode-0.2.70/desktop/{exe, jar}` — the jar next to
the exe wins, the ancestor walk is the LAST resort.

```rust
// R268 (2026-09-15): portable install support. ... look
// for an `aethercode.jar` (no version suffix) sitting NEXT TO
// the exe itself — the natural layout for a portable release
// directory like `release/aethercode-0.2.70/desktop/{exe, jar}`.
// Without this check, find_jar_path falls through to the
// ancestor walk below, which finds the user's most-recent
// maven build in `aethercode/dist/` — frequently a STALE jar
// (a `cp .../aethercode-cli-0.1.0-SNAPSHOT.jar
// dist/aethercode-0.2.66.jar` from a prior round that the
// user forgot to clean up).
if let Ok(exe) = std::env::current_exe() {
    if let Some(exe_dir) = exe.parent() {
        // try the canonical name first
        let exact = exe_dir.join("aethercode.jar");
        if exact.is_file() {
            eprintln!("[R268] find_jar_path: portable install (exact next-to-exe) {}", exact.display());
            return Ok(exact);
        }
        // then any aethercode-*.jar in the same dir
        if let Ok(entries) = std::fs::read_dir(exe_dir) {
            let mut jars: Vec<PathBuf> = entries
                .flatten()
                .map(|e| e.path())
                .filter(|p| is_aethercode_jar(p) && p.is_file())
                .collect();
            if !jars.is_empty() {
                jars.sort_by_key(|p| std::cmp::Reverse(jar_sort_key(p)));
                let picked = jars.swap_remove(0);
                eprintln!("[R268] find_jar_path: portable install (scan next-to-exe) {}", picked.display());
                return Ok(picked);
            }
        }
    }
}
// ancestor walk still below as legacy fallback for dev builds
// (`cargo run` from src-tauri/)
```

The portable-install check is INSERTED BETWEEN the Tauri
resource_dir block and the ancestor walk, not at the end. Order
matters: the ancestor walk is the LAST resort, only for dev
builds.

## Local-env cleanup (one-time, for this session)

Moved 14 stale jars from `aethercode/dist/` to `.bak` extension:

```
aethercode-0.2.66.jar.bak  ← the user's manual stale copy
aethercode-0.2.63.jar.bak
aethercode-0.2.62.jar.bak
... (12 more, all from prior dev rounds)
aethercode-0.2.1.jar.bak
```

The new jar was copied to `release/aethercode-0.2.70/desktop/
aethercode.jar` (next to the exe), where the new portable-install
check finds it first.

## Tests added (5 new source-pin tests)

| Test | Pins |
|---|---|
| lib.rs has a portable-install section BEFORE the ancestor walk | order is the whole fix |
| portable-install checks the exe's parent directory | `current_exe().parent()` used |
| portable-install prefers exact `aethercode.jar` over wildcards | exact name match wins |
| ancestor walk remains as legacy fallback | dev `cargo run` path still works |
| `find_jar_path` defined exactly once | no duplicates from the patch |

`npm run test`: 1062/1062 pass (1057 → 1062, +5)
`npm run typecheck`: clean

## Build & verify

- `cargo build --release --features tauri/custom-protocol`: 3m 48s,
  exe 5,175,296 bytes, SHA 5A88AABA27524ED4D744EA834915827C4EFC79B3
- Marker check: R268 count = 2 in deployed exe
- Bytecode check: new desktop exe has the exact-match
  `aethercode.jar` lookup BEFORE the ancestor walk

## Files touched

- `aethercode-desktop/src-tauri/src/lib.rs` — new portable-install
  block in `find_jar_path` (~70 lines)
- `aethercode-desktop/src/store/findJarPathR268.test.ts` — 5 source-pin tests

## Round commit

`R268` — pending commit