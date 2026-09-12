# R-sdk-5..6: AetherCode SDK Interface Conformance (续)

**Date**: 2026-09-12
**Round**: R-sdk-5..6
**Scope**: 2 新 SDK interface test class, 28 tests, 0 regression
**Status**: ✅ 全部完成

## 触发

R-sdk-1..4 (0d1ba90) 测了 SDK State / Plan / Memory / Cost-Safety 4 个维度的
真 class 行为。R-sdk-5..6 续上 Tool Interface (R-sdk-5) + JSON-RPC Interface
(R-sdk-6), 把 tool / protocol 维度的真 class 行为也锁住。

User 原始要求: "重点是 AetherCode 前端会请求到的相关接口和SDK" — 全部
AetherCode 关键 class (SDK / Memory / Permission / Orchestration / Tools /
Protocol) 都覆盖到。

## R-sdk 范围 (2 round, 2 test class, 28 tests)

| Round | Test class | 真 SDK 包 | Tests | Sub-能力 |
|---|---|---|---:|---|
| R-sdk-5 | `SdkToolInterfaceTest` | `aethercode.tools.StandardTools` (17 tools) | 8 | 工厂完整性 / 命名 / file / network / shell / task / size 稳定 |
| R-sdk-6 | `SdkJsonRpcInterfaceTest` | `aethercode-protocol` (Codec / Dispatcher / Error) | 20 | 错误码 / codec / dispatcher 路由 / protocol exception / crash containment |
| **Total** | | | **28** | |

## R-sdk-5 Tool Interface (8 tests)

测真 `org.aethercode.tools.StandardTools` 工厂 — 17 个 tool 全部加载 + 命名
约定。

| Sub-能力 | Tests | 行为 |
|---|---|---|
| 工厂完整性 | 3 | 17 tool 加载, name 不为 null/blank, name 唯一 |
| 命名约定 | 4 | file / network / shell / task 4 类 tool name 都对 |
| Size 稳定 | 1 | 17 个, 提醒 dev 增删 tool 时更新 TUI palette + 分类器 |

实际 tool 名字 (R-sdk-5 验证):
```
file_read, file_write, file_edit, glob, grep, bash,
todo_write, sub_todo_write, spawn_agent, subagent_status, subagent_list,
web_fetch, web_search, google_scholar, arxiv_fetch,
notebook_edit, ask_user_question
```

注意: `spawn_agent` (不是 `agent`), `google_scholar` (不是 `scholar_search`).

## R-sdk-6 JSON-RPC Interface (20 tests)

测真 `org.aethercode.protocol.jsonrpc` + `org.aethercode.protocol.server`。

| Sub-能力 | Tests | 行为 |
|---|---|---|
| JsonRpcError spec codes | 2 | 标准 5 code (-32700..-32603) 稳定, AetherCode 7 code (-32000..-32007) 稳定 |
| JsonRpcError 构造 | 2 | blank message 抛 IAE, factory 带 context |
| JsonRpcCodec encode/decode | 4 | request/response-result/response-error/notification 4 种 round trip |
| JsonRpcCodec 校验 | 3 | missing version 抛 INVALID_REQUEST, invalid JSON 抛 PARSE_ERROR, leading BOM 自动 strip |
| JsonRpcDispatcher 路由 | 5 | 注册 method 调, 未知 method 返 METHOD_NOT_FOUND, protocol exception 透传, 异常转 INTERNAL_ERROR, unknown notification ignore |
| JsonRpcDispatcher 辅助 | 2 | 同 method 重复注册, methodNames 不可变 |
| JsonRpcMethodHandler 工厂 | 1 | of() 适配 Function, JsonRpcMessage.VERSION="2.0" |

## 设计原则 (跟 R-sdk-1..4 一致)

1. **Self-contained + 真 SDK 并行**: R-eval 测 capability, R-sdk 测 SDK
2. **不破坏 capability 行为**: SDK API 跟 self-contained 不完全一致, 但子能力
   维度对齐
3. **每个 test class 5-22 tests**: 不追求覆盖每个方法, 验证核心 invariant
4. **真实 API 用法**: 测试构造 / method call / 边界, 跟 production 代码
   调用方式 1:1, 验证 front-end 集成没问题

## 关键技术决定 (8 条)

1. **`StandardTools` 工厂是 single source of truth**: 17 tool 全部从
   `StandardTools.all()` 拿, 删一个就 break 全套
2. **Tool name 约定 1:1 跟 PermissionReasoner**: `bash` / `file_read` /
   `file_edit` / `file_write` / `web_fetch` 这些名字被 reasoner 的
   `extractPrompt` 硬编码, 重命名会静默 break
3. **`spawn_agent` 不是 `agent`**: 跟 aethercode.tools.task.AgentTool
   实现名字一致, 不是 JS 风格
4. **`google_scholar` 不是 `scholar_search`**: 跟 aethercode.tools.net 实际
   tool 名一致
5. **JSON-RPC 2.0 spec codes 锁住**: -32700 / -32600 / -32601 / -32602 / -32603
   是 spec 规定, 改了 front-end 全部 break
6. **AetherCode 7 custom codes 锁住**: -32000..-32007 (engine / permission /
   session / cancelled / timeout / tool / unauthorized)
7. **`JsonRpcMethodHandler.handle` throws Exception**: functional interface
   用 `assertDoesNotThrow` 或 test 方法加 `throws Exception`
8. **Dispatcher 跑 worker 池**: `JsonRpcDispatcher` 用 fixed 4-thread pool,
   test 用 `CountDownLatch` 等 worker 完成, 不能直接 assert

## 累计 SDK Interface Test (R-sdk 1-6 全部)

| Module | Tests |
|---|---:|
| R-sdk-1 SdkStateInterfaceTest | 19 |
| R-sdk-2 SdkPlanInterfaceTest | 15 |
| R-sdk-3 SdkMemoryInterfaceTest | 12 |
| R-sdk-4 SdkCostSafetyInterfaceTest | 22 |
| R-sdk-5 SdkToolInterfaceTest | 8 |
| R-sdk-6 SdkJsonRpcInterfaceTest | 20 |
| **Total R-sdk 1-6** | **96** |

## Test 结果

| Module | Tests (新) | 累计 | Pass | Fail | Skip |
|---|---:|---:|---:|---:|---:|
| aethercode-evals (R-sdk-5..6) | 28 | 438 | 438 | 0 | 0 |
| aethercode-orchestration (R-mod-2) | 0 | 260 | 260 | 0 | 0 |
| **Total 新增** | **28** | **698** | **698** | **0** | **0** |

## 后续

1. **R-sdk-7 A2A Interface**: 测真 A2A schema (aethercode-a2a) — R-eval-7 续
2. **R-sdk-8 Reflection Interface**: 测真 `AgentRuntime` / `SelfCorrectionLoop` (orchestration 已是真)
3. **引用新 paper 8 篇到 ref/papers/**: 2503.16416 / 2506.11102 / 2604.00835 /
   2603.22862 / 2601.08173 / 2607.23722 / 2608.04719 / 2602.16902
4. **push** (force-with-lease)

## 教训 (新增 6 条, 累计 321+)

315. **`StandardTools.all()` 是 single source of truth**: 17 tool 全部从
     这拿, test 加 `size=17` 锁住, dev 改了能看到 fail
316. **Tool name 不要瞎猜**: 跑一次 `StandardTools.all()` 看实际 name,
     不要假设 (`agent` → `spawn_agent`, `scholar_search` → `google_scholar`)
317. **`JsonRpcMethodHandler.handle` throws Exception**: functional interface
     接受 `Exception` (不只 RuntimeException), test 方法要 `throws Exception` 或
     用 `assertDoesNotThrow`
318. **`JsonRpcDispatcher` 是异步**: handler 在 worker 池跑, test 必须用
     `CountDownLatch` 等 worker 完成, 不能 sync assert
319. **`assertInstanceOf(JsonRpcRequest.class, back)`** 而不是 `instanceof + cast`:
     简洁 + 错误消息清晰
320. **JSON-RPC error code 锁 spec + 自定义**: JSON-RPC 2.0 spec 用
     -32700..-32603, 框架自定义 -32000..-32099, 改了全 ecosystem 崩

## Commits

- R-sdk-1..4: `0d1ba90` (68 new tests)
- R-sdk-5..6: (本次) `R-sdk-5-6-INTERFACE-CONFORMANCE`
