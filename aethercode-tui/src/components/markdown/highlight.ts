/**
 * R216 — TUI code-block syntax highlighter.
 *
 * <p>The R215 code-block surface (rounded border + lang pill +
 * dark magenta background) is a "container" — without
 * token-level colouring the body is still one flat
 * `t.code` magenta. The user has been asking for "very pretty"
 * markdown, and reading 30 lines of bash / json / typescript
 * in a single colour is a barrier. R216 adds lightweight
 * regex-based tokenization for the three languages we see most
 * often in agent output:
 *
 * <ul>
 *   <li><b>bash</b> / <b>sh</b> / <b>shell</b> —
 *       <code>#</code> comments, <code>"..."</code> /
 *       <code>'...'</code> strings, <code>${VAR}</code>
 *       expansions, control flow (<code>if</code> /
 *       <code>for</code> / <code>case</code>), and
 *       common builtin commands (<code>cd</code> /
 *       <code>echo</code> / <code>grep</code>).
 *   <li><b>json</b> —
 *       <code>"key": value</code> pairs, numbers,
 *       <code>true</code> / <code>false</code> / <code>null</code>.
 *   <li><b>javascript</b> / <b>js</b> /
 *       <b>typescript</b> / <b>ts</b> —
 *       <code>//</code> / <code>/* ... *&#47;</code> comments,
 *       three string flavours (single / double / template),
 *       control-flow keywords, and the usual builtins
 *       (<code>console</code>, <code>Object</code>, etc.).
 * </ul>
 *
 * <p>Token kind → colour mapping lives in <code>theme.ts</code>
 * (<code>t.tokKeyword</code>, <code>t.tokString</code>, …).
 * The renderer is <code>Markdown.tsx</code>; it imports
 * {@link highlightCode} and walks the resulting
 * <code>Token[]</code> emitting one <code>&lt;Text&gt;</code>
 * per kind, identical to the desktop renderer's
 * <code>.tok-keyword</code> / <code>.tok-string</code>
 * CSS classes.
 *
 * <p>Why hand-rolled (no <code>cli-highlight</code>):
 * <ul>
 *   <li><code>cli-highlight</code> emits raw ANSI escape
 *       sequences; Ink has its own colour prop system and
 *       mixing the two corrupts the rendering pipeline.
 *   <li>The set of "languages" we actually see in agent
 *       output is small (bash, json, ts/js). A 300-line
 *       hand-rolled tokenizer is easier to audit than
 *       pulling in 5 MB of highlighter dependencies.
 *   <li>The R215 decision was to keep the markdown
 *       pipeline <i>dependency-free</i>; R216 keeps that
 *       commitment.
 * </ul>
 *
 * <p>Each language tokenizer is a list of
 * <code>{ kind, re }</code> pairs tried in priority order;
 * the first match wins. Unmatched characters accumulate
 * into a single <code>plain</code> token so the output
 * stream never balloons. The whole module is pure
 * (no I/O, no side effects) and is exercised by the
 * <code>r216-code-highlight.test.mjs</code> source-pin
 * suite.
 */

export type TokenKind =
  | "plain"
  | "keyword"   // control flow / structural
  | "string"    // "..." '...' `...`
  | "number"    // 123 0xFF 3.14
  | "comment"   // // ... # ... /* ... */
  | "builtin"   // true false null console Math
  | "operator"; // = + - * / => ===

export interface Token {
  text: string;
  kind: TokenKind;
}

interface Pattern {
  kind: TokenKind;
  re: RegExp;
}

/**
 * Tokenize a string with a priority list of patterns. The
 * first pattern that matches at the current position wins.
 * Unmatched characters accumulate into a single `plain`
 * token so the output stream doesn't fragment.
 */
function tokenize(src: string, patterns: Pattern[]): Token[] {
  const out: Token[] = [];
  let i = 0;
  const n = src.length;
  while (i < n) {
    let matched = false;
    for (const { kind, re } of patterns) {
      // Anchor the pattern at the current position. We rebuild
      // the regex so the `^` matches the *substring* start
      // (not the whole string). The `lastIndex` form of
      // `RegExp.prototype.exec` is global-state and doesn't
      // survive across pattern swaps, so this is simpler and
      // safer.
      //
      // strip the `m` (multiline) flag from `re.flags`.
      // When `src` is multi-line and the pattern includes
      // `(?<=^|\n)` or `^…/m`, the inner `^` would re-match
      // every `\n` in the slice — turning a 3-line YAML
      // snippet into one big "key" token. We want `^` to
      // anchor strictly at the current `i` position, which
      // is exactly what the outer `^(?:...)` already does.
      const flags = re.flags.replace(/m/g, "");
      const anchored = new RegExp("^(?:" + re.source + ")", flags);
      const m = anchored.exec(src.slice(i));
      if (m && m[0].length > 0) {
        const last = out[out.length - 1];
        if (last && last.kind === kind) {
          // Coalesce same-kind tokens — keeps the <Text> tree
          // small and the React diff cheap.
          last.text += m[0];
        } else {
          out.push({ text: m[0], kind });
        }
        i += m[0].length;
        matched = true;
        break;
      }
    }
    if (!matched) {
      // No pattern matched — take a single character as plain
      // and continue. We coalesce adjacent plain chars into
      // the previous plain token when one exists.
      const last = out[out.length - 1];
      if (last && last.kind === "plain") {
        last.text += src[i];
      } else {
        out.push({ text: src[i], kind: "plain" });
      }
      i++;
    }
  }
  return out;
}

// ---------------------------------------------------------------------------
// bash / sh / shell
// ---------------------------------------------------------------------------

/**
 * Bash control-flow / structural keywords. These are the
 * words that change the *shape* of the script — not the
 * commands it runs.
 */
const BASH_KEYWORDS = [
  "if", "then", "else", "elif", "fi",
  "for", "while", "until", "do", "done",
  "case", "esac", "in", "select",
  "function", "return", "exit", "break", "continue",
  "time", "{", "}",
];

/** Common builtin commands — things `bash` ships with. */
const BASH_BUILTINS = [
  "echo", "printf", "read", "export", "local", "source", "alias",
  "set", "unset", "cd", "pwd", "eval", "exec", "test", "true",
  "false", "shift", "trap", "wait", "type", "declare", "typeset",
  "shopt", "ulimit", "umask",
];

/** External commands the agent typically emits. */
const BASH_COMMANDS = [
  "ls", "cat", "grep", "sed", "awk", "find", "mkdir", "rmdir",
  "rm", "mv", "cp", "chmod", "chown", "touch", "ln", "tar",
  "curl", "wget", "git", "npm", "npx", "node", "python", "pip",
  "java", "mvn", "gradle", "make", "docker", "kubectl",
];

function highlightBash(src: string): Token[] {
  const keywordRe = new RegExp(
    "\\b(?:" + BASH_KEYWORDS.filter((k) => /^[a-z]+$/.test(k)).join("|") + ")\\b",
  );
  const builtinRe = new RegExp(
    "\\b(?:" + BASH_BUILTINS.join("|") + ")\\b",
  );
  const commandRe = new RegExp(
    "\\b(?:" + BASH_COMMANDS.join("|") + ")\\b",
  );
  return tokenize(src, [
    // Comments — bash comments are `#` to end of line.
    { kind: "comment", re: /#[^\n]*/ },
    // Strings — both single and double quoted. The patterns
    // are non-greedy and tolerate escaped quotes. We do NOT
    // try to interpolate `${...}` inside the string body —
    // that's a quoted-context feature, not a syntax token.
    { kind: "string", re: /"(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)*'/ },
    // Variable expansion — `$VAR`, `${VAR}`, `$1`, `$$`,
    // `$?`. Treated as builtin colour so the user can spot
    // where a value is being pulled in.
    { kind: "builtin", re: /\$\{?[A-Za-z_][A-Za-z0-9_]*\}?|\$\d+|\$\$|\$\?/ },
    // Numbers — integers, decimals, hex.
    { kind: "number", re: /\b\d+(?:\.\d+)?\b|0x[0-9a-fA-F]+\b/ },
    // Operators — `&&`, `||`, `>>`, `<<`, `|`, `>`, `<`,
    // `==`, `!=`, `=`, `;`. Compound operators must come
    // first so the regex doesn't split `==` into two `=`.
    { kind: "operator", re: /&&|\|\||>>|<<|==|!=|<<=|>>=|\$\(|\)|[|<>=!+\-*/%^&]/ },
    // Keywords — control flow first (higher priority).
    { kind: "keyword", re: keywordRe },
    // Builtins.
    { kind: "builtin", re: builtinRe },
    // External commands.
    { kind: "builtin", re: commandRe },
  ]);
}

// ---------------------------------------------------------------------------
// json
// ---------------------------------------------------------------------------

function highlightJson(src: string): Token[] {
  return tokenize(src, [
    { kind: "string", re: /"(?:[^"\\]|\\.)*"/ },
    { kind: "number", re: /-?\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b/ },
    { kind: "keyword", re: /\b(?:true|false|null)\b/ },
    { kind: "operator", re: /[{}[\]:,]/ },
  ]);
}

// ---------------------------------------------------------------------------
// javascript / typescript
// ---------------------------------------------------------------------------

const JS_KEYWORDS = [
  "break", "case", "catch", "class", "const", "continue", "debugger",
  "default", "delete", "do", "else", "export", "extends", "finally",
  "for", "function", "if", "import", "in", "instanceof", "new",
  "return", "super", "switch", "this", "throw", "try", "typeof",
  "var", "void", "while", "with", "yield", "let", "static", "async",
  "await", "from", "of", "as", "is",
];

const TS_EXTRA_KEYWORDS = [
  "interface", "type", "enum", "namespace", "declare", "public",
  "private", "protected", "readonly", "abstract", "implements",
  "keyof", "satisfies",
];

const JS_BUILTINS = [
  "true", "false", "null", "undefined", "NaN", "Infinity",
  "console", "window", "document", "globalThis", "process", "Buffer",
  "Object", "Array", "String", "Number", "Boolean", "Math", "JSON",
  "Promise", "Date", "RegExp", "Error", "Map", "Set", "Symbol",
  "Set", "WeakMap", "WeakSet", "Proxy", "Reflect",
];

function highlightJavaScript(src: string): Token[] {
  const keywordRe = new RegExp(
    "\\b(?:" + [...JS_KEYWORDS, ...TS_EXTRA_KEYWORDS].join("|") + ")\\b",
  );
  const builtinRe = new RegExp(
    "\\b(?:" + JS_BUILTINS.join("|") + ")\\b",
  );
  return tokenize(src, [
    // Comments — single-line `//` and block `/* ... */`.
    // Block comments are non-greedy and tolerate newlines.
    { kind: "comment", re: /\/\/[^\n]*|\/\*[\s\S]*?\*\// },
    // Strings — single, double, and template. The template
    // literal regex is intentionally non-greedy so we don't
    // span across two adjacent template strings.
    { kind: "string", re: /"(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)*'|`(?:[^`\\]|\\.)*`/ },
    // Numbers — int / float / hex.
    { kind: "number", re: /\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b|0x[0-9a-fA-F]+\b|0b[01]+\b/ },
    // Keywords first (control flow + declarations), then
    // builtins (true/false/null + runtime types).
    { kind: "keyword", re: keywordRe },
    { kind: "builtin", re: builtinRe },
    // Operators — compound forms (`=>`, `===`, `!==`, `&&`,
    // `||`, `++`, `--`, `**`) before single chars so the
    // regex doesn't split them.
    { kind: "operator", re: /=>|===|!==|==|!=|<=|>=|<<|>>|>>>|&&|\|\||\+\+|--|\*\*|[-+*/%=<>!&|^~?]/ },
  ]);
}

// ---------------------------------------------------------------------------
// python
// ---------------------------------------------------------------------------

/**
 * Python control flow + declarations. Modelled after the
 * 3.12 keyword list (https://docs.python.org/3/reference/lexical_analysis.html#keywords).
 * The `True` / `False` / `None` keywords are routed to
 * `builtin` (not `keyword`) because they're more like
 * runtime constants than control-flow markers — same
 * treatment JS gets for `true` / `false` / `null`.
 */
const PY_KEYWORDS = [
  "and", "as", "assert", "async", "await", "break", "class", "continue",
  "def", "del", "elif", "else", "except", "finally", "for", "from",
  "global", "if", "import", "in", "is", "lambda", "nonlocal", "not",
  "or", "pass", "raise", "return", "try", "while", "with", "yield",
  "match", "case",
];

/** Python runtime constants — `True` / `False` / `None`. */
const PY_BUILTINS = [
  "True", "False", "None",
  "self", "cls",
  "print", "len", "range", "list", "dict", "set", "tuple", "frozenset",
  "str", "int", "float", "bool", "bytes", "bytearray",
  "open", "isinstance", "issubclass", "type", "super", "object",
  "enumerate", "zip", "map", "filter", "reversed", "sorted",
  "min", "max", "sum", "abs", "round", "any", "all",
  "Exception", "ValueError", "TypeError", "KeyError", "IndexError",
  "AttributeError", "RuntimeError", "StopIteration",
];

function highlightPython(src: string): Token[] {
  const keywordRe = new RegExp("\\b(?:" + PY_KEYWORDS.join("|") + ")\\b");
  const builtinRe = new RegExp("\\b(?:" + PY_BUILTINS.join("|") + ")\\b");
  return tokenize(src, [
    // Comments: `#` to end of line. (Python's triple-quoted
    // docstrings are strings, not comments — they go through
    // the string branch.)
    { kind: "comment", re: /#[^\n]*/ },
    // Strings: single / double / triple (both flavours) +
    // raw (`r"..."`) + byte (`b"..."`). The triple form is
    // the most common case for docstrings.
    { kind: "string", re: /"""[\s\S]*?"""|'''[\s\S]*?'''|"(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)*'/ },
    // F-strings: `f"..."` / `f'...'` — the leading `f` is
    // a prefix, the body is a string. We match the prefix
    // as a `builtin` and let the string pattern handle the
    // quoted body. (We don't try to interpolate `{expr}`.)
    { kind: "builtin", re: /\b[frbFRB]?(?=["'])/ },
    // Numbers: int / float / hex / oct / binary / scientific.
    { kind: "number", re: /\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b|0x[0-9a-fA-F]+\b|0o[0-7]+\b|0b[01]+\b/ },
    // Decorators: `@name` — render as builtin (annotation).
    { kind: "builtin", re: /@[A-Za-z_][A-Za-z0-9_.]*/ },
    // Operators — Python's compound operators come first
    // so the regex doesn't split them. Order matters:
    // multi-char forms (`**`, `//`, `->`, `:=`) before
    // single-char forms.
    { kind: "operator", re: /->|:=|@=|\*\*|\/\/=|<<=|>>=|\+=|-=|\*=|\/=|%=|=|<=|>=|==|!=|\/\/|<<|>>|@|[-+*/%=<>!&|^~]/ },
    // Keywords first (control flow), then builtins.
    { kind: "keyword", re: keywordRe },
    { kind: "builtin", re: builtinRe },
  ]);
}

// ---------------------------------------------------------------------------
// java
// ---------------------------------------------------------------------------

/**
 * Java reserved words (JLS 21 §3.9). Modifiers that the
 * user sees often (`public` / `private` / `static`) are
 * listed in `JAVA_MODIFIERS` and rendered as `keyword`
 * (declarations) so they stand out from runtime types.
 */
const JAVA_KEYWORDS = [
  "abstract", "assert", "boolean", "break", "byte", "case", "catch",
  "char", "class", "const", "continue", "default", "do", "double",
  "else", "enum", "extends", "final", "finally", "float", "for",
  "goto", "if", "implements", "import", "instanceof", "int", "interface",
  "long", "native", "new", "package", "private", "protected", "public",
  "return", "short", "static", "strictfp", "super", "switch", "synchronized",
  "this", "throw", "throws", "transient", "try", "void", "volatile", "while",
  "yield", "sealed", "permits", "non-sealed", "record", "var",
];

/**
 * Common Java runtime types (java.lang + java.util). We
 * don't try to be exhaustive — just the names the user
 * typically sees in agent output.
 */
const JAVA_BUILTINS = [
  "String", "Object", "Integer", "Long", "Double", "Float", "Boolean",
  "List", "Map", "Set", "Collection", "ArrayList", "HashMap", "HashSet",
  "LinkedList", "TreeMap", "TreeSet", "Optional", "Stream",
  "System", "Math", "Thread", "Runnable", "Callable", "Exception",
  "RuntimeException", "NullPointerException", "IllegalArgumentException",
  "IOException", "ClassNotFoundException",
];

function highlightJava(src: string): Token[] {
  const keywordRe = new RegExp("\\b(?:" + JAVA_KEYWORDS.join("|") + ")\\b");
  const builtinRe = new RegExp("\\b(?:" + JAVA_BUILTINS.join("|") + ")\\b");
  return tokenize(src, [
    // Comments: single-line `//` and block `/* ... */`,
    // plus JavaDoc `/** ... */` (which is also a block
    // comment — we don't need a special case).
    { kind: "comment", re: /\/\/[^\n]*|\/\*[\s\S]*?\*\// },
    // Strings: double-quoted + text-block (Java 13+
    // `"""..."""`). The text block can span lines.
    { kind: "string", re: /"""[\s\S]*?"""|"(?:[^"\\\n]|\\.)*"/ },
    // Char literals: `'a'`.
    { kind: "string", re: /'(?:[^'\\\n]|\\.)'/ },
    // Annotations: `@Override` / `@Test` — render as builtin.
    { kind: "builtin", re: /@[A-Za-z_][A-Za-z0-9_]*/ },
    // Numbers: int / long (with `L` suffix) / float (with
    // `f` or `F` or `d` suffix) / hex / binary.
    { kind: "number", re: /\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?[fFdDlL]?\b|0x[0-9a-fA-F]+[lL]?\b|0b[01]+[lL]?\b/ },
    // Operators: compound forms first (`>>>` for
    // `Stream.toList`, `::` for method refs, `?:` for
    // ternaries). `{` / `}` are structural in Java
    // (class body / method body), so we render them as
    // operators.
    { kind: "operator", re: /->|::|\?::?|\+\+|--|<<=|>>=|>>>=|&&|\|\||===|!==|==|!=|<=|>=|<<|>>|>>>|\+=|-=|\*=|\/=|%=|&=|\|=|\^=|[-+*/%=<>!&|^~?{}]/ },
    // Keywords first (control flow + modifiers + types),
    // then builtins (common runtime types).
    { kind: "keyword", re: keywordRe },
    { kind: "builtin", re: builtinRe },
  ]);
}

// ---------------------------------------------------------------------------
// yaml
// ---------------------------------------------------------------------------

/**
 * YAML tokenizer. YAML is indentation-sensitive, but for
 * colouring we only need a few rules:
 *
 * 1. `#` comments (start of line or after whitespace) — gray
 * 2. Keys at the start of a line (followed by `:`) — green
 *    (so the user can scan a config for what's being set)
 * 3. Booleans / null — keyword (magenta)
 * 4. Numbers — yellowBright
 * 5. Quoted strings — green
 * 6. List bullets (`-`) — operator
 * 7. Block scalars (`|` / `>`) — operator
 * 8. Everything else — plain
 *
 * The "key" detection is heuristic: a token followed by `:`
 * and a space is treated as a key. We don't try to handle
 * nested keys (`parent.child`); they're rendered as plain.
 */
function highlightYaml(src: string): Token[] {
  return tokenize(src, [
    // Comments: `#` to end of line. Must be at start of
    // line OR preceded by whitespace to avoid matching
    // a `#` inside a string (we don't try to track
    // string state — strings are usually quoted in YAML,
    // so an unquoted `#` is almost always a comment).
    { kind: "comment", re: /(?:^|\s)#[^\n]*/ },
    // Quoted strings: single / double.
    { kind: "string", re: /"(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)*'/ },
    // Keys: a token at the start of a line followed by
    // `:`. R217: we use `(?<=^|\n)` lookbehind (not the
    // `^` + `m` flag combo) because the tokenize() helper
    // anchors the outer regex to the current `i` position
    // — a `^/m` would re-match every `\n` in the slice,
    // collapsing multi-line YAML into one giant key. The
    // lookbehind is zero-width, so `i` advances to the
    // end of the key.
    { kind: "string", re: /(?<=^|\n)[A-Za-z_][A-Za-z0-9_.-]*(?=\s*:)/ },
    // Booleans / null.
    { kind: "keyword", re: /\b(?:true|false|yes|no|on|off|null|~)\b/ },
    // Numbers — int / float / hex / octal / sexagesimal.
    // (Sexagesimal is rare; we still match it.)
    { kind: "number", re: /\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b|0x[0-9a-fA-F]+\b|0o[0-7]+\b/ },
    // Block scalars / list markers. The list-marker
    // pattern (`-` at line start) uses the same
    // lookbehind trick as the key pattern.
    { kind: "operator", re: /[|>&*!]|(?<=^|\n)[\s]*-/ },
  ]);
}

// ---------------------------------------------------------------------------
// dispatcher
// ---------------------------------------------------------------------------

/**
 * Public entry point. Dispatches by `lang` (case-insensitive)
 * and returns the tokenized stream. An unknown / empty
 * language falls back to a single `plain` token (so the
 * renderer falls back to the 历史 `t.code` colour).
 */
export function highlightCode(src: string, lang: string): Token[] {
  if (!src) return [];
  const norm = (lang || "").toLowerCase();
  // bash family
  if (norm === "bash" || norm === "sh" || norm === "shell" || norm === "zsh") {
    return highlightBash(src);
  }
  // json family
  if (norm === "json" || norm === "jsonc") {
    return highlightJson(src);
  }
  // js / ts family
  if (
    norm === "javascript" || norm === "js" ||
    norm === "typescript" || norm === "ts" || norm === "tsx" || norm === "jsx"
  ) {
    return highlightJavaScript(src);
  }
  // python family
  if (norm === "python" || norm === "py" || norm === "python3" || norm === "py3") {
    return highlightPython(src);
  }
  // java family — `java` plus a handful of JVM-lang
  // spellings so the same tokenizer covers most agent
  // output (`kotlin` / `kt` / `scala` are *not* exact
  // matches for Java syntax, but the keyword overlap is
  // high enough that the user gets a usable colour scheme
  // even when we don't have a per-language tokenizer).
  if (
    norm === "java" || norm === "kotlin" || norm === "kt" || norm === "scala"
  ) {
    return highlightJava(src);
  }
  // yaml family — `yaml` plus `yml` and TOML (TOML
  // has a similar `key = value` shape; the heuristic key
  // detection works for both).
  if (norm === "yaml" || norm === "yml" || norm === "toml") {
    return highlightYaml(src);
  }
  // Unknown lang — single plain token, identical to the
  // 历史 renderer's behaviour.
  return [{ text: src, kind: "plain" }];
}

// ---------------------------------------------------------------------------
// inline code (对应历史 round)
// ---------------------------------------------------------------------------

/**
 * Universal "inline code" tokenizer. R218 repurposes the
 * lang-specific tokenizers (`highlightBash` / `highlightJson`
 * / `highlightJavaScript` / etc.) for *block* code — the
 * ones with a declared language — but inline code
 * (`` `foo` ``) doesn't have a declared language. The user
 * types `Use the \`git\` command` and we have to do our
 * best.
 *
 * <p>Approach: a *universal* regex that recognises the
 * shapes that look like code regardless of language:
 *
 * <ul>
 *   <li><b>String</b> — <code>"..."</code> / <code>'...'</code> /
 *       <code>\`...\`</code> (rare in inline code but
 *       possible).
 *   <li><b>Number</b> — int / float / hex.
 *   <li><b>Keyword</b> — a small union of the most common
 *       control-flow + declaration keywords across all the
 *       langs we recognise (bash + python + java + js + ts).
 *       We deliberately do NOT include every keyword from
 *       every lang (150+) — that would slow the regex
 *       down and over-classify identifiers as keywords.
 *   <li><b>Operator</b> — the common compound operators
 *       (<code>=&gt;</code> / <code>===</code> / <code>!=</code> /
 *       <code>&amp;&amp;</code> / <code>||</code> / etc.) plus
 *       single chars.
 *   <li>Anything else is <b>plain</b> (typically an
 *       identifier like <code>foo</code> / <code>myVar</code>).
 * </ul>
 *
 * <p>The renderer (Markdown.tsx) wraps the result in a
 * <code>&lt;Box backgroundColor={t.codeBg}&gt;</code> with a
 * single trailing + leading space, so the inline code still
 * reads as a filled "pill" (R215 visual) — but the inside
 * of the pill now has colour-coded tokens. This is the
 * "one bg, many colors" pattern that the user explicitly
 * asked for.
 */

/** The keyword union is intentionally small — 30-50 of the
 *  most common across all supported langs. Adding more
 *  slows the regex and risks over-classifying identifiers. */
const INLINE_KEYWORDS = [
  // control flow (cross-lang)
  "if", "else", "elif", "for", "while", "do", "switch", "case",
  "break", "continue", "return", "throw", "try", "catch",
  "finally", "yield", "await", "async",
  // declarations
  "function", "class", "interface", "struct", "enum", "trait",
  "def", "fn", "func", "lambda",
  "var", "let", "const", "static", "public", "private", "protected",
  "void", "new", "delete", "this", "self", "super",
  // imports / modules
  "import", "export", "from", "as", "in", "of", "with",
  // python-specific (a few high-signal)
  "pass", "raise", "match", "nonlocal", "global",
  // js/ts specific
  "typeof", "instanceof", "in", "of",
  // bash
  "then", "fi", "done", "esac", "in",
];

/** Operator set used by all langs. Compound forms first. */
const INLINE_OPERATORS = [
  "=>", "===", "!==", "==", "!=", "<=", ">=",
  "<<=", ">>=", "&&", "||", "??",
  "->", "::", ":=", "**", "//",
  "<<", ">>", "++", "--",
  "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=",
  "+", "-", "*", "/", "%", "=",
  "<", ">", "!", "&", "|", "^", "~", "?",
  // `(` / `)` are extremely common in inline code
  // (`foo()` / `(x + y)` / `setTimeout(...)` etc.) so we
  // classify them as operators. We deliberately do NOT
  // add `{` / `}` — inline code rarely has block bodies,
  // and adding them would cause over-classification of
  // prose that happens to use braces (`{key: value}` in
  // a code-style aside is rendered as text, not code).
  "(", ")", "[", "]",
];

function buildInlineOperatorRe() {
  // Sort by length DESC so multi-char operators are tried
  // before their single-char prefixes. We then build a
  // single alternation regex from the deduped list.
  const dedup = Array.from(new Set(INLINE_OPERATORS))
    .sort((a, b) => b.length - a.length);
  // Escape each operator for use inside a regex character
  // class is wrong — these are literal alternation
  // branches, not a char class. We escape only the
  // characters that have regex meaning outside a char
  // class: `\`, `.`, `+`, `*`, `?`, `(`, `)`, `[`, `]`,
  // `{`, `}`, `|`, `^`, `$`. For our set the only
  // meaningful escape is `*` (operator `*` vs `**`).
  const escaped = dedup.map((op) => {
    return op.replace(/[\\.+*?()[\]{}|^$]/g, "\\$&");
  });
  return new RegExp(escaped.join("|"));
}

const INLINE_OPERATOR_RE = buildInlineOperatorRe();

/**
 * R219 — heuristic inline-code language inference.
 *
 * <p>Inline code never carries a language tag (markdown
 * `` `xxx` `` has no ` ```lang` prefix). But the user often
 * types code that *is* language-specific — `` `def foo():` ``
 * is Python, `` `class Foo {` `` is Java, `` `=>` `` is JS.
 * R218 used a universal tokenizer; R219 first tries to
 * <i>infer</i> the language from strong-shape signals and
 * dispatches to the per-lang tokenizer when confident.
 *
 * <p>Rules are ordered by specificity (most specific first):
 *
 * <ol>
 *   <li><b>Java</b> — <code>public|private|protected</code> followed
 *       by a type keyword (<code>void</code> / <code>int</code> /
 *       <code>String</code> / <code>static</code> / …) is a
 *       near-perfect signal. <code>class Foo {</code> (brace,
 *       no colon) is also Java — Python would use a colon.
 *   <li><b>Python</b> — <code>def name(</code> is a
 *       high-signal Python signature. <code>class Foo:</code>
 *       (colon) is Python. <code>@decorator</code> at the
 *       start is a Python annotation. <code>self</code> /
 *       <code>import x</code> + no braces are also Python
 *       hints.
 *   <li><b>JavaScript / TypeScript</b> — <code>=&gt;</code>
 *       (arrow) is uniquely JS. <code>const</code> /
 *       <code>let</code> / <code>var</code> followed by
 *       <code>=</code> is a JS declaration. <code>function
 *       name(</code> is JS/TS.
 *   <li><b>Bash</b> — <code>$VAR</code> / <code>${VAR}</code>
 *       is the strongest bash signal. <code>[ $foo -gt 0
 *       ]</code> is bash test syntax. Leading <code>$</code>
 *       is a prompt.
 *   <li><b>JSON</b> — leading <code>{</code> followed by
 *       a quoted key is JSON.
 *   <li><b>YAML</b> — <code>key: value</code> at the start
 *       of the input is YAML.
 * </ol>
 *
 * <p>The function is deliberately conservative: if a rule
 * doesn't fire, we return <code>""</code> and the caller
 * falls back to the prior round universal tokenizer. It's better
 * to fall through than to mis-identify a language and apply
 * the wrong highlight.
 *
 * @returns A lang id matching one of the dispatcher
 *          branches in {@link highlightCode} (e.g.
 *          <code>"python"</code>, <code>"java"</code>,
 *          <code>"javascript"</code>, <code>"bash"</code>,
 *          <code>"json"</code>, <code>"yaml"</code>), or
 *          <code>""</code> when no rule fires.
 */
export function inferInlineLang(src: string): string {
  if (!src) return "";

  // 1. Java — strongest signals first.
  //    `public|private|protected` followed by `void|int|long|String|...`
  //    is a Java method signature. We require the type to
  //    be one of the Java primitive / reference types so
  //    prose like "public trust" doesn't false-positive.
  if (/\b(public|private|protected)\s+(static\s+)?(void|int|long|String|boolean|float|double|byte|char|short)\s+[A-Za-z_]/.test(src)) {
    return "java";
  }
  // `class Foo {` (brace) is Java; `class Foo:` (colon) is Python.
  if (/\bclass\s+[A-Z]\w*\s*\{/.test(src)) return "java";
  // `new Foo(` — Java/Kotlin/Scala constructor call.
  if (/\bnew\s+[A-Z]\w*\s*\(/.test(src)) return "java";

  // 2. Python — `def name(` is the strongest single signal.
  if (/\bdef\s+[A-Za-z_]\w*\s*\(/.test(src)) return "python";
  // `class Foo:` (colon, not brace).
  if (/\bclass\s+[A-Z]\w*\s*:/.test(src)) return "python";
  // `@decorator` at the start of a line.
  if (/(?:^|\s)@\w+/.test(src)) return "python";
  // `self` as a method receiver is a strong Python signal
  // (JS uses `this`).
  if (/\bself\b/.test(src)) return "python";
  // `import x` + no braces (Python doesn't use `{}` for blocks).
  if (/\bimport\s+\w+(\s+as\s+\w+)?/.test(src) && !/[{;]/.test(src)) return "python";

  // 3. JavaScript / TypeScript.
  //    `=>` is uniquely JS arrow-function syntax.
  if (/=>/.test(src)) return "javascript";
  // `function name(` — JS/TS function declaration.
  if (/\bfunction\s+[A-Za-z_]\w*\s*\(/.test(src)) return "javascript";
  // `const|let|var name = ...` — JS/TS variable declaration.
  //    We require the `=` to follow the name so prose like
  //    "let me think" doesn't false-positive.
  if (/\b(const|let|var)\s+[A-Za-z_]\w*\s*=/.test(src)) return "javascript";
  // `require(...)` or `import x from "..."` is JS module syntax.
  if (/\brequire\s*\(/.test(src)) return "javascript";
  if (/\bimport\s+.*\s+from\s+["']/.test(src)) return "javascript";

  // 4. Bash.
  //    `$VAR` / `${VAR}` is the strongest bash signal.
  if (/\$\{?\w+\}?/.test(src)) return "bash";
  // `[ $foo -gt 0 ]` is bash test syntax.
  if (/\[\s+\$?\w+/.test(src)) return "bash";
  // Leading `$` (the bash prompt).
  if (/^\s*\$\s/.test(src)) return "bash";
  // Common bash commands.
  if (/\b(echo|cd|ls|grep|find|awk|sed|cat|mkdir|rm|mv|cp|chmod|curl|wget|export|alias|source|set|unset)\s+/.test(src)) {
    return "bash";
  }

  // 5. JSON.
  if (/^\s*\{.*["']\w+["']\s*:/.test(src)) return "json";

  // 6. YAML.
  if (/^[A-Za-z_][\w-]*\s*:\s*\S+/.test(src)) return "yaml";

  // No rule fired — fall through to the universal
  // tokenizer. Better to render as a generic pill than to
  // mis-identify a language.
  return "";
}

/**
 * R219 — `highlightInlineCode` now does a two-stage
 * dispatch: try `inferInlineLang` first and use the
 * per-lang tokenizer when confident, fall back to the
 * R218 universal tokenizer when no rule fires.
 *
 * <p>The R218 universal path is preserved verbatim — any
 * input that doesn't match an inference rule still gets
 * the "string / number / keyword (50+ union) / operator"
 * treatment from R218.
 */
export function highlightInlineCode(src: string): Token[] {
  if (!src) return [];
  // try to infer the language and dispatch to the
  // per-lang tokenizer. This gives more accurate token
  // classification than the universal fallback (e.g.
  // `def foo():` correctly gets Python `self` builtin
  // recognition; `=>` correctly gets JS arrow-function
  // treatment).
  const lang = inferInlineLang(src);
  if (lang) {
    return highlightCode(src, lang);
  }
  // universal fallback. The keyword regex is
  // built per-call (cheap; ~50 keywords) so the inferred
  // path doesn't have to worry about ordering.
  const keywordRe = new RegExp("\\b(?:" + INLINE_KEYWORDS.join("|") + ")\\b");
  return tokenize(src, [
    // Strings: single / double / template quotes. Same
    // order as the lang-specific tokenizers (string
    // before keyword) so `"const"` is a string, not a
    // keyword.
    { kind: "string", re: /"(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)*'|`(?:[^`\\\n]|\\.)*`/ },
    // Numbers: int / float / hex / scientific.
    { kind: "number", re: /\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b|0x[0-9a-fA-F]+\b/ },
    // Keywords (universal union).
    { kind: "keyword", re: keywordRe },
    // Operators: built once at module load (avoids
    // rebuilding the regex on every call).
    { kind: "operator", re: INLINE_OPERATOR_RE },
  ]);
}

/** Map a token kind to a theme colour name. Pure — no
 *  side effects, so the function is trivially testable. */
export function tokenKindToColor(kind: TokenKind): string {
  switch (kind) {
    case "keyword":  return "tokKeyword";
    case "string":   return "tokString";
    case "number":   return "tokNumber";
    case "comment":  return "tokComment";
    case "builtin":  return "tokBuiltin";
    case "operator": return "tokOperator";
    case "plain":
    default:         return "code";
  }
}
