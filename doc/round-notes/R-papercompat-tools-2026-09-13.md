# R-papercompat-tools-2026-09-13

## 目标

把 8 个 paperCompat RPC method 包装成 model-callable `Tool`, 让
`AetherCodeEngine.query(prompt)` 流程的 LLM tool loop 真正能调用 paper-compat
能力, 不再依赖前端 out-of-band RPC.

## 实际产出 (3 file, 1 round)

### Main code (2 file)
- `aethercode-orchestration/pom.xml` (改): 加 Jackson 依赖 (parse JSON-string args)
- `orchestration/papercompat/PaperCompatTools.java` (~270 lines):
  - 8 个 tool builder (1:1 映射 PaperCompatRpc 8 个 RPC)
  - 命名规范: `paper_compat_xxx_yyy` (snake_case 给 LLM tool dispatch)
  - `wrapAsReadOnly` 包装: paper-compat tool 都是纯计算, isReadOnly=true 跳过 permission prompt
  - JSON-string 解析: `parseList(input, "samples")` 用 Jackson 把 string 转 List<Object> 再喂给 RPC
  - 完整 JavaDoc 说明 8 个 tool 用途 + 命名 + 输入/输出 schema

### Test (1 file, 12 tests)
- `PaperCompatToolsTest.java` (12 tests, 0 regression):
  - `buildAllReturnsEightTools` - 8 个 tool 名字稳定
  - `everyToolHasNameDescriptionAndSchema` - 全部 tool 有 schema
  - `everyToolIsConcurrencySafeAndReadOnly` - paper-compat 都是只读 + 并发安全
  - 8 个 tool 各自 1 个功能性 test (architecture / saturation / redflag / byzantine / voting / plan)

## 集成设计

### 8 个 Tool
| Tool 名字 | 映射 RPC | 用途 |
|---|---|---|
| `paper_compat_architecture_recommend` | `paperCompat.architecture.recommend` | TaskFeatures → 5 种 architecture |
| `paper_compat_saturation_assess` | `paperCompat.saturation.assess` | 评估单 agent 是否饱和 |
| `paper_compat_redflag_inspect` | `paperCompat.redflag.inspect` | MAKER 5 red flag 检测 |
| `paper_compat_byzantine_observe` | `paperCompat.byzantine.observe` | BlockA2A 5 规则 |
| `paper_compat_byzantine_flagged` | `paperCompat.byzantine.flagged` | 当前被 flag 的 agent list |
| `paper_compat_byzantine_reset` | `paperCompat.byzantine.reset` | 清除状态 |
| `paper_compat_voting_first_to_ahead_by_k` | `paperCompat.voting.firstToAheadByK` | MAKER first-to-ahead-by-k voting |
| `paper_compat_plan_execute_sequence` | `paperCompat.plan.executeSequence` | GlobalPlan + HierarchicalExecutor |

### JSON-string 参数约定
3 个 tool 接受 list 参数 (samples / skills / runs)。OpenAI function-calling
schema 通过 list 参数不一定每个 provider 都干净支持, 所以约定:
- tool input schema 写 `"type": "string"` + description 写明 JSON 数组格式
- 包装层 `parseList(input, key)` 用 Jackson parse string → List<Object>
- 然后传进 PaperCompatRpc method (RPC 协议不变)

### ReadOnly 包装
paper-compat 工具是纯计算 (更新内存 detector, 不动 fs/net/shell).
`wrapAsReadOnly` override `isReadOnly` 返回 true, 跳过 engine permission
prompt. 7 个常量都走这 wrapper.

## 关键设计决定

1. **Tool 命名用 snake_case** - LLM tool dispatch 习惯 (OpenAI / Anthropic)
2. **paperCompat.* RPC 命名保留** - 已注册的 RPC 不能改, 兼容性需要
3. **JSON-string 输入是 OpenAI 通用惯例** - 大部分 provider 的 function calling 都吃 string
4. **isReadOnly=true 跳过权限** - paper-compat 工具是 LLM 自检工具, 没必要打断
5. **ReadOnlyToolWrapper 包装** - 而不是改 ToolDef, 保留 delegate 的所有行为
6. **保留 PaperCompatRpc 协议** - RPC layer 接受 List<Object>, tool layer parse string → List
7. **description 详细写 paper 引用** - LLM 看到就知道 paper ID + 实现细节
8. **isConcurrencySafe 默认 true** - paper-compat 工具都是 stateless 或 per-session 状态

## 教训 (新增 5 条, 累计 414+)

410. **Tool 命名 snake_case** - LLM tool dispatch 习惯, 跟 RPC `paperCompat.*` 区分
411. **OpenAI function calling list param 是 string** - JSON parse 在 wrapper 做
412. **ToolDef 直接调 RPC 改 input format** - wrapper 负责 string → List 转换
413. **paper-compat tool 全部 isReadOnly=true** - 跳过 permission, 不打断 LLM 自检流程
414. **`isReadOnly` 包装层 override** - 不改 ToolDef, 留 delegate 完整行为

## 累计统计 (本 round 后)

- aethercode-orchestration: **394/394 pass** (was 382, +12 PaperCompatToolsTest)
- aethercode-evals: 646/646
- 0 回归

## 下一轮 (R-papercompat-engine-registration)

`PaperCompatTools.buildAll()` 加到 `AetherCodeEngine` 的 tool pool, 这样
`engine.query(prompt)` 真的能在 LLM tool loop 里调用 paperCompat 8 个方法.
具体: `StandardTools` 旁边加 `paperCompatTools`, engine constructor 注入.

## 文件清单

- `aethercode/aethercode-orchestration/pom.xml` (改: Jackson dep)
- `aethercode/aethercode-orchestration/src/main/java/org/aethercode/orchestration/papercompat/PaperCompatTools.java` (新, ~290 lines)
- `aethercode/aethercode-orchestration/src/test/java/org/aethercode/orchestration/papercompat/PaperCompatToolsTest.java` (新, 12 tests)
- `doc/round-notes/R-papercompat-tools-2026-09-13.md` (本文件)
