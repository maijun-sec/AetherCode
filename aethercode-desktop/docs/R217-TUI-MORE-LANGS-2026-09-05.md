# R217 — TUI 语法高亮扩展 (python / java / yaml + 修复) (2026-09-05)

> 用户原话: "继续"（接 R216）

## 现状确认 (R216 后)

R216 上了 bash / json / javascript 三个 lang 的 tokenizer。R216 报告 R217+ 候选
按价值排序：

1. **更多 lang** — python (重点: def/class/import/None/True/False)、java
   (public/class/static/void)、yaml (key: value)、css、html
2. **inline code 也用 tokenizer** — 短 inline code 应该是 keyword + plain 组合
3. **更精细 token kind** — `function` (callable identifier)、`punctuation`、`regex`
4. **link preview** `[text](url)` 
5. **admonition** `> [!WARNING]`
6. 修 R44+R95-F pre-existing `case "budget"` 重复

"继续" 按报告排序，做 #1：扩展 tokenizer 覆盖 python / java / yaml。

## R217 决策

**延续 R216 风格** — 不引入新依赖，每个 lang 一个紧凑 regex tokenizer，dispatcher
扩展 lang 变体：

1. **python** — 35 个控制流关键字（`def` / `class` / `import` / `with` / `yield` /
   `match` / `case` 等）+ 30 个 builtin（`True` / `False` / `None` / `self` /
   `print` / `len` / `range` / `Exception` 等）+ `#` 注释 + 三引号字符串 + `f""` /
   `r""` / `b""` 前缀 + `@decorator` + 复合操作符（`**` / `//` / `->` / `:=`）
2. **java** — 53 个关键字（控制流 + 修饰符 + 类型 `boolean` / `byte` / `int` /
   `void`）+ 23 个 builtin runtime 类型（`String` / `List` / `Map` / `Object` /
   `Integer` / `Exception` 等）+ `//` / `/* */` 注释 + 三引号 text block
   (Java 13+) + `@Annotation` + 字符字面量 `'a'` + 复合操作符
3. **yaml** — `#` 注释 + 引号字符串 + **key 启发式检测**（行首 token + `:` 后面
   的 lookahead）+ true/false/null/yes/no/on/off/~ literal + 数字 +
   block scalar / list marker (`-` 行首)
4. **dispatcher 扩展** — `python` / `py` / `python3` / `py3` → python；
   `java` / `kotlin` / `kt` / `scala` → java（共享关键字，泛 JVM 覆盖）；
   `yaml` / `yml` / `toml` → yaml（TOML `key = value` 不完美但至少路由到 tokenizer）
5. **bug 修复** — tokenize() helper 移除 `m` flag（避免多行 src 在 slice 后
   multiline `^` 反复匹配）；YAML key 改用 `(?<=^|\n)` lookbehind 零宽度锚定
   到行首

## 修改清单

### 1. `highlight.ts` — 新增 3 个 tokenizer + dispatcher 扩展

- `highlightPython(src)` — 70 行
- `highlightJava(src)` — 60 行
- `highlightYaml(src)` — 35 行
- dispatcher 新增 9 个 lang 变体（`python`/`py`/`python3`/`py3` / `java`/`kotlin`/
  `kt`/`scala` / `yaml`/`yml`/`toml`）
- `tokenize()` helper 加 `re.flags.replace(/m/g, "")` — 移除 multiline 模式
  避免多行 src 在 slice 后 `^` 反复匹配（这是 R217 调试时发现的隐蔽 bug）

### 2. `Markdown.tsx` — **0 行改动**

R217 完全在 `highlight.ts` 内完成，renderer 不需要任何改动。`highlightCode(body,
lang)` 已经接受任意 lang，dispatcher 自动路由。

### 3. 关键 bug 修复

R217 调试时发现一个 R216 遗留 bug — 当 `src` 是 multi-line 且 pattern 用了
`^` + `m` flag 时，`tokenize()` 把 `src` slice 到 i 位置后，inner `^` 在 multiline
mode 下会**重新匹配** slice 字符串里的每个 `\n` 之后，导致一个 3 行 YAML
snippet 被匹配成一个 giant "key" token：

```yaml
name: alice
age: 30
active: true
```

R216 之前没暴露这个 bug 是因为所有 lang 都是单行测试。R217 加了 multi-line
YAML 才显形。

**修法**：
1. `tokenize()` helper 在 `new RegExp("^(?:" + re.source + ")", re.flags)` 时
   剥掉 `m` flag（外层 `^` 已经锚定到 i 位置，不需要 multiline）
2. YAML key pattern 从 `/^[A-Za-z_][A-Za-z0-9_.-]*(?=\s*:)/m` 改成
   `/(?<=^|\n)[A-Za-z_][A-Za-z0-9_.-]*(?=\s*:)/` — lookbehind 零宽度锚定到
   行首，不 consume `\n`

修完 trace 验证：
- JAVA `public class Foo extends Bar { void run() {} }` → `{}` 是 operator ✓
- YAML `name: alice\nage: 30\nactive: true` → `name`/`age`/`active` 是 key，`30` 是 number，`true` 是 keyword ✓

### 4. 实际 tokenizer 行为（手算）

**` ```python `** — `def foo(x: int) -> str: return str(x)`：
- `def` `return` → magenta (keyword)
- `str` → cyan (builtin)
- `:` `->` `(` `)` → white (operator)
- `foo` `x` `int` → plain (标识符 — 我们没做 function kind)

**` ```java `** — `public class Foo extends Bar { void run() {} }`：
- `public` `class` `extends` `void` → magenta (keyword)
- `Foo` `Bar` `run` → plain (类型/方法名)
- `{` `}` → white (operator)
- `()` → white (operator)

**` ```yaml `** — `name: alice\nage: 30\nactive: true`：
- `name` `age` `active` → green (key)
- `:` ` ` → plain
- `30` → yellowBright (number)
- `true` → magenta (keyword)
- `alice` → plain（值 — 我们没做 unquoted string 的高亮）

## 测试

**R217 专门 source-pin 测试**: `aethercode-tui/scripts/test/r217-more-langs.test.mjs`
（**29 个 test**）

覆盖：
1. **python**:
   - `highlightPython` 函数存在
   - 10+ 关键字（`def` / `class` / `import` / `with` / `yield` / `return` / `if` /
     `else` / `try` / `except`）
   - 4+ builtin（`True` / `False` / `None` / `self`）
   - `#` 注释 + `"""..."""` / `'''...'''` 三引号字符串
   - `@decorator` pattern
   - f-string prefix (`f"..."` 等)
2. **java**:
   - `highlightJava` 函数存在
   - 22+ 关键字（控制流 + 修饰符 + 类型）
   - `@Annotation` pattern
   - 6+ runtime builtin 类型（`String` / `List` / `Map` / `Object` / `Integer` /
     `Exception`）
   - 字符字面量 `'a'`
3. **yaml**:
   - `highlightYaml` 函数存在
   - `#` 注释 + 引号字符串
   - `true` / `false` / `null` / `yes` / `no` literal
   - `(?=\s*:)` key 启发式
4. **dispatcher**:
   - 4 个 python 拼写
   - 4 个 java 拼写（含 `kotlin` / `kt` / `scala`）
   - 3 个 yaml 拼写（含 `toml`）
   - 6 个 R216 已有拼写（bash / sh / shell / json / javascript / typescript）
   - unknown lang → plain fallback
5. **0 新依赖** (cli-highlight / highlight.js / shiki / prismjs / lowlight /
   micromark / starry-night / pygments 全部 forbidden)
6. **Markdown.tsx 不变** — `highlightCode(body, lang)` 仍然存在
7. **tsc 编译干净**
8. **6 个 runtime test**:
   - python `def foo(x: int) -> str: return str(x)` → keyword + builtin + operator
   - java `public class Foo extends Bar { void run() {} }` → keyword + operator
   - yaml `name: alice\nage: 30\nactive: true` → key + number + keyword
   - kotlin `kt` → java 路由（`void` 是 keyword）
   - toml → yaml 路由（multi-token 不是 plain）
   - bash 回归 — R216 没破

**全套相关测试**（回归保护）:

| 测试文件 | 内容 | 结果 |
| -------- | ---- | ---- |
| `r217-more-langs.test.mjs` | R217 新增 | 29/29 ✓ |
| `r216-code-highlight.test.mjs` | R216 tokenizer | 26/26 ✓ |
| `r215-markdown-pretty.test.mjs` | R215 视觉增强 | 18/18 ✓ |
| `r36-markdown.test.mjs` | R36 解析器 | 9/9 ✓ |
| `r33-pills.test.mjs` | Pills + theme tokens | 11/11 ✓ |
| `r34-toolcard.test.mjs` | ToolCard | (通过) |
| `r44-progress.test.mjs` | Progress bar | (通过) |
| `r86-thinking.test.mjs` | Thinking 渲染 | (通过) |
| `r91d-subagent.test.mjs` | Subagent 渲染 | (通过) |
| **合计** | — | **145/145 ✓** |

**tsc -p 整体编译**: 0 错 0 警告 (R217 范围)

**esbuild bundle**: 成功 `1.9MB` (R216 是 1981.6KB, R217 是 1988.7KB, +7KB for
3 个新 lang). `Done in 461ms`. Shipped 到 `aethercode/dist/ac-tui/ac-tui.js`.

- 注: `commands.ts:557 case "budget"` 仍是 R44+R95-F 历史遗留 pre-existing
  warning, R217 没动 commands.ts。

## 文件改动

- **修改**:
  - `aethercode-tui/src/components/markdown/highlight.ts` (从 12124 bytes
    增到 ~17KB; +3 tokenizer, +tokenize 修复, +dispatcher 扩展)
- **新增**:
  - `aethercode-tui/scripts/test/r217-more-langs.test.mjs` (15331 bytes, 29 tests)
- **0 行 Markdown.tsx 改动**
- **dist rebuild**:
  - `dist/ac-tui.js` 1.9MB (+7KB)
  - `aethercode/dist/ac-tui/ac-tui.js` 同步

## 关键 API 决策

1. **JVM-lang 共享 java tokenizer** — `kotlin` / `kt` / `scala` 路由到 java
   tokenizer（不是完美但可用）。理由：JVM 系语言的 keyword 重叠度极高
   （public / class / interface / return / if / else / while / try / catch / throw
   / new / import 都是公共的），不值得为每个 JVM 写独立 tokenizer。**新策略**：
   "close enough" 比 "没有" 好。
2. **TOML 共享 yaml tokenizer** — `toml` 路由到 yaml。YAML 启发式 key 检测
   是 `(?<=^|\n)token(?=\s*:)`，TOML 用 `=` 不是 `:`，所以 key 不会被识别为
   string，但 dispatcher 至少把它路由到 tokenizer，**避免 fall back 到 plain
   的零行为变化**。
3. **bug 修复是 R217 的隐藏价值** — `tokenize()` 移除 `m` flag + YAML
   lookbehind 是 R217 调试时发现的 R216 隐藏 bug。**没有 multi-line 测试
   暴露不出来**。R217 强制了 multi-line yaml runtime test 触发，**这是一
   个测试驱动 bug 修复的好例子**。
4. **`@decorator` / `@Annotation` 是 builtin kind** — 不是 keyword 因为它
   是结构装饰符（runtime metadata）而不是控制流。
5. **runtime test 走 .js compiled** — 跟 R216 一样，R217 runtime test 用
   `createRequire(import.meta.url)` 加载 tsc 编译的 `tmp-r217-tsc/`
   产物。如果 compiled 不在就 skip — source-pin tests 已经覆盖同一 surface。

## 后续候选 (R218+)

1. **inline code 也用 tokenizer** — R216 报告 #2，"比新加 lang 价值更大"
   （模型输出里到处都是 ` `xxx` ` 短代码片段）。需要识别 inline code 的
   "implicit language"（R216 现在所有 inline code 都是单色 magenta pill），
   或者用上下文 heuristic。
2. **更精细 token kind** — `function` (callable identifier)、`punctuation`
   (semi-colon 等)、`regex` (JS 的 /pattern/)
3. **link preview** — `[text](url)` 现在只显示 text
4. **admonition** — `> [!WARNING]` 提示块
5. **修 R44+R95-F pre-existing `case "budget"` 重复**（用户没要求）
6. **更多 lang**：css / html / ruby / go / rust — 价值依次降低

## 教训 (2026-09-05)

1. **"加新 lang" = 测试驱动 bug 修复** — R217 加 multi-line YAML runtime test
   立即暴露了 R216 隐藏的 tokenize() multiline bug。**没有 multi-line 测试
   = bug 永远藏**。R217 强制 `name: alice\nage: 30\nactive: true` 这种 multi-line
   input 触发。
2. **`m` flag + slice 是陷阱** — `^(?:...)` 外层锚定到 i 位置，但内层 `^` 在
   multiline mode 下重新匹配 slice 字符串里的 `\n` 之后。**唯一安全的写法**：
   tokenize() helper **剥掉 `m` flag**，让 `^` 只匹配 slice 开头 = i 位置。
3. **YAML key 检测用 lookbehind 不用 `^/m`** — 两者都锚定到行首，但 `(?<=^|\n)`
   是**零宽度** lookbehind，不会破坏 tokenize() 的 `i` 推进逻辑。
4. **JVM 共享 java tokenizer 是合理 trade-off** — 完美主义 ("每个 JVM-lang 独立
   tokenizer") 会导致代码量 ×3 但用户视觉改善微小。**"close enough" 比
   "没有" 好**。
5. **TOML 路由到 yaml 是不完美但正确的 fallback** — key 不被识别（`=` 不是
   `:`），但至少 dispatcher 路由到了，**避免 fall back 到 plain 的零行为变化**。
   跟 "unknown lang → plain" 一致：能路由就路由，不能就 fallback。
6. **pre-existing warnings 不是 R217 引入的** — esbuild `commands.ts:557 case
   "budget"` 仍是 R44+R95-F 历史遗留。R217 只动 highlight.ts（+tokenize 修复）。
   **修不修看用户, 不在 R217 scope**。

## 报告

`aethercode-desktop/docs/R217-TUI-MORE-LANGS-2026-09-05.md`
