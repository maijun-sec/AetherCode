/**
 * Tests for the LRU `readFileState` map (T-170 → T-171).
 *
 * Covers:
 *  - defaults match design.md §2.7 (100 entries / 25 MiB)
 *  - read() stores content, hash, size, lastReadTs
 *  - hashContent is stable for the same input
 *  - isUnchanged returns true for the same content, false for
 *    different content
 *  - re-reading the same path refreshes LRU position
 *  - eviction by entry count (LRU order)
 *  - eviction by total byte cap (LRU order)
 *  - touch() bumps the LRU position without changing content
 *  - delete() and clear() reset the state
 *  - peek() does NOT refresh the LRU position
 *  - positionOf() reports the LRU order
 */
import { describe, expect, it } from "vitest";

import {
  DEFAULT_MAX_BYTES,
  DEFAULT_MAX_ENTRIES,
  hashContent,
  ReadFileState,
  utf8ByteLength,
} from "../lru-file-state.js";

const FIXED_NOW = () => 1_700_000_000_000;

describe("defaults (T-170)", () => {
  it("default entry cap is 100 (design.md §2.7)", () => {
    expect(DEFAULT_MAX_ENTRIES).toBe(100);
    const lru = new ReadFileState();
    expect(lru.maxEntries).toBe(100);
  });

  it("default byte cap is 25 MiB (design.md §2.7)", () => {
    expect(DEFAULT_MAX_BYTES).toBe(25 * 1024 * 1024);
    const lru = new ReadFileState();
    expect(lru.maxBytes).toBe(25 * 1024 * 1024);
  });
});

describe("hashContent (T-171)", () => {
  it("is deterministic", () => {
    expect(hashContent("hello world")).toBe(hashContent("hello world"));
  });

  it("returns a 64-character lowercase hex string", () => {
    const h = hashContent("hello world");
    expect(h).toMatch(/^[0-9a-f]{64}$/);
  });

  it("two different contents produce different hashes", () => {
    expect(hashContent("hello world")).not.toBe(hashContent("hello WORLD"));
  });

  it("an empty string has a well-defined hash", () => {
    const h = hashContent("");
    expect(h).toMatch(/^[0-9a-f]{64}$/);
    expect(h).toBe(
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    );
  });
});

describe("utf8ByteLength", () => {
  it("ASCII content equals string length", () => {
    expect(utf8ByteLength("hello")).toBe(5);
  });

  it("multi-byte characters are counted in UTF-8 bytes", () => {
    // "中文" is 6 bytes in UTF-8.
    expect(utf8ByteLength("中文")).toBe(6);
  });
});

describe("ReadFileState.read", () => {
  it("stores content, hash, size, and lastReadTs", () => {
    let now = 1000;
    const lru = new ReadFileState({ now: () => now });
    const e = lru.read("/a/b.txt", "hello");
    expect(e.path).toBe("/a/b.txt");
    expect(e.content).toBe("hello");
    expect(e.hash).toBe(hashContent("hello"));
    expect(e.size).toBe(5);
    expect(e.lastReadTs).toBe(1000);
    now = 2000;
    const e2 = lru.read("/a/c.txt", "world");
    expect(e2.lastReadTs).toBe(2000);
  });

  it("refreshing the same path updates the entry", () => {
    let now = 1000;
    const lru = new ReadFileState({ now: () => now });
    lru.read("/a/b.txt", "hello");
    now = 2000;
    lru.read("/a/b.txt", "hello world");
    const peeked = lru.peek("/a/b.txt");
    expect(peeked?.size).toBe(11);
    expect(peeked?.lastReadTs).toBe(2000);
  });
});

describe("ReadFileState.isUnchanged", () => {
  it("returns true when the cached hash matches the new content", () => {
    const lru = new ReadFileState({ now: FIXED_NOW });
    lru.read("/a/b.txt", "hello");
    expect(lru.isUnchanged("/a/b.txt", "hello")).toBe(true);
  });

  it("returns false when the content has changed", () => {
    const lru = new ReadFileState({ now: FIXED_NOW });
    lru.read("/a/b.txt", "hello");
    expect(lru.isUnchanged("/a/b.txt", "hello world")).toBe(false);
  });

  it("returns false when the path is not cached", () => {
    const lru = new ReadFileState({ now: FIXED_NOW });
    expect(lru.isUnchanged("/missing", "anything")).toBe(false);
  });
});

describe("ReadFileState eviction (T-170)", () => {
  it("evicts the least-recently-used entry when over the entry cap", () => {
    const lru = new ReadFileState({ maxEntries: 2, now: FIXED_NOW });
    lru.read("/a", "x");
    lru.read("/b", "y");
    lru.read("/c", "z");
    expect(lru.size()).toBe(2);
    expect(lru.peek("/a")).toBeNull();
    expect(lru.peek("/b")).not.toBeNull();
    expect(lru.peek("/c")).not.toBeNull();
  });

  it("touching an entry moves it to the most-recently-used position", () => {
    const lru = new ReadFileState({ maxEntries: 2, now: FIXED_NOW });
    lru.read("/a", "x");
    lru.read("/b", "y");
    // Touch /a — now /b is the LRU and should be evicted.
    expect(lru.touch("/a")).toBe(true);
    lru.read("/c", "z");
    expect(lru.peek("/a")).not.toBeNull();
    expect(lru.peek("/b")).toBeNull();
    expect(lru.peek("/c")).not.toBeNull();
  });

  it("re-reading an existing path refreshes its LRU position", () => {
    const lru = new ReadFileState({ maxEntries: 2, now: FIXED_NOW });
    lru.read("/a", "x");
    lru.read("/b", "y");
    lru.read("/a", "x"); // refresh /a
    lru.read("/c", "z");
    expect(lru.peek("/a")).not.toBeNull();
    expect(lru.peek("/b")).toBeNull();
    expect(lru.peek("/c")).not.toBeNull();
  });

  it("evicts the oldest entries to satisfy the byte cap", () => {
    // 3 × 10 byte cap, entries 10/10/10: 30 bytes total, over the cap.
    // With maxBytes=15 the LRU evicts until totalBytes <= 15, so only
    // the most-recently-touched entry (/c) survives after 3 reads.
    const lru = new ReadFileState({ maxBytes: 15, now: FIXED_NOW });
    lru.read("/a", "x".repeat(10));
    lru.read("/b", "y".repeat(10));
    lru.read("/c", "z".repeat(10));
    // After 3 reads of 10 bytes each with cap 15, the LRU must
    // continuously evict the oldest to stay under the cap. Only /c
    // (the most recently inserted) survives.
    expect(lru.peek("/a")).toBeNull();
    expect(lru.peek("/b")).toBeNull();
    expect(lru.peek("/c")).not.toBeNull();
    expect(lru.stats().totalBytes).toBeLessThanOrEqual(15);
  });

  it("respects a maxBytes of 0 as 'no byte cap' (entries are only bounded by maxEntries)", () => {
    const lru = new ReadFileState({ maxBytes: 0, maxEntries: 100, now: FIXED_NOW });
    lru.read("/a", "x".repeat(100));
    lru.read("/b", "y".repeat(200));
    // Both entries fit because the byte cap is disabled.
    expect(lru.size()).toBe(2);
    expect(lru.stats().totalBytes).toBe(300);
  });
});

describe("ReadFileState.touch / peek / positionOf", () => {
  it("touch returns false when the path is not cached", () => {
    const lru = new ReadFileState({ now: FIXED_NOW });
    expect(lru.touch("/nope")).toBe(false);
  });

  it("peek does NOT refresh the LRU position", () => {
    const lru = new ReadFileState({ maxEntries: 2, now: FIXED_NOW });
    lru.read("/a", "x");
    lru.read("/b", "y");
    const beforeA = lru.positionOf("/a");
    lru.peek("/a");
    const afterA = lru.positionOf("/a");
    expect(beforeA).toBe(afterA);
  });

  it("positionOf reports 0 for the MRU and N-1 for the LRU", () => {
    const lru = new ReadFileState({ now: FIXED_NOW });
    lru.read("/a", "x");
    lru.read("/b", "y");
    lru.read("/c", "z");
    // Order of insertion: /a, /b, /c; /c is MRU.
    expect(lru.positionOf("/a")).toBe(0);
    expect(lru.positionOf("/b")).toBe(1);
    expect(lru.positionOf("/c")).toBe(2);
  });

  it("positionOf returns -1 for an unknown path", () => {
    const lru = new ReadFileState({ now: FIXED_NOW });
    expect(lru.positionOf("/nope")).toBe(-1);
  });
});

describe("ReadFileState.delete / clear / paths", () => {
  it("delete removes a single entry and updates the byte count", () => {
    const lru = new ReadFileState({ now: FIXED_NOW });
    lru.read("/a", "x".repeat(10));
    lru.read("/b", "y".repeat(20));
    expect(lru.stats().totalBytes).toBe(30);
    expect(lru.delete("/a")).toBe(true);
    expect(lru.peek("/a")).toBeNull();
    expect(lru.stats().totalBytes).toBe(20);
    expect(lru.delete("/a")).toBe(false);
  });

  it("clear empties the LRU", () => {
    const lru = new ReadFileState({ now: FIXED_NOW });
    lru.read("/a", "x");
    lru.read("/b", "y");
    lru.clear();
    expect(lru.size()).toBe(0);
    expect(lru.stats().totalBytes).toBe(0);
  });

  it("paths() returns the LRU order (oldest-first)", () => {
    const lru = new ReadFileState({ now: FIXED_NOW });
    lru.read("/a", "x");
    lru.read("/b", "y");
    lru.read("/c", "z");
    expect(lru.paths()).toEqual(["/a", "/b", "/c"]);
  });
});

describe("ReadFileState.stats", () => {
  it("reflects current entry count and byte total", () => {
    const lru = new ReadFileState({ now: FIXED_NOW });
    lru.read("/a", "x".repeat(100));
    lru.read("/b", "y".repeat(200));
    const stats = lru.stats();
    expect(stats.entryCount).toBe(2);
    expect(stats.totalBytes).toBe(300);
    expect(stats.maxEntries).toBe(100);
    expect(stats.maxBytes).toBe(25 * 1024 * 1024);
  });
});
