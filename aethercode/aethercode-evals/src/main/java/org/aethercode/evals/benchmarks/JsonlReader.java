package org.aethercode.evals.benchmarks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Streams a JSONL file one row at a time, parsing each line as a
 * Jackson {@link JsonNode}. Used by the benchmark adapters to read
 * converted (Python repr → JSON) data files.
 * <p>
 * This is a one-pass forward-only iterator. For random access, use
 * {@link #readAll(Path)}.
 */
public final class JsonlReader implements Iterator<JsonNode>, Iterable<JsonNode> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Iterator<String> lines;
    private JsonNode next;

    private JsonlReader(Path path) {
        Objects.requireNonNull(path, "path");
        List<String> all;
        try {
            all = Files.readAllLines(path);
        } catch (IOException e) {
            throw new RuntimeException("failed to read " + path, e);
        }
        this.lines = all.iterator();
        advance();
    }

    /** Read all rows from a JSONL file at once. */
    public static List<JsonNode> readAll(Path path) {
        JsonlReader r = new JsonlReader(path);
        List<JsonNode> out = new ArrayList<>();
        while (r.hasNext()) {
            out.add(r.next());
        }
        return out;
    }

    /** Read rows lazily. */
    public static JsonlReader stream(Path path) {
        return new JsonlReader(path);
    }

    private void advance() {
        while (lines.hasNext()) {
            String line = lines.next().trim();
            if (line.isEmpty()) continue;
            try {
                next = MAPPER.readTree(line);
                return;
            } catch (IOException e) {
                // skip unparseable line
            }
        }
        next = null;
    }

    @Override
    public boolean hasNext() {
        return next != null;
    }

    @Override
    public JsonNode next() {
        if (next == null) throw new NoSuchElementException();
        JsonNode cur = next;
        advance();
        return cur;
    }

    @Override
    public Iterator<JsonNode> iterator() {
        return this;
    }
}
