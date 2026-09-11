# Hook System

> Tool 调用生命周期的拦截点,纯函数组合,用于 deny-list / 审计 / 遥测 / 输入转换。
>
> **关键代码**: `aethercode-core/src/main/java/org/aethercode/core/tool/ToolHook*.java`

---

## 1. 核心设计

```java
public interface ToolHook {
    record Context(String toolName, Map<String, Object> input, Map<String, Object> attributes) {}
    record Result(Object output, boolean isError) {
        public static Result ok(Object o)   { return new Result(o, false); }
        public static Result error(Object o){ return new Result(o, true); }
    }

    default List<String> toolFilter() { return List.of(); }       // 空 = 全部
    default Context pre(Context ctx)  { return ctx; }              // 前置
    default Result post(Context ctx, Result result) { return result; }  // 后置
    default Result deny(Context ctx)  { return null; }             // 拒绝 (短路径)
}
```

**3 个回调**:
- `pre(ctx)` — 调用前,可改 ctx.input (返回新 Context)
- `deny(ctx)` — 拒绝调用,返回非 null Result 即短路径,工具不跑
- `post(ctx, result)` — 调用后,可改 result (返回新 Result)

**关键约束**:
- Hook 是**纯函数** (无 I/O),可干净组合
- `Context.input` 不可变 (构造时 copy),但可被 `pre` 返回新 Context 替换
- `Context.attributes` 可变 — hook 间可传 facts (e.g. `setAttribute("elapsedMs", 42)`)
- `toolFilter` 空 = 全部 tool,非空 = 只匹配列出的

---

## 2. ⭐ ToolHookRegistry — 3 阶段调度

```java
public class ToolHookRegistry {
    private final List<ToolHook> hooks = new CopyOnWriteArrayList<>();
    ...
}
```

### 2.1 调度顺序

| 阶段 | 顺序 | 行为 |
|---|---|---|
| **pre** | 注册顺序 (FIFO) | 第一个 hook 先跑,后续看到前一个的 ctx 变化 |
| **deny** | 注册顺序 (FIFO) | **第一个**非 null deny 短路径,工具不跑 |
| **post** | **反序** (LIFO) | 最后注册的 hook 先看到 result,最先生成的最后处理 |

**为什么 pre 正序 / post 反序**:
- `pre` 像栈 push,层层包装 input
- `post` 像栈 pop,层层处理 result (类似 middleware onion)

### 2.2 3 阶段调用

```java
// 1. PRE 阶段 (可能改 input)
public HookPreOutcome runPre(String toolName, Map<String, Object> input) {
    Context ctx = new Context(toolName, input, new HashMap<>());
    for (ToolHook h : hooks) {
        if (!matches(h, toolName)) continue;
        ctx = h.pre(ctx);
    }
    // 2. DENY 阶段 (可能短路径)
    for (ToolHook h : hooks) {
        if (!matches(h, toolName)) continue;
        Result d = h.deny(ctx);
        if (d != null) return new HookPreOutcome(ctx, d);
    }
    return new HookPreOutcome(ctx, null);
}

// 3. POST 阶段 (可能改 result)
public Result runPost(Context ctx, Result result) {
    Result current = result;
    for (int i = hooks.size() - 1; i >= 0; i--) {
        ToolHook h = hooks.get(i);
        if (!matches(h, ctx.toolName())) continue;
        current = h.post(ctx, current);
    }
    return current;
}
```

### 2.3 HookPreOutcome

```java
public record HookPreOutcome(Context context, Result denial) {
    public boolean denied() { return denial != null; }
}
```

调用方:
```java
HookPreOutcome pre = registry.runPre(tool.name(), input);
if (pre.denied()) {
    return pre.denial();          // 短路径,工具不跑
}
// 跑工具
Result raw = tool.call(pre.context().input());
// 跑 post (LIFO)
Result final_ = registry.runPost(pre.context(), raw);
```

---

## 3. ⭐ 2 个工厂 helper

### 3.1 `denyIf(predicate, resultFor)`

```java
public static ToolHook denyIf(Predicate<Context> pred, Function<Context, Result> resultFor) {
    return new ToolHook() {
        @Override public Result deny(Context ctx) {
            return pred.test(ctx) ? resultFor.apply(ctx) : null;
        }
    };
}
```

**用法**:
```java
registry.add(denyIf(
    ctx -> ctx.toolName().equals("bash") && ctx.input().get("command").toString().contains("rm -rf"),
    ctx -> Result.error("blocked: rm -rf is not allowed")
));
```

### 3.2 `transformInput(fn)`

```java
public static ToolHook transformInput(Function<Map<String, Object>, Map<String, Object>> fn) {
    return new ToolHook() {
        @Override public Context pre(Context ctx) {
            return new Context(ctx.toolName(), fn.apply(ctx.input()), ctx.attributes());
        }
    };
}
```

**用法**:
```java
registry.add(transformInput(input -> {
    Map<String, Object> copy = new HashMap<>(input);
    String path = (String) copy.get("path");
    if (path != null) copy.put("path", Path.of(path).toAbsolutePath().toString());
    return copy;
}));
```

---

## 4. ⭐ 典型使用场景

| 场景 | Hook 实现 | 阶段 |
|---|---|---|
| **Deny-list** | `denyIf(rm -rf 匹配, 返回 error)` | deny |
| **Audit log** | `post(ctx, result) → log 到 JSONL` | post |
| **Telemetry** | `post(ctx, result) → 记录 latency / token` | post |
| **Input 转换** | `pre(ctx) → 改 path 绝对化` | pre |
| **Redaction** | `post(ctx, result) → 删 output 里的 secret` | post |
| **Rate limit** | `denyIf(超过 100 calls/min, 返回 error)` | deny |
| **Output 截断** | `post(ctx, result) → 超 N 行截断` | post |

---

## 5. 4 个关键设计选择

| 设计 | 原因 |
|---|---|
| Hook 纯函数 (无 I/O) | 可干净组合, 调试可重现 |
| `Context.input` immutable | 防止 hook 互相干扰, 改 input 必须返回新 Context |
| `Context.attributes` mutable | 跨 hook 通信 (e.g. "elapsedMs" 算完后传下游) |
| `pre` 正序, `post` 反序 | middleware onion 模式, 跟 Express / Koa 一致 |
| `deny` 短路径 | 第一道闸, 工具不跑 = 0 cost |
| `CopyOnWriteArrayList` | 注册 / 注销并发安全, reader 无锁 |

---

## 6. ⭐ 注册的 7 个内置 hook (R151 起)

| Hook | 作用 | 阶段 |
|---|---|---|
| `PathSanitizerHook` | path 解析 + 相对路径禁止跳出 project root | pre |
| `BashSafetyHook` | bash 命令 token 扫描 (拒绝 `>`, `\|`, `&&`, `rm`, `mv` 等) | pre + deny |
| `PermissionCheckHook` | 调 `ProjectPermissionPolicy.check` | deny |
| `AuditLoggingHook` | 写 JSONL audit log (tool name, input hash, result, latency) | post |
| `TelemetryHook` | 记录 counter (per-tool 调用次数) | post |
| `RedactionHook` | output 里的 API key / token 替换成 `***` | post |
| `TruncationHook` | output 超 50KB 截断 | post |

`PathSanitizerHook` 详情:
```java
registry.add(transformInput(input -> {
    String path = (String) input.get("path");
    if (path != null) {
        Path resolved = Path.of(path).toAbsolutePath().normalize();
        if (!resolved.startsWith(projectRoot)) {
            throw new SecurityException("path escapes project root: " + path);
        }
        return Map.of("path", resolved.toString());
    }
    return input;
}));
```

---

## 7. RPC 接口

```java
public interface HookManagement {
    /** List registered hooks (debug). */
    List<HookInfo> list();
    /** Add a new hook at runtime. */
    void register(ToolHook hook);
    /** Remove a hook by id. */
    void unregister(String hookId);
    /** Test a hook pipeline without running the tool. */
    HookDryRunResult dryRun(String toolName, Map<String, Object> input);
}
```

---

## 8. 关键类索引

| 类 | 文件 | 字节 | 作用 | round |
|---|---|---|---|---|
| `ToolHook` | `core/tool/ToolHook.java` | 1,917 | 3 回调 interface | R141 |
| `ToolHookRegistry` | `core/tool/ToolHookRegistry.java` | 3,748 | 调度 + 2 helper | R141 |

**内置 hooks** (在 `aethercode-core` 之外的 `aethercode-permission` / `aethercode-memory` / `aethercode-tasks` 中实现):
- `BashSafetyHook` (R130)
- `PermissionCheckHook` (R130)
- `AuditLoggingHook` (R141)
- `TelemetryHook` (R141)
- `RedactionHook` (R151)
- `TruncationHook` (R151)
- `PathSanitizerHook` (R250+)

---

## 9. 关键测试

```
ToolHookRegistryTest.java          (pre/post 顺序 + filter + deny)
ToolHookDenyIfTest.java            (denyIf 工厂)
ToolHookTransformInputTest.java    (transformInput 工厂)
PathSanitizerHookTest.java         (path 边界)
BashSafetyHookTest.java            (bash 分类)
RedactionHookTest.java             (secret 替换)
TruncationHookTest.java            (50KB 截断)
```

---

## 10. 已知 trade-off

| 决策 | 优点 | 缺点 |
|---|---|---|
| Hook 纯函数 | 可重现, 易测 | 不能直接 I/O (要 outside 包装) |
| pre 正序 / post 反序 | middleware onion | 调试栈深 |
| `CopyOnWriteArrayList` | 读无锁 | 写 O(N) 复制 |
| `deny` 短路径 | 0 cost | 必须 `pre` 跑完才能 deny |
| `attributes` mutable | 跨 hook 通信 | 易滥用 (耦合) |
| 内置 7 个 hook 强制 | 默认安全 | 性能开销 (每次 7 个 hook) |
| Hook 异常吞掉 | 不破循环 | 调试难 |

---

## 11. 关键 round 引用

- **R141**: 引入 ToolHook + ToolHookRegistry
- **R151**: RedactionHook + TruncationHook
- **R250+**: PathSanitizerHook (path 边界保护)
- 详细过程见 `../round-notes/R141-HOOK-SYSTEM.md` (如果存在) 或代码注释
