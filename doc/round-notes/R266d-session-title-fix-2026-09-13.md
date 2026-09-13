# R266d-session-title-fix (2026-09-13)

## 触发
User 跑 0.2.66 desktop 完成一个 task 后, 报告 "任务都执行结束了, 左边还是 '新会话' , 没有提示任务摘要"。在右边 PLAN panel 也看到 "汇总结果" 这个 plan step 6/6 完成, 但 chat transcript 中间没看到。

## 根因 (2 个独立问题)

### 问题 1: SessionListRow 永远显示 "新会话"

`aethercode-desktop/src/components/session/SessionListRow.tsx` 的 `sessionDisplayTitle` 三层 fallback:

```ts
if (s.title && s.title.trim()) return s.title;
if (s.preview && s.preview.trim()) return s.preview.trim().slice(0, 60);
return '新会话';
```

期望: title 字段 (daemon 给) → preview 字段 (first user message) → "新会话" fallback.

**实际**: 全部都走 fallback。 3 个 bug 叠在一起:

1. **Java 端 listSessions 不发 `title` 字段** (R198 wire format 不全)
   - 当时 design 是 "desktop 主动传 withPreview=true 才返回 preview", 但 R204 改 SessionListRow 用 title 字段后, daemon 一直没补 title
2. **Java 端 withPreview 默认 false**
   - desktop store/index.ts 多处调 `rpc.listSessions()` 不传 opts → 拿不到 preview
3. **HTTP daemon 路径没装 memory store**
   - DaemonRunner.runHttp 路径只装 SessionStore (line 396), 没调 `http.methods().setMemoryStore(...)`
   - 所以 listSessions 看到 sizeBytes=0 的 session 时, `if (memoryStore != null && ...)` 永远 false, firstPrompt fallback 跳过
   - 跑 stdio 路径 daemon 的 desktop 没问题, 跑 HTTP 路径的 desktop 永远 fallback "新会话"

### 问题 2: EndOfTaskPanel 12 秒 auto-dismiss

`aethercode-desktop/src/components/EndOfTaskPanel.tsx` 设了 12s auto-dismiss:

```ts
const t = window.setTimeout(() => setDismissed(true), 12_000);
```

User 跑完 task 看到 "汇总结果" 时, EndOfTaskPanel 弹出来但 12s 后消失, user 没看到 "汇总" 内容就消失了。 跟 "汇总结果" 看不到直接相关。

## 修复

### Java 端 (3 个 fix)

1. **`AetherCodeMethods.listSessions` 默认 `withPreview=true`** (line 4245)
   ```java
   // 之前: boolean withPreview = Boolean.TRUE.equals(p.get("withPreview"));
   // 现在: boolean withPreview = !Boolean.FALSE.equals(p.get("withPreview"));
   ```
   任何 client 调 listSessions 都拿 preview, 包括 desktop store/index.ts 那几个不传 opts 的地方。

2. **`AetherCodeMethods.listSessions` 补 `title` 字段** (line 4368)
   ```java
   m.put("preview", preview == null ? "" : preview);
   m.put("title", preview == null ? "" : preview);
   ```
   用 first user message 作为 title 跟 preview 同一份 (冗余但兼容 TS 端 SessionListItem 期望 `title: string` 必填)。

3. **`DaemonRunner.runHttp` 装 memory store** (line 396 后面)
   ```java
   try {
       org.aethercode.memory.LayeredMemoryStore httpMemoryStore =
               buildMemoryStore(http.methods(),
                       java.nio.file.Path.of(System.getProperty("user.home"))
                               .resolve(".aethercode"));
       http.methods().setMemoryStore(httpMemoryStore);
   } catch (Exception memEx) {
       LOG.warn("R266d: memory store install failed on http path: {}",
               memEx.getMessage());
   }
   ```
   HTTP 路径补装 memory store, 这样 sizeBytes=0 的 lazy-create session 能从 session_info.first_prompt 提取 title.

### Desktop 端 (1 个 fix)

4. **`EndOfTaskPanel` auto-dismiss 12s → 5 分钟**
   ```ts
   // 之前: 12_000
   // 现在: 5 * 60_000
   ```

## 验证

- `mvn -B -DskipTests -pl aethercode-cli -am package` 26.7s SUCCESS (incremental, 23 modules)
- `cargo build --release --features tauri/custom-protocol` ~5 min, Tauri 2 bundle 4.92 MB
- WebSocket 测试 `listSessions` (新 build):
  - **大 session (111KB transcript)**: `preview` = "在当前目录下实现一个 java maven 项目..." ✓ `title` = same ✓
  - **小 session (sizeBytes=0, lazy create)**: 之前 `preview=""` `title=""`, 现在 `preview` = "在 abc_1 实现 hello world 程序" `title` = same ✓
- `session_info` 表 (sessions.db) 现在有 firstPrompt row ✓
- 旧 session 9/10 之前的 (没 firstPrompt) 仍显示 "新会话" (符合预期 — 升级前创的没 firstPrompt)

## 教训

1. **Wire format incomplete 是常见 round-trip bug** — 跟 R198 的 listSessions schema 不全, R204 改 SessionListRow 用 title 字段但 daemon 没补字段. 教训: 改 type 定义时也要 review 实际 wire format
2. **HTTP path 跟 stdio path 必须**严格走同样的 init 序列 — DaemonRunner.run 跟 runHttp 都有 setMemoryStore 应该, 但 HTTP 漏了
3. **auto-dismiss 在 chat 应用里要慎重** — 12s 太短, 用户没读完就消失. 5 分钟是合理上限
4. **lazy create 的 firstPrompt 必须 early-write** — desktop createSession 传 firstPrompt, daemon 立刻写 memory store. 之前 createSession 有 if (memoryStore != null) 检查, 但 HTTP 路径 memoryStore 永远 null → 永远不写. 教训: init 漏的副作用跨多个 RPC
