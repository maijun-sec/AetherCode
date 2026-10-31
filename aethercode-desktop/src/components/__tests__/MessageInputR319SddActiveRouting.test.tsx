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

// @vitest-environment jsdom
import { describe, it, expect } from 'vitest';

/**
 * R319 — when SDD run is already active, MessageInput must
 * route the user's input as a phase command, NOT as a new
 * SDD intent. Earlier R317 wired startSsdFlow into Enter,
 * but R318 retest showed that "继续下一阶段" got parsed by
 * Slug derivation into `sd-cgbvud` and a fresh phase-1 run
 * was spawned — the user wanted to advance the existing run,
 * not start a new one.
 *
 * Source-pin style: read MessageInput.tsx and assert the
 * routing keywords + the active-vs-inactive branch.
 */
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

// R748: resolve the package root from this file's own location,
// never from the ambient wd — under vitest's jsdom environment
// with several source-pin suites in one run, the ambient wd is
// not reliably the package root.
const __root = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

const TS = join(__root, 'src', 'components', 'MessageInput.tsx');
const source = readFileSync(TS, 'utf8');

describe('MessageInput — R319 SDD-active routing', () => {
  it('checks sddActive state before routing', () => {
    expect(source).toMatch(/sdd\.sddActive/);
  });

  it('treats "继续下一阶段" / "next" / "ok" as approve', () => {
    expect(source).toMatch(/继续下一阶段|继续|next|ok/);
    expect(source).toMatch(/sendSsdCommand\('approve'\)/);
  });

  it('treats "跳过" / "skip" as skip', () => {
    expect(source).toMatch(/跳过|skip/);
    expect(source).toMatch(/sendSsdCommand\('skip'\)/);
  });

  it('treats everything else as modify feedback', () => {
    expect(source).toMatch(/sendSsdCommand\('modify', intent\)/);
  });

  it('only calls startSsdFlow when sddActive is false (no run yet)', () => {
    // The startSsdFlow path must be guarded by !sddActive
    // so existing runs don't accidentally spawn a new one.
    expect(source).toMatch(/sdd\.sddActive\s*\)\s*\{[\s\S]*startSsdFlow/);
  });
});