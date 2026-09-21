# SDD — Spec-Driven Development

> **面向**: 终端用户 / 第一次用 `aethercode sdd` 的人
> **基线**: R306 (2026-09-21) — 8 阶段流式状态已上桌面
> **依赖**: [github/spec-kit](https://github.com/github/spec-kit) — 阶段定义 + markdown 模板来自上游

---

## 这是什么

**AetherCode 的 SDD (Spec-Driven Development)** 是一个 8 阶段的规格化工作流,从立项到实现到收敛全自动跑一遍。它**沿用** GitHub spec-kit 的阶段定义和 markdown 模板,但**不集成 spec-kit CLI 本体**——AetherCode 的 `SddRunner` 直接读 spec-kit 的模板、调 LLM、写产物,不走外部 subprocess。

阶段拆解:

| # | 阶段 (kebab-case id) | 中文 | Spec Kit 上游模板 | 产物文件 | 必需 |
|---|---|---|---|---|---|
| 1 | `constitution` | 项目原则 | `templates/constitution-template.md` | `.aethercode/sdd/<slug>/constitution.md` | ✅ |
| 2 | `specify` | 需求分析 | `templates/specify-template.md` | `.aethercode/sdd/<slug>/spec.md` | ✅ |
| 3 | `clarify` | 需求澄清 | `templates/clarify-template.md` (推理用) | `.aethercode/sdd/<slug>/clarify.json` | ❌ 可选 |
| 4 | `plan` | 详细设计 | `templates/plan-template.md` | `.aethercode/sdd/<slug>/design.md` | ✅ |
| 5 | `analyze` | 一致性分析 | `templates/analyze-template.md` (推理用) | `.aethercode/sdd/<slug>/analyze.json` | ❌ 可选 |
| 6 | `tasks` | 任务分析 | `templates/tasks-template.md` | `.aethercode/sdd/<slug>/tasks.md` | ✅ |
| 7 | `implement` | 执行实现 | (R236 SSD 内部模板) | `.aethercode/sdd/<slug>/dev.log` | ✅ |
| 8 | `converge` | 收敛验证 | (R236 SSD 内部模板) | `.aethercode/sdd/<slug>/convergence.json` | ❌ 可选 |

**模板来源**: `aethercode-workflows/src/main/resources/spec-kit/{templates,memory}/` 5 个 markdown 文件
+ `hard-rules.md`(R292 加的,统一 LLM 输出格式)。

---

## 怎么跑

### 命令行 (standalone)

```bash
java -jar aethercode.jar sdd <feature> "<intent>" [options]
```

`<feature>` 是项目内的子目录名 (≤10 ASCII 字符,kebab-case),`<intent>` 是需求描述。

常用选项:

| 选项 | 含义 |
|---|---|
| `--auto` | 自动跑完 8 阶段,不暂停等用户 |
| `--interactive` | 每个 phase 跑完停下来等 ✅/✏/⏭️(默认) |
| `--no-clarify` | 跳过澄清 |
| `--no-analyze` | 跳过一致性分析 |
| `--no-converge` | 跳过收敛循环 |
| `--cwd <dir>` | 产物写到哪个目录(默认当前目录) |
| `--branch-numbering sequential\|timestamp` | 编号方案(目前 sequential 占位未实现) |

**示例**:

```bash
# 交互式跑 SDD(java maven 排序项目)
java -jar aethercode.jar sdd java-maven \
  "Build a java maven project, support at least 5 sorting algorithms, \
   int/short/long arrays, full unit tests"

# 一键全跑
java -jar aethercode.jar sdd photo-albums "Build a photo album app" --auto
```

### Desktop GUI

打开 Desktop → 输入 prompt → 选 SDD toggle (📐) → 发送。chip strip 显示 8 阶段,每个 phase 跑完停一次等确认。

- ✅ 接受 → 进入下一阶段
- ✏️ 修改 → 输入修改意见,Daemon 重新生成
- ⏭️ 跳过 → 仅对可选阶段生效(clarify / analyze / converge)

### 产物路径

```
<cwd>/.aethercode/sdd/<slug>/
├── constitution.md      # 阶段 1
├── spec.md             # 阶段 2
├── clarify.json        # 阶段 3 (可选)
├── design.md           # 阶段 4
├── analyze.json        # 阶段 5 (可选)
├── tasks.md            # 阶段 6
├── dev.log             # 阶段 7 — 每个 task 一段代码 patch
└── convergence.json    # 阶段 8 (可选)
```

AetherCode 内部路径约定(R294 起,**不是 Spec Kit 上游的 `.specify/specs/<NNN>-<slug>/`**):
- 目录名用 `.aethercode/sdd/`(daemon 子命令 `sdd` 的镜像)
- 设计文件叫 `design.md` 不是 `plan.md`(沿用 R236 SSD 命名)
- 实现日志叫 `dev.log` 不是 `logs/implement.log`

---

## 上游: github/spec-kit

**AetherCode SDD 直接复用** [github/spec-kit](https://github.com/github/spec-kit) 的:

1. **阶段定义** — 7 个 slash command 的拆分和命名(constitution / specify / clarify / plan / analyze / tasks / implement)
2. **Markdown 模板** — `templates/constitution-template.md`、`templates/specify-template.md`、`templates/plan-template.md`、`templates/tasks-template.md`
3. **Phase id** — kebab-case 命名(constitution / specify / plan / tasks / implement / clarify / analyze)

**AetherCode 不集成** spec-kit 本体的:

- ❌ `specify` CLI(Python)— 我们用 JVM daemon,不走外部 subprocess
- ❌ Slash command 文件(`.claude/commands/*.md`)— 我们在 daemon 内部加载模板,不走 AI agent CLI
- ❌ `setup-plan.sh` / `update-claude-md.sh` 等 helper 脚本
- ❌ Spec Kit 的 6 phase 顺序(我们是 8 phase,加了 converge loop)

**AetherCode 在 spec-kit 之上加的**:

- ✅ `converge` 第 8 阶段(post-implementation review loop,R292 起)
- ✅ `.aethercode/sdd/<slug>/` 路径布局(R294 起)
- ✅ `design.md` / `dev.log` 文件命名(R236 SSD 沿用)
- ✅ 中文 phase title(项目原则 / 需求分析 / 详细设计 / 任务分析 / 执行实现 / 需求澄清 / 一致性分析 / 收敛验证)
- ✅ Per-phase 用户确认流(R299 `--interactive` 默认)— spec-kit 上游没这个,人是自己跑 slash command

如果上游 spec-kit 更新了阶段定义或模板,流程:对照[1.2 节]的 markdown 列表,从
[github/spec-kit/templates](https://github.com/github/spec-kit/tree/main/templates)
拉新版本覆盖 jar 资源即可,daemon 代码不用改。

---

## 实现细节(简版)

涉及到的模块:

| 模块 | 角色 |
|---|---|
| `aethercode-workflows/src/main/java/.../sdd/SddConfig.java` | 8 phase 定义 + options |
| `aethercode-workflows/src/main/java/.../sdd/SddRunner.java` | 8 phase 顺序调度 + LLM 调用 + artifact 写盘 |
| `aethercode-workflows/src/main/java/.../sdd/InteractiveRepl.java` | 跟 stdin/stdout 交互(读用户指令) |
| `aethercode-cli/src/main/java/.../cli/SddCommand.java` | `aethercode sdd` CLI 注册 |
| `aethercode-workflows/src/main/resources/spec-kit/{templates,memory,hard-rules}.md` | 模板(从 spec-kit 上游嵌入) |
| `aethercode-desktop/src/components/ssd/tauriSsdDriver.ts` | Desktop → Daemon 桥(通过 stdin pipe) |
| `aethercode-desktop/src/components/ssd/driver.ts` | Wire format 定义 + MockSsdDriver(dev fallback) |
| `aethercode-desktop/src/components/SddPhaseBar.tsx` | 8 chip strip + per-phase action bar |

`SddRunner` 用的 LLM 调用是**阻塞**的(`LlmFn.generate()`),所以单次 phase 内
desktop 端只能等 phase 完成才能看到产物。R306(R293-306) 加了:

- ✅ 每阶段开始 / 完成时向 chat stream 推系统消息
- ✅ chip strip 显示实时进度 (`X/8 完成`)
- ✅ 当前 phase 蓝色脉冲
- ✅ 完成时显示总耗时 + 每 phase 时间表

**没做**的(R307+ 才能):

- ❌ LLM 流式输出(token-by-token)— 目前是 phase-draft 一次性 dump
- ❌ Spec Kit 完整集成(`specify` CLI)— 我们只用了模板

如果将来要做"流式 LLM 输出"或"完整 spec-kit 集成",从这里开始:

- `LlmFn` 改成 `LlmStreamFn`(yield token)
- `SddRunner` 改用 streaming
- 新 `phase-draft-chunk` NDJSON 事件
- `TauriSsdDriver` 转发 chunks 到 renderer
- Desktop handler: debounce 100ms 更新"drafting..."message

---

## 故障排查

### "java: command not found"

`java` 不在 PATH。spec-kit 的硬性依赖。

### SDD 一闪而过

R302 修过 setCwd 后 daemonInfo 没回填;R303 修 shell 插件 ACL;R304 修 scope 白名单。
如果还一闪,先看 `%TEMP%\aethercode-desktop-daemon-info.log` 末尾 `[R302-sdd-spawn-resolve]` /
`[R304-sdd-spawn-failed]` 行。

### 某个 phase 卡住不动

可能性:

1. LLM provider timeout(默认 30s)— 看 daemon 端 stderr
2. 用户没在 Desktop 点 ✅ — chip strip 底部有 "等待确认" 提示
3. STDIN pipe 卡住(R293 follow-up 的 5min readReply 超时会兜底)

### 产物路径不对

AetherCode 用 `.aethercode/sdd/<slug>/`(R294 起),不是上游 spec-kit 的
`.specify/specs/<NNN>-<slug>/`。如果误以为路径不对,先看 README §"产物路径"节。

---

## 参考

- [github/spec-kit 上游](https://github.com/github/spec-kit) — 阶段定义 + 模板
- [AetherCode 内部 R292 集成 round notes](../round-notes/R292-SDD-SPEC-KIT-INTEGRATION.md) — 最早集成 SDD 的设计
- [AetherCode 内部 R306 round notes](../round-notes/R306-...-real-time-status.md) — chip strip 实时状态最新进展
- [AetherCode 内部 R294 round notes](../round-notes/R294-SDD-INTERNAL-PATH-LAYOUT.md) — 路径布局决定