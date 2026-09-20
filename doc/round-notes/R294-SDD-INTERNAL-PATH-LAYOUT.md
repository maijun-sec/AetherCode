# R294 — AetherCode-internal SDD path layout

## 触发

R293 部署后用户实测「每个阶段还是一闪而过，并没有真正干活」。根因不是交互 UI，
是 desktop `release/R292/desktop/aethercode.jar` 不存在 → portable exe 走 mock fallback。

同时路径布局也错了：R292 用了 Spec Kit 上游的
`.specify/specs/<NNN>-<slug>/{constitution, spec, plan, tasks, implement.log}`。
用户明确要 AetherCode 内部约定：
`<cwd>/.aethercode/sdd/<slug>/{constitution.md, spec.md, design.md, tasks.md, dev.log, *.json}`。

## 改动

### 真根因修复：portable mode jar 缺失
- `release/R292/desktop/aethercode.jar` 复制 daemon build 产物到 desktop 同目录
- NSIS/MSI 安装时 jar 嵌入 installer，但 portable 需手动 copy（lesson 620）

### 路径布局
- `SddConfig.artefactRoot` 默认 `.specify` → `.aethercode/ssd` (R294) → `.aethercode/sdd` (R294-followup，小写匹配 daemon `aethercode sdd` 子命令)
- `SddRunner.artefactDir` 去 `specs/` 子目录 → `<cwd>/.aethercode/sdd/<slug>/`
- `SddRunner.constitutionPath` 去 `memory/` (per-feature)
- PLAN phase：`plan.md` → `design.md` (匹配 R236 SSD 命名)
- IMPLEMENT phase：`logs/implement.log` → `dev.log`
- `nextSlug` 去 `NNN-` 前缀，冲突时 `-2`/`-3` 后缀
- PROJECT_CONSTITUTION/PROJECT_TEMPLATES_DIR/PROJECT_SDD_YAML 路径同步
- 5 个 Spec Kit 模板 + hard-rules.md 路径引用全部更新
- `SddRunnerTest.java` 3 个 fixture 更新（中文乱码被 PowerShell Set-Content 破坏后用 Read + Write 工具重写 + 剥 UTF-8 BOM）

### R294-followup：UI 优化 + 小写纠正
- Desktop UI toggle label `规格化流程` → `SDD`（全大写）
- SDD toggle + low/medium/high/xhigh pills 合并到 `.config-group-sdd-quality`
  - hairline divider 分隔，6px gap，right padding
  - SDD toggle 紫色 active + quality pills 小写 monospace
  - 移除 SDD toggle 的 `text-transform: lowercase`
- 修复 R294-followup 改时意外删除 Model 选择器（TS6133 setModel/dropdownValue unused）
- 路径从 `.aethercode/ssd` 全部统一改 `.aethercode/sdd`（小写）
- `SddPhaseBar` h3 / aria-label / subtitle 同步 SDD

## Verification

- daemon tests: 39/39 pass (11 + 10 + 12 + 6)
- desktop tests: 1170 pass (101 files)
- desktop ts + tauri build OK
- standalone e2e: `java -jar aethercode.jar sdd calc "x" --auto --to-phase 5 --cwd <dir>`
  产出 `<dir>/.aethercode/sdd/calc/{constitution, spec, design, tasks, clarify}.json`

## Build artefacts

| File | Size | SHA |
| --- | --- | --- |
| `aethercode.jar` | 54.03 MB | (R294-followup) |
| `aethercode-desktop.exe` | 6.23 MB | (R294-followup) |
| `AetherCode_0.3.0_x64-setup.exe` | 52.49 MB | NSIS |
| `AetherCode_0.3.0_x64_en-US.msi` | 54.12 MB | WiX |

## Deploy

- commit `8f47ac5` (R294): Aethercode-internal SDD path layout
- commit `8b8a00d` (R294-followup): SDD 全大写 + 合并 quality picker + 路径小写 sdd
- push `8f47ac5..8b8a00d main -> main`

## 关键技术决定

1. **路径 AetherCode 内部约定优先于 Spec Kit 上游** — 用户两次纠正，daemon 代码 + 测试 fixture + 模板 + README 全部跟改
2. **R236 SSD 命名延续**：`design.md`/`dev.log` 而不是 `plan.md`/`implement.log`
3. **portable mode 必须 jar 跟 exe 同目录** — NSIS/MSI 自动嵌入但 portable 需手动 copy
4. **SDD toggle 全大写 + quality pills 小写合并** — 视觉识别度优先，inline 模式避免 toolbar 横向膨胀
5. **路径小写匹配 daemon 子命令** — `.aethercode/sdd` 对应 `aethercode sdd` 缩写语义

## 教训 (新增 620-622)

620. **Portable mode 必须在 desktop 同目录放 jar** — NSIS 安装时 `bundle.resources` 嵌入，portable 需手动 copy
621. **用户对 Spec Kit 路径 vs AetherCode 内部路径的偏好要在 round notes + 实际执行双重确认** — R292 注释指 vs 实际做的矛盾被 R294 修正
622. **PowerShell `Set-Content` 默认不是 UTF-8，会破坏中文/特殊字符且加 BOM** — 用 Read + Write 工具保持 UTF-8

## R295 follow-up

1. 用户实测新 desktop：cwd `D:\tmp\abc_1`，点 SDD toggle，输入 prompt
2. 验证产物路径：`D:\tmp\abc_1\.aethercode\sdd\<slug>/{constitution, spec, design, tasks, dev.log}`
3. R295+：TUI `/sdd` slash command + supervisor SddService + IDEA Plugin DaemonBackend SDD RPC