# Getting Started — 5 分钟上手

> 安装 AetherCode,配置一个 LLM provider,**5 分钟内**跑通第一个 query。
>
> **整合自 `doc/GETTING-STARTED.md`** (R250+7 doc 重构),已翻译为中文。

---

## 1. 安装

AetherCode 有 4 种发行形式:

| 形式 | 适用场景 | 文件 |
|---|---|---|
| Shaded CLI jar | 生产 / CI | `aethercode-<version>.jar` (~40 MB) |
| TUI standalone exe | 本地开发, 无需 JVM | `ac-tui-standalone.exe` (~95 MB) |
| TUI Node bundle | 本地开发,有 Node | `ac-tui/ac-tui.js` (~1.5 MB) |
| Desktop app (Tauri) | 终端用户 GUI | `aethercode-desktop.exe` + `.msi` 安装包 |

选最适合你的。对大多数读者,**TUI standalone 是最快路径**。

### 1.1 TUI standalone (Windows, 无 JVM)

从 release zip 下载 `ac-tui-standalone.exe`:

```
aethercode-<version>.zip
├── aethercode-<version>.jar
├── ac-tui/ac-tui.js
├── ac-tui-standalone.exe     ← 运行这个
└── run-tui.bat
```

双击 `ac-tui-standalone.exe` (或 `run-tui.bat` 跑 JVM-based TUI)。standalone exe 内部嵌入了 Java runtime 和 daemon jar,**无外部依赖**。

### 1.2 CLI jar (任意 OS,需要 Java 21+)

```bash
java -jar aethercode-<version>.jar tui
```

需要:
- **Java 21 或更高** (`java -version` 检查)
- POSIX shell 或 PowerShell
- `~/.aethercode/` 可写

### 1.3 Desktop app (Tauri)

跑安装包 (`aethercode-desktop-<version>.msi` on Windows, `.dmg` on macOS, `.deb` / `.AppImage` on Linux)。安装包会注册 Start Menu 入口和桌面快捷方式。

---

## 2. 配置 Provider

AetherCode 至少需要一个 LLM provider。**默认装好不配** — engine 启动但每个 query 都会失败 "no current provider"。配一个:

### 2.1 方式 A: 配置文件 (推荐, 适合 CI)

编辑 `~/.aethercode/settings.yaml`:

```yaml
providers:
  - name: anthropic
    type: openai-compatible
    baseUrl: https://api.anthropic.com
    apiKey: ${ANTHROPIC_API_KEY}
    defaultModel: claude-sonnet-4
    models:
      - id: claude-sonnet-4
        inputPer1k: 0.003
        outputPer1k: 0.015
      - id: claude-opus-4
        inputPer1k: 0.015
        outputPer1k: 0.075
```

`${VAR}` 引用启动时从环境读。每次 daemon 重启文件重载;Desktop Settings 面板镜像解析后的结构。

### 2.2 方式 B: 应用内选择

TUI 里 `/state` 显示当前 model。切换:

```
/model claude-opus-4
```

Desktop 里 Settings 面板的 "Models" tab 显示 picker。点 model 行,**下一次 query 就用新 model** (R109-3 修的 hot-swap)。

### 2.3 验证

跑 `/state` (TUI) 或打开 Header (Desktop)。你应该看到 `model: anthropic/claude-sonnet-4` (或你配的)。

---

## 3. 第一个 Query

### 3.1 TUI

输入框打字,回车。例子:

```
> what is 2 + 2?
> summarise @README.md
> /help
> /agents            # 列出 AetherCode Agent (R109-3)
> /agent reviewer    # 显示 "reviewer" Agent 的 body
```

### 3.2 Desktop

中间 chat view 同样的输入框。打字回车,流式响应实时渲染 (Markdown,tool call 在可展开 card 里)。

### 3.3 CLI (`--print` 模式)

非交互场景 (CI,脚本):

```bash
java -jar aethercode-<version>.jar --print "summarise this file: @README.md"
```

`--print` 跑一个 query,打印响应,成功 exit 0,失败非零。一次性任务用它。

---

## 4. 常见第一天任务

| 任务 | 怎么做 |
|---|---|
| 切换当前 model | `/model <name>` (TUI) 或 Settings (Desktop) |
| 打开 Agent 编辑 | `Settings → Agents tab → 点行` (Desktop); `/agent <name>` (TUI) |
| 把 Agent 绑到特定 model | 编辑 Agent 的 `model:` frontmatter (Settings editor) |
| 跑多步 workflow | `Workflows tab → New → YAML editor` |
| 看 token 用量 / 成本 | Status bar (实时); `/metrics` (TUI 快照) |
| 看最近一次运行的 trace | `/trace` (TUI 列表) 或 `/trace tr-...` (单个 tree) |
| 保存 session 留待后用 | Sessions tab (Desktop — 自动); TUI 里手动 `/sessions` |

---

## 5. 下一步看哪

- [`./使用说明-Desktop与TUI能力测试清单.md`](./使用说明-Desktop与TUI能力测试清单.md) — 每个 surface 完整 walkthrough
- [`./agents.md`](./agents.md) — Agent 编写和 R109-3 model 绑定
- [`../tech-docs/workflow-engine.md`](../tech-docs/workflow-engine.md) — workflow YAML 格式
- [`../tech-docs/providers.md`](../tech-docs/providers.md) — 加新 provider
- [`./troubleshooting.md`](./troubleshooting.md) — 常见错误
