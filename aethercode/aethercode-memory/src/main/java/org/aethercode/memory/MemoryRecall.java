package org.aethercode.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.stream.StreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Lightweight "which memory files matter right now" selector. Modelled after the TS
 * {@code memdir/findRelevantMemories.ts}.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Scan the memory directory for {@code *.md} files (excluding the entrypoint itself)</li>
 *   <li>Read each file's first 5 lines as a "manifest" (filename + short description)</li>
 *   <li>Score each candidate by a cheap lexical match against the current user input
 *       and recent tool activity</li>
 *   <li>Keep at most {@link #MAX_RECALL} files; defer to a side-query LLM call to pick
 *       the final top N if there are too many candidates</li>
 *   <li>Read the chosen files and return their full text</li>
 * </ol>
 *
 * <p>The scorer is intentionally tiny — no embeddings, no vector store. It works because
 * memory files are small, the entrypoint already filters aggressively, and the model is
 * strong enough to make sense of the manifest.
 */
public class MemoryRecall {

    private static final Logger LOG = LoggerFactory.getLogger(MemoryRecall.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Max files recalled per turn. Matches the TS constant. */
    public static final int MAX_RECALL = 5;
    /** Soft cap on characters per file read into the prompt. */
    public static final int MAX_FILE_CHARS = 8_000;
    /** Header lines to read for the manifest. */
    public static final int MANIFEST_HEADER_LINES = 5;
    /** Threshold above which we ask the side-query LLM to disambiguate. */
    public static final int CANDIDATE_THRESHOLD = MAX_RECALL * 3;

    private final ChatClient sideClient; // nullable — falls back to lexical only

    public MemoryRecall(ChatClient sideClient) {
        this.sideClient = sideClient;
    }

    /** Convenience constructor for tests / callers that don't have a side client. */
    public MemoryRecall() { this(null); }

    public static final class RecalledFile {
        public final Path path;
        public final String name;
        public final String manifest;       // first 5 lines, used for display
        public final String content;        // full text up to MAX_FILE_CHARS
        public final double score;
        public RecalledFile(Path path, String name, String manifest, String content, double score) {
            this.path = path; this.name = name; this.manifest = manifest;
            this.content = content; this.score = score;
        }
    }

    /**
     * Choose up to {@link #MAX_RECALL} memory files relevant to the current turn.
     *
     * @param memoryDir     the memory directory (user / project / local)
     * @param currentInput  the user's current input
     * @param recentTools   names of tools the user has used recently
     * @param alreadySurfaced paths already injected this session, so we don't re-inject
     */
    public List<RecalledFile> recall(Path memoryDir, String currentInput,
                                    List<String> recentTools, List<Path> alreadySurfaced) {
        if (memoryDir == null || !Files.isDirectory(memoryDir)) return List.of();
        List<Candidate> candidates = scan(memoryDir, alreadySurfaced);
        if (candidates.isEmpty()) return List.of();
        candidates.forEach(c -> c.score = score(c, currentInput, recentTools));
        candidates.sort(Comparator.comparingDouble((Candidate c) -> c.score).reversed());

        if (candidates.size() <= MAX_RECALL) {
            return candidates.stream().map(MemoryRecall::load).toList();
        }
        if (candidates.size() <= CANDIDATE_THRESHOLD || sideClient == null) {
            return candidates.subList(0, MAX_RECALL).stream().map(MemoryRecall::load).toList();
        }
        // Disambiguate via side query.
        List<String> chosen = disambiguate(candidates);
        if (chosen.isEmpty()) {
            return candidates.subList(0, MAX_RECALL).stream().map(MemoryRecall::load).toList();
        }
        List<RecalledFile> out = new ArrayList<>();
        for (String name : chosen) {
            candidates.stream().filter(c -> c.name.equals(name)).findFirst()
                    .ifPresent(c -> out.add(load(c)));
        }
        return out;
    }

    private List<Candidate> scan(Path memoryDir, List<Path> alreadySurfaced) {
        List<Candidate> out = new ArrayList<>();
        try (Stream<Path> stream = Files.list(memoryDir)) {
            Iterable<Path> it = stream::iterator;
            for (Path p : (Iterable<Path>) () -> stream.iterator()) {
                String fn = p.getFileName().toString();
                if (!fn.endsWith(".md")) continue;
                if (fn.equals(MemoryPaths.ENTRYPOINT_NAME)) continue;
                if (alreadySurfaced != null && alreadySurfaced.contains(p.toAbsolutePath())) continue;
                String manifest = readManifest(p);
                out.add(new Candidate(p, fn, manifest, 0.0));
            }
        } catch (IOException e) {
            LOG.warn("memory scan failed: {}", e.getMessage());
        }
        return out;
    }

    private static String readManifest(Path p) {
        try {
            List<String> lines = Files.readAllLines(p);
            int n = Math.min(MANIFEST_HEADER_LINES, lines.size());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) sb.append(lines.get(i)).append('\n');
            return sb.toString().trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static double score(Candidate c, String currentInput, List<String> recentTools) {
        double s = 0.0;
        if (currentInput == null) currentInput = "";
        String lowerInput = currentInput.toLowerCase();
        String lowerName = c.name.toLowerCase();
        // filename match
        for (String tok : lowerInput.split("\\W+")) {
            if (tok.length() < 3) continue;
            if (lowerName.contains(tok)) s += 2.0;
        }
        // manifest match
        String lowerManifest = c.manifest.toLowerCase();
        for (String tok : lowerInput.split("\\W+")) {
            if (tok.length() < 3) continue;
            if (lowerManifest.contains(tok)) s += 1.0;
        }
        // tool match — tool names appearing in the manifest are a strong signal
        if (recentTools != null) {
            for (String t : recentTools) {
                if (lowerManifest.contains(t.toLowerCase())) s += 1.5;
            }
        }
        // prefer recently modified (mtime is in the candidate — added lazily)
        try {
            double ageDays = (System.currentTimeMillis() - Files.getLastModifiedTime(c.path).toMillis()) / 86_400_000.0;
            s += Math.max(0, 1.0 - ageDays / 30.0); // decay over 30 days
        } catch (IOException ignored) {}
        return s;
    }

    private List<String> disambiguate(List<Candidate> candidates) {
        // Side query: ask the LLM to pick the most relevant files given the current input.
        // Falls back to "first 5 by score" if the side query times out.
        StringBuilder prompt = new StringBuilder();
        prompt.append("Pick the 5 most relevant memory files for the question below. ");
        prompt.append("Return a JSON object: {\"files\": [\"name1.md\", \"name2.md\", ...]}. ");
        prompt.append("Only include files that are actually relevant. If none are relevant, return {\"files\": []}.\n\n");
        prompt.append("Question: ").append("<see manifest below>").append("\n\nFiles:\n");
        for (Candidate c : candidates) {
            prompt.append("- ").append(c.name).append(" — ").append(c.manifest.replace("\n", " ")).append('\n');
        }
        List<Message> req = new ArrayList<>();
        req.add(Message.userText(prompt.toString()));
        StringBuilder out = new StringBuilder();
        try (Stream<StreamEvent> stream = sideClient.stream(req,
                "You rank memory files. Return strict JSON.", List.of())) {
            for (java.util.Iterator<StreamEvent> it = stream.iterator(); it.hasNext(); ) {
                StreamEvent ev = it.next();
                if (ev instanceof StreamEvent.TextDelta td) out.append(td.text());
                else if (ev instanceof StreamEvent.RunEnd) break;
            }
        } catch (Exception e) {
            LOG.warn("side query failed: {}", e.getMessage());
            return List.of();
        }
        try {
            JsonNode root = MAPPER.readTree(out.toString());
            JsonNode arr = root.path("files");
            if (!arr.isArray()) return List.of();
            List<String> picked = new ArrayList<>();
            arr.forEach(n -> picked.add(n.asText()));
            return picked;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static RecalledFile load(Candidate c) {
        String content;
        try {
            content = Files.readString(c.path);
        } catch (IOException e) {
            content = c.manifest;
        }
        if (content.length() > MAX_FILE_CHARS) {
            content = content.substring(0, MAX_FILE_CHARS) + "\n… (truncated)";
        }
        return new RecalledFile(c.path, c.name, c.manifest, content, c.score);
    }

    /** Format the recall as a system-prompt section the model can read. */
    public static String render(List<RecalledFile> files) {
        if (files == null || files.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("# Relevant memories (auto-recalled)\n");
        for (RecalledFile f : files) {
            sb.append("\n## ").append(f.name).append("\n\n");
            sb.append(f.content).append("\n");
        }
        return sb.toString();
    }

    private static final class Candidate {
        final Path path;
        final String name;
        final String manifest;
        double score;
        Candidate(Path p, String n, String m, double s) { path = p; name = n; manifest = m; score = s; }
    }
}
