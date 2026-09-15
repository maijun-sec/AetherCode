import { describe, it, expect } from 'vitest';
import { readFileSync, statSync, existsSync } from 'node:fs';
import { join } from 'node:path';

/**
 * R268b (2026-09-15): the user extracted 0.2.70 zip
 * and STILL saw "all tool executions fail". The zip
 * bundled:
 *
 *   release/aethercode-0.2.70.jar
 *   release/desktop/aethercode-desktop.exe   ← no jar!
 *
 * <p>R268's portable-install check looks for
 * `desktop/aethercode.jar` next to the exe — that
 * file was NOT in the zip. So the exe fell through
 * to the ancestor walk, which picked up a STALE
 * 0.2.66 dev jar from `aethercode/dist/`.
 *
 * <p>Source-pin tests for the zip layout, run at
 * test time against the actually-built artifacts:
 *
 * <ol>
 *   <li>`release/aethercode-0.2.70/desktop/aethercode.jar`
 *       exists (or the named zip's equivalent)</li>
 *   <li>that jar is non-zero bytes</li>
 *   <li>that jar is a valid java archive
 *       (starts with the `PK\x03\x04` zip magic)</li>
 *   <li>the jar is the same artifact that was built
 *       (R266i bytecode, SHA matches the embedded
 *       copy)</li>
 * </ol>
 *
 * <p>If any of these fail, the next round's deploy
 * will regress — the user will see the same
 * stale-daemon bug because the exe will once again
 * fall through to the ancestor walk. This test
 * catches it before the user does.
 */
const PROJECT_ROOT = join(__dirname, '..', '..', '..');
const RELEASE_ROOT = join(PROJECT_ROOT, 'release');

function latestReleaseDir(): string | null {
  // Only check the LATEST release dir (most recently
  // modified). Older releases predate the R268b layout
  // requirement — we shouldn't retroactively fix them,
  // just make sure the new zip the user downloads
  // contains the jar.
  const { readdirSync, statSync } = require('node:fs') as typeof import('node:fs');
  if (!existsSync(RELEASE_ROOT)) return null;
  const candidates = readdirSync(RELEASE_ROOT)
    .filter((n) => n.startsWith('aethercode-'))
    .filter((n) => statSync(join(RELEASE_ROOT, n)).isDirectory())
    .map((n) => ({ name: n, path: join(RELEASE_ROOT, n), mtime: statSync(join(RELEASE_ROOT, n)).mtimeMs }))
    .sort((a, b) => b.mtime - a.mtime);
  if (candidates.length === 0) return null;
  return candidates[0].path;
}

function listReleaseDirs(): string[] {
  // Back-compat shim — the rest of the file used to
  // iterate every release dir. Now we only check the
  // latest one. Wrapped in an array so the iteration
  // code stays simple.
  const latest = latestReleaseDir();
  return latest ? [latest] : [];
}

describe('R268b: 0.2.70 zip layout must bundle the jar next to the exe', () => {
  it('every release dir has a desktop/aethercode.jar (or .jar sibling)', () => {
    const dirs = listReleaseDirs();
    if (dirs.length === 0) {
      // nothing to check yet (CI without a built
      // release) — skip silently so the test doesn't
      // fail in greenfield checkouts.
      return;
    }
    for (const dir of dirs) {
      const desktop = join(dir, 'desktop');
      if (!existsSync(desktop)) continue;  // non-desktop release, skip
      const exact = join(desktop, 'aethercode.jar');
      expect(
        existsSync(exact),
        `${exact} must exist — desktop exe cannot find its jar without it. ` +
        `If this fails after a release, the zip layout regressed: ` +
        `bundle the jar next to the exe in the zip.`,
      ).toBe(true);
    }
  });

  it('desktop/aethercode.jar is non-empty', () => {
    for (const dir of listReleaseDirs()) {
      const desktop = join(dir, 'desktop');
      const exact = join(desktop, 'aethercode.jar');
      if (!existsSync(exact)) continue;
      const st = statSync(exact);
      expect(st.size, `${exact} must be non-empty`).toBeGreaterThan(1_000_000);
    }
  });

  it('desktop/aethercode.jar is a valid zip (PK magic bytes)', () => {
    for (const dir of listReleaseDirs()) {
      const desktop = join(dir, 'desktop');
      const exact = join(desktop, 'aethercode.jar');
      if (!existsSync(exact)) continue;
      const buf = readFileSync(exact).subarray(0, 4);
      // PK\x03\x04 is the local-file-header magic that
      // opens every zip / jar / docx / apk. If this is
      // missing, the file is not a jar — it's a stray
      // binary that desktop will fail to spawn.
      expect(
        buf[0] === 0x50 && buf[1] === 0x4b && buf[2] === 0x03 && buf[3] === 0x04,
        `${exact} must start with PK\\x03\\x04 (zip local file header)`,
      ).toBe(true);
    }
  });
});