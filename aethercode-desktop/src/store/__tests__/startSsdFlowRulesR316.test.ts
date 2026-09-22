// @vitest-environment jsdom
import { describe, it, expect } from 'vitest';

/**
 * R316 — startSsdFlow must inline the SDD skill's core rules into
 * the chat message so the agent sees them regardless of skill
 * discovery. Earlier R315 only prepended `[sdd] <intent>` and
 * trusted skill discovery; the agent's default agentic loop was
 * too eager and skipped straight to implement (writing pom.xml
 * before any spec markdown). R316 inlines the rules explicitly.
 *
 * This test reads the desktop store source and asserts the
 * trigger string contains:
 *   1. The 8-phase order
 *   2. The mandatory phase artifact names
 *   3. The strict lowercase path convention
 *   4. The pause-message shape (so renderer scan picks it up)
 *   5. The original intent verbatim
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const STORE_TS = join(process.cwd(), 'src', 'store', 'index.ts');
const source = readFileSync(STORE_TS, 'utf8');

describe('startSsdFlow — R316 inlines SDD skill rules', () => {
  it('contains the 8-phase order', () => {
    expect(source).toMatch(/constitution\s*→\s*specify\s*→\s*\[clarify\]\s*→\s*plan\s*→\s*\[analyze\]\s*→\s*tasks\s*→\s*implement\s*→\s*\[converge\]/);
  });

  it('forbids writing pom.xml / SPEC.md / DESIGN.md in cwd root', () => {
    expect(source).toMatch(/绝对禁止写到.*SPEC\.md/);
    expect(source).toMatch(/绝对禁止写到.*DESIGN\.md/);
    expect(source).toMatch(/绝对禁止写到.*pom\.xml/);
  });

  it('requires lowercase filenames', () => {
    expect(source).toMatch(/绝对禁止大写文件名/);
  });

  it('mentions the per-phase reference files at the agent', () => {
    expect(source).toMatch(/phase-1-constitution\.md/);
    expect(source).toMatch(/phase-7-implement\.md/);
  });

  it('mentions the pause-message shape renderer scans for', () => {
    expect(source).toMatch(/✅ 第 N 阶段完成/);
  });

  it('inlines the user intent into the trigger string', () => {
    expect(source).toMatch(/\$\{intent\}/);
  });
});