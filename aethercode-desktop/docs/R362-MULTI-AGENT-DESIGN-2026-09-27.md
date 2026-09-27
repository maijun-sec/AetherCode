# R362 — Multi-Agent: primary ↔ subagent + dynamic agent creation

**Date:** 2026-09-27
**Status:** Round 1 ✅ SHIPPED · Round 2 ✅ SHIPPED · Round 3 ✅ SHIPPED
**Scope:** aethercode (Java daemon + tools) + aethercode-desktop (UI + RPC bridge)

---

## Round 状态

| Round | 内容 | Commit | Release |
|-------|------|--------|---------|
| 1 | spawn 接通 AgentRegistry + `<available_agents>` block + `list_agents` tool + builtin 迁移 | `8bfe022` (aethercode) · `8f1507c` (desktop) | `aethercode-0.3.0-r362.1.zip` |
| 2 | `create_agent` / `update_agent` / `delete_agent` 三个 tool（LLM 直接调） | `054eae4` (aethercode) · `1b6190b` (doc) | `aethercode-0.3.0-r362.2.zip` |
| 3 | Retry + Watchdog + SubagentPanel Retry/Cancel 按钮 + `subagent_retry` / `subagent_cancel` tool | `bdea6ed` (aethercode) · `69d2acd` (E2E) · `d4eaea1` (JSON-RPC E2E) | `aethercode-0.3.0-r362.3.zip` |

---

## TL;DR

让 primary agent 能真正编排 subagent：

1. **Round 1 — Spawn 接通 AgentRegistry**
   - `spawn_agent` 接受 `agent_name` 直接调起 `~/.aethercode/agents/<name>/agent.md` 里的 agent
   - 系统 prompt 注入可用 agent 列表（让 primary "智能识别"该调谁）
   - 加 `list_agents` tool + RPC

2. **Round 2 — primary 自己创建/更新/删除 agent（无 confirm 弹窗）**
   - 新增 `create_agent` / `update_agent` / `delete_agent` 三个 tool
   - **不强制 confirm**（chat 修订）：用户主动给"个性" → 让用户补完即可；任务驱动 → 直接落盘；详见附录 A.2
   - AetherCodeMethods 加 `agentCreate` / `agentUpdate` / `agentDelete` RPC
   - Desktop 加 Agent Manager 面板

3. **Round 3 — 失败 retry + dead loop 终止**
   - `SubagentRegistry.retry(jobId)` 重置 FAILED → PENDING → RUNNING
   - 复用 R20-I `RetryPolicy` + `RetryHelper`（已经有！）
   - 集成 R20-J `Watchdog` 到 SubagentRegistry（每个 background job 一个 watchdog，超时自动 FAILED）
   - SubagentPanel 加 Retry / Cancel 按钮

**用户决策（2026-09-27 问卷）：**
- Round 范围：3 个 round 一起规划（不是 Round 1 优先）
- create_agent 权限（**chat 修订**）：默认开启，**不再每次 confirm**：
  - 用户主动给"个性"描述 → 可让用户补完（建议展示 agent preview，但不强制 confirm）
  - 任务驱动自主创建（primary 觉得需要持久化个 agent）→ 完全不打扰，直接落盘
  - 详见 Round 2 的 create_agent tool 设计
- Builtin vs 自定义：用户自定义优先（AgentRegistry 查不到再 fallback 到 SubagentRole 内置 3 个 role）
- `~/.minimax/agents/` builtin agents **全部直接搬到 `~/.aethercode/agents/`**（chat 反馈）—— 不做 multi-source AgentRegistry。Round 1 执行前已完成（8 个 agent 落地：5 个拷 agent.md + 3 个 PERSONA.md 转换；4 个只有 config.yaml 的 coder/verifier/general/mavis 跳过，留给 Round 2 create_agent 自己写）

### 已有（足够多！）

| 组件 | 文件 | 状态 |
|------|------|------|
| `SubagentPool` 并发调度 | `aethercode-core/.../agent/SubagentPool.java` | ✅ maxConcurrent + Priority + 状态机 |
| `SubagentRegistry` job 跟踪 | `aethercode-tools/.../task/SubagentRegistry.java` | ✅ RUNNING/COMPLETED/FAILED/CANCELLED + partial streaming + sessionId 过滤 + audit log |
| `AgentTool` / `spawn_agent` | `aethercode-tools/.../task/AgentTool.java` | ✅ `prompt`/`context`/`multi_step`/`background`/`role`/`MAX_DEPTH=2` |
| `subagent_status` tool | `aethercode-tools/.../task/SubagentStatusTool.java` | ✅ poll background job |
| `subagent_list` tool | `aethercode-tools/.../task/SubagentListTool.java` | ✅ 列出所有 jobs |
| `AgentRegistry`（读 + 写） | `aethercode-core/.../agent/AgentRegistry.java` | ✅ `list()`/`getBody()`/`getMeta()` + `create()`/`update()`/`delete()`（**写方法已存在但未暴露给 LLM**）|
| `SubagentRole` 内置 role | `aethercode-core/.../agent/SubagentRole.java` | ✅ explore / general-purpose / coder |
| `SubagentOrchestrator` | `aethercode-core/.../agent/SubagentOrchestrator.java` | ✅ 委托 + result 收集 |
| `SubagentToast` 桌面 UI | `aethercode-desktop/src/components/SubagentToast.tsx` | ✅ R92-A 已经有 |
| `RetryHelper` + `RetryPolicy` | `aethercode-sdk/.../sdk/RetryHelper.java` | ✅ R20-I 已经有 |
| `Watchdog` | `aethercode-sdk/.../sdk/Watchdog.java` | ✅ R20-J 已经有，但没集成到 SubagentRegistry |

### 缺（要做的）

| 缺口 | 影响 |
|------|------|
| `spawn_agent` 只接受 3 个内置 role 字符串，不接受 AgentRegistry 的自定义 agent | 用户装了 12 个 builtin agent（aethercode-pm、coder、verifier ...）但 spawn 不到 |
| primary system prompt 看不到可用 agent 列表 | 模型不知道该调谁，只能瞎试 |
| 没有 `list_agents` tool | primary 无法 query "有哪些 agent 可用" |
| 没有 `create_agent` / `update_agent` / `delete_agent` tool | AgentRegistry 写方法存在但未暴露 |
| SubagentRegistry 没 retry / watchdog | background job 失败只能放弃，死了不知道 |
| SubagentPanel 缺 Retry / Cancel 按钮 | 用户没法手动重试 |

---

## Round 1 — Spawn 接通 AgentRegistry

### 目标

1. `spawn_agent` 接受 `agent_name` 参数（**新参数**），直接调起 AgentRegistry 里的 agent
2. 系统 prompt 注入 `<agents>` block，让 primary 看到所有可用 agent + 描述
3. 加 `list_agents` tool + `listAgents` RPC，让 primary 能 query
4. **用户决策**：AgentRegistry 优先；查不到 fallback 到 SubagentRole 内置 3 个

### 改动清单

#### 1.1 Java daemon

**`aethercode-tools/.../task/AgentTool.java`**

- 新增 `agent_name` 参数（与 `role` 互斥，二选一）
- 解析顺序：`agent_name` → AgentRegistry lookup → 用 body 作为 system prompt
- 查不到时 fallback 到 `role` → SubagentRole 内置 3 个
- 都没传 → 默认 general-purpose
- description 注入 tool result 的 summary，让 primary 看到

**`aethercode-tools/.../task/ListAgentsTool.java`**（**新文件**）

```java
public static final String NAME = "list_agents";
// 返回所有 agents 的 name + description（built-in + user-defined）
// 输出格式：text 列表，每行 "<name>: <description>"
// primary 用这个来发现可用 agent
```

**`aethercode-tools/.../task/StandardTools.java`**

- 把 `ListAgentsTool.build()` 加进 StandardTools.all()（pool 加新成员）

**`aethercode-core/.../agent/AgentRegistry.java`**

- 加 `findByDescription(String keyword)` 方法（模糊匹配，description 包含 keyword）
- 加 `allNamesWithDescriptions()` 返回 List<{name, description, source}>

**`aethercode-core/.../engine/AetherCodeAgent.java`**（如果存在 system prompt 构造点）

- 在 buildSystemPrompt 末尾追加 `<agents>...</agents>` block，类似 skills 的注入方式
- 每行：`  <agent name="<name>" source="builtin|user"> description="<desc>"></agent>`
- limit：最多列 30 个（避免 prompt 爆掉）

**`aethercode-protocol/.../methods/AetherCodeMethods.java`**

- 加 `listAgents({sessionId?})` RPC（如果有 sessionId 路由，按 session 过滤；当前 daemon 只有一个 AgentRegistry 实例，先不过滤）

**`aethercode-cli/.../Main.java`**

- 不变（AgentRegistry 已经在 builder 里 wire）

#### 1.2 Desktop UI

**`aethercode-desktop/src/lib/methods.ts`**

- 加 `rpc.listAgents()` 包装

**`aethercode-desktop/src/store/index.ts`**

- 加 `listAgents()` action，调 RPC + cache 到 state.agents
- 启动时调一次拉全量

#### 1.3 测试

| 测试 | 内容 |
|------|------|
| `AgentToolR362Test` | spawn_agent(agent_name) → AgentRegistry lookup → system prompt 注入 body |
| `ListAgentsToolTest` | 返回 builtin + user-defined 列表；空 registry 时不崩 |
| `AgentRegistryFindByDescriptionTest` | 模糊匹配；大小写不敏感 |
| `AetherCodeMethodsR362Test` | listAgents RPC 返回正确 wire |
| `AetherCodeAgentSystemPromptR362Test` | system prompt 包含 `<agents>` block 且列出所有 agent |

### Round 1 验证

- aethercode-core / sdk / protocol / cli / tools 全测试通过
- 端到端：primary 看到 `<agents>` block，能调 `spawn_agent(agent_name="aethercode-pm")`
- 用户可用 `~/.aethercode/agents/<name>/agent.md` 自定义 agent，spawn_agent 直接调起

---

## Round 2 — Create/Update/Delete agent tool（**不强制 confirm**，详见附录 A.2）

### 目标

1. 新增 `create_agent` / `update_agent` / `delete_agent` 三个 tool（**chat 修订：不再每次弹窗 confirm**）
2. **不再每次 confirm 弹窗**（详见附录 A.2）：直接调 AgentRegistry.create/update/delete，路径 + body 摘要返回到 tool result；用户主动描述 agent 时 primary 可以让用户补完（可选），任务驱动时不打扰
3. delete 不需要 confirm（删除操作本身低风险，可逆性低）
4. Desktop 加 Agent Manager 面板（独立 tab 或 modal）— 可视化 CRUD + 编辑 agent.md

### 改动清单

#### 2.1 Java daemon

**`aethercode-tools/.../task/CreateAgentTool.java`**（**新文件**）

```java
public static final String NAME = "create_agent";
// 参数：name (required), description, display_name, model, body (required)
// 流程：
//   1. validateName(name)
//   2. check duplicate
//   3. emit permission_request("create_agent", {name, body})
//   4. wait for permission_response
//   5. on approve: agentRegistry.create(...); reload
//   6. on deny: return ToolResult.error("user denied")
```

**`aethercode-tools/.../task/UpdateAgentTool.java`**（**新文件**）

```java
public static final String NAME = "update_agent";
// 类似 create_agent：参数 + body；触发 permission_request；allow 才落盘
```

**`aethercode-tools/.../task/DeleteAgentTool.java`**（**新文件**）

```java
public static final String NAME = "delete_agent";
// 参数：name (required)
// 不触发 permission_request（用户主动）
// agentRegistry.delete(name)
// return "deleted agent: <name>"
```

**`aethercode-tools/.../task/StandardTools.java`**

- 把 3 个新 tool 加进 pool

**`aethercode-tools/.../permission/AgentPermissionPrompter.java`**（**新文件**）

- 复用现有 JsonRpcPermissionPrompter 的 notification 通道
- payload：`{kind: "create_agent"|"update_agent", name, description, model, body, preview_chars: 500}`
- 等 permission_response（同现有的 permission_request/permission_response round trip）

**`aethercode-protocol/.../methods/AetherCodeMethods.java`**

- 加 `agentCreate` / `agentUpdate` / `agentDelete` RPC
- 加 `listAgents` 已经在 Round 1 加了

#### 2.2 Desktop UI

**`aethercode-desktop/src/components/AgentManager.tsx`**（**新文件**）

- 列出所有 agents（builtin + user-defined）
- 每个 agent 可编辑：description / displayName / model / body
- 新建按钮 → 弹出 modal 输入 name + body
- 删除按钮 → 二次确认
- 保存按钮 → 调 RPC → reload

**`aethercode-desktop/src/components/PermissionDialog.tsx`**（**复用 + 扩展**）

- 已经有的 permission_request UI
- 新增 agent 类型的展示：name + description + body preview（折叠）+ 勾选确认

**`aethercode-desktop/src/store/index.ts`**

- 加 `createAgent(payload)` / `updateAgent(payload)` / `deleteAgent(name)` action
- 调 RPC + 刷新 agents cache

#### 2.3 测试

| 测试 | 内容 |
|------|------|
| `CreateAgentToolTest` | normal flow；permission denied；duplicate name；invalid name |
| `UpdateAgentToolTest` | similar；body 修改；frontmatter 不变 |
| `DeleteAgentToolTest` | 正常；不存在时 error；filesystem 真删掉 |
| `AgentPermissionPrompterTest` | payload 正确；wait/notify 时序 |
| `AetherCodeAgentCreateRpcTest` | RPC handler；写文件 + reload |

### Round 2 验证

- aethercode-* 全模块测试通过
- 端到端：primary 分析 user prompt → 决定需要持久化 agent → 调 create_agent → 弹窗 → 用户确认 → 落盘
- 后续 query：primary 看到新 agent 出现在 `<agents>` block，可调起

---

## Round 3 — Retry + Watchdog 集成 + UI 按钮

### 目标

1. `SubagentRegistry.retry(jobId)` 方法：FAILED → PENDING → RUNNING（重置 thread + state）
2. 每个 background job 自动 attach `Watchdog`（默认 60s timeout；超 auto-fail）
3. `AgentTool.call` 在 multi_step 失败时自动 retry（复用 R20-I `RetryPolicy.DEFAULT`，最多 3 次）
4. SubagentPanel 加 Retry / Cancel 按钮
5. 加 RPC `subagentRetry({jobId})` + tool `subagent_retry(jobId)`

### 改动清单

#### 3.1 Java daemon

**`aethercode-tools/.../task/SubagentRegistry.java`**

- 新增 `retry(String jobId)` 方法：
  - jobId 必须存在 + 状态必须是 FAILED
  - 重置：`status = PENDING`, `finishedAtMs = 0`, `error = ""`
  - 重新入队（重新 attachThread + runBackgroundJob）
  - fireChange(new RUNNING event)
- 新增 `cancel(String jobId, String reason)`：已经存在，确认能 interrupt thread

**`aethercode-tools/.../task/SubagentRegistry.java`** + **`aethercode-sdk/.../sdk/Watchdog.java`**

- 集成：每个 background job 启动时 `Watchdog.create(jobId, 60_000, () -> markFailed(jobId, "watchdog timeout"))`
- job 终止时（COMPLETED / FAILED / CANCELLED）watchdog.stop()
- 注意：cancel(watchdog) 必须优雅，stop 必须在 markFailed 之前完成（避免 race）

**`aethercode-tools/.../task/AgentTool.java`**

- `callMultiStep` 包 `RetryHelper.run(..., RetryPolicy.DEFAULT)`：
  - 失败 3 次才最终 FAILED（指数退避 1s + 2s）
  - summary 加 `[retried Nx]` 标记（跟 PlanExecutor 一致）
- 不 retry single-shot（避免 token 浪费）

**`aethercode-tools/.../task/SubagentRetryTool.java`**（**新文件**）

```java
public static final String NAME = "subagent_retry";
// 参数：job_id (required)
// 调 SubagentRegistry.instance().retry(jobId)
// 返回 retry 状态
```

**`aethercode-tools/.../task/SubagentCancelTool.java`**（**新文件**）— 已有 cancel RPC 但没暴露成 tool

```java
public static final String NAME = "subagent_cancel";
// 参数：job_id, reason (optional)
// 调 SubagentRegistry.instance().cancel(jobId, reason)
```

**`aethercode-tools/.../task/StandardTools.java`**

- 加 SubagentRetryTool + SubagentCancelTool

**`aethercode-protocol/.../methods/AetherCodeMethods.java`**

- 加 `subagentRetry({jobId, sessionId?})` RPC
- 加 `subagentCancel({jobId, reason?, sessionId?})` RPC（已有 cancel 部分）

#### 3.2 Desktop UI

**`aethercode-desktop/src/components/SubagentPanel.tsx`**（或新建）

- 每个 job 显示：role + description（来自 AgentRegistry）+ 状态 + elapsed
- 状态 FAILED 时显示 Retry 按钮 → 调 `subagentRetry` RPC
- 状态 RUNNING 时显示 Cancel 按钮 → 调 `subagentCancel` RPC
- 状态 COMPLETED 时显示 result + 折叠的 body

**`aethercode-desktop/src/store/subagentReducer.ts`**

- 加 `retry(jobId)` action
- 加 `cancel(jobId, reason)` action

#### 3.3 测试

| 测试 | 内容 |
|------|------|
| `SubagentRegistryR362RetryTest` | retry FAILED → RUNNING；retry COMPLETED 报错；retry 未知 id 报错 |
| `SubagentRegistryR362WatchdogTest` | 60s timeout 触发 markFailed；正常完成 cancel watchdog |
| `AgentToolR362RetryTest` | multi_step 失败 1 次后 retry 成功 → summary 含 `[retried 1x]`；失败 3 次后 FAILED |
| `SubagentRetryToolTest` | 正常；FAIL 状态；返回 retry 状态 |
| `SubagentCancelToolTest` | 正常；非 RUNNING 时 cancel 报错 |

### Round 3 验证

- aethercode-* 全模块测试通过
- 端到端：primary spawn_agent → background → 用户通过 SubagentPanel 看到 RUNNING → FAILED → 点 Retry → 重新 RUNNING → COMPLETED
- Watchdog：构造一个 hang 住的 subagent → 60s 后自动 FAILED（不依赖人工）

---

## 跨 round 共享基础

### 风险与权衡

| 风险 | 缓解 |
|------|------|
| `~/.minimax/agents/`（12 个 builtin）跟 `~/.aethercode/agents/`（用户）路径不一致 | **chat 决策**：直接搬 8 个有 system prompt 的到 `~/.aethercode/agents/`；不做 multi-source AgentRegistry（详见附录 A.1） |
| create_agent LLM 写错 body | Round 2：每次 confirm + preview；body 大小限制（max 64KB） |
| spawn_agent(agent_name="explore") 撞 SubagentRole 内置 | 用户决策：**用户自定义优先**；AgentRegistry 优先，fallback SubagentRole |
| Watchdog 误杀长任务 | 默认 60s timeout；环境变量 `AETHERCODE_SUBAGENT_TIMEOUT_MS` 可调 |
| Retry 死循环（task 真的 fail 但 LLM 一直 retry） | RetryPolicy.DEFAULT = 3 attempts 上限；3 次后强制 FAILED |
| Round 2 每次都弹窗打扰用户 | **已修订**：默认不 confirm；保留环境变量 `AETHERCODE_CREATE_AGENT_REQUIRE_CONFIRM=1` 可切严格模式（详见附录 A.2） |

### 不在 R362 范围（明确 out of scope）

- A2A 协议（Agent-to-Agent 跨进程通信）：有 `aethercode-a2a/` 模块但 R362 不碰
- 跨 daemon 共享 agent（多用户/多机）：后续 round
- Agent 自动评分 + 自动淘汰：后续 round
- Agent 之间的 skill / tool 共享（agent 可以注册自己的工具）：后续 round

### 兼容性

- `spawn_agent(role=...)` 现有用法保持不变（向后兼容）
- AgentRegistry 新增字段 / 方法全部 additive，不动现有签名
- 新增 RPC 都接受可选 `sessionId`，null/empty = active engine

### 关键文件路径（跨仓）

| 文件 | 作用 |
|------|------|
| `aethercode/aethercode-core/src/main/java/org/aethercode/core/agent/AgentRegistry.java` | Round 1 扩展；Round 2 写方法已存在 |
| `aethercode/aethercode-tools/src/main/java/org/aethercode/tools/task/AgentTool.java` | Round 1 接受 agent_name；Round 3 retry |
| `aethercode/aethercode-tools/src/main/java/org/aethercode/tools/task/StandardTools.java` | Round 1+2+3 新 tool 加进 pool |
| `aethercode/aethercode-protocol/src/main/java/org/aethercode/protocol/methods/AetherCodeMethods.java` | Round 1 listAgents；Round 2 agentCreate/Update/Delete；Round 3 subagentRetry/Cancel |
| `aethercode/aethercode-sdk/src/main/java/org/aethercode/sdk/RetryHelper.java` | Round 3 复用 |
| `aethercode/aethercode-sdk/src/main/java/org/aethercode/sdk/Watchdog.java` | Round 3 集成 |
| `aethercode-desktop/src/store/subagentReducer.ts` | Round 3 retry/cancel action |
| `aethercode-desktop/src/components/AgentManager.tsx` | Round 2 新建 |

---

## 实施顺序

3 个 round 之间**严格串行**，因为：
- Round 2 依赖 Round 1 的 listAgents（用户要先看到 agent 才能决定创建）
- Round 3 依赖 Round 1 的 spawn_agent(agent_name)（retry 时也要按 agent_name 重新构造）

每个 round 独立 release，R362.1 / R362.2 / R362.3 tag。

**预计每个 round**：
- Java: 1-2 天改 + 测试
- Desktop UI: 0.5-1 天
- 集成测试 + MSI 打包: 0.5 天

总计 **6-9 天**（3 round 串行）。

---

## 决策记录

| 日期 | 决策 | 选项 |
|------|------|------|
| 2026-09-27 | Round 范围 | **3 round 一起规划** |
| 2026-09-27 | create_agent 权限 | **chat 修订**：默认开启，**不强制 confirm**（详见附录 A.2） |
| 2026-09-27 | builtin vs 自定义优先级 | **用户自定义优先**（AgentRegistry 查不到再 fallback SubagentRole） |

---

## 附录 A — Round 1 执行前准备工作

### A.1 Builtin agents 迁移（2026-09-27 完成）

按用户 chat 反馈，把 `~/.minimax/agents/` 下 builtin 直接搬到 `~/.aethercode/agents/`：

**结果**（8 个有 system prompt 的 agent 落地，4 个只有 config.yaml 的跳过）：

| Source | Target | 类型 | 备注 |
|--------|--------|------|------|
| `~/.minimax/agents/aethercode-experienced-user/agent.md` | `~/.aethercode/agents/aethercode-experienced-user/agent.md` | 复制 | 1.9 KB |
| `~/.minimax/agents/aethercode-pm/agent.md` | `~/.aethercode/agents/aethercode-pm/agent.md` | 复制 | 2.7 KB |
| `~/.minimax/agents/aethercode-senior-pm/agent.md` | `~/.aethercode/agents/aethercode-senior-pm/agent.md` | 复制 | 5.6 KB |
| `~/.minimax/agents/aethercode-senior-ux/agent.md` | `~/.aethercode/agents/aethercode-senior-ux/agent.md` | 复制 | 6.1 KB |
| `~/.minimax/agents/ui-optimization-expert/agent.md` | `~/.aethercode/agents/ui-optimization-expert/agent.md` | 复制 | 2.9 KB |
| `~/.minimax/agents/ai-agent-pm/PERSONA.md` | `~/.aethercode/agents/ai-agent-pm/agent.md` | 转换（包 frontmatter） | 1.7 KB |
| `~/.minimax/agents/ai-agent-ux-expert/PERSONA.md` | `~/.aethercode/agents/ai-agent-ux-expert/agent.md` | 转换（包 frontmatter） | 2.3 KB |
| `~/.minimax/agents/end-user/PERSONA.md` | `~/.aethercode/agents/end-user/agent.md` | 转换（包 frontmatter） | 1.6 KB |
| ~~`~/.minimax/agents/coder/`~~ | （跳过） | 只有 config.yaml | Round 2 create_agent 自己写 |
| ~~`~/.minimax/agents/verifier/`~~ | （跳过） | 只有 config.yaml | Round 2 create_agent 自己写 |
| ~~`~/.minimax/agents/general/`~~ | （跳过） | 只有 config.yaml | Round 2 create_agent 自己写 |
| ~~`~/.minimax/agents/mavis/`~~ | （跳过） | 只有 config.yaml | Round 2 create_agent 自己写 |

**迁移脚本**：
- `D:\tmp\copy-builtin-agents.py` — 复制 5 个 agent.md
- `D:\tmp\convert-persona-to-agent.py` — 转换 3 个 PERSONA.md → agent.md

**为什么不复制 4 个只有 config.yaml 的**：
- 它们是 Mavis Rust runtime 的内置角色（如 `/agent coder` 在 Mavis CLI 里切换 workspace）
- 没有 system prompt 概念 —— 不能 spawn 起来当 LLM agent
- Round 2 的 `create_agent` tool 让用户/primary 按需生成对应 system prompt，落地为自定义 agent

**Round 1 验证**：
- `~/.aethercode/agents/` 启动扫描应该列出 9 个（含 r234-test-agent + 8 个迁移的）
- AetherCodeMethods.listAgents RPC 返回 9 个
- primary system prompt `<agents>` block 列出全部 9 个

### A.2 create_agent 权限策略（chat 修订）

用户 chat 反馈后，原"每次 confirm 弹窗"策略修订如下：

| 场景 | 触发方式 | 是否 confirm |
|------|----------|-------------|
| 用户明确"创建一个 agent，个性是 X" | 用户主动 | **不强制 confirm**，但 tool result 里返回 `agent_md_path` + body 摘要；primary 可以选择展示给用户补完 |
| 用户下发的任务触发 primary 自己判断要持久化 agent | 任务驱动 | **完全不打扰**，直接落盘 |
| 用户下发的任务，primary 想创建一个"辅助任务"的 agent（不是用户主线的 agent） | 任务驱动 | **完全不打扰**，直接落盘 |
| delete_agent | 用户/primary 主动 | **不 confirm**（删除可逆性低，但磁盘操作不算高风险） |

**实现要点**：
- `create_agent` tool 直接调 `AgentRegistry.create()`，**不再 emit permission_request**
- Tool result 包含 `path: ~/.aethercode/agents/<name>/agent.md` 让用户能直接打开编辑
- 如果 tool result 返回后，primary 想让用户补完（比如 "我看到这个 agent 写好了，你要不要加点内容?"），UI 层可以展示一个 "Open agent file" 按钮
- 跑 safety net：仍然做 validateName（防路径穿越）+ body 大小限制（max 64KB）+ duplicate 检查

**保留的可选 confirm 机制**（不强制启用）：
- 环境变量 `AETHERCODE_CREATE_AGENT_REQUIRE_CONFIRM=1` 让用户开启严格模式
- 默认 false（不打扰）

---

## 附录 B — Round 2 执行记录 (2026-09-27)

Round 2 把 Round 1 已经在 `AgentRegistry` 里写好的 create/update/delete 方法暴露给 LLM。

### B.1 已有且复用（无需重写）

| 组件 | 状态 | 备注 |
|------|------|------|
| `AgentRegistry.create/update/delete/validateName` | ✅ 已在 R286 落地 | Round 1 设计文档里的"写方法已存在但未暴露" — 本轮正是把 LLM 调用入口补上 |
| `AetherCodeMethods.agentCreate/agentUpdate/agentDelete/reloadAgents` | ✅ 已在 R286 注册 | wire 名就是 `createAgent` / `updateAgent` / `deleteAgent` / `reloadAgents` |
| `AetherCodeMethods.getAgentBody`（含 description/displayName/model/variant frontmatter） | ✅ 已在 R286 | 编辑既有 agent 时 prefilled 用 |
| `aethercode-desktop/src/components/AgentsPanel.tsx` + `AgentEditor.tsx` | ✅ R286 已经支持 UI CRUD | `<media>` 文件流：AgentEditor → store.createAgent/updateAgent/deleteAgent → rpc → daemon |
| `aethercode-desktop/src/store/index.ts` 的 `createAgent/updateAgent/deleteAgent/refreshAgents/fetchAgentBody` | ✅ 已在 | |
| `aethercode-desktop/src/lib/methods.ts` 的 `rpc.createAgent/updateAgent/deleteAgent/reloadAgents/getAgentBody/listAgents` | ✅ 已在 | |

Round 2 的真正工作量是 **LLM-callable tool 层 + Round 1 留下来的 `<available_agents>` 系统提示块** 串联起来。

### B.2 Round 2 新增的 Java 代码

#### B.2.1 `CreateAgentTool.java`（新文件）

```java
public static final String NAME = "create_agent";
public static final int MAX_BODY_BYTES = 64 * 1024;     // 64 KB body cap
public static final String STRICT_CONFIRM_ENV = "AETHERCODE_CREATE_AGENT_REQUIRE_CONFIRM";

// 参数：name (required), description?, displayName?, model?, variant?, body (required)
// 流程：
//   1. isStrictModeStatic() → strict mode 拒绝 + 让 LLM 先 ask_user_question
//   2. ctx.extra("agent_registry") null → 报错（提示 --agents-dir）
//   3. name / body 必填检查
//   4. body size ≤ MAX_BODY_BYTES
//   5. AgentRegistry.validateName(name) — 路径穿越 / leading dot / 长度
//   6. registry.create(name, desc, displayName, model, variant, body)
//   7. 返回 ToolResult.of 包含 path 让 LLM/UI 知道 agent 落在哪里
// 关键行为：
//   - 默认 permissive（直接落盘）
//   - strict mode (env=1) 拒绝并告诉 LLM 调 ask_user_question 或用 UI
//   - 不 emit permission_request — 跟 chat 修订的"不打扰"政策一致
//   - body 用 atomic write（registry.writeAgentMd），崩溃半写不会留下损坏文件
```

#### B.2.2 `UpdateAgentTool.java`（新文件）

```java
public static final String NAME = "update_agent";
// 跟 CreateAgentTool 共享 isStrictModeStatic / MAX_BODY_BYTES / STRICT_CONFIRM_ENV
// 流程：跟 create 几乎一样；多一步 registry.getMeta(name).isEmpty() pre-check
//   → 失败-fast "agent 'X' does not exist"（不是 silently 写入新文件）
// body 是 wholesale replace —— 没有 patch / merge；要做小改就让 LLM 先 getAgentBody
// 结果 ToolResult.of 包含"In-flight subagents NOT interrupted"提示
```

#### B.2.3 `DeleteAgentTool.java`（新文件）

```java
public static final String NAME = "delete_agent";
// 共享 strict mode / name validation / registry extra 检查
// 流程：registry.getMeta(name) → 拿到 path → registry.delete(name)
// 失败-fast "agent 'X' does not exist"
// 结果 ToolResult.of 包含"in-flight subagents NOT interrupted"提示
```

#### B.2.4 `StandardTools.java` 改动

- 加 `import CreateAgentTool / UpdateAgentTool / DeleteAgentTool`
- 在 `all()` 里把三个新 tool 加到 `ListAgentsTool.build()` 旁边 —— 共用一个 `<available_agents>` 簇

### B.3 Round 2 测试

| 测试类 | 测试数 | 覆盖 |
|--------|--------|------|
| `CreateAgentToolR362Test` | 12 | happy path + 6 个 validation failure + idempotent overwrite + 严格模式 + missing registry + 空 body 接受 + body 边界 64KB |
| `UpdateAgentToolR362Test` | 9 | happy path + 失败-fast on unknown + 不留幻影文件 + missing name + path traversal + 超出 body + missing registry + 空 frontmatter 字段省略 + spawn_agent 可见性 |
| `DeleteAgentToolR362Test` | 11 | happy path + 失败-fast + 不动文件系统 + missing name + empty name + path traversal + missing registry + 二次 delete 失败 + 不影响其它 agent + 删 sibling 文件 + 提示 in-flight subagent |
| **合计** | **32** | |

**额外测试 hooks**:
- `CreateAgentTool.isStrictModeStatic()` 是 package-private static —— 其他 tool 复用 + 测试可以直接 assert 当 env=unset 时走 permissive branch

### B.4 政策实施细节（chat 2026-09-27 修订版）

| 场景 | LLM 行为 | tool 行为 | 用户感受 |
|------|----------|-----------|----------|
| 用户："帮我建一个 X agent" | (可选) 先 `ask_user_question` 展示 preview → 再 `create_agent` | 直接落盘 | 看到新 agent 出现在 picker |
| 用户："我需要并发处理 Y，建个 Z 专家" | 直接 `create_agent` 不打扰 | 直接落盘 | agent 已可用，无 modal |
| 用户没要求，primary 自己判断需要持久化 | 直接 `create_agent` | 直接落盘 | 不打扰 |
| `AETHERCODE_CREATE_AGENT_REQUIRE_CONFIRM=1` 部署 | 必须先 `ask_user_question` | 拒绝 + 提示 | 看到 confirm modal（UI 层） |
| `update_agent` 不存在 agent | LLM 改用 `create_agent` | 失败-fast + 提示 | 干净信号 |
| `delete_agent` 不存在 agent | LLM 意识到 typo | 失败-fast + 提示 | 干净信号 |
| 删除后 in-flight subagent | LLM 已知"不打断"，可建议用户等完成 | 返回 hint | 用户决定 |

**为什么 strict mode 不在 tool 内弹窗**：
- tool 引擎里没有 UI 通道；permission_request 要走额外的 JsonRpcPermissionPrompter round trip
- 用户已表达"任务驱动场景不要打扰" — strict mode 是 opt-in 的严格化开关
- chat 用户表达"default 不用 confirm；如果用户主动给'个性'，可以让用户补完（建议预览但不强制 confirm）"
- LLM 自己决定什么时候调 `ask_user_question` —— 这是最干净的语义

### B.5 兼容性 + 风险

| 项 | 影响 |
|----|------|
| `create_agent` / `update_agent` / `delete_agent` 是纯 additive tool | 老 client 看不到这三个 tool 名；不影响 R361 / R362 round 1 / 旧 desktop |
| Strict mode 通过 env var 切 | 默认 unset → permissive；想严格只需 set env，无需改代码 |
| AgentRegistry 的 create / update / delete 已存在 | Round 2 没动 AgentRegistry 的代码（只调现成方法）|
| 删除不可逆（hard delete） | 用户能 git / IDE history 找回；Round 1 决策文档论证过 |
| 失败 / missing registry 的 tool error message 都点向 `--agents-dir` 启动配置 | 用户/CLI 排障路径清晰 |

---

## 附录 C — Round 3 计划（详细设计，代码待写）

### C.1 `SubagentRegistry.retry(jobId)` 方法

```java
// SubagentRegistry.java
public synchronized SubagentJob retry(String jobId) {
    SubagentJob j = byId.get(jobId);
    if (j == null) throw new IllegalArgumentException("unknown jobId: " + jobId);
    if (j.status != Status.FAILED)
        throw new IllegalStateException("can only retry FAILED jobs (current=" + j.status + ")");
    j.status = Status.PENDING;
    j.error = "";
    j.startedAtMs = System.currentTimeMillis();
    j.finishedAtMs = 0;
    j.partial = "";
    return j;  // caller must re-attach a thread + start
}
```

### C.2 Watchdog 集成（默认 60s timeout）

```java
// SubagentRegistry.java — register(...)
public synchronized String register(String parentTaskId, String prompt, String role, String sessionId) {
    String jobId = nextId();
    long timeoutMs = Long.parseLong(
        System.getenv().getOrDefault("AETHERCODE_SUBAGENT_TIMEOUT_MS", "60000"));
    Watchdog w = Watchdog.create(jobId, timeoutMs, () -> {
        markFailed(jobId, "watchdog timeout after " + timeoutMs + "ms");
        // best-effort: interrupt the attached thread
        Thread t = threadById.get(jobId);
        if (t != null) t.interrupt();
    });
    SubagentJob j = new SubagentJob(jobId, parentTaskId, prompt, role, sessionId,
        Status.PENDING, 0, 0, "", null, w);
    byId.put(jobId, j);
    return jobId;
}
// on COMPLETED / CANCELLED → w.stop() 必须先于 markCompleted 避免 race
```

### C.3 `AgentTool.callMultiStep` retry 包装

```java
// AgentTool.java
private static Tool.ToolResult callMultiStep(...) {
    return RetryHelper.run(() -> doCallMultiStep(...),
        RetryPolicy.DEFAULT,  // 3 attempts, 1s + 2s backoff
        (attempt, err) -> LOG.warn("multi_step subagent attempt {} failed: {}", attempt, err));
}
```

### C.4 SubagentRetryTool + SubagentCancelTool（新文件）

```java
// SubagentRetryTool.java
public static final String NAME = "subagent_retry";
// 参数：job_id (required)
// 流程：
//   1. SubagentRegistry.instance().retry(job_id) → reset job to PENDING
//   2. 重新 attach thread + 调 runBackgroundJob(...)
//   3. fireChange(new RUNNING event)
// 返回 "subagent <job_id> retried, status=PENDING/RUNNING"

// SubagentCancelTool.java
public static final String NAME = "subagent_cancel";
// 参数：job_id (required), reason? (optional)
// 调 SubagentRegistry.instance().cancel(job_id, reason) — 已存在
// thread.interrupt() 触发 chat-client stream 中断
// 返回 "subagent <job_id> cancelled: <reason>"
```

### C.5 SubagentPanel Retry / Cancel 按钮

- `aethercode-desktop/src/components/SubagentPanel.tsx`
  - 已有 job 行：状态 FAILED → "Retry" 按钮 → 调 `rpc.subagentRetry({jobId})`
  - 已有 job 行：状态 RUNNING → "Cancel" 按钮 → 调 `rpc.subagentCancel({jobId, reason})`
  - 状态 COMPLETED → 显示 result + 折叠 body
- `aethercode-desktop/src/store/index.ts`
  - 加 `retrySubagent(jobId)` + `cancelSubagent(jobId, reason)` action
- `aethercode-desktop/src/lib/methods.ts`
  - 加 `rpc.subagentRetry({jobId})` + `rpc.subagentCancel({jobId, reason})` 包装

### C.6 RPC + tests

| RPC | tool | 测试 |
|-----|------|------|
| `subagentRetry({jobId})` | `subagent_retry` | `SubagentRegistryR362RetryTest`（3 cases） |
| `subagentCancel({jobId, reason})` | `subagent_cancel` | `SubagentRegistryR362WatchdogTest`（3 cases） |
| Watchdog integration | (transparent) | `AgentToolR362RetryTest`（2 cases — retry 成功 / 失败 3 次 FAILED） |
| UI 按钮 | (UI) | `SubagentPanel.test.tsx`（2 cases — FAILED 显 Retry / RUNNING 显 Cancel） |

---

## 附录 D — Round 3 执行记录 (2026-09-27)

Round 3 把 Round 1/2 已经具备的"可观察 + 可创建"补全为"可恢复":FAILED/CANCELLED subagent 可以被 retry,Watchdog 自动超时 kill。

### D.1 已有且复用（无需重写）

| 组件 | 文件 | 状态 | 备注 |
|------|------|------|------|
| `RetryPolicy.DEFAULT` | `aethercode-core/.../util/RetryPolicy.java` | ✅ 新迁入 core(打破 tools→sdk cycle) | 3 attempts, 1s+2s 指数退避 |
| `RetryHelper.run(callable, policy, sleeper)` | `aethercode-core/.../util/RetryHelper.java` | ✅ 同上 | 同步 retry 循环 |
| `Watchdog` | `aethercode-core/.../util/Watchdog.java` | ✅ 同上 | pollMs / timeoutMs / kick |
| `JsonRpcDispatcher` | `aethercode-protocol/.../server/JsonRpcDispatcher.java` | ✅ 已有 | 新 RPC handler 直接挂上 |

### D.2 Round 3 新增的 Java 代码

#### D.2.1 `SubagentRegistry.retry(jobId)` (新方法)

- 接受 `jobId`,在 finished map 里查找
- 状态机:FAILED / CANCELLED → reset 到 RUNNING;RUNNING / COMPLETED / unknown → 拒绝并返回 reason
- 重置 `startedAtMs / finishedAtMs / error / resultText / cancelReason / partialResult`
- 重新放入 running map + restart Watchdog
- 同步 fire 一个新的 RUNNING event(SubagentEvent)
- audit log 追加 RETRY entry(带 previousStatus)

`startedAtMs` 从 `public final long` 改为 `public volatile long` 以支持 reset。

#### D.2.2 Watchdog 集成

- 每个 background job 在 `register()` 时启动一个 per-job Watchdog
- 配置:`AETHERCODE_SUBAGENT_TIMEOUT_MS` env var(默认 60000ms)
- Poll:clamped 到 `max(100, timeoutMs / 5)`(保证 pollMs < timeoutMs)
- 在 `updatePartial()` 里调用 `touch()` 重置 `lastEventMs`
- 在 `markCompleted/markFailed/markCancelled` 里 `stopWatchdog()`(Watchdog.close 防止 scheduled executor 泄漏)
- 超时 callback:`markFailed(jobId, "watchdog timeout after Xms of silence")` + `thread.interrupt()`
- audit log 追加 WATCHDOG entry(poll + timeout 配置)

#### D.2.3 `SubagentRetryTool.java` (新文件, "subagent_retry")

```java
public static final String NAME = "subagent_retry";
// 参数：job_id (required)
// 流程：
//   1. SubagentRegistry.retry(jobId) → reset state to RUNNING
//   2. 若失败 → 拒绝并返回 reason("COMPLETED" / "RUNNING" / "no such job")
//   3. TaskRegistry.updateStatus(taskId, RUNNING) 重置子任务状态
//   4. AgentTool.runBackgroundJob(原 prompt + role + agentBody + multiStep) on fresh Thread
//   5. attachThread + start
//   6. 返回 "Retried subagent '<jobId>' (status=RUNNING)"
// 复用 task id：retry 不创建新 Task,/tasks 面板保持一行 per logical job
```

#### D.2.4 `SubagentCancelTool.java` (新文件, "subagent_cancel")

```java
public static final String NAME = "subagent_cancel";
// 参数：job_id (required), reason? (optional)
// 流程：
//   1. SubagentRegistry.instance().cancel(jobId, reason) → 调 Thread.interrupt()
//   2. 返回 "Cancelled subagent '<jobId>' (reason=<reason>)"
//   3. finished job:返回 "already finished" 消息(不报错)
//   4. unknown job:返回 "already finished" 消息
// Idempotent:已完成 job 上 cancel = no-op
```

#### D.2.5 `subagentRetry` RPC handler (`AetherCodeMethods.java`)

```java
public Object subagentRetry(Object params) {
    Map<String, Object> p = asMap(params);
    String jobId = stringOrThrow(p, "jobId");
    SubagentRegistry.RetryResult r = SubagentRegistry.instance().retry(jobId);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("ok", r.retried());
    out.put("jobId", jobId);
    out.put("retried", r.retried());
    if (!r.retried()) out.put("reason", r.reason());
    return out;
}
```

#### D.2.6 `AgentTool.callMultiStep` retry 包装

```java
// 把 engine.query() 包在 RetryHelper.run(callable, RetryPolicy.DEFAULT) 里
// Callable 抛任何 Exception → retry；空输出 → 当作 transient failure 也 retry
// 成功 on attempt N>1 → 在 result prefix 加 "[retried Nx]" 标记
// single-shot 路径不 retry(避免 token 浪费)
```

#### D.2.7 `SubagentPanel.tsx` Retry 按钮

- FAILED / CANCELLED 行 → 显示 "Retry" 按钮 + "Insert note" 按钮
- COMPLETED 行 → "Insert result" 按钮(原行为保留)
- RUNNING 行 → "Cancel" 按钮(原行为保留)
- Retry 点击 → `rpc_call({method: 'subagentRetry', params: {jobId}})`
- 按钮在 RPC in-flight 时显示 "retrying…"(防双击)

### D.3 解决 module cycle 的拷贝

SDK 模块依赖 tools 模块(SDK 的 AetherCodeEngine 引用 StandardTools),
但 R362 Round 3 需要 tools 依赖 SDK 的 Watchdog/RetryHelper/RetryPolicy。
直接加 tools→sdk dependency 会引入 cycle。

**解决方案**:把 Watchdog / RetryHelper / RetryPolicy 从 sdk 复制到 core
(双方都依赖 core)。SDK 保留原副本(向后兼容),core 是新源代码。
新增 `org.aethercode.core.util.{Watchdog, RetryHelper, RetryPolicy}`。

### D.4 Round 3 测试

| 测试类 | 测试数 | 覆盖 |
|--------|--------|------|
| `SubagentRegistryR362Round3Test` | 13 | retry() 状态机(6 cases)+ Watchdog integration(5 cases)+ audit log RETRY entry(2 cases) |
| `SubagentRetryToolR362Round3Test` | 7 | tool surface + refusal paths + audit log RETRY |
| `SubagentCancelToolR362Round3Test` | 7 | tool surface + idempotency + reason propagation |
| **Round 3 单元测试小计** | **27** | |
| `R362EndToEndTest` (新) | 13 | Round 1+2+3 全链路 in-process E2E |
| `R362JsonRpcE2ETest` (新) | 4 | JSON-RPC wire contract E2E (path traversal → INVALID_PARAMS; unknown job refused) |
| **测试总增加** | **44** | |

### D.5 模块测试 baseline

| Module | Before R362 | After R362 Round 3 | Δ |
|--------|-------------|---------------------|---|
| aethercode-cli | 67 | 67 | 0 |
| aethercode-protocol | 313 | 317 | +4 (R362JsonRpcE2ETest) |
| aethercode-core | (was no R362 tests) | unchanged | 0 |
| aethercode-sdk | unchanged | unchanged | 0 |
| aethercode-tools | 339 | 411 | +72 (32 R2 + 27 R3 + 13 E2E) |
| **Total** | ~1000+ | ~1100+ | +72 |

### D.6 兼容性 + 风险

| 项 | 影响 |
|----|------|
| `subagentRetry` RPC + `subagentRetry` 工具都是纯 additive | 老 client 看不到；不影响 R362 R1/R2 |
| `SubagentJob.startedAtMs` 从 final 改 volatile | 公开 API 兼容(读访问不变);语义变更(retry 后是新值)|
| `Watchdog` 移入 core(SDK 保留副本) | 调用方迁移路径:org.aethercode.sdk.Watchdog → org.aethercode.core.util.Watchdog |
| `RetryHelper` / `RetryPolicy` 同步 | 同上 |
| 删除 agent 不可逆 | 用户能 git / IDE history 找回;R1 决策文档论证过 |
| retry 死循环(3 attempts 之上) | RetryPolicy.DEFAULT 强制 cap = 3 |
| Watchdog 误杀长任务 | 用户通过 `AETHERCODE_SUBAGENT_TIMEOUT_MS` env 调高 |

### D.7 R362 收官

| Round | 状态 | Commit | Release |
|-------|------|--------|---------|
| 1 | ✅ SHIPPED | `8bfe022` (aethercode) · `8f1507c` (desktop) | r362.1 |
| 2 | ✅ SHIPPED | `054eae4` (aethercode) · `1b6190b` (doc) | r362.2 |
| 3 | ✅ SHIPPED | `bdea6ed` (aethercode) · `69d2acd` (E2E tools) · `d4eaea1` (E2E protocol) | r362.3 |

3 round 全部 shipped。aethercode-0.3.0-r362.3.zip 内置 R362 全集。

**测试覆盖总览**:
- Unit: 32 (R2) + 27 (R3) = 59 R362 新增
- E2E in-process: 13 (R362EndToEndTest) + 4 (R362JsonRpcE2ETest) = 17 R362 新增
- Total R362 测试: 76 个新增,全部 pass

### D.8 不在 R362 范围(明确 out of scope,后续 round 候选)

- A2A 协议(`aethercode-a2a/` 模块存在,R362 不碰)
- 跨 daemon 共享 agent(多用户 / 多机)
- Agent 自动评分 + 淘汰
- Per-agent tool whitelist(R362 用 role-based filter;agent_name 只换 system prompt)
