# R-orch-2 DeepAgentsSystem Runtime 集成 (2026-09-12)

## 触发

R-orch-1 把 V (verify) / self-correct / ensemble 三个 round 集成进
AgentRuntime. R-orch-2 把 AgentRuntime 串进 DeepAgentsSystem.respond()
入口 — 真正用上, 不只是 framework class.

之前 respond() 直接 `DeepAgentFactory.invoke()` 拿 action, 没有 verify,
没有 self-correct, 没有 ensemble. R-orch-2 加一个 optional AgentRuntime
字段, 配了之后 respond() 自动走 V → self-correct → ensemble 流程.

## 实际产出 (1 main 改 + 1 test 改, 1 round)

- `clbench/system/DeepAgentsSystem.java` 改:
  - 加 `AgentRuntime<Object> agentRuntime` 字段 (默认 null)
  - 加 `setAgentRuntime(AgentRuntime<Object> r)` / `agentRuntime()` 方法
  - `respond(Query)` 拿 action 后, 如果 runtime 配了, 调 `runtime.run(action)`
    → `RuntimeResult<Object>`, 用 result.action() 作为最终 action
  - response metadata 加 3 个 runtime 字段:
    - `runtime`: RuntimeResult.summary() map
    - `runtime_outcome`: "passed" / "self-corrected" / "ensemble-passed" / etc
    - `runtime_passed`: Boolean
  - 行为兼容: 不配 runtime 时 (default), 走原 bare-agent path, byte-compatible
- `clbench/system/DeepAgentsSystemTest.java` 改 (17 → 23 tests, 6 new):
  - `agentRuntimeDefaultsToNull`: 默认 null, 不破坏旧调用方
  - `setAgentRuntimeRoundtripsTheReference`: setter 引用对等
  - `respondWithoutRuntimeIsBackwardCompatible`: 不配 runtime 时 metadata
    没有 runtime_* keys
  - `respondWithPassingRuntimeExposesPassedMetadata`: 配 passing runtime,
    outcome="passed", runtime_passed=true
  - `respondWithFailingRuntimePreservesOriginalAction`: failing runtime
    仍 return non-null action (clbench loop 仍能 score)
  - `setAgentRuntimeNullRestoresBarePath`: set null 后 metadata 恢复 bare
- **aethercode-evals: 362/362 Java tests pass** (356 + 6 new), 0 回归

## 关键技术决定 (6 条)

1. **Optional runtime, 默认 null** - 不强制每个 DeepAgentsSystem 配 runtime,
   现有调用方零改动
2. **Bare path 保持 byte-compatible** - metadata 不加 runtime_* keys, 旧
   测试/调用方逻辑不破
3. **T = Object** - structured_response 类型在编译期是 Object (实际是 schema
   class), 泛型用 Object
4. **RuntimeResult 走 summary()** - 复用 R-orch-1 的 summary() 给 audit log
5. **metadata 暴露 outcome / passed / summary 三层** - outcome 给快速判断,
   passed 给 boolean, summary 给完整 trace
6. **Failing runtime 不抛** - clbench loop 仍要 score, 失败时返回 last
   attempted action, 让上层决定怎么处理

## API shape

```java
// 旧调用方 (byte-compatible)
DeepAgentsSystem sys = new DeepAgentsSystem();
Response r = sys.respond(query);  // 走 bare agent

// 新调用方 (R-orch-2)
Verifier<Object> schemaCheck = ...;
SelfCorrectionLoop<Object> sc = ...;
MultiAgentOrchestrator<Object> ensemble = ...;
AgentRuntime<Object> rt = AgentRuntime.<Object>builder()
    .name("clbench-runtime")
    .verifier(schemaCheck)
    .selfCorrect(sc)
    .ensemble(ensemble)
    .build();
sys.setAgentRuntime(rt);
Response r = sys.respond(query);
// r.metadata().get("runtime_outcome") = "passed" / "self-corrected" / ...
// r.metadata().get("runtime_passed") = true / false
// r.metadata().get("runtime") = { outcome, passed, attempts, self_correct_attempts, ensemble_consensus }
```

## Runtime integration flow

```
respond(query):
  prompt = compose(query, feedback)
  agent = getAgent(schema)
  result = invoke(agent, prompt, files)         # raw deep agent
  action = structuredResponse(result)
  if agentRuntime != null:
    runtimeResult = agentRuntime.run(action)    # V → self-correct → ensemble
    action = runtimeResult.action()
  metadata = {system, model, interaction, memory_files, runtime*, ...}
  return Response(action, metadata)
```

## Backward compatibility 验证

| 调用方 | 修前 | 修后 |
|---|---|---|
| `new DeepAgentsSystem()` (无 runtime) | 走 bare agent | 走 bare agent (byte-compatible) |
| `respond(q).action()` | 拿 raw action | 拿 raw action (无 runtime) / runtime 后的 action (有 runtime) |
| `respond(q).metadata()` | 4 keys | 4 keys (无 runtime) / 7 keys (有 runtime) |

## 累计测试 (R-orch-2 后)

- aethercode-deepagents: 253/253 Java
- aethercode-talon: 5/5 Java
- aethercode-a2a: 33/33 Java
- aethercode-a2a-deepagent-bridge: 8/8 Java
- aethercode-cli: 48/48 Java
- aethercode-tools vision: 25/25 Java
- aethercode-workflows: 63/63 Java
- **aethercode-evals: 362/362 Java** (356 R-bugfix-1 后 + 6 new)
- aethercode-tools: 322/324 (2 pre-existing)
- aethercode-desktop (TS): 1042/1042 pass
- aethercode-desktop (Rust): 14/14 pass
- **0 回归**

## 教训 (新增 6 条, 累计 214+)

209. **Optional 集成 vs 强制集成** - 加新能力时给旧调用方留 byte-compatible
     path, 用 setter 而不是 constructor 注入
210. **T = Object 兼容 schema** - structuredResponse 是 Object, AgentRuntime
     泛型 Object, 编译通过
211. **Metadata 暴露 3 层** - outcome (string) / passed (boolean) / summary
     (map), 不同调用方取不同层级
212. **Failing runtime 不抛** - clbench loop 仍要 score, 失败时 last action
     仍可观察
213. **set null 恢复** - setAgentRuntime(null) 必须能恢复 bare path,
     测试验证
214. **集成测试用 passing/failing verifier** - 不需要 mock 真 multi-agent,
     简单 Verifier<Object> 跑通流程

## 后续

- R-perf-1: cost ceiling + early-exit + cache
- R-mod-1: 抽 `aethercode-orchestration` 独立 module
- R-orch-3: 跟 `reference/papers/` 8 篇 paper 一起跑 end-to-end
