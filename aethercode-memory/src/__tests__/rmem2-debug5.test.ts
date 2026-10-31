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
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createMemoryStore } from '../memory-store.js';

describe('debug findSimilar', () => {
  it('appends then finds', () => {
    const tmp = mkdtempSync(join(tmpdir(), 'rmem2-d5-'));
    const store = createMemoryStore({
      globalMemoryPath: join(tmp, 'g.md'),
      projectMemoryPath: join(tmp, 'p.md'),
      sessionsDir: join(tmp, 's'),
      dbPath: join(tmp, 'm.db'),
    });
    store.appendProjectChange('one');
    store.appendProjectChange('two');
    const find = store.findSimilar('one');
    console.log('find:', JSON.stringify({ totalScanned: find.totalScanned, rows: find.rows.length, modelId: store.embeddingModelId, dim: store.embeddingDim }));
    store.close();
    rmSync(tmp, { recursive: true, force: true });
    expect(find.totalScanned).toBeGreaterThanOrEqual(2);
  });
});
