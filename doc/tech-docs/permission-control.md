# Permission Control

> 工具调用前的统一权限决策系统,核心是 `ProjectPermissionPolicy` + `MatrixPermissionPolicy` 包装。
>
> **关键代码**: `aethercode-permission/src/main/java/org/aethercode/permission/`
>
> **核心接口**: `aethercode-core/src/main/java/org/aethercode/core/engine/PermissionPolicy.java`

---

## 1. 整体架构

```
Tool 调用请求 (tool, input, ctx)
  ↓
[Matrix 层 (R207)]          ← 第一道闸, 3 维查表 (tool × pathGlob × opKind) → Action
  ├─ DENY  → PermissionResult.Deny (立即返回, 不查规则/mode)
  ├─ ALLOW → PermissionResult.Allow (立即返回, override mode)
  └─ ASK   → fall through
  ↓
[Rules 层]                   ← 经典 deny → ask → allow
  ├─ deny rule 匹配 → Deny
  ├─ ask rule 匹配  → resolveAsk()
  └─ allow rule 匹配 → Allow
  ↓
[Mode fallback]              ← 5 种 mode 之一
  ├─ BYPASS_PERMISSIONS  → Allow
  ├─ AUTO_READ_ONLY      → Allow (read-only 工具 only)
  ├─ ACCEPT_EDITS        → 智能授权 (read-only auto, mutating ask)
  ├─ ACCEPT_TASK         → 子任务边界内 auto, 跨边界 ask
  └─ DEFAULT / PLAN / ASK_BEFORE_TOOL → resolveAsk()
  ↓
PermissionResult.Allow / Deny / Ask
  ↓
[Audit log]                  ← 所有决策写入 PermissionAuditLog
```

**3 层决策**:
- **Matrix**: 用户显式配置的 3 维查表,首道防线
- **Rules**: 经典 deny/ask/allow 规则
- **Mode**: 当规则没匹配时,按 session mode 兜底

---

## 2. 关键类索引

| 类 | 文件 | 字节 | 作用 | round |
|---|---|---|---|---|
| `ProjectPermissionPolicy` | `ProjectPermissionPolicy.java` | 12,624 | 核心决策 (rules + mode) | R130 |
| `MatrixPermissionPolicy` | `MatrixPermissionPolicy.java` | 15,174 | 包装类, 先查 Matrix | R207 |
| `PermissionAuditLog` | `PermissionAuditLog.java` | 6,619 | 所有决策审计 | R130 |
| `PermissionReasoner` | `PermissionReasoner.java` | 5,054 | LLM 解释为什么需要权限 | R130 |
| `CommandAllowlist` | `CommandAllowlist.java` | 4,617 | bash read-only 命令白名单 | R130 |
| `ToolSafeList` | `ToolSafeList.java` | 3,986 | 工具安全列表 | R130 |
| `QuickAllowList` | `QuickAllowList.java` | 2,054 | session 内快速 allow | R130 |
| `SettingsPermissions` | `SettingsPermissions.java` | 1,930 | 3 段规则 (deny/ask/allow) | R130 |
| `Rule` | `Rule.java` | 1,643 | 规则结构 + 匹配逻辑 | R130 |
| `ToolPermissionPrompter` | `ToolPermissionPrompter.java` | 799 | 弹窗接口 (UI 桥接) | R130 |

外部依赖 (在 `aethercode-config`):
- `PermissionMatrix` (3 维查表)
- `OpKind` (6 种操作类型 enum)
- `OpKindDetector` (从 tool + input 自动判断 opKind)
- `Action` (DENY / ALLOW / ASK)
- `SkipConfirmationRegistry` (session 内 skip counter)

---

## 3. ⭐ 6 种 OpKind

`OpKind.java` — 任何 tool 调用自动归类为 6 种操作之一:

| OpKind | 含义 | 例子 |
|---|---|---|
| `READ` | 读文件 / 读数据 | `file_read`, `cat`, `head`, `tail` |
| `LIST` | 列目录 / 列资源 | `glob`, `ls`, `list_dir` |
| `CREATE` | 新建 | `mkdir`, `touch` |
| `WRITE` | 覆盖写 | `file_write`, `echo > file` |
| `DELETE` | 删除 | `rm`, `file_delete` |
| `EXEC` | 通用执行 | `bash` 任何命令, 任何非上述 |

`OpKindDetector.detect(toolName, input, projectRoot)` 自动判断。

---

## 4. ⭐ PermissionMatrix (R207) — 第一道闸

3 维查表结构:

```json
// .aethercode/config.json
{
  "permissionMatrix": {
    "entries": {
      "file_write": {
        "*":          { "WRITE": "ASK"   },
        "*.log":      { "WRITE": "ALLOW" },
        "secrets/*":  { "WRITE": "DENY"  }
      },
      "bash": {
        "*":          { "EXEC":  "ASK"   }
      },
      "file_read": {
        "*":          { "READ":  "ALLOW" }
      }
    }
  }
}
```

**3 维**: `toolName × pathGlob × opKind → Action`

**Action 3 选 1**:
- `DENY` — 立即拒绝,**不查规则/mode** (override 一切)
- `ALLOW` — 立即允许,**override BYPASS_PERMISSIONS** (显式 allow 优先)
- `ASK` — fall through 到 rules + mode

**Lookup 算法**:
```java
public Action lookup(String toolName, String path, OpKind opKind) {
    Map<String, Map<String, String>> toolBucket = entries.get(toolName);
    if (toolBucket == null) return Action.ASK;
    // path-glob best match
    for (Map.Entry<String, Map<String, String>> pathEntry : toolBucket.entrySet()) {
        if (pathGlobMatches(pathEntry.getKey(), path)) {
            String action = pathEntry.getValue().get(opKind.name());
            if (action != null) return Action.parse(action);
        }
    }
    return Action.ASK;
}
```

**Matrix 不可达时** (tool 无 entry): 返回 ASK → 走 rules + mode。`withOverride()` 允许 session 级 override。

---

## 5. ⭐ ProjectPermissionPolicy — 核心决策

### 5.1 4 步决策流

```java
public CompletableFuture<PermissionResult> check(Tool tool, Map<String, Object> input, Tool.CallContext ctx) {
    String prompt = extractPrompt(tool, input);

    // 1. deny rules (最优先)
    for (Rule r : rules.deny) {
        if (r.matchesTool(tool.name()) && r.matchesPrompt(prompt)) {
            return CompletableFuture.completedFuture(PermissionResult.Deny.of(msg));
        }
    }
    // 2. ask rules
    for (Rule r : rules.ask) {
        if (r.matchesTool(tool.name()) && r.matchesPrompt(prompt)) {
            return resolveAsk(tool, input, "ask rule: " + tool.name());
        }
    }
    // 3. allow rules
    for (Rule r : rules.allow) {
        if (r.matchesTool(tool.name()) && r.matchesPrompt(prompt)) {
            return CompletableFuture.completedFuture(new PermissionResult.Allow(input));
        }
    }
    // 4. fall back to mode
    return switch (mode) { ... };
}
```

### 5.2 ⭐ 6 种 PermissionMode

```java
public enum PermissionMode {
    BYPASS_PERMISSIONS,  // Allow 一切
    AUTO_READ_ONLY,      // Allow read-only 工具
    ACCEPT_EDITS,        // 智能授权 (smart permission)
    DEFAULT,             // Ask 大部分
    PLAN,                // 同 DEFAULT
    ASK_BEFORE_TOOL,     // 显式 "interrup me before every non-read-only"
    ACCEPT_TASK          // 子任务边界内 auto, 跨边界 ask
}
```

| Mode | read-only tool | mutating tool | 备注 |
|---|---|---|---|
| `BYPASS_PERMISSIONS` | Allow | Allow | 全部 auto, 慎用 |
| `AUTO_READ_ONLY` | Allow | Ask | 镜像 Claude Code 的 safe mode |
| `ACCEPT_EDITS` (智能授权) | Allow | Ask | UI 标签 "智能授权" |
| `DEFAULT` | Allow (通常) | Ask | 默认 |
| `PLAN` | Allow (通常) | Ask | 同 DEFAULT, 用于 plan mode |
| `ASK_BEFORE_TOOL` | Ask (可能) | Ask | 显式 "interrupt me" |
| `ACCEPT_TASK` | Allow (in-task) | Ask (cross-task) | 子任务粒度 |

### 5.3 ⭐ ACCEPT_EDITS 智能授权 (UI 标签 "智能授权")

```java
case ACCEPT_EDITS -> resolveSmart(tool, input);

private CompletableFuture<PermissionResult> resolveSmart(Tool tool, Map<String, Object> input) {
    if (tool.isReadOnly(input)) {
        return CompletableFuture.completedFuture(new PermissionResult.Allow(input));
    }
    return resolveAsk(tool, input, tool.name() + " requires approval (智能授权模式)");
}
```

**关键**: 用 `Tool.isReadOnly(input)` 接口(各 tool 自行 override):
- `BashTool.isReadOnly()` — 用 `CommandAllowlist` 分类 (ls/cat/pwd/find/head/tail/grep/wc/stat/df/du/tree 算 read-only,**拒绝**含 `>` / `|` / `&&` / `rm` / `mv` 的命令)
- `FileReadTool.isReadOnly()` — 直接 true
- `GlobTool.isReadOnly()` — true
- `WebSearchTool.isReadOnly()` — true
- 写工具 (`FileWriteTool` 等) — false

**好处**:
- 用户切到 ACCEPT_EDITS,不再为 `ls` `cat` `pwd` 弹窗
- 写操作 / 删除 / 危险 bash 仍 ask
- **零循环依赖**: `aethercode-permission` 不用 import `aethercode-tools` 的具体类,通过 `Tool` 接口 dispatch

### 5.4 ⭐ ACCEPT_TASK — 子任务边界

```java
case ACCEPT_TASK -> resolveAcceptTask(tool, input, ctx);

private CompletableFuture<PermissionResult> resolveAcceptTask(
        Tool tool, Map<String, Object> input, Tool.CallContext ctx) {
    Object ctxTaskId = ctx == null ? null : ctx.extra("subTaskId");
    if (ctxTaskId == null) {
        // 无 subTaskId → 视为"在当前 task" → auto-allow
        return CompletableFuture.completedFuture(new PermissionResult.Allow(input));
    }
    Object currentTaskId = currentSubTaskId == null ? null : currentSubTaskId.get();
    if (currentTaskId != null && currentTaskId.equals(ctxTaskId)) {
        // 同 sub-task → auto-allow (用户已批)
        return CompletableFuture.completedFuture(new PermissionResult.Allow(input));
    }
    // 跨 sub-task → ask 一次
    return resolveAsk(tool, input, "new sub-task boundary: " + tool.name() + " requires approval");
}
```

**机制**:
- 引擎在每个 sub-task 边界调 `setCurrentSubTaskId(id)`
- 后续 call 携带 `ctx.extras["subTaskId"]`,匹配 → auto-allow
- 跨 sub-task → 弹 1 次窗
- 旧的 ACCEPT_TASK 一律 auto-allow,新的改成"边界内 auto"

### 5.5 ⭐ 模式热切换

`withMode(newMode)` 返回新实例,共享 `prompter` 和 `rules`,**无 I/O**。

```java
public ProjectPermissionPolicy withMode(PermissionMode newMode) {
    ProjectPermissionPolicy p = new ProjectPermissionPolicy(
            this.rules, newMode, this.prompter);
    if (this.currentSubTaskId != null) {
        p.currentSubTaskId = new AtomicReference<>(this.currentSubTaskId.get());
    }
    return p;
}
```

为什么需要: `AetherCodeMethods.setPermissionMode` RPC 改 mode,如果只改 `AppState.permissionMode`,policy 仍用构造时的 mode,改了没用。`withMode` 让运行时 mode 真正到达 live policy。

---

## 6. ⭐ Bash Read-Only 识别 (CommandAllowlist)

`CommandAllowlist.java` + `BashTool.isReadOnly(input)` — 决定 bash 命令是否 read-only。

**Whitelisted (read-only)**:
- `ls` `cat` `pwd` `find` `head` `tail` `grep` `wc` `stat` `df` `du` `tree` `git status` `curl <read>` 等

**拒绝 (即使在白名单也按 mutating 处理)**:
- 含 `>` (重定向)
- 含 `|` (pipe)
- 含 `&&` / `||` / `;` (command chain)
- 含 `rm` `mv` `cp` `chmod` `chown` (破坏性)
- 含 `sudo` `mkfs` `dd` (critical)

实现: 朴素 token 扫描,**不解析 shell** — 接受少量误报(read-only 命令含 `|` 也被 reject),不接受漏报。

---

## 7. ⭐ Skip-Confirmation 机制 (R207+)

`MatrixPermissionPolicy` 支持 session 内"skip 弹窗 N 次"机制 — 用户在弹窗里选"auto-allow 接下来 5 次"。

```java
private volatile SkipConfirmationRegistry skipRegistry = null;

// 4 个 listener
private volatile IntConsumer onSkipConsumed = null;                    // 每次 skip 用掉
private volatile BiConsumer<String, Integer> onToolSkipConsumed = null; // (tool, remaining) per-tool
private volatile BiConsumer<String, Integer> onSkipLow = null;          // (sessionId, newRemaining) 水位
private volatile int lowWaterline = 5;                                  // 默认水位
```

**水位监听** (`onSkipLow`): 当 counter 从上往下穿过水位线(5→4 / 100→4)时,触发**一次**通知,告诉用户"快用完了"。**仅一次** per session。

**`SkipConfirmationRegistry`**: 跨 session 共享的 registry,记录每个 session 的剩余 skip 次数。

---

## 8. 配置示例

```json
// .aethercode/config.json
{
  "permissions": {
    "deny":  [
      { "tool": "bash", "prompt": "rm -rf", "reason": "destructive" },
      { "tool": "bash", "prompt": "sudo *" }
    ],
    "ask":   [
      { "tool": "file_write", "prompt": "**/*.sql" }
    ],
    "allow": [
      { "tool": "file_read",  "prompt": "**" }
    ]
  },
  "permissionMode": "ACCEPT_EDITS",
  "permissionMatrix": {
    "entries": {
      "file_write": {
        "*":         { "WRITE": "ASK" },
        "*.log":     { "WRITE": "ALLOW" },
        "secrets/*": { "WRITE": "DENY" }
      },
      "bash": {
        "*":         { "EXEC": "ASK" }
      }
    }
  }
}
```

---

## 9. 关键测试

```
ProjectPermissionPolicyR130Test.java     (基础 4 步流程)
MatrixPermissionPolicyR207Test.java      (Matrix 包装)
MatrixPermissionPolicyR207WithModeTest.java (Matrix × mode 组合)
ProjectPermissionPolicyR203Test.java     (mode 切换)
PermissionAuditLogTest.java              (审计)
CommandAllowlistTest.java                (bash 分类)
RuleMatchingTest.java                    (规则匹配)
```

---

## 10. 已知 trade-off

| 决策 | 优点 | 缺点 |
|---|---|---|
| 朴素 token 扫描 bash | 快, 无 shell 解析依赖 | 少量 false positive (`cat \|` 算 mutating) |
| ACCEPT_EDITS 用 `isReadOnly` 接口 | 零循环依赖 | 各 tool 必须正确 implement isReadOnly |
| 6 种 OpKind | 粒度细 | 边界 case 难归类 (curl GET vs POST) |
| Matrix first, rules second, mode last | 配置灵活 | 调试要 trace 3 层 |
| 模式热切换用 `withMode()` | 无 I/O | 每改 mode 创建新对象 |
| 5 个 listener (skip 系列) | 灵活通知 | 调试难 |
| 水位线只触发一次 | 防 spam | 错过时机就没了 |
| `synchronized` 单 prompter | 简单 | 跨线程弹窗要序列化 |

---

## 11. 关键 round 引用

- **R130**: 引入 ProjectPermissionPolicy + 3 段规则 + 5 种 mode
- **R203**: 模式热切换 (`withMode()` 修复 setPermissionMode 不生效 bug)
- **R207**: 引入 MatrixPermissionPolicy (3 维查表) + Skip-Confirmation
- **R244+**: 跨 surface sync (TUI/Desktop/CLI 共享权限配置)
- **R250+**: 智能授权 UI 标签 + ACCEPT_TASK 边界优化
- 详细过程见 `../round-notes/` 相应文档
