# R-papercompat-engine-2026-09-13

## 目标

把 8 个 paper-compat tool 真正装到 AetherCodeEngine 的 query() 流程里,
让 LLM tool loop 能在前端发起的 query 业务中直接调用 paper-compat 能力.

## 实际产出 (3 file, 1 round)

### Main code (2 file)
- `aethercode-cli/src/main/java/org/aethercode/cli/Main.java` (改, +5/-1):
  - `buildEngineForSession` 在 `StandardTools.all()` 之外额外加
    `new PaperCompatTools().buildAll()` (8 个 tool)
  - 在 aethercode-cli 拼接而不是 aethercode-tools.StandardTools 里,
    避免 module cycle (tools → orchestration → protocol → sdk → tools)
- `aethercode-tools/src/main/java/org/aethercode/tools/StandardTools.java` (改):
  - JavaDoc 加 "Paper-compat tools 8 个不在这里" 说明 + cycle 原因
  - `all()` 保持 18 个标准 tool, 不变

### Test (1 file, 3 tests)
- `aethercode-cli/src/test/java/org/aethercode/cli/MainBuildEnginePaperCompatToolsTest.java`:
  - `daemonToolPoolIncludesAllPaperCompatTools` - 8 个 tool 都在 + file_read/bash 标准
  - `paperCompatToolsAreAllReadOnly` - 8 个 tool 全 isReadOnly=true
  - `toolPoolHasUniqueNames` - tool 名字唯一

## 关键设计决定

1. **不在 StandardTools 加 paperCompat** - 会形成 tools → orchestration cycle
2. **在 aethercode-cli 拼接** - cli 已经同时依赖 tools + orchestration, 是天然的聚合点
3. **每个 tool 名字 snake_case** - LLM tool dispatch 习惯
4. **每个 tool isReadOnly=true** - 跳过 permission prompt, paper-compat 是 LLM 自检
5. **保留 isReadOnly wrapper 模式** - 不改 ToolDef, 留 delegate 完整行为
6. **JSON-string 解析在 PaperCompatTools 内部** - RPC 协议不变, 转换在 wrapper 层
7. **测试在 cli 而不是 orchestration** - 因为集成点在 cli (BuildEngineForSession)

## 教训 (新增 3 条, 累计 417+)

415. **tools → orchestration 形成 module cycle** - 集成要选聚合点 (aethercode-cli)
416. **每个 module 拼接自己需要的 tool pool** - 避免在 leaf module 加 cross-cutting
417. **测试 tool pool composition** - `daemonToolPoolIncludesAllPaperCompatTools` 锁住结构

## 累计统计 (本 round 后)

- aethercode-orchestration: 394/394
- aethercode-evals: 646/646
- aethercode-cli: **55/55** (was 52, +3 MainBuildEnginePaperCompatToolsTest)
- 0 回归

## 完整 paperCompat 集成链路 (3 round 总览)

| Round | 内容 | 文件 |
|---|---|---|
| R-paper-batch6-E2E | `PaperCompatRpc` 8 RPC methods 在 dispatcher | orchestration/papercompat/PaperCompatRpc.java |
| R-papercompat-tools | `PaperCompatTools` 8 Tools 包装 | orchestration/papercompat/PaperCompatTools.java |
| R-papercompat-engine (本 round) | `PaperCompatTools.buildAll()` 加到 daemon tool pool | aethercode-cli/Main.java |

LLM 现在能:
- 通过 `AetherCodeEngine.query(prompt)` 业务流, LLM tool loop 调 `paper_compat_architecture_recommend` 等 8 个 tool
- 同时前端 (TUI / Tauri / CLI) 也能通过 `paperCompat.*` RPC 方法直调

## 文件清单

- `aethercode/aethercode-cli/src/main/java/org/aethercode/cli/Main.java` (改: 加 PaperCompatTools)
- `aethercode/aethercode-tools/src/main/java/org/aethercode/tools/StandardTools.java` (改: 加 javadoc)
- `aethercode/aethercode-cli/src/test/java/org/aethercode/cli/MainBuildEnginePaperCompatToolsTest.java` (新, 3 tests)
- `doc/round-notes/R-papercompat-engine-2026-09-13.md` (本文件)
