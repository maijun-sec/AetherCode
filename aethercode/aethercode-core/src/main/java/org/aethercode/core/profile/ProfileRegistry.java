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

package org.aethercode.core.profile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.aethercode.core.intent.IntentClassifier;
import org.aethercode.core.tool.Tool;

/**
 * R401: the registry of MAIN-loop profiles and the tool prune they imply.
 *
 * <h2>The one rule this table encodes</h2>
 * <blockquote><b>A turn that was not asked to CHANGE something does not
 * get a write tool — and it does not get a shell either.</b></blockquote>
 *
 * <p>Concretely: {@code file_write}, {@code file_edit} and
 * {@code notebook_edit} survive the prune ONLY for the three intents
 * where the user asked for an action — CODE_CHANGE, DIAGNOSE and OPS.
 * Every other intent loses them.
 *
 * <p>It also loses the SHELL, because the shell is a write channel:
 * {@code echo x > f} writes, {@code sed -i} writes, and a model asked to
 * create a file will find one. Denying the write tools while leaving the
 * shell in place makes the "read-only" posture a PROMPT rather than a
 * guarantee — see {@link #SHELL_TOOL} for the measurement that proved
 * it on the live daemon.
 *
 * <p>This is the structural half of the fix for the user's complaint
 * ("a concept question produced a pile of unrelated source files").
 * A prompt instruction is soft and the model can ignore it; a pruned
 * tool cannot be called at all, because it is not in the tool table the
 * model is shown.
 *
 * <p>It also makes the classifier's PRECISION cheap to buy: because the
 * default posture ({@link #NAVIGATOR}) is read-only, a mis-recognition
 * costs one wasted turn (the model asks instead of writing) rather than
 * an un-undoable working-tree change. The next turn re-classifies and
 * the write tools come back — "yes, implement it" is a CODE_CHANGE.
 *
 * <h2>Why the tool names are literals</h2>
 * <p>They are the names of the real tools in
 * {@code org.aethercode.tools.StandardTools#all()} — {@code file_read},
 * {@code file_write}, {@code file_edit}, {@code glob}, {@code grep},
 * {@code bash}, {@code web_search}, {@code web_fetch}, ... A name that
 * does not exist prunes nothing, so a typo here would silently widen the
 * pool. {@code ProfileRegistryR401Test} pins every name against the
 * standard tool set.
 *
 * @see Profile
 * @see IntentClassifier
 */
public final class ProfileRegistry {

    private ProfileRegistry() {}

    // ------------------------------------------------------------------
    // The write-capable tools. Everything else in the standard pool is
    // read-only or an orchestration tool.
    // ------------------------------------------------------------------

    /** the tools that can CHANGE the user's working tree. */
    public static final Set<String> WRITE_TOOLS = Set.of(
            "file_write",
            "file_edit",
            "notebook_edit");

    /** The shell. A shell is a WRITE CHANNEL: {@code echo x > f},
     *  {@code sed -i}, {@code python -c "open('f','w')"} all write, and
     *  a model that has been asked to create a file WILL find one. So a
     *  profile that denies the write tools must deny the shell too —
     *  otherwise the "read-only" posture is only a PROMPT, and R401's
     *  whole point is that it is structural.
     *
     *  <p>Measured on the live daemon (R401): under the {@code analyst}
     *  profile (write tools denied, shell allowed) the model called
     *  {@code file_write} six times, was refused six times with
     *  "unknown tool: file_write", and then wrote the file through
     *  {@code bash} anyway. The file the user had NOT asked for existed
     *  at the end of the turn. Denying the write tools alone is not
     *  enough. */
    public static final String SHELL_TOOL = "bash";

    /** the orchestration tool that can start a child agent. A child
     *  inherits its parent's profile (see
     *  {@code AppState#activeProfile}), so a read-only parent cannot
     *  launder a write through a child. */
    public static final String SPAWN_TOOL = "spawn_agent";

    /**
     * R410: the tools that can change the user's CONFIGURATION — the
     * things outside the working tree.
     *
     * <p>The agent registry lives in {@code ~/.aethercode/agents/}, not in
     * the project. A read-only turn that can rewrite an agent definition
     * is not read-only, and R408 measured it happening: under the
     * read-only {@code navigator} profile the model called
     * {@code update_agent(name=end-user, body=probe)} — and it EXECUTED,
     * because the write tools were denied and the AGENT tools were not.
     * The user's agent file became 30 bytes of "probe".
     *
     * <p>★ WHY A DENY LIST AND NOT AN ALLOW LIST ★ An allow-list would
     * be structurally safer (a new write tool would be denied by
     * default), but the tool pool is not a closed set: MCP servers add
     * tools at runtime, and an allow-list would silently disable every
     * one of them — including read-only MCP tools — in a read-only
     * posture. The deny list is the honest trade-off, and its cost is
     * paid HERE: every tool that can change the user's state has to be
     * named. That is why this set exists as a named constant: a reader
     * can see what a read-only posture refuses, and a test pins it.
     */
    public static final Set<String> AGENT_WRITE_TOOLS = Set.of(
            "create_agent",
            "update_agent",
            "delete_agent");

    /**
     * The deny set every READ-ONLY profile carries: no write tool, no
     * shell, and no agent-registry write. See {@link #SHELL_TOOL} for why
     * the shell is in here and {@link #AGENT_WRITE_TOOLS} for why the
     * agent tools are.
     *
     * <p>A read-only posture keeps the READ tools ({@code file_read},
     * {@code grep}, {@code glob}) — those cannot change anything — and
     * loses every channel that can: the write tools, the shell, and the
     * agent registry.
     */
    public static final Set<String> READ_ONLY_DENY = Set.of(
            "file_write",
            "file_edit",
            "notebook_edit",
            "bash",
            "create_agent",
            "update_agent",
            "delete_agent");

    /**
     * R410: RESEARCH is read-only AND refuses to delegate. Built from
     * {@link #READ_ONLY_DENY} rather than spelled out again — a second
     * copy of the deny set is a second list that drifts.
     */
    public static final Set<String> RESEARCH_DENY = deny(READ_ONLY_DENY, Set.of(SPAWN_TOOL));

    /** union of two deny sets, in a stable order. */
    private static Set<String> deny(Set<String> a, Set<String> b) {
        Set<String> out = new java.util.LinkedHashSet<>(a);
        out.addAll(b);
        return Set.copyOf(out);
    }

    // ------------------------------------------------------------------
    // The profiles
    // ------------------------------------------------------------------

    /** CODE_CHANGE — the user asked for code. Writes allowed. */
    public static final Profile BUILDER = new Profile(
            "builder",
            "写代码",
            "实现需求：先读后写，改动最小，改完说明改了什么",
            """
                    # Task posture (R401, per-turn)

                    This turn is a **CODE CHANGE** — the user asked for code to be written or changed.

                    - Implement it in the working tree. Read the surrounding code before you write, and
                      match its conventions.
                    - Do not spend turns on environment research (`mvn -version`, `java -version`, `ls`,
                      `pwd`) — the environment is already known.
                    - Make the change, then state what changed and where.""",
            null,   // no allow-list: every tool is available
            Set.of());

    /** DIAGNOSE — something is broken. Writes allowed because the user
     *  very often asked for the fix, not just the cause ("这个不生效，
     *  帮我修一下" matches the failure-symptom rule). The prompt carries
     *  the "don't change what wasn't asked" half. */
    public static final Profile DEBUGGER = new Profile(
            "debugger",
            "定位问题",
            "先定位根因并给 file:line 证据；没让改就别改",
            """
                    # Task posture (R401, per-turn)

                    This turn is a **DIAGNOSIS** — something is not working.

                    - Find the cause before you touch anything. Read the code, the logs and the error
                      output; reproduce if that is cheap.
                    - Report the root cause with `file:line` evidence. Name the mechanism, not the
                      symptom.
                    - If the user explicitly asked for a fix, make the SMALLEST fix that addresses the
                      root cause, then say what you changed and why.
                    - If the user only asked what is wrong, do NOT modify files.""",
            null,
            Set.of());

    /** RESEARCH — a concept question whose answer is not in this tree.
     *  The strictest profile: no writes AND no shell, because a concept
     *  lookup has no business running commands in the user's repo. */
    public static final Profile RESEARCHER = new Profile(
            "researcher",
            "查资料",
            "先 web_search / web_fetch 找资料，再汇总作答并给来源",
            """
                    # Task posture (R401, per-turn)

                    This turn is a **RESEARCH / concept lookup** — the user asked a question whose
                    answer is not in this working tree.

                    - Gather material FIRST. Use `web_search` to find candidate sources and
                      `web_fetch` to read the promising ones. Prefer primary sources: official
                      documentation, specifications, source repositories, papers.
                    - THEN answer in prose: the direct answer, the relationships that matter, and
                      the sources you used (with URLs).
                    - Do NOT create, edit or scaffold source files. Do NOT start a project. Do
                      NOT write sample or demo code. This is a question, not a feature request.
                    - If the answer IS in this repository, prefer the local file over the web:
                      read it, and tell the user which file.
                    - If web access is unavailable, say so plainly and answer from your own
                      knowledge, clearly labelled as such.
                    - Stay on the question that was asked. Do not drift into adjacent work
                      ("while I'm here I also refactored...").""",
            null,
            RESEARCH_DENY);

    /** ANSWER — a question about THIS repository. Read-only: no write
     *  tools and no shell. */
    public static final Profile ANALYST = new Profile(
            "analyst",
            "读代码答疑",
            "读本仓库回答并引用 file:line",
            """
                    # Task posture (R401, per-turn)

                    This turn is a **QUESTION about this repository**.

                    - Answer it in prose, grounded in the code here. Read the relevant files
                      first — do not answer from imagination.
                    - Do NOT create or modify files. The user asked a question, not for a
                      change. If they want a change, they will ask for it next.
                    - Quote the file and line that supports each claim.
                    - There is no shell in this turn: read with `file_read`, `grep` and `glob`.
                      Nothing here can change the working tree, and trying a command only
                      wastes the turn.""",
            null,
            READ_ONLY_DENY);

    /** REVIEW — read the code (or the diff) and report findings. */
    public static final Profile REVIEWER = new Profile(
            "reviewer",
            "代码审查",
            "只读 + 报告，不要重写",
            """
                    # Task posture (R401, per-turn)

                    This turn is a **CODE REVIEW**.

                    - Read the code (or the diff) and report findings: correctness, risk,
                      missing tests, unclear naming.
                    - Order the findings by severity and give each one a concrete fix.
                    - Do NOT rewrite the code. The user asked for an assessment.
                    - There is no shell in this turn: read with `file_read`, `grep` and `glob`.""",
            null,
            READ_ONLY_DENY);

    /** OPS — build / run / deploy / verify. Writes allowed (a deploy
     *  writes files); the prompt pins "report the real output". */
    public static final Profile OPERATOR = new Profile(
            "operator",
            "构建运维",
            "跑真命令、报真输出",
            """
                    # Task posture (R401, per-turn)

                    This turn is an **OPERATIONS** request — build, run, deploy or verify.

                    - Run the real command and report its real output. Never claim a build
                      passed without having run it.
                    - Do not refactor while a build is running.
                    - If the command fails, report the actual error — do not paper over it.""",
            null,
            Set.of());

    /**
     * R411: PRODUCER — the turn asks for a DELIVERABLE to be written to
     * disk, and the deliverable is not code.
     *
     * <p>★ WHY THIS PROFILE EXISTS ★ The domain axis and the capability
     * axis answer different questions, and R408 measured what happens
     * when one table has to answer both. "Write a poem about autumn and
     * save it to poem.txt" is not code work, so the domain said
     * UNCERTAIN, so the read-only {@link #NAVIGATOR} was chosen, so the
     * write tool was PRUNED — and the user's file was never written. The
     * poem had nothing to do with the requirement, and the requirement
     * had nothing to do with the poem.
     *
     * <p>So a turn that must land ON DISK gets THIS posture when its
     * domain profile is read-only. The posture is deliberately
     * domain-neutral: it says "produce what was asked, where it was
     * asked for", and it explicitly forbids the model from turning the
     * request into a different KIND of work.
     *
     * <p>★ IT MUST NOT LIE ★ A posture that said "this turn is
     * read-only" while the turn held {@code file_write} would be the
     * exact defect R401 was written to remove (a prompt that says one
     * thing while the structure says another). That is why this is a
     * separate profile and not the read-only one with an extra line
     * appended.
     */
    public static final Profile PRODUCER = new Profile(
            "producer",
            "写入产出",
            "把产物按要求写进工作树（产物可以是代码、文档、诗或数据）",
            """
                    # Task posture (R411, per-turn)

                    This turn asks for a **deliverable to be written to disk**. The product may be
                    code, a document, a note, a query or anything else — the request decides what
                    it is, not this profile.

                    - Produce exactly what was asked, and put it where the user said. If they named
                      a file, write that file; if they named a place, use that place.
                    - Read the target before you change it, when the target already exists.
                    - Do not reshape the request into different work: a poem stays a poem, a SQL
                      fix stays a SQL fix. Do not add files, scaffolding, samples or documentation
                      that nobody asked for.
                    - If the request needs material from outside this tree, gather it first
                      (`web_search` / `web_fetch`), then write the result.
                    - Finish by saying what you wrote and where.""",
            null,
            Set.of());

    /** UNCERTAIN — the classifier found no usable signal. This is the
     *  DEFAULT posture and it is deliberately READ-ONLY: "we do not know
     *  what you want" must not become "so we wrote some files". The
     *  agent answers, or asks; the next turn re-classifies and the write
     *  tools return if the user really wanted a change. */
    public static final Profile NAVIGATOR = new Profile(
            "navigator",
            "未识别（只读）",
            "没能识别这一轮的意图：只读 + 先问清楚",
            """
                    # Task posture (R401, per-turn)

                    The shape of this request was not recognised, so this turn is **read-only**.

                    - If it is a QUESTION, answer it in prose. Do not create files.
                    - If it clearly asks for code, describe what you would change and ask for the
                      go-ahead — the next turn is re-classified and the write tools come back.
                    - If it is ambiguous, do the reading that is cheap and reversible first, and
                      ask before you write anything.
                    - There is no shell in this turn: read with `file_read`, `grep` and `glob`.""",
            null,
            READ_ONLY_DENY);

    // ------------------------------------------------------------------
    // The registry
    // ------------------------------------------------------------------

    private static final Map<String, Profile> REGISTRY = new LinkedHashMap<>();

    static {
        REGISTRY.put(BUILDER.name(), BUILDER);
        REGISTRY.put(DEBUGGER.name(), DEBUGGER);
        REGISTRY.put(RESEARCHER.name(), RESEARCHER);
        REGISTRY.put(ANALYST.name(), ANALYST);
        REGISTRY.put(REVIEWER.name(), REVIEWER);
        REGISTRY.put(OPERATOR.name(), OPERATOR);
        REGISTRY.put(NAVIGATOR.name(), NAVIGATOR);
        REGISTRY.put(PRODUCER.name(), PRODUCER);
    }

    /** every profile name, in registration order. */
    public static List<String> names() {
        return List.copyOf(REGISTRY.keySet());
    }

    /** every profile, in registration order. */
    public static List<Profile> all() {
        return List.copyOf(REGISTRY.values());
    }

    /**
     * Every profile as a WIRE description: the stable name (what the
     * override carries), the human label (what the UI shows) and the
     * one-line description (what the picker shows as a hint).
     *
     * <p>The renderer must not keep its own copy of the names or the
     * labels — the registry is the truth, and a second copy is exactly
     * how a list drifts (the R378/R380 lesson).
     */
    public static List<java.util.Map<String, String>> describeAll() {
        List<java.util.Map<String, String>> out = new ArrayList<>();
        for (Profile p : all()) {
            java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
            m.put("name", p.name());
            m.put("label", p.label());
            m.put("description", p.description());
            out.add(m);
        }
        return out;
    }

    /**
     * Look up a profile by name. The match is case-insensitive and
     * trims whitespace. Returns {@code null} for an unknown name —
     * callers must treat that as "no override", never as a silent
     * default that overrides the classifier's own verdict.
     */
    public static Profile lookup(String name) {
        if (name == null) return null;
        Profile p = REGISTRY.get(name.trim().toLowerCase(java.util.Locale.ROOT));
        return p;
    }

    /**
     * The profile for an intent. Every kind maps to exactly one profile
     * — including {@code UNCERTAIN}, which maps to the read-only
     * {@link #NAVIGATOR}. There is no "no profile" state, because a
     * turn without a profile means a turn with the full tool pool, and
     * the full tool pool is the thing we are fixing.
     */
    public static Profile forIntent(IntentClassifier.Kind kind) {
        if (kind == null) return NAVIGATOR;
        switch (kind) {
            case CODE_CHANGE: return BUILDER;
            case DIAGNOSE:   return DEBUGGER;
            case RESEARCH:   return RESEARCHER;
            case ANSWER:     return ANALYST;
            case REVIEW:     return REVIEWER;
            case OPS:        return OPERATOR;
            case UNCERTAIN:
            default:         return NAVIGATOR;
        }
    }

    /**
     * The profile for a WHOLE verdict — R411.
     *
     * <p>The domain axis picks the posture's wording; the capability
     * axis decides whether the write channel is handed over. They are
     * separate questions, so this method answers both:
     *
     * <ol>
     *   <li>the domain profile — {@link #forIntent(Kind)};</li>
     *   <li>if that profile is read-only AND the turn asks for the
     *       working tree to change, the turn is escalated to
     *       {@link #PRODUCER}, which is write-capable and
     *       domain-neutral.</li>
     * </ol>
     *
     * <p>★ WHY THE ESCALATION IS NOT "ADD THE WRITE TOOLS TO THE
     * READ-ONLY PROFILE" ★ the read-only postures say, in their own
     * words, "do not create or modify files". A write-capable turn
     * running under that wording is a prompt that contradicts the tool
     * set — the defect R401 was written to remove. So the escalated
     * turn gets a posture that says what it is.
     *
     * <p>★ AND WHY THE WRITE-CAPABLE DOMAINS ARE NOT TOUCHED ★
     * CODE_CHANGE and OPS already grant the write channel, and their
     * postures are worth keeping: "read the surrounding code before you
     * write" is better advice than "produce exactly what was asked".
     */
    public static Profile forIntent(IntentClassifier.Intent intent) {
        if (intent == null) return NAVIGATOR;
        Profile base = forIntent(intent.kind());
        if (intent.requestsWorkspaceChange() && !grantsWrite(base)) {
            return PRODUCER;
        }
        return base;
    }

    /**
     * Does this profile leave a write channel in the pool?
     *
     * <p>★ THE DENY SET IS THE TRUTH ★ a profile that denies none of
     * the write tools keeps them; one that denies any of them does not.
     * The question is asked of the DENY LIST rather than of a list of
     * "write-capable profiles", because a new profile would otherwise
     * have to be added to that list — and a list nobody updates is a
     * list that silently loses a capability.
     */
    public static boolean grantsWrite(Profile profile) {
        if (profile == null) return false;
        Set<String> denied = profile.deniedTools();
        if (denied == null || denied.isEmpty()) return true;
        for (String w : WRITE_TOOLS) {
            if (denied.contains(w)) return false;
        }
        return true;
    }

    /**
     * Remove the profile's forbidden tools from a pool.
     *
     * <p>Both the LLM-visible list and the executor's lookup go through
     * this method, so a pruned tool is not merely hidden from the model
     * — it is not resolvable at execution time either.
     *
     * @param pool the full tool pool (never null in production)
     * @param profile the profile to apply (null = no pruning)
     * @return the pruned pool; the SAME instance when nothing is pruned
     */
    public static List<Tool> prune(List<Tool> pool, Profile profile) {
        if (pool == null || pool.isEmpty() || profile == null) return pool;
        if (!profile.prunes()) return pool;
        Set<String> allowed = profile.allowedTools();
        Set<String> denied = profile.deniedTools();
        List<Tool> out = new ArrayList<>(pool.size());
        for (Tool t : pool) {
            String n = t == null ? null : t.name();
            if (n == null) continue;
            if (denied != null && denied.contains(n)) continue;
            if (allowed != null && !allowed.contains(n)) continue;
            out.add(t);
        }
        return List.copyOf(out);
    }
}
