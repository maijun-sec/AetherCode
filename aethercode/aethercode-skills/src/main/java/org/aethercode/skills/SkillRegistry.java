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

package org.aethercode.skills;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Load skill files from a directory. R1: simple Markdown file with front matter is parsed
 * line-by-line; the body is the file content after the closing {@code ---}.
 */
public final class SkillRegistry {

    private SkillRegistry() {}

    public static List<Skill> loadDir(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        List<Skill> out = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (Path f : (Iterable<Path>) stream.filter(p -> p.toString().endsWith(".md"))::iterator) {
                out.add(loadOne(f));
            }
        } catch (IOException e) {
            return List.of();
        }
        return out;
    }

    private static Skill loadOne(Path f) {
        try {
            List<String> lines = Files.readAllLines(f);
            if (lines.isEmpty() || !lines.get(0).trim().equals("---")) {
                return new Skill(stripExt(f.getFileName().toString()), "", String.join("\n", lines), List.of(), Map.of());
            }
            int end = -1;
            for (int i = 1; i < lines.size(); i++) {
                if ("---".equals(lines.get(i).trim())) { end = i; break; }
            }
            if (end < 0) end = 1;
            Map<String, Object> meta = new java.util.LinkedHashMap<>();
            String name = stripExt(f.getFileName().toString());
            String description = "";
            for (int i = 1; i < end; i++) {
                String line = lines.get(i);
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String key = line.substring(0, colon).trim();
                String val = line.substring(colon + 1).trim();
                if (key.equals("name")) name = val;
                else if (key.equals("description")) description = val;
                else meta.put(key, val);
            }
            StringBuilder body = new StringBuilder();
            for (int i = end + 1; i < lines.size(); i++) {
                body.append(lines.get(i)).append('\n');
            }
            return new Skill(name, description, body.toString(), List.of(), meta);
        } catch (IOException e) {
            return new Skill(stripExt(f.getFileName().toString()), "", "", List.of(), Map.of());
        }
    }

    private static String stripExt(String s) {
        int dot = s.lastIndexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }
}
