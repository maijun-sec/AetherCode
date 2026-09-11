/**
 * LRU `readFileState` map (T-170 → T-171).
 *
 * Per `design.md §2.7` and `spec.md §2.6`:
 *
 * - A bounded LRU map keyed by absolute file path, default capacity
 *   **100 entries** and **25 MB** total content size.
 * - Each entry stores the file content, the SHA-256 hash of that
 *   content, the size in bytes, and the last-read epoch ms.
 * - On every read we (re)compute the hash and refresh the LRU
 *   position. On a second read of an unchanged file the cache is
 *   served without re-parsing the file.
 * - When the LRU exceeds the entry or byte cap, the least-recently
 *   used entry is evicted until the cap is satisfied.
 *
 * The module is in-process and does not touch the filesystem itself;
 * callers pass the file content in. The 25 MB cap is on the *content*
 * (UTF-8 byte length), not on the LRU overhead.
 *
 * T-172 / T-173 (the actual dedup + re-mount wiring) live in the TUI
 * runtime, not in this module; this is just the data structure.
 */
import { createHash } from "node:crypto";
/** Default maximum number of entries. Matches `design.md §2.7`. */
export const DEFAULT_MAX_ENTRIES = 100;
/** Default maximum total content size in bytes. Matches `design.md §2.7`. */
export const DEFAULT_MAX_BYTES = 25 * 1024 * 1024;
/**
 * Compute the SHA-256 hex hash of a UTF-8 string. Exposed so the
 * pipeline can compare hashes without importing `node:crypto`
 * itself. Returns a 64-character lowercase hex string.
 */
export function hashContent(content) {
    return createHash("sha256").update(content, "utf8").digest("hex");
}
/**
 * UTF-8 byte length of a string. Node's `Buffer.byteLength` is used
 * for accuracy; for ASCII text the result equals `s.length`.
 */
export function utf8ByteLength(content) {
    return Buffer.byteLength(content, "utf8");
}
/**
 * The LRU `readFileState` map. Internally backed by a `Map` (which
 * preserves insertion order); `touch` re-inserts the entry to move it
 * to the most-recently-used position. Eviction is a single forward
 * scan from the oldest entry.
 */
export class ReadFileState {
    maxEntries;
    maxBytes;
    now;
    entries = new Map();
    totalBytes = 0;
    constructor(options = {}) {
        const me = options.maxEntries ?? DEFAULT_MAX_ENTRIES;
        const mb = options.maxBytes ?? DEFAULT_MAX_BYTES;
        this.maxEntries = Math.max(1, Math.floor(me));
        this.maxBytes = Math.max(0, Math.floor(mb));
        this.now = options.now ?? Date.now;
    }
    /** Read (or refresh) an entry. Returns the resulting entry. */
    read(path, content) {
        const size = utf8ByteLength(content);
        const hash = hashContent(content);
        const ts = this.now();
        // If a stale entry is already in the LRU, drop it so the byte
        // accounting stays correct; we'll re-insert below.
        const existing = this.entries.get(path);
        if (existing) {
            this.totalBytes -= existing.size;
            this.entries.delete(path);
        }
        const entry = { path, hash, size, content, lastReadTs: ts };
        this.entries.set(path, entry);
        this.totalBytes += size;
        this.evictIfNeeded();
        return { ...entry };
    }
    /**
     * Look up an entry by path without refreshing its LRU position.
     * Returns `null` when the path is not cached.
     */
    peek(path) {
        const e = this.entries.get(path);
        if (!e) {
            return null;
        }
        return { ...e };
    }
    /**
     * Touch (refresh LRU position) without changing content. Useful
     * for tests that want to simulate "the file is still being
     * referenced".
     */
    touch(path, ts) {
        const e = this.entries.get(path);
        if (!e) {
            return false;
        }
        this.entries.delete(path);
        e.lastReadTs = ts ?? this.now();
        this.entries.set(path, e);
        return true;
    }
    /**
     * Return `true` if the cached content for `path` is byte-identical
     * (by SHA-256) to the given `content`. Returns `false` when the
     * path is not cached.
     */
    isUnchanged(path, content) {
        const e = this.entries.get(path);
        if (!e) {
            return false;
        }
        return e.hash === hashContent(content);
    }
    /**
     * Hash a piece of content using the same algorithm as `read` and
     * return the hex string. Exposed so the dedup pass (T-172) can
     * compare a new tool result body against the stored hash.
     */
    hashOf(content) {
        return hashContent(content);
    }
    /** Delete a single entry. Returns `true` when an entry was removed. */
    delete(path) {
        const e = this.entries.get(path);
        if (!e) {
            return false;
        }
        this.totalBytes -= e.size;
        this.entries.delete(path);
        return true;
    }
    /** Clear all entries. */
    clear() {
        this.entries.clear();
        this.totalBytes = 0;
    }
    /** Return the current entry count. */
    size() {
        return this.entries.size;
    }
    /** Return a snapshot of the LRU stats. */
    stats() {
        return {
            entryCount: this.entries.size,
            totalBytes: this.totalBytes,
            maxEntries: this.maxEntries,
            maxBytes: this.maxBytes,
        };
    }
    /**
     * Return the LRU position of `path`: 0 = most recently used, larger
     * numbers = older. Returns `-1` when the path is not cached. Used
     * by tests to assert eviction order without reaching into the
     * internal `Map`.
     */
    positionOf(path) {
        let i = 0;
        for (const p of this.entries.keys()) {
            if (p === path) {
                return i;
            }
            i += 1;
        }
        return -1;
    }
    /** Return all cached paths, oldest-first. */
    paths() {
        return Array.from(this.entries.keys());
    }
    /**
     * Evict entries until the LRU satisfies both caps. Iterating
     * `Map.keys()` yields entries in insertion order, which is
     * least-recently-used-first because `touch` re-inserts the
     * entry at the tail.
     */
    evictIfNeeded() {
        while (this.entries.size > this.maxEntries ||
            (this.maxBytes > 0 && this.totalBytes > this.maxBytes)) {
            const oldestKey = this.entries.keys().next().value;
            if (typeof oldestKey !== "string") {
                break;
            }
            const oldest = this.entries.get(oldestKey);
            if (!oldest) {
                break;
            }
            this.totalBytes -= oldest.size;
            this.entries.delete(oldestKey);
        }
    }
}
