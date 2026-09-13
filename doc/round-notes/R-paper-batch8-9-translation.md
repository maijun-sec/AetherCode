# R-paper-batch8-9-translation-2026-09-13

## 目标

把剩下 36 篇 paper 中除 batch 7 (8 篇) 外的 28 篇全部翻成信达雅中文 (目标 36/36, 实际 36/36 = 100% 翻译覆盖)。

## 实际产出 (28 file + 1 重写 index + 1 dup, 1 round)

### 翻译 (28 篇新)
| Batch | 数量 | 主题分布 |
|---|---:|---|
| batch 8 (R-paper-batch8) | 12 | raps / autoagent / rederef / mia / cva / agemem / mas-lfm / LIFE / molem / sr2am / automem / prolong |
| batch 9 (R-paper-batch9) | 16 | lifelong / frameworks-2508 / from-language-to-action / ai-agent-arch / agentic-reasoning-survey / dart / alma / auton / erl / admem / multimodal / forgetting / 2510.25445 / holistic / 2512.12791 / 2512.13564v2 / 2601.11327 |
| **Total new** | **28** | + 1 dup (2512.08296 slug 修正) |

### 重写 R-PAPER-EVIDENCE-INDEX.md (21 KB)
- 旧文件 mojibake (PowerShell 写时 GBK 误转)
- 完全重写为 UTF-8 clean (1124 Chinese chars)
- 10 主题分类, 46 paper 全部索引
- 17 Tier-3 兼容实现 table + 13 节 benchmark table

## 关键发现

1. **46/46 paper 全部中文全文翻译** (100% 覆盖) — 累计 ~380 KB 中文
2. **5 篇 paper file name 命名风格反转** (slug 在前, arxiv-id 在后) — 已逐个修正
3. **MiniMax-M3 翻译速度稳定** — 1-2 min/篇, 总耗时 ~30 min for 28 篇
4. **R-PAPER-EVIDENCE-INDEX.md mojibake 彻底修复** — Python 脚本重写, 后续 round 不再用 PowerShell Edit
5. **很多 "placeholder" paper ID 在 R-PAPER-EVIDENCE-INDEX 里被列出, 但 reference/papers 实际没摘要** — 这些需要单独 round 处理 (e.g. 2501.06322 / 2502.16750 / 2505.07087 / 2508.03341 / 2509.18847 / 2510.10472 / 2510.22898 / 2603.22862 / 2608.04719)

## 兼容实现候选 (后续 round)

28 篇新翻译 paper 中可加兼容实现的 (估算 8-10 个):
- 2410.07869 WorFBench → `WorkflowEval` (subsequence/subgraph match)
- 2505.20670 Mirror → `MirrorReflector` (self-reflection loop)
- 2506.01804 MCP-A2A → `McpA2aBridge` (MCP ↔ A2A)
- 2506.04625 Tool-MVR → `ToolMVRSelector` (multi-view retrieval)
- 2510.26352 Geometry → `TeamGeometryComposer` (Wasserstein)
- 2601.11327 Small vs Large → `ModelSizeRouter` (heuristic small/large)
- 2602.23720 Auton → `SnapBlueprint` (cognitive blueprint)
- 2603.13256 ReDeReF → `ProbabilisticReasoner` (training-free)
- 2603.24639 ERL → 已有 HeuristicExtractor
- 2604.18133 MAS-LFM → `LfmSafetyChecker`
- 2606.06787 AdMem → 已有 ProceduralMemory + MemoryCriticAgent
- 2608.28978 → 已有 SelectiveForgettingPolicy + GraphMemoryStore

候选 8-10 个, 跟之前 R-paper-batch7-impl 候选 7 个合并, 留 R-paper-batch7-impl round 一并做。

## 教训 (新增 6 条, 累计 409+)

404. **Paper ID 命名风格 2 种** — 标准 `{arxiv}-{slug}` 或 反转 `{slug}-{arxiv}`, 翻译脚本需要 fuzzy match
405. **很多 paper 在 INDEX 里但 reference/papers 实际没摘要** — 是 "planned but not yet collected"
406. **MiniMax-M3 翻译稳定 1-2 min/篇** — 28 篇 ~30 min, 适合批量跑
407. **`R-PAPER-EVIDENCE-INDEX.md` 必须 Python 重写** — PowerShell Edit/Set-Content 永远踩 GBK 坑
408. **重写 R-PAPER-EVIDENCE-INDEX 应该一次过全部 46 paper** — 分批 append 容易出现 mojibake 残留
409. **dup file name slug 修正 (Copy-Item)** — git 知道是 rename 不会重复

## 累计统计 (本 round 后)

- aethercode-evals: 646/646
- **46/46 论文中文全文翻译** (100% 覆盖, ~380 KB 中文)
- **17 Tier-3 兼容实现** (193 tests)
- **9 benchmark 端到端** (2841 行)
- 0 回归

## 文件清单

### 新翻译 (28 篇, ~230 KB)
- 2602.08009, 2603.09716, 2603.13256, 2604.04503, 2604.05939, 2604.12179, 2604.18133, 2605.14892, 2605.21951, 2605.22138, 2607.01224, 2607.20064 (12)
- lifelong-learning-llm-agents-2501.07278, agentic-ai-frameworks-architectures-2508.10146, from-language-to-action-llm-agents-2508.17281, ai-agent-systems-architectures-2601.01743, 2601.12538, 2602.00994, 2602.07755, 2602.23720-auton-framework-snapchat, 2603.24639, 2606.06787, multimodal-agentic-frameworks-survey-2608.20379, 2608.28978, agentic-ai-comprehensive-survey-2510.25445, holistic-review-agentic-ai-10.1007, 2512.12791, 2512.13564v2, 2601.11327 (16)

### Index 重写
- `doc/R-PAPER-EVIDENCE-INDEX.md` (重写, 21 KB UTF-8 clean)

### dup file
- `reference/papers/2512.08296-scaling-agent-systems_全文翻译.md` (从 `2512.08296-science-of-scaling-agent-systems_全文翻译.md` 复制)
