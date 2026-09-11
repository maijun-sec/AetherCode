# R-radar-1 — AI Agent 验证文档重写

> **目标**: 修 `ai-agent-validation.md` 反映 `aethercode-evals` 真实状态。
>
> **完成日期**: 2026-09-12
> **作者**: Mavis

---

## 1. 背景

R250+ 收口后, 用户问:

> 1. 是否有信息检索的 tool (知乎 / CSDN / 官网)?
> 2. 8 篇论文还有什么没落实? **AI Agent 测评怎么测** Memory / Compact / XX 效果?
> 3. 是否具备 Agent Team / 多 Agent 对抗?
> 4. 论文搜索 (谷歌学术)?

调研后发现 **重大发现**: `aethercode-evals/` 模块 (从 Python `deepagents_evals` 完整 port 到 Java 21) 早已存在,**我之前写的 `ai-agent-validation.md` 完全没体现**:

- **5 个真实 benchmark**: CLBench (continual learning) + Radar (多维评分) + Tau3Subset (30 任务 3 难度) + DRBench (55KB adapter) + ContextBench (18KB adapter)
- **7 个 CLI 子命令**: `run` / `trials` / `aggregate` / `radar` / `catalog` / `model-groups` / `list`
- **LangGraph 集成** (harbor 包) 跟 `oh-my-opencode` submodule 复用
- **DeepAgent 集成** (`DeepAgentsSystem.java` 14 KB) 适配 CLBench
- **0 个测试** — 新模块还没写测试 (R-radar-2 待补)

之前 doc 写的 "4 层金字塔"太抽象,完全没提 5 个 benchmark + 7 CLI + 4 核心类。

---

## 2. 改动

### 2.1 重写 `doc/tech-docs/ai-agent-validation.md`

- **8.3 KB → 13.7 KB** (+ 5.4 KB)
- 章节从 11 个 → 11 个 (结构保留, 内容重写)
- 重点新增:
  - **第 2 节 ⭐⭐ `aethercode-evals` 模块** (5 benchmark + 7 CLI + 4 核心类 + LangGraph + DeepAgent 集成)
  - **第 7 节 ⭐⭐ R-radar 路线图** (8 个 round 的计划)
  - **第 8 节 ⭐⭐ 8 篇论文 Gap 分析** (R-radar 决策依据)

### 2.2 4 层验证金字塔从抽象变具体

| 之前 | 现在 |
|---|---|
| "Unit < 1s" | 2,375+ Java + 1,042 TS + 14 Rust + 60+ Python = ~3,500 |
| "E2E < 60s" | 35 KB R234 + ssd-demo + R246 flaky fix |
| "Manual" | (保留) |
| ❌ (没体现 benchmark 层) | ⭐ **aethercode-evals 5 benchmark** (R-radar-2 待补测试) |

### 2.3 新增 ⭐ R-radar 路线图

把后续 7 个 round 计划公开:
- R-radar-2 补 evals 测试
- R-radar-3 写 evals.md
- R-radar-4/5 论文搜索
- R-radar-6 V 校验器 (论文 2601.01743 gap)
- R-radar-7 Self-correction (论文 2508.17281 gap)
- R-radar-8 Multi-Agent 对抗

### 2.4 新增 ⭐ 8 篇论文 Gap 分析

每个论文对应 R-radar round:
- 2601.01743 → R-radar-6 (V 校验器)
- 2508.17281 → R-radar-7 (Self-correction)
- 2512.13564v2 → R-radar-2 (CLBench 补测试)
- 等

---

## 3. 关键发现

### 3.1 信息检索 tool — 已具备

- `WebSearchTool` (Brave / Serper 后端, env var)
- `WebFetchTool` (OkHttp + Jsoup)
- **能搜知乎/CSDN/官网** (走通用搜索引擎)
- **缺**: Google Scholar 学术搜索 → R-radar-4 补

### 3.2 AI Agent 测评 — 重大发现

- `aethercode-evals/` **完整的评测系统**, 5 benchmark 接入
- **0 测试** 是唯一不足
- 7 CLI 子命令覆盖 run / trials / aggregate / radar / catalog / model-groups / list

### 3.3 Multi-Agent / Agent Team — 已具备

- A2A 协议 + Sub-agent + DeepAgent + Role Registry + Multi-Graph + ACP + Talon + A2A-DeepAgent Bridge + LangGraph 集成
- **多 Agent 对抗** 待 R-radar-8 实现 (角色 + 投票 + 评审)

### 3.4 论文搜索 — 受网络限制

- 之前 arXiv download timeout
- 可以用 `web_fetch` 走 `https://arxiv.org/list/cs.AI/2026` 测试
- R-radar-5 走 WebFetchTool 抓 arxiv / scholar

---

## 4. R-radar 8 round 完整路线图

| Round | 内容 | 估计 | 完成日期 |
|---|---|---|---|
| **R-radar-1** | **重写 ai-agent-validation.md** | 1 round | 2026-09-12 (本 round) |
| R-radar-2 | 补 aethercode-evals 测试 (0 → 50+) | 1-2 round | TBD |
| R-radar-3 | 写 tech-docs/evals.md (5 benchmark 详解) | 1 round | TBD |
| R-radar-4 | 加 google_scholar tool (学术搜索) | 1 round | TBD |
| R-radar-5 | 论文搜索走 WebFetchTool (arxiv / scholar) | 1 round | TBD |
| R-radar-6 | V 校验器框架 (论文 2601.01743 gap #1) | 2-3 round | TBD |
| R-radar-7 | Self-correction 机制 (论文 2508.17281 gap #2) | 2-3 round | TBD |
| R-radar-8 | Multi-Agent 对抗 (角色 + 投票 + 评审) | 2-3 round | TBD |

预计 **8-12 round** (2-3 周)。

---

## 5. 测试

本次改动**只动文档**, 不改代码, 不需要跑测试。

但验证文档准确性:
- ✓ 17 个 evals 文件实测存在 (`aethercode-evals/src/main/java/org/aethercode/evals/`)
- ✓ Cli 7 个子命令确认 (`run` / `trials` / `aggregate` / `radar` / `catalog` / `model-groups` / `list`)
- ✓ 4 个 benchmark 关键文件大小实测 (CLBench 18K / Radar 14K / Tau3 11K / Drbench 55K / Contextbench 18K)
- ✓ 性能基线沿用 R250+ 实测
- ✓ 8 篇论文 gap 对应 R-radar round 闭环

---

## 6. 已知遗留

- **aethercode-evals 0 测试** (R-radar-2 待补)
- **没有 tech-docs/evals.md** (R-radar-3 待写)
- **没有 google_scholar tool** (R-radar-4 待加)
- **没有 V 校验器 / Self-correction / Multi-Agent 对抗** (R-radar-6/7/8 待实现)

---

## 7. 下一步

**R-radar-2**: 补 `aethercode-evals` 测试。
