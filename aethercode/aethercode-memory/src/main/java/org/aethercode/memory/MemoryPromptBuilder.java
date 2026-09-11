package org.aethercode.memory;

import org.aethercode.core.llm.ChatClient;

import java.nio.file.Path;
import java.util.List;

/**
 * Build the system-prompt section for an agent's memory. Modelled after the TS
 * {@code buildMemoryPrompt()} in {@code src/memdir/memdir.ts}.
 *
 * <p>Composes a fixed guideline block (what to save, what not to save, how to save) with the
 * live content of the {@code MEMORY.md} entrypoint, truncated to the cap.
 */
public final class MemoryPromptBuilder {

    private MemoryPromptBuilder() {}

    public static String build(String agentType, MemoryScope scope, Path memoryDir) {
        return build(agentType, scope, memoryDir, null, null, List.of());
    }

    /**
     * Build the system prompt with auto-recalled memory topics appended. The recaller may
     * be null (lexical-only mode).
     */
    public static String build(String agentType, MemoryScope scope, Path memoryDir,
                               ChatClient sideClient, String currentInput, List<String> recentTools) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Persistent Agent Memory (").append(scope.name().toLowerCase()).append(")\n\n");
        sb.append("You have a persistent, file-based memory system at `").append(memoryDir).append("`.\n");
        sb.append("The directory already exists — write to it directly, do not call `mkdir` first.\n\n");

        sb.append("## Types of memory\n");
        sb.append("- user: cross-project preferences and recurring constraints\n");
        sb.append("- feedback: corrections the user has made to your behaviour\n");
        sb.append("- project: knowledge specific to the current project\n");
        sb.append("- reference: pointers to external docs / URLs\n\n");

        sb.append("## What NOT to save\n");
        sb.append("- anything derivable from reading the code\n");
        sb.append("- ephemeral session state\n");
        sb.append("- duplicate entries — update existing memories instead\n\n");

        sb.append("## How to save\n");
        sb.append("1. Write each memory to its own file `").append(memoryDir).append("/<topic>.md`.\n");
        sb.append("2. Add a single-line index entry in `MEMORY.md` with a short description.\n");
        sb.append("3. To recall, use the `file_read` tool on the topic file.\n\n");

        sb.append("## Current scope\n");
        sb.append("agent: ").append(agentType).append("\n");
        sb.append("scope: ").append(scope.name().toLowerCase()).append("\n\n");

        // Append the live entrypoint.
        Path entry = MemoryPaths.entrypoint(memoryDir);
        String content = MemoryEntrypoint.readTruncated(entry);
        sb.append("## ").append(MemoryPaths.ENTRYPOINT_NAME).append("\n\n");
        if (content.isBlank()) {
            sb.append("Your MEMORY.md is currently empty. When you save new memories, they will appear here.\n");
        } else {
            sb.append(content);
        }

        // Auto-recalled topics (prior round).
        if (currentInput != null && !currentInput.isBlank()) {
            MemoryRecall recaller = sideClient == null ? new MemoryRecall() : new MemoryRecall(sideClient);
            List<MemoryRecall.RecalledFile> recalled = recaller.recall(memoryDir, currentInput, recentTools, List.of());
            String rendered = MemoryRecall.render(recalled);
            if (!rendered.isEmpty()) {
                sb.append("\n\n").append(rendered);
            }
        }
        return sb.toString();
    }
}
