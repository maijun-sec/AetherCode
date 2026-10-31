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
import {
  isFact,
  isRule,
  isChange,
  isBreadcrumb,
  isMemoryScope,
  type Fact,
  type Rule,
  type ChangeEntry,
  type Breadcrumb,
  type MemoryEntry,
} from '../types.js';

const fact: Fact = {
  kind: 'fact',
  id: 'f1',
  ts: 1,
  scope: 'global',
  source: 'user',
  tags: [],
  key: 'user.name',
  value: 'Alice',
};

const rule: Rule = {
  kind: 'rule',
  id: 'r1',
  ts: 1,
  scope: 'global',
  source: 'user',
  tags: [],
  text: 'No mocks in production code',
};

const change: ChangeEntry = {
  kind: 'change',
  id: 'c1',
  ts: 1,
  scope: 'project',
  source: 'system',
  tags: [],
  description: 'migrated to z3',
  compressed: false,
};

const crumb: Breadcrumb = {
  kind: 'breadcrumb',
  id: 'b1',
  ts: 1,
  scope: 'global',
  source: 'system',
  tags: ['breadcrumb'],
  message: 'cwd: /a → /b',
};

describe('memory types — type guards', () => {
  it('isFact narrows correctly', () => {
    expect(isFact(fact)).toBe(true);
    expect(isFact(rule)).toBe(false);
    expect(isFact(change)).toBe(false);
    expect(isFact(crumb)).toBe(false);
  });

  it('isRule narrows correctly', () => {
    expect(isRule(rule)).toBe(true);
    expect(isRule(fact)).toBe(false);
  });

  it('isChange narrows correctly', () => {
    expect(isChange(change)).toBe(true);
    expect(isChange(fact)).toBe(false);
  });

  it('isBreadcrumb narrows correctly', () => {
    expect(isBreadcrumb(crumb)).toBe(true);
    expect(isBreadcrumb(fact)).toBe(false);
  });

  it('isMemoryScope accepts only known scopes', () => {
    expect(isMemoryScope('global')).toBe(true);
    expect(isMemoryScope('project')).toBe(true);
    expect(isMemoryScope('session')).toBe(true);
    expect(isMemoryScope('unknown')).toBe(false);
    expect(isMemoryScope('')).toBe(false);
  });
});

describe('memory types — discriminated union', () => {
  it('exhaustively discriminates by kind', () => {
    const entries: MemoryEntry[] = [fact, rule, change, crumb];
    const kinds = entries.map((e) => {
      switch (e.kind) {
        case 'fact':
          return 'fact';
        case 'rule':
          return 'rule';
        case 'change':
          return 'change';
        case 'breadcrumb':
          return 'breadcrumb';
      }
    });
    expect(kinds).toEqual(['fact', 'rule', 'change', 'breadcrumb']);
  });
});
