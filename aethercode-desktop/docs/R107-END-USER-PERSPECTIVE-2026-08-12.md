# R107: 一个普通用户的吐槽

> **视角**: 我不是 AetherCode 重度用户。我用过 ChatGPT / Claude 网页版、Cursor、Bolt、Devin、Lovable，但我**没用过 AetherCode**，只看过介绍页和 R 系列的更新日志。我决定下来试一下。
>
> **写这份的目的**: 不是给实现建议，是把一个"第一眼装上 30 秒就决定留不留"的人脑子里的话原样说出来。
>
> **吐槽的 4 块**: skill 加载 / workflow / agent team / 页面展示。

---

## A. 第一眼体验

### 我打开 AetherCode,看到的第一帧

一个深色主题的桌面 app,顶上窄窄一条 header 写着 `✦ AetherCode · <我的 query 前 60 字> · running`,右上角一颗绿色的 `● Idle`、一个 `claude-opus-4-1` 模型名、一个齿轮。

中间最大一块是欢迎页, 就两行字:

> ✦ AetherCode
> Local desktop UI for the multi-protocol daemon.
> Type a message below to start. Ctrl+Enter to send, Esc to cancel. Adjust model & permission in the bar above the input.

下面是一行 `Tools` `Workflow` `Model` `Perm` `📂 cwd` 五个小按钮,再下面是一个输入框。

**左边一栏**堆了三块:
- `TaskSummary` (我没创建过 task, 显示"0 running / 0 done / 0 failed")
- `Sessions` (空的:"No sessions yet — type below to start", 下面一个 `+ 新会话` 虚线按钮)
- `Tasks` 列表(同样是 "No tasks") + `Projects` 列表(空)

**右边一栏**直接把我吓到:
- 一条 `Progress` 进度条 (没在跑就空)
- `Context` 仪表(0 / 200k tokens)
- `Tokens` (用 0)
- `Traces` (空)
- `Permissions` (空)
- `Memory` (空)
- `Prompts` 历史(空)

最底下还有一个 `StatusBar`,但我没看清楚显示了什么。

### 我能用 30 秒判断出这能干嘛吗?

**不能。**

我打开 30 秒,脑子里闪过的判断是:

- "这是 IDE 还是 chat?" —— 左边像 IDE 的 sidebar,中间像 ChatGPT,右边像 devtools
- "为什么右边一整列都是空的 panel?" —— 看起来像 给我 开了个 Pro 账号但什么都没用上
- "为什么我需要一个 daemon 跑在我本地?" —— 我又不是开发者,我只是想跟 AI 聊个天
- "我能用它做什么" —— 看完一整圈,我能确定的事只有"我能发个 query"
- "skill 在哪?workflow 在哪?agent 在哪?" —— 看不到任何入口

**情绪曲线**: 好奇(第一秒) → 困惑(5 秒) → 怀疑(15 秒) → "先试试打字吧"(30 秒)

### 现状让我做什么决定

**留下,但不是放心地留下,是"先玩玩看"。**

我没有立刻卸载,因为我看到 `claude-opus-4-1` 这个名字 + `Ctrl+Enter` 这种键盘提示,感觉"这背后接的是真的 Claude,那我先发个东西看看"。

但我**没有**在第一天就把它设成默认,也没有装任何 skill、碰任何 workflow,也没有试多 agent。我只是把它当一个"另一个 ChatGPT 客户端"用了一晚上。

如果我装的不是现在这版,而是一个没有任何引导的版本,我大概率会卸载。原因很简单:**没人告诉我这个 app 的"杀手锏"是什么。**

ChatGPT 第一屏告诉你"问任何事"。Cursor 装完就帮你开一个项目、装好 LSP。Lovable 直接给你一个 "Describe your app" 输入框。Devin 告诉你 "Plan and execute"。Bolt.new 一进去就是 "prompt → 预览"。

AetherCode 第一屏只告诉我 "Type a message below"。

---

## B. 我会用的功能 top 3

按使用频率从高到低:

### 1. Chat (输入框 + 发送)
占我用这个 app 90% 的时间。这是最朴素的功能,但也是最核心的。

我**不会**在这里:
- 选模型 (默认 opus 4-1 就行,我要的是 "用 AI", 不是 "用 AI 3.5 vs 4.1 的差别")
- 改 Permission 模式 (什么叫 `bypassPermissions`? 我不是开发者)
- 改 Tools (什么叫 "tools"? 那些是给我的还是给 AI 的?)

**我会做的**: 打字, Ctrl+Enter, 等, 看回复, 接着打字。

### 2. Session 切换 (左侧 sidebar)
这个我**会**用, 但前提是我得先理解它在干嘛。第二次开 app 我才看懂: "哦, 不同的 session = 不同的对话 context, 跟 ChatGPT 的 'Projects' 类似"。

触发方式:
- 我会看左侧 `+ 新会话` 按钮,点
- 我**可能**会学会 Ctrl+K (因为我用过 VSCode)
- 我**不会**用 Ctrl+1-9 (太 power user 了)

### 3. 看模型实际在干什么 (但只是瞥一眼)
发完 query 后,我会本能地往**右边**看。但右边那一堆 panel 让我**信息过载**:
- Progress bar 出现了!但我不理解 `3/15` 是什么意思
- Context meter 在动? 不关心
- Tokens 在涨? 不关心
- Traces 在跳? 不关心
- Permission list? 啥?

我**只会**看三样:
- 顶上 `● Streaming…` 那个点(我知道它在工作)
- 中间输入框上方的 `ActivityIndicator`(我大致知道它在哪一步)
- 主对话区里模型一字一字流出来

右边那一整列对我来说**噪音多于信号**。我会本能地想去折叠/关闭它。

### 我装了之后根本不会碰的功能

老实列一个清单,这些是我装了 AetherCode 三个月后可能还是 0 次打开的东西:

| 功能 | 为什么我不用 |
|------|-------------|
| `@`-mention 文件补全 | 我不在 AetherCode 里写代码,我贴路径用 Ctrl+V |
| Workflow 编辑器 (YAML) | **看都不想看**, 跟"给我这个非工程师"没关系 |
| `/workflow` 系列 slash 命令 | 工程师用的 |
| `/skill add` (要我去终端 copy-paste `git clone`) | **绝对不干**, 我不会开终端 |
| Tools 多选 (开了/关了一些) | 不知道它们是干嘛的,默认全开算了 |
| Permission mode 切换 | 三个词我看不懂,默认的 `default` 用着没出问题就不动 |
| Memory Panel | 不知道干嘛的,ChatGPT 都不让我管 memory |
| PromptHistory (藏在最底下) | 真的会用,但藏在最底下,基本看不到 |
| TraceList | devtools 那种东西 |
| ContextMeter / TokenUsage | 工程师才看,我只是聊天 |
| Tasks 列表 (含 SubTask) | 跟 ChatGPT 一样,我只要最终回答 |
| LoopGuardBanner | 99% 时间不触发,触发了也看不懂 |
| AwaitingDecisionBanner | 同上 |
| StaleWarning (3 色分级) | "30s 没动静? 我干别的去了" |
| Session 的 `×` 删除按钮 | 我不会主动删,ChatGPT 都不让删 |

---

## C. 卡点 (3 个具体场景)

### 卡点 1: "我装了一个 skill 之后, 怎么用?"

**场景**: 同事跟我说 "你装个 `code-review` skill, 让你 review PR 的时候用"。

**我的心路历程**:
1. 打开 AetherCode
2. 找 "skill 商店" / "skill 管理" 入口 —— 找不到
3. 翻 Settings panel —— 只有 Model 和 Permission 两个字段
4. 翻 Header / LeftPanel / RightPanel —— 没有 "skill" 这个 tab
5. 试 `/help` —— 看到 `/skill add` 这个命令
6. 输入 `/skill add code-review` —— 弹出来一个**让我去终端 copy-paste 跑的 bash 命令**
7. **关掉 app, 骂一句, 打开 Cursor**

**问题不是技术**,问题是:
- **没看到"skill 在哪"**
- **没看到"装上之后这个 skill 怎么用"**
- **运行的时候看不到"AI 正在调用 skill X"** —— 跟普通 query 长得一样
- **不知道装得对不对** —— 没"installed 17 skills" 这种总览
- **没有"删除/重装"按钮** —— 搞砸了不知道怎么恢复

**对标**:
- ChatGPT 的 GPTs: chat 旁边有 plugin 图标,点击看描述,选了就用
- Claude 的 Projects: 进入 project 之后,system prompt + 文件夹一目了然
- Cursor 的 @Docs: 输入框 `@docs` 直接出列表

**AetherCode 的现状**给我感觉是: **"skill 是给开发者的隐藏功能"**。我作为普通用户感觉是被排除在外的。

### 卡点 2: "我发了个 query, 5 秒没反应我就想关掉了"

**场景**: 我问了一个稍微复杂的问题, 比如 "帮我把这周会议纪要里的 action items 整理出来"。

**我的心路历程**:
1. 打字, Ctrl+Enter
2. 看到顶上变 `● Streaming…` —— 知道在工作
3. 看到输入框上方出现 `ActivityIndicator` —— 但我不懂它在闪什么
4. **等 3 秒没第一个字** → 想:"是不是挂了"
5. **等 5 秒没第一个字** → 想:"我关掉重来"
6. **等 8 秒, 第一个字终于出来了** → 松一口气
7. **但之后又卡 10 秒** (模型在调工具) → 又想关

**根本原因**:
- 我**不知道**模型在做什么 —— 是在思考? 是在查文件? 是在调网络?
- 那个 `ProgressBar` 1/15 我看不懂 —— 1 是什么?15 是什么?为什么才 1?
- 那个 `StaleWarning` 30 秒后才出来 —— 等它出来我已经想关了
- **没有 "我正在调用 X 工具" 这种人类语言的反馈** —— Cursor 会写"Reading file.py",Devin 会说"Searching the web"

**情绪**: 焦虑 → 怀疑 → 烦躁 → "算了去刷手机"

### 卡点 3: "我想切换到另一个工作, 找不到 session 在哪"

**场景**: 我刚才在 session A 帮我老板起草合同条款, 现在想切到 session B 问 AI 一个 coding 问题。

**我的心路历程 (第一次)**:
1. 想关掉 A 开始新的 —— 看到右侧 History / 左侧 SessionList 里 "Session <id8>" 这种名字
2. **不知道点哪个是"新建"** —— 看到 `+ 新会话` 但虚线框,我以为是 placeholder
3. **更不知道 Ctrl+K 是干嘛的** —— 我又没读过 changelog
4. 试了试直接打字 → 看到消息进了当前 session,对话变长 → "这不是我要的"
5. **关了重开 app** (想"重开就是新对话"?) → 看到对话还在

**第二次 (我已经学聪明了)**:
- 我知道要点 `+ 新会话`
- 我知道 Ctrl+K 调出 palette
- 但**新会话没有名字** —— 都是 "Session a3f4b8c2" 这种 ID 尾巴
- 我**不知道哪个 session 是干嘛的** —— 只能靠时间判断

**对比 ChatGPT**: ChatGPT 左边直接是 "今天的对话 / 昨天的对话 / 上周的对话", 一目了然
**对比 Claude**: Claude 左侧是项目名 + 项目内的对话,自动按第一句话起标题

**AetherCode 现状**: 工程师友好的 ID 风格,普通用户一脸懵

---

## D. 我会推荐吗?

### 会的场景

我会跟**懂技术的同事**推荐, 在这些场景下:
- "你想本地跑 Claude / GPT, 不把代码传给 OpenAI 服务器" → 隐私场景
- "你想把每周重复的工程任务写成 pipeline" → 自动化场景(但**前提是你愿意写 YAML**)
- "你想让 AI 干一连串事,每个事都有独立 context" → 多 session 场景
- "你写了一个 skill 想给自己用" → 内部工具场景

### 不会推荐的场景

- 给**产品 / 运营 / 市场同事** —— 他们不会用 YAML,也不想去 terminal `git clone`
- 给**老板** —— 老板装上之后会问"这个跟 ChatGPT 比有什么不一样", 我答不上来
- 给**学生 / 家长** —— 完全没有引导,他们装完就懵
- 给**设计师** —— 这不是一个创意工具

### 跟 ChatGPT / Cursor 比,我推荐哪个

| 场景 | 我会推荐 | 理由 |
|------|---------|------|
| "我想问个问题, 写封邮件" | **ChatGPT** | 装上就用, 我不需要 local daemon |
| "我天天写代码, 要 AI 帮我补全" | **Cursor** | 真在 IDE 里, 不是聊天框 |
| "我担心代码隐私, 必须本地跑" | **AetherCode** | 唯一选项 |
| "我想让 AI 干一连串事 (搜资料 + 整理 + 写文档)" | **AetherCode** | workflow 这事 ChatGPT 没做好, AetherCode 至少是认真的 |
| "我想让 AI 团队帮我做完整项目" | **AetherCode** (勉强) 或 **Devin** | AetherCode 的多 agent 还很早期, Devin 已经能交付 app |
| "我想做个原型 app" | **Bolt / Lovable** | 那是另外一类工具, AetherCode 没打算做这个 |
| "我手机想用" | **ChatGPT** (有 app) | AetherCode 只有桌面 |

**一句话**: AetherCode 在 "本地 + 多 session + workflow + 多 agent" 这条线是认真的,但**对普通用户的友好度远远落后于 ChatGPT**。如果你不是工程师,大概率你装了三天后就回到 ChatGPT 网页版。

---

## E. 一句话总结

**我作为普通用户, 对 AetherCode 的最大期待是:**

> **"让我不用学 YAML、不用记快捷键、不用懂 daemon、不用开 terminal, 就能让 AI 帮我跑一连串事;而且每一步它在干什么, 我都看得懂。"**

具体拆开:

1. **第一眼告诉我能干嘛** —— 不要让我"打字试试", 要给我"试试这个 / 看看这个 / 装个这个"
2. **skill 像 ChatGPT 的 plugin** —— 在输入框旁边能看见, 点开有描述, 用 / 不用我说了算, 不用了能卸载
3. **workflow 像表单, 不像代码** —— "选模板 → 改几个字段 → 跑", 不让我碰 YAML
4. **多 agent 像团队** —— 谁在干嘛 / 干完了 / 卡住了, 我看得一清二楚, 我能叫停 / 改派 / 加人
5. **页面像 ChatGPT, 不像 VSCode** —— 右边那一整列 devtools panel 我能折叠, 别抢我的注意力
6. **每一步它都在干嘛, 我看得懂** —— "正在读你的邮件" 不是 "1/15"

做到这六点, 我会主动推荐给非工程师朋友。
做不到, 我只能推荐给愿意写 YAML 的同事。

---

## 附录: 普通用户不会说的话 vs 工程师会说的话

写这份的时候,我突然意识到,这份吐槽里我**几乎没用过**以下词:

- daemon / RPC / WS / JSONL / transcript / rebase / executor / SkillInvoker
- `kebab-case` / `path-scope` / `--sessions-dir`
- `subtask` / `stepCount` / `toolCount`
- `Jinja-subset` / `recursive-descent parser`
- `localStorage` / `volatile` / `reconciliation`

而 R 系列文档里这些词高频出现。

**这就是"普通用户"和"开发者"之间的距离**。AetherCode 现在所有的好,都藏在这些词背后;普通用户要的不是这些词,普通用户要的是**"AI 在干我想干的事, 我不用懂它怎么干"**。

这不是批评, 只是视角。
