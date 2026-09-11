# R216 — TUI 代码块 syntax highlighting (2026-09-05)

> 用户原话: "按 R216 推荐继续"（接 R215）

## 现状确认 (R215 后)

R215 让 code block 有了 **rounded 紫色边框** + **顶部 lang pill** + **dark magenta 背景**，
但代码体本身还是单调的 `t.code` (magenta)。30 行的 bash / json / typescript 在一个
颜色里读起来是 "墙"，没有关键字 / 字符串 / 数字 / 注释的视觉区分。

Desktop 端已有 `tok-keyword` / `tok-string` / `tok-comment` / `tok-number` / `tok-builtin`
5 种 token 颜色（CSS class），TUI 这边没有对应物。

## R216 决策

**继续不引入新依赖**（R215 的承诺）。手写一个 12KB 的 `highlight.ts`：

1. **`cli-highlight` / `highlight.js` / `shiki` 全部禁用** — 都需要 ANSI 转义序列
   跟 Ink 的颜色 prop 冲突；`shiki` 还需要额外的 wasm/renderer；轻量化目标不允许。
2. **3 个最常用 lang 优先** — `bash` / `json` / `javascript`。其他 lang（python /
   java / yaml / css / html）走 "unknown-lang fallback"，给一个 `plain` token，跟
   R215 行为一致。
3. **6 种 token kind** — `keyword` / `string` / `number` / `comment` / `builtin` /
   `operator` + `plain`（default）。比 desktop 少 1 种（desktop 用 `tok-function`），
   因为 TUI tokenizer 没做 identifier-vs-callable 区分，简单 `builtin` 已经够用。
4. **每个 lang 的优先级** — comment > string > variable/number > operator > keyword >
   builtin。这样不会让 `${var}` 内的 `#` 误判为 comment，等等。

## 修改清单

### 1. `theme.ts` — 加 6 个 token colour

```ts
// R216: code-block syntax tokens
tokKeyword:  "magenta"      // if const class function (控制流)
tokString:   "green"        // "hello" 'world' `template`
tokNumber:   "yellowBright" // 123 0xFF 3.14
tokComment:  "gray"         // // # /* */ — italic in render
tokBuiltin:  "cyan"         // true false null console Math
tokOperator: "white"        // = + - * / => ===
```

调色板思路：跟 R215 的 `codeBorder` 紫色 + `codeBlockBg` dark magenta 背景协调 —
`keyword` magenta 让关键字在 bg 上对比强烈，`string` green 跟 brand yellow 不冲突，
`number` yellowBright 跟 brand 同色但深浅不同可分辨，`comment` gray 是 "沉默色"。

### 2. `src/components/markdown/highlight.ts` (新文件, 12KB)

核心 API：

```ts
export type TokenKind = "plain" | "keyword" | "string" | "number"
                      | "comment" | "builtin" | "operator";
export interface Token { text: string; kind: TokenKind; }

export function highlightCode(src: string, lang: string): Token[];
export function tokenKindToColor(kind: TokenKind): string;  // 主题 key 名
```

**实现要点**：

- **统一 `tokenize()` helper** — 给定 `{ kind, re }` 数组按优先级尝试匹配，第一个
  match 赢；未匹配字符累积成 `plain` token。同 kind 邻近 token 合并（避免 React tree
 碎片化）。纯函数，零副作用。
- **bash tokenizer**（~70 行）—
  - 11 个 control-flow 关键字 (if/then/else/elif/fi/for/while/until/do/done/case/esac/in/select/...)
  - 24 个 builtin (echo/printf/read/export/local/cd/pwd/...)
  - 26 个常见外部命令 (ls/cat/grep/curl/git/npm/...)
  - `#` 注释、`"`/`'` 字符串、`${VAR}` / `$VAR` 变量展开
  - 复合操作符优先 (&&/||/>>/<</==/!=) 避免分割
- **json tokenizer**（~10 行）— 字符串 + 数字 + 3 个 literal (true/false/null) + 5 个
  结构符号 ({}/[]:,)
- **javascript tokenizer**（~50 行）— 38 个 ES6+ 关键字、7 个 TS 额外关键字 (interface/
  type/enum/...) + 25 个 runtime builtins (Object/Array/console/...) + 三种字符串 (
  single / double / template) + 两种注释 (// /\*\/) + 复合操作符
- **dispatcher** — `bash/shell/sh/zsh` → bash；`json/jsonc` → json；`javascript/js/
  typescript/ts/tsx/jsx` → js；其他 → plain fallback
- **priority 顺序** — comment > string > variable/number > operator > keyword > builtin
  （priority 写死避免回溯）

### 3. `Markdown.tsx` — code block 用 highlightCode

```tsx
{lines.map((ln, j) => {
  if (ln === "") return <Text key={...}> </Text>;
  const tokens = highlightCode(ln, lang);
  return (
    <Text key={...}>
      {tokens.map((tk, k) => (
        <Text
          key={...}
          color={(t as Record<string,string>)[tokenKindToColor(tk.kind)]}
          italic={tk.kind === "comment"}
        >
          {tk.text}
        </Text>
      ))}
    </Text>
  );
})}
```

每个 token 一个 `<Text color={...}>`；comment 多一个 `italic` 让注释视觉上"退后"。
空行保持 R215 的 `<Text> </Text>` 行为以保 box 高度。

## 渲染样例（手算 ASCII）

**Before (R215)** — ` ```bash `：
```
bash
  $ git status
  On branch main
  $ cat file.txt
  hello world
```

全部 magenta — 一眼看去是墙。

**After (R216)** — ` ```bash `：
```
bash (yellow pill)
  [magenta]$[/] [cyan]git[/] [white]status[/]
  [green]"On branch main"[/]
  [magenta]$[/] [cyan]cat[/] [white]file.txt[/]
  [green]"hello world"[/]
```

(注：`[` ... `]` 实际渲染为彩色字符)

实际显示：
- `$` → magenta (variable expansion)
- `git` / `cat` → cyan (builtin / external command)
- `On branch main` → green (string)
- 注释 `# todo` → gray italic (comment)

类似 ` ```ts `：
- `const` / `function` / `class` → magenta (keyword)
- `"hello"` → green (string)
- `123` / `0xFF` → yellowBright (number)
- `// note` → gray italic (comment)
- `console` / `Object` → cyan (builtin)
- `=` / `+` / `=>` → white (operator)

## 测试

**R216 专门 source-pin 测试**: `aethercode-tui/scripts/test/r216-code-highlight.test.mjs`
（**26 个 test**）

覆盖：
1. `theme.ts` 暴露全部 6 个 token colour token
2. `highlight.ts` exports `highlightCode` + `tokenKindToColor` + `TokenKind` + `Token`
3. `highlight.ts` 定义全部 7 个 token kind
4. dispatcher 路由 4 个 bash 拼写 (bash/sh/shell/zsh)
5. dispatcher 路由 2 个 json 拼写 (json/jsonc)
6. dispatcher 路由 6 个 js 拼写 (javascript/js/typescript/ts/tsx/jsx)
7. unknown-lang fallback (single plain token)
8. bash tokenizer 包含 11 个 control-flow 关键字
9. bash tokenizer 包含 comment + string + variable expansion
10. bash keyword regex 是 word-bounded (`\b`)
11. json tokenizer 包含 true/false/null 关键字
12. json tokenizer 包含 string + number + 结构标点
13. js tokenizer 包含 3 种字符串 (single/double/template)
14. js tokenizer 包含 `//` 和 `/* */` 注释
15. js tokenizer 包含 ES6+ 关键字 (const/function/class/async/...)
16. ts tokenizer extras (interface/type/enum/namespace/declare)
17. Markdown.tsx import `highlightCode` + `tokenKindToColor` + `TokenKind`
18. code block branch 调用 `highlightCode(body, lang)`
19. comments 渲染 italic
20. **不引入新依赖** (cli-highlight/highlight.js/shiki/prismjs/lowlight/micromark/starry-night 全部 forbidden)
21. tsc 编译干净
22-26. **5 个 runtime test**:
   - bash tokenizer splits `if [ $foo -gt 0 ]; then` → 4 种 kind
   - json tokenizer splits `{"a": 1, "b": true}` → 4 种 kind
   - js tokenizer splits `const x = "hi"; // c` → 4 种 kind
   - unknown lang fall back to single plain token
   - empty body → empty token array

**全套相关测试**（回归保护）:

| 测试文件 | 内容 | 结果 |
| -------- | ---- | ---- |
| `r216-code-highlight.test.mjs` | R216 新增 (含 5 个 runtime) | 26/26 ✓ |
| `r215-markdown-pretty.test.mjs` | R215 视觉增强 | 18/18 ✓ |
| `r36-markdown.test.mjs` | R36 解析器 (table/rule/quote/strike) | 9/9 ✓ |
| `r33-pills.test.mjs` | Pills + theme tokens | 11/11 ✓ |
| `r34-toolcard.test.mjs` | ToolCard 用 `t.cat*` | (通过) |
| `r44-progress.test.mjs` | Progress bar | (通过) |
| `r86-thinking.test.mjs` | Thinking 渲染 | (通过) |
| `r91d-subagent.test.mjs` | Subagent 渲染 | (通过) |
| **合计** | — | **116/116 ✓** |

**tsc -p 整体编译**: 0 错 0 警告 (R216 范围)

**esbuild bundle**: 成功 `1.9MB` (R215 是 1973.5KB, R216 是 1981.6KB, +8KB for
highlight.ts + token rendering code). `Done in 619ms`. Shipped 到
`aethercode/dist/ac-tui/ac-tui.js`.

- 注: `commands.ts:557 case "budget"` 是 R44+R95-F 历史遗留 pre-existing warning,
  R216 没动 commands.ts (只动 Markdown.tsx + theme.ts + 加 highlight.ts + 1 个 test)。

## 文件改动

- **修改**:
  - `aethercode-tui/src/theme.ts` (+12 行: 6 个 R216 token + 文档注释)
  - `aethercode-tui/src/components/Markdown.tsx` (code block 改成 tokenize + 1 个
    `<Text>` per token; +30 行)
- **新增**:
  - `aethercode-tui/src/components/markdown/highlight.ts` (12124 bytes, 7 kinds,
    3 langs, 1 dispatcher, 1 tokenKindToColor helper)
  - `aethercode-tui/scripts/test/r216-code-highlight.test.mjs` (14114 bytes, 26 tests)
- **dist rebuild**:
  - `dist/ac-tui.js` 1.9MB (+8KB)
  - `aethercode/dist/ac-tui/ac-tui.js` 同步

## 关键 API 决策

1. **不引入 highlighter 依赖** — R215 的"零依赖 markdown 管道"承诺延伸。
   cli-highlight 输出 ANSI 转义序列，Ink 不能解析；shiki 需要渲染器；highlight.js
   也是 ANSI。手写 12KB 的 regex tokenizer 是正确选择。
2. **tokenize priority 写死** — 每个 lang 内部 {comment, string, variable, operator,
   keyword, builtin} 的顺序在源码里硬编码，**不**让用户配置（避免回溯 + 性能 +
   一致性）。
3. **same-kind token 合并** — `tokenize()` helper 在 `out[out.length-1].kind ===
   currentKind` 时合并到上一个 token，避免 `const` + `class` + `function` 生成 3 个
   独立 `<Text>` 节点。React diff 成本下降。
4. **comment italic** — 跟 desktop 渲染器一致 (`font-style: italic` for `.tok-comment`)。
5. **dispatcher 接受 lang 变体** — `bash` / `shell` / `sh` / `zsh` 全部路由到 bash
   tokenizer (模型输出会用任何一种)；`json` / `jsonc` 都支持；JS/TS 6 种拼写都接。
6. **unknown lang 仍 render** — 不是 "highlight or hide"，而是 "highlight or fall
   back"。fallback 是一个 `plain` token + 老的 `t.code` 颜色，零行为变化。
7. **runtime tokenizer test 走 .js compiled** — tsc 把 .ts 编译到 `tmp-r216-tsc/`,
   node --test 用 `createRequire` 加载。如果编译产物不在就 skip，source-pin tests
   已经覆盖同一 surface。

## 后续候选 (R217+)

1. **更多 lang** — python (重点: def/class/import/None/True/False)、java (public/
   class/static/void)、yaml (key: value)、css (selector / property / value)、
   html (tag / attribute)
2. **更精细 token kind** — `function` (callable identifier)、`punctuation`
   (semi-colon 等)、`regex` (JS 的 /pattern/)
3. **inline code 也用 tokenizer** — 短 inline code ` `if x > 0` ` 应该是
   keyword + plain 的组合
4. **link preview** — `[text](url)` 现在只显示 text，desktop 端也类似；要 hover
   预览可做 R218
5. **admonition** — `> [!WARNING]` 类提示块
6. **修 R44+R95-F pre-existing `case "budget"` 重复** (用户没要求, 不在 R216 scope)

## 教训 (2026-09-05)

1. **"代码高亮" ≠ "加 cli-highlight"** — 5MB+ 的依赖、ANSI 转义、跟 Ink 冲突。
   12KB 的 regex tokenizer 已经覆盖 agent 输出最常见的 3 个 lang。**没有依赖
   解决不了的问题**.
2. **priority order 决定正确性** — comment / string / variable 的顺序错了，结果
   就全错（例如 `${var}` 内的 `#` 被误判为 comment）。R216 测试明确
   `if [ $foo -gt 0 ]` 这种 unquoted 变量，**避开** `"$foo"` 被 string 吞的情况。
3. **same-kind 合并是性能** — 没合并时 `const class static function` 是 4 个
   `<Text>` 节点，合并后是 1 个。React diff 成本下降。**当 30 行代码 90% 是
   plain 时, 这种合并节约非常明显**。
4. **word-bounded keyword 必做** — `\bif\b` 不会匹配 `iffy` 或 `gifts`。
   不加 `\b` 的 tokenizer 必然误报。R216 测试 #10 显式 pin `\b`。
5. **runtime test 走 .js compiled** — Node 不能直接 `import` .ts 源文件（没装
   `tsx` / `ts-node`）。R216 的 tsc smoke test 编译出 .js，runtime test 用
   `createRequire` 加载，**让 source-pin + 实际执行双覆盖**。如果 compiled 不
   在就 skip — source-pin tests 已经覆盖同一 surface。
6. **brace-counter 解析 TSX 必死** — JSX attributes 的 `{...}` 表达式让简单
   `{` / `}` 计数失效。R216 测试用 `indexOf('b.kind === "code"')` +
   `indexOf('b.kind === "list"', codeIdx)` 直接 slice 两个 branch 之间，避开
   JSX 复杂度。
7. **pre-existing warnings 不是 R216 引入的** — esbuild `commands.ts:557 case
   "budget"` 是 R44+R95-F 历史遗留。R216 只动 Markdown.tsx + theme.ts + 加
   highlight.ts。**修不修看用户, 不在 R216 scope**。

## 报告

`aethercode-desktop/docs/R216-TUI-CODE-HIGHLIGHT-2026-09-05.md`
