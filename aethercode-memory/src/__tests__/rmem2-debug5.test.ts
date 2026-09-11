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
