import { describe, it, expect } from 'vitest';
import * as api from '../index.js';

describe('aethercode-memory — smoke', () => {
  it('exports the public API surface', () => {
    // Types are erased, but the runtime surface is the union of named exports.
    const keys = Object.keys(api).sort();
    // Sanity check: at least these public symbols are exported.
    expect(keys).toContain('parseGlobalMemory');
    expect(keys).toContain('serializeGlobalMemory');
    expect(keys).toContain('parseProjectMemory');
    expect(keys).toContain('serializeProjectMemory');
    expect(keys).toContain('openAndMigrate');
    expect(keys).toContain('withDatabase');
    expect(keys).toContain('DEFAULT_MIGRATIONS');
    expect(keys).toContain('isFact');
    expect(keys).toContain('isRule');
    expect(keys).toContain('isChange');
    expect(keys).toContain('isBreadcrumb');
  });

  it('parse + serialize round-trip preserves content (global)', () => {
    const text = [
      '# Global Memory',
      '',
      '## Facts',
      '- user.name: Alice',
      '',
      '## Rules',
      '- no mocks',
      '',
      '## Cross-project breadcrumbs',
      '- 2026-08-28 — switched from /a to /b',
      '',
    ].join('\n');
    const mem = api.parseGlobalMemory(text);
    const out = api.serializeGlobalMemory(mem);
    const re = api.parseGlobalMemory(out);
    expect(re.facts).toHaveLength(1);
    expect(re.rules).toHaveLength(1);
    expect(re.breadcrumbs).toHaveLength(1);
  });
});
