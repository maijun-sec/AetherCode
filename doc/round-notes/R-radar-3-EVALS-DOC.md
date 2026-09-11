# R-radar-3: 写 `doc/tech-docs/evals.md` (5 benchmark 详解)

**Round**: R-radar-3  
**Date**: 2026-09-12  
**Module**: `doc/tech-docs/evals.md`  
**Goal**: 给 `aethercode-evals` 模块 (5 benchmark + 7 CLI + harbor 集成) 写一篇 25 KB 的中文技术文档,补完 R-radar-1 留下的"aethercode-evals 完全没暴露"这个洞。

## TL;DR

- 新增 `doc/tech-docs/evals.md` (25 KB, ~600 行)
- 12 章节: 架构 / 5 benchmark 详解 / 7 CLI / 2 parser bug / LangGraph 集成 / 论文对应 / 测试覆盖 / trade-off / round 引用
- 更新 `tech-docs/README.md` 加 cross-link (14 → 15 主题)
- 更新 `ai-agent-validation.md` 跳到 `evals.md` (R-radar-2 完成状态从待补 → ✅)

## 文档结构

| 章节 | 内容 |
|---|---|
| 1. 架构总览 | ASCII 图: 5 benchmark → Cli → ShellRunner → Python runner |
| 2. 5 个核心包 | 文件 + 字节 + 测什么 + source of truth |
| 3. ⭐ 5 benchmark 详解 | CLBench / Radar / Tau3 / DRBench / ContextBench 各 1 节 |
| 4. ⭐ 7 个 CLI 子命令 | run / trials / aggregate / radar / catalog / model-groups / list + 4 exit code + env var + ShellRunner seam |
| 5. ⭐ 已知 2 个 parser bug | R-radar-2 发现的 remainder mode + 嵌套 subcommand options, regression test 锁定 |
| 6. ⭐ LangGraph Agent 集成 | harbor 层的 LanggraphAgent: 3 graph + SHELL_ENV_DENYLIST + harborAssistantId |
| 7. 5 benchmark 对应论文评测多维度 | 论文 2512.13564 / 2601.01743 / 2501.07278 / 10.1007 / 2608.20379 怎么对应 |
| 8. 测试覆盖 (R-radar-2) | 127 tests 跨 5 test class + fixture + pom 改动 + mvn 验证 |
| 9. 与其他模块的关系 | core / tools / memory / compact / permission / a2a / workflows |
| 10. trade-off / 已知限制 | 5 条 |
| 11. 关键 round 引用 | 8 个 R-radar round 关联 |
| 12. 写新 benchmark 的 checklist | 7 步,给后续 R-round 用 |

## 关键决定 (6 条)

1. **doc 不重复 R-radar-1 已写内容** — R-radar-1 已经把 4 层金字塔 + 5 benchmark 高层介绍写进 `ai-agent-validation.md` 了, 本文专注 5 benchmark 各自的实现细节, cross-link 而不重复
2. **data class vs runner 区分清楚** — 5 benchmark 里 Java 端都是 data class + orchestration, 真跑是 Python `uv run pytest`。文档明确说 "5 benchmark 都是 data class, 真正执行是 Python", 避免误导用户去 Java 端找真评测逻辑
3. **2 个 parser bug 单独开章节** — 这两个 bug 是 R-radar-2 发现的, 锁在 regression test, 但没修。文档单独写一章 + 2 个具体测试名, 让后续 round 修复时能直接找到入口
4. **写新 benchmark checklist 提前布局** — R-radar-4/5/6/7/8 都会加新东西, 提前写好 checklist (7 步: port vs mirror / data fixture / CLI 集成 / 测试 / categories.json / round-notes / cross-link), 给后续 round 模板
5. **论文对应表直接说论文 id** — 5 benchmark 对应 5 篇论文, 表格直接列论文 id (2512.13564v2 等), 用户能立刻跳到 `reference/papers/`
6. **测试覆盖 cross-link R-radar-2** — 文档第 8 节直接给 5 个 test class 名字 + 测试数 + fixture 路径, 不再 "见 R-radar-2", 用户能 1 跳到测试代码

## 文档大小 (跟其他 tech-docs 比)

| 文件 | 字节 | 章节数 | 来源 round |
|---|---:|---:|---|
| `architecture.md` | 12 KB | 12 | R130, R141, R250+ |
| `api.md` | 10 KB | 10 | R164, R250+ |
| `memory-system.md` | 18 KB | 14 | R230, R244, R245, R250+ |
| `context-compact.md` | 14 KB | 12 | R83, R136, R140, R250+ |
| `permission-control.md` | 13 KB | 11 | R130, R203, R207, R250+ |
| `workflow-engine.md` | 14 KB | 12 | R241.3, R250+6 |
| `a2a-protocol.md` | 13 KB | 11 | R241.1, R250D, R250+1, R250+3 |
| `mcp-integration.md` | 10 KB | 9 | R132, R250+ |
| `hook-system.md` | 9 KB | 8 | R141, R151, R250+ |
| `ai-agent-validation.md` | 8 KB | 9 | R234, R239, R243, R250+ |
| `skill-system.md` | 6 KB | 6 | R250+ |
| `packaging.md` | 6 KB | 6 | R110, R250+ |
| `providers.md` | 5 KB | 5 | R109-1, R109-3, R250+ |
| `ssd-demo.md` | 6 KB | 6 | R242, R250+ |
| **`evals.md` (本 round)** | **25 KB** | **12** | **R-radar-1/2/3** |

`evals.md` 是 15 篇里最大的, 因为 5 benchmark 各自需要一节详细讲 (CLBench 包含 ContinualLearningSystem 契约 + DeepAgentsSystem 实现 + DeepAgentFactory seam, Radar 包含 8 categories + 8 核心 API + 关键行为, Tau3 包含 30 任务分布, DRBench 包含 6 流程步骤, ContextBench 包含 4 步骤)。

## Cross-link 更新 (3 文件)

### `doc/tech-docs/README.md`
- 加 `evals.md` 行 (15 主题, 14 → 15)
- 改 主题计数 (14 → 15)
- 把 ai-agent-validation.md 的 "做 agent 验证" 段加 evals.md cross-link

### `doc/tech-docs/ai-agent-validation.md`
- 顶部加 evals.md cross-link
- 第 43 行 "R-radar-2 待补测试" → "R-radar-2 ✅ 127 tests, 详见 evals.md"
- 底部 R-radar 路线图: R-radar-1/2/3 状态从 📋 → ✅

### `doc/tech-docs/evals.md` (本 round 新增)
- cross-link 5 benchmark source: clbench.system, evals.Radar, evals.Tau3Subset, harbor_adapters.drbench.DrbenchAdapter, harbor_adapters.contextbench.ContextbenchAdapter, harbor.langgraph_project.LanggraphAgent
- 跳到 R-radar-2 round-notes 验证测试
- 跳到 `reference/papers/2512.13564v2-memory-in-the-age-of-ai-agents.pdf` 论文 (Continual Learning 背景)

## 文档质量检查 (5 条)

1. **关键词保留英文**: Agent / Memory / Compact / Workflow / Skill / MCP / A2A / Hook / Tool / Provider / Bank / benchmark / CLI / Subcommand / dispatch / ShellRunner / LangGraph / Harbor / tier / ContinualLearning / schema / etc. 都保留
2. **章节标题中文 + ⭐ 标关键**: 跟其他 14 篇 tech-docs 风格一致
3. **代码示例全部真的可跑**: 比如 `Main[]{"run", "--model", "X"}` (虽然会触发 bug, 但代码本身是合法的)
4. **trade-off 章节写实际限制**: 5 benchmark 数据源 Python / rendering 在 caller / 2 个 parser bug / DRBench ContextBench 测试留空 / Radar static init 依赖 categories.json
5. **关键 round 引用带日期**: 11 章节给 R-radar round 关联 + 实施日期

## 跨 Round 影响

- **R-radar-4/5**: 写 `google_scholar` tool + 论文搜索走 WebFetchTool, 可以直接用本文档第 7 节 "5 benchmark 对应论文评测多维度" 作为背景
- **R-radar-6**: V 校验器框架, 可以用第 3.1 节 "CLBench 核心接口" 的 `ContinualLearningSystem` 作为起点
- **R-radar-7**: Self-correction 机制, 可以用第 3.1 节 "DeepAgentsSystem 实现" 的 `recordUsageEvent` 作为反馈循环入口
- **R-radar-8**: Multi-Agent 对抗, 可以用第 6 节 "LangGraph Agent 集成" 的 `makeBareGraph` / `createDeepAgent` 作为 sub-agent factory

## 教训 (新增 5 条, 累计 154+)

150. **🆕 cross-link > 复制** — 之前担心内容重复, 后来发现 14 篇 tech-docs 都是 "高层在 ai-agent-validation.md, 细节在 evals.md" 这种分层的。R-radar-3 不重复 R-radar-1 已写内容, cross-link 即可
151. **🆕 文档 size 不应是 metric** — evals.md 25 KB 是 15 篇里最大的, 不是因为凑字数, 是因为 5 benchmark 各自需要 1 节细节。但写完看整体表, 25 KB 确实比第二大的 memory-system.md (18 KB) 大 40%。可以考虑 R-radar 后续把 evals.md 拆成 evals-clbench.md + evals-radar.md + evals-cli.md, 但本 round 不拆
152. **🆕 bug 单独开章节, 写 regression test 名字** — 2 个 parser bug 单独成第 5 章节, 写 4 个具体测试名 (`parserHonorsNegativeNumbersAsRemainder` / `parserStripsLeadingDoubleDashRemainder` / `trialsWithMissingTrialsArgFailsBeforeDispatch` / `runDryRunDoesNotInvokeShellRunner`), 后续修 bug 时直接搜测试名
153. **🆕 写新东西前先写 checklist** — 章节 12 "写新 benchmark 的 checklist" 是给 R-radar-4/5/6/7/8 用的模板, 7 步: port vs mirror / data fixture / CLI 集成 / 测试 / categories.json / round-notes / cross-link
154. **🆕 doc 写完回填主索引 + 兄弟 doc** — 改 README.md (主题索引), 改 ai-agent-validation.md (cross-link + 状态更新), 不能只改新 doc 本身

## 后续 (R-radar-4+)

- **R-radar-4**: 加 `google_scholar` tool — 参考 evals.md 第 3.5 节 "ContextBench" 的 `webSearchTool()` 接口, 复用 SHELL_ENV_DENYLIST / harborAssistantId 模式
- **R-radar-5**: 论文搜索走 WebFetchTool — 走 `aethercode-tools/web` 模块, Brave / Serper / 直接 fetch
- **R-radar-6/7/8**: 大特性, 走 evals.md 第 3.1 节 (CLBench) 起点
- **可能单独 R-round**: 修 Cli parser 2 个 bug (remainder mode + nested sub-options), 跟着 round-notes 这章走
