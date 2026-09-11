# Tech Docs — 技术实现文档

> **面向**: 开发者 / 架构师 / 二次集成方
> **风格**: 介绍**实现方案**,不是过程记录;每个主题 1 篇,带"关键 round 引用"跳到 `round-notes/`。
> **当前共 15 篇主题**(2026-09-12 截至 R-radar-3 evals.md 落地)。

---

## 主题索引

### 1. 整体
| 文件 | 主题 | 字节 | 关键 round |
|---|---|---|---|
| [`architecture.md`](./architecture.md) | 16 模块整体架构、模块依赖、4 种 surface 通信 | 12 KB | R130, R141, R250+ |
| [`api.md`](./api.md) | 32+ JSON-RPC method + 16 个 error code + 14 stream event | 10 KB | R164, R250+ |

### 2. AI Agent 核心能力
| 文件 | 主题 | 字节 | 关键 round |
|---|---|---|---|
| **[`memory-system.md`](./memory-system.md)** | **5 scope + 4 阶编排 (Tier 0-4) + ForgettingPolicy 3 信号** | 18 KB | R230, R244, R245, R250+ |
| **[`context-compact.md`](./context-compact.md)** | **8 段式压缩 (R136.5) + CompactGate + Circuit Breaker** | 14 KB | R83, R136, R140, R250+ |
| **[`permission-control.md`](./permission-control.md)** | **6 OpKind + Matrix 3 维查表 + 6 PermissionMode + ACCEPT_TASK 边界** | 13 KB | R130, R203, R207, R250+ |
| **[`skill-system.md`](./skill-system.md)** | **Skill record + Front matter 解析 + 5 builtin + 3 段路径** | 6 KB | R250+ |
| **[`hook-system.md`](./hook-system.md)** | **3 阶段 (pre/deny/post) + 7 内置 hook + CopyOnWrite 调度** | 9 KB | R141, R151, R250+ |
| **[`ai-agent-validation.md`](./ai-agent-validation.md)** | **4 层验证金字塔 + R234 8 fix + 性能基线 + 4-shot self-eval** | 8 KB | R234, R239, R243, R250+ |
| **[`evals.md`](./evals.md)** | **aethercode-evals 模块: 5 benchmark (CLBench/Radar/Tau3/DRBench/ContextBench) + 7 CLI 子命令 + 127 tests + 2 parser bug** | 25 KB | **R-radar-1, R-radar-2, R-radar-3** |

### 3. 集成与协议
| 文件 | 主题 | 字节 | 关键 round |
|---|---|---|---|
| **[`mcp-integration.md`](./mcp-integration.md)** | **McpManager diff-based reload + 4 transport + OAuth + HealthCheck** | 10 KB | R132, R250+ |
| **[`workflow-engine.md`](./workflow-engine.md)** | **8 步流水线 + SessionSpawner 解耦 + 6 shipped workflow** | 14 KB | R241.3, R250+6 |
| **[`a2a-protocol.md`](./a2a-protocol.md)** | **4 层架构 + 4 RPC method + 6 task state + 手工 JSON-RPC** | 13 KB | R241.1, R250D, R250+1, R250+3 |

### 4. Demo 与实战
| 文件 | 主题 | 字节 | 关键 round |
|---|---|---|---|
| **[`ssd-demo.md`](./ssd-demo.md)** | **真实 SSD 目标检测 (torchvision) + image_understand tool 集成** | 6 KB | R242, R250+ |

### 5. 配置与发布 (R250+7 doc 重构新增)
| 文件 | 主题 | 字节 | 关键 round |
|---|---|---|---|
| **[`providers.md`](./providers.md)** | **OpenAI-compatible transport + per-agent model (R109-3) + 定价** | 5 KB | R109-1, R109-3, R250+ |
| **[`packaging.md`](./packaging.md)** | **4 阶段打 release (jar + TUI bundle + TUI exe + Tauri) + 跨平台** | 6 KB | R110, R250+ |

> **R250+7 doc 重构**: 原 `doc/PROVIDERS.md` / `doc/PACKAGING.md` 整合到这里,内容保留原文,顶部加 "整合自" 标注。

---

## 阅读顺序建议

**第一次接触 AetherCode**:
1. `architecture.md` — 看整体
2. `memory-system.md` — 核心能力
3. `context-compact.md` — 配套机制
4. `permission-control.md` — 安全边界
5. `ssd-demo.md` — 5 分钟跑起来

**二次集成 / 定制**:
1. `skill-system.md` — 怎么加自定义工具
2. `mcp-integration.md` — 怎么接 MCP
3. `workflow-engine.md` — 怎么编排多步任务
4. `hook-system.md` — 怎么拦截关键节点
5. `providers.md` — 怎么加新 LLM provider

**做跨 agent 通信**:
1. `a2a-protocol.md` — 协议本身
2. `workflow-engine.md` — workflow 调用 A2A

**做 agent 验证**:
1. `ai-agent-validation.md` — 6 大类验证 + 评分

**做 release**:
1. `packaging.md` — 打 release 流程

---

## 维护

- 改任何主题前先读 `architecture.md` 对照
- 写新主题时按现有结构:架构图 / 核心类 / 配置 / 测试 / trade-off / 关键 round
- 每篇末尾"关键 round 引用"必填,跳到 `../round-notes/` 找详细过程
- 整合自 `doc/XX.md` 的英文文档保留原文,顶部加 "整合自" 标注
