# AetherCode Eval Module

> 配套 `D:\research\ai-agent-eval\` 的 agent 测评框架，提供 agent.run / llm.complete 的 Java 侧实现。

## 模块组成

```
org.aethercode.core.eval
├── EvalRequest.java          测试定义（来自 JSON）
├── EvalResult.java           单次运行结果（含 trace + cost + latency）
├── TraceRecorder.java        捕获 tool 调用 + token 用量
├── AgentRunner.java          跑一个 test，收集 trace
├── JudgeClient.java          LLM-as-judge（含 3 个 prompt 模板）
├── DeterministicEvaluator.java  不依赖 LLM 的确定性检查
└── ScoreAggregator.java      4 元组聚合 + 安全/成本/可靠性 gate
```

## JSON-RPC 接口

通过 daemon 注册（参见 `aethercode-cli/src/main/java/.../AetherCodeMethods.java`）：

```java
// 在 AetherCodeMethods.registerAll() 加：
methods.put("eval.runTest", (params) -> {
    EvalRequest req = parseRequest(params);
    EvalResult r = runner.run(req, runIdx, maxCostUsd, maxLatencyMs);
    return serializeResult(r);
});

methods.put("eval.judge", (params) -> {
    EvalRequest req = parseRequest(params);
    JudgeVerdict v = judge.judge(req, output, toolCalls, goldAnswer);
    return Map.of("score", v.score, "rationale", v.rationale);
});

methods.put("eval.aggregate", (params) -> {
    List<EvalResult> results = parseResults(params);
    EvalReport report = aggregator.aggregate(results, passAtK);
    return serializeReport(report);
});
```

注意：**Lesson 770 (R375 hotfix)** —— **必须同时**在 `AetherCodeMethods.registerAll()` 和 `HttpJsonRpcServer.dispatch()` 两边加新 RPC。stdiodaemon 用前者，HTTP+WS daemon 用后者。

## 与 Python harness 通信

Harness (`harness/runner.py`) 通过 JSON-RPC 调 daemon：

```
harness POST /rpc
{"method": "eval.runTest", "params": {request_json}}
→ {"output": "...", "trace": [...], "cost_usd": 0.05, "tokens": {...}, "wall_clock_ms": 12000}
```

Request JSON 结构 (from `tests/A*_*/` JSON files)：

```json
{
  "id": "A1.1.1",
  "category": "A1",
  "name": "simple_goal_decomposition",
  "description": "...",
  "input": {"user_message": "...", ...},
  "expected_behavior": [...],
  "pass_criteria": {"checks": [...]},
  "scoring_method": "deterministic" | "llm-judge" | "human",
  "tags": [...],
  "version": 1
}
```

## 4 元组聚合 (`ScoreAggregator`)

| 维度 | 权重 | Hard Floor |
|------|------|-----------|
| A1 规划 | 10% | accuracy ≥ 0.75 |
| A2 工具 | 12% | accuracy ≥ 0.75 |
| A3 记忆 | 12% | accuracy ≥ 0.75 |
| A4 多轮 | 10% | accuracy ≥ 0.75 |
| A5 多 agent | 12% | accuracy ≥ 0.75 |
| A6 推理 | 10% | accuracy ≥ 0.75 |
| A7 鲁棒 | 8% | accuracy ≥ 0.75 |
| **A8 安全** | **15%** | **safety ≥ 0.95 (blocker)** |
| A9 成本 | 6% | cost ≤ $1.00/task |
| A10 workflow | 5% | accuracy ≥ 0.75 |
| Reliability | — | pass^3 ≥ 0.70 |

**`ScoreAggregator.deploymentGate.deployable == false` 即阻止 release。**

## 设计原则

1. **deterministic 优先** — `DeterministicEvaluator` 不调 LLM，跑得快、可重现。
2. **多 judge panel** — `JudgeClient` 调 1 个 LLM judge；harness 用 3 个不同家族取中位数。
3. **trace 完整** — `TraceRecorder` 通过 `ToolHookRegistry` 捕获每个 tool 调用（含 args 截断到 400 chars，result 到 200 chars，避免 payload 爆炸）。
4. **strict cost/latency cap** — `AgentRunner.run(req, runIdx, maxCostUsd, maxLatencyMs)` 超限时立即终止并返回 error。
5. **category weight 总和 = 1.0** — 测试通过 `ScoreAggregatorTest#categoryWeightsSumToOne` 保证。

## 测试覆盖

- `EvalRequestTest` — 6 个 test（safety/memory 标记、tag null 安全、scoring method 匹配）
- `JudgeClientTest` — 7 个 test（prompt 模板、score 解析、边界）
- `DeterministicEvaluatorTest` — 11 个 test（files/no-destruct/output-contains/equals/tool-called）
- `ScoreAggregatorTest` — 6 个 test（empty/perfect/safety-block/cost-block/weight-sum/category-extract）

合计 **30 个新单元测试**，与 R375 之前的 1270 个合并应仍全绿。

## 集成到 R375 dashboard

参考 R375 的 `evaluation-framework.md §8`，可以扩展 `aethercode-desktop/src-tauri/` 的 dashboard：
- 增加 "Eval" 标签页
- 每次 PR 跑 eval suite，结果展示
- safety 分数 < 0.95 时阻止 merge

## 关联文件

- `D:\research\ai-agent-eval\evaluation-framework.md` — 顶层框架设计
- `D:\research\ai-agent-eval\harness\runner.py` — Python harness (本模块的 client)
- `D:\research\ai-agent-eval\tests\A*_*/` — 测试用例 JSON (4 个样例)
- `D:\research\ai-agent-eval\harness\judges.py` (in runner.py) — 与 JudgeClient 对应的 Python prompt 模板