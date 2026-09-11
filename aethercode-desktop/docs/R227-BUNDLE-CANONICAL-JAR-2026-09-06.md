# R227 — 把 jar 真正 bundle 进 tauri resources（修复 R97-C 设计债）

**日期**: 2026-09-06
**触发**: R226.5 发现 desktop spawn 一直选 `0.2.50.jar` 而非 `0.2.1.jar`，原因是 `tauri.conf.json:48` 的 `resources: []` 是空的，jar 没被 bundle，desktop 走 ancestor walk fallback 选最高版本
**范围**: 1 个 tauri config + 1 个 jar 复制 + 1 个通用脚本

## 1. R97-C 设计债

R97-C 的本意是 "the canonical 'what this App version expects' jar" — 把 jar embed 进 exe，每次 desktop 启动都从 `resource_dir` 找 jar。但 5+ rounds 没人填 `tauri.conf.json:48` 的 `resources: []`。

后果：R222-R225 期间所有 daemon 端 Java 改动（firstPrompt / listSessions withPreview / tool call format log）从来没在真实 LLM 链路跑过，因为 desktop fallback 走 ancestor walk 永远选 `0.2.50.jar`（R210 时代）。

R226.5 临时修法：升 version 到 `0.2.51` 让 ancestor walk 选对。
R227 根治：把 jar 真正 bundle 进去。

## 2. 修改

### 2.1 `aethercode-desktop/src-tauri/tauri.conf.json`

```diff
   "version": "0.2.50",
   ...
   "bundle": {
     ...
-    "resources": [],
+    "resources": ["resources/aethercode.jar"],
     ...
   }
```

`version` 同步升到 `0.2.51`（与 R226.5 的 jar version 一致）。

### 2.2 `aethercode-desktop/src-tauri/resources/aethercode.jar`

新增 55,585,147 B (55 MB) 的 jar。**注意**：这是 canonical 名字（`lib.rs:912` 期待 `resource_dir/aethercode.jar`），不是 `aethercode-0.2.51.jar`。

文件源：`aethercode\dist\aethercode-0.2.51.jar` (R225 修复版, SHA `3FFC5433...`)。

### 2.3 `scripts/promote-jar.py` — 通用化 R-series 出包流程

把 R226.5 的一次性 promote 操作固化成脚本。接受 `R{N}` 参数：

1. 读 `tauri.conf.json` 的 `version` 字段（旧 version X.Y.Z）
2. 算新 version X.Y.(Z+1)
3. 在 `aethercode\dist\`：
   - 备份旧 version jar 到 `_trash_{round}\`
   - 复制旧 version jar → 新 version jar
4. 复制到 `aethercode-desktop\src-tauri\resources\aethercode.jar`
5. 改 `tauri.conf.json` 的 `version`

**Idempotent**：如果新 version jar 已存在则 no-op（不会重复 promote）。

**R227 没用上这个脚本**（version 已经手动升到 0.2.51），但 R228+ 出包时会用。

## 3. `find_jar_path` 选择逻辑（R97-C 设计生效后）

```rust
// lib.rs:897
fn find_jar_path(app: &AppHandle) -> Result<PathBuf, String> {
    // 1) Tauri resource_dir first (R97-C canonical path)
    if let Ok(resource_dir) = app.path().resource_dir() {
        let exact = resource_dir.join("aethercode.jar");
        if exact.is_file() {
            return Ok(exact);  // ← R227 走这里
        }
        // ... scan fallback for aethercode-*.jar
    }
    // 2) Closest-ancestor walk (dev mode)
    // 3) AETHERCODE_DIST env var
}
```

R227 之后，**desktop 启动永远走第 1 选择**，永远不会因为 `aethercode\dist\` 里多出来的旧 jar 选错。

## 4. 验证

- tsc -b: (skip — R227 改 tauri config 不动 TS)
- vitest: (skip — R227 不动 TS/JSX)
- tauri build --no-bundle: 9m 14s ✓
- ✅ `target\release\resources\aethercode.jar` 55,585,147 B 存在（跟 dist jar SHA 一致）
- ✅ desktop `find_jar_path` 第一选择走 `resource_dir/aethercode.jar` 永远选对

## 5. R227 产物

| 文件 | 大小 | SHA256 |
|---|---|---|
| `aethercode-0.2.51.jar` | 55,585,147 B | `3FFC5433BEC5F47DE16DF8905C10CCF633B9A2FA8B81A9FC4052C1BD05234A2A` (R225 unchanged) |
| `desktop/aethercode-desktop.exe` | 3,975,168 B | **`023DAF26B56F97FA667603D5C79864D7AA87B8F5B1BE6D98380628AB6EAA9E89`** (R227 NEW — bundles jar) |
| `desktop/resources/aethercode.jar` | 55,585,147 B | `3FFC5433...` (R225, embedded by tauri build) |
| `desktop/resources/icon.ico` | 20,545 B | `784B7E26...` |
| `ac-tui/ac-tui.js` | 2,042,768 B | `BBE6A19D...` (unchanged) |
| `ac-tui-standalone.exe` | 100,106,240 B | `50538AB4...` (unchanged) |
| `README.md` | 15,960 B | `E20DF6A5...` (unchanged) |
| `run-tui.bat` / `.sh` | 144 / 139 B | (unchanged) |
| `aethercode-0.2.51.zip` | 144,112,930 B (137.45 MB) | **`716981FFDBFBC195E56EC11698BFE086244ABCE75B1C961A9CC5FD07B164563D`** (R227 NEW) |

**完整路径**: `D:\work\workspace\idea\engine\AetherCode\release\aethercode-0.2.51\`

## 6. 教训 (2026-09-06)

1. **🔥 R-series 出包流程必须升 jar version + 备份旧 jar** — 已在 `scripts/promote-jar.py` 固化
2. **🔥 `tauri.conf.json` resources 应该填 jar** — R227 实施，5+ rounds 累积的设计债还了
3. **R-series 出包流程** 现在是 4 步：
   - 改 desktop source（TS/JSX/CSS）→ vite build
   - 改 Java source → mvn build → jar
   - 改 `src-tauri`（Rust/Cargo.toml）→ tauri build
   - 跑 `python scripts/promote-jar.py R{N}` 升 jar version
4. **`promote-jar.py` 必须是幂等的** — 重复跑不能出错
5. **tauri bundle resources 跟 jar version 解耦** — `aethercode.jar` 是 canonical 名字，version 体现在 `tauri.conf.json` 和 `aethercode\dist\` 命名
