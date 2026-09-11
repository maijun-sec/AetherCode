package org.aethercode.compact;

import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.aethercode.core.stream.StreamEvent;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class CompactGateTest {

    @Test
    void shortTranscriptNotCompacted() {
        CompactGate gate = new CompactGate(mockClient(""), 1000, 100, 500);
        List<Message> msgs = List.of(Message.userText("hi"), Message.assistantText("hello"));
        assertThat(gate.shouldCompact(msgs)).isFalse();
    }

    @Test
    void longTranscriptIsCompacted() {
        CompactGate gate = new CompactGate(mockClient("summary here"), 200, 50, 500);
        // 400 tokens of input
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) sb.append("word ");
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.userText(sb.toString()));
        msgs.add(Message.assistantText(sb.toString()));
        assertThat(gate.shouldCompact(msgs)).isTrue();
        var res = gate.compactRaw(msgs);
        assertThat(res.wasCompacted()).isTrue();
        assertThat(gate.consecutiveFailures()).isZero();
    }

    @Test
    void circuitBreakerOpensAfterThreeFailures() {
        ChatClient failing = new ChatClient() {
            public String modelId() { return "mock"; }
            public java.util.stream.Stream<StreamEvent> stream(java.util.List<Message> messages, String systemPrompt, java.util.List<org.aethercode.core.tool.Tool> tools) {
                throw new RuntimeException("simulated network failure");
            }
        };
        CompactGate gate = new CompactGate(failing, 10, 5, 50);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 1000; i++) big.append("word ");
        List<Message> msgs = List.of(Message.userText(big.toString()));
        for (int i = 0; i < 5; i++) gate.compact(msgs);
        assertThat(gate.isCircuitOpen()).isTrue();
    }

    @Test
    void spliceSummaryKeepsTail() {
        CompactGate gate = new CompactGate(mockClient(""), 100, 10, 50);
        var orig = new ArrayList<Message>();
        for (int i = 0; i < 20; i++) orig.add(Message.assistantText("line " + i));
        var spliced = gate.spliceSummary(orig, "TLDR");
        // Algorithm: 1 summary + 4 tail = 5; 15 middle messages dropped
        assertThat(spliced).hasSize(5);
        assertThat(spliced.get(0).textContent()).contains("TLDR");
        assertThat(spliced.get(spliced.size() - 1).textContent()).isEqualTo("line 19");
    }

    private static ChatClient mockClient(String text) {
        return new ChatClient() {
            public String modelId() { return "mock"; }
            public java.util.stream.Stream<StreamEvent> stream(java.util.List<Message> messages, String systemPrompt, java.util.List<org.aethercode.core.tool.Tool> tools) {
                if (text == null) {
                    return Stream.of(new StreamEvent.RunEnd("end_turn", List.of()));
                }
                return Stream.of(
                        new StreamEvent.TextDelta(text),
                        new StreamEvent.RunEnd("end_turn", List.of())
                );
            }
        };
    }
}
