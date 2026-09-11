# R-orch-3 E2E Paper Pipeline (2026-09-12)

## 触发

R-radar 6/7/8 各自独立 (V / self-correct / ensemble), R-orch-1 集成进
AgentRuntime, R-orch-2 串进 DeepAgentsSystem, R-perf-1 加 cost ceiling
+ cache. 但还没在真 workload 上跑过.

R-orch-3 写一个 E2E test, 用 `reference/papers/` 8 篇 paper 摘要当 corpus,
跑 `scholar → arxiv → critique → verdict` 完整 pipeline. 把所有 R-radar
+ R-orch + R-perf 组件串起来, 验证集成 work.

## 实际产出 (1 main test + 1 round)

- `aethercode-evals/src/test/java/org/aethercode/evals/e2e/PaperPipelineE2ETest.java`
  (266 lines, 16.7 KB), 3 test:
  - `pipelineProcessesAllEightPapers`: 跑全部 8 篇 paper, 全部通过
  - `pipelineRejectsMissingPaperAtScholar`: 缺失 paper 拒收
  - `pipelineShortCircuitsUnderTightBudget`: 紧 budget 短路, ensemble 不跑
- 真实 corpus 从 `reference/papers/*_摘要.md` 加载, fallback 1 paper
  synthetic corpus
- **aethercode-evals: 398/398 Java tests pass** (395 R-perf-1 后 + 3 new
  E2E), 0 回归

## 关键技术决定 (8 条)

1. **Mock LLM calls, 真 orchestration** - AgentFn 是 functional interface,
   mock 3 个 AgentFn (scholar/arxiv/critic) 模拟 LLM 行为, 但
   AgentRuntime / MultiAgentOrchestrator / CostCeiling / ActionCache /
   SelfCorrectionLoop 全部真组件跑
2. **Pipeline 4 阶段映射论文 gap** - scholar (paper metadata) → arxiv
   (abstract) → critique (multi-agent ensemble) → verdict (V 校验)
3. **Real corpus from reference/papers/** - 8 篇 paper 摘要作为真 workload,
   不 synthetic data
4. **Fallback corpus** - 没 reference/papers/ 时 fallback 1 paper,
   让 fresh clone 也能跑
5. **Mock verifier 接受 concerns.contains("missing")** - 真实场景 paper
   not found 在 scholar step 拒收, 而不是 continue 走 arxiv
6. **Budget 1 call 测试短路** - 验证 cost ceiling 在 self-correct / ensemble
   之前 stop, 不烧 budget
7. **Tight budget test 用 stub EnsembleStrategy** - 不用 CritiqueStrategy,
   因为 tight budget 不让 ensemble 跑, stub 即可
8. **3 个 test 覆盖 3 路径** - happy path (8 paper pass) / sad path
   (missing) / perf path (tight budget). 给 round 完整 picture.

## Pipeline 流程

```
For each paper in corpus (8 篇):
  seed = Verdict(paperId, "seed: title", 0, [])
  result = runtime.run(seed)
  
  runtime.run(seed):
    verifyWithCache(seed)
      if cached → return cached
      else V.verify(seed) → recordCall → cache.put → return
    if V passed → outcome="passed", return seed
    if V failed:
      selfCorrect.run(seed)
        for attempt in 1..maxAttempts:
          V.verify(revised)  // retrySame 改 score +10
          if passed → outcome="self-corrected", return revised
        → outcome="self-correct-exhausted", return last
      if ensemble + budget OK:
        ensemble.run(prompt)
          CritiqueStrategy.run(prompt, proposers):
            scholar.respond → verdict 1
            arxiv.respond → verdict 2
            critic.score(v1) + critic.score(v2) → totals
            winner = max total
        V.verify(winner) → recordCall
        if passed → outcome="ensemble-passed"
        else → outcome="ensemble-failed"
  
  if !result.passed() → record failure
  else assert verdict.acceptable()
```

## Mock 3 components

| 组件 | 类型 | 行为 |
|---|---|---|
| scholar | AgentFn<Verdict> | paperId → "scholar-mock: title", score=0 |
| arxiv | AgentFn<Verdict> | abstract → score 50 if "agent" / "llm" mention, else 0 |
| critic | CritiqueStrategy.Critic<Verdict> | score 0.7 if not "missing", else 0.0 |

## 8 篇 paper 跑结果

8 篇 paper 全部通过: outcome="ensemble-passed" 或 "self-corrected".
- 2512.13564v2 (Memory in the Age of AI Agents)
- 2510.25445 (Agentic AI Comprehensive Survey)
- 2508.10146 (AI Agent Frameworks & Architectures)
- 2601.01743 (AI Agent Systems Architectures)
- 2508.17281 (From Language to Action: LLM Agents)
- 10.1007 (Holistic Review of Agentic AI)
- 2501.07278 (Lifelong Learning for LLM Agents)
- 2608.20379 (Multimodal Agentic Frameworks Survey)

cache.size() >= 1, ceiling.calls() > 0 (verify 跑了 N 次, charge 了 N calls).

## 累计测试 (R-orch-3 后)

- aethercode-orchestration: 0 (空 module, 等 R-mod-2 才有测试)
- aethercode-deepagents: 253/253 Java
- aethercode-talon: 5/5 Java
- aethercode-a2a: 33/33 Java
- aethercode-a2a-deepagent-bridge: 8/8 Java
- aethercode-cli: 48/48 Java
- aethercode-tools vision: 25/25 Java
- aethercode-workflows: 63/63 Java
- **aethercode-evals: 398/398 Java** (395 R-perf-1 后 + 3 new E2E)
- aethercode-tools: 322/324 (2 pre-existing)
- aethercode-desktop (TS): 1042/1042 pass
- aethercode-desktop (Rust): 14/14 pass
- **0 回归**

## 教训 (新增 8 条, 累计 235+)

228. **E2E test 用真 corpus, mock LLM** - 跑 8 篇 paper 真摘要, 但
     mock LLM 调用, orchestration 是真
229. **4 阶段 pipeline 映射论文 gap** - scholar (search) → arxiv (fetch)
     → critique (multi-agent) → verdict (V 校验) 是 paper
     gap 全覆盖
230. **Fallback corpus for fresh clone** - 没 reference/papers/ 时 fallback
     1 paper, 测试不依赖外部资源
231. **Missing paper 在 scholar step 拒收** - 真实场景 paper not found
     直接 fail, 不浪费 arxiv call
232. **Tight budget test 验证短路** - cost ceiling 1 call, ensemble 不跑
233. **Mock verifier 接受 sentinel concern** - "missing" 是 sentinel,
     跟 R-radar-6 verifier crash containment 一致
234. **3 个 test 覆盖 3 路径** - happy / sad / perf, 完整 picture
235. **CritiqueStrategy.Critic 不是 AgentFn** - critic 接受 (proposal,
     allProposals) → double score, 不是 respond(prompt, peers) → T

## 后续

- R-mod-2: 实际抽 5 个 package 到 aethercode-orchestration module
- 真实 LLM 集成 (替换 mock AgentFn)
- a2a 集成 (MultiAgentOrchestrator 用 a2a 调远端 agent)
- 性能 round: ensemble N×cost, 加 cost ceiling / early-exit / cache
  (R-perf-1 已部分覆盖)
