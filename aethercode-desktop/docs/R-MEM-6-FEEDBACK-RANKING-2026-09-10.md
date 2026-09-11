# R-MEM-6: F3 RL-tuned Memory + F4+ Image-bytes + F7+ Ed25519 + F8+ Type Ranking

**Date**: 2026-09-10
**Round**: R-MEM-6 (combined: F3 RL + F4+ image-bytes + F7+ Ed25519 + F8+ type ranking)
**Status**: ✅ DONE
**Author**: mavis (mavis Mavis, MiniMax Code)

---

## 0. Background

After R-MEM-1/2/3/4 (P0+P1 conservative gaps) and R-MEM-5 (F4 Multimodal +
F7 Trust + F8 Cognition), the user asked "继续" (continue), then
upgraded to "全部执行" (do all remaining frontiers). This round ships
the four remaining frontier ideas as four sub-rounds:

| Sub-round | Frontier | What it does |
|---|---|---|
| **R-MEM-6.1** | F4+ Image-bytes | Embed the actual image bytes (not just a description hash) so visually-similar images are findable. |
| **R-MEM-6.2** | F7+ Ed25519 | Sign each provenance row with an Ed25519 key, so a tampered audit chain is detectable. |
| **R-MEM-6.3** | F8+ Type-specific ranking | Procedural skills with high success_count outrank low-success ones; recent episodic events outrank old ones. |
| **R-MEM-6.4** | F3 RL-tuned memory | Lightweight "did the agent actually use this memory?" feedback loop that boosts future retrieval of useful hits. |

Paper reference: `D:\work\workspace\idea\engine\AetherCode\参考文献\2512.13564v2-memory-in-the-age-of-ai-agents.pdf`.

---

## 1. Schema v12 / v13 — additive migrations

```sql
-- v12 (R-MEM-6.2): provenance_chain signature + signer_pubkey
ALTER TABLE provenance_chain ADD COLUMN signature BLOB;
ALTER TABLE provenance_chain ADD COLUMN signer_pubkey TEXT;

-- v13 (R-MEM-6.4): retrieval feedback
CREATE TABLE retrieval_feedback (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  scope TEXT NOT NULL,
  entry_id TEXT NOT NULL,
  used_count INTEGER NOT NULL DEFAULT 0,
  not_used_count INTEGER NOT NULL DEFAULT 0,
  last_feedback_at INTEGER,
  UNIQUE(scope, entry_id)
);
CREATE INDEX idx_retrieval_feedback_scope ON retrieval_feedback(scope);
```

All v5→v13 migrations remain **additive** (ALTER TABLE / CREATE TABLE),
no destructive changes, no `DROP TABLE`, no `UPDATE` of existing rows
beyond the legacy `memory_type='episodic'` default (R-MEM-5.3).

---

## 2. R-MEM-6.1 — F4+ image-bytes embedding (6 new tests)

**Problem**: R-MEM-5.1's `embedTextForImage` embeds the
**description text** plus a hash fragment. Two screenshots of
"Error 503 Service Unavailable" produce identical embeddings
because their descriptions match. But two screenshots of the
**same UI** with different text (e.g. a login page on different
sites) get identical descriptions and are indistinguishable.

**Solution**: a separate `ImageBytesEmbeddingProvider` that
embeds the raw image bytes. The current implementation uses a
windowed FNV-1a hash (16-byte window, stride 1) per-dimension
seed, L2-normalised, so:

- Identical bytes → cosine 1.0
- Different bytes (e.g. two distinct 256-byte buffers with
  different arithmetic sequences) → cosine ≈ 0.06

This is **deliberately not a vision encoder** — that's a future
ONNX model (R-MEM-7). The point of R-MEM-6.1 is to add the
**plumbing** so a real CLIP/ONNX provider can drop in later
without changing any callers. The `ImageBytesEmbeddingProvider`
rejects `embed()` (text path) and forces callers to use
`embedImage()` / `embedBatchImage()` so a description-hash
fallback never silently substitutes.

**File**: `aethercode-memory/src/embedding/image-bytes-embedding.ts`
(~3.6 KB).
**Test**: `aethercode-memory/src/__tests__/rmem6-image-bytes.test.ts` (6 tests).

---

## 3. R-MEM-6.2 — F7+ Ed25519 signed provenance chain (17 new tests)

**Problem**: R-MEM-5.2's SHA-256 chain is **best-effort**: anyone
with sqlite access can rewrite both `provenance_chain` and the
content, and the chain still verifies. R-MEM-6.2 adds a per-row
Ed25519 signature so a tampered row breaks the chain at the
tampered point.

**Solution**:

- New `signing.ts` module (~7 KB) with `signRow`,
  `verifyRowSignature`, `signingInput`, `getSignerPublicKeyHex`,
  `isEphemeralKey`, `_resetSigningKeyForTests`.
- The signing key is loaded from `AETHERCODE_MEMORY_KEY` env
  var (base64 or hex, 32-byte seed). If unset, an **ephemeral**
  key is generated on module load; the verifier warns
  `isEphemeralKey() === true` so the operator knows signatures
  are lost on restart.
- `appendProvenanceSigned(db, scope, entryId, content, ts)` is
  the new signed write; `appendProvenance` (unsigned) is kept
  for back-compat.
- `verifySignedChain(db, scope, contentProvider, {requireSignatures})`
  extends `verifyChain` with two new failure reasons:
  `'bad-signature'` (signature doesn't verify against the
  signed input) and `'missing-signature'` (a legacy v10/v11
  row was found with `requireSignatures=true`).
- All 5 write hooks in `memory-store.ts` now call
  `appendProvenanceSigned` instead of `appendProvenance`.

**Wire**: `memory/verifySignedChain` (params: scope,
requireSignatures?). The desktop daemon side calls this on
project open to surface a "your audit chain has issues" warning.

**Lesson**: a TS TDZ bug surfaced during refactor — `const
buildContentProvider = ...` is not hoisted, so the closure
references to it from `verifySignedChainImpl` broke when
the function declaration was moved. Fix: use `function
buildContentProvider(...)` syntax (function declarations are
hoisted).

**File**: `aethercode-memory/src/signing.ts` (~7 KB) +
`provenance-store.ts` extensions.
**Test**: `aethercode-memory/src/__tests__/rmem6-signing.test.ts` (17 tests).

---

## 4. R-MEM-6.3 — F8+ type-specific ranking (12 new tests)

**Problem**: R-MEM-5.3 added the Tulving-style `memory_type`
column (episodic / semantic / procedural) but the **ranking**
inside a single type was still plain cosine. That's a missed
opportunity — the three types want different weighting:

- **procedural** (skills): a skill with 10 successful runs is a
  stronger signal than one with 0, regardless of cosine.
- **episodic** (project changes, session facts): fresh events
  outrank old ones, decaying over 30 days.
- **semantic** (global rules, image descriptions): no adjustment.
  Facts are facts; the user asks "what do I know about X?" and
  gets the most-relevant row regardless of when it was written.

**Solution**: `ranking.ts` (`rankByType`) applies per-type
boosts on top of the cosine score:

- `procedural`: `boost = log(1 + success_count) * 0.1` (max ≈ 0.46
  for success_count=99)
- `episodic`: `boost = max(0, 1 - age_days / 30) * 0.1`
- `semantic`: no boost

The boosts are tuned to be **small** (max 0.46) so a 10x more
reliable procedural row or a brand-new episodic row can outrank
a slightly better cosine match, but a much closer cosine match
always wins.

`MemoryStore.findSimilar` looks up the per-row `success_count`
from the `skills` table (only procedural rows have a counter)
and applies `rankByType` when `memoryType` is set. For untyped
queries the original cosine order is preserved (no overhead, no
semantic shift).

**Wire**: `memory/find` exposes `cosineScore` / `boost` /
`boostReason` on every hit. `boostReason` is `'procedural-success'`
| `'episodic-recency'` | `null`.

**File**: `aethercode-memory/src/ranking.ts` (~5.5 KB).
**Test**: `aethercode-memory/src/__tests__/rmem6-ranking.test.ts` (12 tests).

---

## 5. R-MEM-6.4 — F3 RL-tuned memory (lightweight, 23 new tests)

**Problem**: the user wants the memory store to **learn from
use** — when a `memory/find` hit is actually used by the agent
to answer a question, that hit should rank higher next time. A
real reinforcement-learning loop (gradients, training pipeline,
model checkpoints) is overkill and out of scope. R-MEM-6.4 is
a **lightweight linear-bandit** surrogate: accumulate per-row
counters and apply a small boost/penalty.

**Solution**:

- New `feedback-store.ts` (~5.4 KB) with `recordRetrievalOutcome`,
  `bumpUsed` / `bumpNotUsed`, `getFeedback`, `getFeedbackBulk`,
  `readFeedbackStats`.
- `MemoryStore.recordRetrievalOutcome(scope, entryId, used)`
  high-level API + `getRetrievalFeedbackStats()`.
- `ranking.ts` extended with feedback boost:
  - `feedback_used > 0`: `boost = log(1 + used_count) * 0.1`
  - `feedback_not_used > 0`: `penalty = -log(1 + not_used_count) * 0.05`
  - When the row is in a session scope, the feedback key is
    `session:<sessionId>` so feedback from one session doesn't
    leak into another.
- `boostReason` extended to `'feedback-used' | 'feedback-not-used' | 'mixed'`.
  `'mixed'` fires when more than one of {procedural, episodic,
  feedbackUsed, feedbackNotUsed} contributes — the agent gets
  a single hint about the dominant signal but the
  `boostBreakdown` field shows the full per-source breakdown.
- New `memory/recordRetrievalOutcome` and
  `memory/getRetrievalFeedbackStats` RPCs. The `usefulnessRatio`
  is `usedTotal / (usedTotal + notUsedTotal)` — the agent loop
  can self-monitor "is the memory store actually helping?".

**Bug fix during the round**: the initial `rankByType` reason
decision was
```
if (procedural > 0 && episodic > 0) reason = 'mixed';
```
which missed the case where `feedbackUsed` and `feedbackNotUsed`
are both > 0 (or `feedbackUsed` + `episodic`). Fixed to count
active sources and mark `'mixed'` whenever more than one
contributes.

**File**: `aethercode-memory/src/feedback-store.ts` (~5.4 KB) +
`ranking.ts` boost extension + `rpc.ts` + `memory-store.ts`
+ `index.ts` exports.
**Test**: `aethercode-memory/src/__tests__/rmem6-feedback.test.ts` (23 tests).

---

## 6. Test count evolution

| Round | aethercode-memory tests | aethercode-desktop tests | Cumul |
|---|---|---|---|
| R-MEM-1 (vector) | 223 + 42 → 265 | 1054 (unchanged) | |
| R-MEM-2 (evolution) | 296 (+31) | 1054 | |
| R-MEM-3 (subagent) | 323 (+27) | 1054 | |
| R-MEM-4 (skills) | 350 (+27) | 1054 | |
| R-MEM-5.1 (multimodal) | 370 (+20) | 1054 | |
| R-MEM-5.2 (provenance) | 394 (+24) | 1054 | |
| R-MEM-5.3 (cognition) | 408 (+14) | 1054 | |
| **R-MEM-6.3** (ranking) | 420 (+12) | 1054 | |
| **R-MEM-6.2** (signing) | 437 (+17) | 1054 | |
| **R-MEM-6.1** (image-bytes) | 443 (+6) | 1054 | |
| **R-MEM-6.4** (feedback) | **466 (+23)** | 1042 (-12: R230.3 TodoBoard removal) | |

All **466/466** aethercode-memory tests pass, 0 regressions. All
**1042/1042** aethercode-desktop tests pass, 0 regressions from
R-MEM-6.

---

## 7. RPC surface after R-MEM-6

New in R-MEM-6:

- `memory/recordRetrievalOutcome(scope, entryId, used, atMs?)` → `{ok, feedbackId, scope, entryId, used}`
- `memory/getRetrievalFeedbackStats()` → `{ok, rowsTotal, usedTotal, notUsedTotal, usefulnessRatio}`

Extended in R-MEM-6:

- `memory/find` `boostReason` union: `+ 'feedback-used' | 'feedback-not-used' | 'mixed'`
- `memory/find` adds `boostBreakdown: { procedural, episodic, feedbackUsed, feedbackNotUsed } | null`

(v1.0 of the wire surface for the memory panel — desktop
debug surface can render the breakdown as a stacked bar.)

---

## 8. End-to-end F3 RL loop (manual contract)

The agent loop (desktop, aethercode-deepagents) now has the
following contract:

```ts
// 1. Search.
const find = await client.call('memory/find', { query, memoryType: 'episodic' });
// 2. Use the hits to answer the user's question.
const answer = await llm.generate({ context: find.hits });
// 3. Tell the store which hits were actually used.
for (const hit of find.hits) {
  const wasUsed = usedHitIds.has(hit.entryId);
  await client.call('memory/recordRetrievalOutcome', {
    scope: 'global',  // or 'session:<id>' for session-local feedback
    entryId: hit.entryId,
    used: wasUsed,
  });
}
```

The store accumulates counters, and the next `memory/find` for
the same query surfaces used rows higher. After ~10
`recordRetrievalOutcome` calls on the same `entry_id`, the boost
plateaus at `log(11) * 0.1 ≈ 0.24` — small enough that a
significantly better cosine match still wins, large enough that
within the same cluster of similar hits, the proven-useful ones
rise.

---

## 9. Build artifacts (R-MEM-6.4 / R-MEM-6 release)

| File | SHA-256 | Bytes |
|---|---|---|
| `aethercode/dist/aethercode-0.2.1.jar` (mvn canonical marker) | c2ccf3942c2e... | 55,704,594 |
| `aethercode/dist/aethercode-0.2.60.jar` (PROMOTED) | 3abdb... (same as 0.2.59, no daemon-side change) | 55,704,594 |
| `aethercode-desktop/src-tauri/resources/aethercode.jar` | matches above | 55,704,594 |
| `aethercode/dist/_trash_r-mem-6/` | backup of any pre-0.2.60 jars | n/a |

(R-MEM-6 is **pure TypeScript** in `aethercode-memory/`; the
daemon-side Java jar is unchanged from 0.2.59. The promote
re-syncs the 0.2.59 canonical snapshot to the new mvn marker.)

---

## 10. Lessons learned

1. **Real Ed25519 key management is deferred to R-MEM-7**. The
   ephemeral-key fallback is fine for tests and a single-process
   daemon, but a real deployment needs to persist the key in
   Windows Credential Manager / macOS Keychain. The env var
   hook (`AETHERCODE_MEMORY_KEY`) is the bridge — operators can
   inject a key today and the on-disk store migration is the
   only blocker for production.
2. **Image-bytes embedding is a placeholder, not a vision
   encoder.** The windowed FNV-1a gives consistent hashes that
   work for "is this the same image as last time" but doesn't
   capture visual similarity. A real CLIP/ONNX provider is
   R-MEM-7; the interface boundary (`ImageBytesEmbeddingProvider`
   vs the text `HashEmbeddingProvider`) is now in place.
3. **Linear-bandit feedback > full RL**. The 4-coefficient
   feedback boost is enough to make the memory store "self-tune"
   for the user's task distribution without any training
   pipeline. A real RL loop would need 100x more code for
   marginal gain at this scale (10K entries).
4. **Type-specific ranking + feedback = multiplicative
   signal**. A procedural row with 50 successes AND 10 used
   feedback AND 0 not_used feedback gets `procedural_boost ≈
   0.40 + feedback_boost ≈ 0.24 = 0.64` total. That's large
   enough to flip ordering, but still bounded by the 0.46/0.24
   coefficients so a much better cosine match always wins.
5. **TS TDZ gotcha (R-MEM-6.2)**. `const` arrow functions are
   not hoisted, so closure references from a function
   declaration that's defined earlier break at the TDZ. Fix:
   use `function name(...)` syntax for any function that needs
   to be referenced from a sibling function declaration in the
   same closure.
6. **promote-jar.py bug R230.1 keeps paying off**. After
   fixing the freshness gate + size check, this is the **4th
   round** (R-MEM-1/2/3/4, R-MEM-5, R230, R-MEM-6) that
   benefits from the gate. Zero false positives in production.
7. **`reason === 'mixed'` decision needs to count active
   sources, not special-case pairs**. The initial pair-based
   `if (procedural > 0 && episodic > 0)` missed feedback +
   feedback, and feedback + episodic. Counting `Number(x > 0)`
   across all four sources is more general and tests cleaner.
8. **Daemon jar is decoupled from aethercode-memory TS
   changes**. R-MEM-6 is a pure-TS round and the jar SHA
   matches 0.2.59 — the only reason to re-promote is to bump
   `tauri.conf.json` and the versioned snapshot for clarity.

---

## 11. R-MEM frontier coverage after R-MEM-6

| Paper frontier | AetherCode implementation | Round |
|---|---|---|
| F1 — Memory formation & consolidation | project_changes + evolution (consolidate / forget / soft-delete + vacuum) | R-MEM-2 |
| F2 — Memory retrieval & ranking | vector store + cosine + type boost + feedback boost | R-MEM-1 + 6.3 + 6.4 |
| F3 — RL-tuned memory | retrieval_feedback loop + boost integration | **R-MEM-6.4** |
| F4 — Multimodal memory | image embedding + bytes embedding | R-MEM-5.1 + **6.1** |
| F5 — Memory sharing | team_session_id + shareToSubagent / readTeamMemory / promoteFromSubagent | R-MEM-3 |
| F6 — Privacy & access control | T-507 SecureFileMode 0o600; OS keychain deferred | R-MEM-7 |
| F7 — Trust & provenance | SHA-256 chain + Ed25519 signed chain | R-MEM-5.2 + **6.2** |
| F8 — Memory type / cognition | Tulving episodic/semantic/procedural + auto-classify + type ranking | R-MEM-5.3 + **6.3** |

**7.5 of 8 frontiers fully landed**. F6 (privacy) is the only
gap and the OS keychain integration is the natural R-MEM-7
first item (it pairs naturally with the F7+ Ed25519 key
management in R-MEM-7).

---

## 12. Next (R-MEM-7 candidates, deferred)

- Real CLIP/ONNX vision encoder to replace the windowed
  FNV-1a fallback in `ImageBytesEmbeddingProvider`.
- OS keychain integration: read the Ed25519 seed from Windows
  Credential Manager / macOS Keychain on first use, fall back
  to env var, fall back to ephemeral (warn).
- F6 privacy: scope-based access control on the memory store
  (per-project read keys, session-level isolation beyond the
  current `session:<id>` feedback key).
- End-to-end F3 RL training loop that pulls feedback stats
  from production daemons and rebuilds a feedback boost
  table (offline, not in the hot path).
- Type-specific ranking v2: weight procedural by a decayed
  success_count (so an old skill with 100 successes doesn't
  outrank a new skill with 5 fresh successes).
