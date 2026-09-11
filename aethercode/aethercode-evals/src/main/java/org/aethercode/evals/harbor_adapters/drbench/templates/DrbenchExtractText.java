package org.aethercode.evals.harbor_adapters.drbench.templates;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Convert a DRBench corpus document to plain text on stdout.
 *
 * <p>Installed in the task image as {@code extract-text}. DRBench's
 * corpus is PDF, DOCX, XLSX, PPTX, and JSONL mailbox exports; the
 * benchmark scores research and synthesis rather than container-format
 * parsing, so the task provides the same extraction path upstream's
 * own agent uses instead of leaving the agent to reverse-engineer
 * OOXML.</p>
 *
 * <p>Java 21 port of {@code harbor_adapters.drbench.templates.extract_text}.</p>
 */
public final class DrbenchExtractText {

    /** Bounds the text handed back for one document so a single
     * pathological file cannot flood the agent's context or the trial log. */
    public static final int MAX_OUTPUT_CHARS = 400_000;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, Function<Path, String>> HANDLERS = Map.of(
            ".pdf", p -> DrbenchExtractText.fromPdf(p),
            ".docx", p -> DrbenchExtractText.fromDocx(p),
            ".xlsx", p -> DrbenchExtractText.fromXlsx(p),
            ".pptx", p -> DrbenchExtractText.fromPptx(p),
            ".jsonl", p -> {
                try {
                    return DrbenchExtractText.fromJsonl(p);
                } catch (IOException ex) {
                    throw new UncheckedIoException(ex);
                }
            });

    private DrbenchExtractText() {}

    /** Default PDF handler stub. */
    public static String fromPdf(Path path) {
        // The Python source uses `pypdf.PdfReader`. The Java port is
        // intentionally a no-op here: there is no PDF library on the
        // classpath by design (matches the rest of the deepagents-evals
        // module, which has no document-format dependencies). Callers
        // that need a working PDF extractor should plug in Apache PDFBox
        // (TODO).
        return "[PDF text extraction not implemented in the Java port: " + path + "]";
    }

    /** Default DOCX handler stub. */
    public static String fromDocx(Path path) {
        return "[DOCX text extraction not implemented in the Java port: " + path + "]";
    }

    /** Default XLSX handler stub. */
    public static String fromXlsx(Path path) {
        return "[XLSX text extraction not implemented in the Java port: " + path + "]";
    }

    /** Default PPTX handler stub. */
    public static String fromPptx(Path path) {
        return "[PPTX text extraction not implemented in the Java port: " + path + "]";
    }

    /**
     * Render a JSONL mailbox or chat export as readable messages.
     *
     * <p>The corpus uses one tagged-union format for both Roundcube
     * mailboxes and Mattermost exports: {@code email} and {@code post}
     * records carry the content, while {@code team}, {@code channel},
     * and {@code user} records describe who and what they belong to.
     * Directory records are rendered too, since a message only makes
     * sense with the channel and people it references.</p>
     */
    public static String fromJsonl(Path path) throws IOException {
        List<String> blocks = new ArrayList<>();
        List<String> people = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            Map<String, Object> record;
            try {
                record = MAPPER.readValue(trimmed, new TypeReference<Map<String, Object>>() {});
            } catch (IOException ex) {
                blocks.add(trimmed);
                continue;
            }
            Object kindObj = record.get("type");
            String kind = kindObj == null ? "" : kindObj.toString();
            switch (kind) {
                case "email" -> blocks.add(renderEmail(record));
                case "post" -> {
                    Object postObj = record.get("post");
                    if (postObj instanceof Map<?, ?> post) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> postMap = (Map<String, Object>) post;
                        blocks.add(renderPost(postMap));
                    }
                }
                case "team", "channel" -> {
                    Object containerObj = record.get(kind);
                    if (containerObj instanceof Map<?, ?> container) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> containerMap = (Map<String, Object>) container;
                        blocks.add(renderContainer(kind, containerMap));
                    }
                }
                case "user" -> people.add(renderUser(record));
                case "version" -> { /* skip */ }
                default -> {
                    // Unknown record type: keep the raw JSON rather than dropping content.
                    try {
                        blocks.add(MAPPER.writeValueAsString(record));
                    } catch (IOException ex) {
                        blocks.add(String.valueOf(record));
                    }
                }
            }
        }
        if (!people.isEmpty()) {
            StringBuilder dir = new StringBuilder("Directory:\n");
            for (String person : people) {
                dir.append("- ").append(person).append('\n');
            }
            blocks.add(0, dir.toString());
        }
        return String.join("\n\n---\n\n", blocks);
    }

    /** Render a Roundcube mailbox record. */
    public static String renderEmail(Map<String, Object> record) {
        Map<String, Object> r = record;
        Object sender = r.getOrDefault("from", "");
        Object fromName = r.get("from_name");
        if (fromName != null && !String.valueOf(fromName).isEmpty()) {
            sender = fromName + " <" + sender + ">";
        }
        List<String[]> fields = new ArrayList<>();
        fields.add(new String[]{"Subject", String.valueOf(r.getOrDefault("subject", ""))});
        fields.add(new String[]{"From", String.valueOf(sender)});
        fields.add(new String[]{"To", recipients(r.get("to"))});
        fields.add(new String[]{"Cc", recipients(r.get("cc"))});
        fields.add(new String[]{"Date", String.valueOf(r.getOrDefault("date", ""))});
        fields.add(new String[]{"Folder", String.valueOf(r.getOrDefault("folder", ""))});
        StringBuilder header = new StringBuilder();
        for (String[] f : fields) {
            if (!f[1].isEmpty()) {
                header.append(f[0]).append(": ").append(f[1]).append('\n');
            }
        }
        Object attachments = r.get("attachments");
        if (attachments != null) {
            header.append("Attachments: ").append(recipients(attachments)).append('\n');
        }
        header.append('\n');
        header.append(String.valueOf(r.getOrDefault("body", "")));
        return header.toString();
    }

    /** Render a Mattermost post. */
    public static String renderPost(Map<String, Object> post) {
        StringBuilder header = new StringBuilder();
        for (String[] f : new String[][]{
                {"Team", String.valueOf(post.getOrDefault("team", ""))},
                {"Channel", String.valueOf(post.getOrDefault("channel", ""))},
                {"User", String.valueOf(post.getOrDefault("user", ""))}}) {
            if (!f[1].isEmpty()) {
                header.append(f[0]).append(": ").append(f[1]).append('\n');
            }
        }
        Object created = post.get("create_at");
        if (created instanceof Number n) {
            // Mattermost stamps posts in milliseconds since the epoch.
            Instant stamp = Instant.ofEpochMilli(n.longValue());
            String formatted = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneOffset.UTC).format(stamp);
            header.append("Date: ").append(formatted).append('\n');
        }
        header.append('\n');
        header.append(String.valueOf(post.getOrDefault("message", "")));
        return header.toString();
    }

    /** Render a Mattermost team or channel definition. */
    public static String renderContainer(String kind, Map<String, Object> container) {
        Object name = container.get("display_name");
        if (name == null || String.valueOf(name).isEmpty()) {
            name = container.getOrDefault("name", "");
        }
        StringBuilder header = new StringBuilder();
        header.append(kind).append(": ").append(name).append('\n');
        for (String[] f : new String[][]{
                {"Team", "team"},
                {"Purpose", "purpose"},
                {"Header", "header"}}) {
            Object value = container.get(f[1]);
            if (value != null && !String.valueOf(value).isEmpty()) {
                header.append(f[0]).append(": ").append(value).append('\n');
            }
        }
        return header.toString();
    }

    /** Return {@code path} as plain text, dispatching on its suffix. */
    public static String extract(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new java.io.FileNotFoundException("not a file: " + path);
        }
        Function<Path, String> handler = HANDLERS.get(extension(path).toLowerCase(Locale.ROOT));
        String text;
        if (handler == null) {
            switch (extension(path).toLowerCase(Locale.ROOT)) {
                case ".txt", ".md", ".csv", ".json" -> text = Files.readString(path, StandardCharsets.UTF_8);
                default -> throw new IllegalArgumentException(
                        "unsupported file type " + extension(path) + "; supported: " + HANDLERS.keySet());
            }
        } else {
            try {
                text = handler.apply(path);
            } catch (UncheckedIoException ex) {
                throw (IOException) ex.getCause();
            }
        }
        if (text.length() > MAX_OUTPUT_CHARS) {
            text = text.substring(0, MAX_OUTPUT_CHARS)
                    + "\n\n[truncated at " + MAX_OUTPUT_CHARS + " characters]";
        }
        return text;
    }

    /**
     * Print each named document as text. Returns a process exit code.
     */
    public static int main(String[] argv) {
        if (argv.length == 0) {
            System.err.println("usage: extract-text <file> [<file> ...]");
            return 2;
        }
        int status = 0;
        for (String name : argv) {
            String text;
            try {
                text = extract(Path.of(name));
            } catch (IOException ex) {
                System.err.println("extract-text: " + ex.getMessage());
                status = 1;
                continue;
            } catch (RuntimeException ex) {
                System.err.println("extract-text: failed to read " + name
                        + ": " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                status = 1;
                continue;
            }
            if (argv.length > 1) {
                System.out.println("===== " + name + " =====");
            }
            System.out.println(text);
        }
        return status;
    }

    /* ----------------------------- helpers ----------------------------- */

    private static String recipients(Object value) {
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(String.valueOf(item));
            }
            return String.join(", ", out);
        }
        return value == null ? "" : value.toString();
    }

    private static String renderUser(Map<String, Object> record) {
        Map<String, Object> user = record;
        if (user.get("user") instanceof Map<?, ?> nested) {
            @SuppressWarnings("unchecked")
            Map<String, Object> nestedMap = (Map<String, Object>) nested;
            user = nestedMap;
        }
        String first = String.valueOf(user.getOrDefault("first_name", ""));
        String last = String.valueOf(user.getOrDefault("last_name", ""));
        StringBuilder name = new StringBuilder();
        if (!first.isEmpty()) {
            name.append(first);
        }
        if (!last.isEmpty()) {
            if (name.length() > 0) {
                name.append(' ');
            }
            name.append(last);
        }
        String handle = String.valueOf(user.getOrDefault("username", ""));
        String email = String.valueOf(user.getOrDefault("email", ""));
        Map<String, String> parts = new LinkedHashMap<>();
        if (name.length() > 0) {
            parts.put("name", name.toString());
        }
        if (!handle.isEmpty()) {
            parts.put("handle", "(" + handle + ")");
        }
        if (!email.isEmpty()) {
            parts.put("email", email);
        }
        return String.join(" ", parts.values());
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }

    /** Bridge unchecked IO from inside a {@link Function}. */
    private static final class UncheckedIoException extends RuntimeException {
        UncheckedIoException(IOException cause) {
            super(cause);
        }
    }
}
