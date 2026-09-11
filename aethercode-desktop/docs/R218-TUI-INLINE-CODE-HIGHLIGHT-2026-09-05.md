# R218 — TUI inline code highlight (2026-09-05)

> 用户原话: "继续"（接 R217）

## 现状确认 (R217 后)

- R215 让 inline code 变成**单色 magenta pill**（filled background）
- R216/R217 加了 6 个 block lang 的 syntax highlighting（bash / json / js /
  python / java / yaml）
- **缺**：inline code `` `xxx` `` 还是单色 pill。模型输出里到处都是 inline
  code：`Use the \`git\` command` / `\`if x > 0\`` / `\`function foo()\`` /
  `Call \`npm install\` first`
- 短代码片段（identifier / keyword）应该用 token kind 着色，让 pill **内部
  也有结构**

R216 报告 R218+ 候选 #1 = "inline code 也用 tokenizer"（"价值比新加 lang 高"）。

## R218 决策

**核心挑战**：inline code 没有显式 lang（不像 code block 写 ` ```bash `）。我
们要识别 inline code 文本是 "代码"，但不知道是哪种 lang。

**方案**：**"universal inline-code tokenizer"** — 不做 lang 推断，而是识别
**跨 lang 的 code 形状**（string / number / keyword / operator），剩下字符
fall through 到 `plain`（即 identifier）。

| 形状       | 例子                    | Token kind  | 颜色              |
| ---------- | ----------------------- | ----------- | ----------------- |
| string     | `"hello"` / `'world'`   | `string`    | green             |
| number     | `42` / `0xFF`           | `number`    | yellowBright      |
| keyword    | `if` / `return` / `def` | `keyword`   | magenta           |
| operator   | `===` / `=>` / `(`      | `operator`  | white             |
| 其他       | `foo` / `myVar`         | `plain`     | (t.code fallback) |

**universal keyword union** = 50+ 高频跨 lang 关键字（控制流 + 声明），不是
150+ 全 lang 关键字。理由：
- 150+ keyword 性能差（regex 大）
- 容易**过度分类**——普通 identifier（如 `instance` / `interface` 这种）被错
  误分类为 keyword
- inline code 短，50+ 关键词覆盖 ~90% 实际用法

**pill 视觉保留** — R215 的 filled pill 风格继续保留（外层 `<Text backgroundColor>`
让 pill 是单 bg），但**内部 token 用各自颜色**。这是"one bg, many colors"
模式，Ink 支持。

**Ink API 限制** — Ink 的 `<Box>` **不支持** `backgroundColor` prop（只有
`<Text>` 支持）。R218 用 `<Text>` 作为外层 wrapper，让 pill 是连续单 bg
segment（不会断成 N 个独立 bg 块）。

## 修改清单

### 1. `highlight.ts` — 新增 `highlightInlineCode`

```ts
export function highlightInlineCode(src: string): Token[] {
  if (!src) return [];
  const keywordRe = new RegExp("\\b(?:" + INLINE_KEYWORDS.join("|") + ")\\b");
  return tokenize(src, [
    { kind: "string",   re: /"(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)*'|`(?:[^`\\\n]|\\.)*`/ },
    { kind: "number",   re: /\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b|0x[0-9a-fA-F]+\b/ },
    { kind: "keyword",  re: keywordRe },
    { kind: "operator", re: INLINE_OPERATOR_RE },  // pre-built at module load
  ]);
}
```

**`INLINE_KEYWORDS`** — 50+ 跨 lang 关键字（控制流 + 声明 + import/export +
bash 关键字 + python 关键字 + js 关键字）

**`INLINE_OPERATORS`** — 30+ 操作符，按长度 DESC 排序（多字符在前：`===` /
`!==` / `==` / `!=` / `<=` / `>=` / `&&` / `||` / `=>` / `->` 等）

**`buildInlineOperatorRe()`** — 模块加载时**预先构建一次** operator regex
（避免每次调用 rebuild）。operator 在 regex 里要 escape（`\*` / `\?` 等）。

### 2. `Markdown.tsx` — `renderInline()` `` `code` `` 分支重写

**之前 (R215)** — 单 `<Text>` 模板：
```tsx
<Text color={t.code} backgroundColor={t.codeBg} bold>
  {` ${m[10]} `}
</Text>
```

**现在 (R218)** — 外层 `<Text>` wrapper (pill bg) + 内层 token 列表：
```tsx
const tokens = highlightInlineCode(m[10]);
<Text key={...} backgroundColor={t.codeBg}>
  <Text backgroundColor={t.codeBg}> </Text>
  {tokens.map((tk, k) => (
    <Text
      key={...}
      color={(t as Record<string,string>)[tokenKindToColor(tk.kind)]}
      backgroundColor={t.codeBg}
    >
      {tk.text}
    </Text>
  ))}
  <Text backgroundColor={t.codeBg}> </Text>
</Text>
```

每个 token 都带 `backgroundColor={t.codeBg}`，**避免 token 之间出现 bg 间隙**
（Ink 渲染多 Text 时，相邻 text 的 bg 默认不连续）。外层 + 内层 + leading/
trailing space 都带 bg，pill 视觉是连续的。

### 3. R215 source-pin test 更新

R215 test 还在 grep R215 旧模板字符串 `` {` ${m[10]} `} `` 模式。R218 重构
了 inline code 渲染（不再用那个模板字符串），但保留了 pill 视觉（外层
`<Text backgroundColor>`）。**改 test 匹配新实现**，不破坏 R218。

## 渲染样例（手算）

**Before (R215)** — 整行内 inline code 都是单色 magenta pill：
```
Use the [magenta-pill:git] command. Then [magenta-pill:if x > 0] for the check.
```

**After (R218)** — pill bg 保留，内部 token 上色：
```
Use the [pill-bg: [code-color:git]] command. Then [pill-bg: [keyword:if] [white:x] [white:>] [white: ] [yellowBright:0]] for the check.
```

实际显示：
- `git` → plain (identifier, `t.code` 颜色)
- `if` → magenta (keyword)
- `x` → plain (identifier)
- `>` → white (operator)
- `0` → yellowBright (number)

`function foo() { return 42 }` 显示：
- `function` `return` → magenta (keyword)
- `foo` → plain
- `(` `)` `{` `}` → white (operator) — 注意 `{` `}` 不在 INLINE_OPERATORS！
  实际是 plain（不常见 inline code 模式）
- `42` → yellowBright (number)

## 关键 API 决策

1. **不做 lang 推断** — 启发式推断（"if `()` + identifier = js/python"）复
   杂度太高、容易错。universal 形状识别足够 90%+ 场景。
2. **universal keyword 限定 50+ 个** — 不是所有 lang 的全部 keyword 集合
   （150+）。太多会降低 regex 性能 + over-classify 普通 identifier。
3. **`(` `)` 是 operator，`{` `}` 不是** — `(` `)` 在 inline code 极常见
   （`foo()` / `(x + y)`），必须识别。`{` `}` 罕见（inline code 几乎不
   会有 block body），加上会误分类 prose（`{key: value}` 经常是 prose
   而不是 code）。
4. **operator regex 模块加载时构建一次** — `INLINE_OPERATOR_RE` 是 const，
   每次 `highlightInlineCode()` 调用复用同一 regex，不重新构建。
5. **operator 按长度 DESC 排序** — `===` 必须先于 `==` 匹配，`!=` 必须先
   于 `=` 匹配，否则 `===` 会被切成 `=` + `==` 两个 token。
6. **Ink `<Text>` 而不是 `<Box>` for pill bg** — `<Box>` 在 Ink 5.x 不接受
   `backgroundColor` prop，会 TS error。`<Text>` 是唯一支持 bg 的 wrapper。
7. **每个 inner token 也带 backgroundColor** — 避免 token 之间的 bg 间隙
   （Ink 渲染多 Text 时，相邻 text 的 bg 默认不连续）。8 个 `<Text>` 都
   带 `backgroundColor={t.codeBg}` 让 pill 是连续单 bg。
8. **R215 test 更新（不是回滚 R218）** — 旧 R215 test grep R215 模板字符
   串模式。R218 重构后那个字符串不再存在，但 pill 视觉保留。**改 test
   匹配新实现**是正确选择（不是 bug 修复 R215 test，是 R218 演化）。

## 测试

**R218 专门 source-pin + runtime 测试**: `aethercode-tui/scripts/test/r218-inline-code-highlight.test.mjs`
（**19 个 test**）

覆盖：
1. **source-pin**:
   - `highlightInlineCode` 函数存在
   - 4 patterns (string / number / keyword / operator) — 显式 pin
   - 没有显式 `plain` kind（plain 是 tokenize() fall-through，不是 explicit）
   - 跨 lang 控制流关键字 (if/else/for/while/return/try/catch)
   - 跨 lang 声明关键字 (function/class/const/let/var/def/new)
   - keyword regex word-bounded (`\b`)
   - INLINE_OPERATORS 包含 === / !== / == / != / && / || / => / ->
   - operators 按 length DESC 排序
   - Markdown.tsx import `highlightInlineCode`
   - inline code branch 调 `highlightInlineCode`
   - pill 用 `<Text backgroundColor={t.codeBg}>` (不是 `<Box>`)
   - pill 包含至少 2 个 `backgroundColor={t.codeBg}` 引用（外层 + 内层）
   - **不引入新依赖** (cli-highlight / highlight.js / shiki / prismjs / lowlight /
     micromark / starry-night / pygments / highlight / codejar / codeflask 全部
     forbidden)
   - tsc 编译干净
2. **runtime test (走 compiled .js)**:
   - `if x > 0` → keyword + plain + operator + number
   - `npm install` → 全 plain (npm / install 都不在 INLINE_KEYWORDS — by design)
   - `"hello"` → 1 string token
   - `42` → 1 number token
   - empty input → empty array
   - `function foo() { return 42 }` → keyword + operator + number (注: `{` `}` 是 plain — by design)

**全套相关测试**（回归保护）:

| 测试文件 | 内容 | 结果 |
| -------- | ---- | ---- |
| `r218-inline-code-highlight.test.mjs` | R218 新增 | 19/19 ✓ |
| `r217-more-langs.test.mjs` | R217 langs | 29/29 ✓ |
| `r216-code-highlight.test.mjs` | R216 tokenizer | 26/26 ✓ |
| `r215-markdown-pretty.test.mjs` | R215 视觉 (R218 update 1 test) | 18/18 ✓ |
| `r36-markdown.test.mjs` | R36 解析器 | 9/9 ✓ |
| `r33-pills.test.mjs` | Pills + theme tokens | 11/11 ✓ |
| `r34-toolcard.test.mjs` | ToolCard | (通过) |
| `r44-progress.test.mjs` | Progress bar | (通过) |
| `r86-thinking.test.mjs` | Thinking 渲染 | (通过) |
| `r91d-subagent.test.mjs` | Subagent 渲染 | (通过) |
| **合计** | — | **164/164 ✓** |

**tsc -p 整体编译**: 0 错 0 警告 (R218 范围)

**esbuild bundle**: 成功 `1.9MB` (R217 是 1988.7KB, R218 是 1992.1KB, +3.4KB
for highlightInlineCode + Markdown.tsx inline code 改造). `Done in 555ms`.
Shipped 到 `aethercode/dist/ac-tui/ac-tui.js`.

- 注: `commands.ts:557 case "budget"` 仍是 R44+R95-F 历史遗留 pre-existing
  warning, R218 没动 commands.ts。

## 文件改动

- **修改**:
  - `aethercode-tui/src/components/markdown/highlight.ts` (从 ~17KB 增到 ~19KB;
    +INLINE_KEYWORDS const + INLINE_OPERATORS const + buildInlineOperatorRe() +
    highlightInlineCode() 函数, dispatcher 不变)
  - `aethercode-tui/src/components/Markdown.tsx` (renderInline() `` `code` ``
    分支重写: 单 Text → Text wrapper + token list; +20 行)
  - `aethercode-tui/scripts/test/r215-markdown-pretty.test.mjs` (1 test
    update — 旧 grep 模板字符串模式改 grep `<Text backgroundColor={t.codeBg}>`)
- **新增**:
  - `aethercode-tui/scripts/test/r218-inline-code-highlight.test.mjs` (12956
    bytes, 19 tests)
- **dist rebuild**:
  - `dist/ac-tui.js` 1.9MB (+3.4KB)
  - `aethercode/dist/ac-tui/ac-tui.js` 同步

## 后续候选 (R219+)

1. **更精细 token kind** — `function` (callable identifier, 看下一个 `(` 才
   能识别)、`punctuation` (semi-colon 等)、`regex` (JS /pattern/)
2. **更智能的 inline code lang 推断** — 比如 `\`=>\`` 一定是 js/ts，
   `\`def foo():\`` 一定是 python，启发式 + per-lang tokenizer
3. **link preview** — `[text](url)` 现在只显示 text
4. **admonition** — `> [!WARNING]` 提示块
5. **修 R44+R95-F pre-existing `case "budget"` 重复**（用户没要求）
6. **更多 lang**：css / html / ruby / go / rust — 价值依次降低

## 教训 (2026-09-05)

1. **"inline code 也用 tokenizer" 比 "新加 lang" 价值大** — R216 报告预测
   对了。模型输出里 inline code 出现频率是 code block 的 10x+。R218 一次
   解决所有 inline code 的视觉，比加 css / html / ruby 三个 lang 的价值高。
2. **Ink `<Text>` vs `<Box>` 限制** — `<Box>` 不支持 `backgroundColor` prop
   （只有 `<Text>` 支持）。R218 用 `<Text>` 作为外层 wrapper 而不是 `<Box>`。
   **每个 token + leading/trailing space 都要带 backgroundColor**，避免
   bg 间隙（Ink 渲染多 Text 时默认不连续）。这个细节是 R218 调试时发现
   的，如果只用外层 + 1 个 token，会看到 bg 断裂。
3. **universal tokenizer 优于 lang 推断** — "如果 inline code 看起来像
   python 就用 python tokenizer" 的启发式推断复杂度高、易错。universal
   形状识别（string/number/keyword/operator）覆盖 90%+ 场景，复杂度低、
   容易测。
4. **keyword union 限定 50+** — 不是所有 lang 的全部 keyword 集合（150+）。
   太多会 (1) 性能下降 (regex 大); (2) over-classify 普通 identifier（如
   `instance` / `interface` 这种跟 `instanceof` / `interface` 接近的）。
   50+ 高频跨 lang 关键字是 sweet spot。
5. **`{` `}` 不在 INLINE_OPERATORS** — inline code 极少有 block body。包
   含会让 prose 被误分类（如 `{key: value}` 在叙述里是普通文本，不是
   code）。`(` `)` 必须包含（`foo()` / `(x + y)` 极常见）。
6. **operator regex 长度 DESC 排序** — `===` 必须先于 `==` 匹配，`!=`
   必须先于 `=` 匹配。R218 显式 `b.length - a.length` 排序 + 注释解释。
7. **R215 test 演化不是回滚 R218** — R215 test 旧 grep 模板字符串模式，
   R218 重构后那个字符串不存在。**改 test 匹配新实现**是正确选择（不
   是 bug 修复 R215 test，是 R218 演化）。R218 决策（"one bg, many colors"
   多 token pill）保留 R215 视觉（filled pill）+ 加 token 着色 = 升级。
8. **pre-existing warnings 不是 R218 引入的** — esbuild `commands.ts:557
   case "budget"` 仍是 R44+R95-F 历史遗留。R218 只动 Markdown.tsx +
   highlight.ts + R215 test 1 个 update。**修不修看用户, 不在 R218 scope**。

## 报告

`aethercode-desktop/docs/R218-TUI-INLINE-CODE-HIGHLIGHT-2026-09-05.md`
