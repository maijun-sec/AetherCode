"""Reproduction test: extract L571 verbatim and try to compile."""
# Copyright (c) 2026 maijun
#
# Permission is hereby granted, free of charge, to any person obtaining a copy
# of this software and associated documentation files (the "Software"), to deal
# in the Software without restriction, including without limitation the rights
# to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
# copies of the Software, and to permit persons to whom the Software is
# furnished to do so, subject to the following conditions:
#
# The above copyright notice and this permission notice shall be included in all
# copies or substantial portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
# AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
# OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
# SOFTWARE.

import subprocess
import os

REPO = r'D:\work\workspace\idea\engine\AetherCode'
CWD = os.path.join(REPO, r'aethercode-desktop')
TARGET = os.path.join(REPO, r'aethercode-desktop\src\store\index.ts')

with open(TARGET, 'rb') as f:
    raw = f.read()
lines = raw.split(b'\n')

# Extract L571 verbatim
line = lines[570]  # 0-indexed
print(f'Extracted L571 ({len(line)} bytes):')
print(repr(line))

# Build a minimal repro
minimal = b"""import { create } from 'zustand';
const useStore = create((set) => ({
  foo: () => {
    set((s) => {
      const detail = 'foo';
      const toolName = 'read';
      const cat = 'fileReads';
      const counters = s.steps?.[0]?.counters;
      const toolId = 'tool-1';
      set((s) => {
        const steps = s.steps?.map((st) => st.id === 'x' ? {
          ...st,
          toolEvents: [...st.toolEvents, { id: toolId, name: toolName, inputSummary: detail, ts: Date.now() }],
          counters: { ...st.counters, [cat]: (st.counters[cat] ?? 0) + 1 },
        } : st);
        return { steps };
      });
      const label = detail ? `\xf0\x9f\x9b\xa0 ${toolName} \xc2\xb7 ${detail}` : `\xf0\x9f\x9b\xa0 ${toolName}\xe2\x80\xa6\`,`;
      return {
        messages: [...s.messages, {
          id: 'msg-1', role: 'tool',
""" + line + b"""
        }],
        lastChunkTs: Date.now(),
        currentActivity: { kind: 'tool', label, ts: Date.now() },
        steps: s.steps,
      };
    });
  },
}));
"""
print()
print('Minimal repro length:', len(minimal))

# Write to temp file with UTF-8 BOM
tmp = os.path.join(os.environ.get('TEMP', '/tmp'), 'repro_l571.ts')
with open(tmp, 'wb') as f:
    f.write(b'\xef\xbb\xbf')
    f.write(minimal)

# Run tsc
r = subprocess.run(
    ['cmd', '/c', 'npx', 'tsc', '--noEmit', '--target', 'ES2021', '--strict',
     '--jsx', 'react-jsx', '--esModuleInterop', '--moduleResolution', 'bundler',
     '--skipLibCheck', tmp],
    capture_output=True, text=True, cwd=CWD, shell=True,
)
print('stdout:', r.stdout[:2000])
print('stderr:', r.stderr[:500])
print('returncode:', r.returncode)
