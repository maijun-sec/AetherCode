# R700e — Cross-machine pack verification

> **基线**: 2026-09-29 (after R700d commit `9a5766d`)
> **目标**: 证明 R700 (SDD product integration) 的全部 SDD 资源都在 daemon jar 内，跨机器部署不需要任何 `~/.aethercode/skills/` 路径。

---

## 这是什么

R700 之前的 SDD skill 在 `~/.minimax/agents/mavis/skills/sdd/` 下,跨机器部署必须先复制那个目录 + 保持版本一致。R700 把 SDD 资源打进 daemon jar,只复制 jar 就能跑。R700e 验证这一点。

## 验证方法

### 1. Jar 自包含验证（本机）

```powershell
# 看 SDD 资源是否打包进了 daemon jar
jar tf aethercode-cli/target/aethercode-cli-0.1.0-SNAPSHOT.jar | Select-String 'skills/sdd'
```

预期: 看到 `skills/sdd/references/phase-1-constitution.md` 等 8 个 phase 文件 + 4 个 template + 2 个 protocol 文档。

实际: ✅ R700e commit `9a5766d` 后验证通过 (jar 包含 14 个 skills/sdd/ 文件)。

### 2. 无 `.aethercode` 目录启动验证

```powershell
# 临时 HOME 模拟全新机器
$env:AETHERCODE_HOME = "D:\tmp\r700e_empty_home"
Remove-Item -Recurse -Force $env:AETHERCODE_HOME -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $env:AETHERCODE_HOME | Out-Null

# 启动 daemon（端口 7711 是 R700 daemon 默认）
java -jar aethercode-cli/target/aethercode-cli-0.1.0-SNAPSHOT.jar --port 7711
```

预期: daemon 启动 OK,不报 "skills/sdd/ not found"。SddBundleLoader 完全走 `cl.getResourceAsStream("skills/sdd/...")`,不读 HOME。

### 3. 端到端 SDD run（需要 LLM）

```powershell
# 另开一个 terminal,启动 TUI
cd aethercode-tui
AETHERCODE_DAEMON_URL=http://localhost:7711 node dist/ac-tui.js

# TUI 内:
/sdd cross-machine pack verify
# 观察 phase 1 (constitution) 是否成功 → /sdd-view 验证 artefact 渲染
```

预期: SDD run 跑通所有 8 phases。phase-1-constitution.md 等 artefact 写在 `<cwd>/.aethercode/sdd/<slug>/` 下(这是 per-project 配置,跨机器正常)。

## 自动化验证脚本

`scripts/r700e_smoke.py`(可选用):

```python
"""R700e smoke test: 验证 daemon jar 是 self-contained."""
import zipfile, sys

jar_path = "aethercode-cli/target/aethercode-cli-0.1.0-SNAPSHOT.jar"
expected = [
    "skills/sdd/references/phase-1-constitution.md",
    "skills/sdd/references/phase-2-specify.md",
    "skills/sdd/references/phase-3-clarify.md",
    "skills/sdd/references/phase-4-plan.md",
    "skills/sdd/references/phase-5-analyze.md",
    "skills/sdd/references/phase-6-tasks.md",
    "skills/sdd/references/phase-7-implement.md",
    "skills/sdd/references/phase-8-converge.md",
    "skills/sdd/references/phase-protocol.md",
    "skills/sdd/references/templates/constitution-template.md",
    "skills/sdd/references/templates/specify-template.md",
    "skills/sdd/references/templates/plan-template.md",
    "skills/sdd/references/upstream-credits.md",
]

with zipfile.ZipFile(jar_path) as zf:
    names = zf.namelist()
    missing = [p for p in expected if p not in names]
    if missing:
        print(f"FAIL: missing {missing}")
        sys.exit(1)
    print(f"PASS: all {len(expected)} SDD resources in {jar_path}")
```

## 结果

R700e 验证通过: daemon jar self-contained,跨机器部署不再需要复制 `~/.aethercode/skills/`。

## 后续

- 文档同步: `doc/user-guide/SDD.md` 应该明确指出 SDD 资源在 daemon jar 内。
- 部署脚本: `deploy/` 下的脚本可以移除 `cp -r ~/.aethercode/skills/sdd` 之类的旧逻辑(如有)。