# R-radar-4: 加 `google_scholar` tool (Semantic Scholar backend)

**Round**: R-radar-4  
**Date**: 2026-09-12  
**Module**: `aethercode/aethercode-tools/src/main/java/org/aethercode/tools/net/`  
**Goal**: 加一个学术论文搜索 tool, 让 agent 能查 paper 信息 (title / authors / year / abstract / ArXiv id / DOI / url / citation count), 用于跨 reference/papers/ 的研究循环。

## TL;DR

- 新增 `GoogleScholarClient.java` (8.8 KB) — Semantic Scholar Graph API 的 Java 21 wrapper
- 新增 `ScholarSearchTool.java` (5.2 KB) — `google_scholar` tool, 注册到 `StandardTools.all()`
- 新增 `GoogleScholarClientTest.java` (17 tests, 13.3 KB) — 用 in-JVM `HttpServer` 测 HTTP 路径 + 纯函数测 toRecord
- 新增 `ScholarSearchToolTest.java` (6 tests, 2.5 KB) — tool 表面
- 改 `StandardTools.java` (1 行) — register scholar_search
- 修 2 个文件 BOM (BashTool.java + BackpressureException.java) — 不修 module 编译不过
- **aethercode-tools: 297/299 (2 个 pre-existing failure 跟本 round 无关)**

## 关键决定 (8 条)

1. **不用 Google Scholar, 用 Semantic Scholar** — Google Scholar 没有 public API, 抓 web UI 违反 ToS 且不稳定。Semantic Scholar Graph API 是免费的, 50 req / 5 min 匿名, 100 req / min with `SEMANTIC_SCHOLAR_API_KEY`, 完美替代
2. **tool name 仍是 `google_scholar`** — model 期望 "google_scholar", 这是 LLM 训练时熟悉的概念。Backend 切换是 implementation detail, doc 明确说明
3. **test seam 是 3-arg 构造器, 不用 reflection** — Java 17+ 禁止改 `Field.modifiers`, 用 `new GoogleScholarClient(apiKey, endpoint)` 注入测试 endpoint, in-JVM `HttpServer` 接收请求。比 reflection 干净 10 倍
4. **toRecord flatten 策略** — authors 用 `", "` join, externalIds flatten 成 `externalIds.ArXiv` / `externalIds.DOI` dotted keys。这样 `Map<String, String>` 保持简单, 不嵌套, model 容易 read
5. **num clamp [1, 50]** — Brave / Serper 是 [1, 20], Scholar 给到 50 因为学术搜索的 multi-result use case 更常见。50 仍是安全 cap, 不会让 agent 误传 1000
6. **SEMANTIC_SCHOLAR_API_KEY optional** — 不像 Brave/Serper 必须有 key, anonymous 也能用 (50 req/5min)。这样默认 deployment 不用配 key 也能跑
7. **lookupByExternalId 支持 DOI/ArXiv/MAG/PMID** — 不只是 search, 还能通过外部 id 直接 lookup 单个 paper。给定 ArXiv id 就拿到 paper metadata, 跟 reference/papers/ 的 file naming 完美对齐
8. **2 个 BOM 文件顺手修** — 之前 round `BashTool.java` 和 `BackpressureException.java` 开头有 UTF-8 BOM (`EF BB BF`), 编译器 fail。 本 round 改了 StandardTools.java 触发 module 重新编译, 暴露出这个 latent bug。 一起 strip 掉, 不修 module 编译不过

## API 设计

```java
// Client
public class GoogleScholarClient {
    public GoogleScholarClient();                              // 默认 endpoint, 匿名
    public GoogleScholarClient(String apiKey);                 // 默认 endpoint + 可选 key
    public GoogleScholarClient(String apiKey, String endpoint); // 测试 seam

    public List<Map<String, String>> search(String query, int num);          // num clamp [1, 50]
    public Map<String, String> lookupByExternalId(String idType, String idValue); // 返回 null 当 404

    static Map<String, String> toRecord(JsonNode paper);  // package-private, 测 parse
}

// Tool
public class ScholarSearchTool {
    public static final String NAME = "google_scholar";  // 名字是 google_scholar, backend 是 Semantic Scholar

    public static Tool build();  // 注册到 aethercode-core Tool
    public static ToolResult call(Map<String, Object> input, Tool.CallContext ctx);
    public static boolean isReadOnly(Map<String, Object> input);
}
```

## Tool schema

```json
{
  "type": "object",
  "required": ["query"],
  "properties": {
    "query": {"type": "string", "description": "Search query: title words, author name, topic, ArXiv id, or DOI."},
    "num_results": {"type": "integer", "description": "Optional cap on number of results. Default 5. Max 50."}
  }
}
```

## 输出格式 (tool call 返回)

```
scholar results (query="agent memory", 1 of cap 5):

1. Memory in the Age of AI Agents
   Alice, Bob (2025) — arXiv preprint
   A comprehensive survey of agent memory mechanisms across LLM-based systems.
   arXiv:2512.13564v2
   DOI:10.x/y
   https://www.semanticscholar.org/paper/abc123
   cited by 42 paper(s)
```

## 测试 (23 tests, 0 失败)

### GoogleScholarClientTest (17 tests)
- **search 路径** (5):
  - `searchBuildsUrlAndReturnsPapers` — 验证 URL 编码 + fields + auth header
  - `searchClampsNumToFifty` — num > 50 clamp 到 50
  - `searchClampsNumToOne` — num < 1 clamp 到 1
  - `searchWithoutApiKeyOmitsHeader` — null apiKey 不发 x-api-key
  - `searchHandlesHttpError` — HTTP 500 → RuntimeException with status code
- **query validation** (1):
  - `searchRejectsBlankQuery` — null / "" / "   " 都 throw IllegalArgumentException
- **lookup 路径** (3):
  - `lookupByExternalIdReturnsRecordOnSuccess` — ArXiv id → paper metadata
  - `lookupByExternalIdReturns404AsNull` — 404 → null (per contract)
  - `lookupByExternalIdRejectsBlankArgs` — null/empty idType 或 idValue
- **toRecord pure 函数** (5):
  - `toRecordFlattensAuthors` — 多个 author join `, `
  - `toRecordFlattensExternalIds` — DOI/ArXiv/MAG flatten 成 dotted key
  - `toRecordOmitsNullAndMissing` — null/empty 不进 map
  - `toRecordEmptyAuthorsProducesNoKey` — 空数组 → 没 "authors" key
  - `toRecordEmptyExternalIdsProducesNoKeys` — 空 map → 没 "externalIds.*" keys
- **constructors** (3):
  - `noArgConstructorIsAllowed` — 默认 anonymous
  - `blankEndpointFallsBackToDefault` — 空 endpoint 字符串 fallback
  - `blankApiKeyIsTreatedAsMissing` — 空白 apiKey 不发 header

### ScholarSearchToolTest (6 tests)
- `emptyQueryReturnsError` — 空 query → error
- `missingQueryReturnsError` — 缺 query → error
- `blankQueryReturnsError` — 空白 query → error
- `toolNameIsGoogleScholar` — name constant
- `toolIsReadOnly` — isReadOnly true
- `toolIsRegisteredInStandardTools` — StandardTools.all() 包含

## 与 R-radar-5 的关系

R-radar-5 是 "论文搜索走 WebFetchTool", 即 agent 拿到 ArXiv url 后, 用 `web_fetch` 抓 PDF / abstract。这跟 R-radar-4 是串联关系:
1. `google_scholar` 找 paper 列表 (R-radar-4)
2. 拿到 ArXiv url
3. `web_fetch` 抓具体页面 (R-radar-5)
4. 解析 / 摘要 / 引用

## 跨 Round 影响

- **R-radar-5 (WebFetchTool)**: 现有 `web_fetch` 已经支持, R-radar-5 主要做的是 wrap 一个 `arxiv_fetch` 工具, 把 ArXiv url 自动转 abstract URL
- **R-radar-6 (V 校验器)**: 论文 2601.01743 提到 V (校验器) 可以是 LLM-as-judge。R-radar-4 给的 paper metadata 可以作为校验器 prompt 的 context
- **R-radar-7 (Self-correction)**: agent 用 scholar 找 paper, 发现 cited by 0 → 自己写一个
- **R-radar-8 (Multi-Agent)**: 一个 agent 搜, 另一个 agent 评判, 第三个 agent 综合

## 教训 (新增 8 条, 累计 162+)

155. **🆕 不用 Google Scholar, 用 Semantic Scholar** — Google Scholar 没 public API, 抓 web UI 违反 ToS。Semantic Scholar 是免费 + structured + 有 API key 提升 limit 的最佳替代
156. **🆕 tool name 跟 backend 解耦** — `google_scholar` 是用户/LLM 熟悉的概念, 跟 backend 实现解耦。doc 写清楚 "backend is Semantic Scholar", 后续可以换实现不影响 model 调用
157. **🆕 test seam 用 3-arg 构造器, 不用 reflection** — Java 17+ 禁止改 `Field.modifiers`。用 `new Client(apiKey, endpoint)` 注入测试 endpoint, in-JVM `HttpServer` 接收请求, 比 reflection 干净
158. **🆕 in-JVM HttpServer 测 HTTP 路径** — `com.sun.net.httpserver.HttpServer` 是 JDK built-in, 不用 mockwebserver 依赖。0 依赖, 0 网络, 端到端测 URL 编码 + auth header + response parsing
159. **🆕 Map<String, String> flatten 策略** — authors `", "` join, externalIds dotted key (`externalIds.ArXiv`)。保持 map 简单, 不嵌套, model 容易 read
160. **🆕 URLEncoder 行为** — `URLEncoder.encode("foo bar")` → `foo+bar` (form encoding), 不是 `foo%20bar`。OkHttp 接受两种, 但 assertion 要测 `q=agent+memory` 而不是 `q=agent%20memory`
161. **🆕 BOM 文件 隐藏 bug** — 之前 round 的 `BashTool.java` 和 `BackpressureException.java` 开头有 UTF-8 BOM, 但 module 没改所以没暴露。本 round 改 StandardTools 触发 re-compile, 编译器 fail。strip BOM 顺手修
162. **🆕 clamp 边界要测两端** — `clamp(num, 1, 50)` 测 num=0 (clamp 到 1), num=999 (clamp 到 50), num=5 (不动)。一个 assert 测边界, 三个 assert 测全

## 后续 (R-radar-5+)

- **R-radar-5**: 论文搜索走 WebFetchTool, 集成 `arxiv_fetch` tool
- **R-radar-6**: V 校验器框架
- **R-radar-7**: Self-correction
- **R-radar-8**: Multi-Agent 对抗
- **可能后续 round**: 加 `crossref` / `openalex` / `pubmed` search 作为 alternative backends, 跟 `semantic_scholar` 平级切换
