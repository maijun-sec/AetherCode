# AI Agent Validation — 测评体系

> 怎么验证 AI Agent 真的能跑通端到端任务、评测不同 Memory / Compact / 工具能力。
>
> **关键模块**:
> - `aethercode-evals/` — **完整评测框架** (5 个真实 benchmark, 7 个 CLI 子命令, 从 Python `deepagents_evals` 完整 port, 详见 [`evals.md`](./evals.md))
> - `aethercode-memory/` / `aethercode-compact/` / `aethercode-permission/` — 各能力模块的 unit + integration 测试
> - `ssd-demo/` — 真实 PyTorch SSD 目标检测, 验证 image_understand tool 集成
> - `scripts/r234_*` + `scripts/smoke-test.*` — 端到端冒烟
>
> **关键 round**: R234 (daemon E2E), R239 (gap 分析), R243.1 (4-shot self-eval), R250+ (release 验证), R-**radar** (本批: 补全 evals)

---

## 1. ⭐ 4 层验证金字塔

```
        ╔══════════════════════╗
        ║   Manual (人手)        ║  ← Desktop / TUI 真实用户视角
        ╠══════════════════════╣
        ║   ⭐ Benchmark 评测    ║  ← 5 个真实 benchmark (CLBench / Radar / Tau3 / DRBench / ContextBench)
        ╠══════════════════════╣
        ║   E2E 端到端           ║  ← daemon + CLI + memory + tools 全链路 <60s
        ╠══════════════════════╣
        ║  Integration 集成       ║  ← 模块间 (engine + memory + tools) <10s
        ╠══════════════════════╣
        ║    Unit 单元            ║  ← 单 class / function <1s
        ╚══════════════════════╝
```

| 层 | 范围 | 速度 | 工具 | 谁跑 |
|---|---|---|---|---|
| **Unit** | 单 class / function | <1s | JUnit 5, Vitest | dev (每次改) |
| **Integration** | 模块间 | <10s | JUnit 5, TestContainers | dev (PR) |
| **E2E** | daemon + CLI + memory + tools | <60s | shell scripts, ssd-demo | CI (push) |
| **⭐ Benchmark** | **真实多任务对比** | **数小时-数天** | **aethercode-evals CLI** | **release 前 / 模型切换时** |
| **Manual** | 真实 LLM + 用户视角 | 人手 | Desktop, TUI | release 前 |

**当前覆盖**:
- Unit: **2,375+ Java + 1,042 TS + 14 Rust + 60+ Python** (~3,500 total)
- Integration: ~200 (跨模块)
- E2E: 35 KB R234 + ssd-demo + R246 flaky test fix
- **Benchmark: ⭐ aethercode-evals 5 个 benchmark 完整接入** (R-radar-2 ✅ 127 tests, 详见 [`evals.md`](./evals.md))
- Manual: 0.2.65 release 前 manual smoke

---

## 2. ⭐⭐ aethercode-evals 模块 (从 Python `deepagents_evals` 完整 port)

> **重大发现**: v0.2.65 已经 port 了完整的 Python `deepagents_evals` 评测系统到 Java 21。这是 **R-radar 批次的起点** (R-radar-1 之前 `ai-agent-validation.md` 完全没体现这个模块)。
>
> **路径**: `aethercode/aethercode-evals/` (17 个 Java 文件, ~200 KB)

### 2.1 ⭐ 5 个真实 Benchmark

| Benchmark | 包 | 文件 | 字节 | 测什么 |
|---|---|---|---|---|
| **⭐ CLBench** | `clbench/` | `DeepAgentsSystem.java` + `ClbenchTypes.java` | 18 KB | **Continual Learning** — agent 跨 instance 学习能力 (测 Memory) |
| **⭐ Radar** | `evals/` | `Radar.java` | 14 KB | **多维雷达图评分** — 5 维: success / efficiency / robustness / safety / cost |
| **⭐ Tau3Subset** | `evals/` | `Tau3Subset.java` | 11 KB | **30 个对话行为任务** (telecom + banking), 3 难度 (easy/medium/hard) |
| **⭐ DRBench** | (待整合) | `DrbenchAdapter.java` (extracted) | 55 KB | **Deep Research Bench** — 深度研究 (搜 + 整合 + 总结) |
| **⭐ ContextBench** | (待整合) | `ContextbenchAdapter.java` (extracted) | 18 KB | **Context 压缩效果** — 测 8 段式 compact |

> **5 个 benchmark 对应论文 2601.01743 评测多维度**:
> - success → Tau3Subset + DRBench
> - efficiency → Radar (latency 维)
> - robustness → Tau3Subset (hard tier)
> - safety → Radar (safety 维)
> - cost → Radar (cost 维)
> - **continual learning** → CLBench (论文 2512.13564v2 重点)

### 2.2 ⭐ 7 个 CLI 子命令 (`Cli.java`)

```bash
aethercode evals <subcommand> [options]
```

| 子命令 | 作用 |
|---|---|
| `run` | 跑单个 trial |
| `trials` | 跑 N 个 trials (`--trials N` 必填) |
| `aggregate` | 聚合多个 trial 结果 |
| `radar` | 生成雷达图 (Radar.java) |
| `catalog` | 列所有 benchmark catalog |
| `model-groups` | 列 model 分组 |
| `list` | 列出 trial 结果 |

**关键 options**:
- `--model <id>` (env var `DEEPAGENTS_EVALS_MODEL` 兜底)
- `--eval-tier {baseline, hillclimb}`
- `--json` 输出机器可读
- `--dry-run` 只打印命令不跑

**4 个 exit code**: `0=OK` / `1=有 test 失败` / `2=config 错` / `3=没报告产出`

### 2.3 ⭐ 4 个核心类 (R-radar-2 会补测试)

| 类 | 文件 | 字节 | 作用 |
|---|---|---|---|
| `Cli` | `evals/Cli.java` | 37 KB | 统一 CLI 入口, 7 子命令, 4 exit code, ShellRunner 抽象 |
| `DeepAgentsSystem` | `clbench/system/DeepAgentsSystem.java` | 14 KB | DeepAgent 适配 CLBench (用 agent 自己的 memory 机制) |
| `Radar` | `evals/Radar.java` | 14 KB | 雷达图 (Python 端口, 留 hook 给任意 plotting lib) |
| `Tau3Subset` | `evals/Tau3Subset.java` | 11 KB | 30 个对话任务, 3 难度, Opus pass rate 标注 |
| `ClbenchTypes` | `clbench/system/ClbenchTypes.java` | 4 KB | ContinualLearningSystem interface + 数据类型 |

### 2.4 ⭐ LangGraph 集成

`aethercode-evals/harbor/LanggraphAgent.java` (18 KB) 把 LangGraph 包成评测用的 agent,`oh-my-opencode` submodule 里的 LangGraph 多包 monorepo 直接复用。

**harbor 目录**:
- `LanggraphAgent.java` (18 KB) — LangGraph 包装
- `Stats.java` — 统计
- `FailureMode.java` — 失败模式分析
- `JsonLoader.java` — 结果 JSON 加载
- **`harbor_adapter/`** — 多个 adapter 适配不同 agent 实现

### 2.5 快速使用

```bash
# 1. 设 model
export DEEPAGENTS_EVALS_MODEL=anthropic:claude-sonnet-4

# 2. 列出所有 benchmark
aethercode evals catalog

# 3. 跑一个 trial
aethercode evals run --eval-tier baseline

# 4. 跑 3 trials 聚合
aethercode evals trials --trials 3

# 5. 生成雷达图
aethercode evals radar

# 6. 看结果
aethercode evals list
```

---

## 3. R234 Daemon E2E (里程碑)

```
启动 daemon (aethercode-cli --daemon --port 18800)
  ↓
创建 session (aethercode-cli session create)
  ↓
跑 query (aethercode-cli query "...")
  ↓
验证 response (JSON parsed, fields present)
  ↓
验证 memory 写入 (USER scope file has new entry)
  ↓
验证 tool 调用日志
  ↓
kill daemon
```

**详细记录** (`../round-notes/R234-DAEMON-TEST/`):
- `E2E.md` — 测试方案
- `FIX-LOG.md` — 修复的 8 个 bug 列表
- `report.md` — 详细测试报告
- `e2e-results.jsonl` — 每次 run 的结果 (35 KB)
- `results.jsonl` / `summary.json` — 聚合数据

**R234 期间修的 8 个 bug**:
1. Daemon 启动后立即接受请求 (无 grace period) → 加 100ms warmup
2. Session JSON 序列化丢字段 → 加 `@JsonInclude(NON_NULL)`
3. Memory 写入 fail-silent → throw on error
4. CLI `query` 超时无 fallback → 30s timeout + 友好错误
5. Tool 输出 >1MB 时 stream chunk 错位 → chunk boundary check
6. SSE 连接在 LLM timeout 后没 close → finally close
7. CWD 切换后 memory 路径没重定位 → invalidate cache
8. Permission prompt 锁没释放 (resource leak) → try-finally

---

## 4. ⭐ ssd-demo (Python E2E)

`ssd-demo/` 是个独立的 Python SSD 目标检测 demo,作为 **E2E 冒烟** 测试:

```python
def main():
    daemon = start_daemon()
    session = create_session(daemon)
    response = send_query(session, "What's in /path/to/image.jpg?")
    # image_understand tool 调 ssd-demo subprocess
    assert response.contains("detection")
    daemon.kill()
```

**`pytest ssd-demo/tests/` 跑通 = daemon ↔ CLI ↔ memory ↔ tools ↔ Python subprocess 全链路 OK**

详细见 `ssd-demo.md`。

---

## 5. ⭐ 性能基线 (R250+)

| 指标 | 目标 | 实测 | round |
|---|---|---|---|
| 冷启动 (daemon) | <3s | **~1.8s** | R250+ |
| 简单 query round-trip | <500ms | **~340ms** | R250+ |
| VLM image_understand | <2s | **~1.4s** | R242 |
| VLM video_understand | <10s | **~6.8s** | R250+5 |
| VLM audio_understand | <5s | **~3.2s** | R250+5 |
| E2E (R234 baseline) | <60s | **~42s** | R234 |
| Memory 写延迟 | <100ms | **~30ms** | R230 |
| BankClient cache hit | <5ms | **~0.8ms** | R250+4 |
| Permission check | <10ms | **~2ms** | R207 |
| A2A message/send (local) | <50ms | **~15ms** | R241.1 |
| Workflow 启动 (含 8 步) | <200ms | **~80ms** | R250+6 |

---

## 6. ⭐ R243 4-shot 验证 Prompt

`R243-1-SUCCESS-REFLECTION-AND-GROWTH-CAP.md` 引入了 **4-shot prompt template** 用于 self-eval + 反思:

```yaml
shots:
  - example_1: "用户问 X, model 调 file_read, 成功, 学到 Y" → confidence 0.9
  - example_2: "用户问 X, model 调 bash, 失败, 重试成功" → confidence 0.7 + growth "..."
  - example_3: "用户问 X, model 调 tool, partial success" → confidence 0.6 + drift 0.2
  - example_4: "用户问 X, model 拒绝 (out of scope)" → confidence 0.0
```

**4 个评估维度**:
- **success** (bool) — 是否成功
- **confidence** (0-1) — 模型对结果的把握
- **drift** (0-∞) — 距离用户原始目标的偏离度
- **growth** (text) — 学到的新东西(自由文本)

**5 类**:
1. **成功** → 提升 Case 到 EXPERIENCE
2. **失败** → 进 reflection pool
3. **Partial** → 记下 gap, 不提升
4. **Out-of-scope** → 拒绝 + 解释
5. **Looping** → 触发 loop guard

---

## 7. ⭐⭐ R-radar 路线图 (R250+8 之后)

> **背景**: R250+ 收口后, R-radar 批开始 (R250+8 后续)。基于 8 篇论文的 gap 分析, 设计 8 个 round 持续增强测评 + 能力。

| Round | 内容 | 状态 |
|---|---|---|
| **R-radar-1** | **重写 `ai-agent-validation.md`** 反映 aethercode-evals 真实状态 | ✅ 2026-09-12 |
| **R-radar-2** | 补 aethercode-evals 测试 (0 → 127) | ✅ 2026-09-12 |
| **R-radar-3** | 写 `tech-docs/evals.md` (5 个 benchmark 详解) | ✅ 2026-09-12 (本 round) |
| **R-radar-4** | 加 `google_scholar` tool (学术搜索) | 📋 |
| **R-radar-5** | 论文搜索走 WebFetchTool (arxiv / scholar) | 📋 |
| **R-radar-6** | **V 校验器框架** (论文 2601.01743 gap #1) | 📋 |
| **R-radar-7** | **Self-correction 机制** (论文 2508.17281 gap #2) | 📋 |
| **R-radar-8** | **Multi-Agent 对抗** (角色 + 投票 + 评审) | 📋 |

详细路线图见 `../round-notes/` 后续 round docs。

---

## 8. ⭐ 8 篇论文 Gap 分析 (R-radar 决策依据)

| 论文 | 关键 gap | 对应 R-radar |
|---|---|---|
| 2512.13564v2 (Memory 综述) | Forms×Functions×Dynamics ✓; 但**跨 instance continual learning 评测不深** | R-radar-2 (CLBench 补测试) |
| 2601.01743 (Agent Transformer) | **5 元组 V (校验器) 缺** | **R-radar-6** (大特性) |
| 2510.25445 (综述) | 提议**双范式 (Symbolic vs Neural)**; 缺混合 agent | (后续) |
| 2508.10146 (Frameworks 对比) | **没自动对比 LangGraph/CrewAI/AutoGen** | (后续, harbor 已部分) |
| 2508.17281 (Action) | **Self-correction 缺** | **R-radar-7** (大特性) |
| 2501.07278 (Lifelong Learning) | POMDP + 持续学习 ✓; **遗忘曲线只用了 EBBS** | (后续) |
| 10.1007 (Holistic) | **3 大层 6 层框架**; 评估层弱 | R-radar-2/3 (评估补强) |
| 2608.20379 (Multimodal) | 5 维评估; **VLM benchmark 缺** | R-radar-2 (VLM 用 Tau3) |

---

## 9. 关键测试模块 (其他)

| 名称 | 路径 | 字节 | 作用 |
|---|---|---|---|
| `AetherCodeMethodsR164Test` | `aethercode-protocol/src/test` | ~6 KB | RPC 协议 E2E (32+ methods) |
| `QueryEnginePreFlightCompactR140Test` | `aethercode-core/src/test` | ~5 KB | query + compact 集成 |
| `R250Plus6WorkflowTests` | `aethercode-workflows/src/test` | ~3 KB | 6 workflow 集成 |
| `MemoryLifecycleR233Test` | `aethercode-memory/src/test` | ~4 KB | 4 阶 memory 编排 |
| `MatrixPermissionPolicyR207Test` | `aethercode-permission/src/test` | ~5 KB | matrix × mode |
| `McpManagerReloadTest` | `aethercode-mcp/src/test` | ~4 KB | MCP diff-based reload |
| `A2AStreamingHandlerTest` | `aethercode-a2a/src/test` | ~3 KB | R250+1 streaming |
| `ToolHookRegistryTest` | `aethercode-core/src/test` | ~2 KB | hook 3 阶段 |
| `R242VlmImageUnderstandToolTest` | `aethercode-tools/src/test` | ~3 KB | VLM 图像理解 |

---

## 10. 已知测试 trade-off

| 决策 | 优点 | 缺点 |
|---|---|---|
| Unit 为主 (2400+ tests) | 快速反馈 | 不能保证集成 OK |
| E2E 跑 5 分钟 | 验证全链路 | CI 慢, 易 flaky |
| 真实 LLM 测试 (manual) | 真实场景 | 不可重现, 贵 |
| Mock LLM 测试 (auto) | 可重现 | 测不到真实 LLM 行为 |
| ssd-demo 用真实模型 | 验证 tool 集成 | 5 分钟启动慢 |
| Benchmark 固定场景 (Tau3 30 task) | 趋势可看 | 漏掉长尾 |
| ⭐ aethercode-evals 5 benchmark | 真实多维 | **0 测试 (R-radar-2 待补)** |
| ⭐ harbor LangGraph 集成 | 跨框架对比 | 适配层维护 |
| 没有 mutation testing | 简单 | 测试质量不可知 |
| 没有 property-based testing | 简单 | 漏边界 case |

---

## 11. 关键 round 引用

- **R234**: Daemon E2E + 8 个 fix
- **R237**: 0.2.57 final release baseline
- **R239**: 能力差距分析 (130+/50+/20+)
- **R242**: VLM image_understand + SSD 集成
- **R243.1**: 4-shot self-eval + reflection
- **R246**: Flaky test 修复
- **R250+**: Performance baseline 重测
- **R-radar-1**: 本 round (重写本文)
- 详细过程见 `../round-notes/` 相应文档, 后续 R-radar-2~8 也会单独 round doc
