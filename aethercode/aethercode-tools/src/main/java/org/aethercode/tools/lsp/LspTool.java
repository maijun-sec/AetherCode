package org.aethercode.tools.lsp;

import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.services.LanguageClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Lightweight LSP client wrapper. Modelled on the TS {@code tools/LSPTool}, but built on
 * lsp4j so the JVM side can talk to a real language server.
 *
 * <p>The tool exposes four sub-actions:
 * <ul>
 *   <li>{@code get_diagnostics} — read the cached diagnostics for a file (or all files)</li>
 *   <li>{@code list_servers}   — which language servers are connected and what languages they handle</li>
 *   <li>{@code hover}          — fetch hover info for a position (delegates to the connected server)</li>
 *   <li>{@code definition}     — fetch "go to definition" for a position</li>
 * </ul>
 *
 * <p>Real LSP transport wiring (stdio / socket) is delegated to a {@link LspTransport}
 * abstraction so the tool itself stays small and the transport can be swapped (prior round+ will
 * add real stdio bootstrap and `didOpen` file synchronization).
 */
public class LspTool {

    public static final String NAME = "lsp";
    private static final Logger LOG = LoggerFactory.getLogger(LspTool.class);
    private static final Map<String, List<Diagnostic>> DIAGNOSTICS = new ConcurrentHashMap<>();

    public static Tool build() {
        var props = new LinkedHashMap<String, Map<String, Object>>();
        props.put("action",   Tools.stringProp("One of: get_diagnostics, list_servers, hover, definition, format."));
        props.put("file_path",Tools.stringProp("Absolute path of the file. Required for all but list_servers."));
        props.put("line",     Tools.intProp("1-indexed line. Required for hover/definition/format."));
        props.put("column",   Tools.intProp("1-indexed column. Required for hover/definition/format."));
        Map<String, Object> schema = Tools.objectSchema(props, "action");
        return Tools.build(new ToolDef(
                NAME,
                "LSP-aware actions: read diagnostics, list connected servers, fetch hover/definition. " +
                        "Wraps the lsp4j language-client primitives.",
                schema,
                (input, ctx) -> CompletableFuture.completedFuture(call(input, ctx))
        ));
    }

    public static Tool.ToolResult call(Map<String, Object> input, Tool.CallContext ctx) {
        String action = (String) input.get("action");
        if (action == null) return Tool.ToolResult.error("action is required");
        try {
            return switch (action) {
                case "list_servers"   -> listServers();
                case "get_diagnostics"-> getDiagnostics((String) input.get("file_path"));
                case "hover"          -> hover((String) input.get("file_path"),
                        num(input, "line"), num(input, "column"));
                case "definition"     -> definition((String) input.get("file_path"),
                        num(input, "line"), num(input, "column"));
                case "format"         -> format((String) input.get("file_path"),
                        num(input, "line"), num(input, "column"));
                default -> Tool.ToolResult.error("unknown action: " + action);
            };
        } catch (Exception e) {
            LOG.warn("lsp action '{}' failed: {}", action, e.getMessage());
            return Tool.ToolResult.error("lsp " + action + " failed: " + e.getMessage());
        }
    }

    public static boolean isReadOnly(Map<String, Object> input) { return true; }

    // ---------------------------------------------------------------------------------------
    //  Diagnostics capture — feeds the in-memory cache when a server publishes
    // ---------------------------------------------------------------------------------------

    /** Register a hook so the IDE-side LanguageClient can hand us diagnostics. */
    public static void recordDiagnostics(String fileUri, PublishDiagnosticsParams params) {
        DIAGNOSTICS.put(fileUri, params.getDiagnostics());
    }

    private static Tool.ToolResult listServers() {
        // R3 doesn't actually spawn language servers yet — return the configured list.
        String configured = System.getenv("AETHERCODE_LSP_SERVERS");
        if (configured == null || configured.isBlank()) {
            return Tool.ToolResult.of("no LSP servers configured (set AETHERCODE_LSP_SERVERS, e.g. 'jdtls:/path/jdtls,javalsp:/path/javalsp')");
        }
        StringBuilder sb = new StringBuilder();
        for (String entry : configured.split(",")) {
            sb.append("- ").append(entry).append('\n');
        }
        return Tool.ToolResult.of("configured LSP servers:\n" + sb);
    }

    private static Tool.ToolResult getDiagnostics(String filePath) {
        if (filePath == null) {
            StringBuilder sb = new StringBuilder();
            DIAGNOSTICS.forEach((uri, list) -> {
                if (list == null || list.isEmpty()) return;
                sb.append(uri).append(":\n");
                list.forEach(d -> sb.append("  • ")
                        .append(d.getSeverity() == null ? "?" : d.getSeverity())
                        .append(' ').append(d.getMessage()).append('\n'));
            });
            return Tool.ToolResult.of(sb.length() == 0 ? "(no diagnostics)" : sb.toString());
        }
        List<Diagnostic> list = DIAGNOSTICS.get(filePath);
        if (list == null || list.isEmpty()) return Tool.ToolResult.of("(no diagnostics for " + filePath + ")");
        StringBuilder sb = new StringBuilder();
        list.forEach(d -> sb.append("  • ")
                .append(d.getSeverity() == null ? "?" : d.getSeverity())
                .append(' ').append(d.getMessage()).append('\n'));
        return Tool.ToolResult.of(sb.toString());
    }

    private static Tool.ToolResult hover(String filePath, int line, int column) {
        if (filePath == null) return Tool.ToolResult.error("file_path is required for hover");
        if (line < 1 || column < 1) return Tool.ToolResult.error("line/column must be 1-indexed");
        // R3 stub: a real implementation would route to the lsp4j client and call
        // LanguageClient#textDocumentHover. The shape of the result is documented so the
        // model can already use the tool.
        return Tool.ToolResult.of("hover at " + filePath + ":" + line + ":" + column
                + " (R3 stub: connect a real LSP server via AETHERCODE_LSP_SERVERS)");
    }

    private static Tool.ToolResult definition(String filePath, int line, int column) {
        if (filePath == null) return Tool.ToolResult.error("file_path is required for definition");
        if (line < 1 || column < 1) return Tool.ToolResult.error("line/column must be 1-indexed");
        return Tool.ToolResult.of("definition at " + filePath + ":" + line + ":" + column
                + " (R3 stub: connect a real LSP server via AETHERCODE_LSP_SERVERS)");
    }

    private static Tool.ToolResult format(String filePath, int line, int column) {
        if (filePath == null) return Tool.ToolResult.error("file_path is required for format");
        return Tool.ToolResult.of("format " + filePath + " (R3 stub)");
    }

    private static int num(Map<String, Object> input, String key) {
        Object v = input.get(key);
        return v instanceof Number n ? n.intValue() : -1;
    }

    // ---------------------------------------------------------------------------------------
    //  R3 also includes a small in-process LanguageClient — handy for tests and for the
    //  IDEA plugin's auto-connect path (R3 IDEA side wires this to the platform's
    //  EditorNotifications).
    // ---------------------------------------------------------------------------------------

    /** Tiny in-memory client useful for tests / IDE wiring. */
    public static class InMemoryLanguageClient implements LanguageClient {
        @Override public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public void showMessage(MessageParams params) { /* noop */ }
        @Override public void publishDiagnostics(PublishDiagnosticsParams params) {
            if (params == null) return;
            recordDiagnostics(params.getUri(), params);
        }
        @Override public void logMessage(MessageParams params) { /* noop */ }
        @Override public CompletableFuture<Void> refreshSemanticTokens() { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<Void> refreshCodeLenses() { return CompletableFuture.completedFuture(null); }
        // Generic fallbacks for newer lsp4j methods; silence the compiler
        public void telemetryEvent(Object o) {}
        public CompletableFuture<Object> showDocument(Object o) { return CompletableFuture.completedFuture(null); }
        public void semanticTokens_Refresh() {}
        public void codeLens_Refresh() {}
        public void workspaceFolders_Refresh() {}
    }
}
