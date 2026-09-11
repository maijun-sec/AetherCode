package org.aethercode.acp;

import org.aethercode.acp.schema.ContentBlock;
import org.aethercode.acp.schema.ContentBlocks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility functions for converting ACP content blocks to
 * LangChain multimodal content, plus the shell-injection
 * heuristics and command-type extractor used by the ACP
 * server's HITL permission flow.
 *
 * <p>Mirror of {@code deepagents_acp.utils}.</p>
 */
public final class Utils {
    private Utils() {}

    private static final int MAX_DISPLAY_COMMAND_LENGTH = 120;

    /**
     * Literal substrings that indicate shell injection risk.
     * Ported from {@code deepagents_cli.config.DANGEROUS_SHELL_PATTERNS}.
     * Used by {@link #containsDangerousPatterns(String)} to reject
     * commands that embed arbitrary execution via redirects,
     * substitution operators, or control characters.
     */
    public static final List<String> DANGEROUS_SHELL_PATTERNS = List.of(
            "$(",   // Command substitution
            "`",    // Backtick command substitution
            "$'",   // ANSI-C quoting
            "\n",   // Newline (command injection)
            "\r",   // Carriage return (command injection)
            "\t",   // Tab (injection in some shells)
            "<(",   // Process substitution (input)
            ">(",   // Process substitution (output)
            "<<<",  // Here-string
            "<<",   // Here-doc (can embed commands)
            ">>",   // Append redirect
            ">",    // Output redirect
            "<",    // Input redirect
            "${"    // Variable expansion with braces
    );

    // -------------------------------------------------------------------
    // Content-block conversion
    // -------------------------------------------------------------------

    /**
     * Convert an ACP text block to a LangChain content block.
     * Returns a single text block carrying the same string.
     */
    public static List<Map<String, Object>> convertTextBlockToContentBlocks(
            ContentBlocks.TextContentBlock block) {
        Objects.requireNonNull(block, "block");
        return List.of(Map.of("type", "text", "text", block.text()));
    }

    /**
     * Convert an ACP image block to LangChain multimodal content.
     * Inline base64 data is wrapped in a {@code data:} URI and
     * emitted as an {@code image_url} block; missing data
     * falls back to a text placeholder.
     */
    public static List<Map<String, Object>> convertImageBlockToContentBlocks(
            ContentBlocks.ImageContentBlock block) {
        Objects.requireNonNull(block, "block");
        if (block.data() != null && !block.data().isEmpty()) {
            String dataUri = "data:" + block.mimeType() + ";base64," + block.data();
            return List.of(Map.of(
                    "type", "image_url",
                    "image_url", Map.of("url", dataUri)));
        }
        return List.of(Map.of(
                "type", "text",
                "text", "[Image: no data available]"));
    }

    /**
     * Convert an ACP audio block. Audio is not currently
     * supported by the Deep Agent multimodal content path,
     * so this always raises {@link UnsupportedOperationException}.
     */
    public static List<Map<String, Object>> convertAudioBlockToContentBlocks(
            ContentBlocks.AudioContentBlock block) {
        throw new UnsupportedOperationException("Audio is not currently supported.");
    }

    /**
     * Convert an ACP resource block (a file pointer) to a
     * text content block that the model can read. The
     * {@code root_dir} prefix is stripped from the URI so
     * the model sees a path relative to the agent's working
     * directory.
     */
    public static List<Map<String, Object>> convertResourceBlockToContentBlocks(
            ContentBlocks.ResourceContentBlock block,
            String rootDir) {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(rootDir, "rootDir");
        final String filePrefix = "file://";
        StringBuilder text = new StringBuilder("[Resource: ").append(block.name());
        if (block.uri() != null && !block.uri().isEmpty()) {
            String uri = block.uri();
            boolean hasFilePrefix = uri.startsWith(filePrefix);
            String path = hasFilePrefix ? uri.substring(filePrefix.length()) : uri;
            if (path.startsWith(rootDir)) {
                path = path.substring(rootDir.length());
                while (path.startsWith("/")) path = path.substring(1);
            }
            uri = hasFilePrefix ? "file://" + path : path;
            text.append("\nURI: ").append(uri);
        }
        if (block.description().isPresent()) {
            text.append("\nDescription: ").append(block.description().get());
        }
        if (block.mimeType().isPresent()) {
            text.append("\nMIME type: ").append(block.mimeType().get());
        }
        text.append("]");
        return List.of(Map.of("type", "text", "text", text.toString()));
    }

    /**
     * Convert an embedded resource block. Text resources are
     * wrapped in a single text block; blob resources become a
     * data URI string. Raises {@link IllegalArgumentException}
     * if the embedded resource has neither {@code text} nor
     * {@code blob}.
     */
    public static List<Map<String, Object>> convertEmbeddedResourceBlockToContentBlocks(
            ContentBlocks.EmbeddedResourceContentBlock block) {
        Objects.requireNonNull(block, "block");
        ContentBlocks.EmbeddedResource resource = block.resource();
        if (resource instanceof ContentBlocks.TextResource text) {
            return List.of(Map.of(
                    "type", "text",
                    "text", "[Embedded " + text.mimeType() + " resource: " + text.text()));
        }
        if (resource instanceof ContentBlocks.BlobResource blob) {
            String dataUri = "data:" + blob.mimeType() + ";base64," + blob.blob();
            return List.of(Map.of(
                    "type", "text",
                    "text", "[Embedded resource: " + dataUri + "]"));
        }
        throw new IllegalArgumentException(
                "Could not parse embedded resource block. " +
                        "Block expected either a `text` or `blob` property.");
    }

    /**
     * Convert any {@link ContentBlock} to the LangChain content
     * shape. Mirrors the dispatch the Python port performs in
     * {@code AgentServerACP.prompt}.
     */
    public static List<Map<String, Object>> convertContentBlockToContentBlocks(
            ContentBlock block, String rootDir) {
        if (block instanceof ContentBlocks.TextContentBlock t) {
            return convertTextBlockToContentBlocks(t);
        }
        if (block instanceof ContentBlocks.ImageContentBlock i) {
            return convertImageBlockToContentBlocks(i);
        }
        if (block instanceof ContentBlocks.AudioContentBlock a) {
            return convertAudioBlockToContentBlocks(a);
        }
        if (block instanceof ContentBlocks.ResourceContentBlock r) {
            return convertResourceBlockToContentBlocks(r, rootDir);
        }
        if (block instanceof ContentBlocks.EmbeddedResourceContentBlock e) {
            return convertEmbeddedResourceBlockToContentBlocks(e);
        }
        return List.of(Map.of("type", "text", "text", block.toString()));
    }

    // -------------------------------------------------------------------
    // Shell-injection heuristics
    // -------------------------------------------------------------------

    /**
     * Check if a command contains dangerous shell patterns.
     * These patterns can be used to bypass allow-list validation
     * by embedding arbitrary commands within seemingly safe
     * commands.
     */
    public static boolean containsDangerousPatterns(String command) {
        if (command == null) return false;
        for (String pattern : DANGEROUS_SHELL_PATTERNS) {
            if (command.contains(pattern)) return true;
        }
        // Bare variable expansion ($VAR without braces) can leak sensitive paths.
        if (Pattern.compile("\\$[A-Za-z_]").matcher(command).find()) {
            return true;
        }
        // Standalone & (background execution) should not be auto-approved.
        // Matches & not part of &&.
        return Pattern.compile("(?<![&])&(?![&])").matcher(command).find();
    }

    /**
     * Extract all command types from a shell command, handling
     * {@code &&} / {@code ||} / {@code ;} separators and pipes.
     * Sensitive commands (python, node, npm, uv, ...) have
     * dedicated handlers that include subcommands and module
     * names in the signature; non-sensitive commands are
     * returned as their base command.
     */
    public static List<String> extractCommandTypes(String command) {
        if (command == null || command.trim().isEmpty()) return List.of();

        List<String> commandTypes = new ArrayList<>();
        for (String segment : COMPOUND_SPLIT.split(command)) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty()) continue;
            for (String pipeSegment : trimmed.split("\\|")) {
                String pipe = pipeSegment.trim();
                if (pipe.isEmpty()) continue;
                List<String> tokens = shlexSplit(pipe);
                if (tokens == null || tokens.isEmpty()) continue;
                String baseCmd = tokens.get(0);
                java.util.function.Function<List<String>, String> handler =
                        COMMAND_HANDLERS.get(baseCmd);
                commandTypes.add(handler != null
                        ? handler.apply(tokens)
                        : baseCmd);
            }
        }
        return commandTypes;
    }

    private static final Pattern COMPOUND_SPLIT = Pattern.compile("&&|\\|\\||;");

    private static final Map<String, java.util.function.Function<List<String>, String>> COMMAND_HANDLERS =
            buildCommandHandlers();

    private static Map<String, java.util.function.Function<List<String>, String>> buildCommandHandlers() {
        Map<String, java.util.function.Function<List<String>, String>> h = new LinkedHashMap<>();
        java.util.function.Function<List<String>, String> python = Utils::extractPythonSignature;
        h.put("python", python);
        h.put("python3", python);
        h.put("node", Utils::extractNodeSignature);
        h.put("npm", Utils::extractNpmSignature);
        h.put("npx", Utils::extractNpxSignature);
        java.util.function.Function<List<String>, String> yarnPnpm = Utils::extractYarnPnpmSignature;
        h.put("yarn", yarnPnpm);
        h.put("pnpm", yarnPnpm);
        h.put("uv", Utils::extractUvSignature);
        return Map.copyOf(h);
    }

    private static String extractPythonSignature(List<String> tokens) {
        String base = tokens.get(0);
        if (tokens.size() < 2) return base;
        if ("-m".equals(tokens.get(1)) && tokens.size() > 2) {
            return base + " -m " + tokens.get(2);
        }
        if ("-c".equals(tokens.get(1))) {
            return base + " -c";
        }
        return base;
    }

    private static String extractNodeSignature(List<String> tokens) {
        String base = tokens.get(0);
        if (tokens.size() < 2) return base;
        if ("-e".equals(tokens.get(1)) || "-p".equals(tokens.get(1))) {
            return base + " " + tokens.get(1);
        }
        return base;
    }

    private static String extractNpmSignature(List<String> tokens) {
        String base = tokens.get(0);
        if (tokens.size() < 2) return base + " " + tokens.get(1);
        String sub = tokens.get(1);
        if ("run".equals(sub) && tokens.size() > 2) {
            return base + " run " + tokens.get(2);
        }
        return base + " " + sub;
    }

    private static String extractNpxSignature(List<String> tokens) {
        String base = tokens.get(0);
        return tokens.size() > 1 ? base + " " + tokens.get(1) : base;
    }

    private static String extractYarnPnpmSignature(List<String> tokens) {
        String base = tokens.get(0);
        if (tokens.size() < 2) return base;
        String sub = tokens.get(1);
        if ("run".equals(sub) && tokens.size() > 2) {
            return base + " run " + tokens.get(2);
        }
        return base + " " + sub;
    }

    private static String extractUvSignature(List<String> tokens) {
        String base = tokens.get(0);
        if (tokens.size() < 2) return base + " " + tokens.get(1);
        String sub = tokens.get(1);
        if ("run".equals(sub) && tokens.size() > 2) {
            return base + " run " + tokens.get(2);
        }
        return base + " " + sub;
    }

    /**
     * Trim a command string for display. Mirrors
     * {@code truncate_execute_command_for_display}.
     */
    public static String truncateExecuteCommandForDisplay(String command) {
        if (command == null) return "";
        if (command.length() >= MAX_DISPLAY_COMMAND_LENGTH) {
            return command.substring(0, MAX_DISPLAY_COMMAND_LENGTH) + "...";
        }
        return command;
    }

    /**
     * Format an execute-tool result for display. Extracts the
     * exit-code line and the truncation line, then composes a
     * markdown block with command / output / status.
     */
    public static String formatExecuteResult(String command, String result) {
        if (result == null) result = "";
        String[] lines = result.split("\n", -1);
        List<String> outputLines = new ArrayList<>();
        String exitCodeLine = null;
        String truncatedLine = null;
        for (String line : lines) {
            if (line.startsWith("[Command ") && line.contains("exit code")) {
                exitCodeLine = line;
            } else if (line.startsWith("[Output was truncated")) {
                truncatedLine = line;
            } else {
                outputLines.add(line);
            }
        }
        String output = String.join("\n", outputLines);
        // Strip a single trailing newline (mirrors Python's rstrip()).
        if (output.endsWith("\n")) {
            output = output.substring(0, output.length() - 1);
        }
        StringBuilder out = new StringBuilder();
        out.append("**Command:**\n```bash\n").append(command).append("\n```\n");
        if (!output.isEmpty()) {
            out.append("**Output:**\n```\n").append(output).append("\n```\n");
        } else {
            out.append("**Output:** _(empty)_\n");
        }
        if (exitCodeLine != null) {
            String stripped = exitCodeLine;
            if (stripped.startsWith("[")) stripped = stripped.substring(1);
            if (stripped.endsWith("]")) stripped = stripped.substring(0, stripped.length() - 1);
            out.append("**Status:** ").append(stripped);
        }
        if (truncatedLine != null) {
            String stripped = truncatedLine;
            if (stripped.startsWith("[")) stripped = stripped.substring(1);
            if (stripped.endsWith("]")) stripped = stripped.substring(0, stripped.length() - 1);
            out.append("\n_").append(stripped).append("_");
        }
        return out.toString();
    }

    // -------------------------------------------------------------------
    // Token splitting (small, dependency-free shlex implementation)
    // -------------------------------------------------------------------

    /**
     * Split a command string into tokens using POSIX-ish
     * shell-rules. Returns {@code null} when the input is
     * unterminated (mirrors {@code shlex.split}'s
     * {@code ValueError} on missing quotes).
     */
    private static List<String> shlexSplit(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean hadToken = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (inSingle) {
                if (c == '\'') inSingle = false;
                else current.append(c);
                continue;
            }
            if (inDouble) {
                if (c == '"') inDouble = false;
                else if (c == '\\' && i + 1 < input.length()) {
                    current.append(input.charAt(++i));
                } else {
                    current.append(c);
                }
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (hadToken) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    hadToken = false;
                }
                continue;
            }
            hadToken = true;
            if (c == '\'') {
                inSingle = true;
            } else if (c == '"') {
                inDouble = true;
            } else if (c == '\\' && i + 1 < input.length()) {
                current.append(input.charAt(++i));
            } else {
                current.append(c);
            }
        }
        if (inSingle || inDouble) return null; // unterminated
        if (hadToken) tokens.add(current.toString());
        return tokens;
    }

    /**
     * Match helper for callers that need to perform
     * {@code Pattern}-style work on a command string. Mirrors
     * the {@code re.search} / {@code re.match} semantics used in
     * the Python port (anywhere match).
     */
    public static boolean matchesAnywhere(String input, String regex) {
        if (input == null || regex == null) return false;
        return Pattern.compile(regex).matcher(input).find();
    }

    /**
     * Match helper for callers that need a {@link Matcher}
     * against a command string. Returns an empty matcher when
     * the input doesn't match; callers should call
     * {@link Matcher#find()} or {@link Matcher#matches()} to
     * decide.
     */
    public static Matcher matcher(String input, String regex) {
        return Pattern.compile(regex).matcher(input == null ? "" : input);
    }
}
