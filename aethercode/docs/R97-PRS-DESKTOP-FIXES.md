# R97-PRS SHIPPED (2026-08-18 11:0X) — Desktop App "task failed with no message" 3-bug fix

**R97-PRS 触发**: user 重新测试 R97-I 后的 exe，跑了 user 报的 prompt（在 D:\tmp\abc 下生成一个 java maven 排序项目），App 端看到 "看似跑了几秒然后整个任务失败，也没有提示"。

**根因分析** (3 个 bug 互相放大):

### Bug 1 — Root cause: bundled default model 是 `MiniMax-Text-01`
**Text-01 是幻觉机**。WebSocket 直连 user 那个 17888 daemon 跑同样 prompt 复现了 user 的体验：
- 32 秒，54 个 event
- 0 个 `tool_use_start` 发出
- text_bytes=3646
- run_end stopReason="stop"（模型自己觉得"对话结束"）
- session JSONL 停在 `public void insertionSort(int[] array) {` 处不再更新

Text-01 在 markdown 文本里把 `file_write` 调用以 ```` ```json {...} ``` ```` 包成纯文本，**根本不调 tool**。这是 R97-I 已识别的 in-memory 候选（R97-P），一直没改。

### Bug 2 — 后端 `query()` catch 块不强制发 run_end
`AetherCodeMethods.java:1893-1917`，query() 抛 Throwable 时走 catch 分支，**只发 `NOTIFY_LOG` 通知，不发 `stream_event` 里的 RunEnd**。如果 LLM call 中途异常（timeout / 4xx / 5xx），前端永远收不到 `run_end`，`isStreaming` 卡在 `true`，输入框永远 disable。

### Bug 3 — 前端 `log` handler 把 error 也吞了
`store/index.ts:1653-1665`：
```ts
rpc.on('log', (params: any) => {
  const level = params?.level ?? 'info';
  if (level === 'info') return;  // ← 吞了
```
daemon 的 catch 块发的是 `{runId, error: "..."}` —— **没有 `level` 字段**，所以 `level` 兜底成 `'info'`，直接 return。即使 daemon 抛了 error 通知，前端 UI 也看不见。

---

## 修复（3 个改动）

### R97-P — bundled default 改 `MiniMax-M3`
**File**: `aethercode-core/src/main/java/org/aethercode/core/providers/ProviderRegistry.java`

把 `bundledDefaults()` 里的 minmax provider 从 `MiniMax-Text-01` 改成 `MiniMax-M3`：
```java
out.add(new ProviderSpec(
        "minmax", "openai-compat",
        "https://api.minimaxi.com/v1",
        "MINIMAX_API_KEY",
        "MiniMax-M3",                              // ← 改了
        List.of(
                new ModelSpec("MiniMax-M3",       0.001, 0.008, 1_000_000, true),   // ← isDefault=true
                new ModelSpec("MiniMax-Text-01",  0.001, 0.008, 1_000_000, false),  // 仍可手选
                new ModelSpec("MiniMax-M1",       0.001, 0.008, 1_000_000, false)
        )));
```

**R97-P 验证**: 用新 jar 启动 daemon（port 18999, cwd D:\tmp\abc, model M3），跑 user 同样 prompt：
- 77 秒，5 个 tool_use_start (bash x2, todo_write, file_write x2)
- M3 真的调 file_write 创建 7 个 java 源文件 + pom.xml
- run_end 干净到达 stopReason="stop"
- 之前的 "Text-01 假装调 tool 实际写 markdown" 问题消失

### R97-R — query() catch 强制发 run_end
**File**: `aethercode-protocol/src/main/java/org/aethercode/protocol/methods/AetherCodeMethods.java`

catch (Throwable) 块除了发 `NOTIFY_LOG` 之外，**还发一个 synthetic RunEnd stream_event**：
```java
// R97-R: synthetic run_end so the renderer
// un-sticks its input box. The shape mirrors
// eventToMap(StreamEvent.RunEnd) plus an
// `error` field the renderer can surface.
Map<String, Object> endWrap = new LinkedHashMap<>();
endWrap.put("runId", runId);
Map<String, Object> endEvent = new LinkedHashMap<>();
endEvent.put("type", "run_end");
endEvent.put("stopReason", th.getMessage() != null && !th.getMessage().isBlank()
        ? "error: " + th.getMessage()
        : "error");
endEvent.put("error", th.getMessage() != null ? th.getMessage() : th.getClass().getName());
endEvent.put("isError", true);
endWrap.put("event", endEvent);
notifier.accept(new JsonRpcNotification(
        org.aethercode.protocol.jsonrpc.JsonRpcMessage.VERSION,
        NOTIFY_STREAM_EVENT, endWrap));
```

**附带**: `EngineContinuationDispatcher.java:165-175` 同样把 `level: "error"` 加进 errWrap（之前缺这个字段，前端吞掉）。`BackpressureException` 的 bpWrap 也加 `level: "warn"`。

**附带**: `AetherCodeEngine.setChatClient` 之前只改 AetherCodeEngine.chatClient，没改 QueryEngine.chatClient（QueryEngine 字段是 final，且构造时一次注入）。R97-R 把 QueryEngine.chatClient 改成可变 + 加 `setChatClient(ChatClient)` setter，setChatClient 时同步传播。

**R97-R test**: `aethercode-protocol/src/test/java/org/aethercode/protocol/methods/QueryErrorNotificationTest.java`
- 用 `ThrowingChatClient` 让 `ChatClient.stream()` 返回一个 spliterator，第一次 `tryAdvance` 就抛
- 验证 catch 块发出 1) NOTIFY_LOG with `level=error` + 2) NOTIFY_STREAM_EVENT with `type=run_end, isError=true, stopReason="error: ..."`
- 测试 PASS

### R97-S — 前端 log handler 不吞 error
**File**: `aethercode-desktop/src/store/index.ts`

提取成纯函数 `applyLogNotification(set, params)`：
```ts
const hasError = params?.error != null && String(params.error).length > 0;
const rawLevel = String(params?.level ?? '').toLowerCase();
const level = rawLevel === '' ? (hasError ? 'error' : 'info') : rawLevel;
if (level === 'info' && !hasError) return; // skip pure-noise info entries
const msg = params?.message ?? params?.error ?? '';
if (!msg) return;
const isError = level === 'err' || level === 'error';
set((s) => ({
  messages: [...s.messages, {
    id: newId('system'), role: 'system' as const,
    content: `[${level}] ${msg}`, timestamp: Date.now(), isError,
  }],
  isStreaming: isError ? false : s.isStreaming,
}));
```

**R97-S test**: `aethercode-desktop/src/store/logHandler.test.ts` (6 cases)
- missing level + error field → 显示 + isError
- level=error → 显示 + isError + un-sticks
- level=warn → 显示 (non-error) + 不 un-stick
- level=info + 无 error → 吞
- level=err (短形式) → 当 err 处理
- 空消息 + 无 error → 吞
- 6/6 pass

---

## Files changed

| File | 改动 |
|---|---|
| `aethercode-core/src/main/java/org/aethercode/core/providers/ProviderRegistry.java` | bundled minmax default M3，加 M3 进 models list，Text-01 isDefault=false |
| `aethercode-core/src/main/java/org/aethercode/core/engine/QueryEngine.java` | chatClient 字段 final → 可变，加 `setChatClient(ChatClient)` |
| `aethercode-sdk/src/main/java/org/aethercode/sdk/AetherCodeEngine.java` | `setChatClient` 同步到 QueryEngine.setChatClient |
| `aethercode-protocol/src/main/java/org/aethercode/protocol/methods/AetherCodeMethods.java` | catch (Throwable) 强制 emit synthetic RunEnd + BackpressureException + errWrap 加 `level=error` |
| `aethercode-protocol/src/main/java/org/aethercode/protocol/methods/EngineContinuationDispatcher.java` | errWrap 加 `level=error` |
| `aethercode-desktop/src/store/index.ts` | log handler 提取 `applyLogNotification`，加 `__test_handleLogNotification` test seam |
| `aethercode-protocol/src/test/java/org/aethercode/protocol/methods/QueryErrorNotificationTest.java` | NEW, 1 test (~9KB) |
| `aethercode-protocol/src/test/java/org/aethercode/protocol/methods/SessionManagerRpcTest.java` | 改 timeout 3s → 15s（LLM under load 时跑超过 3s） |
| `aethercode-desktop/src/store/logHandler.test.ts` | NEW, 6 tests (~4KB) |
| `aethercode/dist/aethercode-0.2.1.jar` | 39,893,597 bytes (R97-E: 39,893,284 + 313 字节) |
| `aethercode/dist/release-r97h/...` (R97-I 同样的 release 目录) | exe + zip 重 build |
| `aethercode/docs/R97-PRS-DESKTOP-FIXES.md` | 本文件 |

## 验证

- `mvn -B install -DskipTests` BUILD SUCCESS
- `mvn -B -pl aethercode-protocol -am test` — 89 tests, 0 failures
- `npx tsc -b --noEmit` (desktop) — 0 errors
- `npx vitest run` (desktop) — 39 tests, 0 failures (33 subagentReducer + 6 logHandler NEW)
- daemon 真实跑 user prompt — M3 真的调 file_write + 创建 7 个源文件 + pom.xml

## Build artifacts (R97-PRS 末)

- `dist/aethercode-0.2.1.jar` 39,893,597 bytes
- `dist/release-r97h/app/aethercode-desktop.exe` (TBD - building)
- `dist/release-r97h.zip` (TBD)
- `dist/release-r97h.tar.gz` (TBD)

## 关键 insight

1. **bundled default = product behavior, not config**: Text-01 是"全模型里唯一一个不调 tool 的"，bundled default 选它就是"开箱即坏"。改 bundled default 才是 fix，让 user 不用每次去 Settings 切模型。
2. **catch 块必须 emit 一个终止事件**: query() 走 catch 时 if 没有 RunEnd，前端的状态机永远 stuck。**所有 async runner 的 catch 块都应该至少 emit 一个 "the run is over" 事件**，跟 happy path 一致。
3. **JSON notification 的 type 字段 fallback 是个 footgun**: `level ?? 'info'` 是经典的"silent default" — 99% 看起来对，但 1% 情况下吞掉真正的 error。规则：**error/notification 永远要看 error 字段是否独立存在**。
4. **setChatClient 必须 propagation 到所有内部引用**: AetherCodeEngine.chatClient 是 public mutable，但 QueryEngine.chatClient 是 final + 构造时一次注入。test 用 setChatClient 注入 broken client 时，QueryEngine 还是用原 client — 这种 bug 只能靠"测试期望 throw → catch 块"才能发现。
