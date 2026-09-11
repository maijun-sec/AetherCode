# User Guide — 用户文档

> **面向**: 终端用户 / 集成方 / 第一次接触 AetherCode 的人
> **风格**: 怎么用,**不**讲实现;具体实现看 [`../tech-docs/`](../tech-docs/)。

---

## 文档索引

| 文件 | 主题 | 字节 | 适用版本 |
|---|---|---|---|
| [`getting-started.md`](./getting-started.md) | 5 分钟上手:安装 + 配置 provider + 跑第一个 query | ~7 KB | v0.2.x |
| [`使用说明-Desktop与TUI能力测试清单.md`](./使用说明-Desktop与TUI能力测试清单.md) | Desktop + TUI 怎么启动、能力怎么逐项验证 | 32 KB | 截至 v0.2.57 (R237 final) |
| [`Memory-能力与使用指南.md`](./Memory-能力与使用指南.md) | Memory 系统的 7 类分层、4 阶编排、跨进程 RPC 怎么用 | 22 KB | 截至 R233 (2026-09-07) |
| [`agents.md`](./agents.md) | 怎么写自定义 agent + per-agent model (R109-3) | ~7 KB | v0.2.x |
| [`troubleshooting.md`](./troubleshooting.md) | 12 个常见问题 + 修复方法 (provider timeout / loop detected / SmartScreen / 等) | ~10 KB | v0.2.x |

> **R250+7 doc 重构**: 原 `doc/GETTING-STARTED.md` / `doc/AGENTS.md` / `doc/TROUBLESHOOTING.md` 整合到这里,内容保留原文,顶部加 "整合自" 标注。

---

## 我想...

| 我想... | 看哪篇 |
|---|---|
| 第一次装 AetherCode 跑个 query | [`getting-started.md`](./getting-started.md) |
| 理解 Memory 是怎么自动召回的、怎么手动干预 | `Memory-能力与使用指南.md` |
| 跑 Desktop / TUI,把每个能力点勾一遍 | `使用说明-Desktop与TUI能力测试清单.md` |
| 写自定义 agent (per-persona system prompt) | `agents.md` |
| 遇到 "no current provider" / loop / SmartScreen 等问题 | `troubleshooting.md` |
| 看 5 分钟 SSD demo | → [`../tech-docs/ssd-demo.md`](../tech-docs/ssd-demo.md) |
| 看完整 JSON-RPC API | → [`../tech-docs/api.md`](../tech-docs/api.md) |
| 看 memory 系统的实现原理 | → [`../tech-docs/memory-system.md`](../tech-docs/memory-system.md) |
| 加新 LLM provider | → [`../tech-docs/providers.md`](../tech-docs/providers.md) |

---

## 维护

- 写新用户文档时,沿用现有 2 篇中文文档的"测试项 / 步骤化"风格
- 整合自 `doc/XX.md` 的英文文档保留原文,在顶部加 "整合自" 标注
- 涉及具体版本时,在文末标"基线版本"和"SHA256"
- 涉及技术实现时,跳到 `../tech-docs/` 而不是在 user-guide 重复写
