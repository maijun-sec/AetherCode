# R-eval-2 Memory Capability (2026-09-12)

## 触发

R-eval master plan 第二个 round。Survey on Evaluation of LLM-based
Agents (2503.16416) §2.4 + arXiv:2512.13564 "Memory in the Age of AI
Agents" 提到 3 类 memory + 跨 session 持久化 + 遗忘机制。

AetherCode `aethercode-memory` module 已有 ExperienceRecord /
ExperienceStore / MemoryScope (USER/PROJECT/SESSION/LOCAL) /
ForgettingPolicy / FileBackedMemory 等完整 memory 子系统. R-eval-2
写一个 self-contained MemoryEntry / MemoryStore / ForgettingPolicy
model 测 3 类 memory + 跨 session 持久化 + 遗忘 + 压缩.

## 实际产出 (1 test class, 23 tests, 1 round)

- `aethercode-evals/src/test/java/org/aethercode/evals/capability/memory/MemoryCapabilityTest.java`
  (28 KB), 23 tests:
  - **MemoryEntry model** (3 tests): validation / utility clamp / token
    accounting
  - **Episodic memory** (2 tests): past event record / cross-session
    context rebuild
  - **Semantic memory** (2 tests): project fact store / highest-utility
    recall
  - **Procedural memory** (2 tests): how-to store / withUse() bumps
    utility
  - **Cross-session persistence** (2 tests): project facts survive
    session boundary / index consistency after remove
  - **Forgetting policy** (4 tests): fresh high-utility high score /
    stale low-utility low score / decay pass removes stale / weights
    normalize
  - **Compaction** (3 tests): drops lowest-utility first / drops
    episodic before semantic / noop when under budget
  - **Recall** (2 tests): by tag / by kind+tag
  - **Token accounting** (2 tests): per-entry / total
  - **End-to-end lifecycle** (1 test): full memory across sessions
- **aethercode-evals: 442/442 Java tests pass** (419 R-eval-1 + 23 new
  R-eval-2), 0 回归

## 关键技术决定 (8 条)

1. **3 类 memory 跟 paper 对齐** - EPISODIC / SEMANTIC / PROCEDURAL 跟
   2512.13564 §3.1.1 一致
2. **MemoryEntry 12 字段跟 ExperienceRecord 1:1** - id / kind / title /
   body / createdAt / updatedAt / sessionId / sourceQuery /
   sourceOutcome / utility / uses / tags
3. **Utility 0..1 强制 clamp** - 用户给 5.0 也只到 1.0, 给负数到 0
4. **Forget policy 用 recency * frequency * utility 3 维** - 跟 2512.13564
   §5.2.3 描述一致
5. **frequency 字段用 entry.uses() 不估算** - 不用 updatedAt-createdAt
   delta 估算, 直接用 record 字段, 更准
6. **Compaction dropOrder 优先 EPISODIC, 然后 PROCEDURAL, 然后 SEMANTIC** -
   semantic 长期价值最高, 不轻易 drop
7. **Validation: body null 抛 NPE** - 跟 AetherCode ExperienceRecord
   一样 requireNonNull
8. **withUse() 用 asymptotic formula** - u' = u + (1-u)*0.1, 跟
   ExperienceRecord 一样

## Memory 5 维度 + 23 tests 映射

| 维度 (paper) | Tests | 论文 |
|---|---|---|
| Episodic memory | 2 | 2512.13564 §3.1.1 |
| Semantic memory | 2 | 2512.13564 §3.1.1 |
| Procedural memory | 2 | 2512.13564 §3.1.1 |
| Cross-session | 2 | 2503.16416 §2.4 |
| Forgetting/decay | 4 | 2503.16416 §2.4 / 2512.13564 §5.2.3 |
| Compaction | 3 | 2503.16416 §2.4 (token-budgeted) |
| Recall | 2 | 2503.16416 §2.4 (semantic similarity) |
| Token accounting | 2 | 2503.16416 §2.4 (memory budget) |
| Validation | 3 | (sanity) |

## 累计测试 (R-eval-2 后)

- aethercode-orchestration: 0
- aethercode-evals: **442/442** (419 R-eval-1 + 23 R-eval-2)
- 其他不变
- 0 回归

## 教训 (新增 8 条, 累计 251+)

244. **3 类 memory 对齐 paper** - EPISODIC/SEMANTIC/PROCEDURAL 跟
     2512.13564 §3.1.1 一致
245. **Utility 强制 clamp 0..1** - 防止外部传错值
246. **frequency 字段用 uses() 直接取** - 不用时间差估算, 更准
247. **Compaction dropOrder 优先 EPISODIC** - semantic 长期价值高
248. **withUse() asymptotic u' = u + (1-u)*0.1** - 跟 ExperienceRecord
     一致
249. **Recency * frequency * utility 3 维** - 2512.13564 §5.2.3 描述
250. **Validation requireNonNull** - body / kind 必须非 null
251. **Token 4 chars/token heuristic** - 跟 R-radar-4/5 TokenCounter 一致

## 后续

- R-eval-3: Tool Use & Function Calling (aethercode-tools)
- R-eval-4: Self-Reflection & Self-Correction
- R-eval-5: State Tracking & Causal Reasoning
- R-eval-6: AetherCode JSON-RPC Interface
- R-eval-7: A2A Multi-Agent
- R-eval-8: Cost-Efficiency, Safety & Robustness
