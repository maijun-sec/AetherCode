# AetherCode

> **AetherCode v0.2.65** — 跨 surface 的 AI agent 平台
>
> 28 个 Java 模块 + 5 个 TypeScript workspace + Rust (Tauri) + Python + Kotlin (IDEA plugin)
> **3,400+ tests** (Java 2,375+ / TypeScript 1,042 / Rust 14 / Python 60+)

AetherCode 是一个**多 surface 的 AI agent 平台**。
同一个 JVM 引擎同时驱动 Desktop GUI、TUI 终端、IDE 插件、headless CLI 四种前端,协议是 JSON-RPC 2.0。
后端用 Java 21 (records + sealed types + virtual threads),Memory / Permission / Compact 等核心能力都做了**可插拔**的模块化设计,可以独立替换或扩展。

---

## 1. ⭐ 核心能力

| 能力 | 是什么 | 怎么用 |
|---|---|---|
| **跨 session Memory** | 7 类分层 (USER / PROJECT / SESSION / EXPERIENCE / WORKING / AUDIT / TASK) + 4 阶自动编排 (Tier 0-4) | 自动 recall + 衰减,无需配置 |
| **Context 智能压缩** | **8 段式** Markdown 摘要 (Claude 7 段 + OpenCode 1 段 = Active Constraints) | 超 80% context window 自动触发 |
| **3 维权限矩阵** | `tool × pathGlob × opKind → Action` 第一道闸 + `deny/ask/allow` 规则 + 5 种 mode (含智能授权 `ACCEPT_EDITS`) | `~/.aethercode/settings.yaml` 配置 |
| **MCP 协议桥** | 4 种 transport (stdio / socket / SSE / WebSocket) + diff-based hot reload + OAuth + HealthCheck | 配 `mcp.json` 即可接入 |
| **YAML Workflow 引擎** | 8 步流水线 (Loader → Validator → SkillComposer → VariableSubstitution → ...) + 6 个内置 workflow | `tdd-feature` / `code-review` / `migrate-deps` 等 |
| **Skill 系统** | 5 个内置 + Markdown + frontmatter 格式,激活时注入 system prompt | `/agents` 选 |
| **SDD 工作流** | 8 阶段规格化开发 (constitution→specify→clarify→plan→analyze→tasks→implement→converge),复用 [github/spec-kit](https://github.com/github/spec-kit) 的阶段定义 + markdown 模板 | `aethercode sdd <feature> "<intent>"` 或 Desktop 📐 按钮 |
| **Hook 编排** | 3 阶段 (pre / deny / post) + 7 个内置 hook (BashSafety / PathSanitizer / Redaction / Truncation ...) | 纯函数,可注册可替换 |
| **A2A 协议 (v0.3)** | 4 层架构 + 4 个 JSON-RPC method + 6 task state + 手工 JSON-RPC 编码 | 跨 agent 通信,带 SSE streaming |
| **Bank 跨设备同步** | HTTPS + TLS + Bearer Token, USER/PROJECT scope 跨设备共享 | `~/.aethercode/bank/` |
| **VLM 多模态** | image / video / audio understanding tool | 调 `image_understand` 等 |
| **Self-Eval + Drift 监控** | 4-shot prompt 评估 confidence / drift / success / growth | 自动沉淀到 ExperienceStore |
| **Loop Guard** | 分层 loop detector (3+ 次同 tool / 8 turn 触发) + `LoopGuardBanner` | 自动停 + 续 |

---

## 2. ⭐ 4 种应用形态

| 形态 | 栈 | 适用 |
|---|---|---|
| **Desktop** | Tauri 2 + React 19 + WebView2 (Windows) / WKWebView (macOS) / WebKitGTK (Linux) | 终端用户, GUI, 多窗格 |
| **TUI** | Bun + Ink 5 + TypeScript | 终端党, SSH, 快速命令 |
| **IDEA Plugin** | Kotlin + IntelliJ Platform 2024.2+ | JetBrains IDE 用户 |
| **CLI (headless)** | Java shaded jar | CI / 脚本 / 服务器 |

**同一个 engine**,**同一个 JSON-RPC 协议**,**同一个 Memory** — 4 个 surface 行为完全一致。

---

## 3. 架构概览

```
┌────────────────────────────────────────────────────────┐
│                  Presentation (3+1 surfaces)            │
│   Desktop (Tauri/React)  TUI (Ink)  IDEA (Kotlin)     │
│                       CLI (shaded jar)                  │
└─────────────────────────┬──────────────────────────────┘
                          │ JSON-RPC 2.0
                          │ (stdio / HTTP / WebSocket / SSE)
┌─────────────────────────▼──────────────────────────────┐
│   aethercode-sdk + aethercode-protocol  (公共面)       │
└─────────────────────────┬──────────────────────────────┘
                          │
┌─────────────────────────▼──────────────────────────────┐
│   aethercode (Java 21, 28 modules)                     │
│   ─ core engine: QueryEngine / Message / StreamEvent    │
│   ─ memory: 5 scope + 4 阶 + Bank 同步                  │
│   ─ compact: 8 段式 + Circuit Breaker                   │
│   ─ permission: Matrix + 5 mode + ACCEPT_TASK 边界     │
│   ─ mcp: 4 transport + diff reload + OAuth              │
│   ─ workflows: 8 步流水线 + 6 shipped                   │
│   ─ skills / hooks / tools / tasks / a2a / talon ...    │
│   ─ deepagents / bridge / runtime / acp / models ...   │
└────────────────────────────────────────────────────────┘
```

详细模块图、数据流、依赖关系见 [`doc/tech-docs/architecture.md`](doc/tech-docs/architecture.md)。

---

## 4. 开发环境准备

### 4.1 必需

| 工具 | 版本 | 用途 |
|---|---|---|
| **JDK** | 21+ (推荐 21 LTS) | Java 模块编译/运行 |
| **Maven** | 3.9+ | Java 构建 |
| **Node.js** | 20+ | TS workspace 编译 |
| **pnpm** | 9+ | TS workspace 管理 |
| **Git** | 2.30+ (支持 submodule) | 拉取 reference 项目 |

### 4.2 可选 (按需)

| 工具 | 用途 |
|---|---|
| **Rust** (stable, + Tauri CLI) | 编译 Desktop Tauri 后端 |
| **Bun** (≥ 1.1) | 编译 TUI standalone exe |
| **Python** (3.10+) | 跑 ssd-demo (目标检测) |
| **IntelliJ IDEA** (2024.2+) | 开发/运行 IDEA 插件 |
| **Maven Central** 账号 | 发布 jar (用 Sonatype OSSRH) |

### 4.3 一次性准备

```bash
# 1. 克隆 (含 submodule)
git clone --recurse-submodules https://github.com/maijun-sec/AetherCode.git
cd AetherCode

# 2. 装 Rust 工具链 (Desktop 编译要)
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
cargo install tauri-cli --version "^2.0"

# 3. 装 Bun (TUI standalone)
curl -fsSL https://bun.sh/install | bash

# 4. (Windows) 装 WiX Toolset 3.x — 打 MSI 包装包需要
```

---

## 5. 快速开始

### 5.1 跑 Demo (5 分钟)

```bash
# 1. 全栈构建 (Java + TS,~ 2 分钟)
mvn -B -pl aethercode -am package -DskipTests
pnpm install
pnpm --filter aethercode-tui build
pnpm --filter aethercode-desktop build

# 2. 跑 SSD demo (验证 tool 集成,Python)
cd ssd-demo
pip install -e .
python -m ssd_demo --input ../test-image.jpg
cd ..

# 3. 跑 Desktop
pnpm --filter aethercode-desktop tauri dev
```

### 5.2 跑 Headless CLI

```bash
# 启动 daemon
java -jar aethercode/aethercode-cli/target/aethercode.jar --daemon

# 单次 query (另一个 shell)
java -jar aethercode/aethercode-cli/target/aethercode.jar --print "列出当前目录的 .java 文件"
```

### 5.3 配 LLM Provider

编辑 `~/.aethercode/settings.yaml` (详细 schema 见 [`doc/tech-docs/providers.md`](doc/tech-docs/providers.md)):

```yaml
providers:
  - name: anthropic
    type: openai-compatible
    baseUrl: https://api.anthropic.com
    apiKey: ${ANTHROPIC_API_KEY}
    defaultModel: claude-sonnet-4
    models:
      - id: claude-sonnet-4
        inputPer1k: 0.003
        outputPer1k: 0.015
```

---

## 6. ⭐ 构建 & 测试

### 6.1 Java (28 modules)

```bash
# 全模块编译 (~30s, 含测试)
mvn -B -pl aethercode -am clean install

# 跳测试的快速 build
mvn -B -pl aethercode -am package -DskipTests

# 单模块
mvn -B -pl aethercode-core test
mvn -B -pl aethercode-memory test

# 跑某个 test
mvn -B -pl aethercode-permission test -Dtest=MatrixPermissionPolicyR207Test
```

### 6.2 TypeScript (5 workspaces)

```bash
# 全 workspace
pnpm install
pnpm -r build
pnpm -r test

# 单 workspace
pnpm --filter aethercode-tui build
pnpm --filter aethercode-tui test

# TypeScript 类型检查
pnpm -r exec tsc --noEmit
```

### 6.3 Rust (Tauri 后端)

```bash
cd aethercode-desktop/src-tauri
cargo build --release
cargo test --lib
```

### 6.4 Python (ssd-demo)

```bash
cd ssd-demo
pip install -e ".[dev]"
pytest
```

### 6.5 打 Release

```powershell
# Windows
.\package.ps1

# Linux / macOS
./package.sh
```

详细打 release 流程 (4 stage + 跨平台 + 签名) 见 [`doc/tech-docs/packaging.md`](doc/tech-docs/packaging.md)。

### 6.6 E2E 冒烟

```bash
# Java + TS 全栈冒烟 (~10s 暖 cache)
pnpm smoke

# 性能 benchmark (TUI 渲染 + LLM round-trip)
pnpm bench
```

---

## 7. 文档导航

完整文档在 [`doc/`](doc/README.md) 目录,**按受众分三层**:

| 目录 | 面向 | 内容 |
|---|---|---|
| [`doc/user-guide/`](doc/user-guide/README.md) | 终端用户 / 集成方 | 怎么用 — 5 分钟上手、Memory 能力、Desktop/TUI 测试、Agent 编写、问题排查 |
| [`doc/tech-docs/`](doc/tech-docs/README.md) | 开发者 / 架构师 | 怎么实现 — 28 模块架构、Memory、Context Compact、权限、Skill、MCP、Workflow、Hook、A2A、AI Agent 验证、provider、打包 |
| [`doc/round-notes/`](doc/round-notes/README.md) | 历史研究者 / 维护者 | R07 ~ R250+ 各 round 详细过程记录,43 个文档 |

**按场景查**:

| 我想... | 看哪 |
|---|---|
| 第一次跑 | [`doc/user-guide/getting-started.md`](doc/user-guide/getting-started.md) |
| 写自定义 agent | [`doc/user-guide/agents.md`](doc/user-guide/agents.md) |
| 遇到问题 | [`doc/user-guide/troubleshooting.md`](doc/user-guide/troubleshooting.md) |
| 理解核心能力怎么实现 | [`doc/tech-docs/`](doc/tech-docs/) (14 篇主题) |
| 知道某 R-round 怎么设计的 | [`doc/round-notes/R<round>-*.md`](doc/round-notes/) |
| 看 32+ JSON-RPC method | [`doc/tech-docs/api.md`](doc/tech-docs/api.md) |
| 了解 A2A 协议 | [`doc/tech-docs/a2a-protocol.md`](doc/tech-docs/a2a-protocol.md) |

---

## 8. 贡献

1. 看 [`doc/tech-docs/architecture.md`](doc/tech-docs/architecture.md) 了解 28 模块怎么组合
2. 选一个未处理的 R-round 或新建一个 `R<round>-<topic>.md` 在 `doc/round-notes/`
3. 改代码 + 加测试。CI matrix 是真理 (Java Surefire + TS Vitest + Cargo test)
4. 跑完整 `pnpm test` + `mvn test` 再 push
5. PR 描述里 link 到对应的 R-round 文档

PR 检查清单:
- [ ] Java 模块: `mvn -B -pl <module> test` 通过
- [ ] TS workspace: `pnpm --filter <pkg> test` 通过
- [ ] 新功能加了对应的 `doc/tech-docs/<topic>.md` 或 `doc/round-notes/R<N>-*.md`
- [ ] 没有引入新的硬编码中文注释 (代码注释必须英文,文档用中文)
- [ ] Token / API key / 密码不进 commit (用 env var)

---

## 9. License

MIT License — see [LICENSE](LICENSE) (待加)。

---

## 10. ⭐ 参考

> 实现过程中参考的开源项目 + 学术论文 + 内部 round notes。

### 10.1 Reference 项目 (git submodule)

通过 `git clone --recurse-submodules` 拉取到 `reference/projects/`:

| 项目 | 来源 | 用途 |
|---|---|---|
| `reference/projects/claude-code-analysis/` | https://github.com/maijun-sec/claude-code-analysis | AetherCode 早期版本的 TypeScript 参考实现 |
| `reference/projects/oh-my-opencode/` | https://github.com/code-yeongyu/oh-my-opencode | OpenCode 的 TypeScript 多包 monorepo,结构借鉴 |

> 内部早期 AetherCode 用 TypeScript 实现过一遍,作为架构探索的脚手架。**AetherCode v0.2.65 之后切换到 Java + TypeScript 双栈**,TypeScript 仍保留作 reference (用 git submodule 隔离)。

### 10.2 学术论文

`reference/papers/` 收录了 8 篇 arXiv / Springer 综述 (每个含 PDF + 中文翻译 + 摘要),作为 Memory / Compact / Agent 架构设计的理论参考:

| 论文 | arXiv ID | 用于 |
|---|---|---|
| Memory in the Age of AI Agents | 2512.13564v2 | 7 类 Memory 分层 + Forgetting 机制 |
| Agentic AI: A Comprehensive Survey | 2510.25445 | 整体 Agent 框架综述 |
| Agentic AI Frameworks & Architectures | 2508.10146 | 多 agent 架构对比 |
| AI Agent Systems: Architectures | 2601.01743 | v0.2.65 主要参考 |
| From Language to Action: LLM Agents | 2508.17281 | Tool use + Action selection |
| A Holistic Review of Agentic AI | 10.1007/s11831-026-10675-8 | 工业视角综述 |
| Lifelong Learning in LLM Agents | 2501.07278 | 持续学习 + Memory 演化 |
| Multimodal Agentic Frameworks Survey | 2608.20379 | 多模态工具调用 |

### 10.3 R-round 历史

`doc/round-notes/` 收录 R07 ~ R250+ 各 round 的设计、决策、测试、trade-off,**43 个文档** 涵盖:

- **R07**: IDEA 插件集成
- **R83**: 7 段式 compact + CompactGate + SlidingWindow
- **R130**: 3-tier 权限
- **R132**: McpManager 替换 one-shot
- **R136.5**: **8 段式** compact (Claude 7 + OpenCode 1)
- **R140**: QueryEngine 集成 pre-flight compact
- **R203**: 权限 mode 热切换修复
- **R207**: Matrix 3 维查表 + Skip-Confirmation
- **R230**: Memory 综述驱动优化 + ForgettingPolicy
- **R233**: MemoryLifecycle 4 阶编排
- **R241.1 / R241.3**: A2A 协议模块 + Workflow Engine
- **R242**: VLM 工具 (image / video / audio)
- **R243**: Self-Eval + Drift bank
- **R244-249**: BankClient 跨 surface + Auth + TLS
- **R250+1~7**: SSE / 桥接 / CLI 集成 / 缓存 / 多模态 / 实战 workflow / Desktop UI 修复
- **R237**: 0.2.57 final release baseline

详细见 [`doc/round-notes/`](doc/round-notes/) 和 [`doc/round-notes/README.md`](doc/round-notes/README.md)。

### 10.4 性能基线 (R250+)

| 指标 | 实测 |
|---|---|
| 冷启动 (daemon) | ~1.8s |
| 简单 query round-trip | ~340ms |
| VLM image_understand | ~1.4s |
| VLM video_understand | ~6.8s |
| BankClient cache hit | ~0.8ms |
| Permission check | ~2ms |
| A2A message/send (local) | ~15ms |
| Workflow 启动 (含 8 步) | ~80ms |
| E2E (R234 baseline) | ~42s |

### 10.5 Citation

```bibtex
@software{aethercode2026,
  title  = {AetherCode: A Polyglot AI Agent Platform},
  version = {0.2.65},
  year   = {2026},
  url    = {https://github.com/maijun-sec/AetherCode}
}
```
