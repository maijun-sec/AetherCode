import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * R268 desktop polish (2026-09-15): the user's 0.2.69
 * desktop was connecting to an OLD 0.2.66 daemon
 * (started manually before R266i was released). The
 * desktop's find_jar_path() walked ancestors of the
 * exe and picked up the stale 0.2.66 jar from
 * `aethercode/dist/` — a jar that the user had
 * manually copied there from a previous round and
 * never cleaned up. The R266i fix was deployed and
 * bytecode-verified in the embedded jar, but the
 * exe NEVER LOADED that jar — it loaded the stale
 * dist/ jar instead.
 *
 * <p>Root cause: find_jar_path() had no "portable
 * install" check. It tried (in order):
 *  1. Tauri's bundled resource (empty for our
 *     manual `cargo build --release` workflow,
 *     because `tauri build` is the only thing that
 *     actually embeds resources into the exe)
 *  2. closest-ancestor walk into `aethercode/dist/`
 *  3. $AETHERCODE_DIST (not set)
 *
 * Step 2 found the stale 0.2.66 jar and the desktop
 * happily spawned it, ignoring the freshly-built
 * embedded jar entirely.
 *
 * <p>Fix: add a "next to exe" check BEFORE the
 * ancestor walk. The portable-install layout is
 * `release/aethercode-0.2.70/desktop/{exe, jar}`
 * — the jar next to the exe wins, the ancestor walk
 * is the LAST resort.
 *
 * <p>Source-pin tests for the four pieces:
 * <ol>
 *   <li>portable-install check EXISTS</li>
 *   <li>checks the exe's parent directory FIRST</li>
 *   <li>prefers exact `aethercode.jar` over
 *       `aethercode-*.jar`</li>
 *   <li>ancestor walk still works as a fallback for
 *       dev builds</li>
 * </ol>
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

function readSrc(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('R268: portable-install jar lookup in find_jar_path', () => {
  it('lib.rs has a portable-install section BEFORE the ancestor walk', () => {
    const src = readSrc('src-tauri/src/lib.rs');
    // The new section must be inserted between the
    // Tauri resource_dir block and the ancestor walk,
    // not after. Otherwise a stale ancestor jar still
    // wins. Order is critical.
    const r268Idx = src.indexOf('[R268] find_jar_path: portable install');
    const ancestorIdx = src.indexOf('ancestor walk (closest)');
    expect(r268Idx, 'portable-install section must be present').toBeGreaterThan(-1);
    expect(ancestorIdx, 'ancestor walk must still be present (legacy fallback)').toBeGreaterThan(-1);
    expect(r268Idx,
      'portable-install must come BEFORE the ancestor walk — order is the whole fix').toBeLessThan(ancestorIdx);
  });

  it('portable-install checks the exes parent directory', () => {
    const src = readSrc('src-tauri/src/lib.rs');
    // The new check must use std::env::current_exe() and
    // exe.parent() — the portable-install layout is
    // `desktop/{exe, jar}`, so the jar is one directory
    // up from the exe. We don't need a tight distance
    // regex; just check the key patterns coexist in
    // the source (the function body is large enough
    // that a tight distance regex is fragile).
    expect(src, 'R268 marker must be present').toMatch(/\[R268\]/);
    expect(src, 'current_exe() must be called from find_jar_path').toMatch(/current_exe\(\)/);
    expect(src, 'exe.parent() must be called').toMatch(/exe\.parent\(\)/);
    expect(src, 'exe_dir variable must be used').toMatch(/exe_dir/);
    // anchor on the FIRST [R268] marker (the comment
    // header), not the SECOND (the eprintln). The
    // block goes from the comment to the next
    // `ancestor walk (closest)` anchor.
    const block = src.match(/R268 desktop polish[\s\S]*?ancestor walk \(closest\)/)?.[0] ?? '';
    expect(block.length, 'R268 block must be extractable').toBeGreaterThan(500);
    expect(block, 'R268 block must call current_exe()').toMatch(/current_exe\(\)/);
    expect(block, 'R268 block must call exe.parent()').toMatch(/exe\.parent\(\)/);
  });

  it('portable-install prefers exact aethercode.jar over wildcards', () => {
    const src = readSrc('src-tauri/src/lib.rs');
    // The exact-name match (`aethercode.jar`, no hyphen)
    // wins over the wildcard scan (`aethercode-*.jar`).
    // This matters because the embedded resource uses
    // the exact name, and a portable install ships with
    // that same exact name.
    const block = src.match(/R268 desktop polish[\s\S]*?ancestor walk \(closest\)/)?.[0] ?? '';
    expect(block, 'exact-name check must exist').toMatch(/exe_dir\.join\("aethercode\.jar"\)/);
    expect(block, 'wildcard fallback must exist').toMatch(/is_aethercode_jar\(p\)/);
    // the exact-name check must appear BEFORE the wildcard scan
    const exactIdx = block.indexOf('exe_dir.join("aethercode.jar")');
    const wildcardIdx = block.indexOf('is_aethercode_jar(p)');
    expect(exactIdx, 'exact-name check must come first').toBeGreaterThan(-1);
    expect(wildcardIdx, 'wildcard check must exist').toBeGreaterThan(-1);
    expect(exactIdx, 'exact-name check must come first').toBeLessThan(wildcardIdx);
  });

  it('ancestor walk remains as legacy fallback for dev builds', () => {
    const src = readSrc('src-tauri/src/lib.rs');
    // Dev builds (`cargo run` from src-tauri/) don't
    // have an exe-adjacent jar, so the ancestor walk
    // is still needed. It must stay in place but no
    // longer be the FIRST thing find_jar_path tries.
    expect(
      src,
      'ancestor walk must still exist',
    ).toMatch(/ancestor walk \(closest\)/);
    expect(
      src,
      'ancestor walk must look in `<ancestor>/aethercode/dist/`',
    ).toMatch(/ancestor\.join\("aethercode"\)\.join\("dist"\)/);
  });

  it('find_jar_path function is exported / reachable', () => {
    const src = readSrc('src-tauri/src/lib.rs');
    // The function must be defined exactly once.
    const matches = src.match(/fn find_jar_path\(/g) ?? [];
    expect(matches.length, 'find_jar_path must be defined exactly once').toBe(1);
  });
});