# R-paper-batch7-translation-2026-09-13

## 目标

把剩下 36 篇 paper 中的 8 篇翻成信达雅中文全文，继续向 46/46 翻译覆盖推进。

## 实际产出 (8 file, 1 round)

### 翻译 (8 篇, 116 KB 中文)
| Paper ID | 主题 | 翻译大小 | API 耗时 |
|---|---|---:|---:|
| 2410.07869-worFBench | Eval & Benchmark (workflow gen) | 14 KB | 99s |
| 2503.03459-umm | Cognitive Architecture (unified mind model) | 12 KB | 37s |
| 2505.02279-agent-interop | Protocol & Interop (survey) | 10 KB | 43s |
| 2505.20670-mirror | Tool Use & Reflection | 15 KB | 43s |
| 2506.01804-mcp-a2a | Protocol & Interop (MCP↔A2A) | 18 KB | 31s |
| 2506.04625-tool-mvr | Tool Use & Reflection (multi-view) | 14 KB | 56s |
| 2510.26352-geometry-dialogue | Multi-Agent (team composition) | 14 KB | 126s |
| 2601.07577-tdp | Planning (task-decoupled) | 13 KB | 54s |
| **Total** | | **110 KB** | **8 min** |

### 翻译模板 (`D:\Users\maijun\AppData\Local\Temp\translate_paper.py`)
- 8 章节固定结构: 标题/摘要/背景/方法/实验/局限/工程解读/译者后记
- 4000-6000 字目标
- prompt 中明确"信达雅"原则: 数字/公式/人名 100% 准确保留
- strip `<think>...</think>` 块 (reasoning model 噪音)
- 跳过 size > 8KB 已翻译文件 (增量)
- 3 retry + 5s 间隔 (网络抖动)

## 关键发现

1. **MiniMax-M3 翻译质量优** — 8 章节完整, 人名/公式/数字全保留, 学术腔不机械
2. **think 块 50-65% 输出占比** — 22KB raw 经常只有 7-12KB 实际译文, 但译文质量 OK
3. **平均 1 min/篇** — 8 篇 ~8 分钟跑完
4. **累积: 10 + 8 = 18/46 翻译** (39%)

## 兼容实现候选 (后续 round)

8 篇翻译 paper 中 7 篇没有对应 AetherCode 实现 (TDP 已有 TaskDecoupledPlanner):
- 2410.07869 WorFBench → 可加 `WorkflowEval` (subsequence/subgraph match)
- 2503.03459 UMM → 抽象, 暂略
- 2505.02279 Interop Survey → 综述, 暂略
- 2505.20670 Mirror → 可加 `MirrorReflector` (self-reflection loop)
- 2506.01804 MCP-A2A → 可加 `McpA2aBridge`
- 2506.04625 Tool-MVR → 可加 `ToolMVRSelector` (multi-view retrieval)
- 2510.26352 Geometry → 可加 `TeamGeometryComposer`

7 个兼容实现 + 7 个 test 列入 R-paper-batch7-impl round。

## R-PAPER-EVIDENCE-INDEX 编码问题 (新发现)

- 文件 `doc/R-PAPER-EVIDENCE-INDEX.md` 全文 mojibake, 历史 PowerShell 写入问题
- git HEAD 内容是 BOM + UTF-8 + CRLF, 但 PowerShell 编辑时 GBK 误转
- 解码尝试 GBK/GB18030/CP936/Latin1 全部 fail
- 解决: git checkout 恢复 HEAD, **不要用 PowerShell Edit/Set-Content 写这个文件**
- 后续 round: 用 Python 脚本整个重写 (UTF-8 clean + 46 paper 全部索引)

## 教训 (新增 5 条, 累计 403+)

399. **MiniMax-M3 翻译质量优** — 8 章节结构 + 公式/数字保留 ✓
400. **think 块 50-65% 输出** — strip 后实际译文更精炼
401. **Python 脚本是避免 PowerShell GBK 踩坑的唯二方法** (另一个是 Read/Write tool)
402. **`doc/R-PAPER-EVIDENCE-INDEX.md` 全文 mojibake** — 历史 PowerShell 写入坑
403. **R-PAPER-EVIDENCE-INDEX 未来要重写** — UTF-8 clean, 46 paper 表格

## 累计统计 (本 round 后)

- aethercode-evals: 646/646
- **18/46 论文全文翻译** (10 + 8 = 39%)
- 0 回归

## 文件清单

- `reference/papers/2410.07869-worFBench_全文翻译.md` (新, 14 KB)
- `reference/papers/2503.03459-umm-unified-mind-model_全文翻译.md` (新, 12 KB)
- `reference/papers/2505.02279-agent-interop-protocols-survey_全文翻译.md` (新, 10 KB)
- `reference/papers/2505.20670-mirror-reflection_全文翻译.md` (新, 15 KB)
- `reference/papers/2506.01804-mcp-a2a-framework_全文翻译.md` (新, 18 KB)
- `reference/papers/2506.04625-tool-mvr_全文翻译.md` (新, 14 KB)
- `reference/papers/2510.26352-geometry-of-dialogue-team-composition_全文翻译.md` (新, 14 KB)
- `reference/papers/2601.07577-tdp-task-decoupled-planning_全文翻译.md` (新, 13 KB)
- `D:\Users\maijun\AppData\Local\Temp\translate_paper.py` (翻译脚本, 5.6 KB)
- `doc/round-notes/R-paper-batch7-translation.md` (本文件)
