package org.aethercode.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * External editor support for composing prompts.
 *
 * <p>Java-native port of the Python {@code deepagents_code.editor} module.
 * Resolves an editor command from the environment, opens a temp file, and
 * returns the saved contents.</p>
 */
public final class Editor {
    private Editor() {}

    private static final Logger LOG = LoggerFactory.getLogger(Editor.class);

    /** Mapping of GUI editor base names to their blocking flag. */
    public static final Map<String, String> GUI_WAIT_FLAG = Map.of(
            "code", "--wait",
            "cursor", "--wait",
            "zed", "--wait",
            "atom", "--wait",
            "subl", "-w",
            "windsurf", "--wait");

    /** Set of vim-family editor base names that receive the {@code -i NONE} flag. */
    public static final Set<String> VIM_EDITORS = Set.of("vi", "vim", "nvim");

    /** Maximum editor name length that remains readable in compact hints. */
    public static final int EDITOR_DISPLAY_NAME_MAX_LENGTH = 20;

    private static final Set<Character> ALLOWED_PUNCTUATION = Set.of('.', '_', '+', '-');

    /** Raised when an external editor cannot be opened or read. */
    public static class ExternalEditorError extends RuntimeException {
        public ExternalEditorError(String msg) { super(msg); }
        public ExternalEditorError(String msg, Throwable cause) { super(msg, cause); }
    }

    /**
     * Resolve editor command from environment. Checks {@code VISUAL}, then
     * {@code EDITOR}, then falls back to platform default.
     *
     * @return tokenized command list, or {@code null} if the env var was set
     *         but empty after tokenization
     */
    public static List<String> resolveEditor() {
        return resolveEditor(System.getenv());
    }

    /** Variant that accepts an explicit env map. */
    public static List<String> resolveEditor(Map<String, String> env) {
        String editor = (env == null) ? null
                : (env.get("VISUAL") != null && !env.get("VISUAL").isEmpty()
                        ? env.get("VISUAL") : env.get("EDITOR"));
        if (editor == null || editor.isEmpty()) {
            if (isWindows()) {
                return List.of("notepad");
            }
            return List.of("vi");
        }
        List<String> tokens = shlexSplit(editor);
        return tokens.isEmpty() ? null : tokens;
    }

    /**
     * Return a safe configured editor name for user-facing hints.
     */
    public static String editorDisplayName() {
        return editorDisplayName(System.getenv());
    }

    /** Variant that accepts an explicit env map. */
    public static String editorDisplayName(Map<String, String> env) {
        String editor = (env == null) ? null
                : (env.get("VISUAL") != null && !env.get("VISUAL").isEmpty()
                        ? env.get("VISUAL") : env.get("EDITOR"));
        if (editor == null || editor.isEmpty()) {
            return null;
        }
        List<String> tokens;
        try {
            tokens = shlexSplit(editor);
        } catch (RuntimeException e) {
            return null;
        }
        if (tokens.isEmpty()) {
            return null;
        }
        String name = stripExt(basename(tokens.get(0)));
        if (name.isEmpty()
                || name.length() > EDITOR_DISPLAY_NAME_MAX_LENGTH
                || !hasAlnum(name)
                || !allAsciiAlnumOrAllowed(name)) {
            return null;
        }
        return name;
    }

    /**
     * Open {@code currentText} in an external editor and return the saved
     * contents. The text is written to a temp {@code .md} file, the configured
     * editor is launched, and the result is read back.
     *
     * @param currentText    the text to pre-populate in the editor
     * @param allowEmpty     return an empty or whitespace-only edited result
     *                       instead of treating it as cancellation
     * @param raiseOnError   re-raise editor launch and file errors instead of
     *                       treating them as cancellation
     * @return the edited text with normalized line endings, or {@code null} on
     *         cancellation / failure (when {@code raiseOnError} is false)
     * @throws ExternalEditorError if opening or reading the editor file fails
     *         while {@code raiseOnError} is true
     */
    public static String openInEditor(String currentText,
                                       boolean allowEmpty,
                                       boolean raiseOnError) {
        List<String> cmd = resolveEditor();
        if (cmd == null) {
            if (raiseOnError) {
                throw new ExternalEditorError("Editor command resolved to no arguments");
            }
            return null;
        }
        Path tmp = null;
        try {
            tmp = Files.createTempFile("deepagents-edit-", ".md");
            Files.writeString(tmp, currentText == null ? "" : currentText, StandardCharsets.UTF_8);
            List<String> full = prepareCommand(cmd, tmp.toString());

            // TODO: launch the editor process and wait for exit. The Java
            // port can use ProcessBuilder for portability; the Python port
            // hands stdin/stdout/stderr through. For now we return the
            // unedited text and let the caller treat it as cancellation.
            LOG.warn("openInEditor is not yet wired to an actual subprocess launch");
            String edited = Files.readString(tmp, StandardCharsets.UTF_8);
            edited = edited.replace("\r\n", "\n").replace("\r", "\n");
            if (edited.endsWith("\n")) {
                edited = edited.substring(0, edited.length() - 1);
            }
            if (!allowEmpty && edited.strip().isEmpty()) {
                return null;
            }
            return edited;
        } catch (IOException e) {
            if (raiseOnError) {
                throw new ExternalEditorError("External editor failed", e);
            }
            return null;
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

    /** Build the full command list with appropriate flags. */
    static List<String> prepareCommand(List<String> cmd, String filepath) {
        List<String> out = new ArrayList<>(cmd);
        String exe = stripExt(basename(cmd.get(0))).toLowerCase();
        if (GUI_WAIT_FLAG.containsKey(exe)) {
            String flag = GUI_WAIT_FLAG.get(exe);
            if (!out.contains(flag)) {
                out.add(1, flag);
            }
        }
        if (VIM_EDITORS.contains(exe) && !out.contains("-i")) {
            out.add("-i");
            out.add("NONE");
        }
        out.add(filepath);
        return out;
    }

    // ---- shlex/utility helpers -------------------------------------------------

    /** A small POSIX-style tokenizer covering the common case for $EDITOR values. */
    static List<String> shlexSplit(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean hadToken = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inSingle) {
                if (c == '\'') {
                    inSingle = false;
                } else {
                    cur.append(c);
                }
                continue;
            }
            if (inDouble) {
                if (c == '"') {
                    inDouble = false;
                } else if (c == '\\' && i + 1 < s.length()) {
                    cur.append(s.charAt(++i));
                } else {
                    cur.append(c);
                }
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (cur.length() > 0 || hadToken) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    hadToken = true;
                }
                continue;
            }
            if (c == '\'') {
                inSingle = true;
                hadToken = true;
            } else if (c == '"') {
                inDouble = true;
                hadToken = true;
            } else if (c == '\\' && i + 1 < s.length()) {
                cur.append(s.charAt(++i));
                hadToken = true;
            } else {
                cur.append(c);
                hadToken = true;
            }
        }
        if (cur.length() > 0 || hadToken) {
            out.add(cur.toString());
        }
        return out;
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static String basename(String p) {
        if (p == null) return "";
        int idx = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        return idx < 0 ? p : p.substring(idx + 1);
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    private static boolean hasAlnum(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 128 && Character.isLetterOrDigit(c)) {
                return true;
            }
        }
        return false;
    }

    private static boolean allAsciiAlnumOrAllowed(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 128) return false;
            if (!Character.isLetterOrDigit(c) && !ALLOWED_PUNCTUATION.contains(c)) return false;
        }
        return true;
    }
}
