/**
 * Global memory file — `<UserHome>/.aethercode/memory.md`.
 *
 * Layout (see design.md §1.2):
 *
 * ```markdown
 * # Global Memory
 *
 * ## Facts
 * - <user fact 1>
 * - <user fact 2>
 *
 * ## Rules
 * - <rule 1>
 * - <rule 2>
 *
 * ## Cross-project breadcrumbs
 * - 2026-08-28 — switched from <old> to <new>
 * ```
 *
 * The parser is permissive: missing sections become empty arrays; unknown
 * sections are preserved as `breadcrumb` entries. The serializer emits the
 * canonical layout deterministically so byte-equal round-trips are stable.
 */

import type { Breadcrumb, Fact, MemoryEntry, Rule } from './types.js';

/** Known section headings in the global memory file (English). */
const SECTION_FACTS = 'facts';
const SECTION_RULES = 'rules';
const SECTION_BREADCRUMBS = 'cross-project breadcrumbs';

/** Sections we expect to find. */
export type GlobalSection = 'facts' | 'rules' | 'breadcrumbs';

/** Parsed global memory. */
export interface GlobalMemory {
  readonly facts: ReadonlyArray<Fact>;
  readonly rules: ReadonlyArray<Rule>;
  readonly breadcrumbs: ReadonlyArray<Breadcrumb>;
}

interface MutableGlobalMemory {
  facts: Fact[];
  rules: Rule[];
  breadcrumbs: Breadcrumb[];
}

const EMPTY_GLOBAL: GlobalMemory = Object.freeze({
  facts: Object.freeze([]) as ReadonlyArray<Fact>,
  rules: Object.freeze([]) as ReadonlyArray<Rule>,
  breadcrumbs: Object.freeze([]) as ReadonlyArray<Breadcrumb>,
});

/** Split a markdown document into sections keyed by their `##` heading. */
function splitSections(text: string): Map<string, string[]> {
  const out = new Map<string, string[]>();
  let current: string | null = null;
  let buf: string[] = [];
  const flush = (): void => {
    if (current !== null) {
      out.set(current, buf);
    }
    current = null;
    buf = [];
  };
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trimEnd();
    const heading = /^\s*##\s+(.+?)\s*$/.exec(line);
    if (heading) {
      flush();
      current = heading[1].trim().toLowerCase();
      buf = [];
    } else if (current !== null) {
      buf.push(line);
    }
  }
  flush();
  return out;
}

/** Extract bullet items from a section body. */
function extractBullets(body: ReadonlyArray<string>): string[] {
  const out: string[] = [];
  for (const line of body) {
    const trimmed = line.trim();
    if (!trimmed) continue;
    const m = /^[-*]\s+(.*)$/.exec(trimmed);
    if (m) {
      out.push(m[1].trim());
    } else if (out.length > 0) {
      // Continuation line — append to the previous bullet.
      out[out.length - 1] = `${out[out.length - 1]} ${trimmed}`;
    } else {
      out.push(trimmed);
    }
  }
  return out;
}

/**
 * Parse a global memory markdown document into structured entries.
 * Unknown sections are ignored; missing sections yield empty arrays.
 */
export function parseGlobalMemory(text: string, now: number = Date.now()): GlobalMemory {
  const sections = splitSections(text);
  const factsSection = sections.get(SECTION_FACTS) ?? [];
  const rulesSection = sections.get(SECTION_RULES) ?? [];
  const crumbsSection = sections.get(SECTION_BREADCRUMBS) ?? [];

  const factBullets = extractBullets(factsSection);
  const ruleBullets = extractBullets(rulesSection);
  const crumbBullets = extractBullets(crumbsSection);

  if (factBullets.length === 0 && ruleBullets.length === 0 && crumbBullets.length === 0) {
    return EMPTY_GLOBAL;
  }

  const mem: MutableGlobalMemory = { facts: [], rules: [], breadcrumbs: [] };
  let counter = 0;
  const nextId = (kind: string): string => `global-${kind}-${counter++}`;

  for (const bullet of factBullets) {
    // Facts may be either "key: value" or free text.
    const colon = bullet.indexOf(':');
    const fact: Fact =
      colon > 0 && colon < bullet.length - 1
        ? {
            kind: 'fact',
            id: nextId('fact'),
            ts: now,
            scope: 'global',
            source: 'user',
            tags: [],
            key: bullet.slice(0, colon).trim(),
            value: bullet.slice(colon + 1).trim(),
          }
        : {
            kind: 'fact',
            id: nextId('fact'),
            ts: now,
            scope: 'global',
            source: 'user',
            tags: [],
            key: `note.${counter}`,
            value: bullet,
          };
    mem.facts.push(fact);
  }
  for (const bullet of ruleBullets) {
    mem.rules.push({
      kind: 'rule',
      id: nextId('rule'),
      ts: now,
      scope: 'global',
      source: 'user',
      tags: [],
      text: bullet,
    });
  }
  for (const bullet of crumbBullets) {
    mem.breadcrumbs.push({
      kind: 'breadcrumb',
      id: nextId('crumb'),
      ts: now,
      scope: 'global',
      source: 'system',
      tags: ['breadcrumb'],
      message: bullet,
    });
  }
  return mem;
}

/** Serialize a global memory object back to canonical markdown. */
export function serializeGlobalMemory(memory: GlobalMemory): string {
  const out: string[] = [];
  out.push('# Global Memory');
  out.push('');
  out.push('## Facts');
  if (memory.facts.length === 0) {
    out.push('');
  } else {
    for (const f of memory.facts) {
      out.push(`- ${f.key}: ${f.value}`);
    }
    out.push('');
  }
  out.push('## Rules');
  if (memory.rules.length === 0) {
    out.push('');
  } else {
    for (const r of memory.rules) {
      out.push(`- ${r.text}`);
    }
    out.push('');
  }
  out.push('## Cross-project breadcrumbs');
  if (memory.breadcrumbs.length === 0) {
    out.push('');
  } else {
    for (const c of memory.breadcrumbs) {
      out.push(`- ${c.message}`);
    }
    out.push('');
  }
  // Drop trailing empty line but keep a single trailing newline.
  while (out.length > 0 && out[out.length - 1] === '') {
    out.pop();
  }
  return out.join('\n') + '\n';
}

/** Convenience: flatten a GlobalMemory into a MemoryEntry[]. */
export function globalMemoryEntries(memory: GlobalMemory): ReadonlyArray<MemoryEntry> {
  return [...memory.facts, ...memory.rules, ...memory.breadcrumbs];
}
