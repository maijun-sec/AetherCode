# R268e — desktop auto-restore session + daemon write-guard pre-populate

Date: 2026-09-15
Round: R268e
Type: feature + deployment
Status: ✅ Deployed (commit pending)

## Trigger (用户反馈)

User 0.2.70 R268d 测试 (16:00 截图):
1. 双击打开 desktop, 输入 "继续执行" 四个字 — agent 思考 + 想 file_write, 然后死了
2. 历史会话中只有用户输入 prompt, 没有 Agent 执行记录显示
3. 历史会话中 cwd 为空, 没有回写回来

## 根因分析

### 问题 1: "继续执行" 死了

Daeme 1 daemon 重启后, `WriteExistingFileGuardHook.readBySession` 是内存
里的空 map, 没有任何 file_read 历史。但 transcript (JSONL) 里**有**
完整 file_read / file_write / file_edit 调用记录。

Agent 想 `file_write D:\tmp\abc_1\src\test\java\com\example\sort\HeapSortTest.java`,
但 R89 write-guard 看不到这个文件被读过 (daemon 内存空) → 拒绝 →
agent 决策 "fresh approach" → 卡死。

### 问题 2: 历史会话只显示用户 prompt

历史列表 preview 只取 first user message 作为标题 (设计如此), agent
的 122 条 execution 消息都藏在 transcript 里。这是 preview 行为, 不
是数据缺失 — 点击会话进右面板能看到。

### 问题 3: cwd 为空

desktop `desktop-state.json` 里有 `lastCwd: D:\tmp\abc_1`, 但 desktop
启动时没把它 push 给 daemon, daemon 用空 cwd 启动。R268d exe 是 R267
+ R268 + R268b + R268c + R268d 部署, 但 desktop 端 store/index.ts 的
auto-restore + cwd restoration 是 R268e 才加的。

## R268e 修复

### Fix 1: Daemon write-guard pre-populate

**File**: `aethercode-hooks/.../WriteExistingFileGuardHook.java`

新增方法:
```java
public void prePopulateFromSession(String sessionId, Collection<String> paths) {
    if (sessionId == null || paths == null || paths.isEmpty()) return;
    Set<String> incoming = new LinkedHashSet<>();
    for (String raw : paths) {
        if (raw == null || raw.isBlank()) continue;
        Path p;
        try { p = Paths.get(raw).toAbsolutePath().normalize(); }
        catch (Exception e) { continue; }
        String canonical = canonicalize(p);
        if (canonical == null) canonical = p.toString();
        if (isWindows()) canonical = canonical.toLowerCase();
        incoming.add(canonical);
    }
    if (incoming.isEmpty()) return;
    readBySession.compute(sessionId, (k, prev) -> {
        Set<String> next = (prev == null) ? new LinkedHashSet<>() : prev;
        for (String c : incoming) { next.remove(c); next.add(c); }
        return next;
    });
    trimToCap(readBySession.get(sessionId));
    touchSession(sessionId);
    LOG.info("R268e write-guard: pre-populated {} paths for session {}",
             incoming.size(), sessionId);
}
```

**File**: `aethercode-protocol/.../AetherCodeMethods.java`

`loadSession()` 在 `engine.loadSession` 之后 best-effort 调用:
```java
private void prePopulateWriteGuardFromTranscript(String sessionId, Transcript t) {
    List<String> paths = new ArrayList<>();
    if (t != null) {
        for (Message m : t.messages()) {
            if (m == null || m.content() == null) continue;
            for (ContentBlock b : m.content()) {
                if (!(b instanceof ContentBlock.ToolUseBlock tu)) continue;
                String name = tu.name();
                if (!"file_read".equals(name) && !"file_write".equals(name)
                    && !"file_edit".equals(name)) continue;
                if (tu.input() == null) continue;
                Object p = tu.input().get("file_path");
                if (p == null) p = tu.input().get("path");
                if (p == null) p = tu.input().get("filePath");
                if (p == null) continue;
                String s = p.toString();
                if (!s.isBlank()) paths.add(s);
            }
        }
    }
    if (paths.isEmpty()) return;
    HookRegistry reg = engine.hookRegistry();
    if (reg == null) return;
    for (Hook h : reg.snapshot()) {
        if (h instanceof WriteExistingFileGuardHook guard) {
            guard.prePopulateFromSession(sessionId, paths);
            return;
        }
    }
}
```

### Fix 2: Desktop auto-restore session

**File**: `aethercode-desktop/src/store/index.ts`

```typescript
// R268e (2026-09-15): auto-restore the most-recent
// session when the daemon comes up with NO
// currentSessionId. Previously the renderer's
// first paint picked up "blank session" + the user
// saw their prompt land in a fresh empty
// session — the LLM had no task context, the
// chat panel showed no history, and `cwd` /
// `currentSessionId` were blank.
if (!state?.sessionId && (sessions.sessions ?? []).length > 0) {
  const recent = [...(sessions.sessions ?? [])]
    .filter((s) => typeof s.lastUsedAt === 'number')
    .sort((a, b) => (b.lastUsedAt ?? 0) - (a.lastUsedAt ?? 0))[0];
  if (recent && recent.id) {
    const ageMs = Date.now() - (recent.lastUsedAt ?? 0);
    if (ageMs < 24 * 60 * 60 * 1000) {
      try {
        await get().switchSession(recent.id);
      } catch (e) {
        console.warn('[store] R268e auto-restore failed:', e);
      }
    }
  }
}
```

## 测试

### 新增 4 个 test in WriteExistingFileGuardHookTest

1. `prePopulateFromSession_unblocksExistingFilesWithoutRead` — 核心场景
2. `prePopulateFromSession_isSessionScoped` — s1 pre-populated, s2 仍 blocked
3. `prePopulateFromSession_emptyAndNullInputsAreNoOps`
4. `prePopulateFromSession_acceptsAlternativePathKeys`

`mvn test -pl aethercode-protocol`: 269/269 pass

### Vitest

Desktop 不需新 test (auto-restore 用现有 switchSession API)。

## Build

```bash
mvn install -pl aethercode-protocol,aethercode-cli -am install -DskipTests  ✓
npm run build                                                          ✓
cargo build --release --features tauri/custom-protocol                 ✓
```

## 部署 verification

### jar SHA

- 旧 R268d: `01C6069EE86A11F994CE0432D5CC8F94811C98A2` (56,582,323 bytes)
- 新 R268e: `B84855BF02F25B0A045493559DA4A38518DDF6AE` (56,583,636 bytes)

### exe SHA

- 旧 R268d: `80832BE67C7CEE6AED96F00DF0C364E82763F75D` (5,175,808 bytes)
- 新 R268e: `D15CEEF3DDB080278A7B2FE6D79BCC6065448ABF` (5,175,808 bytes)

### zip SHA

- 旧 R268d: `E176D11DC80C0B685308D6DA9489E741E7B658CD` (108,482,674 bytes)
- 新 R268e: `5236076A0D58CC73600BB872B1F9A5360418DD79` (108,486,518 bytes)

### Bytecode markers in deployed jar (8/8)

| Class | Marker | Round |
|---|---|---|
| ProgressLoopDetector | isStructurallyEmpty, lastLoopKind, empty_tool_input | R266i |
| WriteExistingFileGuardHook | prePopulateFromSession, R268e write-guard | R268e |
| AetherCodeMethods | prePopulateWriteGuardFromTranscript, R268e: prePopulateWriteGuard, autoApproveMediumHigh | R268e + R268d |

### Exe bundle reference: `assets/index-cpSz9Dpe.js` (R268e build)

- 旧 R268d bundle: `assets/index-CP05ZflY.js`
- 新 R268e bundle: `assets/index-cpSz9Dpe.js` (682,158 bytes, sha256[:16] `5de853f0cf0fcc46`)

## Zip layout (R268b portable install dual-path)

```
aethercode-0.2.70/
  RELEASE-NOTES.md          6,060 bytes
  aethercode-0.2.70.jar     56,583,636 bytes (R268b parent-dir fallback)
  ac-tui/
    ac-tui.js               2,293,623 bytes
    README.md               8,011 bytes
  desktop/
    aethercode-desktop.exe  5,175,808 bytes (R268e build, 含 R268e JS bundle)
    aethercode.jar          56,583,636 bytes (R268e jar, R268 portable install primary)
```

## 用户下一步验证

1. 双击 desktop 启动 → 应该看到最近 24h session 自动加载 (R268e auto-restore)
2. cwd 应该从 desktop-state.json 加载, 显示 `D:\tmp\abc_1`
3. 输入 "继续执行" → agent 应该能正常 file_write HeapSortTest.java (R89 不再拦截)
4. daemon stderr 应有 `R268e write-guard: pre-populated N paths for session <sid>`

## 教训 (新增 5 条)

528. **daemon 内存 state 必须有 cold-start 恢复路径** — readBySession 这种 transient cache 不能只在内存, 必须能从 transcript 重建
529. **scope 限制的 cache 也需要 pre-populate** — 不是所有 hook 都是 global, session-scoped 的更需冷启动填充
530. **transcript scan 是 daemon cold-start 恢复的标准模式** — loadSession 是自然的 hook 点, 已经有完整 transcript 可读
531. **exe rebuild 必须配 npm build** — `cargo build` 不会自动触发 beforeBuildCommand 除非 explicit, 或者必须 verify dist/ mtime 比 src/ 新
532. **JS bundle hash 是 desktop 部署链 fingerprint** — `assets/index-XYZ.js` 在 exe 里有 reference, 比 source mtime 更可靠

## 文件清单

### 修改
- `aethercode/aethercode-hooks/src/main/java/org/aethercode/hooks/builtin/WriteExistingFileGuardHook.java`
- `aethercode/aethercode-hooks/src/test/java/org/aethercode/hooks/builtin/WriteExistingFileGuardHookTest.java`
- `aethercode/aethercode-protocol/src/main/java/org/aethercode/protocol/methods/AetherCodeMethods.java`
- `aethercode-desktop/src/store/index.ts`

### 新增
- `doc/round-notes/R268e-auto-restore-and-pre-populate-2026-09-15.md` (本文)

### 更新
- `release/aethercode-0.2.70/RELEASE-NOTES.md`
- `release/aethercode-0.2.70/desktop/aethercode.jar` (R268e jar)
- `release/aethercode-0.2.70/desktop/aethercode-desktop.exe` (R268e exe)
- `release/aethercode-0.2.70.zip` (re-packed)