# Round Notes — R-round 过程记录

> **面向**: 历史研究者 / 维护者 / 想了解"为什么这么设计"的人
> **风格**: 详细过程记录,每个 round 一篇;保留决策、trade-off、测试成绩。
> **当前共 43 篇**(2026-09-11 截至 R250+7)。

> **技术视角速查** → 看 [`../tech-docs/`](../tech-docs/);**用户视角速查** → 看 [`../user-guide/`](../user-guide/)。

---

## 0. R 编号约定

- `R<整数>` — 主 round(例如 `R07`, `R230`, `R250`)
- `R<整数>.<小数>` — 子 round(例如 `R238.1`, `R241.3`)
- `R<整数>+<正整数>` — 主 round 之后的 post-round(例如 `R250+1` ~ `R250+7`)

post-round 通常用于:review 反馈、follow-up 修复、独立小特性。

---

## 1. 早期(IDE 插件奠基)

| 文件 | 主题 | 字节 |
|---|---|---|
| [`R07-IDEA-Plugin-Integration-Foundation.md`](./R07-IDEA-Plugin-Integration-Foundation.md) | IDEA 插件集成基础 | — |

## 2. Memory 系统演进(R230-R250+)

| 文件 | 主题 | 字节 |
|---|---|---|
| [`AetherCode-Memory-Optimization-Design.md`](./AetherCode-Memory-Optimization-Design.md) | Memory 优化设计(综合) | — |
| [`AetherCode-Memory-System-Understanding.md`](./AetherCode-Memory-System-Understanding.md) | Memory 系统理解(综述) | — |
| [`R230-MEMORY-SURVEY-DRIVEN-OPTIMIZATION.md`](./R230-MEMORY-SURVEY-DRIVEN-OPTIMIZATION.md) | 综述驱动 Memory 优化 | — |
| [`R230-MEMORY-WRITE-READ-MAP.md`](./R230-MEMORY-WRITE-READ-MAP.md) | 写/读时机详细矩阵 | — |
| [`R241-3-PERSISTENCE-UTILITY-DECAY.md`](./R241-3-PERSISTENCE-UTILITY-DECAY.md) | 持久化 + 效用衰减 | — |
| [`R241-EXPERIENCESTORE-STRATEGY-LIBRARY.md`](./R241-EXPERIENCESTORE-STRATEGY-LIBRARY.md) | ExperienceStore + 策略库 | — |
| [`R243-1-SUCCESS-REFLECTION-AND-GROWTH-CAP.md`](./R243-1-SUCCESS-REFLECTION-AND-GROWTH-CAP.md) | 成功反思 + 增长能力 | — |
| [`R243-3-DRIFT-BANK-TO-AGENTS.md`](./R243-3-DRIFT-BANK-TO-AGENTS.md) | Drift Bank 推到 agents | — |
| [`R244-1-SELF-EVAL-CONFIDENCE-METRIC.md`](./R244-1-SELF-EVAL-CONFIDENCE-METRIC.md) | 自评估 + 置信度指标 | — |
| [`R244-2-CROSS-SURFACE-BANK-HTTP.md`](./R244-2-CROSS-SURFACE-BANK-HTTP.md) | 跨 surface bank + HTTP | — |
| [`R244-3-TS-BANK-CLIENT.md`](./R244-3-TS-BANK-CLIENT.md) | TS bank client | — |
| [`R245-1-TUI-BANK-INTEGRATION.md`](./R245-1-TUI-BANK-INTEGRATION.md) | TUI bank 集成 | — |
| [`R245-2-MEMORY-AUDIT-SELF-EVAL.md`](./R245-2-MEMORY-AUDIT-SELF-EVAL.md) | Memory audit + self-eval | — |
| [`R245-3-PERIODIC-DECAY-SCHEDULER.md`](./R245-3-PERIODIC-DECAY-SCHEDULER.md) | 周期衰减 scheduler | — |
| [`R245-4-CONFIDENCE-AWARE-DRIFT.md`](./R245-4-CONFIDENCE-AWARE-DRIFT.md) | 置信度感知 drift | — |
| [`R245-5-WELCOME-BANK-STATUS.md`](./R245-5-WELCOME-BANK-STATUS.md) | Welcome + bank 状态 | — |
| [`R247-BANK-SERVER-AUTH.md`](./R247-BANK-SERVER-AUTH.md) | Bank 服务端鉴权 | — |
| [`R248-BANK-SERVER-TLS.md`](./R248-BANK-SERVER-TLS.md) | Bank 服务端 TLS | — |
| [`R249-DESKTOP-RUST-BANK-CLIENT.md`](./R249-DESKTOP-RUST-BANK-CLIENT.md) | Desktop Rust bank client | — |

## 3. A2A 协议(R241, R250+, R250D)

| 文件 | 主题 | 字节 |
|---|---|---|
| [`R241-A2A-PROTOCOL-MODULE.md`](./R241-A2A-PROTOCOL-MODULE.md) | A2A 协议模块 | — |
| [`R250D-A2A-HTTP-TRANSPORT.md`](./R250D-A2A-HTTP-TRANSPORT.md) | A2A HTTP transport | — |

## 4. 工具与能力(R242, R243)

| 文件 | 主题 | 字节 |
|---|---|---|
| [`R242-IMAGE-UNDERSTAND-AND-ROLES.md`](./R242-IMAGE-UNDERSTAND-AND-ROLES.md) | 图像理解 + 角色 | — |
| [`R242-VLM-IMAGE-UNDERSTAND-TOOL.md`](./R242-VLM-IMAGE-UNDERSTAND-TOOL.md) | VLM 图像理解工具 | — |
| [`R243-2-TALON-WIRE-READY.md`](./R243-2-TALON-WIRE-READY.md) | Talon wire ready | — |
| [`R243-2B-TALON-WIRE-ACTIVE.md`](./R243-2B-TALON-WIRE-ACTIVE.md) | Talon wire active | — |

## 5. R250 主 round + post-round

| 文件 | 主题 | 字节 |
|---|---|---|
| [`R250-RELEASE-AUTH-TLS-RUST-CARGO.md`](./R250-RELEASE-AUTH-TLS-RUST-CARGO.md) | 0.2.64 release + Auth + TLS + Rust cargo | — |
| [`R250+-功能说明.md`](./R250+-功能说明.md) | R250+ 功能说明(汇总) | — |

## 6. 调研 / 综述 / 验证

| 文件 | 主题 | 字节 |
|---|---|---|
| [`R238-SURVEY-TRANSLATIONS.md`](./R238-SURVEY-TRANSLATIONS.md) | 综述翻译 | — |
| [`R238.1-SURVEY-TRANSLATIONS-3ROUND-VERIFY.md`](./R238.1-SURVEY-TRANSLATIONS-3ROUND-VERIFY.md) | 综述翻译 3 轮验证 | — |
| [`R239-CAPABILITY-GAP-ANALYSIS.md`](./R239-CAPABILITY-GAP-ANALYSIS.md) | 能力差距分析 | — |
| [`R240-LIMITS-USERFACE-AND-TOT-MIDDLEWARE.md`](./R240-LIMITS-USERFACE-AND-TOT-MIDDLEWARE.md) | 限制 + 用户面 + ToT middleware | — |

## 7. 测试与发布

| 文件 | 主题 | 字节 |
|---|---|---|
| [`R237-FINAL-RELEASE-2026-09-09.md`](./R237-FINAL-RELEASE-2026-09-09.md) | 0.2.57 final release | — |
| [`R246-FLAKY-TEST-FIX.md`](./R246-FLAKY-TEST-FIX.md) | Flaky test 修复 | — |

## 8. SSD Demo

| 文件 | 主题 | 字节 |
|---|---|---|
| [`SSD-DEMO-RUN.md`](./SSD-DEMO-RUN.md) | SSD demo run | — |
| [`SSD-WORKFLOW.md`](./SSD-WORKFLOW.md) | SSD workflow | — |

## 9. R234 Daemon 测试报告(R234-DAEMON-TEST/)

| 文件 | 主题 | 字节 |
|---|---|---|
| [`E2E.md`](./E2E.md) | E2E 报告 | — |
| [`FIX-LOG.md`](./FIX-LOG.md) | Fix log | — |
| [`e2e-results.jsonl`](./e2e-results.jsonl) | E2E 结果(JSONL) | — |
| [`e2e-summary.json`](./e2e-summary.json) | E2E 摘要(JSON) | — |
| [`report.md`](./report.md) | 报告 | — |
| [`results.jsonl`](./results.jsonl) | 结果(JSONL) | — |
| [`summary.json`](./summary.json) | 摘要(JSON) | — |

---

## 维护

- 加新 round 文档 → 写 `R<新 round>-<topic>.md`,然后**回本 README 加索引**
- 文件名规则: `R<round>-<topic>.md`,空格用 `-`
- 改老文档 → 在 round 末尾加"修订记录"小节
- 看测试成绩 → 每个 round 文档都有"测试 / 回归"小节
