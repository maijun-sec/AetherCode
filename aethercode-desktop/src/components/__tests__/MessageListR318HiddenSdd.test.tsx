// @vitest-environment jsdom
import { describe, it, expect } from 'vitest';

/**
 * R318 — MessageList must collapse the `<!-- hidden-sdd:start/end -->`
 * block in the user message. The SDD skill inlines a long
 * instruction block between those markers (so the agent gets
 * SKILL.md + phase reference content without filesystem
 * round-trips). The user only needs to see the intent and
 * a "SDD" badge — the technical block is for the agent.
 *
 * Source-pin style: read MessageList.tsx and assert the
 * collapse pattern + the badge class. If a future refactor
 * drops the collapse (and re-shows the full SDD instruction
 * block to the user), this test fails.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const TS = join(process.cwd(), 'src', 'components', 'MessageList.tsx');
const source = readFileSync(TS, 'utf8');

describe('MessageList — R318 hidden-sdd collapse', () => {
  it('defines the hidden-sdd open/close marker regex', () => {
    expect(source).toMatch(/hidden-sdd:start/);
    expect(source).toMatch(/hidden-sdd:end/);
  });

  it('has a collapseHiddenSdd helper', () => {
    expect(source).toMatch(/function collapseHiddenSdd/);
  });

  it('the helper replaces the hidden block to leave only the intent visible', () => {
    expect(source).toMatch(/content\.replace\(HIDDEN_SDD_OPEN_R, ''\)\.trim\(\)/);
  });

  it('renders an SDD badge in the user message meta when isSdd', () => {
    expect(source).toMatch(/message-sdd-badge/);
    expect(source).toMatch(/📐 SDD/);
  });

  it('user message branch consumes collapseHiddenSdd before rendering', () => {
    // Look for the LegacyMessage user branch calling
    // collapseHiddenSdd and reading `visibleContent`.
    expect(source).toMatch(/collapseHiddenSdd\(m\.content\)/);
    expect(source).toMatch(/visibleContent/);
  });
});