# R-radar-5: 论文搜索走 WebFetchTool (arxiv_fetch tool)

**Round**: R-radar-5  
**Date**: 2026-09-12  
**Module**: `aethercode/aethercode-tools/src/main/java/org/aethercode/tools/net/`  
**Goal**: 给 agent 一个 `arxiv_fetch` tool, 让它能根据 arXiv id 拿到 paper 的 abstract / metadata, 配合 R-radar-4 的 `google_scholar` 形成"找 paper → 抓 paper"完整循环。

## TL;DR

- 新增 `ArxivFetchTool.java` (8.3 KB) — `arxiv_fetch` tool, 接受 arxiv_id 走 abstract 页面或 API
- 新增 `ArxivAtomParser.java` (6.3 KB) — 解析 arXiv Atom XML response, 输出 Markdown 块
- 新增 `ArxivFetchToolTest.java` (25 tests, 8.4 KB) — id normalize / version strip / parser / tool 表面
- 改 `StandardTools.java` (1 行) — register arxiv_fetch
- **aethercode-tools: 322/324 tests (2 pre-existing 跟本 round 无关)**
- 跟 R-radar-4 的 `google_scholar` 串联, 形成 paper research loop

## 关键决定 (7 条)

1. **新 tool 名 `arxiv_fetch`, 不复用 `web_fetch`** — 三个理由: (a) id 多种形式 (`2512.13564` / `2512.13564v2` / `arXiv:2512.13564` / 完整 URL), 需要 normalize; (b) arXiv API 返回 Atom XML, 需要专门 parser; (c) model 不用记 arxiv URL path (`/abs/` vs `/pdf/` vs `/api/query`)
2. **2 种 response mode** — `format=html` (default) 走 abstract page, delegate 给 WebFetchTool; `format=api` 走 arXiv Atom API 返回结构化 fields (title / authors / abstract / DOI / primary_category / published)
3. **API mode 自动 strip version** — `2512.13564v2` → API query `id_list=2512.13564`. arXiv API 把每个 version 当独立 doc, 一对多 model 几乎 always 想要 latest
4. **Atom XML 用 regex 解析, 不引 JAXB/StAX** — arXiv API 字段固定, regex 足够; 引 XML 解析库加 dependency 不值得。XML entities (`&amp;` / `&lt;` / `&gt;` / `&quot;` / `&apos;`) 手写 decode
5. **HTML mode 复用 WebFetchTool.call()** — 直接 delegate, 不重复 OkHttp client / SSRF guard / HTML strip 逻辑。WebFetchTool 已有 30K char cap, 足够装 abstract
6. **id normalize 严格** — `YYMM.NNNNN[vN]` shape only, 4-5 digit paper number, 'v' 必须后接 digit。其他格式返回 null + clear error message
7. **in-JVM HttpServer 测 HTTP 路径需要 WebFetchTool mock** — 这次 R-radar-5 不直接测 HTTP, 而是测 normalize / stripVersion / parser。HTTP 路径已经被 WebFetchToolTest 覆盖, 集成靠 delegation

## 跟 R-radar-4 的串联

```
┌──────────────────────┐
│ google_scholar       │   R-radar-4
│  (search tool)       │
└──────────────────────┘
          │
          │ 返回 list of papers, 每个带 externalIds.ArXiv = "2512.13564v2"
          │
          ▼
┌──────────────────────┐
│ arxiv_fetch          │   R-radar-5
│  arxiv_id=2512.13564v2 │
│  format=api          │   → 走 export.arxiv.org API
└──────────────────────┘
          │
          │ 返回:
          │   title: Memory in the Age of AI Agents
          │   authors: Alice, Bob
          │   id: https://arxiv.org/abs/2512.13564v2
          │   published: 2025-12-15
          │   primary_category: cs.AI
          │   doi: 10.x/y
          │   abstract: A comprehensive survey...
          │
          ▼
┌──────────────────────┐
│ (LLM 处理 / 摘要 / 引用) │
└──────────────────────┘
```

## 跟现有 reference/papers/ 对齐

`aethercode/reference/papers/` 已经存了 8 篇 paper (3 种形式 × 8 paper = 24 文件), 文件名是 `2512.13564v2-memory-in-the-age-of-ai-agents.pdf`。本 round 提供的 tool 跟这个命名一致 — 给一个 id, 直接拿到 paper 完整信息。

后续可以加的 (R-radar-6/7/8 期间):
- `read_paper` tool — 读取本地 `reference/papers/<id>-*.pdf` 文件, 跟 R-radar-4/5 的 fetch tool 配合
- `cite_paper` tool — 把 paper 信息格式化成 BibTeX

## 测试 (25 tests, 0 失败)

### ArxivFetchToolTest (25 tests)
- **normalizeId** (10):
  - `normalizeIdAcceptsBareId` / `normalizeIdAcceptsVersionedId`
  - `normalizeIdStripsArxivPrefix` / `normalizeIdExtractsFromAbsUrl`
  - `normalizeIdLowercases`
  - `normalizeIdRejectsNull` / `normalizeIdRejectsBlank` / `normalizeIdRejectsMissingDot`
  - `normalizeIdRejectsBadNumberPart` (too few / too many / non-numeric digits)
  - `normalizeIdRejectsNonDigitVersion`
- **stripVersion** (2):
  - `stripVersionReturnsInputWhenNoVersion`
  - `stripVersionStripsValidVersionSuffix`
- **Tool surface** (7):
  - `toolNameIsArxivFetch`
  - `toolIsReadOnly`
  - `emptyArxivIdReturnsError` / `missingArxivIdReturnsError` / `malformedArxivIdReturnsError`
  - `invalidFormatReturnsError`
  - `toolIsRegisteredInStandardTools`
- **ArxivAtomParser** (5):
  - `parserExtractsAllFields` — title/authors/abstract/DOI/primary_category/published/id
  - `parserDecodesXmlEntities` — `&amp;` / `&lt;` / `&gt;` / `&quot;` / `&apos;`
  - `parserReturnsNullOnEmptyBody` / `parserReturnsNullOnUnrecognizedBody`
  - `parserCollapsesWhitespaceInAbstract`

## Tool schema

```json
{
  "type": "object",
  "required": ["arxiv_id"],
  "properties": {
    "arxiv_id": {
      "type": "string",
      "description": "arXiv paper id. Accepts bare id (2512.13564), versioned (2512.13564v2), arXiv: prefix, or a full arxiv.org/abs/ URL."
    },
    "format": {
      "type": "string",
      "description": "Optional response format. Default 'html' (abstract page text). Set 'api' to get structured Atom XML fields (title/authors/abstract/DOI)."
    }
  }
}
```

## 教训 (新增 6 条, 累计 168+)

163. **🆕 复用 WebFetchTool 而不是 fork** — ArxivFetchTool 调 WebFetchTool.call() 而不是自己写 OkHttp client / SSRF guard / HTML strip。WebFetchTool 已经是 single source of truth for HTTP fetch + 30K cap + SSRF guard
164. **🆕 Atom XML regex 足够, 不引 XML 解析库** — arXiv API 字段固定, regex 几个 pattern 就够。引 JAXB / StAX 加 200KB+ dependency, 不值
165. **🆕 XML entity 手写 decode, 5 个就够** — `&amp;` / `&lt;` / `&gt;` / `&quot;` / `&apos;` 5 个, 再加 `&#NN;` 数字 reference。不引通用 XML decoder
166. **🆕 attribute 也要 match** — `<arxiv:doi xmlns:arxiv="...">10.x/y</arxiv:doi>`, regex 不能写 `<arxiv:doi>`, 要 `<arxiv:doi[^>]*>`. 同样 PRIMARY_CATEGORY 已经这么写了, DOI 我第一版漏掉
167. **🆕 arXiv API 看待 version 是独立 doc** — id_list 给 `2512.13564` 跟 `2512.13564v2` 返回不同 entry。one-shot lookup 几乎 always 想要 latest, 所以 strip version
168. **🆕 tool 串接 (scholar → arxiv)** — 一个 tool 的输出是另一个 tool 的输入, 设计 schema 时要让 model 能 strip 需要的部分。R-radar-4 输出的 `externalIds.ArXiv` 直接喂 R-radar-5 的 `arxiv_id` 即可

## 后续 (R-radar-6+)

- **R-radar-6**: V 校验器框架, 可以用 R-radar-5 的 paper metadata 作为校验器 prompt context
- **R-radar-7**: Self-correction 机制
- **R-radar-8**: Multi-Agent 对抗, 多个 agent 找 paper / 评 paper / 总结
- **可能后续 round**: `read_paper` tool (读本地 `reference/papers/` PDF) + `cite_paper` tool (输出 BibTeX)
