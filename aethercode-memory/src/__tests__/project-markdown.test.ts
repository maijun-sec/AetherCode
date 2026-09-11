import { describe, it, expect } from 'vitest';
import {
  parseProjectMemory,
  serializeProjectMemory,
  projectMemoryEntries,
} from '../project-markdown.js';

const SAMPLE = `# AetherCode

## 说明
A type-safe memory layer with three backends.

## 修改记录 (最近 20 次)
- 2026-08-28 12:00 — initial scaffold
- 2026-08-27 09:30 — added fact schema
- 2026-08-26 18:45 — wired sqlite migration runner

## Project facts
- build_cmd: pnpm test
- entry: src/index.ts
`;

describe('parseProjectMemory', () => {
  it('parses the canonical layout', () => {
    const mem = parseProjectMemory(SAMPLE, 1_000);
    expect(mem.title).toBe('AetherCode');
    expect(mem.description).toBe('A type-safe memory layer with three backends.');
    expect(mem.changes).toHaveLength(3);
    expect(mem.changes[0]?.description).toBe('initial scaffold');
    expect(mem.changes[1]?.description).toBe('added fact schema');
    expect(mem.changes[2]?.description).toBe('wired sqlite migration runner');
    expect(mem.facts).toHaveLength(2);
    expect(mem.facts[0]?.key).toBe('build_cmd');
    expect(mem.facts[0]?.value).toBe('pnpm test');
  });

  it('returns an empty ProjectMemory for an empty document', () => {
    const mem = parseProjectMemory('');
    expect(mem.title).toBe('Untitled Project');
    expect(mem.description).toBe('');
    expect(mem.changes).toHaveLength(0);
    expect(mem.facts).toHaveLength(0);
  });

  it('parses timestamps from change bullets', () => {
    const mem = parseProjectMemory(SAMPLE, 1_000);
    // Sorted by file order; first change was 2026-08-28 12:00.
    const expected = Date.parse('2026-08-28T12:00:00');
    expect(mem.changes[0]?.ts).toBe(expected);
  });

  it('falls back to `now` when a change bullet has no timestamp', () => {
    const text = `# P\n\n## 说明\n\n## 修改记录 (最近 20 次)\n- no timestamp here\n\n## Project facts\n`;
    const mem = parseProjectMemory(text, 42_000);
    expect(mem.changes[0]?.ts).toBe(42_000);
  });

  it('tolerates a `## Project facts` heading with English aliases', () => {
    const text = `# P\n\n## 说明\n\n## Project Facts\n- k: v\n`;
    const mem = parseProjectMemory(text, 1_000);
    expect(mem.facts).toHaveLength(1);
    expect(mem.facts[0]?.key).toBe('k');
  });
});

describe('serializeProjectMemory', () => {
  it('emits the canonical layout', () => {
    const mem = parseProjectMemory(SAMPLE, 1_000);
    const out = serializeProjectMemory(mem);
    expect(out).toContain('# AetherCode');
    expect(out).toContain('## 说明');
    expect(out).toContain('## 修改记录');
    expect(out).toContain('## Project facts');
  });

  it('honors the change limit (trim oldest)', () => {
    const mem = parseProjectMemory(SAMPLE, 1_000);
    const out = serializeProjectMemory(mem, /* changeLimit */ 2);
    expect(out).toContain('(最近 2 次)');
    // The most recent two changes are the 27th and 26th; the 28th is dropped.
    expect(out).toContain('added fact schema');
    expect(out).toContain('wired sqlite migration runner');
    expect(out).not.toContain('initial scaffold');
  });

  it('round-trips through a full parse → serialize → parse cycle', () => {
    const mem = parseProjectMemory(SAMPLE, 1_000);
    const out = serializeProjectMemory(mem, 20);
    const re = parseProjectMemory(out, 1_000);
    expect(re.title).toBe(mem.title);
    expect(re.description).toBe(mem.description);
    expect(re.changes).toHaveLength(mem.changes.length);
    expect(re.facts).toHaveLength(mem.facts.length);
  });

  it('serializes an empty project memory cleanly', () => {
    const empty = parseProjectMemory('');
    const out = serializeProjectMemory(empty);
    expect(out).toContain('# Untitled Project');
    expect(out).toContain('## 说明');
    expect(out).toContain('## 修改记录');
    expect(out).toContain('## Project facts');
  });
});

describe('projectMemoryEntries', () => {
  it('flattens changes and facts in order', () => {
    const mem = parseProjectMemory(SAMPLE, 1_000);
    const flat = projectMemoryEntries(mem);
    expect(flat).toHaveLength(mem.changes.length + mem.facts.length);
    expect(flat[0]?.kind).toBe('change');
  });
});
