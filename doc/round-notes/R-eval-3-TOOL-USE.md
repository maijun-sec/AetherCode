# R-eval-3 Tool Use & Function Calling (2026-09-12)

## 触发

R-eval master plan 第 3 round。Survey 2503.16416 §2.2 + 2604.00835
Table 4 + 2603.22862 (Long-Horizon Multi-Tool) + 2607.23722 (Multi-Step
Tool-Use) + 2608.04719 (Canary Tools) 都强调 tool use 是 agent 核心能力,
5 子能力: intent recognition / function selection / parameter mapping /
multi-tool orchestration / sandboxing.

AetherCode `aethercode-tools` module 有 file/edit/shell/lsp/net/vision/plugin/task
8 大类 30+ tools. R-eval-3 写一个 self-contained `Tool` / `ToolRegistry` /
`Param` model, 25 个 test 覆盖 5 子能力 + 长 context + 并发 + 容错.

## 实际产出 (1 test class, 25 tests, 1 round)

- `aethercode-evals/src/test/java/org/aethercode/evals/capability/tooluse/ToolUseCapabilityTest.java`
  (28 KB), 25 tests:
  - **Intent recognition** (2): tool needed / unknown tool error
  - **Function selection** (2): pick from catalog / case-sensitive
  - **Parameter mapping** (3): required / optional / type coercion
  - **Multi-tool orchestration** (2): chain / parallel + dependency
  - **SSRF / sandbox** (4): block private network / allow public /
    write path allow / read path allow
  - **Long-context** (2): 1 MB string / unicode
  - **Error recovery** (3): handler exception / unknown name /
    duplicate registration
  - **Capability classification** (1): read vs mutating tool
  - **Long-horizon** (1): 10 步 chain
  - **Concurrency** (1): parallel invocations independent
  - **Output shape** (1): generic Object payload
  - **Validation** (2): blank name / blank param
  - **E2E plan** (1): 4-tool search→fetch→summarise→write
- **aethercode-evals: 467/467 Java tests pass** (442 R-eval-2 + 25 new
  R-eval-3), 0 回归

## 关键技术决定 (8 条)

1. **BFCL 风格 Tool model** - name / description / params / handler,
   跟 Berkeley Function Calling Leaderboard test 一致
2. **ToolRegistry 单点 invoke** - validate required / SSRF / path,
   然后跑 handler, 返 ToolResult (ok + payload 或 error)
3. **SSRF 默认 block private** - localhost / 127.0.0.1 / 10.* /
   192.168.* / 169.254.*, 跟 aethercode-tools WebFetchTool 一致
4. **Path allow list 区分 read / write** - read_file 必须 allowReadPath,
   write_file 必须 allowWritePath, 跟 R-eval-2 跨 session 隔离思路一致
5. **Type coercion** - string "1" 自动 parse 成 int 1, 跟 LLM 输出
   可能返回 string-encoded int 的现实对齐
6. **Handler 异常包成结构化 error** - 保留 exception class name
   + message, agent 拿到能 retry
7. **Long-context 1 MB string 测** - 防止 truncate / 编码错
8. **Generic Object payload** - 不同 tool 返 string / int / list,
   不强制类型

## 5 子能力 + 25 tests 映射

| 子能力 (paper) | Tests | 论文 |
|---|---|---|
| Intent recognition | 2 | 2608.04719 canary tools |
| Function selection | 2 | 2503.16416 §2.2 |
| Parameter mapping | 3 | 2604.00835 ToolSandbox |
| Multi-tool orchestration | 2 | 2603.22862 long-horizon |
| SSRF / sandbox | 4 | 2503.16416 §2.2 + aethercode-tools |
| Long-context | 2 | 2607.23722 multi-step |
| Error recovery | 3 | 2503.16416 §2.2 |
| Capability classification | 1 | (sanity) |
| Long-horizon chain | 1 | 2603.22862 |
| Concurrency | 1 | 2503.16416 §2.2 |
| Output shape | 1 | (sanity) |
| Validation | 2 | (sanity) |
| E2E plan | 1 | 2503.16416 §2.2 + 2603.22862 |

## API shape (Tool + ToolRegistry)

```java
ToolRegistry reg = new ToolRegistry()
    .blockHost("internal.example.com")
    .allowReadPath("/tmp/")
    .allowWritePath("/tmp/");

reg.register(new Tool("get_weather",
    "Look up the current weather for a city.",
    List.of(new Param("city", "string", true, "City name.")),
    args -> "Sunny, 25C in " + args.get("city")));

ToolResult r = reg.invoke("get_weather", Map.of("city", "Tokyo"));
if (r.ok()) {
    System.out.println(r.payload());
} else {
    System.out.println("error: " + r.error());
}
```

## 累计测试 (R-eval-3 后)

- aethercode-orchestration: 0
- aethercode-evals: **467/467** (442 R-eval-2 + 25 R-eval-3)
- 其他不变
- 0 回归

## 教训 (新增 8 条, 累计 259+)

252. **BFCL 风格 Tool model** - name/description/params/handler, 跟
     Berkeley Function Calling Leaderboard test 一致
253. **ToolRegistry 单点 invoke** - validate / sandbox / handler 集中
254. **SSRF 默认 block private** - localhost / RFC1918 / link-local
255. **Path allow list 区分 read/write** - 写文件比读文件更严
256. **Type coercion** - string "1" parse 成 int 1, 跟 LLM 输出对齐
257. **Handler 异常包成结构化 error** - 保留 class name + message
258. **Long-context 1 MB string 测** - 防止 truncate / 编码错
259. **Generic Object payload** - 不同 tool 返不同类型

## 后续

- R-eval-4: Self-Reflection & Self-Correction (SelfCorrectionLoop)
- R-eval-5: State Tracking & Causal Reasoning
- R-eval-6: AetherCode JSON-RPC Interface
- R-eval-7: A2A Multi-Agent
- R-eval-8: Cost-Efficiency, Safety & Robustness
