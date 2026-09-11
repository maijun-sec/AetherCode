package org.aethercode.core.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * a JSONL structured-event logger. Modelled on the TS
 * {@code services/log/}. One {@link Map} per call, one JSON line per
 * entry, append-only, with auto-flush.
 *
 * <p>The logger is thread-safe via a synchronized block on the writer
 * reference; writes are atomic at the line level so a process crash
 * never produces a half-line.
 */
public final class JsonlLogger implements java.io.Closeable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BufferedWriter writer;
    private final Path file;
    private final boolean ownsFile;
    private long lineCount = 0;

    public JsonlLogger(Path file) throws IOException {
        this(file, true);
    }

    public JsonlLogger(Path file, boolean append) throws IOException {
        Files.createDirectories(file.getParent());
        StandardOpenOption[] opts = append
                ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING};
        this.writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, opts);
        this.file = file;
        this.ownsFile = true;
    }

    /** log a single event. Keys are written in insertion order for stable diffs. */
    public synchronized void log(Map<String, Object> event) throws IOException {
        if (event == null) return;
        Map<String, Object> withTs = new LinkedHashMap<>();
        withTs.put("ts", System.currentTimeMillis());
        withTs.putAll(event);
        String json = MAPPER.writeValueAsString(withTs);
        writer.write(json);
        writer.newLine();
        writer.flush();
        lineCount++;
    }

    public synchronized long lineCount() { return lineCount; }
    public Path file() { return file; }

    @Override
    public synchronized void close() throws IOException {
        if (ownsFile && writer != null) writer.close();
    }
}
