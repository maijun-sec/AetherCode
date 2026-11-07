// Copyright (c) 2026 maijun
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

package org.aethercode.protocol.methods;

import org.aethercode.core.tool.Tool;
import org.aethercode.core.transcript.SessionStore;
import org.aethercode.sdk.AetherCodeEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R388j — the session PREVIEW must survive a quote inside the text.
 *
 * <p>The preview extractor is a cheap scanner, not a JSON parser: it
 * finds {@code "text"}, then the opening quote, then the CLOSING
 * quote. The closing quote used to be found with
 * {@code indexOf('"', quoteIdx + 1)} — the FIRST quote of any kind,
 * escaped or not.
 *
 * <p>So a user message of {@code say "hi" now} produced the title
 * {@code say \}. Every session whose first prompt mentioned a quoted
 * word — a filename, a code snippet, a shell command — got a title
 * cut off mid-sentence, and the session list in the TUI and the
 * Desktop both showed the mutilated text.
 *
 * <p>A JSON string ends at a quote that is NOT preceded by an odd
 * number of backslashes. That is the rule this test pins, together
 * with the unescaping that makes the title read as the user typed
 * it.
 */
class AetherCodeMethodsR388jPreviewTest {

    private static AetherCodeEngine engineFor(Path cwd) {
        return new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .build();
    }

    /** Write a one-message transcript and return its session id. */
    private static String sessionWith(Path cwd, String jsonTextValue) throws Exception {
        AetherCodeEngine engine = engineFor(cwd);
        SessionStore store = new SessionStore(cwd.resolve(".aethercode/sessions"));
        engine.setSessionStore(store);
        String id = "preview-" + System.nanoTime();
        Path file = store.dir().resolve(id + ".jsonl");
        Files.writeString(file,
                "{\"id\":\"m1\",\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":"
                        + jsonTextValue + "}]}",
                StandardCharsets.UTF_8);
        store.touch(id, null, null, null, null, null, 0);
        return id;
    }

    private static String previewOf(Path cwd, String id) {
        AetherCodeEngine engine = engineFor(cwd);
        SessionStore store = new SessionStore(cwd.resolve(".aethercode/sessions"));
        engine.setSessionStore(store);
        AetherCodeMethods m = new AetherCodeMethods(engine, n -> {});
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) m.listSessions(Map.of("withPreview", true));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sessions = (List<Map<String, Object>>) r.get("sessions");
        Map<String, Object> target = sessions.stream()
                .filter(s -> id.equals(s.get("id")))
                .findFirst().orElseThrow();
        return (String) target.get("preview");
    }

    @Test
    void preview_keepsTextAfterAnEscapedQuote(@TempDir Path cwd) throws Exception {
        // The JSON body of the text is: say \"hi\" now
        String id = sessionWith(cwd, "\"say \\\"hi\\\" now\"");
        assertThat(previewOf(cwd, id)).isEqualTo("say \"hi\" now");
    }

    @Test
    void preview_keepsTextAfterSeveralEscapedQuotes(@TempDir Path cwd) throws Exception {
        String id = sessionWith(cwd, "\"a \\\"b\\\" c \\\"d\\\" e\"");
        assertThat(previewOf(cwd, id)).isEqualTo("a \"b\" c \"d\" e");
    }

    @Test
    void preview_unescapesBackslashAndNewline(@TempDir Path cwd) throws Exception {
        String id = sessionWith(cwd, "\"path C:\\\\tmp and a\\nnewline\"");
        assertThat(previewOf(cwd, id)).isEqualTo("path C:\\tmp and a newline");
    }

    @Test
    void preview_stopsAtTheRealClosingQuote(@TempDir Path cwd) throws Exception {
        // The classic shape: a Windows path in a quoted string.
        String id = sessionWith(cwd, "\"open \\\"D:\\\\tmp\\\\x.txt\\\" please\"");
        assertThat(previewOf(cwd, id)).isEqualTo("open \"D:\\tmp\\x.txt\" please");
    }

    @Test
    void preview_isUnchangedForPlainText(@TempDir Path cwd) throws Exception {
        String id = sessionWith(cwd, "\"just a plain prompt\"");
        assertThat(previewOf(cwd, id)).isEqualTo("just a plain prompt");
    }

    @Test
    void preview_handlesCJKText(@TempDir Path cwd) throws Exception {
        String id = sessionWith(cwd, "\"帮我把\\\"排序\\\"这个函数写出来\"");
        assertThat(previewOf(cwd, id)).isEqualTo("帮我把\"排序\"这个函数写出来");
    }
}
