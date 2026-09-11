package org.aethercode.tools.lsp;

import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextDocumentSyncOptions;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.services.LanguageServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * handle the LSP session lifecycle: initialize, didOpen, didChange, shutdown. Wraps
 * the lsp4j {@link LanguageServer} proxy and tracks per-document versions.
 */
public class StdioLspSession {

    private static final Logger LOG = LoggerFactory.getLogger(StdioLspSession.class);

    private final LanguageServer server;
    private final CompletableFuture<Void> listen;
    private final Process process;
    private final Map<String, Integer> versions = new HashMap<>();
    private boolean initialized = false;
    /** capabilities captured from the initialize response — drives the didChange fallback. */
    private ServerCapabilities capabilities;
    /** did we silently downgrade the last didChange from incremental to full? */
    private volatile boolean lastDidChangeDowngraded = false;

    public StdioLspSession(LanguageServer server, CompletableFuture<Void> listen, Process process) {
        this.server = server;
        this.listen = listen;
        this.process = process;
    }

    public CompletableFuture<InitializeResult> initialize(String rootUri) {
        InitializeParams params = new InitializeParams();
        params.setRootUri(rootUri);
        params.setProcessId((int) ProcessHandle.current().pid());
        var fut = server.initialize(params);
        fut.thenAccept(r -> {
            server.initialized(new org.eclipse.lsp4j.InitializedParams());
            initialized = true;
            this.capabilities = r.getCapabilities();
            LOG.info("LSP session initialized; sync kind = {}", syncKindLabel(capabilities));
        });
        return fut;
    }

    /** the server's text-document sync kind, or {@code Full} if absent. */
    public TextDocumentSyncKind syncKind() {
        if (capabilities == null) return TextDocumentSyncKind.Full;
        var sync = capabilities.getTextDocumentSync();
        if (sync == null) return TextDocumentSyncKind.Full;
        if (sync.isLeft()) return TextDocumentSyncKind.Full;
        TextDocumentSyncOptions opts = sync.getRight();
        if (opts == null || opts.getChange() == null) return TextDocumentSyncKind.Full;
        return opts.getChange();
    }

    public boolean wasLastDidChangeDowngraded() { return lastDidChangeDowngraded; }
    public ServerCapabilities capabilities() { return capabilities; }

    private static String syncKindLabel(ServerCapabilities c) {
        if (c == null) return "(none)";
        var sync = c.getTextDocumentSync();
        if (sync == null) return "Full (default)";
        if (sync.isLeft()) return "Full";
        var opts = sync.getRight();
        if (opts == null) return "Full (default)";
        var k = opts.getChange();
        return k == null ? "Full (default)" : k.toString();
    }

    public void didOpen(Path file, String languageId, String content) {
        if (!initialized) throw new IllegalStateException("not initialized");
        DidOpenTextDocumentParams p = new DidOpenTextDocumentParams();
        TextDocumentItem item = new TextDocumentItem();
        item.setUri(file.toUri().toString());
        item.setLanguageId(languageId);
        item.setVersion(0);
        item.setText(content);
        p.setTextDocument(item);
        server.getTextDocumentService().didOpen(p);
        versions.put(file.toString(), 0);
    }

    public void didChange(Path file, String content) {
        didChange(file, content, IncrementalChange.FULL);
    }

    /**
     * incremental didChange. Modelled on the TS
     * {@code services/lsp/documentSync.ts}. Three change modes:
     *
     * <ul>
     *   <li>{@link IncrementalChange#FULL} — replace the whole document</li>
     *   <li>{@link IncrementalChange#RANGE} — replace a sub-range (startLine..endLine)</li>
     *   <li>{@link IncrementalChange#INSERT} — insert at a position, no deletion</li>
     * </ul>
     *
     * <p>Range and insert modes need the server to advertise
     * {@code TextDocumentSyncKind.Incremental}; we fall back to full when the server
     * only supports full sync.
     */
    public void didChange(Path file, String content, IncrementalChange change) {
        if (!initialized) throw new IllegalStateException("not initialized");
        // when the server only supports full sync, downgrade incremental requests to FULL.
        TextDocumentSyncKind k = syncKind();
        boolean downgraded = false;
        if (k != TextDocumentSyncKind.Incremental && change.kind != IncrementalChange.Kind.FULL) {
            LOG.debug("LSP server only supports full sync — downgrading incremental change for {}", file);
            change = IncrementalChange.FULL;
            downgraded = true;
        }
        lastDidChangeDowngraded = downgraded;
        int v = versions.getOrDefault(file.toString(), 0) + 1;
        versions.put(file.toString(), v);
        DidChangeTextDocumentParams p = new DidChangeTextDocumentParams();
        VersionedTextDocumentIdentifier id = new VersionedTextDocumentIdentifier();
        id.setUri(file.toUri().toString());
        id.setVersion(v);
        p.setTextDocument(id);
        TextDocumentContentChangeEvent ev = new TextDocumentContentChangeEvent();
        switch (change.kind) {
            case FULL -> {
                ev.setText(content);
                ev.setRange(null);
                p.setContentChanges(java.util.List.of(ev));
            }
            case RANGE -> {
                ev.setText(content);
                org.eclipse.lsp4j.Range r = new org.eclipse.lsp4j.Range();
                r.setStart(new org.eclipse.lsp4j.Position(change.startLine, 0));
                r.setEnd(new org.eclipse.lsp4j.Position(change.endLine, 0));
                ev.setRange(r);
                p.setContentChanges(java.util.List.of(ev));
            }
            case INSERT -> {
                ev.setText(content);
                org.eclipse.lsp4j.Range r = new org.eclipse.lsp4j.Range();
                r.setStart(new org.eclipse.lsp4j.Position(change.startLine, change.startCol));
                r.setEnd(new org.eclipse.lsp4j.Position(change.startLine, change.startCol));
                ev.setRange(r);
                p.setContentChanges(java.util.List.of(ev));
            }
        }
        server.getTextDocumentService().didChange(p);
    }

    /** incremental change descriptor. */
    public static final class IncrementalChange {
        public enum Kind { FULL, RANGE, INSERT }
        public final Kind kind;
        public final String text;
        public final int startLine, startCol, endLine, endCol;

        private IncrementalChange(Kind k, String text, int sl, int sc, int el, int ec) {
            kind = k; this.text = text; startLine = sl; startCol = sc; endLine = el; endCol = ec;
        }
        public static IncrementalChange FULL = new IncrementalChange(Kind.FULL, "", 0, 0, 0, 0);
        public static IncrementalChange range(int sl, int el, String text) {
            return new IncrementalChange(Kind.RANGE, text, sl, 0, el, 0);
        }
        public static IncrementalChange insert(int line, int col, String text) {
            return new IncrementalChange(Kind.INSERT, text, line, col, line, col);
        }
    }

    public void didClose(Path file) {
        if (!initialized) return;
        org.eclipse.lsp4j.DidCloseTextDocumentParams p = new org.eclipse.lsp4j.DidCloseTextDocumentParams();
        TextDocumentIdentifier id = new TextDocumentIdentifier();
        id.setUri(file.toUri().toString());
        p.setTextDocument(id);
        server.getTextDocumentService().didClose(p);
        versions.remove(file.toString());
    }

    public void shutdown() {
        try {
            server.shutdown().get();
            server.exit();
        } catch (Exception e) {
            LOG.warn("shutdown failed: {}", e.getMessage());
        }
        if (process != null && process.isAlive()) process.destroy();
    }

    public LanguageServer server() { return server; }
    public boolean isInitialized() { return initialized; }

    public static String rootUriFrom(Path p) { return p.toUri().toString(); }
    public static String uriFrom(Path p) { return Paths.get(p.toString()).toUri().toString(); }

    public int versionOf(Path file) { return versions.getOrDefault(file.toString(), 0); }
}
