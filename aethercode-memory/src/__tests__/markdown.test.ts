import { describe, it, expect } from 'vitest';
import {
  parseGlobalMemory,
  serializeGlobalMemory,
  globalMemoryEntries,
} from '../markdown.js';

const SAMPLE = `# Global Memory

## Facts
- user.name: Alice
- project.build_cmd: pnpm test
- just a note without a colon

## Rules
- No mocks in production code
- Always run linter before commit

## Cross-project breadcrumbs
- 2026-08-28 — switched from /old to /new
`;

describe('parseGlobalMemory', () => {
  it('parses a well-formed global memory file', () => {
    const mem = parseGlobalMemory(SAMPLE, 1_000);
    expect(mem.facts).toHaveLength(3);
    expect(mem.facts[0]?.key).toBe('user.name');
    expect(mem.facts[0]?.value).toBe('Alice');
    expect(mem.facts[1]?.key).toBe('project.build_cmd');
    expect(mem.facts[1]?.value).toBe('pnpm test');
    // A note without a colon is preserved with a generated key.
    expect(mem.facts[2]?.key.startsWith('note.')).toBe(true);
    expect(mem.facts[2]?.value).toBe('just a note without a colon');

    expect(mem.rules).toHaveLength(2);
    expect(mem.rules[0]?.text).toBe('No mocks in production code');
    expect(mem.rules[1]?.text).toBe('Always run linter before commit');

    expect(mem.breadcrumbs).toHaveLength(1);
    expect(mem.breadcrumbs[0]?.message).toBe('2026-08-28 — switched from /old to /new');
  });

  it('returns an empty GlobalMemory for an empty document', () => {
    const mem = parseGlobalMemory('');
    expect(mem.facts).toHaveLength(0);
    expect(mem.rules).toHaveLength(0);
    expect(mem.breadcrumbs).toHaveLength(0);
  });

  it('returns an empty GlobalMemory when there is no recognized section', () => {
    const mem = parseGlobalMemory('# Title only\n\nNothing here.\n');
    expect(mem.facts).toHaveLength(0);
    expect(mem.rules).toHaveLength(0);
    expect(mem.breadcrumbs).toHaveLength(0);
  });

  it('handles CRLF line endings', () => {
    const text = SAMPLE.replace(/\n/g, '\r\n');
    const mem = parseGlobalMemory(text, 1_000);
    expect(mem.facts).toHaveLength(3);
    expect(mem.rules).toHaveLength(2);
    expect(mem.breadcrumbs).toHaveLength(1);
  });

  it('tolerates trailing whitespace and extra blank lines', () => {
    const text = `# Global Memory\n\n  ## Facts   \n\n- a: b\n\n\n- c: d\n`;
    const mem = parseGlobalMemory(text, 1_000);
    expect(mem.facts).toHaveLength(2);
    expect(mem.facts[0]?.key).toBe('a');
  });
});

describe('serializeGlobalMemory', () => {
  it('emits the canonical layout', () => {
    const mem = parseGlobalMemory(SAMPLE, 1_000);
    const out = serializeGlobalMemory(mem);
    expect(out).toContain('# Global Memory');
    expect(out).toContain('## Facts');
    expect(out).toContain('## Rules');
    expect(out).toContain('## Cross-project breadcrumbs');
  });

  it('round-trips facts and rules (modulo generated keys for free-form facts)', () => {
    const mem = parseGlobalMemory(SAMPLE, 1_000);
    const out = serializeGlobalMemory(mem);
    const re = parseGlobalMemory(out, 1_000);
    expect(re.facts.filter((f) => !f.key.startsWith('note.'))).toHaveLength(2);
    expect(re.rules).toHaveLength(2);
    expect(re.breadcrumbs).toHaveLength(1);
  });

  it('serializes an empty memory cleanly', () => {
    const empty = parseGlobalMemory('');
    const out = serializeGlobalMemory(empty);
    expect(out).toContain('# Global Memory');
    expect(out).toContain('## Facts');
    expect(out).toContain('## Rules');
    expect(out).toContain('## Cross-project breadcrumbs');
  });
});

describe('globalMemoryEntries', () => {
  it('flattens all three sections in order', () => {
    const mem = parseGlobalMemory(SAMPLE, 1_000);
    const flat = globalMemoryEntries(mem);
    expect(flat).toHaveLength(mem.facts.length + mem.rules.length + mem.breadcrumbs.length);
    expect(flat[0]?.kind).toBe('fact');
  });
});

describe('performance', () => {
  it('parses a 10K-character file in < 10ms', () => {
    // Build a large doc with 200 facts, 200 rules, 200 breadcrumbs.
    const sections: string[] = ['# Global Memory', ''];
    sections.push('## Facts');
    for (let i = 0; i < 200; i += 1) {
      sections.push(`- fact.key.${i}: value ${i}`);
    }
    sections.push('', '## Rules');
    for (let i = 0; i < 200; i += 1) {
      sections.push(`- rule number ${i} for testing performance`);
    }
    sections.push('', '## Cross-project breadcrumbs');
    for (let i = 0; i < 200; i += 1) {
      sections.push(`- ${i % 31}/0${(i % 12) + 1}/2026 — event ${i}`);
    }
    const text = sections.join('\n');
    expect(text.length).toBeGreaterThan(10_000);
    const start = performance.now();
    const mem = parseGlobalMemory(text);
    const elapsed = performance.now() - start;
    expect(mem.facts.length).toBeGreaterThan(0);
    expect(elapsed).toBeLessThan(50); // generous on slow CI; design target 10ms
  });
});
