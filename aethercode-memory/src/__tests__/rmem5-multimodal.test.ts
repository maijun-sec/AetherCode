/**
 * R-MEM-5.1: tests for F4 Multimodal (image memory).
 *
 * Pin the v9 schema migration (media_type + media_ref columns),
 * the image-hash helpers, the VectorStore.upsert mediaType
 * plumbing, the MemoryStore.appendImage API, and the
 * memory/appendImage RPC.
 */

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import Database from 'better-sqlite3';
import { openAndMigrate } from '../sqlite.js';
import { createMemoryStore } from '../memory-store.js';
import { memoryAppendImage, memoryFind, MemoryRpcError } from '../rpc.js';
import { sha256File, sha256Buffer, embedTextForImage } from '../image-hash.js';
import type { MemoryStore } from '../memory-store.js';

let tmp: string;
let store: MemoryStore;
let db: Database.Database;

beforeEach(() => {
  tmp = mkdtempSync(join(tmpdir(), 'rmem5-'));
  store = createMemoryStore({
    globalMemoryPath: join(tmp, 'g.md'),
    projectMemoryPath: join(tmp, 'p.md'),
    sessionsDir: join(tmp, 's'),
    dbPath: join(tmp, 'm.db'),
  });
  db = openAndMigrate(join(tmp, 'm.db'));
});

afterEach(() => {
  db.close();
  store.close();
  rmSync(tmp, { recursive: true, force: true });
});

describe('R-MEM-5.1: schema v9 migration', () => {
  it('adds media_type and media_ref columns to vec_index', () => {
    const cols = db.prepare(`PRAGMA table_info(vec_index)`).all() as Array<{ name: string }>;
    const names = cols.map((c) => c.name);
    expect(names).toContain('media_type');
    expect(names).toContain('media_ref');
  });

  it('creates idx_vec_index_media_type', () => {
    const indexes = db.prepare(`PRAGMA index_list(vec_index)`).all() as Array<{ name: string }>;
    expect(indexes.map((i) => i.name)).toContain('idx_vec_index_media_type');
  });

  it('migrates existing rows with media_type=text and media_ref=NULL', () => {
    store.appendProjectChange('legacy');
    const row = db.prepare(`SELECT media_type, media_ref FROM vec_index WHERE scope = 'project'`).get() as { media_type: string; media_ref: string | null };
    expect(row.media_type).toBe('text');
    expect(row.media_ref).toBeNull();
  });
});

describe('R-MEM-5.1: image-hash helpers', () => {
  it('sha256File returns the SHA-256 of a file', () => {
    const p = join(tmp, 'img1.png');
    writeFileSync(p, Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]));
    expect(sha256File(p)).toMatch(/^[0-9a-f]{64}$/);
  });

  it('sha256Buffer matches sha256File for the same bytes', () => {
    const p = join(tmp, 'img2.png');
    const buf = Buffer.from('hello world');
    writeFileSync(p, buf);
    expect(sha256Buffer(buf)).toBe(sha256File(p));
  });

  it('embedTextForImage prefixes description with hash slice', () => {
    expect(embedTextForImage('a screenshot', null)).toBe('a screenshot');
    expect(embedTextForImage('a screenshot', '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'))
      .toBe('a screenshot [0123456789abcdef]');
  });
});

describe('R-MEM-5.1: MemoryStore.appendImage', () => {
  it('appends from filePath with SHA-256 as mediaRef', () => {
    const p = join(tmp, 'shot.png');
    writeFileSync(p, Buffer.from([1, 2, 3, 4, 5]));
    const out = store.appendImage({ scope: 'project', description: 'login screen', filePath: p });
    expect(out.mediaType).toBe('image');
    expect(out.mediaRef).toMatch(/^[0-9a-f]{64}$/);
    expect(out.entryId).toMatch(/^image-[0-9a-f]{32}$/);
  });

  it('appends from fileBuffer', () => {
    const out = store.appendImage({ scope: 'project', description: 'buffer shot', fileBuffer: Buffer.from('data') });
    expect(out.mediaRef).toBe(sha256Buffer(Buffer.from('data')));
  });

  it('uses caller-supplied mediaRef when provided', () => {
    const out = store.appendImage({ scope: 'project', description: 'precomputed', mediaRef: 'a'.repeat(64) });
    expect(out.mediaRef).toBe('a'.repeat(64));
  });

  it('rejects when no source is provided', () => {
    expect(() => store.appendImage({ scope: 'project', description: 'no source' })).toThrow();
  });

  it('rejects empty description', () => {
    expect(() => store.appendImage({ scope: 'project', description: '', mediaRef: 'a'.repeat(64) })).toThrow();
  });

  it('rejects invalid scope', () => {
    expect(() => store.appendImage({
      scope: 'invalid' as unknown as 'project',
      description: 'x',
      mediaRef: 'a'.repeat(64),
    })).toThrow();
  });

  it('stores the image in vec_index with media_type=image', () => {
    const out = store.appendImage({ scope: 'project', description: 'shot', mediaRef: 'b'.repeat(64) });
    const row = db.prepare(`SELECT media_type, media_ref, content_text FROM vec_index WHERE entry_id = ?`).get(out.entryId) as { media_type: string; media_ref: string; content_text: string };
    expect(row.media_type).toBe('image');
    expect(row.media_ref).toBe('b'.repeat(64));
    expect(row.content_text).toContain('shot');
  });

  it('identical images (same bytes) collapse to the same entry', () => {
    const p = join(tmp, 'same.png');
    writeFileSync(p, Buffer.from('same-content'));
    const a = store.appendImage({ scope: 'project', description: 'first', filePath: p });
    const b = store.appendImage({ scope: 'project', description: 'second', filePath: p });
    expect(a.entryId).toBe(b.entryId);
    expect(a.mediaRef).toBe(b.mediaRef);
    // The row count in vec_index for this entry should be 1.
    const count = db.prepare(`SELECT COUNT(*) AS n FROM vec_index WHERE entry_id = ?`).get(a.entryId) as { n: number };
    expect(count.n).toBe(1);
  });
});

describe('R-MEM-5.1: findSimilar surfaces mediaType + mediaRef', () => {
  it('memoryFind returns mediaType=image for image rows', () => {
    store.appendImage({ scope: 'project', description: 'screenshot of error dialog', mediaRef: 'a'.repeat(64) });
    const out = memoryFind(store, { query: 'screenshot error', scope: 'project' });
    expect(out.hits.length).toBeGreaterThan(0);
    const hit = out.hits.find((h) => h.mediaType === 'image');
    expect(hit).toBeDefined();
    expect(hit?.mediaRef).toBe('a'.repeat(64));
  });

  it('memoryFind returns mediaType=text for text rows', () => {
    store.appendProjectChange('a normal project change');
    const out = memoryFind(store, { query: 'normal project change', scope: 'project' });
    expect(out.hits[0]?.mediaType).toBe('text');
    expect(out.hits[0]?.mediaRef).toBeNull();
  });
});

describe('R-MEM-5.1: RPC handlers', () => {
  it('memoryAppendImage works with filePath', () => {
    const p = join(tmp, 'rpc.png');
    writeFileSync(p, Buffer.from([7, 7, 7]));
    const out = memoryAppendImage(store, { scope: 'project', description: 'rpc', filePath: p });
    expect(out.ok).toBe(true);
    expect(out.mediaType).toBe('image');
    expect(out.mediaRef).toMatch(/^[0-9a-f]{64}$/);
  });

  it('memoryAppendImage decodes base64 fileBuffer', () => {
    const out = memoryAppendImage(store, {
      scope: 'project',
      description: 'b64',
      fileBuffer: Buffer.from('hello').toString('base64'),
    });
    expect(out.ok).toBe(true);
    expect(out.mediaRef).toBe(sha256Buffer(Buffer.from('hello')));
  });

  it('memoryAppendImage uses pre-computed mediaRef', () => {
    const out = memoryAppendImage(store, {
      scope: 'project',
      description: 'pre',
      mediaRef: 'c'.repeat(64),
    });
    expect(out.mediaRef).toBe('c'.repeat(64));
  });

  it('memoryAppendImage validates params', () => {
    expect(() => memoryAppendImage(store, { scope: '' as 'project', description: 'x', mediaRef: 'a' })).toThrow(MemoryRpcError);
    expect(() => memoryAppendImage(store, { scope: 'project', description: '', mediaRef: 'a' })).toThrow(MemoryRpcError);
    expect(() => memoryAppendImage(store, { scope: 'project', description: 'x' })).toThrow(MemoryRpcError);
    expect(() => memoryAppendImage(store, {
      scope: 'invalid' as unknown as 'project',
      description: 'x',
      mediaRef: 'a',
    })).toThrow(MemoryRpcError);
  });
});
