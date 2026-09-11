# R-MEM-5: Three Frontiers in One Round (F4 + F7 + F8)

**Date**: 2026-09-08
**Round**: R-MEM-5 (combined: F4 Multimodal + F7 Trust + F8 Cognition)
**Status**: ✅ DONE
**Author**: mavis (mavis Mavis, MiniMax Code)

---

## 0. Background

After R-MEM-1/2/3/4 closed the conservative P0+P1 gaps from the
arxiv 2512.13564 survey, the user asked to "挑一个 frontier 大赌注"
(pick a frontier as a big bet), then immediately upgraded to
"全部都执行" (do all three at once). The three frontiers shipped
in this round are F4 (multimodal), F7 (trust), and F8 (cognition)
— the higher-risk additions on top of the conservative base.

Paper PDF: `reference/papers/2512.13564v2-memory-in-the-age-of-ai-agents.pdf`.

---

## 1. F4 Multimodal — image memory (R-MEM-5.1)

**Problem**: only text content can land in the vector index. A
user who pastes a screenshot or attaches an image has no way to
ask "did I see this error before?".

**Solution**: extend `vec_index` with two columns:
- `media_type TEXT NOT NULL DEFAULT 'text'` — `'text'` or `'image'`
- `media_ref TEXT` — file path or SHA-256 of the file content

Plus a new `image-hash.ts` helper (`sha256File` / `sha256Buffer`
/ `embedTextForImage`) and a new `MemoryStore.appendImage` API
that takes a description + (filePath | fileBuffer | mediaRef) and
embeds the description + hash fragment as the embedding text.

RPC: `memory/appendImage`. `memory/find` and `memory/findByType`
both surface `mediaType` + `mediaRef` on every hit.

**New file**: `aethercode-memory/src/image-hash.ts` (1.8 KB).

**Schema**: v9 migration adds 2 columns + 1 index.

**Tests**: 20 new cases (`rmem5-multimodal.test.ts`).

**Out of scope (deferred)**: real CLIP/vision encoder for image
embeddings. The hash-based fingerprint is enough to dedupe
identical images and to "remember" what an image *was about* via
its user-supplied description. A future round can plug a real
encoder into the `EmbeddingProvider` interface.

---

## 2. F7 Trust — provenance chain (R-MEM-5.2)

**Problem**: nothing in the memory store detects tampering. A
rogue cleanup script that mass-edits the project_changes table
silently rewrites history. SQLite WAL races can lose or
reorder rows. The user has no way to know.

**Solution**: a per-scope linked list of (entry_id, content_hash,
prev_content_hash) rows in a new `provenance_chain` table.
`contentHash` is SHA-256 of `entry_id | content | ts`. Every
write to the standard tables (project_changes, session_messages,
skills, vec_index image rows) auto-records a chain row.

`MemoryStore.verifyChain(scope)` walks the chain in ts order,
recomputes each hash from the backing-store content via a
scope-aware provider, and returns either `{ ok: true, count,
headHash }` or `{ ok: false, count, brokenAt, reason: 'broken-link'
| 'tamper', details }`.

**New file**: `aethercode-memory/src/provenance-store.ts` (7.2 KB).

**Schema**: v10 migration creates `provenance_chain` + 2 indexes.

**RPCs**: `memory/recordProvenance` (explicit), `memory/verifyChain`.

**Tests**: 24 new cases (`rmem5-provenance.test.ts`).

**Out of scope (deferred)**: cryptographic signatures (Ed25519)
using the system keychain. The chain is best-effort: anyone
with sqlite access can rewrite both the chain and the content.
A signed-audit-log mode is a clean follow-up.

---

## 3. F8 Cognition — Tulving-style memory types (R-MEM-5.3)

**Problem**: every memory entry is treated as the same kind of
thing. The agent has no way to ask "what *do I know* (semantic)?"
vs "what *happened* (episodic)?" vs "what *do I know how to do*
(procedural)?" — even though each retrieval wants a different
weighting (recency, generality, reliability).

**Solution**: a third new column on `vec_index`:
- `memory_type TEXT NOT NULL DEFAULT 'episodic'`

Three Tulving-style values:
- `episodic` — time-stamped event (project changes, session
  facts). Default for legacy rows.
- `semantic` — stable fact or rule (image descriptions with their
  captions, global rules). General knowledge.
- `procedural` — how-to / skill. The `skills` table rows map here.

The standard write paths auto-classify:
- `appendProjectChange` → `episodic`
- `appendSessionFact` → `episodic`
- `upsertSkill` → `procedural`
- `appendImage` → `semantic`
- global warm-up → `semantic`

`VectorStore.search` takes an optional `memoryType` filter. A
new convenience RPC `memory/findByType(scope, memoryType, opts)`
is a thin wrapper around `memory/find` for callers that want to
make the type explicit.

**Schema**: v11 migration adds 1 column + 1 index.

**RPC**: `memory/findByType` (params: `query`, `memoryType`,
`scope?`, `topK?`, `threshold?`, `sessionId?`).

**Tests**: 14 new cases (`rmem5-cognition.test.ts`).

**Out of scope (deferred)**: type-specific ranking. Right now
`memory/findByType` uses the same cosine-then-sort ranking as
`memory/find` — a procedural query might benefit from boosting
high-`success_count` skills, and an episodic query might want
recency weighting. Both are clean follow-ups (one helper
function each, no schema change).

---

## 4. Test + build numbers

| Phase | Module | Suites | Tests | Delta |
|---|---|---:|---:|---:|
| R-MEM-1/2/3/4 (was) | aethercode-memory | 103 | 350 | — |
| R-MEM-5.1 (F4) | aethercode-memory | 109 | 370 | +20 |
| R-MEM-5.2 (F7) | aethercode-memory | 116 | 394 | +24 |
| R-MEM-5.3 (F8) | aethercode-memory | 121 | 408 | +14 |
| R-MEM-5 combined | aethercode-memory | 121 | 408 | +58 (+17%) |
| aethercode-desktop | (unchanged) | 278 | 1054 | 0 regression |

Java daemon: `mvn install -DskipTests` — 27 modules BUILD SUCCESS.
Canonical jar (aethercode-cli-0.1.0-SNAPSHOT.jar) is 55,646,520 B,
SHA256 = `3BB6FAFD4611CACD573F3FD4B9C8AFBD542AC5BEAACFE87C923EA415F1A084FB`.

Daemon changed by 25 B vs R-MEM-1/2/3/4 (just a manifest timestamp
tweak — the Java daemon side is unchanged in this round, all work
is TS).

---

## 5. Release artifacts

(R-MEM-5 combined release — version `0.2.55`)

| File | SHA256 | Size |
|---|---|---:|
| `aethercode-0.2.55.jar` (dist) | `3BB6FAFD4611CACD573F3FD4B9C8AFBD542AC5BEAACFE87C923EA415F1A084FB` | 55,646,520 B |
| `desktop/aethercode-desktop.exe` | `F038575AD60F58E5E5B8866F0DF28C890E43B2A3590B11F0D8A759BFC8456283` | 3,982,336 B |
| `desktop/resources/aethercode.jar` | `3BB6FAFD4611CACD573F3FD4B9C8AFBD542AC5BEAACFE87C923EA415F1A084FB` | 55,646,520 B |
| `aethercode-0.2.55.zip` | `4BE84CD80D7FEDDB251F7876114692A45E7FA680C5DAB525F6657313B58E1304` | 144,237,598 B |

Release dir: `release/aethercode-0.2.55/` (10 files, 217,469,183 B uncompressed).

Plus the tauri bundle installers (kept in `src-tauri/target/release/bundle/`):
- `bundle/msi/AetherCode_0.2.55_x64_en-US.msi`
- `bundle/nsis/AetherCode_0.2.55_x64-setup.exe`

Build wall time: mvn 1m17s, tauri ~12 min (rust re-link only — no full recompile, all work was TS).

---

## 6. Why these three (and what we deferred)

The paper has 8 frontiers; R-MEM-1/2/3/4 closed F1, F2, F5. This
round closes F4, F7, F8. The only remaining frontier is F3 (RL-
tuned memory) and the broad cross-cutting question of *parametric*
or *latent* memory. Both are research-grade problems and are
explicitly out of scope per the user's "R-MEM-5 = pick a frontier
big bet" mandate.

The 4 follow-ups left for R-MEM-6+ are:
1. **F4+** — plug a real CLIP/vision encoder into
   `EmbeddingProvider` so the image embedding isn't a
   hash-fingerprint + description text.
2. **F7+** — Ed25519 signed audit log mode (use the system
   keychain so a sqlite-only attacker can't forge entries).
3. **F8+** — type-specific ranking. `procedural` queries
   boost by `success_count`; `episodic` queries weight by
   recency. Both are tiny helpers in `findSimilarImpl`.
4. **F3** — RL-tuned memory: reward signal = how often a
   retrieved memory was actually used by the agent. Heavy
   lift, multiple rounds.

---

## 7. Key lessons (R-MEM-5)

1. **Same `vec_index` table, more columns**. The R-MEM-1
   design predicted future columns on `vec_index` and the
   "R-MEM-X = ADD COLUMN" pattern holds. v9, v10, v11 all
   chain cleanly.

2. **Auto-classify on every write path**. Asking callers to
   pick the memory type on every write would have been a
   foot-gun. Instead, the standard write methods
   (`appendProjectChange`, `appendSessionFact`, `upsertSkill`,
   `appendImage`) each tag their own row. Callers only need
   to know the type for retrieval, not creation.

3. **Provenance chain is "best-effort", not "tamper-proof"**.
   The chain is the *content* hashing against itself; an
   attacker with sqlite access can rewrite both. But it
   catches the common case of "I ran a script that edited
   some rows and now I have no idea what changed".

4. **Pure-TS work needs no daemon-side change**. The R-MEM-5
   jar is +25 B vs R-MEM-1/2/3/4 (just timestamp). All new
   RPCs are routed through the existing JSON-RPC surface —
   no new Java method registrations, no protocol changes.

5. **`promote-jar.py` is STILL broken** (R229 lesson, R-MEM-1
   lesson, now R-MEM-5 lesson — third time). The
   `promote-rmem-jar.py` workaround keeps catching it. The
   real fix needs to land in R230.

6. **SQLite template literal gotcha** (R-MEM-5 new lesson).
   `'f'.repeat(64)` is a JS string op, not SQL. When using
   template literals to build SQL, bind dynamic values as
   `?` params — never as JS expressions inside the literal.

---

## 8. Cross-references

- Paper PDF: `reference/papers/2512.13564v2-memory-in-the-age-of-ai-agents.pdf`
- Paper Chinese: `reference/papers/2512.13564v2_中文翻译.md`
- System understanding: `doc/项目文档/AetherCode-Memory-System-Understanding.md`
- Optimization design: `doc/项目文档/AetherCode-Memory-Optimization-Design.md`
- R-MEM-1/2/3/4 combined report: `aethercode-desktop/docs/R-MEM-MEMORY-OPTIMIZATION-2026-09-08.md`
- R229 daemon TODO update: `aethercode-desktop/docs/R229-DAEMON-TODO-UPDATE-2026-09-07.md`

---

## 9. R-MEM-5 release build steps (one-shot script)

```powershell
# 1. build memory module TS
cd aethercode-memory
npm run build

# 2. build Java daemon (small manifest change)
cd ../aethercode
mvn -B install -DskipTests

# 3. promote jar (one-shot, fixes promote-jar.py bug)
cd ..
python D:/Users/maijun/AppData/Local/Temp/promote-rmem-jar.py

# 4. build desktop
cd aethercode-desktop
npx tauri build   # ~11 min on this machine (rust re-link)

# 5. stage release artifacts
python ../scripts/promote-rmem5-release.py
python ../scripts/zip-rmem5.py
```

Total wall time: ~30 min (15 min mvn + 11 min tauri + 4 min scripts).
