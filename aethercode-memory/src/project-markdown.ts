/**
 * Project memory file — `<cwd>/.aethercode/memory.md`.
 *
 * Layout (see design.md §1.2):
 *
 * ```markdown
 * # <Project Title>
 *
 * ## 说明 (Description)
 * <LLM-merged description of the project>
 *
 * ## 修改记录 (Change Log, 最近 N 次 = recent N entries)
 * - 2026-08-28 12:00 — <short description>
 *
 * ## Project facts
 * - <project-specific fact 1>
 * ```
 *
 * The Chinese headings are preserved verbatim. The parser is permissive about
 * casing/spacing and a trailing `(最近 N 次)` parenthetical. The serializer
 * writes the canonical form.
 */

import type { ChangeEntry, Fact, MemoryEntry } from './types.js';

/** Canonical section headings — these are the literal strings we emit. */
const HEADING_DESCRIPTION = '说明';
const HEADING_CHANGES = '修改记录';
const HEADING_FACTS = 'Project facts';

/** Lower-cased / parenthesised-stripped forms used for lookups. */
const HEADING_DESCRIPTION_KEY = HEADING_DESCRIPTION.toLowerCase();
const HEADING_CHANGES_KEY = HEADING_CHANGES.toLowerCase();
const HEADING_FACTS_KEY = HEADING_FACTS.toLowerCase();

/** Parsed project memory. */
export interface ProjectMemory {
  readonly title: string;
  readonly description: string;
  readonly changes: ReadonlyArray<ChangeEntry>;
  readonly facts: ReadonlyArray<Fact>;
}

/** Strip an optional `(...)` suffix from a heading line. */
function canonicaliseHeading(raw: string): string {
  return raw.replace(/\s*\([^)]*\)\s*$/u, '').trim().toLowerCase();
}

/** Split a markdown document into sections keyed by their canonical heading. */
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
      current = canonicaliseHeading(heading[1]);
      buf = [];
    } else if (current !== null) {
      buf.push(line);
    }
  }
  flush();
  return out;
}

function findAnySection(
  sections: Map<string, string[]>,
  candidates: ReadonlyArray<string>,
): ReadonlyArray<string> | null {
  for (const c of candidates) {
    const body = sections.get(c);
    if (body !== undefined) return body;
  }
  return null;
}

function extractBullets(body: ReadonlyArray<string>): string[] {
  const out: string[] = [];
  for (const line of body) {
    const trimmed = line.trim();
    if (!trimmed) continue;
    const m = /^[-*]\s+(.*)$/.exec(trimmed);
    if (m) {
      out.push(m[1].trim());
    } else if (out.length > 0) {
      out[out.length - 1] = `${out[out.length - 1]} ${trimmed}`;
    } else {
      out.push(trimmed);
    }
  }
  return out;
}

/** Extract the H1 title or fall back to a default. */
function extractTitle(text: string, fallback: string): string {
  for (const rawLine of text.split(/\r?\n/)) {
    const m = /^\s*#\s+(.+?)\s*$/.exec(rawLine);
    if (m) {
      const title = m[1].trim();
      if (title && title.toLowerCase() !== 'global memory') return title;
    }
  }
  return fallback;
}

/** Parse a timestamp prefix from a change bullet, if present. */
function extractChangeTs(bullet: string, fallback: number): { ts: number; rest: string } {
  // ISO date or YYYY-MM-DD HH:MM at the start.
  const iso = /^(\d{4}-\d{2}-\d{2})(?:[ T](\d{2}:\d{2}(?::\d{2})?))?\s*[—\-:]\s*(.*)$/u.exec(bullet);
  if (iso) {
    const date = iso[1];
    const time = iso[2] ?? '00:00';
    const rest = iso[3];
    const ts = Date.parse(`${date}T${time.length === 5 ? `${time}:00` : time}`);
    return { ts: Number.isFinite(ts) ? ts : fallback, rest };
  }
  return { ts: fallback, rest: bullet };
}

/**
 * Parse a project memory markdown document into structured entries.
 */
export function parseProjectMemory(text: string, now: number = Date.now()): ProjectMemory {
  const title = extractTitle(text, 'Untitled Project');
  const sections = splitSections(text);

  // Description (## 说明 / Description)
  const descBody = findAnySection(sections, [HEADING_DESCRIPTION_KEY, 'description', 'desc']) ?? [];
  const description = descBody.join('\n').trim();

  // Changes (## 修改记录 / Change Log ...) — keep the N from the heading if present.
  const changesBody = findAnySection(sections, [HEADING_CHANGES_KEY, 'changes', 'change log']) ?? [];
  const changeBullets = extractBullets(changesBody);
  const changes: ChangeEntry[] = [];
  let counter = 0;
  for (const bullet of changeBullets) {
    const { ts, rest } = extractChangeTs(bullet, now);
    changes.push({
      kind: 'change',
      id: `project-change-${counter++}`,
      ts,
      scope: 'project',
      source: 'system',
      tags: ['change'],
      description: rest,
      compressed: false,
    });
  }

  // Project facts (## Project facts)
  const factsBody = findAnySection(sections, [HEADING_FACTS_KEY, 'project-facts', 'facts']) ?? [];
  const factBullets = extractBullets(factsBody);
  const facts: Fact[] = [];
  counter = 0;
  for (const bullet of factBullets) {
    const colon = bullet.indexOf(':');
    if (colon > 0 && colon < bullet.length - 1) {
      facts.push({
        kind: 'fact',
        id: `project-fact-${counter++}`,
        ts: now,
        scope: 'project',
        source: 'user',
        tags: [],
        key: bullet.slice(0, colon).trim(),
        value: bullet.slice(colon + 1).trim(),
      });
    } else {
      facts.push({
        kind: 'fact',
        id: `project-fact-${counter++}`,
        ts: now,
        scope: 'project',
        source: 'user',
        tags: [],
        key: `note.${counter}`,
        value: bullet,
      });
    }
  }

  return { title, description, changes, facts };
}

/** Serialize project memory back to canonical markdown. */
export function serializeProjectMemory(memory: ProjectMemory, changeLimit: number = 20): string {
  const out: string[] = [];
  out.push(`# ${memory.title || 'Untitled Project'}`);
  out.push('');
  out.push(`## ${HEADING_DESCRIPTION}`);
  out.push(memory.description || '');
  out.push('');
  out.push(`## ${HEADING_CHANGES} (最近 ${changeLimit} 次)`);
  if (memory.changes.length === 0) {
    out.push('');
  } else {
    // Keep only the most recent `changeLimit` entries.
    const recent = memory.changes.slice(-changeLimit);
    for (const c of recent) {
      const ts = new Date(c.ts);
      const date = `${ts.getFullYear()}-${pad2(ts.getMonth() + 1)}-${pad2(ts.getDate())}`;
      const time = `${pad2(ts.getHours())}:${pad2(ts.getMinutes())}`;
      out.push(`- ${date} ${time} — ${c.description}`);
    }
    out.push('');
  }
  out.push(`## ${HEADING_FACTS}`);
  if (memory.facts.length === 0) {
    out.push('');
  } else {
    for (const f of memory.facts) {
      out.push(`- ${f.key}: ${f.value}`);
    }
    out.push('');
  }
  while (out.length > 0 && out[out.length - 1] === '') {
    out.pop();
  }
  return out.join('\n') + '\n';
}

function pad2(n: number): string {
  return n < 10 ? `0${n}` : String(n);
}

/** Convenience: flatten a ProjectMemory into a MemoryEntry[]. */
export function projectMemoryEntries(memory: ProjectMemory): ReadonlyArray<MemoryEntry> {
  return [...memory.changes, ...memory.facts];
}

/**
 * Read & parse the project memory file at the given path. If the file
 * does not exist, returns a fresh, empty ProjectMemory with the given
 * default title.
 */
export function readProjectMemoryFile(
  fs: { readFileSync: (path: string, encoding: 'utf-8') => string; existsSync: (path: string) => boolean },
  path: string,
  defaultTitle: string,
  now?: number,
): ProjectMemory {
  if (!fs.existsSync(path)) {
    return { title: defaultTitle, description: '', changes: [], facts: [] };
  }
  const text = fs.readFileSync(path, 'utf-8');
  return parseProjectMemory(text, now);
}
