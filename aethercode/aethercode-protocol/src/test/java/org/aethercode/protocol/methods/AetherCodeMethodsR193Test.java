package org.aethercode.protocol.methods;

import org.aethercode.core.stream.StreamEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * contract test for the {@code tool_output_delta} stream
 * event. The BashTool streams each stdout/stderr line via
 * {@code ctx.emit(...)} and QueryEngine now forwards those as
 * {@code StreamEvent.ToolOutputDelta} so the desktop can
 * stream-append them to the active tool event's {@code output}
 * field. This test guards the wire shape:
 *
 * <pre>
 *   {type: "tool_output_delta", id: "tool-1", text: "[out] line"}
 * </pre>
 *
 * <p>legacy, the bash tool's streamed output was silently
 * dropped (the SDK's AetherCodeEngine passed {@code null} for
 * the messageSink). The user described this as "mvn 命令执行
 * 的时间有点儿长" — the actual runtime was fine, but with no
 * live feedback the wait felt slow. R193's wire-format change
 * is the smallest possible fix: keep the BashTool emit path,
 * add a new StreamEvent variant, plumb it through AetherCode
 * Methods' eventToMap.
 *
 * <p>The {@code id} field is the tool call id, matching
 * {@code ToolUseStart.id} / {@code ToolResult.id} of the same
 * call. The desktop uses it to find the matching tool event
 * in the current step and stream-append {@code text}.
 */
class AetherCodeMethodsR193Test {

    @Test
    void toolOutputDelta_serializesToExpectedWireShape() {
        StreamEvent ev = new StreamEvent.ToolOutputDelta("tool-abc-123", "[out] Apache Maven 3.9.6");
        Map<String, Object> wire = AetherCodeMethods.eventToMapPublic(ev);
        assertThat(wire).containsEntry("type", "tool_output_delta");
        assertThat(wire).containsEntry("id", "tool-abc-123");
        assertThat(wire).containsEntry("text", "[out] Apache Maven 3.9.6");
    }

    @Test
    void toolOutputDelta_emptyTextStillSerializes() {
        // BashTool only emits non-empty lines (its drain loop
        // reads `r.readLine()` which returns null on EOF but
        // skips empty strings). Still — the wire shape must
        // hold for an empty text. We don't filter here; the
        // engine side already filters in QueryEngine.
        StreamEvent ev = new StreamEvent.ToolOutputDelta("tool-x", "");
        Map<String, Object> wire = AetherCodeMethods.eventToMapPublic(ev);
        assertThat(wire).containsEntry("type", "tool_output_delta");
        assertThat(wire).containsEntry("id", "tool-x");
        assertThat(wire).containsKey("text");
    }

    @Test
    void toolOutputDelta_preservesMultiLineText() {
        // The BashTool emit loop processes one line at a time,
        // but future tools may emit a multi-line block. Make
        // sure the wire format doesn't mangle the text.
        String multi = "[out] line 1\n[err] line 2\n[out] line 3";
        StreamEvent ev = new StreamEvent.ToolOutputDelta("tool-multi", multi);
        Map<String, Object> wire = AetherCodeMethods.eventToMapPublic(ev);
        assertThat((String) wire.get("text")).isEqualTo(multi);
    }

    @Test
    void toolOutputDelta_distinctIdsArePreserved() {
        // Two parallel tools streaming at once — the desktop
        // must be able to route each delta to the right tool
        // event. The wire shape must carry the per-call id
        // faithfully.
        Map<String, Object> a = AetherCodeMethods.eventToMapPublic(
                new StreamEvent.ToolOutputDelta("tool-A", "AAA"));
        Map<String, Object> b = AetherCodeMethods.eventToMapPublic(
                new StreamEvent.ToolOutputDelta("tool-B", "BBB"));
        assertThat(a).containsEntry("id", "tool-A");
        assertThat(b).containsEntry("id", "tool-B");
        assertThat((String) a.get("text")).isEqualTo("AAA");
        assertThat((String) b.get("text")).isEqualTo("BBB");
    }
}
