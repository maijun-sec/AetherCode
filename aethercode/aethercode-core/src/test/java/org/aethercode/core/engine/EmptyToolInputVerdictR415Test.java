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
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.aethercode.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.aethercode.core.app.AppState;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * R415 — THE VERDICT MUST NAME THE CAUSE.
 *
 * <h2>★ WHAT THE LIVE MATRIX MEASURED ★</h2>
 * <p>R415's second live round ran the scenario matrix with a fresh jar and
 * found six red checks whose cause was NOT the model. A census over every
 * session file counted <b>370 native tool calls</b>:</p>
 * <ul>
 *   <li>349 carried a full argument object (ids {@code call_*}) — none
 *       empty;</li>
 *   <li>21 carried an <b>EMPTY argument object</b> (ids
 *       {@code chatcmpl-tool-*}) — <b>21 of 21</b>, i.e. every call of
 *       that shape arrived parameterless.</li>
 * </ul>
 * <p>The {@code chatcmpl-tool-*} id shape is minted by the UPSTREAM
 * provider: it appears nowhere in this repository and in none of our
 * dependencies (searched the shaded jar's every entry). The provider's
 * streaming protocol splits a tool call's arguments into fragments
 * (first chunk: id + name + {@code arguments: ""}), and on that one path
 * the fragments never arrive.</p>
 *
 * <h2>★ WHY THIS TEST EXISTS ★</h2>
 * <p>Two messages in the engine described that failure WRONG:</p>
 * <ol>
 *   <li>the tool-layer rejection said "was called with missing required
 *       parameters … fill the placeholders" — which reads to the model
 *       as "you forgot one field", so the model re-emits the same
 *       broken call (the mirror loop);</li>
 *   <li>the engine's hard-stop verdict said "this is a model-side bug …
 *       please start a new session or rephrase your prompt" — it
 *       blamed the user's prompt for a transport fault, and told the
 *       user to throw away a session whose transcript is fine.</li>
 * </ol>
 * <p>R415's own rule ("the verdict comes from the daemon, and it names
 * the real cause") applies to the engine's own diagnostics, not only to
 * the wire. These pins hold the two messages to that rule.</p>
 *
 * <h2>★ THE MUTATION WITNESSES ★</h2>
 * <ul>
 *   <li>M98 — the engine's verdict restored to "model-side bug …
 *       rephrase your prompt" ⇒ the verdict pin;</li>
 *   <li>M99 — the tool layer drops the empty/partial distinction (one
 *       wording for both) ⇒ the distinction pin.</li>
 * </ul>
 */
class EmptyToolInputVerdictR415Test {

    private static final String QE =
            "src/main/java/org/aethercode/core/engine/QueryEngine.java";

    private static String readQueryEngineSource() {
        String[] candidates = new String[] {
                QE,
                "aethercode-core/" + QE,
                "../aethercode-core/" + QE,
        };
        for (String c : candidates) {
            Path p = Path.of(c);
            if (Files.exists(p)) {
                try {
                    return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
        throw new IllegalStateException(
                "QueryEngine.java not found under any of "
                        + java.util.Arrays.toString(candidates));
    }

    // ------------------------------------------------------------------
    // 1. the tool layer: an EMPTY argument object is its own failure
    // ------------------------------------------------------------------

    /** A tool with one required parameter, plus a counter so we can
     *  prove the call never reached it. */
    private static Tool bashWithRequiredCommand(AtomicInteger ran) {
        LinkedHashMap<String, Map<String, Object>> props = new LinkedHashMap<>();
        props.put("command", Tools.stringProp("the shell command to run"));
        Map<String, Object> schema = Tools.objectSchema(props, "command");
        return Tools.build(new ToolDef("bash", "run a shell command", schema,
                (in, ctx) -> {
                    ran.incrementAndGet();
                    return CompletableFuture.completedFuture(Tool.ToolResult.of("ok"));
                }));
    }

    private static String runWithInput(Map<String, Object> input, AtomicInteger ran) {
        AppState st = new AppState("s1", Path.of(""));
        st.toolPool().add(bashWithRequiredCommand(ran));
        StreamingToolExecutor exec = new StreamingToolExecutor(PermissionPolicy.allowAll(), 4);
        List<StreamingToolExecutor.Event> events = exec.run(
                List.of(new ContentBlock.ToolUseBlock("call_1", "bash", input)),
                st).collect(Collectors.toList());
        return events.stream()
                .filter(e -> e instanceof StreamingToolExecutor.Event.Completed
                        && ((StreamingToolExecutor.Event.Completed) e).isError())
                .map(e -> String.valueOf(((StreamingToolExecutor.Event.Completed) e).output()))
                .findFirst().orElse("");
    }

    @Test
    @DisplayName("R415: a call that arrived with NO arguments gets its own diagnosis")
    void emptyArgumentObjectGetsItsOwnDiagnosis() {
        AtomicInteger ran = new AtomicInteger();
        String msg = runWithInput(Map.of(), ran);
        assertThat(ran.get())
                .as("the tool must not run: there are no arguments to run it with")
                .isZero();
        assertThat(msg)
                .as("the rejection must SAY the argument object was empty: %s", msg)
                .contains("EMPTY argument object");
        assertThat(msg)
                .as("the rejection must NOT claim the model merely omitted a field: %s", msg)
                .doesNotContain("was called with missing required parameters");
        assertThat(msg)
                .as("the rejection must name the likely cause — the arguments were "
                        + "dropped in transport, not forgotten: %s", msg)
                .contains("transport");
        assertThat(msg)
                .as("the rejection must still show the shape the model owes us: %s", msg)
                .contains("expected tool_use shape")
                .contains("\"command\":\"<value>\"");
        assertThat(msg)
                .as("the advice must tell the model that repeating the identical call "
                        + "cannot help: %s", msg)
                .contains("STOP retrying");
    }

    @Test
    @DisplayName("R415: a call with SOME arguments keeps the missing-field diagnosis")
    void partiallyFilledInputKeepsTheMissingFieldDiagnosis() {
        AtomicInteger ran = new AtomicInteger();
        // "cwd" is not a property of this schema, "command" is required
        // and absent => the call is "partially filled", not "empty".
        String msg = runWithInput(Map.of("cwd", "/tmp"), ran);
        assertThat(ran.get()).isZero();
        assertThat(msg)
                .as("a partially filled call is still a missing-field slip: %s", msg)
                .contains("was called with missing required parameters");
        assertThat(msg)
                .as("the empty-case wording must not swallow the partial case: %s", msg)
                .doesNotContain("EMPTY argument object");
    }

    // ------------------------------------------------------------------
    // 2. the engine's verdict: name the cause, don't blame the prompt
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R415: the empty_tool_input verdict names the cause instead of blaming the prompt")
    void theVerdictNamesTheCauseInsteadOfBlamingThePrompt() {
        String src = readQueryEngineSource();
        int idx = src.indexOf("\"empty_tool_input\",");
        assertThat(idx).as("the empty_tool_input SideNote literal not found").isGreaterThan(0);
        // a generous window: the SideNote is a concatenation of string
        // literals, so the text is split across lines.
        String window = src.substring(idx, Math.min(idx + 2500, src.length()));
        assertThat(window)
                .as("the verdict must not call a dropped-arguments fault a MODEL bug: %s", window)
                .doesNotContain("model-side bug");
        assertThat(window)
                .as("the verdict must not tell the user to rephrase their prompt — the "
                        + "prompt was fine; the arguments never arrived: %s", window)
                .doesNotContain("rephrase your prompt");
        assertThat(window)
                .as("the verdict must name the upstream provider as the likely cause: %s", window)
                .contains("provider");
        assertThat(window)
                .as("the verdict must offer the action that CAN work (retry the turn): %s", window)
                .contains("retry");
    }

    @Test
    @DisplayName("R415: the empty_tool_input verdict keeps its kind (the classifier depends on it)")
    void theVerdictKeepsItsKind() {
        String src = readQueryEngineSource();
        int idx = src.indexOf("\"loop_detected: empty_tool_input (\"");
        assertThat(idx)
                .as("the stopReason literal must still start with 'loop_detected: "
                        + "empty_tool_input (' — the wire classifier keys on that kind")
                .isGreaterThan(0);
        // The parenthetical is free-form, but it must still say what
        // the calls were: an EMPTY ARGUMENT OBJECT, not "empty tool
        // calls" (which reads as "the model sent nothing").
        String paren = src.substring(idx, Math.min(idx + 400, src.length()));
        assertThat(paren)
                .as("the detail suffix must describe the empty ARGUMENT OBJECT: %s", paren)
                .contains("empty argument object");
    }
}
