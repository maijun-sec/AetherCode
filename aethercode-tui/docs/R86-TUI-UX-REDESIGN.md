# R86 — TUI 体验重做

> 日期：2026-08-15  
> 状态：已落地（TUI bundle + Java 引擎均已 build，191 TS 单元测试 + 70 Java permission 测试全 pass）

## 背景

之前的 TUI 在用户体验上有 4 个问题：

1. **构建**：Desktop MSI 经常因为 WiX 下载超时挂掉
2. **任务执行**：每个工具调用都会被全屏 Permission 模态打断
3. **视觉混乱**：思考内容、工具结果、决策请求混在一起，看不清
4. **没有项目级 batch 确认**：每次都要单独按 Y

这一轮针对这 4 个问题做了一次性重做。

---

## 1. 构建兜底 — `package.ps1 -SkipMsi` 默认开启

### 改了什么

`package.ps1` 加了一个 `-SkipMsi` 开关，**默认是开**。它把 desktop 步骤从 `npx tauri build` 换成 `npx tauri build --no-bundle`，MSI / NSIS 那些 installer 就不打了，只产 exe。

同时把 cargo 自动加入 PATH（找 `$env:USERPROFILE\.cargo\bin` 这几个常见位置），不用手动设环境变量。

### 怎么用

```powershell
# 日常构建（推荐）
.\package.ps1 -SkipTests           # 跳过 mvn test，跳过 MSI

# 真要打 MSI（带 -SkipMsi:$false）
.\package.ps1 -SkipTests -SkipMsi:$false
```

产物：`release\aethercode-0.2.1\aethercode-0.2.1.jar` + `ac-tui.js` + `aethercode-desktop.exe`，**3 件就够用**，MSI 是可选的。

---

## 2. 任务中途不再中断 — `ACCEPT_TASK` 模式

### 改了什么

Java 端加了新权限模式 `ACCEPT_TASK`：

- 单 sub-task 内**自动放行所有写操作**（包括 bash / file_write / file_edit）
- 跨 sub-task 边界时**才弹一次决策**
- read-only 工具永远自动放行（跟之前一样）

sub-task 边界 = 模型调用 `sub_todo_write` 切状态时。引擎把这当作 stage 切换点。

### 怎么用

```java
// 在 AetherCodeEngine.Builder 里
builder().permissionMode(PermissionMode.ACCEPT_TASK).build();
```

或通过 JSON-RPC：

```bash
curl -X POST -d '{"jsonrpc":"2.0","id":1,"method":"setPermissionMode","params":{"mode":"ACCEPT_TASK"}}' ...
```

### 关键文件

- `aethercode-core/src/main/java/org/aethercode/core/permission/PermissionMode.java` — 加 enum 值
- `aethercode-permission/src/main/java/org/aethercode/permission/ProjectPermissionPolicy.java` — `resolveAcceptTask` 实现
- `aethercode-core/src/main/java/org/aethercode/core/engine/StreamingToolExecutor.java` — 接收 subTaskId + 推给 policy
- `aethercode-core/src/main/java/org/aethercode/core/engine/QueryEngine.java` — 在 `emitSubTaskTransitions` 里更新 current sub-task

### 行为细节

- **stale state 不致命**：每次新 query 开始时 `lastSubTaskStatus.clear()`，sub-task tracker 重置
- **race 安全**：sub-task id 用 `AtomicReference`，多线程并发切换 task 不会脏读
- **fallback 友好**：没有 subTaskId 在 CallContext 里（老 caller）→ 默认放行，避免误伤

---

## 3. 视觉分层 — Thinking / Reply / Tools / Permission

### 改了什么

| 之前 | 之后 |
|------|------|
| 思考内容跟正文混在一起 | 拆到独立 `thinking` role，dim 灰色，默认折叠 |
| Permission 是全屏模态，把 TUI 整个替换掉 | 改成**内联决策卡**，固定在 scrollback 顶部 |
| section 没有 header | 每个 turn 有 emoji 头：`🧠 Thinking` / `💬 Reply` / `🔧 Tools` / `⚠ Permission` |

`<think>...</think>` 标签现在在 reducer 里被解析成独立 turn。算法：

1. 每次 `text_delta` 事件，把 lookahead 跟新 delta 拼起来
2. 从左到右扫，找最近的 `<think>` 或 `</think>`
3. 找到的 → flush 之前的 text 进对应 mode 的 turn，切 mode
4. 找不到的 → 剩下的都进 lookahead，等下一个 delta

`streamEnd` 动作负责 flush 任何遗留的 lookahead（避免丢尾巴）。

### 怎么用

- **Ctrl-I**：切换 thinking 显示/隐藏（默认 on）
- **Tab / Enter**：展开 / 折叠单个 turn（跟之前一样）
- **Esc**（在 decision pending 时）：折叠 inline permission card，但 keys A/T/P/U/D/N 仍生效

### 关键文件

- `aethercode-tui/src/state.ts` — 新增 `thinking` role、`streamingMode`、`tagLookahead`、新的 `streamText` reducer
- `aethercode-tui/src/components/Scrollback.tsx` — 加 `Thinking` 渲染 + `PermissionCard` 内联组件
- `aethercode-tui/src/components/InputBox.tsx` — decision pending 时 input 改成 read-only
- `aethercode-tui/src/tui.tsx` — 移掉全屏 `PermissionModal`，改内联

### 测试

`aethercode-tui/scripts/test/r86-thinking.test.mjs` — 8 个测试覆盖 thinking parser（split tag、multiple cycles、streamEnd flush）

---

## 4. 项目级 batch 确认

### 改了什么

Permission 决策卡现在有 **5 个选项**（之前 4 个）：

| 键 | 行为 | scope | 持久化 |
|----|------|-------|-------|
| A | 允许这一次 | session | 不落盘 |
| T | 允许这个 task（到下次 query） | session | 不落盘 |
| **P** | 允许这个项目 | **project** | `<cwd>/.aethercode/permissions.json` |
| **U** | 允许这个用户（所有项目） | **user** | `~/.aethercode/permissions.json` |
| D | 拒绝这一次 | session | 不落盘 |
| N | 拒绝这个工具 | session | 不落盘 |

`permissionPolicyOverride` RPC 的 `scope` 参数现在支持 `session` / `project` / `user`。

### 文件结构

```json
// <cwd>/.aethercode/permissions.json 或 ~/.aethercode/permissions.json
{
  "version": 1,
  "rules": [
    { "tool": "bash", "prompt": "git status", "reason": "R86 TUI override (project)" },
    { "tool": "file_write", "prompt": "D:/work/.*\\.md", "reason": "R86 TUI override (user)" }
  ]
}
```

启动时 `AetherCodeEngine` 的构造器会读这两个文件，**追加**到 in-memory allow 列表。文件不存在或格式错误 → 静默忽略（不影响主流程）。

### 关键文件

- `aethercode-protocol/.../AetherCodeMethods.java` — `permissionPolicyOverride` 扩展 + `persistedRulesFile` helper
- `aethercode-sdk/.../AetherCodeEngine.java` — `mergePersistedRules` 在 engine init 末尾调用
- `aethercode-tui/src/tui.tsx` — `replyPermission` 加 `rememberScope` 参数

### 注意

- 落盘是**追加**语义：现有 rule 不删，新 rule 加在末尾
- 不做去重（成本小，去重了反而可能掩盖用户意图）
- 建议把 `<cwd>/.aethercode/permissions.json` 加进 `.gitignore`，或者按团队约定决定是否入库

---

## 跨项目 lessons

1. **TS 端 streamText parser** — `text_delta` 事件可能切分 `<think>` 标签，必须有 lookahead 缓冲；最简算法是拼 lookahead + 新 delta，然后 `indexOf` 找 tag，剩下的当 lookahead
2. **Java 端 PermissionMode 切换** — 加新 mode 时务必更新所有 `switch` 表达式（PermissionReasoner 也是）；编译期会逼出所有遗漏
3. **跨模块的接口** — `StreamingToolExecutor` 不能直接 import `ProjectPermissionPolicy`（包反向依赖），用 `PermissionPolicy.setCurrentSubTaskId` 默认实现做桥
4. **构建期 vs 运行时** — Tauri MSI 是运行时下载 WiX 的，构建期不能保证网络；加 `--no-bundle` 兜底是性价比最高的修复

---

## 验证清单

- [x] `npm run build` 在 aethercode-tui 跑通（1.5 MB bundle）
- [x] `mvn test` 在 aethercode 全模块跑通（426 + 70 = 496 tests）
- [x] `.\package.ps1 -SkipTests` 产出 3 件 release 产物
- [x] desktop Tauri exe 仍能构建（19 min，cargo 在 PATH 后）
- [x] TUI 191 个 TS 单元测试 + Java permission 70 个测试全 pass
