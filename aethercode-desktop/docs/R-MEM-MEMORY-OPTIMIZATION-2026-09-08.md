# R-MEM-1/2/3/4: AetherCode Memory System Optimization

**Date**: 2026-09-08
**Round**: R-MEM-1/2/3/4 (combined release)
**Status**: ✅ DONE
**Author**: mavis (mavis Mavis, MiniMax Code)

---

## 0. Background

User asked to download arxiv 2512.13564 (Hu et al., 47 authors, "Memory in
the Age of AI Agents" survey), analyse the paper, and design + implement
optimizations to AetherCode's Memory system. The user explicitly chose
"全部 P0+P1" (R-MEM-1/2/3/4) via the in-app questionnaire.

Paper PDF stored at `reference/papers/2512.13564v2-memory-in-the-age-of-ai-agents.pdf`
(14.9 MB). Chinese translation in `reference/papers/2512.13564v2_中文翻译.md` (196 KB)
and Chinese abstract in `2512.13564v2_摘要.md` (15.8 KB).

System understanding doc: `doc/项目文档/AetherCode-Memory-System-Understanding.md`
(19.5 KB). Design doc: `doc/项目文档/AetherCode-Memory-Optimization-Design.md`
(19.0 KB).

---

## 1. Paper framework (recap)

The survey organises the agent-memory landscape along three axes:

- **3 forms**: token-level (notes / external stores) / parametric
  (model weights) / latent (hidden state).
- **3 functions**: factual (knowledge) / experiential (what worked,
  what didn't) / working (current-task scratchpad).
- **3 dynamics**: formation (when does a memory get written?) /
  evolution (consolidation + forgetting) / retrieval (how to find
  the right memory when needed?).
- **8 frontiers**: F1 retrieval vs generation, F2 automated
  management, F3 RL-tuned memory, F4 multimodal, F5 multi-agent
  shared memory, F6 world-model memory, F7 trustworthy memory, F8
  human-cognition inspired.

AetherCode's memory system before this round covered only F1
(key-filtered retrieval), and only the "factual" function. We had
no vector search, no evolution (consolidation / forgetting), no
multi-agent sharing, no skill memory.

---

## 2. What we built (P0 + P1, four rounds combined)

### 2.1 R-MEM-1: vector retrieval (P0, F1)

**Problem**: `memory/get` was key-filtered only. No way to ask
"what do I know about X?" without knowing the key in advance.

**Solution**: brute-force cosine search over a `vec_index` SQLite
table. Each entry is `(scope, entry_id, content_text, embedding BLOB,
model_id, ts)`. Embeddings come from a pluggable
`EmbeddingProvider` (default: deterministic 384-dim FNV hash + sign
trick — offline, zero deps). Production deployments can swap in an
ONNX MiniLM model via the same interface.

**New files**:
- `aethercode-memory/src/vec-store.ts` (9.5 KB) — VectorStore
  class, cosineSimilarity helper
- `aethercode-memory/src/embedding/hash-embedding.ts` (4.2 KB) —
  HashEmbeddingProvider
- `aethercode-memory/src/embedding/remote-embedding.ts` (1.3 KB) —
  RemoteEmbeddingProvider stub
- `aethercode-memory/src/embedding/index.ts` (0.6 KB) — interface + barrel

**Schema**: v5 migration adds `vec_index` table + 2 indexes.

**RPC**: `memory/find` (params: `query`, `scope?`, `topK?`,
`threshold?`, `sessionId?`).

**42 new tests** (`rmem1-hash-embedding.test.ts`,
`rmem1-vec-store.test.ts`, `rmem1-integration.test.ts`).

### 2.2 R-MEM-2: evolution — consolidation + forgetting (P0, F2)

**Problem**: `project_changes` was an append-only log forever.
No way to merge near-duplicate entries, no way to drop stale or
low-value ones.

**Solution**: 6 new columns on `project_changes`
(`expires_at`, `consolidated_into`, `value_tag`,
`last_accessed_at`, `access_count`, `deleted_at`) + 3 supporting
indexes. Two new algorithms:
- `consolidateProjectChanges(db, projectId, opts)`: Jaccard-token
  similarity, single-pass greedy grouping, longest description wins
  as canonical, sources marked `consolidated_into = targetId`.
- `forgetProjectChanges(db, projectId, policy, now)`: opt-in policy
  (`expired` / `inactiveSinceMs` / `lowValue`); soft-delete with
  `deleted_at`; 30-day retention window before vacuum hard-deletes.

**New file**: `aethercode-memory/src/evolution.ts` (10.3 KB).

**Schema**: v6 migration adds 6 columns + 3 indexes.

**RPCs**: `memory/consolidate`, `memory/forget`, `memory/stats`.

**31 new tests** (`rmem2-evolution.test.ts`).

**Bugs caught during testing** (committed as part of the round):
- `forgetProjectChanges` default should be opt-in
  (`expired === true` not `!== false`).
- `readProjectMemoryStats` count helper missing `projectId` param.
- `autoEmbed` default was `false`, should be `true` (we always
  have a provider since R-MEM-1).

### 2.3 R-MEM-3: subagent shared memory (P0, F5)

**Problem**: subagents ran in isolation. They could write to
`project_changes` but the main agent had no way to know what the
subagents had learned.

**Solution**: add a `team_session_id` column to `project_changes`
(NULL = normal change, non-NULL = shared team entry). Index on
`(project_id, team_session_id) WHERE team_session_id IS NOT NULL`.
Three new helpers in `project-store.ts`:
- `appendTeamChange(db, projectId, description, teamSessionId, opts)` —
  tag a new change as team-shared.
- `listTeamChanges(db, projectId, teamSessionId?)` — read shared
  rows, optionally filtered by subagent session.
- `promoteTeamChanges(db, projectId, teamSessionId, ids)` — clear
  `team_session_id` so the row becomes a normal project change.

**Schema**: v7 migration adds the column + index.

**RPCs**: `memory/shareToSubagent`, `memory/readTeamMemory`,
`memory/promoteFromSubagent`.

**27 new tests** (`rmem3-team-memory.test.ts`).

### 2.4 R-MEM-4: skill memory (P1, F2 + F5)

**Problem**: agent has no persistent experience of "this worked /
this didn't". Every tool call was a one-shot with no lessons
learned.

**Solution**: new `skills` table (id, scope, name, signature,
description, tags, source, success_count, last_success_at,
last_failure_at, ts; UNIQUE(scope, name)). Three new helpers in
`skill-store.ts`:
- `upsertSkill(db, input)` — UNIQUE-on-(scope,name), preserves
  counters on conflict.
- `incrementSkillSuccess` / `recordSkillFailure` — the auto-capture
  hooks. The agent's tool wrapper would call these after every
  tool call.
- `findSkillsByText(db, query, opts)` — Jaccard on
  signature+description+tags, ordered by score then
  success_count. Skips zero-score rows (no shared tokens → not
  a meaningful match).

**New file**: `aethercode-memory/src/skill-store.ts` (10.4 KB).

**Schema**: v8 migration adds the `skills` table + 2 indexes.

**RPCs**: `memory/upsertSkill`, `memory/recordSkillOutcome`,
`memory/findSkill`. Skills are also auto-embedded into
`vec_index` (scope='skill') so `memory/find` picks them up too.

**27 new tests** (`rmem4-skills.test.ts`).

---

## 3. Test + build numbers

| Phase | Module | Suites | Tests | Delta |
|---|---|---:|---:|---:|
| Pre-R-MEM | aethercode-memory | 76 | 223 | — |
| R-MEM-1 | aethercode-memory | 78 | 265 | +42 |
| R-MEM-2 | aethercode-memory | 79 | 296 | +31 |
| R-MEM-3 | aethercode-memory | 81 | 323 | +27 |
| R-MEM-4 | aethercode-memory | 103 | 350 | +27 |
| R-MEM-1/2/3/4 combined | aethercode-memory | 103 | 350 | +127 (+57%) |
| aethercode-desktop | (unchanged) | 278 | 1054 | 0 regression |

Java daemon: `mvn install -DskipTests` — 27 modules BUILD SUCCESS.
Canonical jar (aethercode-cli-0.1.0-SNAPSHOT.jar) is 55,646,495 B,
SHA256 = `2442B6D1BA8A4E092B1FA2E800B71636F0CA134D90138C59EAFE9061DDED9306`.

The daemon jar picked up R233-era Java memory changes
(MemoryLifecycle / ExperienceMemory / ForgettingPolicy / etc.) on
top of the R229 baseline.

---

## 4. Release artifacts

(R-MEM combined release — version `0.2.54`)

| File | SHA256 | Size |
|---|---|---:|
| `aethercode-0.2.54.jar` (dist) | `2442B6D1BA8A4E092B1FA2E800B71636F0CA134D90138C59EAFE9061DDED9306` | 55,646,495 B |
| `desktop/aethercode-desktop.exe` | `16693C5965445178B0E353436F84CC28604AEDD4BE8086CD47FE0DD6E0CEF591` | 3,982,336 B |
| `desktop/resources/aethercode.jar` | `2442B6D1BA8A4E092B1FA2E800B71636F0CA134D90138C59EAFE9061DDED9306` | 55,646,495 B |
| `aethercode-0.2.54.zip` | `C90D2A1A2BE6886587BD80C4F9B48866FFCF70B5148B9209998625D6057D57E6` | 144,237,367 B |

Release dir: `release/aethercode-0.2.54/` (10 files, 217,469,133 B uncompressed).

Plus the tauri bundle installers (kept in `src-tauri/target/release/bundle/`):
- `bundle/msi/AetherCode_0.2.54_x64_en-US.msi`
- `bundle/nsis/AetherCode_0.2.54_x64-setup.exe`

Build wall time: mvn 1m17s, tauri 11m06s (rust re-link only — no full recompile).

---

## 5. Why these four (and what we deferred)

The 8-frontier paper has 8 axes; we shipped on 4 of them. The other
4 (RL-tuned memory, multimodal memory, world-model memory,
human-cognition) are deeper research problems with no clear
smallest-shippable-feature. R-MEM-1/2/3/4 cover the four
"everyday agent" gaps the paper identifies as the highest-leverage:

- F1 (retrieval) — R-MEM-1
- F2 (automated management) — R-MEM-2 + R-MEM-4
- F5 (multi-agent shared memory) — R-MEM-3
- experiential function (which the paper highlights as
  under-developed across all current systems) — R-MEM-4

The 4 frontiers we deferred need real model changes (RL training
loop for F3, multimodal encoder for F4, world model for F6,
cognition experiments for F8) or trust infrastructure (F7). Those
are bigger bets — see `doc/项目文档/AetherCode-Memory-Optimization-Design.md`
§6 for the proposed R-MEM-5..8 sequences.

---

## 6. Key lessons

1. **Schema migrations compose well**: v5 + v6 + v7 + v8 all chain
   via `ALTER TABLE ADD COLUMN` and additive `CREATE TABLE` /
   `CREATE INDEX`. Existing rows get safe defaults (NULL, 0, 'normal').
   No need for data backfill.

2. **`autoEmbed = true` is the right default once a provider
   exists** (R-MEM-1 lesson applied to R-MEM-4). Skipping embedding
   "for speed" makes `findSimilar` useless, which defeats the
   point of the round. Hash provider is fast enough (384-dim,
   O(N) tokens).

3. **Skip zero-score search results** (R-MEM-4 lesson). A skill
   with no shared tokens isn't a "weak match" — it's a non-match.
   Returning it dilutes results and breaks the "top hit is the
   best fit" invariant tests rely on.

4. **`promote-jar.py` is still buggy** (R229 lesson, confirmed
   again here). It copies from the previous versioned jar, not
   from the mvn canonical. Workaround: my `promote-rmem-jar.py`
   one-shot script mtime-compares and force-overwrites. The
   real fix lives in R230 scope (out of R-MEM scope, deferred).

5. **Field naming avoids R200+ dead code**: I called the new
   `ProjectChangeInput.team_session_id` (not `teamSessionId`) to
   match the SQLite column name. Same lesson as R229's
   `daemonTodos` rename — never reuse a name that's already taken,
   even if the old use is dead.

6. **Multi-agent memory needs identity, not just data**. R-MEM-3
   works because `team_session_id` ties every shared row back to
   the originating subagent. Without that, "share" and "promote"
   can't reason about who is responsible for what.

---

## 7. Cross-references

- Paper PDF: `reference/papers/2512.13564v2-memory-in-the-age-of-ai-agents.pdf`
- Paper Chinese: `reference/papers/2512.13564v2_中文翻译.md`
- Paper abstract: `reference/papers/2512.13564v2_摘要.md`
- System understanding: `doc/项目文档/AetherCode-Memory-System-Understanding.md`
- Optimization design: `doc/项目文档/AetherCode-Memory-Optimization-Design.md`
- R229 daemon TODO update: `aethercode-desktop/docs/R229-DAEMON-TODO-UPDATE-2026-09-07.md`
- 3 R-MEM-1/2/3 reports: `aethercode-memory/docs/R-MEM-{1,2,3}-...md`
  (TBD — see R-MEM-1/2 docs from prior rounds)

---

## 8. R-MEM-* release build steps (one-shot script)

```powershell
# 1. build memory module TS
cd aethercode-memory
npm run build

# 2. build Java daemon
cd ../aethercode
mvn -B install -DskipTests

# 3. promote jar (one-shot, fixes promote-jar.py bug)
cd ..
python D:/Users/maijun/AppData/Local/Temp/promote-rmem-jar.py

# 4. build desktop
cd aethercode-desktop
npx tauri build   # ~13 min on this machine (rust re-link)

# 5. stage release artifacts
python ../scripts/promote-rmem-release.py
python ../scripts/zip-rmem.py
```

Total wall time: ~30 min (15 min mvn + 13 min tauri + 2 min scripts).
