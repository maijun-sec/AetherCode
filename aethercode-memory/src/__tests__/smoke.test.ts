// Copyright (c) 2026 maijun
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

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
