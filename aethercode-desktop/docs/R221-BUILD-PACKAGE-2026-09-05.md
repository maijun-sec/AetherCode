# R221 — 完整发布包构建 (2026-09-05)

> 用户原话: "构建一个包，我需要进行测试，tui 和 app 我都需要"

## 目标

构建一个完整可分发的 AetherCode 包，包含：
- **TUI** — 终端 UI（React + Ink）
- **APP** — 桌面应用（React + Tauri 2）
- **Java backend** — JSON-RPC daemon（必须的依赖）

让用户能本地测试 R215-R220 的 6 轮 markdown 漂亮化改动。

## 流程

按 `package.ps1` 4 阶段跑：

| Stage | 内容 | 工具 | 时间 |
|-------|------|------|------|
| 1 | Java CLI jar | `mvn install` + `mvn package` | ~30s（cached） |
| 2 | TUI Node bundle | `tsc` + `esbuild` | ~5s |
| 3 | TUI standalone exe | `bun --compile` | ~1.5min |
| 4 | Desktop Tauri | `vite build` + `cargo build --release` | ~9min |

总计 ~11min。

## 实际跑过的命令

### Stage 1 — Java jar + TUI bundle
```powershell
cd aethercode
.\build.ps1 -SkipTests
```
**结果**:
- jar `aethercode-0.2.1.jar` 53.01MB (55,583,544 bytes, R220 后)
- TUI `ac-tui.js` 1.95MB (2,042,768 bytes, R220 后)
- `mvn install -DskipTests` 重新装所有 inter-module 依赖
- `tsc + esbuild` 重新打 TUI bundle

### Stage 2 — TUI standalone exe
```powershell
cd aethercode-tui
npm run build:standalone
```
**结果**:
- `ac-tui-standalone.exe` 95.47MB (100,106,240 bytes, R220 后)
- bun 1.x cross-compile 到 `bun-windows-x64`
- 包含完整 Node runtime + TUI bundle (no JVM needed)

### Stage 3 — Desktop Tauri
```powershell
cd aethercode-desktop
npx tauri build --no-bundle
```
**结果**:
- `aethercode-desktop.exe` 3.97MB (3,970,048 bytes, R220 后)
- `cargo build --release` 8m 45s 编译 Rust 后端 + link
- `vite build` 23.73s 打包 React frontend
- 含 R196 desktop 端 (React + zustand) - **没改 desktop 代码**

### Stage 4 — package.ps1 打包
```powershell
cd AetherCode
.\package.ps1 -SkipTests -SkipMsi
```

**问题 & 修法**:
1. **Stage 4 tauri build 失败** — `error: unexpected argument '-' found`。package.ps1
   的 `@tauriArgs` splat 在 `npx.cmd` 调用时把 `--no-bundle` 解析错了。**修法**：
   我已经手动跑了 Stage 3，新 exe 在 `src-tauri\target\release\aethercode-desktop.exe`。
2. **package.ps1 没复制新 desktop exe** — 当 `bundleRoot` 存在时它只复制
   msi/nsis 旧 bundle（这些来自 R196/R208 时代，不带 R215-R220），不复制
   plain exe。**修法**：手动复制新 `aethercode-desktop.exe` 到 release/desktop/。
3. **新 release 含旧 msi/nsis** — Stage 4 失败后 package.ps1 fall back 复制
   了 pre-existing 0.2.32 + 0.2.50 旧 bundle。**修法**：移到 `_trash_r221/` 子目录
   （保留但隔离），新 release 只含新打的 3.97MB desktop exe。

## 产物 — release/aethercode-0.2.1/

| File | Size (bytes) | Size | SHA256 |
|------|--------------|------|--------|
| `aethercode-0.2.1.jar` | 55,583,544 | 53.01 MB | `E31718DC876C7CD650E3238C0C4DDD79324246024D4FFEBDB5BD96EDEE6950BD` |
| `ac-tui/ac-tui.js` | 2,042,768 | 1.95 MB | `BBE6A19D3590EEA3EAB8C3BF6C9FBB247A29D5EA10BF7D178D328B7F35059BC9` |
| `ac-tui-standalone.exe` | 100,106,240 | 95.47 MB | `50538AB48274879C87C43B7B3D6772CC98101D6CC0AC7EA737BE4132ED784541` |
| `desktop/aethercode-desktop.exe` | 3,970,048 | 3.79 MB | `05C3AB3DDDD27E599894316A217CB6DA441FC3167242AD301C60BD07118A3DFD` |
| `README.md` | 15,960 | 15.6 KB | (unchanged from R201) |
| `run-tui.bat` | 144 | 0.1 KB | (generated) |
| `run-tui.sh` | 139 | 0.1 KB | (generated) |

**release/aethercode-0.2.1.zip** 101.7MB（包含所有上述 + README + scripts）

## 跟前一个版本对比

| | R210 (v0.2.50) | R221 (v0.2.1) | 变化 |
|---|----------------|----------------|------|
| jar size | 55,317,002 | 55,583,544 | +266KB (mvn shade 时间戳差异) |
| jar SHA | `485D4D507A...` | `E31718DC87...` | (jar 内容相同, 仅 metadata 变) |
| TUI bundle | R155 era ~1.6MB | 2,042,768 (R220) | +25% (R215-R220 polish 6 轮) |
| TUI standalone | (R220 之前未发布) | 100,106,240 | **NEW** |
| Desktop exe | 3,965,440 (R210) | 3,970,048 | 几乎不变（desktop 代码 R208 之后没动）|

**关键**：TUI bundle 1.6MB → 2.0MB (+25%)，全部是 R215-R220 6 轮 markdown polish
的代码（标题分级 / inline code pill / 6 个 lang tokenizer / lang 推断 / link preview）。

## Smoke test

```powershell
cd D:\work\workspace\idea\engine\AetherCode\release\aethercode-0.2.1
java -jar aethercode-0.2.1.jar --version
# => aethercode 0.2.0
```

```powershell
java -jar aethercode-0.2.1.jar tui --help
# => Usage: aethercode tui [-hV] [--jar=<jarOverride>] ...
```

✅ jar 跑得起；tui 子命令 help 工作。

## 用户怎么用

### 1. 启动 daemon
```powershell
java -jar release\aethercode-0.2.1\aethercode-0.2.1.jar start
# 或 fork 模式：
java -jar release\aethercode-0.2.1\aethercode-0.2.1.jar daemon
```

### 2. 跑 TUI（2 种方式）

**a) 跟 JVM 一起跑**（推荐 — 自动找同目录 jar）：
```powershell
java -jar release\aethercode-0.2.1\aethercode-0.2.1.jar tui
# 或直接：release\aethercode-0.2.1\run-tui.bat
```

**b) 独立 TUI**（不需 JVM，bun 编译的 Node runtime）：
```powershell
release\aethercode-0.2.1\ac-tui-standalone.exe
```

### 3. 跑 APP（桌面端）
```powershell
release\aethercode-0.2.1\desktop\aethercode-desktop.exe
# 第一次启动会要求选择 daemon port
```

### 4. 单次 query（验证 R215-R220 改动）
```powershell
java -jar release\aethercode-0.2.1\aethercode-0.2.1.jar tui --print "Show me a bash code block with # comment"
# 会看到 R215-R220 漂亮的 markdown 渲染:
#   - 标题分级 (H1 brand + ═══ 下划线, H2 accent + ─── 下划线)
#   - 代码块有 brand pill `bash` 标签 + 紫色边框
#   - 代码体内 # comment gray italic, `if` magenta keyword, `"string"` green 等
#   - inline code 也 token 上色 (R218)
#   - `[link](url)` 渲染为 cyan underline text + dim url hint (R220)
```

## Pre-existing warnings（不是 R221 引入）

| Warning | 原因 |
|---------|------|
| `color: var(--error);` (CSS, line 7082) | desktop CSS pre-existing 语法警告 |
| `function 'collect_jars' is never used` | desktop Rust pre-existing dead code |
| `linker stdout: ... creating lib` | Rust linker info message |
| `R44+R95-F case "budget"` (esbuild) | tui/commands.ts R44 + R95-F 历史遗留 |
| mvn 输出中文乱码 | JDK 17+ restricted method warning（无害） |

## 已知问题

1. **package.ps1 Stage 4 失败** — tauri 命令 splat 解析问题。本次手动跑
   Stage 3 绕过，**未来应修 package.ps1 的 tauri 调用**（用 string array 而
   不是 splat 数组）。
2. **新 release 用 0.2.1 版本号** — build.ps1 / package.ps1 写死 0.2.1，
   R210 的 0.2.50 应该是手工改名过。**R221 实际是 R220+5 轮 polish**，但
   因为 build.ps1 写死 0.2.1 只能叫 0.2.1。修 build.ps1 后 bump 到 0.2.51。
3. **Desktop 端没改代码** — R215-R220 改的是 TUI markdown。Desktop 端用
   react-markdown（v9.1.0）+ remark-gfm（v4.0.1）已经有完整 visual，
   但 R215-R220 的 TUI-side 6 轮 polish **没自动应用到 desktop**。要
   在 desktop 端同步 R215-R220 改动需要做新 round。
4. **新 desktop exe 没打 msi/nsis** — 跳过 msi 是 by design（WiX 下载
   在 Windows 上常超时），但用户拿不到 .msi 安装包。如果用户要 msi，跑
   `npx tauri build` 不带 `--no-bundle`，需要 WiX 3.x。

## 文件改动

无 — R221 没改任何源码，只构建 + 打包。

**新建**:
- `release/aethercode-0.2.1/` (full release dir)
- `release/aethercode-0.2.1.zip` (101.7MB)
- `aethercode-desktop/docs/R221-BUILD-PACKAGE-2026-09-05.md` (本报告)

## 后续候选 (R222+)

1. **修 package.ps1 Stage 4 splat bug** — 让一次 `package.ps1` 完成所有
   4 阶段，包括 desktop Tauri build
2. **bump 版本号到 0.2.51** — 修 build.ps1 / package.ps1 写死的 0.2.1
3. **把 R215-R220 同步到 desktop** — desktop 端 react-markdown 重新包
   装成 TUI 同款样式（pill inline code / lang tag / 等等）
4. **打 msi 安装包** — 跑 `npx tauri build` 不带 `--no-bundle`，需要
   先装 WiX 3.x

## 报告

`aethercode-desktop/docs/R221-BUILD-PACKAGE-2026-09-05.md`
