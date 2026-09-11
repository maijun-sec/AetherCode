# AetherCode Documentation

> **AetherCode v0.2.65** (R250+7 complete) · 2026-09-11
>
> 本目录是 AetherCode 的全部文档,按受众分三类组织。
> 顶层 `README.md` 是入口,深入阅读走下面三个子目录。

---

## 0. 目录速查

| 子目录 | 面向谁 | 内容 |
|---|---|---|
| **[`user-guide/`](./user-guide/)** | **终端用户 / 集成方** | 怎么用 — 安装、5 分钟上手、Memory 能力、Desktop/TUI 测试、Agent 编写、问题排查 |
| **[`tech-docs/`](./tech-docs/)** | **开发者 / 架构师** | 怎么实现 — 16 模块架构、Memory、Context Compact、权限、Skill、MCP、Workflow、Hook、A2A、AI Agent 验证、打包、LLM provider |
| **[`round-notes/`](./round-notes/)** | **历史研究者 / 维护者** | R-round 过程记录 — 每个 round 的设计、决策、测试、trade-off |

> **R250+7 doc 重构**: 原顶层 10 个英文元文档 (`AGENTS.md` / `API.md` / `ARCHITECTURE.md` / `CHANGELOG.md` / `GETTING-STARTED.md` / `PACKAGING.md` / `PROVIDERS.md` / `TROUBLESHOOTING.md` / `USER-GUIDE.md` / `WORKFLOWS.md`) 已合并:
> - 5 个跟新文档完全重叠 → 删 (`API.md` / `ARCHITECTURE.md` / `CHANGELOG.md` / `USER-GUIDE.md` / `WORKFLOWS.md`)
> - 5 个有独立价值 → 整合到子目录 (`AGENTS.md` → `user-guide/agents.md` 等)

---

## 1. 我该看哪个目录?

| 我想... | 去看 |
|---|---|
| 安装 + 第一次跑 | [`user-guide/getting-started.md`](./user-guide/getting-started.md) |
| 跑 Demo / 看 Desktop / TUI 界面截图和操作 | [`user-guide/使用说明-Desktop与TUI能力测试清单.md`](./user-guide/使用说明-Desktop与TUI能力测试清单.md) |
| 写自定义 agent | [`user-guide/agents.md`](./user-guide/agents.md) |
| 遇到问题排查 | [`user-guide/troubleshooting.md`](./user-guide/troubleshooting.md) |
| 理解 memory 7 类分层 / 4 阶编排 | [`tech-docs/memory-system.md`](./tech-docs/memory-system.md) |
| 理解 context 8 段式压缩 | [`tech-docs/context-compact.md`](./tech-docs/context-compact.md) |
| 加一个新的权限规则 | [`tech-docs/permission-control.md`](./tech-docs/permission-control.md) |
| 加一个新的 tool (skill) | [`tech-docs/skill-system.md`](./tech-docs/skill-system.md) |
| 接入一个新的 MCP server | [`tech-docs/mcp-integration.md`](./tech-docs/mcp-integration.md) |
| 写一个自定义 workflow | [`tech-docs/workflow-engine.md`](./tech-docs/workflow-engine.md) |
| 加一个 hook point | [`tech-docs/hook-system.md`](./tech-docs/hook-system.md) |
| 跑 5 分钟 SSD demo | [`tech-docs/ssd-demo.md`](./tech-docs/ssd-demo.md) |
| 看 A2A 协议怎么跨 agent 通信 | [`tech-docs/a2a-protocol.md`](./tech-docs/a2a-protocol.md) |
| 看完整 JSON-RPC API (32+ methods) | [`tech-docs/api.md`](./tech-docs/api.md) |
| 16 模块整体架构 | [`tech-docs/architecture.md`](./tech-docs/architecture.md) |
| 加新的 LLM provider | [`tech-docs/providers.md`](./tech-docs/providers.md) |
| 打 release 包 | [`tech-docs/packaging.md`](./tech-docs/packaging.md) |
| 跑 AI Agent 验证套件 | [`tech-docs/ai-agent-validation.md`](./tech-docs/ai-agent-validation.md) |
| 了解某个 R-round 的设计取舍 | `round-notes/R<round>-*.md` |

---

## 2. 文档组织约定

- **代码 vs 文档语言**: 代码注释用 **英文** (`aethercode/`, `aethercode-desktop/`),
  `.md` 文档主体用 **中文** (5 个 R250+7 整合过来的英文文档保留英文原文,见每篇顶部 "整合自" 标注)。
- **R-round 标识符**: 全文保持 `R<number>[.<sub>][+<postfix>]` 形式,例如:
  - `R07` (round 7)
  - `R250.1` (round 250, sub-round 1)
  - `R250+1` (round 250 之后第 1 个 post-round,常用于 review/fix/follow-up)
- **每篇技术文档** 的"关键 round 引用"小节标了来源 round;要看完整过程就跳到 `round-notes/`。
- **每篇 R-round 文档** 都标了"产出"和"测试成绩",便于 audit。

---

## 3. 论文参考

代码仓根目录 `reference/papers/` 收录了 8 篇 arXiv / Springer 综述
(每个含 PDF + 中文翻译 + 摘要),设计阶段的重要参考依据。
详见 [reference README](../reference/papers/)。

---

## 4. 维护

- 加新 round 文档 → 写 `doc/round-notes/R<new>-<topic>.md`
- 加新主题技术文档 → 在 `doc/tech-docs/` 加 `<topic>.md`,然后回 `tech-docs/README.md` 加索引
- 加新用户文档 → 在 `doc/user-guide/` 加,中文写,跟 `Memory-能力与使用指南.md` 风格一致
- 修改 `doc/README.md` 本文件,更新速查表
