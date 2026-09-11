# aethercode-memory

Three-layer memory system for AetherCode: **global / project / session**, with
a three-tier backend cascade (**LRU cache → SQLite → markdown file**).

This is Phase 1 of the memory module rebuild — see `design.md` §1 for the full
plan. The first 15 core tasks (T-001 to T-017) deliver the module skeleton,
type definitions, markdown parse/serialize, and the versioned SQLite
migration runner.

## Layout

```
aethercode-memory/
├── package.json
├── tsconfig.json          # strict mode, ES2022
├── tsconfig.build.json    # build-only (excludes tests)
├── vitest.config.ts
├── .eslintrc.json
├── .prettierrc.json
└── src/
    ├── index.ts           # public API (barrel)
    ├── types.ts           # MemoryEntry, Fact, Rule, ChangeEntry, Breadcrumb
    ├── markdown.ts        # parse/serialize <UserHome>/.aethercode/memory.md
    ├── project-markdown.ts# parse/serialize <cwd>/.aethercode/memory.md
    ├── sqlite.ts          # versioned migration runner
    └── __tests__/
        ├── smoke.test.ts
        ├── types.test.ts
        ├── markdown.test.ts
        ├── project-markdown.test.ts
        └── sqlite.test.ts
```

## Build & test

```bash
pnpm install
pnpm test       # vitest run
pnpm build      # tsc -p tsconfig.build.json → dist/
```

## Public API (Phase 1)

```ts
import {
  parseGlobalMemory,
  serializeGlobalMemory,
  parseProjectMemory,
  serializeProjectMemory,
  openAndMigrate,
  withDatabase,
  DEFAULT_MIGRATIONS,
  isFact, isRule, isChange, isBreadcrumb,
} from 'aethercode-memory';
```

### Types

- `MemoryEntry` — discriminated union of `Fact | Rule | ChangeEntry | Breadcrumb`
- `MemoryReadResult` — `{ source, entries, totalTokens, truncated }`
- `MemoryScope` — `'global' | 'project' | 'session'`
- `MemorySource` — provenance tag

### Markdown

- `parseGlobalMemory(text, now?)` → `GlobalMemory` (facts, rules, breadcrumbs)
- `serializeGlobalMemory(memory)` → canonical markdown string
- `parseProjectMemory(text, now?)` → `ProjectMemory` (title, description, changes, facts)
- `serializeProjectMemory(memory, changeLimit?)` → canonical markdown string

### SQLite

- `openAndMigrate(path, migrations?)` → open `Database` handle
- `withDatabase(path, fn, migrations?)` → run `fn` then auto-close
- `readSchemaVersion(db)` → current `user_version` (0 if uninitialized)
- `DEFAULT_MIGRATIONS` — three migrations matching `design.md` §1.2:
  1. `session_messages`
  2. `project_db`
  3. `project_changes`

## Phase 2+ (later tasks)

- T-018 to T-024: session_messages read/write, project_db/project_changes
  read/write, jsonl writer/folder, atomic fsync.
- T-030 to T-036: LRU cache.
- T-040 to T-049: `MemoryStore` read/write API.
- T-050 to T-058: project compression.
- T-060 to T-076: cwd switch + RPC methods.
- T-080 to T-094: TUI `MemoryPanel` and CLI commands.
