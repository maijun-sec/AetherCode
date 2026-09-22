# SDD — Spec-Driven Development

> **面向**: 终端用户 / 第一次用 AetherCode 跑 SDD 的人
> **基线**: R312 (2026-09-22) — daemon SDD 流程已下线,Mavis agent 在 chat 里直接驱动 8 阶段
> **依赖**: [github/spec-kit](https://github.com/github/spec-kit) — 阶段定义 + markdown 模板来源

---

## 这是什么

**AetherCode 的 SDD (Spec-Driven Development)** 是一个 8 阶段的规格化工作流,从立项到实现到收敛全自动跑一遍。它**沿用** GitHub spec-kit 的阶段定义和 markdown 模板,由 **Mavis agent 在 chat 里直接驱动**——LLM 调用由 Mavis agent 自己发起,产物文件由 agent 用工具直接写到 `<cwd>/.aethercode/sdd/<slug>/`。

R312 之前,SDD 跑在 daemon 的独立 SDD 流程里(desktop 端通过 `java -jar aethercode.jar sdd ...` 子进程启动),用户反馈两点:

1. **"LLM 生成结果需要用户进去" 是不合理的**——SDD 阶段的 LLM 调用应该是 agent 自己触发,不是把生成内容粘贴回来让 agent 接力。
2. **"SSD 通过调用 jar 命令实现" 是过度设计**——Mavis agent 本身就有 LLM 调用 + 文件读写工具,8 phase 流程 agent 自然能跑,不需要单独的 daemon SDD 编排器。

R312 把 daemon 端的 SDD 编排(`SddRunner` / `SddConfig` / `InteractiveRepl` / `SddCommand` / spec-kit 模板资源)和 desktop 端的 SDD UI(`SddPhaseBar` / `ssd/driver.ts` / `ssd/tauriSsdDriver.ts` / 11 个 SDD 测试)全部删掉。**SDD 现在是 Mavis agent 的一个标准能力**:用户在 chat 里说"用 SDD 流程帮我生成 X spec",agent 读 spec-kit 模板、调 chat LLM、写文件、按阶段推进。

阶段拆解:

| # | 阶段 (kebab-case id) | 中文 | Spec Kit 上游模板 | 产物文件 | 必需 |
|---|---|---|---|---|---|
| 1 | `constitution` | 项目原则 | `templates/constitution-template.md` | `.aethercode/sdd/<slug>/constitution.md` | ✅ |
| 2 | `specify` | 需求分析 | `templates/specify-template.md` | `.aethercode/sdd/<slug>/spec.md` | ✅ |
| 3 | `clarify` | 需求澄清 | (agent 推理) | `.aethercode/sdd/<slug>/clarify.json` | ❌ 可选 |
| 4 | `plan` | 详细设计 | `templates/plan-template.md` | `.aethercode/sdd/<slug>/design.md` | ✅ |
| 5 | `analyze` | 一致性分析 | (agent 推理) | `.aethercode/sdd/<slug>/analyze.json` | ❌ 可选 |
| 6 | `tasks` | 任务分析 | `templates/tasks-template.md` | `.aethercode/sdd/<slug>/tasks.md` | ✅ |
| 7 | `implement` | 执行实现 | (agent 自己用工具实现) | `.aethercode/sdd/<slug>/dev.log` | ❌ 可选 |
| 8 | `converge` | 收敛验证 | (agent 自己 review) | `.aethercode/sdd/<slug>/convergence.json` | ❌ 可选 |

---

## 怎么跑

### 在 chat 里说一句话

打开 AetherCode Desktop / TUI / 任意接 Mavis agent 的界面,在 chat 里说:

```
用 SDD 流程帮我生成 java-maven 的 spec:
Build a java maven project, support at least 5 sorting algorithms,
int/short/long arrays, full unit tests
```

或者更简洁:

```
/sdd java-maven Build a java maven project with 5+ sorting algorithms
```

agent 会:

1. 创建目录 `<cwd>/.aethercode/sdd/java-maven/`
2. 读 spec-kit 的 constitution / specify / plan / tasks 模板(在 agent 的 spec-kit skill bundle 里)
3. 按 8 阶段顺序,每个阶段调 chat LLM 生成 markdown
4. 把产物写到对应路径
5. 每个阶段完成后向 chat 报告一次(简短总结 + 文件路径)
6. 用户可以在任何阶段插入修改意见,agent 重新跑该阶段

### 产物路径

```
<cwd>/.aethercode/sdd/<slug>/
├── constitution.md      # 阶段 1
├── spec.md             # 阶段 2
├── clarify.json        # 阶段 3 (可选)
├── design.md           # 阶段 4
├── analyze.json        # 阶段 5 (可选)
├── tasks.md            # 阶段 6
├── dev.log             # 阶段 7 (可选,每个 task 一段实现记录)
└── convergence.json    # 阶段 8 (可选)
```

约定(R294 起,沿用 R236 SSD 命名,**不是 Spec Kit 上游的 `.specify/specs/<NNN>-<slug>/`**):
- 目录名 `.aethercode/sdd/`
- 设计文件 `design.md`(不是 `plan.md`)
- 实现日志 `dev.log`(不是 `logs/implement.log`)
- slug 用 ≤10 ASCII 字符,kebab-case,冲突时追加 `-2`/`-3` 后缀

---

## 跟旧版 SDD 的区别

R312 之前,R292-R311 投入 16 rounds 做了一个 daemon-driven 的 SDD:

- daemon 端: `SddRunner` / `SddConfig` / `InteractiveRepl` / `SddCommand` + 5 个 spec-kit 模板 jar 资源
- desktop 端: `SddPhaseBar` + 8 chip strip + `ssd/tauriSsdDriver.ts` + 11 个测试 + wire format NDJSON 协议
- 用户在 desktop 点 SDD toggle → daemon 起子进程跑 8 phase → 每个 phase 通过 stdin pipe 等用户确认

**R312 全删了**,理由:

1. **架构错位**——Mavis agent 已经有 LLM 调用 + 工具能力,daemon 再起一个独立的 SDD 编排器是重复造轮子。
2. **强制用户介入不必要的环节**——`phase-content` wire 协议要求用户在 desktop 面板里粘贴 LLM 生成的 markdown,违背"agent 自动驱动"的基本直觉。
3. **过度复杂**——16 rounds 修了 8 层 "一闪而过" 问题(stdout listener / shell ACL / scope / setCwd 回填等),本质上是 RPC 协议层叠出来的复杂度,本来可以用 agent 直驱避免。

**R312 之后**:
- ✅ agent 在 chat 里看到 "用 SDD 流程生成 X" → 自动按 8 阶段推进
- ✅ 每个阶段产物直接写文件,用户不需要操作任何面板
- ✅ 用户可以在 chat 中途给反馈("constitution 第 3 条改成 ...", "spec 加个 edge case"),agent 重新跑对应阶段
- ❌ 没有专门的 SDD toggle、chip strip、wire 协议
- ❌ 没有 `aethercode sdd` 命令行入口

---

## 上游: github/spec-kit

**AetherCode SDD 沿用** [github/spec-kit](https://github.com/github/spec-kit) 的:

1. **阶段定义** — 7 个 slash command 的拆分和命名(constitution / specify / clarify / plan / analyze / tasks / implement)
2. **Markdown 模板** — `templates/constitution-template.md`、`templates/specify-template.md`、`templates/plan-template.md`、`templates/tasks-template.md`
3. **Phase id** — kebab-case 命名

**AetherCode 不集成** spec-kit 本体的:

- ❌ `specify` CLI(Python)— 我们用 Mavis agent 直接驱动
- ❌ Slash command 文件(`.claude/commands/*.md`)— 不走 AI agent CLI
- ❌ `setup-plan.sh` / `update-claude-md.sh` 等 helper 脚本
- ❌ Spec Kit 的 6 phase 顺序(我们是 8 phase,加了 converge loop)

**AetherCode 在 spec-kit 之上加的**:

- ✅ `converge` 第 8 阶段(post-implementation review loop)
- ✅ `.aethercode/sdd/<slug>/` 路径布局
- ✅ `design.md` / `dev.log` 文件命名
- ✅ 中文 phase title(项目原则 / 需求分析 / 详细设计 / 任务分析 / 执行实现 / 需求澄清 / 一致性分析 / 收敛验证)
- ✅ Mavis agent 在 chat 里直接驱动(不需要独立编排器)

---

## 实现细节(简版)

SDD 现在是 Mavis agent 的内置能力,涉及到的模块:

| 模块 | 角色 |
|---|---|
| Mavis agent 内置 spec-kit skill bundle | 8 phase 模板 + 阶段定义 |
| Mavis agent 工具: `read_file` / `write_file` / `chat_llm` | 读模板、写产物、调 LLM |
| 用户 chat 输入 | 触发 SDD 流程 + 中途反馈 |

**对比 R312 前的实现**:

| | R312 前(daemon-driven) | R312 后(Mavis agent-driven) |
|---|---|---|
| LLM 调用方 | daemon `SddRunner` | Mavis agent(直接调 chat LLM) |
| 模板加载 | daemon jar 资源 | agent skill bundle |
| 产物写入 | daemon `InteractiveRepl` | agent `write_file` 工具 |
| 用户交互 | desktop `SddPhaseBar` + chip strip + textarea | chat 里直接说 |
| Wire 协议 | NDJSON stdin/stdout pipe | 不需要 |
| 进程模型 | `java -jar ... sdd ...` 子进程 | 无,chat 里直接走 |
| 复杂度 | 16 rounds × 8 层修复 | 1 个 skill bundle |

---

## 故障排查

### "agent 没有跑 SDD"

确保 chat 输入里明确提到 "SDD" 或 "用 spec-kit 流程"。Mavis agent 看到关键词会触发 SDD skill;否则会按普通 chat 处理。

### "某个 phase 产物不对"

直接在 chat 里说:

```
spec.md 第 3 节 "性能需求" 改一下,目标应该是 < 100ms 不是 < 1s
```

agent 会:
1. 读现有的 `<cwd>/.aethercode/sdd/<slug>/spec.md`
2. 调 LLM 修改第 3 节
3. 把更新后的内容写回文件

### "我想跳过可选阶段(clarify / analyze / converge)"

chat 里说:

```
跑 SDD 但跳过 clarify 和 converge
```

agent 会按 6 阶段跑(必需 4 + analyze 共 6)。

### 产物路径不对

AetherCode 用 `.aethercode/sdd/<slug>/`(R294 起),不是上游 spec-kit 的
`.specify/specs/<NNN>-<slug>/`。如果误以为路径不对,先看 README §"产物路径"节。

---

## 参考

- [github/spec-kit 上游](https://github.com/github/spec-kit) — 阶段定义 + 模板
- [AetherCode 内部 R312 round notes](../round-notes/R312-SDD-AGENT-DRIVEN.md) — 干掉 daemon SDD 流程,改由 Mavis agent 驱动
- [AetherCode 内部 R294 round notes](../round-notes/R294-SDD-INTERNAL-PATH-LAYOUT.md) — 路径布局决定
- [AetherCode 内部 R236 SSD round notes](../round-notes/R236-SSD-NAMING.md) — `design.md` / `dev.log` 命名沿用