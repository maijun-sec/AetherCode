import { describe, it, expect } from 'vitest';
import { createLruCache } from '../lru-cache.js';

describe('lru-cache — basic hit / miss', () => {
  it('returns undefined for a missing key', () => {
    const c = createLruCache<string>({ capacity: 4 });
    expect(c.get('global', 'u1', 'k1')).toBeUndefined();
    expect(c.size).toBe(0);
    expect(c.totalTokens).toBe(0);
  });

  it('returns the value on a hit', () => {
    const c = createLruCache<string>({ capacity: 4 });
    c.set('global', 'u1', 'k1', 'hello', 2);
    const e = c.get('global', 'u1', 'k1');
    expect(e?.value).toBe('hello');
    expect(e?.tokenCount).toBe(2);
  });

  it('uses a (scope, scopeId, key) tuple as the cache key', () => {
    const c = createLruCache<string>({ capacity: 16 });
    c.set('global', 'u1', 'k1', 'A', 1);
    c.set('project', 'p1', 'k1', 'B', 1);
    c.set('global', 'u1', 'k2', 'C', 1);
    c.set('global', 'u2', 'k1', 'D', 1);
    expect(c.get('global', 'u1', 'k1')?.value).toBe('A');
    expect(c.get('project', 'p1', 'k1')?.value).toBe('B');
    expect(c.get('global', 'u1', 'k2')?.value).toBe('C');
    expect(c.get('global', 'u2', 'k1')?.value).toBe('D');
  });

  it('peek does not update recency', () => {
    const c = createLruCache<string>({ capacity: 2 });
    c.set('global', '_', 'a', 'A', 1);
    c.set('global', '_', 'b', 'B', 1);
    // peek 'a' — should NOT change the recency order.
    expect(c.peek('global', '_', 'a')?.value).toBe('A');
    c.set('global', '_', 'c', 'C', 1); // evicts 'a' (still oldest after peek)
    expect(c.get('global', '_', 'a')).toBeUndefined();
    expect(c.get('global', '_', 'b')?.value).toBe('B');
    expect(c.get('global', '_', 'c')?.value).toBe('C');
  });
});

describe('lru-cache — eviction', () => {
  it('evicts the least-recently-used entry when over capacity', () => {
    const c = createLruCache<string>({ capacity: 2 });
    c.set('global', '_', 'a', 'A', 1);
    c.set('global', '_', 'b', 'B', 1);
    c.set('global', '_', 'c', 'C', 1); // evicts 'a'
    expect(c.size).toBe(2);
    expect(c.get('global', '_', 'a')).toBeUndefined();
    expect(c.get('global', '_', 'b')?.value).toBe('B');
    expect(c.get('global', '_', 'c')?.value).toBe('C');
  });

  it('promotes an entry on get so it survives eviction', () => {
    const c = createLruCache<string>({ capacity: 2 });
    c.set('global', '_', 'a', 'A', 1);
    c.set('global', '_', 'b', 'B', 1);
    // touch 'a' to make it MRU
    c.get('global', '_', 'a');
    c.set('global', '_', 'c', 'C', 1); // evicts 'b'
    expect(c.get('global', '_', 'a')?.value).toBe('A');
    expect(c.get('global', '_', 'b')).toBeUndefined();
    expect(c.get('global', '_', 'c')?.value).toBe('C');
  });

  it('keeps the token total accurate through evictions', () => {
    const c = createLruCache<string>({ capacity: 3 });
    c.set('global', '_', 'a', 'A', 10);
    c.set('global', '_', 'b', 'B', 20);
    c.set('global', '_', 'c', 'C', 30);
    expect(c.totalTokens).toBe(60);
    c.set('global', '_', 'd', 'D', 40); // evicts 'a'
    expect(c.totalTokens).toBe(90);
  });
});

describe('lru-cache — invalidation', () => {
  it('invalidate removes one entry', () => {
    const c = createLruCache<string>({ capacity: 4 });
    c.set('global', '_', 'a', 'A', 1);
    c.set('global', '_', 'b', 'B', 1);
    expect(c.invalidate('global', '_', 'a')).toBe(true);
    expect(c.get('global', '_', 'a')).toBeUndefined();
    expect(c.get('global', '_', 'b')?.value).toBe('B');
  });

  it('invalidate returns false for missing keys', () => {
    const c = createLruCache<string>({ capacity: 4 });
    expect(c.invalidate('global', '_', 'nope')).toBe(false);
  });

  it('invalidateScope drops every entry for a scope (or scopeId)', () => {
    const c = createLruCache<string>({ capacity: 16 });
    c.set('global', 'u1', 'a', 'A', 1);
    c.set('global', 'u1', 'b', 'B', 1);
    c.set('global', 'u2', 'c', 'C', 1);
    c.set('project', 'p1', 'd', 'D', 1);
    expect(c.invalidateScope('global', 'u1')).toBe(2);
    expect(c.get('global', 'u1', 'a')).toBeUndefined();
    expect(c.get('global', 'u1', 'b')).toBeUndefined();
    expect(c.get('global', 'u2', 'c')?.value).toBe('C');
    expect(c.get('project', 'p1', 'd')?.value).toBe('D');
  });

  it('clear wipes everything', () => {
    const c = createLruCache<string>({ capacity: 4 });
    c.set('global', '_', 'a', 'A', 1);
    c.set('project', 'p', 'b', 'B', 1);
    c.clear();
    expect(c.size).toBe(0);
    expect(c.totalTokens).toBe(0);
  });
});

describe('lru-cache — TTL', () => {
  it('expires entries after the TTL elapses', () => {
    let nowMs = 1_000;
    const c = createLruCache<string>({ capacity: 4, ttlMs: 100, now: () => nowMs });
    c.set('global', '_', 'a', 'A', 1);
    expect(c.get('global', '_', 'a')?.value).toBe('A');
    nowMs += 50;
    expect(c.get('global', '_', 'a')?.value).toBe('A');
    nowMs += 60; // total 110ms > ttl 100ms
    expect(c.get('global', '_', 'a')).toBeUndefined();
  });

  it('records an expiresAt on every entry when TTL is set', () => {
    const c = createLruCache<string>({ capacity: 4, ttlMs: 200 });
    c.set('global', '_', 'a', 'A', 1);
    const e = c.peek('global', '_', 'a');
    expect(e?.expiresAt).toBeDefined();
    expect(e!.expiresAt! - e!.insertedAt).toBe(200);
  });

  it('omits expiresAt when TTL is off (default)', () => {
    const c = createLruCache<string>({ capacity: 4 });
    c.set('global', '_', 'a', 'A', 1);
    const e = c.peek('global', '_', 'a');
    expect(e?.expiresAt).toBeUndefined();
  });
});

describe('lru-cache — capacity & token accounting', () => {
  it('defaults capacity to 256 (T-031)', () => {
    const c = createLruCache<string>();
    expect(c.capacity).toBe(256);
  });

  it('rejects capacity < 1', () => {
    expect(() => createLruCache<string>({ capacity: 0 })).toThrow(/capacity/);
    expect(() => createLruCache<string>({ capacity: -1 })).toThrow(/capacity/);
  });

  it('updating a key subtracts the old token total before adding the new one', () => {
    const c = createLruCache<string>({ capacity: 4 });
    c.set('global', '_', 'a', 'A', 5);
    c.set('global', '_', 'a', 'A2', 7);
    expect(c.totalTokens).toBe(7);
    expect(c.size).toBe(1);
  });

  it('isFull reports capacity pressure', () => {
    const c = createLruCache<string>({ capacity: 4 });
    expect(c.isFull()).toBe(false);
    c.set('global', '_', 'a', 'A', 1);
    c.set('global', '_', 'b', 'B', 1);
    c.set('global', '_', 'c', 'C', 1);
    c.set('global', '_', 'd', 'D', 1);
    expect(c.isFull()).toBe(true);
    expect(c.isFull(0.5)).toBe(true);
    expect(c.isFull(2)).toBe(true);
  });

  it('keys() returns the cache keys in recency order (oldest first)', () => {
    const c = createLruCache<string>({ capacity: 4 });
    c.set('global', '_', 'a', 'A', 1);
    c.set('global', '_', 'b', 'B', 1);
    c.set('global', '_', 'c', 'C', 1);
    const keys = c.keys();
    expect(keys).toHaveLength(3);
    expect(keys[0]).toEqual(['global', '_', 'a']);
    expect(keys[2]).toEqual(['global', '_', 'c']);
  });
});
