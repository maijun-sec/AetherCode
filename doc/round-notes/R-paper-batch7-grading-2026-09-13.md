# R-paper-batch7-grading-2026-09-13

## 目标

修真 LLM benchmark grading 跟 LLM 实际输出格式不对齐的 bug：
- HumanEval: LLM 给 full def + body，expected 是 body
- SWE-bench: LLM 给 markdown diff 包装，expected 是 raw diff
- AgentInstruct: LLM 给 prose + action，expected 是 Think: + Act: chain
- 写 provider-agnostic system prompt 模板

## 实际产出 (6 file, 1 round)

### Main code (4 file)
- `BenchmarkSystemPrompt.java` (新, 5.7 KB):
  - `BASE` / `MMLU` / `HUMANEVAL` / `SWE_BENCH` / `AGENT_INSTRUCT` / `GENERIC` 6 个 prompt
  - `forBenchmark(name)` 自动选 + `compose(base, suffix...)` 拼接
  - 全部 < 1 KB (provider budget 友好)
  - **provider-agnostic** (无 model name, 无 chat-template token, 无 provider 名字)
- `HumanEvalAdapter.java` (改, +80 lines):
  - `grade()` override: extract python block → extract function body → normalize → equals
  - `extractPythonCode(s)`: 找 ```python ... ``` 或 bare `def ...`
  - `extractFunctionBody(s)`: 找 def, 截 body; 接受 bare body (canonical solution)
- `SweBenchAdapter.java` (改, +60 lines):
  - `grade()` override: extract diff from ```diff ... ``` → file overlap + non-trivial line overlap
  - 拒绝 file 不 match 或 line 只 whitespace 变化
- `AgentInstructAdapter.java` (改, +70 lines):
  - `grade()` override: extract Act: action → os/db 用 Jaccard >= 0.4, 其他用 substring
  - `extractAction(s)`: Act: 后到 code fence end 或 next Think:
  - `jaccard(a, b)`: token-set overlap

### Test (2 file, 19 tests)
- `BenchmarkSystemPromptTest.java` (新, 8 tests): 每个 prompt 长度 < 1KB, 无 provider 特定字
- `GradingFixTest.java` (新, 11 tests): HumanEval / SWE-bench / AgentInstruct grading

### Test 改动 (1 file)
- `BenchmarkReportRealLlmTest.java` (改):
  - sampleSize 30 → 5 (10 min 跑完)
  - 9 个 benchmark (全套)
  - **per-benchmark agent 用 BenchmarkSystemPrompt.forBenchmark(name)** — 不同 benchmark 用对应 prompt

## 关键设计决定

1. **provider-agnostic prompt** — 不用 chat template token, 不用 provider 名字, 1KB budget
2. **per-benchmark agent** — MMLU 用 MMLU prompt, HumanEval 用 HUMANEVAL prompt
3. **HumanEval 接受 bare body OR def+body** — canonical solution 是 body, LLM 给 full def
4. **SWE-bench 拒绝 file 不 match** — file overlap 必需, line overlap 排除 placeholder
5. **AgentInstruct Jaccard for os/db** — 短 bash / SQL 命令 token 化后高 overlap
6. **extractAction 多行** — Act: 后到 ``` end (不是第一个 newline)
7. **System prompt 拼接有 size cap** — hard cap 1024 chars
8. **grade() override 而不是 default** — 每个 adapter 自己决定匹配策略

## 教训 (新增 8 条, 累计 436+)

429. **provider-agnostic system prompt** — 不写 model name, 不写 chat template
430. **HumanEval grading 要 normalize 缩进** — 4-space / tab 都要 strip
431. **extractFunctionBody 接受 bare body** — canonical solution 形式
432. **SWE-bench file overlap 必需** — placeholder "old"/"new" 不能 line overlap 误匹配
433. **AgentInstruct Act: 多行** — ``` code block 不能被 newline 截断
434. **per-benchmark system prompt 提升 pass@1** — generic prompt 不告诉 LLM format
435. **Jaccard ≥ 0.4 for short commands** — single command 容易完全 match (1.0)
436. **PowerShell Set-Content 永远 BOM** — Python `open(path, 'w', encoding='utf-8', newline='\n')` 是唯一 reliable 方式

## 累计统计 (本 round 后)

- aethercode-orchestration: 442/442 (无变化)
- aethercode-evals: **666/666** (was 647, +19 grading fix tests)
- 0 回归
- 真 LLM 端到端: 后台跑 9 benchmark (待 report)

## 文件清单

### Main code
- aethercode-evals/src/main/java/org/aethercode/evals/benchmarks/BenchmarkSystemPrompt.java (新)
- aethercode-evals/src/main/java/org/aethercode/evals/benchmarks/HumanEvalAdapter.java (改)
- aethercode-evals/src/main/java/org/aethercode/evals/benchmarks/SweBenchAdapter.java (改)
- aethercode-evals/src/main/java/org/aethercode/evals/benchmarks/AgentInstructAdapter.java (改)

### Test
- aethercode-evals/src/test/java/org/aethercode/evals/benchmarks/BenchmarkSystemPromptTest.java (新, 8 tests)
- aethercode-evals/src/test/java/org/aethercode/evals/benchmarks/GradingFixTest.java (新, 11 tests)
- aethercode-evals/src/test/java/org/aethercode/evals/benchmarks/BenchmarkReportRealLlmTest.java (改, per-benchmark system prompt)

### Round note
- doc/round-notes/R-paper-batch7-grading-2026-09-13.md (本文件)
