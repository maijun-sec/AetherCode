# R219 — TUI inline-code 语言推断 (2026-09-05)

> 用户原话: "继续吧"（接 R218）

## 现状确认 (R218 后)

R218 给 inline code 加了 **universal tokenizer** — 50+ 跨 lang keyword union +
string / number / operator。**universal 覆盖 90%+ 场景**，但有些 case 可以更精准：

- `` `def foo():` `` → R218 universal 识别 `def` 是 keyword（OK），但 `foo` / `:` / `()`
  都是 plain。Python tokenizer 能识别 `self` / `cls` / `Exception` 等 30+ Python builtin
- `` `class Foo {` `` → universal 不知道 `class` 是 Java declaration（vs Python
  `class Foo:`）。Java tokenizer 能识别 `String` / `List` / `Map` 等 23 个 Java
  runtime builtin
- `` `=>` `` → universal 不知道 `=>` 是 JS arrow function（operator）
- `` `if [ $foo -gt 0 ]` `` → universal 把 `$foo` 当 plain，但 bash tokenizer 把它
  当 builtin (variable expansion)

R218 报告 R219+ 候选 #1 = "更智能的 inline code lang 推断"。

## R219 决策

**两阶段 dispatch**：
1. **`inferInlineLang(src)`** — 启发式规则集，识别 lang
2. **命中** → 调 per-lang `highlightCode(src, lang)` (R216/R217)
3. **未命中** → fall through 到 R218 universal tokenizer

**保守推断**：宁可 miss，不要 mis-classify。`hello world`（普通文本）不应该
被错误识别为任何 lang。

**规则按特异性排序**（最强信号在前）：

| 优先级 | Lang | 关键 signals |
|--------|------|--------------|
| 1 | **Java** | `public/private/protected` + `void/int/String/...`; `class Foo {`（brace）; `new Foo(` |
| 2 | **Python** | `def name(`; `class Foo:`（colon）; `@decorator`; `self`; `import x` 无 `{};` |
| 3 | **JavaScript/TS** | `=>`; `function name(`; `const|let|var name =`; `require(`; `import x from "..."` |
| 4 | **Bash** | `$VAR` / `${VAR}`; `[ $foo ... ]`; leading `$`; 常见命令 (echo/cd/ls/grep/...) |
| 5 | **JSON** | `{ "key":` / `{ 'key':` |
| 6 | **YAML** | `key: value` (行首) |
| 7 | **"" (unknown)** | fall through 到 R218 universal |

## 修改清单

### 1. `highlight.ts` — 新增 `inferInlineLang`

**~80 行**启发式实现：
- 6 个 lang 段（java / python / javascript / bash / json / yaml）
- 每段 1-5 条强信号规则
- 顺序敏感性：必须 Java 在 Python 前（因为 `class Foo {` 是 Java；`class Foo:`
  是 Python，但 brace vs colon 是区分的；不过 rule ordering 还是 first-match-wins）
- `""` return 表示 fall through

**为什么保守**：启发式推断宁可漏判不要错判。例如 `import sys` 在 Python 是 `import x`，
但 `import os from "node:os"` 是 JS/TS。我加了 `{` `;` check 区分，但仍然要小心。

### 2. `highlightInlineCode` 改造

```ts
export function highlightInlineCode(src: string): Token[] {
  if (!src) return [];
  // R219: try to infer the language and dispatch to the
  // per-lang tokenizer. This gives more accurate token
  // classification than the universal fallback.
  const lang = inferInlineLang(src);
  if (lang) {
    return highlightCode(src, lang);
  }
  // R218: universal fallback. The keyword regex is built
  // per-call (cheap; ~50 keywords).
  const keywordRe = new RegExp("\\b(?:" + INLINE_KEYWORDS.join("|") + ")\\b");
  return tokenize(src, [
    { kind: "string", re: ... },
    { kind: "number", re: ... },
    { kind: "keyword", re: keywordRe },
    { kind: "operator", re: INLINE_OPERATOR_RE },
  ]);
}
```

**关键**：R218 路径**完全保留**。R219 只在 `inferInlineLang` 命中时升级到
per-lang tokenizer；未命中 → 走 R218。

### 3. R218 test 1 个更新

R218 runtime test 用了 `function foo() { return 42 }` 期望有 operator（`(` `)`
是 R218 universal 的 operator）。R219 走 javascript 路径后 `(` `)` 不是
operator（JS tokenizer operator regex 没这两个单字符）。

**改 R218 test** 用 `x === 42` — `===` 是 operator，**在 R218 universal 路径
和 R219 javascript 路径下都是 operator**。这样 test 仍然有意义（operator
出现），但不耦合到 R218 旧行为。

## 实际效果对比

| Inline code | R218 (universal) | R219 (per-lang) |
|-------------|------------------|------------------|
| `` `def foo():` `` | `def` keyword + `foo() ` plain + `:` plain | `def` keyword + `foo` plain + `()` operator + `:` plain（python 路径下）|
| `` `class Foo {` `` | `class` keyword (50+ union) + `Foo ` plain + `{` plain | `class` keyword + `Foo` plain + `{` operator (java 路径下) |
| `` `const x = arr => arr.length` `` | `const` keyword + ` x ` plain + `= ` plain + `arr ` plain + `=>` operator + ` arr` plain + `.` plain + `length` plain | `const` keyword + `x` plain + `=` operator + `arr` plain + `=>` operator + `arr` plain + `.` operator + `length` plain（js 路径下 operator 集合更窄但更精确）|
| `` `if [ $foo -gt 0 ]` `` | `if` keyword + ` [` plain + `$foo` plain + ` -gt ` plain + `0` number + `]` plain | `if` keyword + `[` operator + `$foo` **builtin** + `-gt` operator + `0` number + `]` operator（bash 路径下 `$foo` 被识别为 variable expansion）|
| `` `hello world` `` | 全 plain | 全 plain（universal fallback）|
| `` `x === 42` `` | `x` plain + `===` operator + `42` number | 同上（universal fallback，因为没有 `const|def|class` 等强信号）|

## 关键 API 决策

1. **保守推断** — 宁可 miss 不要 mis-classify。启发式规则的 false positive 成本
   比 false negative 高（错的颜色比默认 plain 更糟糕）。
2. **Java 在 Python 前** — `class Foo {` 是 Java，`class Foo:` 是 Python。
   `{}` vs `:` 是区分的（brace = Java，colon = Python）。但 rule ordering 仍
   然重要，因为 Java 也有 `class Foo extends Bar` 等 patterns。
3. **JavaScript 在 Bash 前** — `const` / `let` / `var` 是 JS 强信号，
   `${VAR}` 是 bash 强信号。如果都出现（如 `const x = $foo`），先 JS 后 bash。
4. **R218 路径完全保留** — 改动是**增量**，不是重写。R218 24 行 universal
   路径代码原封不动。
5. **per-call keyword regex 重建** — R218 universal 路径里 `keywordRe` 每次
   调用 rebuild（~50 关键词，廉价），不用 const 缓存。per-lang 路径直接调
   `highlightCode(src, lang)`，那里有完整 tokenizer。
6. **R218 test 1 个更新** — `function foo()` 期望 operator 是 R218 旧行为。
   R219 走 js 路径后 `(` `)` 不是 operator。改 test 用 `x === 42` 让 test 仍
   然有意义（operator 出现），但不耦合到 R218 旧行为。

## 测试

**R219 专门 source-pin + runtime 测试**: `aethercode-tui/scripts/test/r219-inline-lang-infer.test.mjs`
（**34 个 test**）

覆盖：
1. **source-pin**:
   - `inferInlineLang` 函数存在
   - `if (!src) return ""` guard
   - 6 个 lang 段（java / python / js / bash / json / yaml）的强信号 regex
   - 规则顺序：Java 在 Python 前，Python 在 JS 前，JS 在 Bash 前
   - `highlightInlineCode` 调用 `inferInlineLang` 然后 `highlightCode(src, lang)`
   - R218 universal fallback 保留（`tokenize(src, [...])` 仍在）
   - **0 新依赖** (cli-highlight / highlight.js / shiki / prismjs / lowlight /
     micromark / starry-night / pygments / highlight / codejar / codeflask /
     refractor 全部 forbidden)
   - Markdown.tsx 不变（highlightInlineCode 仍然 import + 调用）
   - tsc 编译干净
2. **runtime test (走 compiled .js)**:
   - `def foo(x: int) -> str: return str(x)` → 推断 python → `def`/`return`
     keyword + `str` builtin + `->` operator（**仅 python tokenizer 有 `str` builtin**）
   - `class Foo { void run() {} }` → 推断 java → `class`/`void` keyword
   - `const x = arr => arr.map(n => n * 2)` → 推断 javascript → `const` keyword
   - `if [ $foo -gt 0 ]; then` → 推断 bash → `if`/`then` keyword + `$foo` builtin
     + `[`/`-gt`/`;`/`]` operator
   - `{"name": "alice"}` → 推断 json
   - `name: alice\nage: 30` → 推断 yaml
   - `x === 42` → 不确定 lang，但 operator + number 一定出现（R218 universal 兜底）
   - `hello world` → 推断 "" → universal fallback
   - `git status` → 推断 ""（git 不在 bash 命令 list）→ universal fallback

**全套相关测试**（回归保护）:

| 测试文件 | 内容 | 结果 |
| -------- | ---- | ---- |
| `r219-inline-lang-infer.test.mjs` | R219 新增 | 34/34 ✓ |
| `r218-inline-code-highlight.test.mjs` | R218 inline code (1 test update) | 19/19 ✓ |
| `r217-more-langs.test.mjs` | R217 langs | 29/29 ✓ |
| `r216-code-highlight.test.mjs` | R216 tokenizer | 26/26 ✓ |
| `r215-markdown-pretty.test.mjs` | R215 视觉 | 18/18 ✓ |
| `r36-markdown.test.mjs` | R36 解析器 | 9/9 ✓ |
| `r33-pills.test.mjs` | Pills + theme tokens | 11/11 ✓ |
| `r34-toolcard.test.mjs` | ToolCard | (通过) |
| `r44-progress.test.mjs` | Progress bar | (通过) |
| `r86-thinking.test.mjs` | Thinking 渲染 | (通过) |
| `r91d-subagent.test.mjs` | Subagent 渲染 | (通过) |
| **合计** | — | **198/198 ✓** |

**tsc -p 整体编译**: 0 错 0 警告 (R219 范围)

**esbuild bundle**: 成功 `1.9MB` (R218 是 1992.1KB, R219 是 1993.5KB, +1.4KB
for inferInlineLang + 2-stage dispatch). `Done in 1321ms`. Shipped 到
`aethercode/dist/ac-tui/ac-tui.js`.

- 注: `commands.ts:557 case "budget"` 仍是 R44+R95-F 历史遗留 pre-existing
  warning, R219 没动 commands.ts。

## 文件改动

- **修改**:
  - `aethercode-tui/src/components/markdown/highlight.ts` (从 ~19KB 增到 ~21KB;
    +inferInlineLang (~80 行) + highlightInlineCode 改 2-stage dispatch (R218
    universal 路径保留))
  - `aethercode-tui/scripts/test/r218-inline-code-highlight.test.mjs` (1 test
    update — `function foo() { return 42 }` 改 `x === 42`)
- **新增**:
  - `aethercode-tui/scripts/test/r219-inline-lang-infer.test.mjs` (17729 bytes, 34 tests)
- **dist rebuild**:
  - `dist/ac-tui.js` 1.9MB (+1.4KB)
  - `aethercode/dist/ac-tui/ac-tui.js` 同步

## 后续候选 (R220+)

1. **更精细 token kind** — `function` (callable identifier)、`punctuation`、
   `regex` (JS `/pattern/`)
2. **link preview** — `[text](url)` 现在只显示 text
3. **admonition** — `> [!WARNING]` 提示块
4. **修 R44+R95-F pre-existing `case "budget"` 重复**（用户没要求）
5. **更多 lang** — css / html / ruby / go / rust — 价值依次降低
6. **更激进 lang 推断** — 上下文 + 文档（"the class" 后面跟 brace = Java,
   "the class" 后面跟 colon = Python）。但成本/价值待评估。
7. **per-line 推断** — multi-line inline code (不常见) 不支持，R219 是
   single-line 推断。

## 教训 (2026-09-05)

1. **"两阶段 dispatch" 是 generic 模式** — R219 模式：先 cheap 规则筛
   candidate（heuristic），命中再调 expensive 但更精准的路径
   （per-lang tokenizer），未命中 fall through 到 default。
   这个模式比"一种方法走到底"更鲁棒——precision 和 recall 都保。
2. **保守推断 > 激进推断** — 启发式 false positive 成本（错的颜色）比
   false negative 高（默认 plain）。宁可 miss 不要 mis-classify。R219 的
   6 个 lang 段都是 high-signal 规则，没有"看起来像 python 就 python"
   的模糊判断。
3. **rule ordering 是 first-match-wins 关键** — 6 个 lang 段顺序重要。
   Java 在 Python 前（`class Foo` 容易被错识），JS 在 Bash 前（`const`
   比 `$VAR` 更特定）。R219 test 显式 pin 这个 ordering。
4. **保留 fallback 路径 = 不会破旧 test** — R219 改动是**增量**，R218
   universal 路径完全保留。R218 test 1 个更新是因为 R219 走了新路径
   （不是 R218 行为变化），R218 test 也跟着改 input 而不是回滚 R219。
5. **R218 test 更新不是 R219 失败** — R218 test 旧用 `function foo()`
   期望 operator。R219 走 js 路径后 `(` `)` 不是 operator。这不是 R219
   引入的 regression — R219 改进的是**准确性**（JS tokenizer 的 operator
   集合就是更窄）。改 test 是合理的演化，不是 bug 修复。
6. **`hello world` 不应该被推断** — R219 没 match 任何 rule，返回 `""`，
   走 R218 universal。这保留了"模型输出里的普通文字不会被错误染上
   代码色"的 sanity 行为。
7. **pre-existing warnings 不是 R219 引入的** — esbuild `commands.ts:557
   case "budget"` 仍是 R44+R95-F 历史遗留。R219 只动 highlight.ts
   (1 个文件 + 测试 + 报告) + R218 test 1 个 update。**修不修看用户, 不
   在 R219 scope**。

## 报告

`aethercode-desktop/docs/R219-TUI-INLINE-LANG-INFER-2026-09-05.md`
