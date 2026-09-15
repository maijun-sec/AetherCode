# R270 — history list two-line summary (Claude Code / OpenCode style)

Date: 2026-09-15
Round: R270
Type: UX (desktop + daemon)
Status: ✅ Deployed (commit `07e911f` push 成功)

## Trigger (用户反馈)

用户 17:26 反馈: "历史列表 preview 行为，还是希望改，因为 claude code、opencode 等都不是这个样子的"。

观察 Cursor / Claude Code / OpenCode — 历史列表每行都有 "what the agent was just doing" 的 summary, 跟 first prompt 不一样。AetherCode 只有 first user prompt。

## 现状 (修复前)

`SessionListRow.tsx` 三行布局:
```
●  <title> (first user prompt, capped 60)
   <cwd> · <time> · <tokens>
   "<preview>" (verbatim first user prompt in quotes)
```

第三行是冗余的: title 已经 fallback to preview,展示一模一样的文本只是 quoting 一下。**完全看不到 agent 执行历史**。

## R270 修复

### Fix 1 — Daemon `extractLastAgentEvent`

`AetherCodeMethods.extractLastAgentEvent(info)` 从 transcript 末尾向前找最后一条 assistant role message:

```java
// 1) Prefer tool_use blocks — they tell the user
//    "what the agent just did"
String toolLabel = extractFirstToolUseLabel(line);
if (toolLabel != null && !toolLabel.isEmpty()) {
    return capForList(toolLabel, 80);
}

// 2) Fall back to plain text
String text = extractFirstTextValue(line);
if (text != null && !text.isEmpty()) {
    return capForList(text, 80);
}
```

#### Tool use label 格式

`<tool_name> <input_path_tail>`:
- `file_write D:\tmp\abc_1\src\test\java\com\example\sort\HeapSortTest.java` → `file_write HeapSortTest.java` (trim to path tail)
- `bash ls -la /var/log` → `bash ls -la /var/log` (command 字段)
- `file_read /Users/me/proj/app.md` → `file_read app.md`

实现要点:
- Input 字段优先 `file_path` / `path` / `filePath` / `command` / `cmd` / `url` / `pattern` / `query` / `prompt` / `name` / `target` / `destination` (顺序尝试, 找到第一个就返回)
- Fallback: 第一个 string-typed 字段
- `trimToPathTail`: 把 Windows `\` 和 POSIX `/` 都 normalize, 取最后一段
- 80 字符 cap + ellipsis

#### Text label 格式

前 80 字符的 assistant text (whitespace-collapsed):
- `我来写一下排序...` 
- `已完成`

#### 性能

- `Files.readAllLines()` — transcripts 通常 < 1 MB
- per-line 4 KB cap 防大 tool input 拖慢 regex
- O(n) 在 line 数,从末尾向前遍历
- bundled with `withPreview` gate,不需要第二次 transcript scan

#### wire format

`listSessions` 新增 `lastAgentEvent` 字段:

```java
String lastEvent = extractLastAgentEvent(info);
m.put("lastAgentEvent", lastEvent == null ? "" : lastEvent);
```

### Fix 2 — SessionListRow 第三行

新行 layout:
```
●  <title>                                    (first prompt, capped 60)
   <cwd> · <time> · <tokens>
   → file_write HeapSortTest.java     ← R270 NEW (lastAgentEvent)
   "<preview>"                        ← 仅当 title !== preview (用户 rename 过)
```

CSS:
```css
.session-list-row-last-event {
  font-size: 11px;
  color: var(--text);
  margin-top: 2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-weight: 500;
}
.session-list-row-last-event-arrow {
  color: var(--accent, #5da9ff);  /* 蓝色箭头 */
  margin-right: 4px;
  font-weight: 600;
}
```

`→` 箭头用 accent 蓝色, 跟现有 ActivityIndicator / StreamingIndicator 配色一致。11px / font-weight: 500 让 last action 比 preview line 更显眼(用户最想看的就是这块)。

preview 行加 conditional:
```tsx
{session.preview
  && session.title
  && session.title.trim()
  && session.preview.trim() !== session.title.trim()
  && (
  <div className="session-list-row-preview">"{session.preview}"</div>
)}
```

## 测试

### Daemon (`AetherCodeMethodsR270Test` — 5 test)

1. `listSessions_lastAgentEvent_summarizesLastToolUse` — transcript 末尾是 text, 返回 text(不是 tool_use)
2. `listSessions_lastAgentEvent_fallsBackToToolUseWhenLastLineIsPlainText` — 末尾是 tool_use, 返回 "file_read app.log"
3. `listSessions_lastAgentEvent_emptyForBrandNewSession` — 空 transcript 返回 ""
4. `listSessions_lastAgentEvent_capsAt80Chars` — 120 字符 Chinese text → 80 字符 + "…"
5. `listSessions_lastAgentEvent_missingWhenWithPreviewFalse` — `withPreview=false` 时字段不存在

### Desktop (`SessionListRowR270.test.tsx` — 6 test, jsdom)

**Source-pin (in SessionListRow.test.tsx, 2 new)**:
- `R270: renders a lastAgentEvent line`
- `R270: hides the preview line when title === preview`

**Behaviour (6)**:
- renders the arrow + lastAgentEvent when populated
- hides the lastAgentEvent line when empty
- hides the lastAgentEvent line when whitespace only
- hides the verbatim preview when title === preview (default)
- shows the verbatim preview when title differs from preview
- shows BOTH the lastAgentEvent line AND the verbatim preview line when renamed

### 测试结果

```
mvn protocol: 269 → 274 pass (+5)
vitest:        1079 → 1087 pass (+8)
```

**0 回归**。

## 部署

### Build

```bash
mvn install -pl aethercode-protocol,aethercode-cli -DskipTests  ✓
npx tsc -b --noEmit                                              ✓ clean
npm run build                                                    ✓ built in 4.34s, bundle index-QOXXRanI.js
cargo build --release --features tauri/custom-protocol           ✓ 3m 06s
```

### 部署链

| 项 | 值 |
|---|---|
| commit | `07e911f` (push 成功) |
| jar SHA | `246561A7B75BE5975E790A01703D9D478D6C3402` (R270 daemon) |
| exe SHA | `794D152536A481BDDB0DF34C0813CA36BC202DD8` (R270 build, JS bundle `QOXXRanI`) |
| zip SHA | `58B38C5CDE23348FC379FC46212041043A183AF6` (108,494,282 bytes) |
| 9/9 jar bytecode markers | R266i 1 + R268d 1 + R268e 4 + R270 3 ✓ |
| 老 zip | `.prev.bak` (R269 backup) |

### Bytecode markers in deployed jar

| Class | Marker | Round |
|---|---|---|
| ProgressLoopDetector | isStructurallyEmpty | R266i |
| WriteExistingFileGuardHook | prePopulateFromSession, R268e write-guard | R268e |
| AetherCodeMethods | extractLastAgentEvent, lastAgentEvent, R270: | **R270** |
| AetherCodeMethods | autoApproveMediumHigh | R268d |
| AetherCodeMethods | prePopulateWriteGuard | R268e |

## 用户下一步验证

1. 双击 `release\aethercode-0.2.70\desktop\aethercode-desktop.exe` 启动
2. 看历史列表 — 每行现在是:
   ```
   ●  <first prompt>           ← title
      <cwd> · <time> · tokens   ← meta
      → file_write HeapSortTest.java   ← NEW! last agent action
   ```
3. 不同 session 的 last action 不一样:
   - 文件操作的: `→ file_write HeapSortTest.java`
   - bash: `→ bash ls -la /var/log`
   - thinking-only: `→ 我来写一下排序...`
4. rename 过 session 还能在最后看到原始 first prompt (在 quotes 里)

## 教训 (新增 4 条)

540. **path tail trim 是 session-list summary 的关键** — `file_write D:\tmp\abc_1\...\HeapSortTest.java` 全路径在 session list 没用,用户只要知道是哪个文件
541. **优先 tool_use 而不是 text** — 用户想看 "agent 做了什么",不是 "agent 想了什么"
542. **从 transcript 末尾向前 scan 优于从头部向后** — 用户最关心的永远是最近的活动,first prompt 已经在 title 里
543. **bundled with `withPreview` gate** — 同一个 transcript scan pass 出两个字段,避免双倍 IO

## 文件清单

### 修改
- `aethercode/aethercode-protocol/src/main/java/org/aethercode/protocol/methods/AetherCodeMethods.java` (extractLastAgentEvent + listSessions 集成)
- `aethercode-desktop/src/lib/methods.ts` (SessionInfo 加 lastAgentEvent)
- `aethercode-desktop/src/rpc/types.ts` (SessionListItem 加 lastAgentEvent)
- `aethercode-desktop/src/components/session/SessionListRow.tsx` (第三行 + preview 条件渲染)
- `aethercode-desktop/src/components/LeftPanel.css` (last-event CSS)
- `aethercode-desktop/src/components/session/__tests__/SessionListRow.test.tsx` (R270 source-pin)
- `release/aethercode-0.2.70/RELEASE-NOTES.md` (R270 section + 部署链历史)

### 新增
- `aethercode/aethercode-protocol/src/test/java/org/aethercode/protocol/methods/AetherCodeMethodsR270Test.java` (5 test)
- `aethercode-desktop/src/components/session/__tests__/SessionListRowR270.test.tsx` (6 test)
- `doc/round-notes/R270-history-two-line-summary-2026-09-15.md` (本文)