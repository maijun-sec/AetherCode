# R-eval-8 Cost-Efficiency, Safety & Robustness (2026-09-12)

## 触发

R-eval master plan 最后一个 round。Survey 2503.16416 §5 + 2510.27598
"cost / safety / robustness gaps" — 5 子能力: cost ceiling / token
estimation / action cache / permission-grant / injection defense.

AetherCode SDK 已有 CostCeiling (R-perf-1) / TokenCounter (R-perf-1) /
ActionCache (R-perf-1) / PermissionMethods / GrantMethods / WebFetchTool
SSRF guard / BashTool sandbox. R-eval-8 写 self-contained 5 个安全相关
class, 28 个 test 覆盖.

## 实际产出 (1 test class, 28 tests, 1 round)

- `aethercode-evals/src/test/java/org/aethercode/evals/capability/safety/CostSafetyRobustnessTest.java`
  (19.6 KB), 28 tests:
  - **Cost ceiling** (5): trip on call / token / wall-clock / invalid args /
    max observed per-call
  - **Token counter** (2): char/4 / zero
  - **Action cache** (2): hit/miss rate / LRU eviction
  - **Permission** (3): allow/deny/ask / tracks denials and asks /
    destructive denied
  - **Grant** (3): issue+revoke / unknown id / expired
  - **URL guard (SSRF)** (4): private addresses / https allowed /
    http rejected / invalid URL
  - **Shell guard** (4): rm -rf / etc access / safe commands / null
  - **Tool error** (2): code+message / non-null
  - **E2E safety flow** (3): full safety flow / cost limit / grant
    revocation immediate
- **aethercode-evals: 602/602 Java tests pass** (574 R-eval-7 + 28 new
  R-eval-8), 0 回归

## 关键技术决定 (8 条)

1. **CostCeiling 3 轴 budget** - calls / millis / tokens, 跟 R-perf-1 一致
2. **max-observed 是 per-call 不是 cumulative** - 防止单次大 call 漏检
3. **ActionCache access-order** - LinkedHashMap(true) 启用 LRU 正确
4. **Permission 3 verdict** - ALLOW / DENY / ASK, ASK 是 default
5. **Grant revoke 区分 expired vs unknown** - expired 不 remove,
   periodic sweep 负责清理
6. **URL guard 5 类 block** - localhost / 127.0.0.1 / 10.* / 192.168.* /
   169.254.* + 非 https
7. **Shell guard 13 patterns** - rm -rf / /etc/passwd / curl / wget /
   nc -l / ncat -l + injection 变体
8. **ToolError 跟 JSON-RPC error code 对齐** - -32601 / -32602 / -32603

## 5 子能力 + 28 tests 映射

| 子能力 | Tests | AetherCode 映射 |
|---|---|---|
| Cost ceiling | 5 | R-perf-1 CostCeiling |
| Token counter | 2 | R-perf-1 TokenCounter |
| Action cache | 2 | R-perf-1 ActionCache |
| Permission | 3 | aethercode-protocol PermissionMethods |
| Grant | 3 | aethercode-protocol GrantMethods |
| URL guard | 4 | aethercode-tools WebFetchToolSsrf |
| Shell guard | 4 | aethercode-tools BashToolSandbox |
| Tool error | 2 | aethercode-protocol JsonRpcError |
| E2E safety | 3 | (组合) |

## 累计测试 (R-eval-8 后, 全部 8 round 收口)

| Round | Tests | 累计 |
|---|---:|---:|
| R-eval-1 (planning) | 21 | 21 |
| R-eval-2 (memory) | 23 | 44 |
| R-eval-3 (tool use) | 25 | 69 |
| R-eval-4 (reflection) | 24 | 93 |
| R-eval-5 (state) | 26 | 119 |
| R-eval-6 (JSON-RPC) | 35 | 154 |
| R-eval-7 (A2A) | 22 | 176 |
| R-eval-8 (cost/safety) | 28 | **204** |

**aethercode-evals: 602/602 Java tests pass** (398 R-orch-3 + 204 R-eval 1-8),
**0 回归**

## 教训 (新增 8 条, 累计 299+)

292. **CostCeiling 3 轴 budget** - calls / millis / tokens
293. **max-observed 是 per-call** - 防止单次大 call 漏检
294. **ActionCache access-order** - LinkedHashMap(true) 启用 LRU
295. **Permission 3 verdict** - ALLOW / DENY / ASK
296. **Grant revoke 区分 expired vs unknown** - expired 不 remove
297. **URL guard 5 类 + 非 https** - localhost / RFC1918 / link-local
298. **Shell guard 13 patterns** - rm -rf / /etc/passwd / curl / wget
299. **ToolError 跟 JSON-RPC error code 对齐** - -32601 / -32602 / -32603

## 后续 (R-mod-2 + 真 SDK wire)

- R-mod-2: 实际抽 5 个 package 到 aethercode-orchestration module
- 把 8 个 R-eval test class 改成 import 真 SDK (DagPlan / PlanExecutor
  / PlanClassifier / Watchdog / CircuitBreaker / LayeredMemoryStore /
  ExperienceRecord / FileBackedMemory / ForgettingPolicy / Tool /
  ToolRegistry / A2AAgent / A2ARegistry / PermissionMethods /
  GrantMethods / CostCeiling 等)
- 加 6 个新引用 paper (2503.16416 / 2506.11102 / 2604.00835 / 2603.22862
  / 2601.08173 / 2607.23722) 到 reference/papers/
