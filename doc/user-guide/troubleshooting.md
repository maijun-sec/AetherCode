# Troubleshooting — 常见问题排查

> 常见错误 + 修复方法。按症状分类。
>
> **整合自 `doc/TROUBLESHOOTING.md`** (R250+7 doc 重构),已翻译为中文。

---

## 1. "no current provider" — 第一次 query

**症状**: engine 启动了,但每个 query 都失败 "no current provider",或 `/state` 显示 `model:` 为空。

**原因**: 没配 provider。默认装好不带 provider。

**修复**: 编辑 `~/.aethercode/settings.yaml` (schema 看 [`../tech-docs/providers.md`](../tech-docs/providers.md)),重启 daemon。
- TUI: `Ctrl-C` 退出,再跑
- Desktop: 退出再开

---

## 2. Provider timeout

**症状**: query 挂 60s,失败 `rpc timeout after 60000ms: query`。

**原因**: LLM provider 慢或不通。可能是网络防火墙、代理、或者 provider 自己 incident。

**修复**:

1. 直接测 base URL: `curl <baseUrl>/v1/models` 应该返回 model 列表
2. 检查 API key: `curl -H "Authorization: Bearer <key>" <baseUrl>/v1/models`
3. TUI 里加超时: `JsonRpcClient.request(..., {timeoutMs: 120_000})` 支持 per-request override
4. 看 daemon 的 stderr 有没有 "connection refused" 或 TLS 错误

---

## 3. "context window exceeded"

**症状**: 长对话失败 "context window exceeded",或者 Desktop 的 `ContextMeter` 变红。

**原因**: transcript 涨超了 model 的 context 限制。不同 model 限制不同 (Claude Sonnet 200k, GLM-4 128k, 等)。

**修复**:

- **Auto-compact**: AetherCode 在 transcript 涨到 80% window 时自动压缩。等 `SideNote kind=compaction` 然后对话继续
- **Force compact**: TUI `/compact` (如果启用) 或 `compact` RPC;Desktop Settings 面板 "Memory" tab 有 "Compact now" 按钮
- **换大 context model**: `/model claude-opus-4-1-200k` (或你 provider 的大 context 选项)
- **新开 session**: Desktop `SessionList → "+ New"` (或 TUI `/sessions` → "new"),丢 transcript

---

## 4. "loop detected" — engine 自己停了

**症状**: query 半路停 `stopReason: loop_detected`。Desktop 显示 `LoopGuardBanner`。

**原因**: R101 的分层 loop detector 触发了。**同一个 tool call (或同一个错误消息)** 在最近 8 turn 里出现 3+ 次。engine 觉得 "这次不会收敛"。

**修复**:

1. **Ack 继续**: 点 `LoopGuardBanner` 的 "继续" (或调 `loopAck` RPC)。tier 重置,下一 turn 继续
2. **细化 prompt**: 如果 model 真的卡了,加更多 context (比如贴错误消息) 通常能解锁
3. **换 model**: 更便宜/更快的 model 有时能短路 loop,因为它不会太"努力"去 "修复" 问题
4. **关掉 detector**: Desktop Settings 有 "Loop detector" 开关 (R83+)。**Off = 不自动停**。Off 有风险 — 病态场景 engine 会一直跑

---

## 5. Agent step 失败 "agent not found"

**症状**: workflow 的 `kind: agent` step 失败 `agent not found: <name>`。

**原因**: Agent 目录不在 `~/.minimax/agents/<name>/agent.md`。或者 name 拼错了。

**修复**:

1. `/agents` (TUI) 或 `Settings → Agents` (Desktop) 看真实列表
2. 用列表里有的 name
3. 如果 Agent 应该存在,用 Settings editor 或 `createAgent` RPC 创建

---

## 6. TUI standalone exe 被 Windows SmartScreen 拦

**症状**: 双击 `ac-tui-standalone.exe` 弹 "Windows protected your PC"。

**原因**: exe 没 code-sign。SmartScreen 对所有 Microsoft Store 外的未签名 exe 都报警。

**修复**:

1. 点 "More info" → "Run anyway"。一次性确认
2. CI / 团队分发: 用 `signtool.exe sign /fd SHA256 /a ac-tui-standalone.exe` 签名。详细看 [`../tech-docs/packaging.md`](../tech-docs/packaging.md) 的 sign 步骤

---

## 7. Daemon 启动立刻挂

**症状**: TUI 刚 spawn 就说 "daemon disconnected"。

**原因**: daemon 的 JVM 启动时崩了。常见:

- **Java 版本错** (要 21+): `java -version` 应该显示 21.x
- **缺 `~/.aethercode/` 目录**: daemon 第一次跑会建,但权限问题可能挡住
- **端口冲突**: 用 `--http-port` 时,另一进程占着

**修复**:

1. 直接跑 daemon 看错误: `java -jar aethercode-<version>.jar --daemon 2>&1 | less`
2. 错误信息指向原因
3. 如果 "Address already in use",换端口: `--http-port 7001`
4. 如果 "Permission denied" on `~/.aethercode/`,`chmod 700 ~/.aethercode`

---

## 8. Workflow 子 session 报错

**症状**: workflow step 失败 "child session error: <message>"。

**原因**: per-step 子 session (用于 `kind: agent` / `kind: skill` / `kind: llm` step) 失败了。常见:

- Agent / Skill 不存在
- Model provider 不通
- LLM call 超时

**修复**:

1. 交互式跑 step: 在 TUI 打开 Agent / Skill,跑同样 prompt。错误信息会更具体
2. step 上加 `on_error: continue` 跳过失败,让下游 step 继续
3. `kind: llm` step 显式 `model: anthropic/claude-sonnet-4` (或稳定的 model)

---

## 9. Desktop daemon 重启后 UI stale

**症状**: Desktop `StatusBar` / `Header` 等在 `switchProvider` 或 `setModel` 后显示 stale 数据。

**原因**: React store 在 switch 后没 refetch `getState`。R109-3 修了 hot-swap,但 store 需要显式 `refreshState()` 调用才能拿到新值。

**修复**: 点 UI 任意位置触发 re-render (会调 `refreshState`);或重启 Desktop。

---

## 10. TUI 在 `stream_event` 上卡死

**症状**: TUI 显示 streaming 响应,永远不结束。

**原因**: daemon 流中崩了,但 TUI 的 readline 还连着死 pipe。TUI 不一定立刻检测到。

**修复**: `Ctrl-C` 退出。重启 TUI。**对话从上一个 committed turn 继续** (Desktop 保存 transcript;**TUI 不保存**)。

---

## 11. IDEA 插件 "daemon not reachable"

**症状**: IDEA 插件的 AetherCode tool window 显示 "daemon not reachable"。

**原因**: 插件需要 HTTP daemon。默认 stdio daemon (TUI 和 Desktop 用的) 从 IDE 触不到。

**修复**: 启 HTTP 模式 daemon:

```bash
java -jar aethercode-<version>.jar --daemon --http-port 7000
```

然后在 IDEA 插件的 settings,设 "Daemon URL" 为 `http://localhost:7000`。

---

## 12. 进一步帮助

- [`../tech-docs/api.md`](../tech-docs/api.md) — 完整 JSON-RPC surface
- [`../tech-docs/architecture.md`](../tech-docs/architecture.md) — 什么跑在哪
- [`../round-notes/`](../round-notes/) — 各种 R-round 过程记录,大多含 "known issues / workarounds" 节
