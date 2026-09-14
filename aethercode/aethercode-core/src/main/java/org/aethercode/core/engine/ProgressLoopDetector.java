package org.aethercode.core.engine;

import org.aethercode.core.message.ContentBlock;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * dynamic, semantic loop detector.
 *
 * <p>R83: the "high-risk tool repeated" check has been removed. The
 * original idea was to apply a stricter threshold to destructive tools
 * ({@code bash}, {@code file_write}, etc.) so a runaway {@code rm} loop
 * is caught faster than a runaway {@code read} loop. In practice this
 * was a numeric cap on sensitive commands that the user couldn't
 * override, and it produced false positives on legitimate exploration
 * (e.g. {@code ls} + {@code cat} on a 4-line script). It's been
 * replaced by per-todo adaptive control in {@code TodoRunController},
 * which respects user confirmations and the LLM's own plan.
 *
 * <p>The detector now recognises four patterns:
 *
 * <ol>
 *   <li><b>Same fingerprint</b> — the same tool call (name + canonical
 *       input) appears {@code fingerprintThreshold} times in the last
 *       {@code window} batches. Catches "model keeps calling the same
 *       tool with the same args over and over".</li>
 *   <li><b>Same error</b> — the same tool returned an error with the
 *       same message text {@code sameErrorThreshold} times in a row.
 *       Catches "the model keeps trying the broken thing".</li>
 *   <li><b>Long output without tool calls</b> — the model has produced
 *       more than {@code longOutputThreshold} characters of text in a
 *       single turn without emitting any tool call. Suggests the
 *       model is "thinking out loud" and not actually doing anything.
 *       Emits a SideNote, not a hard stop, so the model can wrap up.</li>
 *   <li><b>User interrupt</b> — {@link #notifyUserInterrupt()} was called.
 *       Immediate stop on the next batch check.</li>
 * </ol>
 *
 * <p>R101: tiered warning instead of immediate hard stop. The first
 * N times a pattern fires, the detector emits a "loop_warn" loop
 * info (kind starts with "loop_warn_") and the engine continues.
 * Only on the {@link #WARN_BEFORE_STOP + 1}-th consecutive hit
 * does the kind switch to "loop_detected" and the engine hard
 * stops. The user can {@link #acknowledge()} at any time to
 * reset the tier, signalling "yes, this is intentional — keep
 * going". This converts a hard stop into a soft warning the user
 * can act on, which was the dominant pain point with the prior round
 * detector: legitimate iteration (e.g. batch-renaming 50 files)
 * was indistinguishable from a true stuck loop, and there was
 * no escape hatch.
 *
 * <p>These are heuristics for "model is obviously stuck", not
 * caps on how many times a tool can be called. Per-todo step
 * counting and the LLM-driven decision to continue or adjust is
 * the {@code TodoRunController}'s job.
 */
public final class ProgressLoopDetector {

    /** How many consecutive hits before the detector hard-stops.
     *  Hits 1..N are emitted as warnings (kind "loop_warn_1" ..
     *  "loop_warn_N"); hit N+1 is the existing "loop_detected"
     *  hard-stop. Default 2 → warn twice, then stop. Set to 0
     *  to skip the warning phase and stop on the first hit
     *  (legacy behaviour). Set to a large number (e.g. 99)
     *  to effectively disable the hard stop.
     *
     *  <p>R134: this is now an instance field (configurable per
     *  detector via {@link Builder#warnBeforeStop(int)} and
     *  {@link #setWarnBeforeStop(int)}). The static constant
     *  remains for backward compat with existing code that reads
     *  {@code ProgressLoopDetector.WARN_BEFORE_STOP}; the default
     *  constructor still uses 2. */
    public static final int WARN_BEFORE_STOP = 2;

    /** What the detector concluded. {@code null} = no loop.
     *
     *  <p>R101: the {@code kind} field has three classes:
     *  <ul>
     *    <li>starts with {@code "loop_warn_"} — soft warning,
     *        engine should continue</li>
     *    <li>{@code "loop_detected"} — hard stop, engine should
     *        end the run</li>
     *    <li>anything else — informational, engine continues</li>
     *  </ul>
     *  The {@code tier} field is 1..{@code WARN_BEFORE_STOP} for
     *  warnings and {@code WARN_BEFORE_STOP + 1} for a hard stop.
     *  The caller can use {@code LoopInfo#shouldStop()} for a
     *  one-line decision. */
    public record LoopInfo(String kind, String description, String summary, int tier) {
        public boolean isLoop() { return kind != null; }
        /** caller treats {@code true} as "end the run now".
         *  Both "loop_detected" (the detector's tier-3 verdict)
         *  and "user_interrupt" (the user pressed Ctrl-C) are
         *  immediate hard stops — no warning, no tiered escape. */
        public boolean shouldStop() {
            return "loop_detected".equals(kind) || "user_interrupt".equals(kind);
        }
        public boolean isWarning() { return kind != null && kind.startsWith("loop_warn_"); }
    }

    /** Builder. Defaults match what {@code ToolLoopDetector} used to do,
     *  with one removal in R83:
     *  <ul>
     *    <li>{@code highRiskThreshold} and {@code highRiskTools} are gone.
     *        See the class-level Javadoc for why.</li>
     *  </ul>
     */
    public static final class Builder {
        private int window = 8;
        private int fingerprintThreshold = 3;
        private int sameErrorThreshold = 3;
        private int longOutputThreshold = 1500;
        /** how many consecutive no-tool-call long turns
         *  before the long_output pattern fires. Default 1
         *  (the legacy behaviour: a single long turn
         *  fires). Set to 2+ to require multiple consecutive
         *  long turns. */
        private int longOutputConsecutive = 1;
        /** how many warn-tier hits before hard stop. */
        private int warnBeforeStop = WARN_BEFORE_STOP;
        /** how many consecutive turns where the model
         *  emitted a tool call with an empty / missing
         *  input map before the empty_tool_input pattern
         *  fires. Default 2 (R266h, was 3) — the
         *  v0.2.66 desktop transcript showed the model
         *  sending empty `{}` 20+ times in a row even
         *  after the engine returned a precise "command
         *  is required" error and listed every accepted
         *  parameter. Each retry costs the user a turn
         *  (LLM roundtrip + TUI render + the model
         *  "thinking" it would fix it this time). 2
         *  consecutive empty batches is the right
         *  "definitely stuck" signal — by the third
         *  retry the user has already wasted two
         *  tool cards. The hard stop fires
         *  immediately (not tiered) because a confused
         *  model can't self-correct and a soft warning
         *  would just delay the inevitable. */
        private int emptyInputStreakThreshold = 2;

        public Builder window(int v) {
            if (v < 1) throw new IllegalArgumentException("window must be >= 1");
            this.window = v;
            return this;
        }
        public Builder fingerprintThreshold(int v) {
            if (v < 1) throw new IllegalArgumentException("fingerprintThreshold must be >= 1");
            this.fingerprintThreshold = v;
            return this;
        }
        public Builder sameErrorThreshold(int v) {
            if (v < 1) throw new IllegalArgumentException("sameErrorThreshold must be >= 1");
            this.sameErrorThreshold = v;
            return this;
        }
        public Builder longOutputThreshold(int v) {
            if (v < 1) throw new IllegalArgumentException("longOutputThreshold must be >= 1");
            this.longOutputThreshold = v;
            return this;
        }
        /** how many consecutive no-tool-call long turns
         *  before long_output fires. Set to 2+ to require
         *  multiple consecutive long thinking turns. */
        public Builder longOutputConsecutive(int v) {
            if (v < 1) throw new IllegalArgumentException("longOutputConsecutive must be >= 1");
            this.longOutputConsecutive = v;
            return this;
        }
        /** how many warn-tier hits before hard stop. Default 2.
         *  Set to a large value (e.g. 99) to effectively disable
         *  the hard stop and let the engine finish naturally. */
        public Builder warnBeforeStop(int v) {
            if (v < 0) throw new IllegalArgumentException("warnBeforeStop must be >= 0");
            this.warnBeforeStop = v;
            return this;
        }
        /** configure the empty-input threshold. See
         *  {@link #emptyInputStreakThreshold} on the instance. */
        public Builder emptyInputStreakThreshold(int v) {
            if (v < 1) throw new IllegalArgumentException("emptyInputStreakThreshold must be >= 1");
            this.emptyInputStreakThreshold = v;
            return this;
        }
        public ProgressLoopDetector build() {
            int fp = Math.min(fingerprintThreshold, window);
            return new ProgressLoopDetector(this, window, fp, sameErrorThreshold,
                    longOutputThreshold, longOutputConsecutive, warnBeforeStop,
                    emptyInputStreakThreshold);
        }
    }

    public static Builder builder() { return new Builder(); }

    /** heuristic factory for "this is a complex multi-step task"
     *  that scales all thresholds UP so the model can do real work
     *  without tripping the loop detector on legitimate "thinking
     *  out loud" text or "verify what I just wrote" tool calls.
     *
     *  <p>Scales (relative to the defaults in {@link Builder}):
     *  <ul>
     *    <li>{@code window} 8 → 20 (more room in the rolling history)
     *    <li>{@code fingerprintThreshold} 3 → 5 (allow 5 of the
     *        same tool call in 20 turns before warning — real
     *        iteration like "compile, fix, compile, fix" stays safe)
     *    <li>{@code longOutputThreshold} 1500 → 10000 chars
     *        (R134 raise from 5000: the RAG end-to-end test
     *        showed the model writes 3-4 files then enters a
     *        5-8K-char thinking phase about the next batch —
     *        5000 was still too tight. 10000 lets a "thinking
     *        out loud" turn of 8-10K chars pass without firing)
     *    <li>{@code longOutputConsecutive} 1 → 2 (R134: only
     *        fire long_output after 2 consecutive no-tool-call
     *        long turns. A single 10K thinking turn is normal
     *        for a complex project; 2+ consecutive ones is
     *        actually stuck.)
     *    <li>{@code warnBeforeStop} 2 → 4 (give the user 4 chances
     *        to ack before the engine hard-stops; with auto-finish
     *        via {@link #notifyProgress}, most complex tasks never
     *        hit the hard stop at all because each successful
     *        file_write resets the tier)
     *  </ul>
     *
     *  <p>Call this from the engine when {@code userInput.length() > 1500}
     *  OR the user prompt mentions writing N (N >= 3) files, OR a
     *  tool result shows {@code file_write} succeeded.
     *
     *  <p>This is intentionally aggressive — false positives (a real
     *  loop is missed) are bad, but false negatives (the engine
     *  cuts a legitimate task short mid-file) are much worse. The
     *  R133 RAG end-to-end run cut off every module at
     *  {@code loop_detected} with the old defaults. The RAG use
     *  case is exactly what this factory is for.
     */
    public static ProgressLoopDetector forComplexTask() {
        return builder()
                .window(20)
                .fingerprintThreshold(5)
                .longOutputThreshold(10000)
                .longOutputConsecutive(2)
                .warnBeforeStop(4)
                .build();
    }

    /** R136.4: "max context" factory. Scales the
     *  detector thresholds UP for million-token models
     *  (MiniMax M3, Gemini 2.5 Pro) where a 1M-context
     *  "thinking" turn can legitimately produce 100K+
     *  chars of text. Same idea as {@link #forComplexTask()}
     *  but with the longOutput ceiling raised further
     *  and the warn-before-stop ladder extended so a
     *  model that genuinely needs 50+ tool calls to
     *  complete a RAG module (write 5 files, verify
     *  each, think about the next) doesn't get
     *  cut off mid-task.
     *
     *  <p>Numbers:
     *  <ul>
     *    <li>{@code window} 8 → 50 (rolls a 50-turn
     *        history so a long multi-file task fits in
     *        the window without "forgetting" earlier
     *        work)</li>
     *    <li>{@code fingerprintThreshold} 3 → 10
     *        (allow 10 of the same tool call in 50
     *        turns before warning; real "compile, fix,
     *        compile, fix" iteration stays safe and
     *        bash-driven inspection doesn't get
     *        short-circuited)</li>
     *    <li>{@code longOutputThreshold} 1500 → 100_000
     *        chars (R136.4: a 1M-context model can
     *        legitimately produce 80-100K of "thinking"
     *        text per turn when planning the next 10
     *        file_writes)</li>
     *    <li>{@code longOutputConsecutive} 1 → 3
     *        (only fire after 3 consecutive no-tool-call
     *        long turns. A 100K thinking turn is
     *        normal; 3 in a row is the model actually
     *        stuck.)</li>
     *    <li>{@code warnBeforeStop} 2 → 8 (give the
     *        user 8 chances to ack before the engine
     *        hard-stops; with auto-finish via
     *        {@link #notifyProgress}, most real tasks
     *        never hit the hard stop at all)</li>
     *  </ul>
     *
     *  <p>Triggered by env var
     *  {@code AETHERCODE_LOOP_DETECTOR=max} OR by
     *  {@code pickLoopDetector} when {@code contextWindow
     *  >= 500_000}.
     */
    public static ProgressLoopDetector forMaxContext() {
        return builder()
                .window(50)
                .fingerprintThreshold(10)
                .longOutputThreshold(100_000)
                .longOutputConsecutive(3)
                .warnBeforeStop(8)
                .build();
    }

    // ---- instance state --------------------------------------------------

    private final int window;
    private final int fingerprintThreshold;
    private final int sameErrorThreshold;
    private final int longOutputThreshold;
    /** how many consecutive no-tool-call long turns
     *  before the long_output pattern fires. Default 1
     *  (legacy behaviour). The complex-task factory sets
     *  this to 2. */
    private final int longOutputConsecutive;
    /** how many warn-tier hits before hard stop. Per-instance
     *  so {@link Builder#warnBeforeStop(int)} / {@link #forComplexTask()}
     *  can opt into a more permissive detector. Volatile (not
     *  final) so the desktop Settings panel can tune it at
     *  runtime via {@link #setWarnBeforeStop(int)}. */
    private volatile int warnBeforeStop;
    /** threshold for the empty_tool_input pattern. Per-instance
     *  so {@link Builder#emptyInputStreakThreshold(int)} /
     *  {@link #setEmptyInputStreakThreshold(int)} can override the
     *  default of 2. Volatile (not final) so the desktop Settings
     *  panel can tune it at runtime. */
    private volatile int emptyInputStreakThreshold = 2;

    /** Per-batch fingerprints in arrival order. Capped at {@code window}. */
    private final Deque<String> history = new ArrayDeque<>();
    /** Counts of each fingerprint currently in the window. */
    private final Map<String, Integer> counts = new HashMap<>();
    /** Consecutive same-error fingerprint for the same error message text. */
    private String lastErrorKey = null;
    private int lastErrorCount = 0;
    /** consecutive turns where the model emitted a tool
     *  call with an empty / missing input map. Reset to 0 on
     *  any non-empty batch (a tool with at least one
     *  populated parameter). Fires the empty_tool_input
     *  pattern when the streak reaches
     *  {@link #emptyInputStreakThreshold}. */
    private int emptyInputStreak = 0;
    /** consecutive no-tool-call turns that exceeded
     *  longOutputThreshold. Reset to 0 on any tool call or on
     *  a turn with text under the threshold. Fires the
     *  long_output pattern when this counter reaches
     *  {@code longOutputConsecutive}. */
    private int longOutputStreak = 0;
    /** Whether the user has pressed Ctrl-C. */
    private boolean userInterrupted = false;
    /** current consecutive-loop-streak tier. 0 = no streak.
     *  Each non-null LoopInfo from {@link #recordBatch} bumps
     *  this by 1; {@link #acknowledge()} resets it to 0. The
     *  tier maps onto {@link LoopInfo#tier} for the caller's
     *  decision (warn vs stop). */
    private int currentTier = 0;
    /** The last loop kind we saw, so {@link #acknowledge()} can
     *  reset both the tier and the rolling fingerprint history
     *  (so the same pattern doesn't immediately re-fire). */
    private String lastLoopKind = null;

    private ProgressLoopDetector(Builder ignored, int window, int fingerprintThreshold,
                                  int sameErrorThreshold, int longOutputThreshold,
                                  int longOutputConsecutive,
                                  int warnBeforeStop,
                                  int emptyInputStreakThreshold) {
        this.window = window;
        this.fingerprintThreshold = fingerprintThreshold;
        this.sameErrorThreshold = sameErrorThreshold;
        this.longOutputThreshold = longOutputThreshold;
        this.longOutputConsecutive = longOutputConsecutive;
        this.warnBeforeStop = warnBeforeStop;
        this.emptyInputStreakThreshold = emptyInputStreakThreshold;
    }

    /** R136.3: number of file_write tool calls seen
     *  in this run so far. A non-zero value is the
     *  "the model is making real progress" signal. */
    private int fileWriteCount = 0;
    /** R136.3: number of "shell" tool calls (bash that
     *  didn't end up writing a file) seen so far.
     *  Used to spot the "stuck in environment check"
     *  pattern: lots of bash version-checks + 0
     *  file_writes is the canonical "model isn't
     *  actually doing anything" signal. */
    private int shellOnlyCount = 0;
    /** R136.3: number of {@code recordBatch} calls
     *  so far in this run. Used to enforce a
     *  turn budget for the no-file-write check. */
    private int turnCount = 0;
    /** R136.3: how many consecutive turns with
     *  0 file_writes we've tolerated. Reset to 0
     *  on any file_write. The pattern fires after
     *  {@code noFileWriteTurns >= maxNoFileWriteTurns}. */
    private int noFileWriteStreak = 0;
    /** bumped default to 10 (was 6 in R136.3).
     *  4 priming hits + 4 warning-tier hits + 1
     *  hard-stop hit in a forComplexTask() test is
     *  9 turns — the previous default of 6 fired
     *  the no_file_write_progress hard stop
     *  before the forComplexTask warn ladder
     *  could reach its final tier. The new
     *  default 10 means the model has 10 full
     *  turns of bash-only / read-only exploration
     *  before this detector trips, while
     *  bash-only + 80% shell ratio tightens the
     *  trigger (see recordBatch). Set lower
     *  (3-5) for short tasks where exploration
     *  is bounded; higher (15+) for long RAG
     *  multi-file generation runs. */
    private volatile int maxNoFileWriteTurns = 10;
    /** also require that 80% of the recent
     *  turns were "shell-only" (bash, exec, etc.)
     *  with no file_write — guards against
     *  false positives on a normal chat that
     *  happens to be light on file writes (e.g.
     *  a quick Q&A run). */
    private static final double NO_FW_SHELL_RATIO = 0.5;
    /** "research mode" early detector. Counts
     *  consecutive turns where the only tool call was
     *  a shell command that produced a small output
     *  (under {@link #SMALL_OUTPUT_THRESHOLD} chars).
     *  Canonical cases: {@code mvn -version},
     *  {@code java -version}, {@code echo foo},
     *  {@code ls}, {@code pwd}. These are pure
     *  environment research that does NOT advance the
     *  task; the model should switch to file_write
     *  after 2-3 of them. Fires earlier than
     *  {@link #maxNoFileWriteTurns} (default 10)
     *  because the "research-only" pattern is
     *  obvious from turn 3 onward. */
    private volatile int smallOutputStreak = 0;
    /** count of "big-output" shell calls so far
     *  in this run. A "big" shell output is >=
     *  SMALL_OUTPUT_THRESHOLD chars — meaning the
     *  shell actually DID something (mvn test,
     *  dir /s, curl, etc.) instead of just printing
     *  a version string. Used by the
     *  noFileWriteProgress detector to skip the
     *  hard-stop when the model is making real
     *  progress via shell (just not file_write yet).
     *  R138.1's research-mode detector already
     *  distinguishes small vs big output, but it
     *  resets the streak on big output — meaning
     *  a model that alternates between research
     *  bash and real-work bash never trips
     *  research_mode but might trip
     *  no_file_write_progress after 10 turns.
     *  R146 makes the noFileWriteProgress
     *  detector also smart: if the recent history
     *  has any big-output bash, give the model
     *  more slack (it's doing real work, just not
     *  file_write yet). */
    private int bigOutputCount = 0;
    /** how many consecutive small-output shell
     *  turns we tolerate before the research-mode
     *  detector fires. Default 3 (one for "verify
     *  the env", one for "double-check", one for
     *  "definitely no" — by turn 4 the model is
     *  wasting time). */
    private volatile int maxSmallOutputStreak = 3;
    /** bash output under this char count counts
     *  as "small" (likely a version check, ls, echo).
     *  {@code mvn -version} is ~3 lines (~150 chars);
     *  {@code ls} is typically <500 chars;
     *  {@code mvn test} is >5K chars (real work). */
    private static final int SMALL_OUTPUT_THRESHOLD = 300;

    public int window() { return window; }
    public int fingerprintThreshold() { return fingerprintThreshold; }
    public int sameErrorThreshold() { return sameErrorThreshold; }
    public int longOutputThreshold() { return longOutputThreshold; }
    /** consecutive long-output turns before the pattern fires. */
    public int longOutputConsecutive() { return longOutputConsecutive; }
    /** current long-output streak. */
    public int longOutputStreak() { return longOutputStreak; }
    /** per-instance warn-before-stop threshold. */
    public int warnBeforeStop() { return warnBeforeStop; }
    public int currentWindowSize() { return history.size(); }
    public boolean isUserInterrupted() { return userInterrupted; }
    /** current loop-streak tier. 0 = no streak in progress. */
    public int currentTier() { return currentTier; }
    /** the specific kind of the most recent
     *  hard-stop. Distinct from {@link LoopInfo#kind()}
     *  which is always {@code "loop_detected"} for
     *  hard stops. Returns one of
     *  {@code "research_mode"} /
     *  {@code "no_file_write_progress"} /
     *  {@code "same_fingerprint"} / {@code "user_interrupt"}
     *  / null if no loop fired yet. */
    public String lastLoopKind() { return lastLoopKind; }

    /** R136.3: setter for {@link #maxNoFileWriteTurns}.
     *  Tests + the engine (via the maxContext mode
     *  auto-pick) can lower this for short tasks. */
    public void setMaxNoFileWriteTurns(int n) {
        if (n < 1) throw new IllegalArgumentException("maxNoFileWriteTurns must be >= 1");
        this.maxNoFileWriteTurns = n;
    }
    /** setter for {@link #maxSmallOutputStreak}.
     *  Tests can lower this from 3 to 2 for tighter
     *  scenarios. The production default of 3 is the
     *  result of a smoke-test series: turn 1
     *  ("mvn -version" — legitimate env check), turn 2
     *  ("java -version" — sometimes legitimate), turn 3
     *  ("echo test" — clearly stuck). */
    public void setMaxSmallOutputStreak(int n) {
        if (n < 1) throw new IllegalArgumentException("maxSmallOutputStreak must be >= 1");
        this.maxSmallOutputStreak = n;
    }
    /** setter for {@link #emptyInputStreakThreshold}.
     *  Tests can lower this from 3 to 1 for tighter
     *  scenarios. The production default of 3 gives the
     *  model a small grace window in case a legitimate
     *  first-attempt tool call has empty args (e.g. a
     *  `glob` with no `pattern` to "list everything"). */
    public void setEmptyInputStreakThreshold(int n) {
        if (n < 1) throw new IllegalArgumentException("emptyInputStreakThreshold must be >= 1");
        this.emptyInputStreakThreshold = n;
        // any threshold change invalidates the in-flight
        // streak so a model that already had 2 empty
        // batches doesn't immediately fire when the
        // desktop lowers the threshold.
        this.currentTier = 0;
        this.lastLoopKind = null;
    }

    /** R136.3: total file_write tool calls in this run. */
    public int fileWriteCount() { return fileWriteCount; }
    /** R136.3: total "shell only" tool calls (bash that
     *  didn't write a file) so far. */
    public int shellOnlyCount() { return shellOnlyCount; }
    /** R136.3: total turns so far. */
    public int turnCount() { return turnCount; }
    /** R136.3: consecutive turns with 0 file_writes. */
    public int noFileWriteStreak() { return noFileWriteStreak; }
    /** R136.3: budget for no-file-write turns. */
    public int maxNoFileWriteTurns() { return maxNoFileWriteTurns; }
    /** consecutive small-output shell turns. */
    public int smallOutputStreak() { return smallOutputStreak; }
    /** budget for small-output shell turns. */
    public int maxSmallOutputStreak() { return maxSmallOutputStreak; }
    /** count of big-output shell calls in
     *  this run. Used by the noFileWriteProgress
     *  detector to relax its threshold when the
     *  model is making real progress via shell. */
    public int bigOutputCount() { return bigOutputCount; }
    /** current empty-input streak. */
    public int emptyInputStreak() { return emptyInputStreak; }
    /** configured threshold for the empty-input pattern. */
    public int emptyInputStreakThreshold() { return emptyInputStreakThreshold; }

    // ---- inputs ---------------------------------------------------------

    /** Notify the detector that the user pressed Ctrl-C / interrupted.
     *  The next call to {@link #recordBatch} returns a "user_interrupt"
     *  loop info immediately. */
    public void notifyUserInterrupt() {
        this.userInterrupted = true;
    }

    /** user-driven escape hatch. Resets the tier to 0 and
     *  clears the rolling fingerprint history so the same
     *  pattern that just fired can fire again after the user
     *  says "yes, this is intentional — keep going". The
     *  engine calls this in response to the
     *  {@code loopAck} RPC from the desktop. The user also
     *  implicitly acks by clicking "Continue" in the LoopGuardBanner.
     *
     *  <p>Subtle: we keep the per-batch history intact (so the
     *  detector still has a meaningful window) but reset the
     *  tier counter. The next non-null {@link LoopInfo} starts
     *  at tier 1 again. The {@code lastLoopKind} is also reset
     *  so a different pattern can fire its own tier-1 warning. */
    public void acknowledge() {
        this.currentTier = 0;
        this.lastLoopKind = null;
        this.lastErrorKey = null;
        this.lastErrorCount = 0;
        // an explicit user ack also clears the
        // empty-input streak — the user told us "this is
        // intentional, keep going", so even if the model
        // continues to emit empty inputs, we won't fire
        // again on the same in-flight sequence.
        this.emptyInputStreak = 0;
        // Don't clear history — that would erase the window
        // and the same pattern would re-fire on the next
        // batch regardless. Instead, the caller (the
        // engine) should continue and the user's next batch
        // will naturally extend the history.
    }

    /** programmatic escape hatch. Sets the tier to
     *  {@code tier}, capping at {@code warnBeforeStop + 1}.
     *  Used by tests; production code should call
     *  {@link #acknowledge()} to reset fully or do nothing
     *  to let the tier keep climbing. */
    public void setTierForTest(int tier) {
        this.currentTier = Math.max(0, Math.min(tier, warnBeforeStop + 1));
    }

    /**
     * change the warn-before-stop threshold at runtime. Used by
     * the desktop Settings panel when the user wants to make the
     * detector more or less aggressive. Set to a large value
     * (e.g. 99) to effectively disable the hard stop.
     */
    public void setWarnBeforeStop(int v) {
        if (v < 0) throw new IllegalArgumentException("warnBeforeStop must be >= 0");
        this.warnBeforeStop = v;
        this.currentTier = 0;  // any tier in flight is now invalidated
        this.lastLoopKind = null;
    }

    /**
     * "auto-finish loop detector" — the engine calls this
     * whenever it sees real progress (e.g. a {@code file_write}
     * tool result that wrote new bytes to disk, or a non-trivial
     * shell command that returned successfully). The detector
     * resets its tier to 0 so a long thinking phase AFTER a
     * successful write doesn't trip the loop detector on the
     * NEXT batch.
     *
     * <p>The fingerprint history is NOT cleared — the next batch
     * still has to compete with the last 5+ batches in the
     * window. We just don't want the tier to climb across a
     * clear progress boundary. This is the RAG "I just wrote
     * 5 files, now I'm thinking about what to do next" signal —
     * the thinking is real work, not a loop.
     *
     * <p>Counter for diagnostics: a run with 10+ successful
     * {@code file_write} tool calls and zero
     * {@code loop_detected} stops is the canonical "RAG
     * generation works" signal.
     */
    public void notifyProgress() {
        this.progressEvents++;
        this.currentTier = 0;
        this.lastLoopKind = null;
        this.lastErrorKey = null;
        this.lastErrorCount = 0;
        this.longOutputStreak = 0;  // R134: progress also resets
        // progress naturally means a non-empty
        // tool batch succeeded, which is incompatible
        // with the empty-input pattern. Clear the streak
        // explicitly so a long thinking phase AFTER a
        // successful tool call doesn't leave the
        // streak intact.
        this.emptyInputStreak = 0;
    }

    /** how many times {@link #notifyProgress} has been called
     *  on this detector. Diagnostic metric for tests + the desktop
     *  StatusBar — high value means the engine is seeing real
     *  progress in the loop. */
    private long progressEvents = 0;
    public long progressEvents() { return progressEvents; }

    /**
     * Build the canonical fingerprint for a tool call. {@code name} plus
     * the input map with keys sorted and values stringified. Same as
     * {@link ToolLoopDetector#fingerprint} for compatibility.
     */
    public static String fingerprint(ContentBlock.ToolUseBlock b) {
        if (b == null) return "<null>";
        StringBuilder sb = new StringBuilder(b.name());
        sb.append('|');
        Map<String, Object> input = b.input();
        if (input != null && !input.isEmpty()) {
            input.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> sb.append(e.getKey()).append('=')
                            .append(String.valueOf(e.getValue())).append(';'));
        }
        return sb.toString();
    }

    /**
     * Record a tool batch + its results from this turn. Returns a
     * {@link LoopInfo} if the detector concludes we're looping, else
     * {@code null}.
     *
     * @param batch      the tool calls the model emitted this turn
     * @param outputs    the per-tool results (must align 1:1 with batch)
     * @param assistantTextChars how many characters of text the model produced
     *                            alongside the tool calls (use 0 if the model
     *                            only produced tool calls)
     */
    public LoopInfo recordBatch(List<ContentBlock.ToolUseBlock> batch,
                                List<BatchResult> outputs,
                                int assistantTextChars) {
        // R136.3: bump the per-run counters BEFORE the
        // loop-pattern checks so the no-file-write
        // detector can see "this turn had 0 fw".
        turnCount++;
        // hoist the batch-flags to method scope so
        // the research-mode detector below can use them
        // (it runs OUTSIDE the `if (batch != null)`
        // block because the R138 logic also wants to
        // know about the outputs, not just the batch).
        boolean anyFileWrite = false;
        boolean anyShell = false;
        if (batch != null) {
            for (ContentBlock.ToolUseBlock b : batch) {
                if (b == null) continue;
                String n = b.name();
                if (n == null) continue;
                if (n.equals("file_write") || n.equals("file_create")
                        || n.equals("write_file") || n.equals("file_edit")) {
                    anyFileWrite = true;
                } else if (n.equals("bash") || n.equals("shell")
                        || n.equals("exec") || n.equals("run_command")) {
                    anyShell = true;
                }
            }
            if (anyFileWrite) {
                fileWriteCount++;
                noFileWriteStreak = 0;
            } else {
                noFileWriteStreak++;
                if (anyShell) shellOnlyCount++;
            }
        } else {
            noFileWriteStreak++;
        }
        // "research mode" early detector. Track
        // consecutive turns whose shell outputs are all
        // small (e.g. mvn -version, java -version,
        // echo, ls, pwd). After 3 such turns with no
        // file_write, the model is stuck in environment
        // research and the user wants a hard stop
        // before the model wastes 4-5 more turns.
        // We update smallOutputStreak even when the
        // batch also has non-shell tools (e.g.
        // file_read + bash) — what matters is that
        // the shell output was small, not that bash
        // was the only tool. Reset on file_write or
        // on a bash output that exceeds the threshold
        // (real work, not research).
        if (anyShell && outputs != null) {
            // Track three buckets:
            //   smallBashOut = at least one bash with output <= threshold
            //   bigBashOut   = at least one bash with output >  threshold
            //   emptyBashOut = bash ran but produced no output
            // If we see ANY big output, the model is doing real work —
            // reset the research-mode streak. If we see small output
            // and no big output, increment the streak. If the bash
            // produced no output at all (anomaly), don't change the
            // streak — we don't have enough info to call it research.
            boolean smallBashOut = false;
            boolean bigBashOut = false;
            boolean anyBashResult = false;
            for (BatchResult r : outputs) {
                if (r == null) continue;
                String nm = r.name();
                if (nm == null) continue;
                if (nm.equals("bash") || nm.equals("shell")
                        || nm.equals("exec") || nm.equals("run_command")) {
                    anyBashResult = true;
                    String o = r.output();
                    if (o == null || o.isEmpty()) {
                        // empty output — leave streak alone (anomaly)
                    } else if (o.length() <= SMALL_OUTPUT_THRESHOLD) {
                        smallBashOut = true;
                    } else {
                        bigBashOut = true;
                    }
                }
            }
            if (anyFileWrite) {
                // File write overrides any research-mode signal.
                smallOutputStreak = 0;
            } else if (bigBashOut) {
                // Real work (mvn test, build, large dir) — reset.
                smallOutputStreak = 0;
            } else if (smallBashOut) {
                smallOutputStreak++;
            }
            // else: anyBashResult is false (data anomaly) OR all bash
            // produced empty output — leave streak alone.
            // track big-output bash count for the
            // noFileWriteProgress smart-skip. Bumped
            // whenever we see a big bash output.
            if (bigBashOut) {
                bigOutputCount++;
            }
        } else if (anyFileWrite) {
            smallOutputStreak = 0;
        }
        if (!anyFileWrite
                && smallOutputStreak >= maxSmallOutputStreak
                && noFileWriteStreak >= maxSmallOutputStreak
                && turnCount > 2) {
            currentTier++;
            this.lastLoopKind = "research_mode";
            return new LoopInfo("loop_detected",
                    "model stuck in research mode: " + smallOutputStreak + " consecutive turns of small-output shell "
                            + "(no file_write, no real work — likely mvn -version / java -version / echo / ls / pwd)",
                    "research mode: " + smallOutputStreak + " small shell turns in a row",
                    currentTier);
        }
        // R136.3: "no file_write progress" detector.
        // tightened — also require a high
        // shell-only ratio so a normal Q&A run that
        // doesn't write files doesn't trip. After
        // maxNoFileWriteTurns (default 10) consecutive
        // turns with 0 file_writes AND turn > 3
        // (give the model 3 priming turns)
        // AND shellOnlyCount / turnCount >= 50%, hard
        // stop immediately. The "stuck in research
        // mode" trap is the canonical case where
        // tier-warnings only delay the inevitable;
        // the user wants to see "you've been reading
        // for 10 turns, stop and write something"
        // right away. Other patterns (long_output,
        // same_fingerprint) still go through tiered().
        //
        // SMART-SKIP when the model is making
        // real progress via shell. If
        // bigOutputCount >= 2 (the model has done at
        // least 2 big-output shell commands in this
        // run, e.g. mvn test, dir /s, curl), it's
        // doing real work, just not file_write YET.
        // Allow up to 2x the normal budget
        // (maxNoFileWriteTurns * 2) before firing.
        // This catches the user-reported case where
        // a model alternates between small bash
        // (mvn -version) and big bash (dir /s /b)
        // and trips no_file_write_progress even
        // though it's clearly making progress.
        int effectiveMaxFwTurns = maxNoFileWriteTurns;
        if (bigOutputCount >= 2) {
            effectiveMaxFwTurns = maxNoFileWriteTurns * 2;
        }
        if (noFileWriteStreak >= effectiveMaxFwTurns
                && turnCount > 3
                && turnCount > 0
                && ((double) shellOnlyCount) / turnCount >= NO_FW_SHELL_RATIO) {
            currentTier++;
            this.lastLoopKind = "no_file_write_progress";
            return new LoopInfo("loop_detected",
                    "model produced " + noFileWriteStreak + " consecutive turns with no file_write "
                            + "(file_writes=" + fileWriteCount + ", shell=" + shellOnlyCount
                            + ", big_output_bash=" + bigOutputCount
                            + ", turns=" + turnCount + ")",
                    "no file_write in " + noFileWriteStreak + " turns",
                    currentTier);
        }
        // empty_tool_input detector. Track consecutive
        // turns where the model emitted a tool call with an
        // empty / missing input map. A confused model can
        // fall into a 50-turn "fix the tool call format"
        // loop (the v0.2.19 real-prompt regression showed
        // exactly this). After {@code emptyInputStreakThreshold}
        // (default 3) consecutive empty batches, hard-stop
        // immediately. The streak is reset to 0 on any
        // batch that has at least one tool call with a
        // populated input.
        //
        // We gate on {@code turnCount > 2} so the model
        // has the same 2-turn grace period that
        // {@code research_mode} (prior round) and
        // {@code no_file_write_progress} (R136.3) get.
        // Without this, a model that emits
        // {@code glob {}} on turn 1 to "list the
        // directory" (a legitimate empty-args call) would
        // trip the detector before it has a chance to
        // recover.
        //
        // Note we run this check BEFORE the userInterrupt
        // check so an empty-input model is caught even when
        // the user is also pressing Ctrl-C (the
        // userInterrupt branch is a more graceful exit).
        //
        // R266i (2026-09-14): the {@code Map.isEmpty()}
        // check below was the R266h blind spot. A model
        // that sends {@code {"command": ""}} or
        // {@code {"command": null}} produces a non-empty
        // Map (one entry, blank/null value) and slipped
        // past the detector. The desktop user reported
        // 16+ consecutive {@code bash (missing command)}
        // cards with no LoopGuardBanner — the detector
        // was correctly counting the input as "non-empty"
        // and resetting the streak to 0 forever.
        //
        // The fix is to extend the emptiness check to
        // "structurally empty" — i.e. every value is
        // null, blank string, empty Map, or empty
        // Collection. This matches the user's intuition
        // ("the model didn't know what to fill in")
        // without false-positiving on legitimate
        // empty-arg tools like {@code ls -la} on a
        // directory (which is {@code {"command": "ls -la"}},
        // a non-empty value).
        if (batch != null && !batch.isEmpty() && turnCount > 2) {
            boolean allEmpty = true;
            for (ContentBlock.ToolUseBlock b : batch) {
                if (b == null) continue;
                if (!isStructurallyEmpty(b.input())) {
                    allEmpty = false;
                    break;
                }
            }
            if (allEmpty) {
                emptyInputStreak++;
                if (emptyInputStreak >= emptyInputStreakThreshold) {
                    currentTier++;
                    this.lastLoopKind = "empty_tool_input";
                    return new LoopInfo("loop_detected",
                            "model emitted " + emptyInputStreak + " consecutive tool calls with empty input "
                                    + "(e.g. bash {} / glob {} / file_write {}). This usually means the model is "
                                    + "stuck in a 'fix the tool call format' loop. The BashTool error message "
                                    + "now lists every accepted parameter; instruct the model to re-read the tool "
                                    + "schema and STOP retrying the same call.",
                            "empty tool input: " + emptyInputStreak + " consecutive batches",
                            currentTier);
                }
            } else {
                emptyInputStreak = 0;
            }
        }
        if (userInterrupted) {
            // user interrupt is a hard stop on the FIRST
            // detection, not a tiered warning. The user explicitly
            // asked to stop; warning them twice would be confusing.
            // We keep the original "user_interrupt" kind so the
            // engine can distinguish "user pressed Ctrl-C" from
            // "the loop detector fired N times" — shouldStop()
            // returns true for both (see the record definition).
            currentTier++;
            this.lastLoopKind = "user_interrupt";
            return new LoopInfo("user_interrupt",
                    "user interrupted the run",
                    "user pressed Ctrl-C / interrupted the run",
                    currentTier);
        }
        if (batch == null || batch.isEmpty()) {
            // Pure text turn — check for long output without tool calls.
            // only fire after `longOutputConsecutive`
            // consecutive long turns. A single 10K-char
            // thinking phase is normal for a complex project
            // (model plans the next 3 files in detail); 2+
            // consecutive ones means it's actually stuck.
            if (assistantTextChars >= longOutputThreshold) {
                longOutputStreak++;
                if (longOutputStreak >= longOutputConsecutive) {
                    return tiered("long_output",
                            "model produced " + assistantTextChars + " chars of text without any tool call "
                                    + "(streak " + longOutputStreak + "/" + longOutputConsecutive + ")",
                            "long output without tool calls (" + assistantTextChars + " chars, streak "
                                    + longOutputStreak + ")");
                }
                return null;  // streak not long enough yet — let it continue
            }
            // Under threshold — reset streak.
            longOutputStreak = 0;
            return null;
        }

        // 1. Per-batch fingerprint frequency (prior round logic).
        Map<String, Integer> batchCounts = new HashMap<>();
        for (ContentBlock.ToolUseBlock b : batch) {
            batchCounts.merge(fingerprint(b), 1, Integer::sum);
        }
        String hot = null;
        int best = 0;
        for (Map.Entry<String, Integer> e : batchCounts.entrySet()) {
            if (e.getValue() > best) { best = e.getValue(); hot = e.getKey(); }
        }

        // the high-risk tool check has been removed. The old logic
        // was a numeric cap on sensitive commands that the user couldn't
        // override, and it produced false positives on legitimate
        // exploration. Per-todo adaptive control in TodoRunController
        // is now the source of truth for "this is taking too long".

        // 2. Same fingerprint threshold.
        if (hot != null) {
            evictIfFull();
            history.addLast(hot);
            counts.merge(hot, 1, Integer::sum);
            if (counts.get(hot) >= fingerprintThreshold) {
                return tiered("same_fingerprint",
                        "tool call " + hot + " repeated " + counts.get(hot)
                                + " times in last " + window + " turns",
                        summariseFingerprint(hot));
            }
        }

        // 3. Same error threshold.
        if (outputs != null && !outputs.isEmpty()) {
            String errKey = aggregateErrorKey(outputs);
            if (errKey != null) {
                if (errKey.equals(lastErrorKey)) {
                    lastErrorCount++;
                } else {
                    lastErrorKey = errKey;
                    lastErrorCount = 1;
                }
                if (lastErrorCount >= sameErrorThreshold) {
                    return tiered("same_error",
                            "same error returned " + lastErrorCount + " times in a row: " + errKey,
                            "same error x" + lastErrorCount + ": " + errKey);
                }
            } else {
                lastErrorKey = null;
                lastErrorCount = 0;
            }
        }

        // 4. Long output alongside the batch (the model keeps talking without
        //    making tool calls effective — i.e., all the tool calls in the
        //    batch are the same and the text is huge). This was the original
        //    legacy "loop detected" trigger that hard-stopped the engine.
        //    R101 tiers it: warn first, stop only on the 3rd hit.
        // any tool call resets the long_output streak
        //    (the model is being productive, not stuck).
        longOutputStreak = 0;
        if (assistantTextChars >= longOutputThreshold
                && batch.stream().map(ProgressLoopDetector::fingerprint).distinct().count() == 1) {
            return tiered("long_output",
                    "model produced " + assistantTextChars
                            + " chars of text and only repeats one tool call",
                    "long output + repeated tool call (" + assistantTextChars + " chars)");
        }

        return null;
    }

    /** wrap a raw (kind, description, summary) tuple in the
     *  tier-aware form. Bumps {@code currentTier} by 1 and chooses
     *  the public {@code kind} string based on the tier:
     *  <ul>
     *    <li>tier <= {@code WARN_BEFORE_STOP}: emit
     *        {@code "loop_warn_<tier>"}; the engine continues.</li>
     *    <li>tier == {@code WARN_BEFORE_STOP + 1}: emit
     *        {@code "loop_detected"}; the engine hard-stops.</li>
     *  </ul>
     *  This is the single place where "warn vs stop" is decided,
     *  so the engine never has to inspect the tier directly. */
    private LoopInfo tiered(String rawKind, String description, String summary) {
        currentTier++;
        this.lastLoopKind = rawKind;
        // per-instance warnBeforeStop (was hardcoded WARN_BEFORE_STOP).
        if (currentTier > warnBeforeStop) {
            return new LoopInfo("loop_detected", description, summary, currentTier);
        }
        return new LoopInfo("loop_warn_" + currentTier, description, summary, currentTier);
    }

    /**
     * Convenience overload: same as {@link #recordBatch(List, List, int)}
     * with no results and no assistant text. Useful for tests + callers
     * that don't have either.
     */
    public LoopInfo recordBatch(List<ContentBlock.ToolUseBlock> batch) {
        return recordBatch(batch, List.of(), 0);
    }

    private void evictIfFull() {
        while (history.size() >= window) {
            String evicted = history.pollFirst();
            if (evicted == null) break;
            counts.merge(evicted, -1, Integer::sum);
            if (counts.getOrDefault(evicted, 0) <= 0) counts.remove(evicted);
        }
    }

    /** Reset the detector (call at the start of each user query).
     * also clears the R101 tier state and last-loop-kind
     *  marker. Without this, a previously-paused loop's tier would
     *  bleed into the new query (e.g. a tier-2 warn that was
     *  acknowledged on the previous query would still count
     *  toward a stop on the new one).
     * also resets the progressEvents counter. */
    public void reset() {
        history.clear();
        counts.clear();
        lastErrorKey = null;
        lastErrorCount = 0;
        longOutputStreak = 0;  // R134
        userInterrupted = false;
        currentTier = 0;
        lastLoopKind = null;
        progressEvents = 0;
        // R136.3: reset the per-run counters so a
        // second query in the same session gets a
        // clean slate.
        fileWriteCount = 0;
        shellOnlyCount = 0;
        smallOutputStreak = 0;  // R138 research-mode streak
        bigOutputCount = 0;     // R146 smart-skip counter
        turnCount = 0;
        noFileWriteStreak = 0;
    }

    /** Snapshot of the current history for diagnostics / tests. */
    public List<String> recentFingerprints() {
        return List.copyOf(history);
    }

    /** Canonical rendering of a fingerprint, used in summary messages. */
    public static String summariseFingerprint(String fp) {
        if (fp == null) return "<null>";
        int pipe = fp.indexOf('|');
        String name = pipe < 0 ? fp : fp.substring(0, pipe);
        String args = pipe < 0 ? "" : fp.substring(pipe + 1);
        if (args.length() > 60) args = args.substring(0, 59) + "…";
        return name + "(" + args + ")";
    }

    /** Normalize an error string for the "same error" key. */
    static String normalizeError(String s) {
        if (s == null) return null;
        // Strip whitespace + lowercase + truncate.
        String t = s.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        if (t.length() > 120) t = t.substring(0, 120);
        return t.isEmpty() ? null : t;
    }

    private static String aggregateErrorKey(List<BatchResult> outputs) {
        String joined = null;
        for (BatchResult r : outputs) {
            if (r == null || !r.isError || r.output == null) continue;
            String k = normalizeError(r.output);
            if (k == null) continue;
            joined = joined == null ? k : (joined + "|" + k);
        }
        return joined;
    }

    /**
     * R266i (2026-09-14): detect a "structurally empty" tool input.
     *
     * <p>The R266h {@code Map.isEmpty()} check fired only on
     * literally-empty maps ({@code {}}). A model that emits
     * {@code {"command": ""}} or {@code {"command": null}} —
     * a Map with one entry whose value is blank/null — slipped
     * past the detector, because {@code Map.isEmpty()} returned
     * {@code false}. On the desktop the user reported 16+ consecutive
     * {@code bash (missing command)} cards with no
     * {@code LoopGuardBanner} and no hard-stop: the detector was
     * treating every card as "non-empty input" and resetting the
     * empty-input streak to 0 forever.
     *
     * <p>"Structurally empty" means: every value in the map is
     * null, blank string, empty {@link Map}, or empty {@link
     * java.util.Collection}. This matches the user's intuition
     * ("the model didn't know what to fill in") without false-positiving
     * on legitimate empty-arg tools. A real command like
     * {@code {"command": "ls -la"}} has a non-blank value, so the map
     * is NOT structurally empty and the detector will reset the
     * streak as expected.
     *
     * <p>Note: this is a structural heuristic, not a schema-aware
     * check. A future round could plumb the tool's
     * {@code inputSchema.required} list through {@code recordBatch}
     * for a tighter "all required fields are missing" definition.
     * For now this catches the common model-side confusion pattern
     * (the v0.2.19 real-prompt regression and the user's
     * 2026-09-14 desktop report) with no false positives on
     * observed legitimate inputs.
     */
    static boolean isStructurallyEmpty(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            // truly empty / null map
            return true;
        }
        for (Object v : input.values()) {
            if (v == null) continue;
            if (v instanceof String s) {
                if (!s.isBlank()) return false; // non-blank string = real content
                continue;
            }
            if (v instanceof CharSequence cs) {
                if (cs.length() > 0) return false;
                continue;
            }
            if (v instanceof Map<?, ?> m) {
                if (!m.isEmpty()) return false; // nested non-empty map = real content
                continue;
            }
            if (v instanceof java.util.Collection<?> c) {
                if (!c.isEmpty()) return false; // non-empty list = real content
                continue;
            }
            if (v instanceof Object[] arr) {
                if (arr.length > 0) return false; // non-empty array = real content
                continue;
            }
            // any other non-null value (Integer, Boolean, etc.) is
            // considered real content — the model wrote SOMETHING
            // here, even if the value is semantically useless.
            return false;
        }
        return true;
    }

    /**
     * Per-tool result for the current batch. The query engine wraps each
     * tool's output into this shape so the detector can spot repeated
     * errors without depending on engine internals.
     */
    public record BatchResult(String id, String name, String output, boolean isError) {}
}
