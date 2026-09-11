# Packaging — Release 打流程

> 怎么打出 AetherCode release bundle: shaded CLI jar、TUI Node bundle、TUI standalone exe、Desktop Tauri 安装包。
>
> **整合自 `doc/PACKAGING.md`** (R250+7 doc 重构),已翻译为中文。

---

## 1. TL;DR (一行命令)

```powershell
# Windows
.\package.ps1

# Linux / macOS
./package.sh
```

跑完 4 个 build stage,产出 `release/aethercode-<version>/` 和 `release/aethercode-<version>.zip`。

---

## 2. 脚本做了什么

```
package.ps1
  ├── Stage 1/4: Java CLI jar           (delegates to aethercode\build.ps1)
  │     - mvn install (全测试套件)
  │     - mvn -pl aethercode-cli package → shaded jar
  │     - copy 到 dist\aethercode-<version>.jar
  │
  ├── Stage 2/4: TUI Node bundle       (delegates to aethercode\build.ps1)
  │     - tsc -p tsconfig.json
  │     - esbuild bundle → dist\ac-tui\ac-tui.js
  │
  ├── Stage 3/4: TUI standalone exe    (aethercode-tui\package.json)
  │     - bun build --compile --target=bun-windows-x64
  │       --outfile dist\ac-tui-standalone.exe src/tui.tsx
  │
  └── Stage 4/4: Desktop Tauri          (aethercode-desktop\package.json)
        - vite build (frontend bundle)
        - tauri build (Rust bundle → .msi / .exe / .dmg / .deb / .AppImage)
```

脚本然后把所有这些打包到 `release/aethercode-<version>/` 并 zip。

---

## 3. 命令行开关

| 开关 | 作用 |
|---|---|
| `-SkipTests` | 跳过 Java 测试套件 (加快打包迭代) |
| `-SkipDesktop` | 跳过 Tauri build (省 5-10 分钟 + 不用装 Rust toolchain) |
| `-SkipTuiStandalone` | 跳过 bun build (Windows-only,需要 bun) |
| `-SkipZip` | 不打 zip (只留 release 目录) |
| `-Version <X.Y.Z>` | 覆盖版本号 |
| `-Clean` | 打包前清空 `dist/` 和 `release/` |

## 4. 环境变量

| 变量 | 作用 |
|---|---|
| `AETHERCODE_VERSION` | 等同于 `-Version` |
| `AETHERCODE_TUI_DIR` | 覆盖 TUI 源目录 (跨仓 build 用) |
| `AETHERCODE_DESKTOP_DIR` | 覆盖 desktop 源目录 |
| `JAVA_HOME` | JDK 21 位置 (mvn 隐式使用) |
| `PATH` | 必须包含 `mvn`、`bun`、`npm`,可选 `tauri` CLI |

---

## 5. 产出布局

```
release/
└── aethercode-0.2.1/
    ├── aethercode-0.2.1.jar          ← daemon + CLI (40 MB)
    ├── ac-tui/
    │   └── ac-tui.js                 ← TUI Node bundle (1.5 MB)
    ├── ac-tui-standalone.exe         ← TUI standalone (Windows, 95 MB)
    ├── desktop/
    │   └── msi/aethercode-desktop-0.2.1-x64_en-US.msi
    │       (Tauri 安装包; 仅当 Stage 4 跑了)
    ├── run-tui.bat                   ← Windows 启动脚本
    ├── run-tui.sh                    ← POSIX 启动脚本
    └── README.md                     ← 项目顶层 README
```

zip (`release/aethercode-0.2.1.zip`, ~73 MB) 是同样内容减源码树,直接可上传到 GitHub Releases 或内网共享。

---

## 6. 版本号

版本从 `aethercode/dist/CHANGELOG.md` (最近的 `## <version>` heading) 读。release 切版本用 `-Version 0.2.2` 或 `AETHERCODE_VERSION=0.2.2`。

⚠️ **脚本不自动更新** shaded jar 的 `pom.xml` `<version>` tag。**手动在 R-round commit 里改**。

## 7. 跨平台

- **Windows**: 完整支持。mvn / bun / npm / tauri 都跑得动
- **Linux**: mvn + bun + npm 跑得动。Tauri 出 `.deb` 和 `.AppImage` 而不是 `.msi`
- **macOS**: mvn + bun + npm + tauri 跑得动。Tauri 出 `.dmg` 和 `.app` bundle。**通用 binary (arm64 + x86_64) 需要 `--target universal-apple-darwin` flag,脚本默认不传** — 通过 `npm run tauri:build -- -- --target universal-apple-darwin` 透传

---

## 8. 手动打包 (脚本失败时)

有时统一脚本会卡在 toolchain 小问题 (mvn cache stale, 缺 Rust target)。fallback 到手动:

```bash
# 1. Java jar + TUI Node bundle
cd aethercode
mvn -B test
mvn -B -pl aethercode-cli package -DskipTests
cd ..

# 2. TUI standalone (Windows)
cd aethercode-tui
npm install
bun build --compile --target=bun-windows-x64 --outfile dist/ac-tui-standalone.exe src/tui.tsx
cd ..

# 3. Desktop Tauri
cd aethercode-desktop
npm install
npm run tauri:build
cd ..

# 4. Bundle
mkdir -p release/aethercode-<version>
cp aethercode/dist/aethercode-<version>.jar release/aethercode-<version>/
mkdir -p release/aethercode-<version>/ac-tui
cp aethercode/dist/ac-tui/ac-tui.js release/aethercode-<version>/ac-tui/
cp aethercode-tui/dist/ac-tui-standalone.exe release/aethercode-<version>/
cp -r aethercode-desktop/src-tauri/target/release/bundle release/aethercode-<version>/desktop/
```

---

## 9. CI

GitHub Actions matrix build 可以按 OS 跑 `package.sh`。预期时间:

- Linux: 3-4 min (jar + TUI), 7-9 min 含 Tauri
- macOS: 4-5 min (jar + TUI), 10-12 min 含 Tauri
- Windows: 3-4 min (jar + TUI), 8-10 min 含 Tauri

**Tauri 是瓶颈**(Rust 冷编译)。CI 缓存 `~/.cargo/`、`target/`、`node_modules/` 和本地 mvn repo,rerun 控制在 2 分钟内。

---

## 10. 脚本不做什么

- **签名 binary**。Code signing 需要单独步骤 (`signtool.exe` on Windows, `codesign` on macOS, `gpg --sign` on Linux)。加到 CI step
- **推 release channel**。脚本产本地 artifact;上传是 release workflow 的事
- **更新 `pom.xml` / `package.json` / `tauri.conf.json` 里的版本**。脚本从 changelog 读版本。**手动改**或用单独的 bump 工具
- **写 R-round retro doc**。那些在 `aethercode-desktop/docs/` 里,R-round commit 时**手写**

---

## 11. 跨参考

- [`./architecture.md`](./architecture.md) — 产物包含什么 + 运行时怎么配合
- [`../user-guide/getting-started.md`](../user-guide/getting-started.md) — 终端用户怎么用这些产物
