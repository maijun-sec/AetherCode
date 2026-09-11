package org.aethercode.tools.lsp;

import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextDocumentSyncOptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * covers the auto-detect / fallback path of {@link StdioLspSession}.
 *
 * <p>StdioLspSession is normally backed by a live LSP server; here we exercise
 * the capability-driven logic by injecting {@link ServerCapabilities} via
 * reflection (the field is package-private to this test, not exposed publicly).
 */
class StdioLspSyncFallbackTest {

    @Test
    void defaultSyncKindIsFull() throws Exception {
        StdioLspSession s = newSession(null);
        assertThat(s.syncKind()).isEqualTo(TextDocumentSyncKind.Full);
    }

    @Test
    void leftSyncKindIsFull() throws Exception {
        ServerCapabilities c = new ServerCapabilities();
        c.setTextDocumentSync(org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(TextDocumentSyncKind.Full));
        StdioLspSession s = newSession(c);
        assertThat(s.syncKind()).isEqualTo(TextDocumentSyncKind.Full);
    }

    @Test
    void rightSyncOptionsRespected() throws Exception {
        ServerCapabilities c = new ServerCapabilities();
        TextDocumentSyncOptions opts = new TextDocumentSyncOptions();
        opts.setChange(TextDocumentSyncKind.Incremental);
        opts.setOpenClose(true);
        c.setTextDocumentSync(org.eclipse.lsp4j.jsonrpc.messages.Either.forRight(opts));
        StdioLspSession s = newSession(c);
        assertThat(s.syncKind()).isEqualTo(TextDocumentSyncKind.Incremental);
    }

    @Test
    void rightSyncOptionsWithNullChangeIsFull() throws Exception {
        ServerCapabilities c = new ServerCapabilities();
        TextDocumentSyncOptions opts = new TextDocumentSyncOptions();
        opts.setOpenClose(true);
        c.setTextDocumentSync(org.eclipse.lsp4j.jsonrpc.messages.Either.forRight(opts));
        StdioLspSession s = newSession(c);
        assertThat(s.syncKind()).isEqualTo(TextDocumentSyncKind.Full);
    }

    @Test
    void wasLastDidChangeDowngradedStartsFalse() throws Exception {
        StdioLspSession s = newSession(null);
        assertThat(s.wasLastDidChangeDowngraded()).isFalse();
    }

    private static StdioLspSession newSession(ServerCapabilities caps) throws Exception {
        StdioLspSession s = new StdioLspSession(null, null, null);
        if (caps != null) {
            Field f = StdioLspSession.class.getDeclaredField("capabilities");
            f.setAccessible(true);
            f.set(s, caps);
        }
        return s;
    }
}
