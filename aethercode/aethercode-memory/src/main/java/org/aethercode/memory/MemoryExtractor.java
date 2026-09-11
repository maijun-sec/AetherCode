package org.aethercode.memory;

import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.aethercode.core.permission.PermissionResult;
import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.Tool.ToolResult;
import org.aethercode.tools.file.FileEditTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Auto-memory extraction. Modelled after the TS
 * {@code services/extractMemories/extractMemories.ts} + the session-memory background
 * subagent. After enough messages accumulate, this class:
 *
 * <ol>
 *   <li>Creates a fresh {@code MEMORY.md} file in the session-memory directory
 *       (0o700 dir, 0o600 file, O_EXCL — matches the TS sessionMemory.ts setup)</li>
 *   <li>Forks a subagent with a tightly scoped tool pool (only {@code file_edit} on the
 *       exact memory path; everything else denied)</li>
 *   <li>Asks the subagent to summarise the long-term-relevant parts of the conversation
 *       and write them to the memory file</li>
 * </ol>
 *
 * <p>Trigger thresholds (matching the TS sessionMemoryUtils):
 * <ul>
 *   <li>{@code minimumMessageTokensToInit = 10 000}</li>
 *   <li>{@code minimumTokensBetweenUpdate = 5 000}</li>
 *   <li>{@code toolCallsBetweenUpdates = 3}</li>
 * </ul>
 *
 * <p>The decision to extract also requires either a natural break (no tool_use in the
 * last assistant turn) or both the token and tool-call thresholds.
 */
public class MemoryExtractor {

    private static final Logger LOG = LoggerFactory.getLogger(MemoryExtractor.class);

    public static final int MIN_MESSAGE_TOKENS_TO_INIT = 10_000;
    public static final int MIN_TOKENS_BETWEEN_UPDATE = 5_000;
    public static final int TOOL_CALLS_BETWEEN_UPDATES = 3;

    private final ChatClient chatClient;
    private final Path memoryDir;
    private final Path memoryFile;
    private long lastExtractedTokens = 0;
    private boolean initialized = false;
    private int toolCallsSinceUpdate = 0;

    public MemoryExtractor(ChatClient chatClient, Path sessionMemoryDir) {
        this.chatClient = chatClient;
        this.memoryDir = sessionMemoryDir;
        this.memoryFile = sessionMemoryDir.resolve("MEMORY.md");
    }

    public Path memoryFile() { return memoryFile; }
    public boolean isInitialized() { return initialized; }

    /**
     * Decide whether the current transcript warrants an extraction.
     */
    public boolean shouldExtract(List<Message> messages) {
        long tokens = estimateTokens(messages);
        if (!initialized) {
            if (tokens < MIN_MESSAGE_TOKENS_TO_INIT) return false;
            initialized = true;
        }
        boolean hasMetTokenThreshold = (tokens - lastExtractedTokens) >= MIN_TOKENS_BETWEEN_UPDATE;
        boolean hasMetToolCallThreshold = toolCallsSinceUpdate >= TOOL_CALLS_BETWEEN_UPDATES;
        boolean noToolUseInLastTurn = !hasToolCallsInLastAssistantTurn(messages);
        return (hasMetTokenThreshold && hasMetToolCallThreshold)
                || (hasMetTokenThreshold && noToolUseInLastTurn);
    }

    public void noteToolCall() { toolCallsSinceUpdate++; }

    /**
     * Run the extraction. Returns true if the memory file was successfully written.
     */
    public boolean extract(List<Message> messages) {
        try {
            ensureDirAndFile();
        } catch (IOException e) {
            LOG.warn("failed to set up session memory file: {}", e.getMessage());
            return false;
        }
        try {
            String summary = runForkedSubagent(messages);
            if (summary != null && !summary.isBlank()) {
                Files.writeString(memoryFile, summary);
                lastExtractedTokens = estimateTokens(messages);
                toolCallsSinceUpdate = 0;
                return true;
            }
        } catch (Exception e) {
            LOG.warn("memory extraction failed: {}", e.getMessage());
        }
        return false;
    }

    private void ensureDirAndFile() throws IOException {
        Files.createDirectories(memoryDir);
        try {
            // 0o700 on the dir, 0o600 on the file (sensitive session state).
            Files.setPosixFilePermissions(memoryDir, PosixFilePermissions.fromString("rwx------"));
        } catch (Exception ignored) {
            // Windows: skip POSIX permission tweak.
        }
        if (!Files.exists(memoryFile)) {
            Files.createFile(memoryFile);
            try {
                Files.setPosixFilePermissions(memoryFile, PosixFilePermissions.fromString("rw-------"));
            } catch (Exception ignored) {}
        }
    }

    private String runForkedSubagent(List<Message> messages) {
        // Build a tightly scoped tool: file_edit on the memory file only.
        final Path memPath = memoryFile.toAbsolutePath();
        Tool scopedEdit = new Tool() {
            @Override public String name() { return FileEditTool.NAME; }
            @Override public String description() { return FileEditTool.NAME + " (memory-scoped)"; }
            @Override public Map<String, Object> inputSchema() { return FileEditTool.build().inputSchema(); }
            @Override
            public CompletableFuture<PermissionResult> checkPermissions(Map<String, Object> input, CallContext ctx) {
                String path = (String) input.get("file_path");
                if (path == null || !Path.of(path).toAbsolutePath().equals(memPath)) {
                    return CompletableFuture.completedFuture(
                            PermissionResult.Deny.of("memory extraction may only touch " + memPath));
                }
                return CompletableFuture.completedFuture(new PermissionResult.Allow(input));
            }
            @Override
            public CompletableFuture<ToolResult> call(Map<String, Object> input, CallContext ctx) {
                String path = (String) input.get("file_path");
                if (path == null || !Path.of(path).toAbsolutePath().equals(memPath)) {
                    return CompletableFuture.completedFuture(
                            ToolResult.error("only " + memPath + " is allowed"));
                }
                return CompletableFuture.completedFuture(FileEditTool.call(input, ctx));
            }
        };

        String systemPrompt = """
                You are a memory-extraction subagent. Your only job is to write a concise
                Markdown summary of the conversation to the memory file. The file_edit tool
                is sandboxed to the exact memory path — do not attempt to read or edit
                anything else.

                Capture only long-term-relevant facts:
                - user preferences and constraints
                - project-specific knowledge not derivable from the code
                - decisions that affect future work
                - external references the user pointed at

                Skip:
                - one-off debugging chatter
                - tool outputs the user can re-derive
                - anything that needs a fresh read of the codebase
                """.strip();

        StringBuilder prompt = new StringBuilder("Conversation to summarise:\n\n");
        for (Message m : messages) {
            prompt.append("- [").append(m.role()).append("] ").append(m.textContent().strip()).append('\n');
        }
        prompt.append("\nUse the file_edit tool with old_string=\"\" and new_string containing the summary, to create the file.");

        List<Message> req = new ArrayList<>();
        req.add(Message.userText(prompt.toString()));

        // Run the subagent.
        StringBuilder out = new StringBuilder();
        Stream<org.aethercode.core.stream.StreamEvent> stream = chatClient.stream(
                req, systemPrompt, List.of(scopedEdit));
        for (java.util.Iterator<org.aethercode.core.stream.StreamEvent> it = stream.iterator(); it.hasNext(); ) {
            org.aethercode.core.stream.StreamEvent ev = it.next();
            if (ev instanceof org.aethercode.core.stream.StreamEvent.TextDelta td) {
                out.append(td.text());
            } else if (ev instanceof org.aethercode.core.stream.StreamEvent.RunEnd) {
                break;
            }
        }
        return out.toString();
    }

    static long estimateTokens(List<Message> messages) {
        long total = 0;
        for (Message m : messages) {
            for (ContentBlock b : m.content()) {
                if (b instanceof ContentBlock.TextBlock t) total += t.text().length();
                else if (b instanceof ContentBlock.ToolResultBlock r && r.content() instanceof String s) total += s.length();
            }
        }
        return total / 4;
    }

    private static boolean hasToolCallsInLastAssistantTurn(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m.role() == Role.ASSISTANT) {
                for (ContentBlock b : m.content()) if (b instanceof ContentBlock.ToolUseBlock) return true;
                return false;
            }
            if (m.role() == Role.USER) return false;
        }
        return false;
    }
}
