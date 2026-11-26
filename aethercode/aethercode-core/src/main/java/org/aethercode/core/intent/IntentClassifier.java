/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.aethercode.core.intent;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * R401: decide what KIND of turn a user prompt is, BEFORE the model sees
 * it, so the engine can pick a posture (a
 * {@link org.aethercode.core.profile.Profile}) instead of answering
 * every prompt with the same full-tool, code-shaped agent.
 *
 * <h2>Why this exists</h2>
 * <p>The user reported a prompt that was plainly a question —
 * {@code "AI Agent 中 checkpoint 和 hook 的关系，及如何记录代码是 human
 * 生成的，还是 ai 生成的 …"} — and the engine answered it by generating a
 * pile of unrelated source files. The model had no idea what the prompt
 * was <em>for</em>: the system prompt describes what the agent IS, never
 * what THIS turn is about. An agent told "you are a coding assistant"
 * will write code when handed an ambiguous prompt, and a concept
 * question looks exactly like a feature request to it.
 *
 * <h2>Why deterministic and not a model call</h2>
 * <p>An LLM classifier would cost a round-trip on EVERY prompt and
 * would be non-deterministic (the same prompt can classify differently
 * on two runs). The taxonomy here is small and the signals are lexical,
 * so a rule table is both cheaper and reproducible — and a reproducible
 * verdict is one the UI can display and the user can override.
 *
 * <p>See {@code docs/R400-cancel-intent-checkpoint-design.md} §2 for the
 * design and §2.4.2 for the ordering this class implements.
 *
 * <h2>The safety property</h2>
 * <p>The expensive failure mode is the opposite of the one this class
 * was written for: a QUESTION classified as code work makes the agent
 * write files nobody asked for, and the user cannot undo "a pile of
 * unrelated source files" easily. So the classifier holds ONE hard
 * invariant:
 *
 * <blockquote><b>A question-shaped prompt is never classified as
 * {@link Kind#CODE_CHANGE} unless it carries an explicit
 * implementation verb.</b></blockquote>
 *
 * <p>And the invariant is enforced in the STRICTEST way, because the
 * loose verb list contains "实现" — which also appears inside
 * "实现原理" ("the principle behind the implementation"). Two extra
 * guards keep that from happening:
 *
 * <ul>
 *   <li>a <b>concept blocker</b> ("原理", "机制", "关系", "是什么", …)
 *       means the user wants an EXPLANATION, so a question carrying one
 *       is never promoted to code work;</li>
 *   <li>a question may only be promoted to code work by a
 *       <b>verb + object</b> compound ("实现一个", "write a", …), never
 *       by a bare verb.</li>
 * </ul>
 *
 * <p>Everything else is allowed to be wrong occasionally — a misfire
 * costs a wasted turn — but a misfire on the write path costs the user
 * their working tree. That asymmetry is also why the UNCERTAIN verdict
 * maps to a READ-ONLY profile: the default posture must be the safe
 * one.
 *
 * <h2>Ordering</h2>
 * <p>The rules are evaluated in a fixed order and the FIRST match wins.
 * The order is not arbitrary: failure symptoms ({@link Kind#DIAGNOSE})
 * are checked before the generic question rule, because "为什么报错" is
 * both a question AND a diagnosis, and the diagnosis is the more
 * specific answer. The order is pinned by
 * {@code IntentClassifierR401Test}.
 *
 * <h2>What this class does NOT own</h2>
 * <p>The per-turn prompt text. That belongs to the profile
 * ({@code ProfileRegistry}), because the profile is what the UI shows
 * and what the user overrides — two copies of the same wording would
 * drift.
 */
public final class IntentClassifier {

    private IntentClassifier() {}

    // ------------------------------------------------------------------
    // Taxonomy
    // ------------------------------------------------------------------

    /**
     * The kinds of task the classifier can name. Each one carries a
     * human label (what the UI shows) and a stable wire token (what
     * the renderer matches on).
     */
    public enum Kind {
        /** write / modify code. */
        CODE_CHANGE("代码生成", "code_change"),
        /** find out WHY something is broken. */
        DIAGNOSE("问题定位", "diagnose"),
        /** look up EXTERNAL material and answer from it. */
        RESEARCH("概念搜索", "research"),
        /** read code and report findings; do not rewrite it. */
        REVIEW("代码审查", "review"),
        /** build / run / deploy / verify. */
        OPS("构建运维", "ops"),
        /** answer a question from THIS repository. */
        ANSWER("仓库答疑", "answer"),
        /** no usable signal — do not steer, stay read-only. */
        UNCERTAIN("未识别", "uncertain");

        private final String label;
        private final String token;

        Kind(String label, String token) {
            this.label = label;
            this.token = token;
        }

        /** the human-facing label (Chinese — the UI is Chinese). */
        public String label() { return label; }

        /** the stable wire token. Lower-case, snake_case. */
        public String token() { return token; }

        /** parse a wire token back into a kind. Returns null when the
         * token is unknown — callers must treat an unknown token as
         * "no override", never as a silent default that overrides the
         * classifier's own verdict. */
        public static Kind fromToken(String token) {
            if (token == null) return null;
            String t = token.trim().toLowerCase(Locale.ROOT);
            for (Kind k : values()) {
                if (k.token.equals(t)) return k;
            }
            return null;
        }
    }

    /**
     * The verdict: a kind, the signal that produced it, a human-readable
     * reason (shown in the UI so the user can see WHY the agent decided
     * what it decided), and — R411 — the CAPABILITY axis.
     *
     * <h2>★ TWO AXES, NOT ONE ★</h2>
     * <p>{@link #kind()} is the DOMAIN axis: "what KIND of task is this?"
     * (code, diagnosis, research, …). It answers "how should the agent
     * approach this?" and it is what picks the posture's wording.
     *
     * <p>{@link #requestsWorkspaceChange()} is the CAPABILITY axis: "does
     * the user ask for something in the working tree to CHANGE or to be
     * WRITTEN?" It answers "may this turn touch the disk?" and it is what
     * decides whether the write channel is handed over.
     *
     * <p>★ WHY THEY MUST BE SEPARATE ★ R408 measured the cost of
     * pretending they are the same thing: "write a poem about autumn and
     * save it to poem.txt" is not code work, so the domain axis said
     * UNCERTAIN, so the read-only profile was chosen, so the write tool
     * was PRUNED — and the user's file was never written. The product
     * (a poem) had nothing to do with the requirement (a file on disk).
     * A domain table cannot answer "may I write?", and a write-capability
     * table cannot answer "what kind of work is this?".
     */
    public record Intent(Kind kind, String reason, boolean requestsWorkspaceChange) {

        /** the two-argument form — a verdict with no capability claim. */
        public Intent(Kind kind, String reason) {
            this(kind, reason, false);
        }

        public Intent {
            if (kind == null) kind = Kind.UNCERTAIN;
            if (reason == null) reason = "";
        }

        /**
         * ★ THE CAPABILITY AXIS ★ "the user asks for the working tree to
         * change". The domain axis says what KIND of work this is; this
         * says whether the answer has to land ON DISK.
         *
         * <p>It is deliberately NOT derived from {@link #kind()}: a poem
         * is not code, and a saved SQL fix is not code either, but both
         * have to be written to a file.
         */
        public boolean requestsWorkspaceChange() {
            return requestsWorkspaceChange;
        }

        /** the stable wire token. */
        public String token() { return kind.token(); }

        /** the human-facing label. */
        public String label() { return kind.label(); }

        /** is this a verdict the classifier is confident about? The
         *  UNCERTAIN kind is the only one that means "no signal" —
         *  it still maps to a profile (the read-only navigator), but
         *  the reason it carries is "no intent signal", not a
         *  positive match. */
        public boolean isConfident() { return kind != Kind.UNCERTAIN; }

        public static Intent uncertain(String reason) {
            return new Intent(Kind.UNCERTAIN, reason);
        }
    }

    // ------------------------------------------------------------------
    // Signal tables
    // ------------------------------------------------------------------

    /**
     * Explicit requests for EXTERNAL material. This is the strongest
     * signal there is: the user literally named the tool.
     */
    private static final List<String> RESEARCH_MARKERS = List.of(
            "web search", "web_search", "web fetch", "web_fetch",
            "搜索引擎", "联网", "网上查", "查资料", "查找资料", "搜资料",
            "搜一下资料", "查一下资料", "google", "bing", "duckduckgo",
            "官方文档", "参考文档", "官方文档站", "最新版本", "release notes",
            "github", "stackoverflow", "stack overflow", "arxiv", "论文",
            "博客", "blog", "调研", "综述", "业界", "有哪些开源", "开源项目"
    );

    /**
     * Failure symptoms. Checked BEFORE the generic question rule: "为什么
     * 报错" is a question AND a diagnosis, and the diagnosis is the
     * more specific verdict.
     */
    private static final List<String> DIAGNOSE_MARKERS = List.of(
            "报错", "异常", "失败", "不生效", "不起作用", "没反应", "无反应",
            "崩溃", "卡住", "卡死", "排查", "定位一下", "定位问题", "复现",
            "出了问题", "出错了", "为什么错", "哪里错", "nullpointer",
            "null pointer", "stacktrace", "stack trace", "traceback",
            "exception", "segfault", "panic:"
    );

    /** Requests to read code and report on it. */
    private static final List<String> REVIEW_MARKERS = List.of(
            "code review", "review this", "审查", "评审", "代码质量",
            "有没有问题", "潜在风险", "重构建议", "帮我看看这个改动",
            "看一下这个 diff", "看看这个 patch", "评估一下这个实现"
    );

    /** Build / run / deploy / verify requests. */
    private static final List<String> OPS_MARKERS = List.of(
            "跑一下", "跑一遍", "运行一下", "编译一下", "构建一下", "打包一下",
            "部署一下", "发布一下", "测试一下", "验证一下", "起个服务",
            "mvn ", "gradle ", "npm run", "pnpm ", "docker "
    );

    /**
     * Explicit implementation verbs. These may promote a NON-question
     * prompt to {@link Kind#CODE_CHANGE}. They may NOT promote a
     * question — that needs {@link #STRICT_CODE_VERBS}.
     *
     * <p>★ WHY THE ARTICLE IS PART OF THE VERB ★
     * Some English verbs collide with nouns: "the create method",
     * "the write path", "the build tool", "the add operation". A bare
     * "create" would fire on all of them, so those verbs are listed as
     * <b>verb + determiner</b> ("create a", "create an") instead. The
     * determiner is what makes the phrase an instruction.
     *
     * <p>★ R404: "THE" IS A DETERMINER TOO ★
     * The list had only the INDEFINITE forms ("create a", "create an"),
     * so "create the file X" / "write the file X" / "add the test" fell
     * through to {@link Kind#UNCERTAIN} ⇒ the read-only
     * {@code navigator} profile ⇒ {@code file_write} pruned out of the
     * tool pool ⇒ the model answered "unknown tool: file_write" and
     * then routed around it through the shell.
     *
     * <p>Measured on the live daemon (R403): the same prompt with "a"
     * wrote the file; the same prompt with "the" could not. The
     * determiner carries the same disambiguating power, and the noun
     * collisions above are UNAFFECTED — "the create method" does not
     * contain "create the", because the determiner has to FOLLOW the
     * verb.
     *
     * <p>★ WHY BARE "update" IS DELIBERATELY ABSENT ★
     * "give me a status update" is a noun phrase, and a bare "update"
     * would read it as an instruction. "update the README" is not
     * ambiguous, so only the determiner form is listed.
     *
     * <p>★ R405: WHY BARE "implement" IS GONE FROM HERE ★
     * "implement" was the only bare verb in this list, and a SUBSTRING
     * match on it fires on "the implementation of LRU" — measured: that
     * phrase was classified CODE_CHANGE, so a concept question was
     * handed the write tools. The determiner forms ("implement a",
     * "implement an", "implement the") disambiguate exactly like their
     * siblings, and the bare form now lives in
     * {@link #AMBIGUOUS_CODE_VERBS}, where it needs the imperative
     * position (or a path-like object) to fire.
     */
    private static final List<String> CODE_VERBS = List.of(
            "实现", "编写", "写一个", "写个", "写一段", "新增", "创建", "生成", "重构",
            "重写", "封装", "提取", "补充", "开发", "搭建", "接入", "集成",
            "加上", "加个", "加一个", "加一段", "添加", "改造", "迁移", "删除", "删掉", "移除",
            "优化", "简化", "完善", "修复", "修一下", "更新", "升级", "补一个",
            "帮我改", "改一下", "改名", "重命名", "清理", "整理", "排序",
            "implement a", "implement an", "implement the",
            "create a", "create an", "create the",
            "add a", "add an", "add the",
            "refactor", "rewrite", "scaffold",
            "generate a", "generate an", "generate the",
            "write a", "write an", "write the",
            "build a", "build an", "build the",
            "update the",
            "delete", "remove",
            "rename", "fix", "optimize", "migrate", "translate", "convert",
            "format", "sort", "support", "adapt"
    );

    /**
     * R405: verbs that are AMBIGUOUS ALONE — the same word is a noun as
     * often as it is a verb ("a status update", "the write path", "an
     * edit", "a change").
     *
     * <p>★ WHY A SEPARATE LIST ★ these cannot live in
     * {@link #CODE_VERBS}: that list is read with a plain
     * {@code String.contains}, so a bare "update" there would fire on
     * "give me a status update". R404's answer was the determiner
     * ("update the"), which covers "update the README" but NOT "Update
     * pom.xml" — and "Update pom.xml" is a request, measured UNCERTAIN
     * ⇒ the read-only {@code navigator} profile ⇒ {@code file_write}
     * pruned ⇒ the same silent failure R404 fixed, one word shorter.
     *
     * <p>★ THE ANSWER IS A POSITION, NOT A WORD ★ the difference
     * between the request and the noun phrase is WHERE the word sits:
     * <ul>
     *   <li>"Update pom.xml" — the verb OPENS the sentence ⇒ an
     *       instruction;</li>
     *   <li>"give me a status update on the migration" — the verb sits
     *       inside a noun phrase ⇒ not an instruction.</li>
     * </ul>
     * So a word from this list fires only when it is a STANDALONE token
     * AND either
     * <ul>
     *   <li>it is in <b>imperative position</b> — the prompt starts with
     *       it, after an optional request prefix ("please", "can you",
     *       "请", "帮我"); or</li>
     *   <li>the prompt carries a <b>path-like object</b> ("Update
     *       pom.xml", "Modify Foo.java") — a noun phrase about a real
     *       file is not how a request is phrased.</li>
     * </ul>
     *
     * <p>★ WHY THE MATCH MUST BE WHOLE-WORD ★ "the implementation of
     * LRU" contains "implement"; a substring match reads it as an
     * instruction. The same holds for "the created file" and "the
     * generated code".
     */
    private static final List<String> AMBIGUOUS_CODE_VERBS = List.of(
            "update", "modify", "change", "edit", "introduce", "bump",
            "adjust", "add", "build", "generate", "create", "write",
            "implement", "replace",
            "修改", "调整", "改成", "换成"
    );

    /**
     * R405: wrappers that keep the imperative reading. "can you update
     * pom.xml?" and "请修改 Foo.java" are REQUESTS; "what changed in
     * pom.xml?" and "should I update the file?" are QUESTIONS.
     *
     * <p>★ THE QUESTION WORDS ARE NOT HERE ★ "should i", "what",
     * "why", "how", "did" belong to the information-question side, and
     * a request prefix that is also a question word would turn
     * "should I update the file?" into code work — which is exactly the
     * pin R404 left behind.
     */
    private static final List<String> REQUEST_PREFIXES = List.of(
            "please ", "pls ", "can you ", "could you ", "would you ",
            "will you ", "just ", "now ", "also ", "then ", "hey ",
            "ok ", "okay ", "help me ", "i need you to ", "i want you to ",
            "let's ", "let us ",
            "请", "帮我", "帮忙", "麻烦", "现在", "我要", "我想让"
    );

    /**
     * Verb + OBJECT compounds — the only things that may promote a
     * QUESTION to code work.
     *
     * <p>The bare verb is not enough, because the bare verb also shows
     * up inside nouns: "实现" inside "实现原理" (the principle behind an
     * implementation), "重构" inside "什么是重构". A question that
     * contains one of these is asking for an EXPLANATION.
     */
    private static final List<String> STRICT_CODE_VERBS = List.of(
            "实现一个", "实现个", "实现新的", "实现下", "写一个", "写个",
            "写一段", "新增一个", "新增个", "添加一个", "添加个", "加一个",
            "加个", "创建一个", "创建个", "生成一个", "生成个", "开发一个",
            "开发个", "搭建一个", "搭建个", "接入一个", "集成一个", "补一个",
            "补充一个", "重构一下", "重构下", "重写一下", "改造一下",
            "迁移一下", "优化一下", "简化一下", "完善一下", "修复一下",
            "帮我实现", "帮我写", "帮我加", "帮我改", "帮我删", "删掉",
            "重命名", "implement a", "implement the", "implement support",
            "create a", "create the", "add a", "add the", "add support",
            "write a", "write the", "build a", "generate a", "scaffold",
            "refactor the", "rewrite the", "delete the", "remove the"
    );

    /**
     * Words that mean the user wants an EXPLANATION of a concept, not
     * an action. A question carrying one of these is never promoted to
     * code work, whatever verb it also contains.
     */
    private static final List<String> CONCEPT_BLOCKERS = List.of(
            "原理", "机制", "关系", "区别", "差别", "是什么", "什么是",
            "为什么", "为何", "意思", "含义", "优缺点", "好处", "坏处",
            "有哪些", "值不值得", "之间的", "之间有",
            "what is", "what are", "what does", "how does", "how do i know",
            "difference", "differences", "meaning", "why", "how it works"
    );

    /**
     * Words that anchor the task to THIS repository. A question with a
     * local anchor is answered from the local code, not from the web.
     */
    private static final List<String> LOCAL_ANCHORS = List.of(
            "这个项目", "该项目", "本项目", "当前项目", "我们项目", "我们的项目",
            "这个仓库", "本仓库", "当前仓库", "这个代码库", "本代码库",
            "这个文件", "该文件", "本文件", "这个类", "该类", "本类",
            "这个方法", "该方法", "这个函数", "该函数", "这个模块", "当前模块",
            "这个接口", "该接口", "这段代码", "这段逻辑", "这里的代码",
            "上面这段", "刚才那段", "本工程", "当前工程", "我们的代码",
            "this repo", "this project", "this file", "this class",
            "this method", "this function", "this module", "the codebase"
    );

    /**
     * R411: demonstratives ("this one", "that one") on their own are NOT
     * a local anchor — "this framework" is a concept question that belongs
     * on the web. A demonstrative pointed at a CONCRETE ARTIFACT is: "this
     * SQL", "that error", "the log file above" all name something that
     * lives in this tree.
     *
     * <p>★ WHY TWO LISTS AND NOT ONE ★ The pairing is the signal. "这条"
     * alone ("this rule") is a concept; "这条 SQL" ("this SQL statement")
     * is a row in someone's database. Firing on the demonstrative alone
     * would send concept questions to the local tree; firing on the
     * artifact alone would fire on every mention of "SQL" in the world.
     * The pair is the smallest rule that is right for both.
     */
    private static final List<String> DEMONSTRATIVES = List.of(
            "这条", "这份", "这批", "这段", "这行",
            "刚才那个", "刚刚那个", "上面那个", "上述那个", "前面那个",
            "这个", "那个");

    /**
     * R411: the artifacts a demonstrative can point at. Deliberately
     * concrete: a file, a statement, a record, a piece of configuration.
     *
     * <p>★ "消息" IS NOT HERE ★ "this message" / "this news item" is a
     * concept question, and treating it as local would send the agent
     * looking for it in the repository instead of asking the web.
     */
    private static final List<String> ANCHORED_ARTIFACTS = List.of(
            "语句", "查询", "代码", "记录", "日志", "报错", "错误", "异常",
            "工具", "脚本", "配置", "字段", "组件", "页面", "服务", "命令",
            "事务", "索引", "表结构", "字段值");

    /**
     * R411 — the CAPABILITY axis, part 1: the request names WHERE its
     * result goes.
     *
     * <p>★ WHY A DIRECTION IS REQUIRED ★ "保存" alone ("save") is also
     * the word in "保存当前进度" and in "怎么保存" — both of which are
     * not a request for a file. Every entry here carries the direction
     * (到 / 成 / 为 / 进 / 盘 / 文件) or the English preposition, so the
     * marker only fires when a DESTINATION is named.
     */
    private static final List<String> PERSIST_MARKERS = List.of(
            "保存到", "存到", "存进", "存成", "存为", "另存",
            "写入", "写进", "落盘", "落到文件",
            "导出到", "输出到", "导出成", "输出成",
            "生成文件", "写成文件", "写入文件", "存文件",
            "save to", "save as", "saved to", "write to", "written to",
            "export to", "output to", "dump to", "persist to", "store in");

    /**
     * R411 — the CAPABILITY axis, part 2: the request asks for an existing
     * thing to CHANGE.
     *
     * <p>It is {@link #CODE_VERBS} plus the verbs that mean "make this
     * better" without naming a language artefact. The two lists are
     * UNIONED rather than copied, because a copy is a list that drifts
     * (R410's Lesson 1552).
     */
    private static final List<String> CHANGE_VERBS = union(CODE_VERBS, List.of(
            "调优", "提速", "写好", "做好", "改好", "修好", "处理好", "优化成",
            "优化好", "改到位", "理顺", "顺一下",
            "tweak", "speed up", "clean up", "improve", "polish"));

    /**
     * R411 — the request hints that the user is ASKING FOR SOMETHING, not
     * asking about it. R405 already had a list of request PREFIXES, but
     * those only work at the START of the prompt ("please …", "帮我 …"),
     * and the sentence that needs this signal puts the hint in the
     * MIDDLE: "这条 SQL 查询很慢，帮我看看怎么优化".
     *
     * <p>★ WHY "请" IS NOT HERE ★ "请" is the first character of "请问",
     * which is a QUESTION word. A list containing "请" would read every
     * polite question as a request, and every polite question would get
     * the write channel.
     */
    /**
     * R413: a DIAGNOSIS that asks for an ACTION — the fix, or a
     * reproduction.
     *
     * <p>★ WHY THIS LIST EXISTS ★ R401 kept the write tools on the
     * {@code debugger} posture, because "这个不生效" usually means "fix
     * it". But R413's live scenario matrix measured what that costs when
     * the user asked for NOTHING but the cause: the turn wrote a
     * reproduction harness into the user's working tree
     * ({@code repro/Repro.java}, {@code out/Repro.class}) — four files
     * nobody asked for, while the verdict's own capability axis said
     * {@code requestsWorkspaceChange=false} all along.
     *
     * <p>So R413 splits the two: a diagnosis that only asks WHERE the
     * problem is gets NO write channel, and a diagnosis that asks for the
     * fix (or for a reproduction) keeps it. THIS LIST is the second half.
     */
    private static final List<String> DIAGNOSE_ACTION_VERBS = List.of(
            "修复", "修一下", "修好", "改好", "改一下", "帮我改", "改成",
            "解决", "搞定", "复现", "重现",
            "跑一下", "跑一遍", "试一下", "试试", "验证一下",
            "fix", "fix it", "repair", "patch");

    private static final List<String> REQUEST_HINTS = List.of(
            "帮我", "帮忙", "麻烦", "替我", "给我", "我要", "我想让", "我想",
            "please", "can you", "could you", "would you", "help me",
            "i need you to", "i want you to", "for me", "go ahead");

    /**
     * R414: WEAK research markers — "查一下 X", "搜一下 X".
     *
     * <h2>★ WHY THIS IS NOT IN {@link #RESEARCH_MARKERS} ★</h2>
     * <p>{@code RESEARCH_MARKERS} is RULE 1: it wins over everything,
     * including the local anchor. And that is right for its entries —
     * "web search", "github", "官方文档" name an EXTERNAL source
     * explicitly. But "查一下 X" does NOT: the user said "look it up",
     * and the question is WHERE — the web or this tree. Putting it in
     * rule 1 would send "查一下这个文件" to the internet for a file that
     * is sitting right there.
     *
     * <p>⇒ The weak marker is resolved by the LOCAL ANCHOR instead:
     * "查一下这个仓库的 X" is a question about this tree (ANSWER), and
     * "查一下 Java 21 的虚拟线程" is a lookup on the web (RESEARCH).
     *
     * <h2>★ MEASURED (R414) ★</h2>
     * <p>Before this list existed, "查一下 Java 21 的虚拟线程" and
     * "搜一下 Spring Boot 4 的新特性" classified as
     * {@link Kind#UNCERTAIN} — the read-only {@code navigator} posture,
     * whose directive says "the shape of this request was not
     * recognised". The user asked for a lookup and got a shrug. The
     * strong markers had never covered the plain "查一下/搜一下"
     * phrasing, which is how Chinese speakers usually ask.
     */
    private static final List<String> WEAK_RESEARCH_MARKERS = List.of(
            "查一下", "查一查", "查查", "搜一下", "搜一搜", "搜搜",
            "搜索一下", "查下", "看下资料",
            "look up", "search for", "find out about");

    /**
     * R414: the Chinese 把-construction.
     *
     * <p>Chinese puts the OBJECT first and the VERB last: "把 X 改成 Y",
     * "把 X 删掉". R405's imperative-position rule looks for the verb at
     * the HEAD of the sentence, so every 把-construction fell through to
     * {@link Kind#UNCERTAIN} — measured: "把 AppConfig 里的
     * MAX_RETRIES 改成 5" and "把这条 SQL 改成参数化的" both landed on
     * the read-only navigator, i.e. the user asked for a code change and
     * the agent was not allowed to make one.
     *
     * <p>★ THE CONSTRUCTION IS THE IMPERATIVE MARKER ★ in English the
     * position of the verb says whether the sentence is an instruction
     * ("Update pom.xml" vs "the updated pom.xml"). In the 把-construction
     * the position says nothing — 把 itself is the marker, and the verb
     * is at the END. So the verb is looked up ANYWHERE after 把.
     *
     * <p>★ ONLY THE CHINESE VERBS ★ the 把-construction is a Chinese
     * grammar, so only the Chinese entries of
     * {@link #AMBIGUOUS_CODE_VERBS} qualify. That keeps an English
     * noun phrase inside a Chinese sentence ("把 status update 的说明
     * 翻译一下") from being read as a code change.
     */
    private static final String BA_CONSTRUCTION = "把";

    /**
     * R414: the verbs a 把-construction may carry.
     *
     * <p>★ WHY THIS IS NOT THE AMBIGUOUS LIST ★
     * {@link #AMBIGUOUS_CODE_VERBS} contains verbs that also form NOUN
     * phrases ("修改记录" = the change log, "调整方案" = the adjustment
     * plan). In a 把-construction those noun phrases are the OBJECT:
     * "把修改记录给我看看" asks to be SHOWN the change log, and reading
     * it as a code change hands the write tools to a read request.
     *
     * <p>The list here therefore holds only verbs that do not head a
     * noun phrase in this position — "改成/换成/设为/变成" always take an
     * object, and "翻译成/翻译为" is the same shape.
     *
     * <p>★ AND THE OBJECT STILL HAS TO LOOK LIKE CODE ★ the rule below
     * also requires a code-shaped token BETWEEN 把 and the verb. That is
     * what separates "把 AppConfig 里的 MAX_RETRIES 改成 5" (an edit)
     * from "把说明文档改成英文" (still an edit — 文档 is an artefact) and
     * from "把 status update 的说明改成中文" (not an edit — no code-shaped
     * token in the object).
     */
    private static final List<String> BA_CHANGE_VERBS = List.of(
            "改成", "换成", "设为", "变成", "翻译成", "翻译为", "改写为");

    /**
     * R414: the Chinese artefact words that make a 把-object a real
     * target of a change (a file, a document, a piece of code).
     */
    private static final List<String> BA_ARTEFACT_WORDS = List.of(
            "文档", "文件", "代码", "脚本", "配置", "常量", "方法", "字段",
            "类", "接口", "函数", "语句", "测试", "注释", "日志");

    /**
     * R414: a code-shaped IDENTIFIER — a CamelCase name ("AppConfig") or
     * an UPPER_SNAKE constant ("MAX_RETRIES", "SQL").
     *
     * <p>★ WHY AN IDENTIFIER COUNTS AS "A THING IN THIS TREE" ★ the
     * 把-object of a real edit is usually a symbol, not a word: "把
     * AppConfig 里的 MAX_RETRIES 改成 5". Neither artefact word nor
     * path matches it, so without this the most ordinary Chinese code
     * request would still fall through to
     * {@link Kind#UNCERTAIN}.
     *
     * <p>★ THE CAMEL-CASE HALF IS NOT WORD-ANCHORED ★ "AppConfig" has no
     * word boundary before its inner capital ("pC" sits inside the word),
     * so {@code \b[a-z][A-Z]} would never match it. The pattern
     * therefore looks for the lowercase-to-uppercase TRANSITION
     * anywhere, and only the UPPER_SNAKE half keeps the word boundary it
     * needs ("SQL" must be a word of its own, not the "SQL" inside
     * "MySQL").
     */
    private static final Pattern IDENTIFIER_SHAPED = Pattern.compile(
            "[a-z][A-Z]|\\b[A-Z][A-Z0-9_]{2,}\\b");

    /**
     * R414: where one request ends and the next begins.
     *
     * <p>★ WHY A CLAUSE IS THE UNIT ★ one Chinese line can carry two
     * requests: "查一下 X，并把 Y 改成 Z" is a lookup AND an edit. The
     * lookup's object is the clause it lives in, so a code verb INSIDE
     * that clause is part of the subject ("查一下这个改名的方法" asks
     * ABOUT a method called 改名, and must not be read as "rename it"),
     * while a code verb OUTSIDE the clause is a coordinated request that
     * needs the write channel.
     *
     * <p>★ "并且" AND NOT "并" ★ 并 also opens 并发 ("concurrency"), and
     * cutting a clause there would split "查一下 Java 的并发实现" in the
     * middle of a word. The conjunction forms that cannot be a word are
     * listed instead.
     */
    private static final List<String> CLAUSE_BOUNDARIES = List.of(
            "，", ",", "；", ";", "。", "!", "！", "?", "？",
            "然后", "并且", "并把", "同时把", "再把");

    /**
     * R414: 并 followed by something that can only be a VERB — "并重命名",
     * "并删除". It is a clause boundary, but it cannot be listed as a
     * literal, because 并 also opens 并发 ("concurrency") and 重新
     * ("again") — cutting a clause there would split a word.
     */
    private static final Pattern CONJUNCTION_EDIT = Pattern.compile(
            "并(?=[改重删移新增创建生成加写修优整理清换设变提封装迁移补转翻])");

    /** union of two lists, order preserved. */
    private static List<String> union(List<String> a, List<String> b) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>(a);
        out.addAll(b);
        return List.copyOf(out);
    }

    /** A path-like token: something with a directory separator or a
     *  recognisable source extension. */
    private static final Pattern PATH_LIKE = Pattern.compile(
            "(?:[\\w.\\-]+[/\\\\][\\w.\\-]+[/\\\\]*)|"
            + "\\b[\\w.\\-]+\\.(?:java|kt|kts|scala|ts|tsx|js|jsx|mjs|cjs|py|go|rs|rb|php|cs|c|cc|cpp|h|hpp|swift|m|md|txt|json|ya?ml|xml|toml|sql|sh|ps1|bat|css|scss|html|vue|svelte)\\b",
            Pattern.CASE_INSENSITIVE);

    /** A question marker. Chinese interrogatives and the English
     *  question forms. */
    private static final Pattern QUESTION_SHAPED = Pattern.compile(
            "[?？]|为什么|为何|怎么|如何|怎样|什么|哪些|哪个|是否|能不能|可不可以|可以吗|"
            + "是不是|有没有|区别|差别|关系|原理|机制|why|how|what|which|whether",
            Pattern.CASE_INSENSITIVE);

    /**
     * The engine's OWN continuation prompts. They arrive as USER-role
     * messages, and they are not the user's intent — classifying one
     * of them would replace the user's task with the engine's task.
     * Callers that scan the transcript must skip these.
     */
    public static boolean isEngineGeneratedDirective(String text) {
        if (text == null) return false;
        String t = text.trim();
        return t.startsWith("<system-directive type=\"todo-continuation\"")
                || t.startsWith("<system-directive type=\"research-mode-recovery\"")
                || t.startsWith("<system-directive type=\"progress-stall-recovery\"");
    }

    // ------------------------------------------------------------------
    // Classification
    // ------------------------------------------------------------------

    /**
     * classify a prompt. Returns {@link Kind#UNCERTAIN} when the prompt
     * carries no usable signal — the caller then keeps the READ-ONLY
     * posture rather than guessing.
     *
     * <p>Never throws: a classifier that can break a user's turn is
     * worse than one that gives up.
     */
    public static Intent classify(String prompt) {
        try {
            Intent base = classifyInternal(prompt);
            // ★ R411: THE CAPABILITY AXIS IS COMPUTED HERE ★ the domain
            // axis says WHAT KIND of work this is; the capability axis
            // says whether the answer has to LAND ON DISK. They are
            // computed from the same prompt but they answer different
            // questions, and only the second one decides whether the
            // write channel is handed over.
            return new Intent(base.kind(), base.reason(),
                    requestsWorkspaceChange(base.kind(), prompt));
        } catch (RuntimeException e) {
            return Intent.uncertain("classifier failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // R411 — the capability axis
    // ------------------------------------------------------------------

    /**
     * ★ THE CAPABILITY AXIS ★ "does the user ask for the working tree to
     * change (or to receive a file)?"
     *
     * <p>Three ways to answer yes, and every one of them is about the
     * DELIVERABLE rather than about the DOMAIN:
     *
     * <ol>
     *   <li><b>the domain already means "change"</b> — CODE_CHANGE and
     *       OPS are requests to change something by definition;</li>
     *   <li><b>the request names where the result goes</b> — "保存到
     *       poem.txt", "save to notes.md". This is the case R408
     *       measured: a POEM is not code work, but the file it has to
     *       land in is the user's requirement;</li>
     *   <li><b>a request wearing a question mark</b> — a question-shaped
     *       prompt that carries a request hint ("帮我"), points at a
     *       local artefact ("这条 SQL") and asks for a change ("优化").
     *       Without all three it stays a question.</li>
     * </ol>
     *
     * <h2>★ WHY A QUESTION NEVER GRANTS THE WRITE CHANNEL ON ITS OWN ★</h2>
     * <p>R401's hard invariant: a misfire on the write path costs the
     * user their working tree, and a misfire on the read path costs one
     * turn. So a question only reaches the write channel when it is
     * wearing a request's clothes — and then only when it also points at
     * something LOCAL.
     *
     * <p>★ THE EXAMPLE IS QUESTION-SHAPED ON PURPOSE ★ "帮我优化这个算法
     * 要注意什么？" wears a request's clothes and asks for a change, but
     * it points at NOTHING in this tree ⇒ it stays read-only. (A
     * non-question "帮我优化一下这个算法" is a CODE_CHANGE on the DOMAIN
     * axis and gets the write channel from there — a different rule, and
     * one that predates R411.)
     */
    private static boolean requestsWorkspaceChange(Kind kind, String prompt) {
        if (kind == Kind.CODE_CHANGE || kind == Kind.OPS) return true;
        if (prompt == null || prompt.isBlank()) return false;
        String p = prompt.trim();
        String lower = p.toLowerCase(Locale.ROOT);
        boolean question = QUESTION_SHAPED.matcher(p).find();
        boolean requestHint = hasRequestHint(lower);

        // (1b) ★ R413: A DIAGNOSIS THAT ASKS FOR THE ACTION ★
        //      "这个不生效，帮我修一下" is a diagnosis by symptom, but the
        //      user asked for the FIX — the write channel must survive, or
        //      the fix request becomes unanswerable.
        //      ★ AND THE MIRROR ★ "帮我定位一下原因" asks for the CAUSE
        //      only ⇒ no write channel ⇒ the turn may not leave a
        //      reproduction harness in the user's tree (R413's matrix
        //      measured exactly that: four files nobody asked for).
        if (kind == Kind.DIAGNOSE
                && firstHit(lower, DIAGNOSE_ACTION_VERBS) != null
                && (!question || requestHint)) {
            return true;
        }

        // (2) the request names where the result goes. A question only
        //     counts when it is also a REQUEST ("怎么保存到 X?" is a
        //     question about saving; "帮我保存到 X" is a request to
        //     save).
        if (firstHit(lower, PERSIST_MARKERS) != null && (!question || requestHint)) {
            return true;
        }

        // (3) a request wearing a question mark, pointed at a local
        //     artefact.
        if (question && requestHint && hasLocalAnchor(p, lower)
                && firstHit(lower, CHANGE_VERBS) != null) {
            return true;
        }
        return false;
    }

    /**
     * Does the prompt ASK for something? R405's {@link #REQUEST_PREFIXES}
     * only work at the START of a prompt; this list is found anywhere.
     */
    private static boolean hasRequestHint(String lower) {
        for (String h : REQUEST_HINTS) {
            if (lower.contains(h)) return true;
        }
        return false;
    }

    private static Intent classifyInternal(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return Intent.uncertain("empty prompt");
        }
        String p = prompt.trim();
        String lower = p.toLowerCase(Locale.ROOT);

        // An engine-generated continuation is not the user's intent.
        if (isEngineGeneratedDirective(p)) {
            return Intent.uncertain("engine-generated directive");
        }

        boolean question = QUESTION_SHAPED.matcher(p).find();
        boolean local = hasLocalAnchor(p, lower);
        boolean concept = containsAny(lower, CONCEPT_BLOCKERS);
        boolean strictVerb = containsAny(lower, STRICT_CODE_VERBS);
        // ★ R405: THE IMPERATIVE POSITION ★ a request, not a noun phrase.
        // "Update pom.xml" opens with the verb; "give me a status update"
        // does not. This is the only signal that separates the two.
        String imperativeVerb = imperativeAmbiguousVerb(lower);

        // 1. An explicit request for external material wins over
        //    everything else: the user has already said what they want
        //    done, and "web search" is not something a keyword table
        //    should second-guess.
        String researchHit = firstHit(lower, RESEARCH_MARKERS);
        if (researchHit != null) {
            return new Intent(Kind.RESEARCH, "explicit external-source request: " + researchHit);
        }

        // 2. Failure symptoms — a diagnosis, whether or not it was
        //    phrased as a question.
        String diagHit = firstHit(lower, DIAGNOSE_MARKERS);
        if (diagHit != null) {
            return new Intent(Kind.DIAGNOSE, "failure symptom: " + diagHit);
        }

        // 3. Review.
        String reviewHit = firstHit(lower, REVIEW_MARKERS);
        if (reviewHit != null) {
            return new Intent(Kind.REVIEW, "review request: " + reviewHit);
        }

        // 4. Build / run / deploy.
        String opsHit = firstHit(lower, OPS_MARKERS);
        if (opsHit != null) {
            return new Intent(Kind.OPS, "build/run request: " + opsHit);
        }

        // 5. A question.
        if (question) {
            // 5a. A concept question — the user wants an EXPLANATION.
            //     Never code work, whatever verb it carries.
            if (concept) {
                return local
                        ? new Intent(Kind.ANSWER, "concept question about this repository")
                        : new Intent(Kind.RESEARCH, "concept question with no local anchor");
            }
            // 5b. Anchored to this repository — answer it from here.
            if (local) {
                if (strictVerb) {
                    return new Intent(Kind.CODE_CHANGE,
                            "question carrying an implementation verb: " + firstHit(lower, STRICT_CODE_VERBS));
                }
                // ★ R405: A REQUEST-QUESTION IS A REQUEST ★ "can you
                // update pom.xml?" is an instruction wearing a question
                // mark. Only the IMPERATIVE position may promote here —
                // a path-like object must NOT, because "what changed in
                // pom.xml?" is a question ABOUT a file.
                if (imperativeVerb != null) {
                    return new Intent(Kind.CODE_CHANGE,
                            "question carrying an implementation verb in the imperative position: " + imperativeVerb);
                }
                return new Intent(Kind.ANSWER, "question about this repository");
            }
            // 5c. No local anchor. A concept question by default; code
            //     work only when the user spelled out an imperative
            //     object ("怎么实现一个 LRU 缓存?").
            if (strictVerb) {
                return new Intent(Kind.CODE_CHANGE,
                        "question carrying an implementation verb: " + firstHit(lower, STRICT_CODE_VERBS));
            }
            if (imperativeVerb != null) {
                return new Intent(Kind.CODE_CHANGE,
                        "question carrying an implementation verb in the imperative position: " + imperativeVerb);
            }
            return new Intent(Kind.RESEARCH, "concept question with no local anchor");
        }

        // 6. R414: the Chinese 把-construction. The verb sits at the END of
        //    the sentence, so the implementation-verb rule (which reads the
        //    HEAD of the sentence) cannot see it — measured: "把 AppConfig
        //    里的 MAX_RETRIES 改成 5" and "把这条 SQL 改成参数化的" both
        //    landed on UNCERTAIN, i.e. the user asked for a code change and
        //    the write channel was pruned away.
        String baVerb = baChangeRequest(p, lower);
        if (baVerb != null) {
            return new Intent(Kind.CODE_CHANGE,
                    "Chinese 把-construction asking for a change: " + baVerb);
        }

        // 7. R414: a LOOKUP — "查一下 X", "搜一下 X". The marker is an
        //    INFORMATION verb, so the turn is a lookup, and the anchor
        //    decides WHERE it looks: this tree, or the web.
        String lookup = lookupRequest(p, lower);
        if (lookup != null) {
            return local
                    ? new Intent(Kind.ANSWER, "lookup about this repository: " + lookup)
                    : new Intent(Kind.RESEARCH, "lookup request with no local anchor: " + lookup);
        }

        // 8. Not a question: an explicit implementation verb means code
        //    work.
        String codeHit = firstHit(lower, CODE_VERBS);
        if (codeHit != null) {
            return new Intent(Kind.CODE_CHANGE, "implementation verb: " + codeHit);
        }

        // 8b. R405: an AMBIGUOUS verb — the same word is a noun as often
        //     as a verb, so it fires only when it stands alone AND
        //     either opens the sentence (imperative position) or has a
        //     path-like object.
        //
        //     ★ THE PATH-ANCHORED HALF IS NOT AVAILABLE TO QUESTIONS ★
        //     it is why "what changed in pom.xml?" stays a question
        //     while "can you update pom.xml?" becomes code work.
        String ambiguous = standaloneAmbiguousVerb(lower);
        if (ambiguous != null
                && (imperativeVerb != null || PATH_LIKE.matcher(p).find())) {
            String why = imperativeVerb != null
                    ? "implementation verb in the imperative position: "
                    : "implementation verb with a path-like object: ";
            return new Intent(Kind.CODE_CHANGE, why + ambiguous);
        }

        // 9. Nothing matched.
        return Intent.uncertain("no intent signal");
    }

    private static boolean hasLocalAnchor(String p, String lower) {
        if (containsAny(lower, LOCAL_ANCHORS)) return true;
        if (PATH_LIKE.matcher(p).find()) return true;
        // ★ R411: a demonstrative pointed at a concrete artefact ★
        if (containsAny(lower, DEMONSTRATIVES) && hasAnchoredArtefact(lower)) return true;
        return false;
    }

    /**
     * ★ THE ARTEFACT HALF OF THE PAIR ★ "sql" has to be a STANDALONE
     * word — "nosql" and "mysql" contain it, and those are concept
     * questions, not rows in this tree.
     */
    private static boolean hasAnchoredArtefact(String lower) {
        if (containsAny(lower, ANCHORED_ARTIFACTS)) return true;
        int at = lower.indexOf("sql");
        while (at >= 0) {
            if (isStandaloneAt(lower, at, "sql")) return true;
            at = lower.indexOf("sql", at + 1);
        }
        return false;
    }

    // ------------------------------------------------------------------
    // R414 — the two constructions a keyword table cannot see
    // ------------------------------------------------------------------

    /**
     * R414: the Chinese 把-construction, resolved.
     *
     * <p>★ THREE THINGS HAVE TO LINE UP ★
     * <ol>
     *   <li>the sentence carries 把;</li>
     *   <li>a change verb from {@link #BA_CHANGE_VERBS} appears AFTER 把 —
     *       Chinese puts the object first and the verb last, so the
     *       verb's POSITION is the instruction, and a verb that never
     *       takes an object ("修改记录" = the change log) is not in the
     *       list;</li>
     *   <li>the OBJECT — the text between 把 and the verb — looks like
     *       something in this tree.</li>
     * </ol>
     *
     * <p>★ WHY THE THIRD CONDITION IS NOT OPTIONAL ★ "把 status update 的
     * 说明改成中文" has the construction but no local target: the user
     * wants the answer in the CHAT. Reading it as a code change hands
     * the write tools to a translation request — the exact misfire the
     * whole posture table exists to prevent.
     *
     * @param p the prompt AS THE USER WROTE IT — the code-shape check
     *           needs the CASE, and the classifier lower-cases the text
     *           for its keyword tables (measured: "AppConfig" and
     *           "MAX_RETRIES" became "appconfig" and "max_retries", and
     *           then no identifier can ever be recognised)
     * @param lower the same prompt, lower-cased
     * @return the change verb, or null when this is not a 把-request
     */
    private static String baChangeRequest(String p, String lower) {
        int ba = lower.indexOf(BA_CONSTRUCTION);
        while (ba >= 0) {
            int after = ba + BA_CONSTRUCTION.length();
            String verb = null;
            int verbAt = -1;
            for (String v : BA_CHANGE_VERBS) {
                int at = lower.indexOf(v, after);
                if (at >= 0 && (verbAt < 0 || at < verbAt)) {
                    verb = v;
                    verbAt = at;
                }
            }
            if (verb != null
                    && baObjectIsCodeShaped(p.substring(after, verbAt))) {
                return verb;
            }
            ba = lower.indexOf(BA_CONSTRUCTION, ba + 1);
        }
        return null;
    }

    /**
     * R414: does the 把-object name something that lives in this tree?
     *
     * <p>Three shapes qualify: an artefact word ("文档", "字段", "测试"),
     * a path ("src/OrderDao.java") or an identifier ("AppConfig",
     * "MAX_RETRIES", "SQL").
     */
    private static boolean baObjectIsCodeShaped(String object) {
        if (containsAny(object, BA_ARTEFACT_WORDS)) return true;
        if (PATH_LIKE.matcher(object).find()) return true;
        return IDENTIFIER_SHAPED.matcher(object).find();
    }

    /**
     * R414: a LOOKUP request — "查一下 X", "搜一下 X", "look up X".
     *
     * <p>★ THE MARKER IS AN INFORMATION VERB ★ so the turn is a lookup,
     * not a modification. But WHERE it looks is decided by the anchor:
     * a local subject is answered from this tree, an external one from
     * the web.
     *
     * <p>★ WHY THE CLAUSE MATTERS ★ "查一下这个改名的方法" asks ABOUT a
     * method called 改名 — the verb "改名" is part of the SUBJECT, not
     * a second request. "查一下 X，并把 Y 改成 Z" is two requests, and
     * the second one needs the write channel. The difference is the
     * CLAUSE, so that is what this reads.
     *
     * @return the marker, or null when this is not a lookup
     */
    private static String lookupRequest(String p, String lower) {
        String hit = null;
        int at = -1;
        for (String m : WEAK_RESEARCH_MARKERS) {
            int i = lower.indexOf(m);
            if (i >= 0 && (at < 0 || i < at)) {
                hit = m;
                at = i;
            }
        }
        if (hit == null) return null;
        List<int[]> bounds = clauseBoundaries(lower);
        int start = clauseStart(bounds, at);
        int end = clauseEnd(bounds, at);
        String outside = p.substring(0, start)
                + (end < 0 ? "" : p.substring(end));
        if (carriesEditSignal(outside)) return null;
        return hit;
    }

    /**
     * R414: does this text ask for the tree to CHANGE?
     *
     * <p>★ THE SAME QUESTION THE CODE-VERB RULE ASKS ★ so the lookup
     * rule and the implementation-verb rule agree about what an edit
     * is — a lookup does not get to call an edit "not an edit".
     */
    private static boolean carriesEditSignal(String text) {
        if (text == null || text.isBlank()) return false;
        String low = text.toLowerCase(Locale.ROOT);
        if (baChangeRequest(text, low) != null) return true;
        return containsAny(low, CODE_VERBS);
    }

    /**
     * R414: every clause boundary in the text, as {@code {index, length}}
     * pairs, ordered by index.
     */
    private static List<int[]> clauseBoundaries(String lower) {
        List<int[]> out = new java.util.ArrayList<>();
        for (String b : CLAUSE_BOUNDARIES) {
            int i = lower.indexOf(b);
            while (i >= 0) {
                out.add(new int[] { i, b.length() });
                i = lower.indexOf(b, i + 1);
            }
        }
        java.util.regex.Matcher m = CONJUNCTION_EDIT.matcher(lower);
        while (m.find()) {
            out.add(new int[] { m.start(), m.end() - m.start() });
        }
        out.sort((a, b) -> Integer.compare(a[0], b[0]));
        return out;
    }

    /** the start of the clause that contains {@code at}. */
    private static int clauseStart(List<int[]> bounds, int at) {
        int start = 0;
        for (int[] b : bounds) {
            if (b[0] + b[1] <= at) start = Math.max(start, b[0] + b[1]);
        }
        return start;
    }

    /** the index of the boundary that ENDS the clause containing {@code at},
     *  or -1 when the clause runs to the end of the prompt. */
    private static int clauseEnd(List<int[]> bounds, int at) {
        int end = -1;
        for (int[] b : bounds) {
            if (b[0] >= at && (end < 0 || b[0] < end)) end = b[0];
        }
        return end;
    }

    // ------------------------------------------------------------------
    // R405 — the imperative position
    // ------------------------------------------------------------------

    /** A Latin word character: letter, digit or underscore. */
    private static boolean isLatinWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
    }

    /**
     * A CJK ideograph.
     *
     * <p>★ WHY CHINESE NEEDS ITS OWN BOUNDARY ★ Chinese has no word
     * boundary: "修改记录" (the change log) STARTS with "修改" (modify).
     * A Chinese verb is therefore a standalone word only when the
     * character AFTER it is not another ideograph — that is what keeps
     * "修改记录" out of the instruction column while "修改 Foo.java" is
     * in it.
     */
    private static boolean isCjk(char c) {
        // ★ UNICODE ESCAPES, NOT LITERAL CHARACTERS ★ the lexer resolves
        // an escape before the file's encoding matters, so these ranges
        // cannot be corrupted by a mis-decoded source file.
        return (c >= '\u3400' && c <= '\u4dbf')
                || (c >= '\u4e00' && c <= '\u9fff')
                || (c >= '\uf900' && c <= '\ufaff');
    }

    /** Is the word at {@code at} a standalone token (not part of a longer word)? */
    private static boolean isStandaloneAt(String lower, int at, String verb) {
        int end = at + verb.length();
        if (end < lower.length()) {
            char c = lower.charAt(end);
            if (isLatinWordChar(c)) return false;
            if (isCjk(c) && isCjk(verb)) return false;
        }
        if (at > 0 && isLatinWordChar(lower.charAt(at - 1))) return false;
        return true;
    }

    /** Is this verb written in Chinese? */
    private static boolean isCjk(String verb) {
        return !verb.isEmpty() && isCjk(verb.charAt(0));
    }

    /**
     * The first STANDALONE occurrence of an ambiguous verb anywhere in the
     * prompt, or null.
     *
     * <p>★ WHY STANDALONE ★ "the implementation of LRU" contains
     * "implement"; a substring match reads it as an instruction. The same
     * holds for "the created file" and "the generated code".
     */
    private static String standaloneAmbiguousVerb(String lower) {
        for (String verb : AMBIGUOUS_CODE_VERBS) {
            int at = lower.indexOf(verb);
            while (at >= 0) {
                if (isStandaloneAt(lower, at, verb)) return verb;
                at = lower.indexOf(verb, at + 1);
            }
        }
        return null;
    }

    /**
     * The ambiguous verb that OPENS the prompt — after an optional request
     * prefix ("please", "can you", "请", "帮我") — or null.
     *
     * <p>★ THIS IS THE WHOLE POINT ★ "Update pom.xml" is a request;
     * "give me a status update on the migration" is a noun phrase. The
     * difference is not the word, it is where the word sits.
     */
    private static String imperativeAmbiguousVerb(String lower) {
        String rest = lower.trim();
        boolean stripped = true;
        while (stripped) {
            stripped = false;
            for (String p : REQUEST_PREFIXES) {
                if (rest.startsWith(p)) {
                    rest = rest.substring(p.length()).trim();
                    stripped = true;
                    break;
                }
            }
        }
        for (String verb : AMBIGUOUS_CODE_VERBS) {
            if (rest.startsWith(verb) && isStandaloneAt(rest, 0, verb)) return verb;
        }
        return null;
    }

    private static boolean containsAny(String lower, List<String> markers) {
        return firstHit(lower, markers) != null;
    }

    /** the first marker present, or null. The marker is what the
     *  "reason" field shows, so the UI can name the signal. */
    private static String firstHit(String lower, List<String> markers) {
        for (String m : markers) {
            if (lower.contains(m)) return m;
        }
        return null;
    }
}
