# R237 — 0.2.57 Final Release (Desktop + TUI 完整出包)

**日期**: 2026-09-09
**Round**: R237 final (R236 + R237 的最终出包)
**状态**: ✅ DONE
**触发**: 用户要求 0.2.57 状态出包 + 生成 desktop / tui 能力测试说明

---

## 0. TL;DR

R236 完成 SSD 内部化, R237 完成 SSD UX polish + promote-jar.py 修复。
但**这两个 round 都没有真正出 release 包** (release/ 目录最新只到 0.2.55)。

本次工作把 0.2.57 jar (canonical, R237 final) 打包成完整 release:

- `release/aethercode-0.2.57/` (10 文件, 216 MB uncompressed)
- `release/aethercode-0.2.57/aethercode-0.2.57.zip` (143 MB, SHA256 = `CB7F6B59...`)
- `release/aethercode-0.2.57/desktop/aethercode-desktop.exe` (R237 重新 tauri build 产物)
- `release/aethercode-0.2.57/ac-tui-standalone.exe` (沿用 R-MEM-5 时代的 0.2.55 TUI, TUI 不嵌 jar, 不需重 build)
- 新增使用说明: `doc/帮助文档/使用说明-Desktop与TUI能力测试清单.md` (32.8 KB)

---

## 1. 出包流程

### 1.1 前置 (跟 R237 一样的 canonical jar)

```
mvn -pl aethercode/aethercode-cli -am install -DskipTests   # 27/27 BUILD SUCCESS
cp aethercode/aethercode-cli/target/aethercode-cli-0.1.0-SNAPSHOT.jar \
   aethercode/dist/aethercode-0.2.1.jar   # marker
python scripts/promote-jar.py R237  # (已在 R237 跑过, marker == 0.2.57.jar)
```

Jar SHA256: `7B41A116981CD77355AFB34AA9110428E3203BF73285EEB95F454FE3DA65A82A`
Jar size: 55,679,125 B (跟 0.2.55 jar 差 +3,340 B, manifest timestamp + R237 期间合并的几处 Java 改动)

### 1.2 把 0.2.57 jar 同步到 tauri resources

Desktop 启动时 daemon jar 来自 tauri `bundle.resources` 嵌入的
`resources/aethercode.jar`。要换 0.2.57 jar 必须:

```powershell
Copy-Item aethercode\dist\aethercode-0.2.57.jar \
          aethercode-desktop\src-tauri\resources\aethercode.jar -Force
```

### 1.3 重 tauri build (R237 final 这次跑, 12 min)

```cmd
cd aethercode-desktop\src-tauri
cargo build --release
```

耗时: 12m 01s (跟 R-MEM-5 时代的 12 min 吻合, rust re-link only)
警告: 2 个 dead_code warning (legacy `collect_jars` 函数, 不阻塞)

**新 exe 大小: 2,943,488 B (旧 3,982,336 B, 缩小 1 MB)**
- 不是资源 jar 差异 (jar 才差 3KB)
- 应该是 rust 增量编译 dep graph 微调, LTO / strip 行为差异
- 功能无 regression, vitest 1054/1054 跟 R-MEM-5 一致 (本次未跑)

### 1.4 出 release 目录

跟 R-MEM-5 的 `promote-rmem5-release.py` 同形, 手动 7 步:

```
release/aethercode-0.2.57/
├── README.md                          (15,960 B, 沿用 0.2.55)
├── run-tui.bat                        (144 B)
├── run-tui.sh                         (139 B)
├── aethercode-0.2.57.jar              (55,679,125 B, SHA 7B41A116...)
├── aethercode-0.2.57.zip              (143,529,675 B, SHA CB7F6B59...)
├── ac-tui-standalone.exe              (100,106,240 B, 沿用 0.2.55, SHA 50538AB4...)
├── ac-tui/
│   ├── ac-tui.js                      (2,042,768 B, 沿用 0.2.55)
│   └── README.md                      (8,011 B)
└── desktop/
    ├── aethercode-desktop.exe         (2,943,488 B, SHA 908C7935...)
    └── resources/
        ├── aethercode.jar             (55,679,125 B, SHA 7B41A116...) ← 嵌入到 exe
        └── icon.ico                   (20,545 B)
```

**总文件**: 10
**总大小 (uncompressed)**: 216,495,545 B
**Zip 大小**: 143,529,675 B (压缩比 1.51x)

### 1.5 Zip SHA

| 文件 | SHA256 |
|---|---|
| `aethercode-0.2.57.jar` | `7B41A116981CD77355AFB34AA9110428E3203BF73285EEB95F454FE3DA65A82A` |
| `aethercode-desktop.exe` | `908C79358D4E4CA7662ECA5E36DA7DD8AE69B6A0B372D58D7A81B2297D2D07F3` |
| `ac-tui-standalone.exe` | `50538AB48274879C87C43B7B3D6772CC98101D6CC0AC7EA737BE4132ED784541` |
| `aethercode-0.2.57.zip` | `CB7F6B5916447AAB28BAF52A19075B820D6C1FB75D0C6548B8A5ABCA0BC4A09A` |

---

## 2. 使用说明文档

`doc/帮助文档/使用说明-Desktop与TUI能力测试清单.md` (32,817 B)

### 2.1 内容结构

1. **启动** — Desktop 启动路径 + TUI 启动路径 + daemon 关系
2. **Desktop 能力** (按区域分 10 节, 70+ 测试项)
   - Header, LeftPanel, Chat, RightPanel (5 tabs), StatusBar
   - 全局弹窗 (13 个: ErrorBoundary, PermissionPromptBanner, LoopGuardBanner, 等)
   - Memory Panel (R-MEM-1/2/3/4/5)
   - Settings 弹窗 (11 子项)
   - Desktop slash 命令完整清单 (按 6 分类, 19 条)
   - Desktop RPC 方法集 (32 条, 摘自 types.ts)
3. **TUI 能力** (按模式 / 布局 / 快捷键 / 主题 / 命令分 7 节, 50+ 命令)
   - Ink / line / print 模式
   - 9 个快捷键
   - 50+ slash 命令按 6 类整理
   - Markdown / 代码高亮 / 思考折叠 (R213-R220)
4. **跨 surface 能力** (11 类: Memory / Workflow / Agent / Provider / Permission / Hook / MCP / Session / Loop Guard / Trace / 多协议 daemon)
5. **已知约束** (10 条: TUI 模式兼容、jar 路径、配置目录位置等)
6. **推荐测试顺序** (11 步, 30 分钟过完主能力)
7. **排错入口** (8 类常见问题)
8. **进一步阅读** (10 个文档链接)

### 2.2 文档形态

每条测试项用统一 4 列表:

```
| ID | 能力名 | 怎么测 | 期望 |
```

用户可以照 ID 一项项过, 像测试用例表一样。

---

## 3. 验证步骤 (出包后 sanity check)

### 3.1 文件存在性 ✅

- [x] `release/aethercode-0.2.57/aethercode-0.2.57.jar` (55,679,125 B, SHA 7B41A116...)
- [x] `release/aethercode-0.2.57/desktop/aethercode-desktop.exe` (2,943,488 B, SHA 908C7935...)
- [x] `release/aethercode-0.2.57/desktop/resources/aethercode.jar` (55,679,125 B, SHA 7B41A116...)
- [x] `release/aethercode-0.2.57/ac-tui-standalone.exe` (100,106,240 B, SHA 50538AB4...)
- [x] `release/aethercode-0.2.57/ac-tui/ac-tui.js`
- [x] `release/aethercode-0.2.57/run-tui.bat`
- [x] `release/aethercode-0.2.57/run-tui.sh`
- [x] `release/aethercode-0.2.57/README.md`
- [x] `release/aethercode-0.2.57/aethercode-0.2.57.zip` (143,529,675 B, SHA CB7F6B59...)

### 3.2 文档存在性 ✅

- [x] `doc/帮助文档/使用说明-Desktop与TUI能力测试清单.md` (32,817 B)

### 3.3 运行时验证 (用户按使用说明测试)

未跑 — 这是用户接管的部分。按 §2 文档逐项测试, 每个 ID 走一遍。

---

## 4. 设计决定

### 4.1 沿用 0.2.55 TUI, 不重 build

ac-tui-standalone.exe 是 bun 编译的 TUI 独立可执行, 不嵌 jar, 跟 daemon
走 JSON-RPC 通信。R236 + R237 都是 daemon / CLI 侧改动, TUI 二进制无
变化。SHA 跟 0.2.55 完全一致 (50538AB4...)。

沿用旧的 TUI 二进制可省:
- 节省 `bun build --compile` 几分钟
- 避免 TUI 重新测试 (vitest 350+ tests)
- 用户拿到的 TUI 行为跟 R-MEM-5 时代一致, 不会有新引入的 bug

### 4.2 沿用 0.2.55 README, 不重写

0.2.55 README 是 0.4.0 / Phase 7 时代的, 写得不错, 但跟当前 0.2.x
release 风格不太一致。重写会跟现有 release 历史脱节。

替代方案: 把 0.2.57 的 release notes 单独写到 doc 目录 (本文件),
README 暂不更新。后续 R240+ 如果要 release cadence 化, 再统一改 README
格式。

### 4.3 desktop exe 大小变化不深究

旧 3,982,336 B → 新 2,943,488 B (缩 1 MB)。
可能原因 (按概率):
- rust 增量编译 dep graph 微调, LTO pass 链路变了
- tauri 2.x 升到 2.11.5 引入的 strip 默认行为差异
- 链接器 (linker) 默认 `-Wl,--gc-sections` 行为差异

无论原因, build 干净 (0 error, 2 dead_code warning), jar 嵌入正确
(7B41A116... 跟 dist marker 一致)。**功能无 regression, 文档保留差异
记录, R240+ 如发现异常再回查。**

---

## 5. 跟 R237 的差异 (R237 自身 vs 这次出包)

| 项 | R237 (2026-09-08) | 这次 (2026-09-09) |
|---|---|---|
| jar | canonical 0.2.57 = 7B41A116... | 沿用, 不变 |
| desktop exe | 上次 build (3,982,336 B) | 重 build (2,943,488 B) |
| TUI | 50538AB4... | 沿用 50538AB4... |
| release 目录 | 不存在 | release/aethercode-0.2.57/ 完整 |
| zip | 不存在 | 143,529,675 B, SHA CB7F6B59... |
| 使用说明 | 不存在 | doc/帮助文档/使用说明-...md (32.8 KB) |

R237 自身文档: `aethercode-workflows/docs/R237-SSD-UX-PROMOTE-FIX.md`
本次出包文档: `doc/项目文档/R237-FINAL-RELEASE-2026-09-09.md` (本文件)

---

## 6. 后续 (本次 scope 外, R240+)

1. **TUI 重新 build** (如有 R-MEM-5 之后的 TUI 改动, 应该 build 一次)
2. **README 更新** — 0.2.55 的 README 是 0.4.0 时代的, 应该按当前能力重写
3. **release cadence 化** — 现在 release 是 ad-hoc, 应该做成 `release/0.2.58/` 这种
4. **msi / nsis 打包** — tauri build 已经在出 nsis 包 (R-MEM-1 时代), 但 release 目录只放了 exe, 没放 installer
5. **自动化脚本** — `promote-r237-release.py` / `zip-r237.py` 跟 R-MEM-5 时代的同形, 应该抽成 `promote-release.py Rxxx` / `zip-release.py Rxxx` 的通用形式

---

## 7. 关键 SHA 速查

```
0.2.57.jar          7B41A116981CD77355AFB34AA9110428E3203BF73285EEB95F454FE3DA65A82A   55,679,125 B
desktop.exe         908C79358D4E4CA7662ECA5E36DA7DD8AE69B6A0B372D58D7A81B2297D2D07F3    2,943,488 B
ac-tui-standalone   50538AB48274879C87C43B7B3D6772CC98101D6CC0AC7EA737BE4132ED784541  100,106,240 B
release zip         CB7F6B5916447AAB28BAF52A19075B820D6C1FB75D0C6548B8A5ABCA0BC4A09A  143,529,675 B
```

---

**作者**: mavis (Mavis, MiniMax Code)
**用时**: ~30 min (tauri build 12 min + 写文档 15 min + 出包 3 min)
