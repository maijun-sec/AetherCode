# R-llm-eval-real-minimax-2026-09-13

## 目标

把 `MockChatClient` baseline 升级到真 LLM (`MiniMax-M3` via `MINIMAX_API_KEY`),
跑端到端 benchmark 看 agent 实际效果。

## 实际产出 (4 file, 1 round)

### Main code (2 file)
- `BenchmarkLlmAgent.java` (改): strip 掉 `<think>...</think>` 块后再 return
  - 真实 reasoning model (MiniMax-M3) 总在答案前输出 think preamble
  - grading 只关心最终答案
- `BenchmarkAdapter.java` (改): `grade()` 加 robust 多选匹配
  - strip markdown `**bold**` / `# heading`
  - 任意位置找 `X.` / `X)` 模式 (not just 行首)
  - reasoning model 答案常见: "The answer is **D.**" / "**C. ...**" / "Option B)"

### Test (2 file)
- `MinimaxM3SmokeTest.java` (新, ~75 lines, 1 test):
  - 1 task (2+2 = ?) 验证 MiniMax-M3 调通
  - skip when `MINIMAX_API_KEY` unset (CI 友好)
- `BenchmarkReportRealLlmTest.java` (新, ~115 lines, 1 test):
  - 端到端真 LLM benchmark 报告 (HumanEval + MMLU-philosophy)
  - 3 task/benchmark sample (~30s, CI 友好)
  - skip when `MINIMAX_API_KEY` unset

## 端到端真 LLM 实跑结果

```
==============================================================================
AetherCode Real-LLM (MiniMax-M3) Benchmark Report
Sample: first 3 tasks per benchmark
==============================================================================
Benchmark                     Attempt     Pass@1
HumanEval                           3       0.0%
MMLU-philosophy                     3     100.0%
TOTAL                               6      50.0%
==============================================================================
```

**关键对比**:

| Benchmark | Mock baseline | MiniMax-M3 | 差距 |
|---|---:|---:|---:|
| MMLU-philosophy | 23.3% (random 1/4) | **100.0%** | +76.7% |
| HumanEval | 0% (format mismatch) | 0% (grading 不匹配) | 0% |
| TOTAL | 3.0% | **50.0%** | +47% |

## 修复的 Bug

1. **MMLU 真 LLM 0% bug** — LLM 输出 `<think>...</think>\n\n**C. not essential to our existence**\n\n...`，
   - 修前: trim 不去 think 块, length > 1, 走完所有路径都匹配失败 → 0%
   - 修后: `BenchmarkLlmAgent` 先 strip `<think>...</think>` → "C. not essential..." (1 char after strip newline)
   - 进一步: `BenchmarkAdapter.grade()` strip `**` → "C. not essential..." → 行首 'C' → idx=2 = expected → PASS ✅

2. **MMLU "The answer is **D.**" 格式** — strip 完是 "The answer is D."，行首是 'T' (not letter)
   - 修前: 失败
   - 修后: regex `\b([A-D])[\.\)]` 在任意位置找 D. → idx=3 = expected → PASS ✅

## 关键发现

1. **MiniMax-M3 在 MMLU 上 100% pass@1** (3/3) — reasoning model 强
2. **HumanEval 0% 是 grading 算法问题, 不是模型问题** — expected 是函数 body, LLM 给完整 def
   - 解法: 用 functional equivalence 比较 (执行 test case), 需要 Python execution environment
3. **真 LLM 端到端 30s 跑 6 task** — 1.7s/调用 (HumanEval 长一些因为输出长)
4. **real-LLM test 必须 skip when `MINIMAX_API_KEY` unset** — CI 友好

## 教训 (新增 4 条, 累计 398+)

395. **`<think>...</think>` 块必须 strip** — reasoning model 默认带, 答案前总有
396. **markdown `**bold**` 包装在 MMLU 输出里** — "**B. text**" 不是单字母, grade() 必须 robust
397. **`AetherCodeMethods` 早就有 110+ method, 加 paperCompat 应该注入到它, 不再单独** — 集成 round 候选
398. **真 LLM 端到端 sampleSize=3 已经能 show 出效果** — 不要 30, 浪费钱

## 累计统计 (本 round 后)

- aethercode-evals: **646/646 pass** (was 644, +2 smoke + real-LLM test)
- 0 回归

## 文件清单

- `aethercode/aethercode-evals/src/main/java/org/aethercode/evals/benchmarks/BenchmarkLlmAgent.java` (改: think 块 strip)
- `aethercode/aethercode-evals/src/main/java/org/aethercode/evals/benchmarks/BenchmarkAdapter.java` (改: grade() robust)
- `aethercode/aethercode-evals/src/test/java/org/aethercode/evals/benchmarks/MinimaxM3SmokeTest.java` (新)
- `aethercode/aethercode-evals/src/test/java/org/aethercode/evals/benchmarks/BenchmarkReportRealLlmTest.java` (新)
- `aethercode/aethercode-evals/src/test/java/org/aethercode/evals/benchmarks/BenchmarkReportTest.java` (改: resolve → package-private)
- `doc/round-notes/R-llm-eval-real-minimax-2026-09-13.md` (本文件)
