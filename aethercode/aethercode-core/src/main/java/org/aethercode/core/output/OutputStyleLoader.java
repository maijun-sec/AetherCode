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

package org.aethercode.core.output;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * load custom {@link OutputStyle}s from a directory of {@code .md} files.
 * Modelled on the TS {@code outputStyles/loadOutputStylesDir.ts}.
 *
 * <p>Each file is read as a single string and registered under the
 * file's name (without the {@code .md} extension). The body becomes the
 * style's system-prompt suffix.
 *
 * <p>Format:
 * <pre>
 *   # terse (optional title — the file name is the id)
 *
 *   Reply with the absolute minimum. No prose. No greetings. ...
 * </pre>
 *
 * <p>Existing styles with the same id are overwritten; built-in styles
 * (DEFAULT, TERSE, EXPLANATORY, JSON) are not affected unless the user's
 * file has the same id, in which case it wins.
 */
public final class OutputStyleLoader {

    private static final Logger LOG = LoggerFactory.getLogger(OutputStyleLoader.class);

    private final Path dir;

    public OutputStyleLoader(Path dir) { this.dir = dir; }

    public Path dir() { return dir; }

    /** load all .md files as output styles. Missing dir returns empty. */
    public List<OutputStyle> loadAll() {
        if (dir == null || !Files.isDirectory(dir)) return List.of();
        List<OutputStyle> out = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path p : (Iterable<Path>) stream.filter(f -> f.toString().endsWith(".md"))::iterator) {
                try {
                    String body = Files.readString(p);
                    String id = stripExt(p.getFileName().toString());
                    if (id.isBlank() || body.isBlank()) continue;
                    OutputStyle s = OutputStyle.register(id, body.trim());
                    out.add(s);
                } catch (IOException e) {
                    LOG.warn("skip output-style file {}: {}", p, e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.debug("list output-style dir failed: {}", e.getMessage());
        }
        return out;
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
