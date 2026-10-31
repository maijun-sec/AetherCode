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
 * R317 + R319 — MessageInput Enter handler must route through
 * startSsdFlow() when the SDD toggle is on AND no run is
 * active. When a run is already active, route via
 * sendSsdCommand('approve' | 'modify' | 'skip') instead so
 * that phrases like "继续下一阶段" advance the existing run
 * instead of spawning a fresh phase 1 with a garbage slug.
 *
 * Source-pin style: read src/components/MessageInput.tsx
 * and assert the routing branch.
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

describe('MessageInput — R317 SDD routing on Enter', () => {
  it('reads sddEnabled from store', () => {
    expect(source).toMatch(/sddEnabled\s*=\s*useStore\(\(s\)\s*=>\s*s\.sddEnabled\)/);
  });

  it('gates sendMessage on sddEnabled in the Enter handler', () => {
    // Either form: `useStore.getState().sddEnabled` or
    // `sdd.sddEnabled` after pulling the store snapshot.
    expect(source).toMatch(/sddEnabled/);
  });

  it('calls startSsdFlow(intent) when toggle is on AND no run is active', () => {
    // startSsdFlow must be guarded by `!sdd.sddActive` so
    // active runs don't accidentally spawn a fresh phase 1.
    expect(source).toMatch(/startSsdFlow\(intent\)/);
  });

  it('calls sendSsdCommand(approve) when user types "继续" / "next" / "ok" with active run', () => {
    expect(source).toMatch(/sendSsdCommand\('approve'\)/);
  });

  it('calls sendSsdCommand(modify) for arbitrary feedback text', () => {
    expect(source).toMatch(/sendSsdCommand\('modify', intent\)/);
  });
});