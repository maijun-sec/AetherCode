# R-eval-7 A2A Multi-Agent (2026-09-12)

## 触发

R-eval master plan 第 7 round。Survey 2601.01743 §III.1.1 + 2508.17281 §6
+ A2A protocol v0.3 — multi-agent 协作是 agent 核心能力, 5 子能力:
agent card / 消息路由 / SSE streaming / ensemble / cross-process bridge.

AetherCode 已有 aethercode-a2a (A2A protocol server) +
aethercode-a2a-deepagent-bridge (桥接 deepagent runtime). R-eval-7
写 self-contained AgentCard / A2ATask / A2AAgent / A2ARegistry /
A2AStreamingClient / Ensemble / A2ABridge, 22 个 test 覆盖 5 子能力.

## 实际产出 (1 test class, 22 tests, 1 round)

- `aethercode-evals/src/test/java/org/aethercode/evals/capability/multiagent/A2AMultiAgentTest.java`
  (22 KB), 22 tests:
  - **Agent card** (2): advertises skills / rejects blank name
  - **Routing** (4): by exact name / skill mismatch / skill fallback /
    unknown agent
  - **Registry** (2): duplicate rejected / processes task history
  - **SSE streaming** (2): emits progress events / rejects invalid
    percent
  - **Ensemble** (3): picks highest / crashing proposer / multiple
    judges aggregate
  - **Bridge** (3): sends/receives / propagates errors / in-process
    transport
  - **Multi-agent scenarios** (2): planner delegates / many parallel
  - **Validation** (3): blank target / id auto / version optional
  - **E2E** (1): planner → multi-agent chain
- **aethercode-evals: 574/574 Java tests pass** (552 R-eval-6 + 22 new
  R-eval-7), 0 回归

## 关键技术决定 (8 条)

1. **A2A protocol v0.3 1:1** - AgentCard / A2ATask / A2AResult /
   A2AHandler / A2ARegistry 跟 aethercode-a2a schema 一致
2. **Routing 双策略** - exact agent name 优先, skill fallback 次之
3. **Skill mismatch 返错误** - agent 没有对应 skill, 不静默 fallback
4. **SSE 4 阶段 progress** - 25% / 50% / 75% / 100%, 跟 A2A 实际
   streaming 行为对齐
5. **Ensemble crash containment** - proposer 抛异常 → null proposal,
   ensemble 继续
6. **Bridge pluggable Transport** - in-process channel 测, 实际
   aethercode-a2a-deepagent-bridge 用 WebSocket / HTTP
7. **Many agents 并行** - CompletableFuture.supplyAsync + allOf
8. **A2ATask id auto-assign** - 跟 JSON-RPC 2.0 一致, 缺省 UUID

## 5 子能力 + 22 tests 映射

| 子能力 | Tests | AetherCode 映射 |
|---|---|---|
| Agent card | 2 | aethercode-a2a AgentCard |
| Routing | 4 | aethercode-a2a Registry |
| SSE streaming | 2 | aethercode-a2a StreamingClient |
| Ensemble | 3 | aethercode-evals.multiagent (R-radar-8) |
| Cross-process bridge | 3 | aethercode-a2a-deepagent-bridge |
| Multi-agent scenarios | 2 | (组合) |
| Validation | 3 | (sanity) |
| E2E | 1 | (组合) |

## API shape (A2A)

```java
A2ARegistry r = new A2ARegistry()
    .register(new A2AAgent(
        new AgentCard("search", "search the web", List.of("search"), "1.0"),
        task -> List.of("https://a.com")));

A2ATask task = new A2ATask("t1", "search", "search", Map.of("query", "agent eval"));
A2AResult result = r.route(task);

A2AStreamingClient client = new A2AStreamingClient(r);
client.runWithProgress(task, ev -> System.out.println(ev.percent() + "%"));
```

## 累计测试 (R-eval-7 后)

- aethercode-orchestration: 0
- aethercode-evals: **574/574** (552 R-eval-6 + 22 R-eval-7)
- 其他不变
- 0 回归

## 教训 (新增 8 条, 累计 291+)

284. **A2A protocol v0.3 1:1** - AgentCard / A2ATask / A2AResult /
     A2AHandler / A2ARegistry
285. **Routing 双策略** - exact name → skill fallback
286. **Skill mismatch 返错误** - 不静默 fallback
287. **SSE 4 阶段 progress** - 25/50/75/100 跟 A2A streaming 对齐
288. **Ensemble crash containment** - proposer 异常 → null, 继续
289. **Bridge pluggable Transport** - in-process / WebSocket / HTTP
290. **Many agents 并行** - CompletableFuture.allOf
291. **A2ATask id auto-assign** - 跟 JSON-RPC 2.0 一致

## 后续

- R-eval-8: Cost-Efficiency, Safety & Robustness
- R-mod-2: 实际抽 orchestration 到 aethercode-orchestration module
