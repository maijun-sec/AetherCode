# R268b: parent-dir versioned jar fallback (2026-09-15)

## 触发

用户第 5 次报 "新包仍然全部 tool 失败"。R268 的 portable install
fix 在 zip 里漏了一个环节：0.2.70 zip 没把 jar 打到 `desktop/` 下。

```
aethercode-0.2.70/
├── aethercode-0.2.70.jar          ← 真正的 R266i jar (56 MB)
├── desktop/
│   └── aethercode-desktop.exe    ← R268 exe, 但旁边没 jar
```

exe 找不到 sibling jar → 跳到 ancestor walk → 找到
`aethercode/dist/aethercode.jar` (stale 0.2.66 build, 56 MB
但不是 R266i) → daemon 跑老代码 → `Map.isEmpty()` 不判
`{"command":""}` → 16+ bash (missing command) 卡片 → "tool 失败"。

## 根因

1. **Zip 布局错**：R268 portable install 假设 exe 旁有
   `aethercode.jar`，但实际 zip 里 jar 在父目录叫
   `aethercode-0.2.70.jar`。
2. **R268 fallback 链不够 robust**：portable install 找不到
   时直接跳 ancestor walk，找 stale dist jar 而不是父目录
   的 versioned jar。
3. 我之前说 "拷到 desktop/aethercode.jar" 是手动一次性操作
   没进 zip。用户重新解压就没了。

## 修复

### 1. R268b parent-dir fallback (`lib.rs`)

在 R268 portable install 检查（看 `exe.parent()`）之后、ancestor
walk 之前，加一层"父目录"检查（看 `exe.parent().parent()`）：

```rust
// R268b (2026-09-15): parent-dir versioned jar.
// Some zip layouts put the jar one level up from the exe
// (the cli ships as `release/aethercode-0.2.70.jar` while
// the exe lives in `release/desktop/`). Without this check,
// the exe falls through to the ancestor walk and finds a
// STALE dev jar from `aethercode/dist/`.
if let Some(parent_dir) = exe_dir.parent() {
    let parent_exact = parent_dir.join("aethercode.jar");
    if parent_exact.is_file() {
        eprintln!("[R268b] find_jar_path: portable install (parent-dir exact) ...");
        return Ok(parent_exact);
    }
    if let Ok(entries) = std::fs::read_dir(parent_dir) {
        // ... scan for aethercode-*.jar in parent_dir
    }
}
```

现在 `find_jar_path` 的完整顺序：

```
1. Tauri bundled resource
2. ★ exe 旁 aethercode.jar     (R268, portable install 主路径)
3. ★ exe 旁 aethercode-*.jar   (R268, wildcard fallback)
4. ★ exe 父目录 aethercode.jar  (R268b NEW)
5. ★ exe 父目录 aethercode-*.jar (R268b NEW)
6. closest-ancestor walk `<ancestor>/aethercode/dist/`  (dev fallback)
7. $AETHERCODE_DIST  (env var)
```

### 2. 0.2.70 zip 布局修

把 `aethercode.jar` 真的打进 `desktop/` 下，跟 exe 同目录。
parent dir 也保留 `aethercode-0.2.70.jar`（cli 用）。

```
aethercode-0.2.70/
├── aethercode-0.2.70.jar          ← cli (java -jar)
├── RELEASE-NOTES.md
├── ac-tui/{ac-tui.js, README.md}
└── desktop/
    ├── aethercode-desktop.exe    ← desktop exe (R268b)
    └── aethercode.jar             ← ★ NEW: 紧挨 exe
```

R268 portable install 步骤 2 直接命中，再也不会走 ancestor walk。

### 3. Test 覆盖

- `findJarPathR268.test.ts`：5 个 test 验证 R268 portable install
  + ancestor walk 顺序
- `zipLayoutR268b.test.ts`：3 个 test 验证 latest release zip 包含
  `desktop/aethercode.jar`，jar 非空，是合法 zip
- 加 4 个 R268b test：`R268b marker 在 find_jar_path 中`、
  `parent_dir.join("aethercode.jar") 调用`、
  `parent_dir wildcard scan`、`parent-dir 在 ancestor walk 之前`

### 4. 本地清理

- `aethercode/dist/aethercode.jar` (stale 0.2.66 build) →
  `aethercode.jar.bak`，跟之前的 .bak 文件同等待遇

## 产出

| 路径 | SHA | Size |
|---|---|---|
| `release/aethercode-0.2.70/desktop/aethercode-desktop.exe` | `D10A6836DF633EF1A54C64BC5433FFA54EB76590` | 4,161,024 |
| `release/aethercode-0.2.70/desktop/aethercode.jar` | `337DCAE816EC072665EDE34DBCB3C06F07EC509D` | 56,582,324 |
| `release/aethercode-0.2.70.zip` | `F9D2076249D10D9A0FEBD611E307B608EA9FB775` | 107,468,262 |

zip 比上一版大 (56 MB → 107 MB) 因为 jar 在 desktop/ 和 parent
dir 各一份。R268b 让两种 layout 都能 work。

## Bytecode verification

exe 字符串检查（`strings` 命令）：
- `[R268]` 4 处
- `[R268b]` 2 处  
- `parent_dir` 0 处（Rust 优化器 strip 了局部变量名，但保留 eprintln 字符串）
- `[R268b] find_jar_path: portable install (parent-dir exact)` ✓
- `[R268b] find_jar_path: portable install (parent-dir scan)` ✓

jar 字节码检查（extract inner jar → 查 class 文件）：
- ProgressLoopDetector.class: isStructurallyEmpty (1), emptyInputStreak (3), lastLoopKind (1)
- QueryEngine$1.class: empty_tool_input (2)
- StreamingToolExecutor.class: buildMissingParamError (1), missing fields (1)
- 6/6 R266i markers ✓

## 验证

- vitest 1069/1069 pass（含 7 个新 test）
- typecheck clean
- 0 回归
- daemon 用 R266i jar 启动成功，监听 18891，JSON-RPC 响应正常

## 教训 (新增)

215. **zip layout 是部署的一部分** — 修改 exe 的 jar 查找逻辑后，必须
     同步修改 zip 打包脚本，否则 exe 的新 fallback 路径是空中楼阁
216. **fallback 链要 cover 所有合法 layout** — exe 旁 + parent dir +
     ancestor walk + env var，4 层都要测
217. **bytecode verification ≠ deployment** — 上一轮 R268 我以为 jar
     已经在 desktop/，实际只在 parent dir，user 重新解压就丢失
218. **release 目录是单一真相** — 如果 jar 在多个地方（desktop/
     parent dir / dist/），找 jar 的逻辑就一定会混乱。要么 jar
     只在一个地方，要么 fallback 链要明确优先级
219. **"拷过去了" 不等于 "在 zip 里"** — manual copy 不进 release
     artifact，下次 release / re-pack 全部丢失
220. **deploy verification 必须看 zip 内容** — `ls release/` 不够，
     `unzip -l release/*.zip` 才是真验证

## 用户下一步

解压新的 `release/aethercode-0.2.70.zip` (107 MB)，
跑 `desktop/aethercode-desktop.exe`。exe 启动时 stderr 应该
有：
```
[R268] find_jar_path: portable install (exact next-to-exe) D:\...\desktop\aethercode.jar
```
如果还失败，抓 stderr log 看 `find_jar_path:` 那行就知道走的是
哪条 fallback 路径。