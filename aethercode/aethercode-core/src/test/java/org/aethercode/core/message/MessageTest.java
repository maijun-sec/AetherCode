package org.aethercode.core.message;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageTest {

    @Test
    void userTextFactoryProducesUserMessage() {
        Message m = Message.userText("hi");
        assertThat(m.role()).isEqualTo(Role.USER);
        assertThat(m.textContent()).isEqualTo("hi");
        assertThat(m.id()).isNotBlank();
    }

    @Test
    void toolResultFactoryCarriesToolUseId() {
        Message m = Message.toolResult("abc", "ok", false);
        assertThat(m.role()).isEqualTo(Role.TOOL_RESULT);
        assertThat(m.content().get(0)).isInstanceOf(ContentBlock.ToolResultBlock.class);
        ContentBlock.ToolResultBlock rb = (ContentBlock.ToolResultBlock) m.content().get(0);
        assertThat(rb.content()).isEqualTo("ok");
        assertThat(rb.toolUseId()).isEqualTo("abc");
        assertThat(m.metadata()).containsEntry("tool_use_id", "abc");
    }

    @Test
    void toolUseBlockRejectsBlankId() {
        assertThatThrownBy(() -> new ContentBlock.ToolUseBlock("", "bash", java.util.Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // The renderer's transcript_event subscriber
    // (aethercode-desktop/src/store/index.ts) consumes
    // the Map shape produced by toMap() and converts it
    // to its own ChatMessage. The test pins the
    // contract so a future refactor doesn't quietly
    // change a field name and break the wire.

    @Test
    void toMap_emitsLowercaseRoleAndEpochMillis() {
        Message m = Message.userText("hi");
        java.util.Map<String, Object> w = m.toMap();
        assertThat(w).containsKey("id");
        assertThat(w.get("id")).isEqualTo(m.id());
        // Role MUST be lowercase — the renderer's
        // ChatMessage.role union is {user, assistant,
        // system, tool_result}; a stray "USER" would
        // silently drop the message.
        assertThat(w.get("role")).isEqualTo("user");
        // Timestamp MUST be a long epoch ms (not
        // ISO-8601) — the renderer compares with
        // Date.now().
        assertThat(w.get("timestamp")).isInstanceOf(Long.class);
        long ts = (long) w.get("timestamp");
        assertThat(ts).isEqualTo(m.timestamp().toEpochMilli());
        assertThat(w.get("timestamp")).isNotEqualTo(m.timestamp().toString());
    }

    @Test
    void toMap_roundtripsThroughJackson() throws Exception {
        // The broadcast notifier in HttpJsonRpcServer
        // serialises the params map through the
        // JsonRpcCodec, which uses Jackson. The
        // renderer deserialises the same JSON into a
        // generic Object (it doesn't know about
        // ContentBlock records). This test pins the
        // JSON shape the wire actually carries so a
        // future refactor doesn't quietly change a
        // field name and break the renderer.
        //
        // The {@code content} value is a
        // {@code List<ContentBlock>}; Jackson needs
        // the {@code @JsonTypeInfo} annotation
        // (declared on the sealed interface) to add
        // a {@code type} discriminator per block.
        // That works when the ObjectMapper knows
        // about the sealed-interface hierarchy —
        // {@code findAndRegisterModules()} picks up
        // the metadata. Without it, the discriminator
        // is missing and the renderer's
        // messageToChatMessage would silently drop
        // the block. The daemon's real ObjectMapper
        // is built the same way (see
        // {@code Transcript.MAPPER}).
        Message m = Message.userText("hello world");
        java.util.Map<String, Object> w = m.toMap();
        com.fasterxml.jackson.databind.ObjectMapper om =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                        .findAndRegisterModules();
        String json = om.writeValueAsString(w);
        // The wire shape the renderer sees. Use
        // readTree so we don't need default-typing
        // to round-trip the sealed interface.
        com.fasterxml.jackson.databind.JsonNode node = om.readTree(json);
        assertThat(node.get("id").asText()).isEqualTo(m.id());
        assertThat(node.get("role").asText()).isEqualTo("user");
        assertThat(node.get("timestamp").asLong()).isEqualTo(m.timestamp().toEpochMilli());
        com.fasterxml.jackson.databind.JsonNode content = node.get("content");
        assertThat(content.isArray()).isTrue();
        assertThat(content).hasSize(1);
        com.fasterxml.jackson.databind.JsonNode block = content.get(0);
        // The @JsonTypeInfo discriminator is what
        // the renderer's messageToChatMessage reads
        // off each block. A missing or wrong-case
        // `type` would silently drop the block.
        //
        // NOTE: Jackson's sealed-interface subtype
        // handling is a JDK 17+ feature; on older
        // runtimes the discriminator may be absent.
        // The renderer's messageToChatMessage
        // tolerates a missing `type` by treating
        // the block as text (the legacy default),
        // so the worst case is a missing tool_use
        // / tool_result distinction — not a
        // dropped block. The test asserts the
        // modern shape so a future Jackson upgrade
        // (or a new Jackson default) doesn't
        // silently regress the discriminator.
        assertThat(block.has("type")).isTrue();
        assertThat(block.get("type").asText()).isEqualTo("text");
        assertThat(block.get("text").asText()).isEqualTo("hello world");
    }

    @Test
    void toMap_assistantWithToolUseKeepsBlocksIntact() {
        // An assistant message can carry tool_use
        // blocks. toMap() converts each block to a
        // Map<String, Object> via Jackson so the
        // @JsonTypeInfo discriminator is preserved on
        // the wire. The renderer's messageToChatMessage
        // drops tool_use blocks when rendering, but
        // they need to survive the wire round-trip so
        // a future R-round can surface structured
        // tool calls in the historical view. This
        // test pins that contract.
        ContentBlock.ToolUseBlock tu = new ContentBlock.ToolUseBlock(
                "tu-1", "bash", java.util.Map.of("command", "ls"));
        Message m = Message.assistantToolUse(java.util.List.of(tu));
        java.util.Map<String, Object> w = m.toMap();
        assertThat(w.get("role")).isEqualTo("assistant");
        assertThat(w.get("content")).isInstanceOf(java.util.List.class);
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> blocks =
                (java.util.List<java.util.Map<String, Object>>) w.get("content");
        assertThat(blocks).hasSize(1);
        // The `type` discriminator is what tells the
        // renderer this is a tool_use block (not text
        // or tool_result).
        assertThat(blocks.get(0).get("type")).isEqualTo("tool_use");
        assertThat(blocks.get(0).get("id")).isEqualTo("tu-1");
        assertThat(blocks.get(0).get("name")).isEqualTo("bash");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> input =
                (java.util.Map<String, Object>) blocks.get(0).get("input");
        assertThat(input).containsEntry("command", "ls");
    }
}
