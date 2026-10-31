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
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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
