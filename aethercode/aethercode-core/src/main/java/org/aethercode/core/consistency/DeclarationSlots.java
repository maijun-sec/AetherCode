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

package org.aethercode.core.consistency;

import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R415: a VALUE-BEARING DECLARATION — the unit the consistency check works
 * on.
 *
 * <h2>★ WHAT COUNTS AND WHAT DOES NOT ★</h2>
 * <p>A slot is a named thing that carries a value: a constant, a default, a
 * configuration key. The consistency question is about THOSE: if the value
 * of {@code MAX_RETRIES} changes in one file, the copies of that constant
 * must change with it.
 *
 * <p>★ A RENAMED METHOD IS NOT A SLOT ★ "rename {@code findOrders}" has
 * call sites, not copies — and "every call site was updated" is a
 * question the <b>language server</b> answers, not a text scan. Pretending
 * a text scan answers it would be a check that reports noise. So the slot
 * set is deliberately narrow: value-bearing declarations. The scope is
 * stated here so a reader knows what the engine does NOT claim.
 */
public final class DeclarationSlots {

    /**
     * A constant declaration: {@code public static final int MAX_RETRIES = 3;},
     * {@code export const MAX_RETRIES = 3}, {@code MAX_RETRIES = 3}.
     *
     * <p>★ WHY THE NAME MUST BE UPPER_SNAKE ★ every line of code has
     * assignments; only the CONSTANTS are contracts. Restricting the name
     * keeps the index small and the report readable.
     *
     * <p>★ WHY {@code =(?!=)} ★ without it, {@code if (X == 1)} parses as
     * "the constant X has the value {@code = 1}" — and a comparison would
     * masquerade as a declaration.
     */
    private static final Pattern CONSTANT = Pattern.compile(
            "^\\s*"
                    + "(?:(?:public|private|protected|static|final|const|let|var|val|export|default|inline|readonly|unsafe)\\s+)*"
                    + "(?:[A-Za-z_][A-Za-z0-9_]*(?:\\s*<[^>]*>)?\\s+)?"
                    + "([A-Z][A-Z0-9_]{1,})\\s*=(?!=)\\s*(.*?)\\s*;?\\s*$");

    /** A C/C++ preprocessor define. */
    private static final Pattern DEFINE = Pattern.compile(
            "^\\s*#\\s*define\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+(.+?)\\s*$");

    /**
     * A configuration key.
     *
     * <p>★ ONLY FOR CONFIG FILES ★ in source code, {@code url = ...} is
     * an assignment to a local; in a {@code .properties} or {@code .yaml}
     * it is a configuration contract. The file decides which it is.
     */
    private static final Pattern CONFIG_KEY = Pattern.compile(
            "^\\s*([A-Za-z_][A-Za-z0-9_.-]*)\\s*[:=](?!=)\\s*(.+?)\\s*$");

    /** The file extensions whose keys are configuration contracts. */
    public static boolean isConfigFile(Path file) {
        if (file == null) return false;
        String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".properties") || name.endsWith(".yaml") || name.endsWith(".yml")
                || name.endsWith(".conf") || name.endsWith(".cfg") || name.endsWith(".ini")
                || name.endsWith(".toml") || name.endsWith(".env")) {
            return true;
        }
        return name.equals(".env");
    }

    private DeclarationSlots() {}

    /**
     * One declaration.
     *
     * @param symbol the name
     * @param value the value, normalized (no trailing ';', collapsed spaces)
     * @param line the 1-based line it sits on
     */
    public record Slot(String symbol, String value, int line) {

        /** Does this slot carry the same value as that one? */
        public boolean sameValue(Slot other) {
            return other != null && value.equals(other.value());
        }
    }

    /**
     * The slots declared by a file's content.
     *
     * <p>★ THE FIRST DECLARATION WINS ★ a symbol declared twice in one
     * file is a duplicate declaration (a bug in the file, not a second
     * copy), and the first one is the one a reader sees.
     *
     * @param file the file the content came from (for the config rules)
     * @param content the text
     * @return symbol -> slot, in declaration order
     */
    public static java.util.Map<String, Slot> parse(Path file, String content) {
        java.util.Map<String, Slot> out = new java.util.LinkedHashMap<>();
        if (content == null || content.isEmpty()) return out;
        boolean config = isConfigFile(file);
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            if (raw.isBlank()) continue;
            Slot slot = matchConstant(raw, i + 1);
            if (slot == null) slot = matchDefine(raw, i + 1);
            if (slot == null && config) slot = matchConfigKey(raw, i + 1);
            if (slot == null) continue;
            out.putIfAbsent(slot.symbol(), slot);
        }
        return out;
    }

    private static Slot matchConstant(String line, int lineNo) {
        Matcher m = CONSTANT.matcher(line);
        if (!m.matches()) return null;
        String value = normalize(m.group(2));
        if (value.isEmpty()) return null;
        return new Slot(m.group(1), value, lineNo);
    }

    private static Slot matchDefine(String line, int lineNo) {
        Matcher m = DEFINE.matcher(line);
        if (!m.matches()) return null;
        String value = normalize(m.group(2));
        if (value.isEmpty()) return null;
        return new Slot(m.group(1), value, lineNo);
    }

    private static Slot matchConfigKey(String line, int lineNo) {
        Matcher m = CONFIG_KEY.matcher(line);
        if (!m.matches()) return null;
        String value = normalize(m.group(2));
        if (value.isEmpty()) return null;
        return new Slot(m.group(1), value, lineNo);
    }

    /**
     * Normalize a value so two copies of the same constant compare equal.
     *
     * <p>★ THE TRAILING SEMICOLON IS NOT PART OF THE VALUE ★
     * {@code int MAX = 3;} and {@code MAX = 3} declare the same value; and
     * the whitespace inside is not part of it either.
     */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String v = raw.trim();
        while (v.endsWith(";")) v = v.substring(0, v.length() - 1).trim();
        v = v.replaceAll("\\s+", " ").trim();
        // a trailing comment is not part of the value
        int hash = v.indexOf(" //");
        if (hash >= 0) v = v.substring(0, hash).trim();
        return v;
    }
}
