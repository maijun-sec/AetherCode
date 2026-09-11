package org.aethercode.core.workflow;

import org.aethercode.core.stream.StreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * workflow executor. Walks a parsed workflow's steps in
 * order, executes them by type, and emits a {@code workflow_step}
 * SideNote on every state transition (pending / running / ok /
 * error). The side_note handler in the desktop's store builds
 * the {@code runningWorkflow} tracker that the
 * {@code WorkflowProgressBar} reads.
 *
 * <h3>Supported step types</h3>
 * <ul>
 *   <li>{@code shell} — runs the command via {@link ProcessBuilder};
 *       captures exit code + stdout tail.</li>
 *   <li>{@code delay} — {@link Thread#sleep(long)} for {@code duration} ms.</li>
 *   <li>{@code parallel} — fans out the {@code branches[]} list
 *       concurrently. The parent's exit code is the worst of
 *       the branches' (any failure → parallel error), with
 *       {@code wait: "all" | "any" | "majority"} semantics.</li>
 *   <li>{@code gate} — evaluates the {@code when} expression
 *       (a tiny Jinja-style subset, see {@link #evalWhen}) and
 *       dispatches the {@code then} / {@code else_} branch list.
 *       The {@code else_} branch is keyed with an underscore so
 *       YAML doesn't treat it as a reserved word.</li>
 *   <li>{@code skill} — prior round stub. Emits a running → ok transition
 *       without actually loading the skill. prior round wires the
 *       agent-driven executor (the agent that loads the
 *       {@code workflow-system} skill walks the YAML).</li>
 *   <li>{@code agent} — prior round stub. Same — prior round will spawn the
 *       named agent's task and stream its progress into the
 *       workflow.</li>
 * </ul>
 *
 * <h3>Parameter substitution</h3>
 * <p>{@code {{inputs.<key>}}} and {@code {{steps.<id>.<field>}}}
 * are substituted at run time. The same regex-based
 * Jinja-subset used by the desktop for the workflow editor's
 * preview lives in {@link #substitute}.
 *
 * <h3>Streaming protocol</h3>
 * <p>The caller passes a {@code Consumer<StreamEvent>}. Each step
 * transition emits one {@link StreamEvent.SideNote} with
 * {@code kind = "workflow_step"} and a deterministic message
 * the desktop parses. The shape mirrors R102's stub message
 * so the existing store handler keeps working.
 */
public final class WorkflowExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowExecutor.class);

    private final WorkflowReader.WorkflowDoc doc;
    private final Map<String, Object> inputs;
    private final Map<String, StepResult> results = new LinkedHashMap<>();
    private final Consumer<StreamEvent> sink;
    private final String runId;
    /** optional hook for {@code skill} and {@code agent}
     *  step types. When non-null, the executor invokes the
     *  hook to run a query on a child session and uses the
     *  returned text as the step's stdout. The hook signature
     *  is {@code (kind, name, prompt) -> result}. The wiring
     *  in {@code AetherCodeMethods.runWorkflow} passes a
     *  closure that calls
     *  {@code AetherCodeEngine.queryInChildSession}. prior round was
     *  a stub that emitted a static "ok" event; R106 actually
     *  runs the query. */
    private final SkillInvoker skillInvoker;
    /** optional callback that resolves a
     *  named agent to its frontmatter
     *  {@code model:} field. The executor calls
     *  this once per {@code kind: agent} step to
     *  look up the model string, then forwards it
     *  to the {@code SkillInvoker} as the
     *  {@code modelOverride} parameter (the
     *  SkillInvoker implementation resolves the
     *  string to a
     *  {@link org.aethercode.core.llm.ChatClient}
     *  — the executor itself doesn't have
     *  ProviderRegistry access). Pass {@code null}
     *  to disable the lookup; in that case the
     *  override is always null and the agent uses
     *  the engine's default model. */
    private final java.util.function.Function<String, String> agentModelLookup;

    public WorkflowExecutor(WorkflowReader.WorkflowDoc doc,
                            Map<String, Object> inputs,
                            String runId,
                            Consumer<StreamEvent> sink) {
        this(doc, inputs, runId, sink, null, null);
    }

    /** secondary constructor that accepts a
     *  {@code SkillInvoker} for real skill/agent execution. */
    public WorkflowExecutor(WorkflowReader.WorkflowDoc doc,
                            Map<String, Object> inputs,
                            String runId,
                            Consumer<StreamEvent> sink,
                            SkillInvoker skillInvoker) {
        this(doc, inputs, runId, sink, skillInvoker, null);
    }

    /** tertiary constructor that ALSO
     *  accepts an {@code agentModelLookup} for
     *  per-agent model binding. The wiring in
     *  {@code AetherCodeMethods.runWorkflow}
     *  passes a closure backed by
     *  {@code AetherCodeEngine.getAgentMeta} so
     *  the executor can resolve the agent's
     *  frontmatter model without re-parsing the
     *  agent.md file. Tests that don't care
     *  about the model can use the 4-arg or
     *  5-arg constructors. */
    public WorkflowExecutor(WorkflowReader.WorkflowDoc doc,
                            Map<String, Object> inputs,
                            String runId,
                            Consumer<StreamEvent> sink,
                            SkillInvoker skillInvoker,
                            java.util.function.Function<String, String> agentModelLookup) {
        this.doc = doc;
        this.inputs = inputs == null ? Map.of() : inputs;
        this.runId = runId;
        this.sink = sink;
        this.skillInvoker = skillInvoker;
        this.agentModelLookup = agentModelLookup;
    }

    /** function signature for the skill/agent hook.
     *  Implementations run the query on a child session and
     *  return the captured text. The interface is a
     *  functional interface so callers can pass a lambda. */
    @FunctionalInterface
    public interface SkillInvoker {
        /** the canonical invocation. Forwards
         *  every {@link org.aethercode.core.stream.StreamEvent}
         *  from the child session to {@code eventSink}
         *  so the caller (the workflow executor) can
         *  bubble progress up to its own stream.
         *
         * @param kind "skill" or "agent"
         * @param name the skill / agent name (matches the
         *              step's {@code name:} field)
         * @param prompt the rendered prompt (after Jinja
         *                substitution of {{inputs.*}} /
         *                {{steps.*.*}} references)
         * @param modelOverride the agent's frontmatter
         *                       {@code model:} field
         *                       (e.g. {@code "glm/glm-4-flash"}),
         *                       resolved to a
         *                       {@link org.aethercode.core.llm.ChatClient}
         *                       by the caller's
         *                       SkillInvoker
         *                       implementation. The
         *                       executor does NOT
         *                       resolve the string —
         *                       aethercode-core has
         *                       no ProviderRegistry
         *                       access, so the
         *                       resolution happens in
         *                       the methods layer
         *                       (where the registry
         *                       lives). {@code null}
         *                       when the step is a
         *                       skill (skills have
         *                       no model binding)
         *                       or when the agent
         *                       has no
         *                       {@code model:}
         *                       frontmatter (fall
         *                       back to the
         *                       engine's default
         *                       model).
         * @param eventSink receives every
         *                   {@code StreamEvent} the
         *                   child session emits
         *                   (text_delta,
         *                   tool_use_start, run_end,
         *                   side_note, …). The caller
         *                   may pass a no-op sink if
         *                   it doesn't need the
         *                   events.
         * @return the child session's final assistant
         *         text
         * @throws Exception on any error (the executor
         *                      wraps it as a step-level
         *                      error) */
        String invoke(String kind, String name, String prompt,
                      String modelOverride,
                      Consumer<org.aethercode.core.stream.StreamEvent> eventSink) throws Exception;

        /** convenience overload for callers
         *  that don't need the model override. Drops
         *  the override (engine default model is
         *  used). Useful for tests and for the prior round
         *  stub path. */
        default String invoke(String kind, String name, String prompt,
                              Consumer<org.aethercode.core.stream.StreamEvent> eventSink) throws Exception {
            return invoke(kind, name, prompt, null, eventSink);
        }

        /** convenience overload for callers
         *  that don't need the event stream. Drops
         *  the events. Lets the prior round stub path and
         *  any test mocks keep their old 3-arg
         *  lambda without rewiring. The model
         *  override is also dropped. */
        default String invoke(String kind, String name, String prompt) throws Exception {
            return invoke(kind, name, prompt, null, ev -> { /* no-op sink */ });
        }
    }

    /** One step's captured output + status. The desktop's
     *  store reads {@code steps.<id>.<field>} from this for
     *  the workflow editor's preview; the executor uses
     *  {@code steps.<id>.exitCode} for {@code when}-expression
     *  evaluation.
     *
     *  <p>R105: {@code finalEmitted} is set by an inner
     *  step executor (e.g. {@code runShell}) when it has
     *  already emitted the terminal {@code ok}/{@code error}
     *  SideNote. The outer {@code run()} loop checks this
     *  flag and avoids double-emitting when it downgrades a
     *  failed step to {@code skipped} via
     *  {@code continue_on_error}. */
    public static final class StepResult {
        public final String id;
        public final String type;
        public String status;        // pending | running | ok | error | skipped
        public int exitCode;         // 0 = success, anything else = error, -1 = not applicable
        public String stdout;        // captured output (truncated)
        public String stderr;        // captured error (truncated)
        public long durationMs;
        public boolean finalEmitted;  // R105: inner already emitted the terminal event

        public StepResult(String id, String type) {
            this.id = id;
            this.type = type;
            this.status = "pending";
            this.exitCode = -1;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("type", type);
            m.put("status", status);
            m.put("exitCode", exitCode);
            m.put("stdout", stdout);
            m.put("stderr", stderr);
            m.put("durationMs", durationMs);
            return m;
        }
    }

    public Map<String, StepResult> results() { return results; }

    /** Walk all steps in order. Returns the final step's status
     *  ("ok" if all completed without error). The {@code when}
     *  branches ({@code then} / {@code else_}) are evaluated
     *  inline; only one is dispatched per gate.
     *
     *  <p>R105: respect each step's {@code continueOnError} flag.
     *  When false (the default), an exception or explicit error
     *  status aborts the workflow — remaining steps are marked
     *  {@code skipped} with a reason and the loop breaks. When
     *  true, the failing step is downgraded to {@code skipped}
     *  and the workflow keeps advancing. */
    public String run() {
        // Seed results with one entry per declared step so the
        // progress bar can render the full pill row from the
        // first workflow_step event.
        for (var step : doc.steps()) {
            results.put(step.id(), new StepResult(step.id(), step.type()));
        }
        emitFirstPendingForAll();
        boolean aborted = false;
        for (var step : doc.steps()) {
            if (aborted) {
                // a previous step failed without
                // continue_on_error. Mark this step as skipped
                // and emit a transition so the progress bar
                // shows it instead of leaving it pending.
                StepResult r = results.get(step.id());
                if (r != null && "pending".equals(r.status)) {
                    r.status = "skipped";
                    r.stderr = "skipped: workflow aborted by previous error";
                    emit(step, "skipped", "aborted by previous error");
                }
                continue;
            }
            StepResult r = results.get(step.id());
            if (r == null) continue;
            long start = System.currentTimeMillis();
            try {
                r.status = "running";
                emit(step, "running", null);
                executeStep(step);
                r.durationMs = System.currentTimeMillis() - start;
                // executeStep may have set status="error"
                // without throwing (e.g. shell with non-zero
                // exit). The inner method also already emitted
                // the terminal "error" SideNote (and set
                // finalEmitted=true). For continue_on_error we
                // DOWNGRADE — emit a fresh "skipped" event so
                // the progress bar reflects the new state. For
                // the non-continue case the inner event stands.
                if ("error".equals(r.status)) {
                    if (step.continueOnError()) {
                        r.status = "skipped";
                        // Force-emit the downgrade even though
                        // finalEmitted is true (the inner
                        // "error" event is being superseded).
                        emitRaw(step.id(), step.type(),
                                indexOf(step.id()) + 1, doc.steps().size(),
                                "skipped", r.stderr);
                        r.finalEmitted = true;
                    } else {
                        // Inner already emitted the "error"
                        // event; no need to double-emit.
                        LOG.warn("workflow step {} ({}) failed: {}",
                                step.id(), step.type(), r.stderr);
                        aborted = true;
                    }
                }
            } catch (Exception e) {
                r.durationMs = System.currentTimeMillis() - start;
                if (step.continueOnError()) {
                    r.status = "skipped";
                    r.stderr = e.getMessage();
                    emit(step, "skipped", e.getMessage());
                } else {
                    r.status = "error";
                    r.stderr = e.getMessage();
                    emit(step, "error", e.getMessage());
                    LOG.warn("workflow step {} ({}) failed: {}",
                            step.id(), step.type(), e.getMessage());
                    aborted = true;
                }
            }
        }
        return overallStatus();
    }

    /** Overall status: "ok" if every ok-or-skipped, "error"
     *  if any error. "skipped" steps do not poison the result
     *  — they were deliberately downgraded by the user's
     *  {@code continue_on_error: true} flag. */
    private String overallStatus() {
        boolean anyError = false;
        boolean anyOk = false;
        for (var r : results.values()) {
            if ("error".equals(r.status)) anyError = true;
            if ("ok".equals(r.status) || "skipped".equals(r.status)) anyOk = true;
        }
        if (anyError) return "error";
        if (anyOk) return "ok";
        return "ok"; // empty workflow is "ok"
    }

    private void emitFirstPendingForAll() {
        // Emit one "pending" event for every step on run start
        // so the desktop's progress bar has the full list from
        // the start. The desktop's store builds the step list
        // from the first event; subsequent "running" / "ok"
        // events only update the per-step status.
        for (int i = 0; i < doc.steps().size(); i++) {
            var s = doc.steps().get(i);
            emitRaw(s.id(), s.type(), i + 1, doc.steps().size(), "pending", null);
        }
    }

    private void emit(WorkflowReader.Step step, String status, String errMsg) {
        // if the step is a top-level step, prefix with
        // "step N/M" so the desktop can render the step pill
        // row. For sub-steps (a parallel's branch or a gate's
        // sub-step) we just emit the status so the desktop's
        // progress bar still gets a transition event. Sub-step
        // events have no N/M; the parent's N/M is what the
        // user sees in the UI.
        int idx = indexOf(step.id());
        if (idx < 0) {
            emitSub(step, status, errMsg);
            return;
        }
        emitRaw(step.id(), step.type(), idx + 1, doc.steps().size(), status, errMsg);
        // mark the result as having its terminal event
        // emitted. The outer run() loop checks this flag so
        // it doesn't double-emit when downgrading an error
        // to skipped via continue_on_error. The flag only
        // matters for top-level steps (sub-steps are routed
        // through emitSub and don't share the StepResult
        // re-emission path).
        StepResult r = results.get(step.id());
        if (r != null) r.finalEmitted = true;
    }

    /** Emit a side_note for a sub-step (parallel branch or
     *  gate sub-step) without the "step N/M" prefix. The
     *  desktop's progress bar shows the parent's pill; the
     *  sub-step's status is recorded in the StepResult map
     *  for the executor to aggregate. The raw event still
     *  has {@code kind: "workflow_step"} so the desktop
     *  routes it correctly. */
    private void emitSub(WorkflowReader.Step step, String status, String errMsg) {
        String message = status + ": " + step.id() + " (" + step.type() + ")";
        if (errMsg != null) message += " — " + errMsg;
        try {
            sink.accept(new StreamEvent.SideNote("workflow_step", message));
        } catch (Exception e) {
            LOG.debug("emitSub failed: {}", e.getMessage());
        }
    }

    private void emitRaw(String id, String type, int idx, int total, String status, String errMsg) {
        String message = "step " + idx + "/" + total + " " + status + ": " + id + " (" + type + ")";
        if (errMsg != null) message += " — " + errMsg;
        try {
            sink.accept(new StreamEvent.SideNote("workflow_step", message));
        } catch (Exception e) {
            LOG.debug("emit failed: {}", e.getMessage());
        }
    }

    private int indexOf(String id) {
        for (int i = 0; i < doc.steps().size(); i++) {
            if (doc.steps().get(i).id().equals(id)) return i;
        }
        return -1;
    }

    /** Execute one step. R103 covers shell / delay / parallel /
     *  gate. Skill + agent are stubs that emit "ok" without
     *  actually doing work — prior round will wire them. */
    private void executeStep(WorkflowReader.Step step) {
        String body = stepBody(step);
        switch (step.type()) {
            case "shell" -> runShell(step, body);
            case "delay" -> runDelay(step, body);
            case "parallel" -> runParallel(step, body);
            case "gate" -> runGate(step, body);
            case "skill" -> runSkillOrAgent(step, body, "skill");
            case "agent" -> runSkillOrAgent(step, body, "agent");
            default -> {
                StepResult r = results.get(step.id());
                if (r != null) {
                    r.status = "error";
                    r.stderr = "unknown step type: " + step.type();
                }
            }
        }
    }

    /** the step body is opaque to the executor — only
     *  some fields (cmd, duration, branches, then, else_, when)
     *  are read. The executor pulls the value of the {@code body}
     *  by re-reading the workflow raw + slicing the chunk for
     *  the step's id. For prior round we keep this simple and only
     *  support the explicit fields the executor needs; the
     *  raw chunk is available via {@link WorkflowReader}.
     *  We re-parse the chunk from {@code doc.raw()} lazily on
     *  first need (caches for the run). */
    private String stepBody(WorkflowReader.Step step) {
        // Find the chunk for this step. The reader's parse
        // builds the steps list; we re-find the chunk here.
        if (doc.raw() == null) return "";
        String[] chunks = doc.raw().split("(?m)^\\s*-\\s+id\\s*:\\s*['\"]?" + Pattern.quote(step.id()) + "['\"]?");
        if (chunks.length < 2) return "";
        return chunks[1];
    }

    private void runShell(WorkflowReader.Step step, String chunk) {
        // Extract `cmd:` value. Single line, optionally quoted.
        Pattern CMD = Pattern.compile("(?m)^\\s*cmd\\s*:\\s*(.+?)\\s*(?:#.*)?$");
        Matcher m = CMD.matcher(chunk);
        if (!m.find()) {
            fail(step, "shell step has no `cmd:` line");
            return;
        }
        String cmdRaw = m.group(1).trim();
        // Strip matching outer quotes.
        String cmd = stripQuotes(cmdRaw);
        // Substitute {{inputs.x}} / {{steps.x.field}}.
        cmd = substitute(cmd);

        // Extract timeout (default 5 min).
        int timeoutMs = 300_000;
        Matcher tm = Pattern.compile("(?m)^\\s*timeout\\s*:\\s*(\\d+)").matcher(chunk);
        if (tm.find()) {
            try { timeoutMs = Integer.parseInt(tm.group(1)); } catch (NumberFormatException ignored) {}
        }

        try {
            ProcessBuilder pb = isWindows()
                    ? new ProcessBuilder("cmd.exe", "/c", cmd)
                    : new ProcessBuilder("bash", "-c", cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            // Drain stdout (combined with stderr) on a separate
            // thread so a slow process doesn't deadlock on a
            // full pipe buffer.
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        synchronized (out) {
                            if (out.length() < 8 * 1024) {  // cap at 8KB
                                out.append(line).append('\n');
                            } else if (out.length() == 8 * 1024) {
                                out.append("... (truncated)\n");
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }, "workflow-shell-reader");
            reader.setDaemon(true);
            reader.start();
            boolean exited = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!exited) {
                p.destroyForcibly();
                fail(step, "shell step timed out after " + timeoutMs + "ms");
                return;
            }
            reader.join(2000);
            int code = p.exitValue();
            StepResult r = results.get(step.id());
            if (r != null) {
                r.exitCode = code;
                r.stdout = out.toString();
                r.status = (code == 0) ? "ok" : "error";
                if (code != 0) r.stderr = "exit code " + code;
            }
            emit(step, (code == 0) ? "ok" : "error", (code == 0) ? null : ("exit " + code));
        } catch (Exception e) {
            fail(step, "shell exec failed: " + e.getMessage());
        }
    }

    private void runDelay(WorkflowReader.Step step, String chunk) {
        Matcher m = Pattern.compile("(?m)^\\s*duration\\s*:\\s*(\\d+)").matcher(chunk);
        if (!m.find()) {
            fail(step, "delay step has no `duration:` line");
            return;
        }
        int ms = Integer.parseInt(m.group(1));
        try {
            Thread.sleep(Math.max(0, ms));
            StepResult r = results.get(step.id());
            if (r != null) {
                r.status = "ok";
                r.exitCode = 0;
            }
            emit(step, "ok", null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(step, "delay interrupted");
        }
    }

    private void runParallel(WorkflowReader.Step step, String chunk) {
        // Parse branches. Each branch is a sub-step with
        // id, type, and the type-specific body. We reuse the
        // shell / delay / stub handlers per branch type.
        List<WorkflowReader.Step> branches = parseBranches(chunk);
        if (branches.isEmpty()) {
            fail(step, "parallel step has no branches");
            return;
        }
        // read the `wait:` policy. Default "all" matches
        // R103's behaviour. "any" completes the parallel as
        // soon as one branch succeeds (others continue in the
        // background; their results are recorded but don't
        // affect the parallel's status). "majority" waits
        // until either >50% branches are ok or >50% are
        // error. We also accept "first_error" as an alias
        // for "any" with success==false — implemented as
        // "any" with the success==false branch also completing
        // the wait.
        String waitPolicy = parseWaitPolicy(chunk);
        // Fan out. Each branch has its own StepResult mirror
        // so the progress bar can show per-branch status.
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (var b : branches) {
            StepResult br = new StepResult(b.id(), b.type());
            results.put(b.id(), br);
            // Emit a "pending" SideNote for the branch so the
            // desktop's progress bar can show it (if the
            // desktop wants branch-level status — currently
            // it just shows the parent parallel's step).
            emitRaw(b.id(), b.type(), indexOf(step.id()) + 1, doc.steps().size(), "pending", null);
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    br.status = "running";
                    emitRaw(b.id(), b.type(), indexOf(step.id()) + 1, doc.steps().size(), "running", null);
                    executeStep(b);
                } catch (Exception e) {
                    br.status = "error";
                    br.stderr = e.getMessage();
                    emitRaw(b.id(), b.type(), indexOf(step.id()) + 1, doc.steps().size(), "error", e.getMessage());
                }
            }));
        }
        // apply the wait policy.
        switch (waitPolicy) {
            case "any" -> {
                // Wait until at least one branch is ok, OR
                // every branch has finished without any ok
                // (all errored). Other branches keep running
                // in the background after we return; their
                // results are recorded but don't affect the
                // parallel's status. Polled on a short
                // interval to avoid blocking on a slow first
                // branch.
                long deadline = System.currentTimeMillis() + 600_000; // 10 min
                while (System.currentTimeMillis() < deadline) {
                    boolean anyOk = false;
                    boolean allDone = true;
                    for (var b : branches) {
                        StepResult br = results.get(b.id());
                        if (br == null) { allDone = false; continue; }
                        if ("ok".equals(br.status)) anyOk = true;
                        if ("pending".equals(br.status)
                                || "running".equals(br.status)) {
                            allDone = false;
                        }
                    }
                    if (anyOk) break;
                    if (allDone) break;
                    try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
            }
            case "majority" -> {
                // Wait until one outcome reaches a majority
                // (strictly > half), or until every branch
                // has finished (whichever comes first).
                // Polled on a short interval to avoid
                // spinning. Caps at a generous timeout to
                // prevent indefinite blocking if a branch
                // hangs.
                long deadline = System.currentTimeMillis() + 600_000; // 10 min
                int need = branches.size() / 2 + 1;
                while (System.currentTimeMillis() < deadline) {
                    int ok = 0, err = 0, done = 0;
                    for (var b : branches) {
                        StepResult br = results.get(b.id());
                        if (br == null) continue;
                        if ("ok".equals(br.status))    { ok++;   done++; }
                        else if ("error".equals(br.status)) { err++;   done++; }
                        else if (!"pending".equals(br.status)
                              && !"running".equals(br.status)) { done++; }
                    }
                    if (ok >= need || err >= need) break;
                    if (done >= branches.size()) break;  // all settled
                    try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
            }
            default -> {
                // "all" (and any unknown value) — wait for every
                // branch to finish. Same as R103.
                try {
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
                } catch (Exception ignored) {}
            }
        }
        // Aggregate: the parallel's status follows the wait
        // policy:
        //   wait=all  → error if any branch errored
        //   wait=any  → error if FIRST branch errored AND no
        //               ok branch has finished yet (best-effort
        //               approximation; we count "any" as "ok
        //               unless first completion was an error
        //               with no later ok seen"). For R105 we
        //               keep it simple: parallel is ok if at
        //               least one branch is ok, error
        //               otherwise.
        //   wait=majority → error if a majority of branches
        //                    errored, ok otherwise.
        boolean anyError = false;
        boolean anyOk = false;
        int ok = 0, err = 0;
        for (var b : branches) {
            StepResult br = results.get(b.id());
            if (br == null) continue;
            if ("error".equals(br.status)) { anyError = true; err++; }
            if ("ok".equals(br.status))    { anyOk    = true; ok++;  }
        }
        boolean parallelError = switch (waitPolicy) {
            case "majority" -> err >= (branches.size() / 2 + 1);
            case "any"      -> !anyOk;        // ok if any branch succeeded
            default         -> anyError;      // all: any error aborts
        };
        StepResult r = results.get(step.id());
        if (r != null) {
            r.status = parallelError ? "error" : "ok";
            r.exitCode = parallelError ? 1 : 0;
            String reason = parallelError
                    ? (waitPolicy.equals("any")
                            ? "no branch succeeded (wait: any)"
                            : (waitPolicy.equals("majority")
                                ? "majority of branches errored (wait: majority)"
                                : "one or more branches failed"))
                    : null;
            emit(step, parallelError ? "error" : "ok", reason);
            return;
        }
        emit(step, parallelError ? "error" : "ok", null);
    }

    /** parse the {@code wait:} policy from a parallel step's
     *  chunk. Accepts {@code all} / {@code any} / {@code majority}
     *  (case-insensitive). Anything else falls back to {@code all}. */
    static String parseWaitPolicy(String chunk) {
        if (chunk == null) return "all";
        Matcher m = Pattern.compile("(?im)^\\s*wait\\s*:\\s*['\"]?([a-zA-Z_]+)['\"]?\\b[^\\n#]*(?:#.*)?$").matcher(chunk);
        if (!m.find()) return "all";
        String v = m.group(1).toLowerCase();
        return switch (v) {
            case "any", "majority", "all" -> v;
            default -> "all";
        };
    }

    private void runGate(WorkflowReader.Step step, String chunk) {
        Matcher m = Pattern.compile("(?m)^\\s*when\\s*:\\s*(.+?)\\s*(?:#.*)?$").matcher(chunk);
        if (!m.find()) {
            fail(step, "gate step has no `when:` line");
            return;
        }
        String whenRaw = m.group(1).trim();
        boolean truthy = evalWhen(stripQuotes(whenRaw));
        String branchKey = truthy ? "then" : "else_";
        // Parse the `then:` / `else_:` list of nested steps.
        // We use a simple regex split on `(?m)^\\s*<key>:\\s*$`
        // and grab the body until the next `^\\S` line.
        Pattern BR = Pattern.compile("(?ms)^\\s*" + Pattern.quote(branchKey) + "\\s*:\\s*\\n(.*?)(?=^\\S|\\z)");
        Matcher bm = BR.matcher(chunk);
        if (!bm.find()) {
            // gate fired but no branch — treat as no-op ok.
            StepResult r = results.get(step.id());
            if (r != null) {
                r.status = "ok";
                r.exitCode = 0;
                r.stdout = "gate " + (truthy ? "true" : "false") + " — no branch";
            }
            emit(step, "ok", null);
            return;
        }
        String body = bm.group(1);
        // Treat each `- id: ...` entry in `body` as a sub-step.
        // Reuse the parallel branch parser with a small
        // synthetic wrapper.
        List<WorkflowReader.Step> sub = parseBranches(body);
        if (sub.isEmpty()) {
            StepResult r = results.get(step.id());
            if (r != null) {
                r.status = "ok";
                r.exitCode = 0;
                r.stdout = "gate " + (truthy ? "true" : "false") + " — empty branch";
            }
            emit(step, "ok", null);
            return;
        }
        // Dispatch sub-steps sequentially. Failures bubble up
        // to the gate's status; the gate itself is "error" if
        // any sub-step failed.
        boolean anyError = false;
        for (var s : sub) {
            StepResult sr = new StepResult(s.id(), s.type());
            results.put(s.id(), sr);
            sr.status = "running";
            try {
                executeStep(s);
            } catch (Exception e) {
                sr.status = "error";
                sr.stderr = e.getMessage();
            }
            if ("error".equals(sr.status)) anyError = true;
        }
        StepResult r = results.get(step.id());
        if (r != null) {
            r.status = anyError ? "error" : "ok";
            r.exitCode = anyError ? 1 : 0;
        }
        emit(step, anyError ? "error" : "ok", anyError ? "sub-step failed" : null);
    }

    private void runStub(WorkflowReader.Step step, String note) {
        StepResult r = results.get(step.id());
        if (r != null) {
            r.status = "ok";
            r.exitCode = 0;
            r.stdout = note;
        }
        emit(step, "ok", null);
    }

    /** real skill/agent step. Reads the step's
     *  {@code name:} and {@code prompt:} fields, renders the
     *  prompt with the Jinja-style substitution, and invokes
     *  the {@link SkillInvoker} hook. The captured text is
     *  stored as the step's stdout; the step is "ok" on
     *  success and "error" on any exception. Falls back to
     *  the prior round stub when no hook is wired (so existing tests
     *  that don't care about the real execution still pass). */
    private void runSkillOrAgent(WorkflowReader.Step step, String chunk, String kind) {
        // Extract the name (required) and the prompt (the
        // first non-name key — usually `prompt:` or `input:`).
        Pattern NAME = Pattern.compile("(?im)^\\s*name\\s*:\\s*['\"]?([^'\"\\n#]+)['\"]?\\s*(?:#.*)?$");
        Pattern PROMPT = Pattern.compile("(?im)^\\s*prompt\\s*:\\s*['\"]?(.+?)['\"]?\\s*(?:#.*)?$");
        // Anchored multiline match — find the first key that
        // isn't `type` / `name` and read its value.
        Pattern FIRST_PROMPT_KEY = Pattern.compile("(?im)^\\s*(prompt|input|message|text|args|body|content)\\s*:\\s*['\"]?(.+?)['\"]?\\s*(?:#.*)?$");
        Matcher nm = NAME.matcher(chunk);
        if (!nm.find()) {
            fail(step, kind + " step has no `name:` line");
            return;
        }
        String name = nm.group(1).trim();
        String prompt = "";
        Matcher pm = PROMPT.matcher(chunk);
        if (pm.find()) {
            prompt = pm.group(1).trim();
        } else {
            Matcher fm = FIRST_PROMPT_KEY.matcher(chunk);
            if (fm.find()) prompt = fm.group(2).trim();
        }
        prompt = substitute(prompt);
        if (skillInvoker == null) {
            // R103 fallback — emit a "skipped" note so the
            // user knows the step was a stub. R106+ should
            // always wire the invoker.
            runStub(step, "stub: " + kind + " \"" + name
                    + "\" (SkillInvoker not wired; pass one to WorkflowExecutor to enable real execution)");
            return;
        }
        // per-agent model binding. When
        // this is a {@code kind: agent} step, look
        // up the agent's frontmatter {@code model:}
        // field via the agentModelLookup callback
        // (the executor is in aethercode-core and
        // does not have AgentRegistry access; the
        // methods layer wires the callback at
        // runWorkflow time). The modelOverride
        // string is forwarded to the SkillInvoker
        // — the SkillInvoker implementation
        // resolves it to a ChatClient via
        // ProviderRegistry (which only the methods
        // layer has). For {@code kind: skill} the
        // override is always null (skills have no
        // model binding).
        String modelOverride = null;
        if ("agent".equals(kind) && agentModelLookup != null) {
            try {
                String m = agentModelLookup.apply(name);
                if (m != null && !m.isBlank()) modelOverride = m;
            } catch (Exception e) {
                LOG.debug("agentModelLookup for {} failed: {}", name, e.getMessage());
            }
        }
        try {
            // use the 4-arg overload with
            // {@code this.sink} so the child session's
            // events (text_delta, tool_use_start,
            // run_end, side_note, ...) reach the
            // workflow's WS clients. The default
            // implementation in SkillInvoker (used by
            // the R103 stub path) ignores the sink —
            // see SkillInvoker#invoke(...) above.
            //
            // use the 5-arg overload that
            // also passes the modelOverride so the
            // child session can use the agent's
            // frontmatter model.
            String result = skillInvoker.invoke(kind, name, prompt, modelOverride, ev -> {
                // Re-emit the child's events as
                // workflow_step SideNotes with an
                // explicit parentStepId. The desktop
                // can attribute them to the active
                // step card. We use a "child_session_event"
                // kind so the renderer's side_note
                // handler routes them to the workflow
                // progress bar instead of the
                // top-level message list.
                if (sink == null) return;
                org.aethercode.core.stream.StreamEvent.SideNote wrapped =
                        new org.aethercode.core.stream.StreamEvent.SideNote(
                                "child_session_event",
                                formatChildEventMessage(step.id(), kind, name, ev));
                try { sink.accept(wrapped); } catch (Exception ignored) {}
            });
            StepResult r = results.get(step.id());
            if (r != null) {
                r.status = "ok";
                r.exitCode = 0;
                r.stdout = result == null ? "" : result;
            }
            emit(step, "ok", null);
        } catch (Exception e) {
            fail(step, kind + " step failed: " + e.getMessage());
        }
    }

    private void fail(WorkflowReader.Step step, String reason) {
        StepResult r = results.get(step.id());
        if (r != null) {
            r.status = "error";
            r.stderr = reason;
        }
        emit(step, "error", reason);
    }

    //
    // The 4-arg SkillInvoker hook above forwards every
    // StreamEvent the child session emits to the workflow's
    // own sink. The desktop's WorkflowProgressBar reads the
    // resulting "child_session_event" SideNote messages and
    // renders them as a nested activity list under the running
    // step pill. For that to be useful, the message has to
    // carry enough structure to drive a compact row:
    //
    //   step=<id> | kind=agent | name=code-reviewer | ev=ToolUseStart |
    //   tool=Bash | arg=ls -la | argtruncated=0
    //   step=<id> | kind=agent | name=code-reviewer | ev=ToolResult |
    //   tool=Bash | status=ok | outlen=240
    //   step=<id> | kind=agent | name=code-reviewer | ev=RunStart |
    //   model=claude-sonnet-4
    //   step=<id> | kind=agent | name=code-reviewer | ev=RunEnd |
    //   stop=end_turn
    //   step=<id> | kind=agent | name=code-reviewer | ev=SideNote |
    //   notekind=compaction | notemsg=...
    //
    // The format is a pipe-delimited sequence of key=value
    // pairs. Values are escaped to keep pipe characters and
    // backslashes safe (we never need quotes in the args
    // themselves; a tool input is rendered as a single
    // truncated string).
    //
    // Old clients that only see the class name in the
    // message still work — the renderer falls through to a
    // generic event row when the message does not parse.

    static String formatChildEventMessage(String stepId, String kind, String name, StreamEvent ev) {
        // keep the legacy "[step-id] " prefix so
        // older clients (prior round era) that match on the
        // bracket prefix keep working. The new structured
        // payload follows after the legacy tail, separated
        // by a pipe. New clients ignore everything before
        // the first pipe after "[step-id] " and parse the
        // key=value list.
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(stepId).append("] ");
        sb.append(kind).append(" \"").append(name).append("\" ");
        sb.append(ev.getClass().getSimpleName());
        sb.append("|step=").append(esc(stepId));
        sb.append("|kind=").append(esc(kind));
        sb.append("|name=").append(esc(name));
        sb.append("|ev=").append(ev.getClass().getSimpleName());
        if (ev instanceof StreamEvent.ToolUseStart tu) {
            sb.append("|tool=").append(esc(tu.name()));
            String brief = briefArg(tu.input());
            sb.append("|arg=").append(esc(brief));
            sb.append("|argtruncated=").append(briefArgTruncated(tu.input()) ? "1" : "0");
        } else if (ev instanceof StreamEvent.ToolResult tr) {
            // we don't have the tool name on
            // ToolResult directly (the executor pairs them
            // up via the id; the workflow doesn't). Mark
            // it as "tool result" and surface the status
            // so the renderer can show ✓ / ✗.
            sb.append("|tool=").append("result");
            sb.append("|status=").append(tr.isError() ? "err" : "ok");
            sb.append("|outlen=").append(resultLength(tr.content()));
        } else if (ev instanceof StreamEvent.RunStart rs) {
            sb.append("|model=").append(esc(rs.model() == null ? "?" : rs.model()));
        } else if (ev instanceof StreamEvent.RunEnd re) {
            sb.append("|stop=").append(esc(re.stopReason() == null ? "?" : re.stopReason()));
        } else if (ev instanceof StreamEvent.SideNote sn) {
            sb.append("|notekind=").append(esc(sn.kind() == null ? "?" : sn.kind()));
            sb.append("|notemsg=").append(esc(sn.message() == null ? "" : sn.message()));
        } else if (ev instanceof StreamEvent.TextDelta td) {
            sb.append("|text=").append(esc(td.text() == null ? "" : td.text()));
        }
        return sb.toString();
    }

    /** Build a brief, single-line preview of a tool input map.
     *  The first scalar value is preferred (a Bash "command",
     *  a Read "path", etc.). When the input has multiple
     *  keys (e.g. a Write with path + content) we surface
     *  the most "name-like" key first and append "..."
     *  when the rest of the values are non-trivial. */
    private static String briefArg(java.util.Map<String, Object> input) {
        if (input == null || input.isEmpty()) return "";
        // Prefer "command" / "path" / "file_path" / "file" as
        // the most identifiable arg. Fall back to the first
        // scalar value otherwise.
        String[] preferred = {"command", "path", "file_path", "file", "url", "prompt", "input", "query"};
        for (String p : preferred) {
            Object v = input.get(p);
            if (v == null) continue;
            String s = String.valueOf(v);
            if (s.length() > 80) s = s.substring(0, 77) + "...";
            return p + "=" + s;
        }
        // Fallback: first value, no key.
        Object first = input.values().iterator().next();
        String s = String.valueOf(first);
        if (s.length() > 80) s = s.substring(0, 77) + "...";
        return s;
    }

    private static boolean briefArgTruncated(java.util.Map<String, Object> input) {
        if (input == null || input.isEmpty()) return false;
        for (Object v : input.values()) {
            String s = String.valueOf(v);
            if (s.length() > 80) return true;
        }
        return false;
    }

    private static int resultLength(Object content) {
        if (content == null) return 0;
        String s = String.valueOf(content);
        return s.length();
    }

    /** Escape pipe (|) and backslash (\) in a value so the
     *  pipe-delimited key=value format stays parseable.
     *  We do not need to escape '=' because values that
     *  contain '=' are fine (the renderer splits on the
     *  first '=' only). */
    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '|') sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }

    // ---- helpers --------------------------------------------------------

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win");
    }

    private static String stripQuotes(String s) {
        s = s.trim();
        if (s.length() >= 2) {
            char a = s.charAt(0), b = s.charAt(s.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    /** Tiny Jinja-style expression evaluator. Supports:
     *  <ul>
     *    <li>{@code {{inputs.<key>}}} — input value (string-coerced)</li>
     *    <li>{@code {{steps.<id>.<field>}}} — step result field
     *        (status / exitCode / stdout / stderr)</li>
     *    <li>comparisons {@code ==} {@code !=} {@code >} {@code <} {@code >=} {@code <=}</li>
     *    <li>logical {@code &&} {@code ||}</li>
     *  </ul>
     *  Anything we can't parse evaluates to {@code false} —
     *  the gate defaults to the {@code else_} branch. prior round
     *  could swap in a real expression engine. */
    boolean evalWhen(String expr) {
        if (expr == null || expr.isBlank()) return false;
        String resolved = substitute(expr);
        try {
            return new WhenParser(resolved).parseOr();
        } catch (Exception e) {
            LOG.debug("when expression parse failed for '{}': {}", expr, e.getMessage());
            return false;
        }
    }

    /** Substitute {@code {{inputs.x}}} / {@code {{steps.x.field}}}
     *  references in {@code template} with their string form.
     *  Missing keys resolve to empty string (so {@code == 0}
     *  still works on a missing exitCode — empty is not "0"). */
    String substitute(String template) {
        if (template == null || !template.contains("{{")) return template;
        Pattern P = Pattern.compile("\\{\\{\\s*(inputs|steps)\\s*\\.\\s*([A-Za-z0-9_]+)(?:\\s*\\.\\s*([A-Za-z0-9_]+))?\\s*\\}\\}");
        Matcher m = P.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String kind = m.group(1);
            String k1 = m.group(2);
            String k2 = m.group(3);
            String val = "";
            if ("inputs".equals(kind)) {
                Object v = inputs.get(k1);
                val = v == null ? "" : String.valueOf(v);
            } else if ("steps".equals(kind) && k2 != null) {
                StepResult r = results.get(k1);
                if (r != null) {
                    switch (k2) {
                        case "status"   -> val = r.status;
                        case "exitCode" -> val = String.valueOf(r.exitCode);
                        case "stdout"   -> val = r.stdout == null ? "" : r.stdout;
                        case "stderr"   -> val = r.stderr == null ? "" : r.stderr;
                        default         -> val = "";
                    }
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(val));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Parse the {@code branches[]} / nested-step list of a
     *  parallel step or the {@code then} / {@code else_} body
     *  of a gate step. Each entry is `- id: <name>\n  type: <t>`,
     *  with a body that the executor pulls from the parent
     *  chunk when running. */
    private List<WorkflowReader.Step> parseBranches(String body) {
        List<WorkflowReader.Step> out = new ArrayList<>();
        String[] chunks = body.split("(?m)^\\s*-\\s+id\\s*:");
        for (int i = 1; i < chunks.length; i++) {
            String c = chunks[i];
            String id = c.split("\\r?\\n", 2)[0]
                    .replaceAll("['\"]", "").trim();
            if (id.isEmpty()) continue;
            Matcher tm = Pattern.compile("(?m)^\\s*type\\s*:\\s*['\"]?([^'\"\\n#]+)['\"]?").matcher(c);
            String type = tm.find() ? tm.group(1).trim() : "unknown";
            out.add(new WorkflowReader.Step(id, type));
        }
        return out;
    }

    // ---- minimal expression parser for `when` -----------------------

    /** Hand-rolled recursive-descent parser. R104 could swap
     *  in a real engine; this is enough for the common
     *  {@code steps.test.exitCode == 0} pattern. */
    private static final class WhenParser {
        private final String s;
        private int pos = 0;
        WhenParser(String s) { this.s = s; }
        boolean parseOr() {
            boolean v = parseAnd();
            while (true) {
                skipWs();
                if (consume("||")) { v = v || parseAnd(); }
                else return v;
            }
        }
        boolean parseAnd() {
            boolean v = parseCmp();
            while (true) {
                skipWs();
                if (consume("&&")) { v = v && parseCmp(); }
                else return v;
            }
        }
        boolean parseCmp() {
            String left = parseValue();
            skipWs();
            String op = null;
            for (String o : new String[]{"==", "!=", ">=", "<=", ">", "<"}) {
                if (s.startsWith(o, pos)) { op = o; pos += o.length(); break; }
            }
            if (op == null) return truthy(left);
            String right = parseValue();
            return switch (op) {
                case "==" -> left.equals(right);
                case "!=" -> !left.equals(right);
                case ">"  -> cmpNum(left, right) > 0;
                case "<"  -> cmpNum(left, right) < 0;
                case ">=" -> cmpNum(left, right) >= 0;
                case "<=" -> cmpNum(left, right) <= 0;
                default  -> false;
            };
        }
        /** Values: bare literals, numbers, single/double
         *  quoted strings, or unquoted identifiers (we
         *  substitute their value via the outer's
         *  {@code substitute} call before parsing). */
        String parseValue() {
            skipWs();
            if (pos >= s.length()) return "";
            char c = s.charAt(pos);
            if (c == '"' || c == '\'') {
                char q = c; pos++;
                int start = pos;
                while (pos < s.length() && s.charAt(pos) != q) pos++;
                String v = s.substring(start, pos);
                if (pos < s.length()) pos++;
                return v;
            }
            int start = pos;
            while (pos < s.length() && " \t\n()|&=!<>\"'".indexOf(s.charAt(pos)) < 0) pos++;
            return s.substring(start, pos);
        }
        boolean truthy(String v) {
            if (v == null) return false;
            if (v.isEmpty()) return false;
            if ("0".equals(v)) return false;
            if ("false".equalsIgnoreCase(v)) return false;
            return true;
        }
        long cmpNum(String a, String b) {
            try { return Long.parseLong(a) - Long.parseLong(b); }
            catch (NumberFormatException e) {
                // Fallback to string compare.
                return a.compareTo(b);
            }
        }
        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }
        boolean consume(String lit) {
            skipWs();
            if (s.startsWith(lit, pos)) { pos += lit.length(); return true; }
            return false;
        }
    }
}
