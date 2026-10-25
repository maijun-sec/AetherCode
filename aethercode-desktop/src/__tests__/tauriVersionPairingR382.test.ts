// R382 (2026-10-25) — the Tauri NPM packages and the Tauri Rust crates
// must stay on the same major.minor.
//
// THE DRIFT THIS CAUGHT
// ---------------------
// The 0.2.72 packaging build failed with:
//
//   Found version mismatched Tauri packages.
//     tauri (v2.11.5) : @tauri-apps/api (v2.12.4)
//     tauri-plugin-dialog (v2.7.2) : @tauri-apps/plugin-dialog (v2.8.3)
//     tauri-plugin-shell (v2.3.6) : @tauri-apps/plugin-shell (v2.5.0)
//
// Both sides used FLOATING ranges — `Cargo.toml` said `tauri = "2"` and
// `package.json` said `"@tauri-apps/api": "^2.0.0"`. So each side drifted
// to its own latest at its own pace: `pnpm install` refreshed the NPM
// side to newer minors, while `Cargo.lock` stayed where the last
// `cargo` run had pinned it. Nothing in the repo compared the two, so
// the mismatch only surfaced when a human ran `tauri build`.
//
// The fix for the build was to pin the NPM side down to the Rust
// side's minors. This test is what makes that pin STICK: the ranges
// must stay exact, and the two manifests must agree on major.minor.
//
// WHY "major.minor" AND NOT "exact"
// ---------------------------------
// Tauri's own rule is same major.minor, and the two sides genuinely
// have different patch cadences — `@tauri-apps/api` has no 2.11.5 (it
// published 2.11.0 / 2.11.1), while the Rust `tauri` crate is at
// 2.11.5. Requiring exact equality would be a rule the ecosystem
// cannot satisfy, and a rule that cannot be satisfied is a rule that
// gets deleted.
//
// WHY THE PIN MUST BE EXACT
// -------------------------
// If package.json goes back to `^2.0.0`, the next `pnpm install`
// re-introduces the drift and the next `tauri build` fails again.
// The exactness check is the part that prevents the regression; the
// major.minor check is the part that catches it if someone bumps one
// side by hand.
//
// These are contract tests: they read package.json + Cargo.lock from
// disk and compare them. No build, no network.

import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

// Resolve the package root from this file's own location, never from
// the ambient wd (the same reason R748 gave for the store source pins).
const __root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');

/**
 * The NPM package ↔ Rust crate pairs Tauri's version check compares.
 * `npm` is the @tauri-apps/* package; `crate` is the Rust crate in
 * Cargo.lock.
 */
const PAIRS: ReadonlyArray<{ npm: string; crate: string }> = [
  { npm: '@tauri-apps/api', crate: 'tauri' },
  { npm: '@tauri-apps/plugin-dialog', crate: 'tauri-plugin-dialog' },
  { npm: '@tauri-apps/plugin-shell', crate: 'tauri-plugin-shell' },
];

/** "2.11.1" → "2.11". Returns null for anything unparseable. */
export function majorMinor(version: string): string | null {
  const m = /^(\d+)\.(\d+)/.exec(version.trim());
  return m ? `${m[1]}.${m[2]}` : null;
}

/**
 * The comparison the gate is built on. Exported so the negative
 * self-check below can feed it a SYNTHETIC mismatch — a gate that
 * cannot be shown to reject a bad pair is a gate that might be a
 * no-op.
 */
export function pairingError(
  npmVersion: string,
  crateVersion: string,
): string | null {
  const a = majorMinor(npmVersion);
  const b = majorMinor(crateVersion);
  if (!a || !b) {
    return `cannot parse versions: npm=${npmVersion} crate=${crateVersion}`;
  }
  if (a !== b) {
    return `major.minor mismatch: npm=${npmVersion} (${a}) vs crate=${crateVersion} (${b})`;
  }
  return null;
}

/** Read the version of one @tauri-apps/* package out of package.json. */
function npmVersion(pkg: Record<string, unknown>, name: string): string {
  const deps = {
    ...(pkg.dependencies as Record<string, string>),
    ...(pkg.devDependencies as Record<string, string>),
  };
  const v = deps?.[name];
  if (typeof v !== 'string') {
    throw new Error(`R382: ${name} is not declared in package.json`);
  }
  return v;
}

/** Read one crate's version out of Cargo.lock's [[package]] blocks. */
function crateVersion(lockText: string, crate: string): string {
  for (const block of lockText.split('[[package]]')) {
    const name = /^name\s*=\s*"([^"]+)"/m.exec(block)?.[1];
    if (name !== crate) continue;
    const version = /^version\s*=\s*"([^"]+)"/m.exec(block)?.[1];
    if (!version) break;
    return version;
  }
  throw new Error(`R382: crate ${crate} is not in Cargo.lock`);
}

describe('desktop — R382 Tauri NPM/Rust version pairing', () => {
  const pkg = JSON.parse(readFileSync(join(__root, 'package.json'), 'utf8'));
  const lockText = readFileSync(join(__root, 'src-tauri', 'Cargo.lock'), 'utf8');

  it('pins every @tauri-apps package to an EXACT version', () => {
    // A floating range is what let the two sides drift apart in the
    // first place. `^` and `~` both allow a minor bump.
    for (const { npm } of PAIRS) {
      const v = npmVersion(pkg, npm);
      expect(
        /^\d+\.\d+\.\d+$/.test(v),
        `R382: ${npm} must be pinned to an exact version (no ^ or ~), got "${v}"`,
      ).toBe(true);
    }
  });

  it('pairs every @tauri-apps package with its Rust crate on the same major.minor', () => {
    for (const { npm, crate } of PAIRS) {
      const npmV = npmVersion(pkg, npm);
      const crateV = crateVersion(lockText, crate);
      const err = pairingError(npmV, crateV);
      expect(
        err,
        `R382: ${npm} (${npmV}) must match Rust crate ${crate} (${crateV}) on major.minor`,
      ).toBeNull();
    }
  });

  it('the pairing check actually rejects a mismatch', () => {
    // Negative self-check. Without this, a comparator that always
    // returns null would satisfy the test above and the gate would be
    // a no-op that only ever says "fine".
    expect(pairingError('2.12.4', '2.11.5')).toMatch(/major\.minor mismatch/);
    expect(pairingError('2.7.2', '2.3.6')).toMatch(/major\.minor mismatch/);
    // …and it accepts a genuinely matching pair, so it is not simply
    // rejecting everything.
    expect(pairingError('2.11.1', '2.11.5')).toBeNull();
    expect(pairingError('2.7.2', '2.7.2')).toBeNull();
  });

  it('reads each package and each crate from the files, not from a literal', () => {
    // If the fixture were hard-coded, the two tests above would keep
    // passing after package.json / Cargo.lock change. The three pairs
    // are genuinely three DIFFERENT versions on both sides, so a
    // constant-returning stub would be visible here.
    const npmVersions = PAIRS.map((p) => npmVersion(pkg, p.npm));
    const crateVersions = PAIRS.map((p) => crateVersion(lockText, p.crate));
    expect(new Set(npmVersions).size).toBeGreaterThan(1);
    expect(new Set(crateVersions).size).toBeGreaterThan(1);
    // …and every value parses.
    for (const v of [...npmVersions, ...crateVersions]) {
      expect(majorMinor(v)).not.toBeNull();
    }
  });
});
