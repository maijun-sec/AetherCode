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
     * The verdict: a kind, the signal that produced it, and a
     * human-readable reason (shown in the UI so the user can see WHY
     * the agent decided what it decided).
     */
    public record Intent(Kind kind, String reason) {

        public Intent {
            if (kind == null) kind = Kind.UNCERTAIN;
            if (reason == null) reason = "";
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
     */
    private static final List<String> CODE_VERBS = List.of(
            "实现", "编写", "写一个", "写个", "写一段", "新增", "创建", "生成", "重构",
            "重写", "封装", "提取", "补充", "开发", "搭建", "接入", "集成",
            "加上", "加个", "加一个", "加一段", "添加", "改造", "迁移", "删除", "删掉", "移除",
            "优化", "简化", "完善", "修复", "修一下", "更新", "升级", "补一个",
            "帮我改", "改一下", "改名", "重命名", "清理", "整理", "排序",
            "implement", "create a", "create an", "add a", "add an",
            "refactor", "rewrite", "scaffold", "generate a", "generate an",
            "write a", "write an", "build a", "build an", "delete", "remove",
            "rename", "fix", "optimize", "migrate", "translate", "convert",
            "format", "sort", "support", "adapt"
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
            return classifyInternal(prompt);
        } catch (RuntimeException e) {
            return Intent.uncertain("classifier failed: " + e.getMessage());
        }
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
                return new Intent(Kind.ANSWER, "question about this repository");
            }
            // 5c. No local anchor. A concept question by default; code
            //     work only when the user spelled out an imperative
            //     object ("怎么实现一个 LRU 缓存?").
            if (strictVerb) {
                return new Intent(Kind.CODE_CHANGE,
                        "question carrying an implementation verb: " + firstHit(lower, STRICT_CODE_VERBS));
            }
            return new Intent(Kind.RESEARCH, "concept question with no local anchor");
        }

        // 6. Not a question: an explicit implementation verb means code
        //    work.
        String codeHit = firstHit(lower, CODE_VERBS);
        if (codeHit != null) {
            return new Intent(Kind.CODE_CHANGE, "implementation verb: " + codeHit);
        }

        // 7. Nothing matched.
        return Intent.uncertain("no intent signal");
    }

    private static boolean hasLocalAnchor(String p, String lower) {
        if (containsAny(lower, LOCAL_ANCHORS)) return true;
        return PATH_LIKE.matcher(p).find();
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
