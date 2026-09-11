package org.aethercode.tools.vision;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-process deterministic VLM used by the test suite and as a safe default when no VLM key is
 * available.
 *
 * <p>Two strategies are applied to the call path:</p>
 * <ol>
 *   <li>If the path matches a registered fixture (see {@link #register(String, String)}), the
 *       fixture description is returned verbatim;</li>
 *   <li>Otherwise, the first few bytes of the file are read, base64'd, and stitched into a canned
 *       description — enough to let the agent run end-to-end without a real model.</li>
 * </ol>
 *
 * <p>The mock also records every call, so tests can assert that the agent passes the right
 * parameters.</p>
 */
public class MockVlmClient implements VlmClient {

    private static final Logger LOG = LoggerFactory.getLogger(MockVlmClient.class);

    private final Map<String, String> fixtures = new LinkedHashMap<>();
    private final Map<String, String> videoFixtures = new LinkedHashMap<>();
    private final Map<String, String> audioFixtures = new LinkedHashMap<>();
    private final java.util.List<CallRecord> calls = new java.util.ArrayList<>();
    private final java.util.List<VideoCallRecord> videoCalls = new java.util.ArrayList<>();
    private final java.util.List<AudioCallRecord> audioCalls = new java.util.ArrayList<>();
    private final AtomicLong totalCalls = new AtomicLong(0L);

    /** Record of a single image tool call; exposed for tests. */
    public record CallRecord(String imagePath, String prompt, Options options) {}

    /** Record of a single video tool call. */
    public record VideoCallRecord(String videoPath, String prompt, Options options) {}

    /** Record of a single audio tool call. */
    public record AudioCallRecord(String audioPath, String prompt, Options options) {}

    public MockVlmClient() {}

    /**
     * Register a deterministic description for the given image path. Subsequent
     * {@link #understand} calls for the same path return this string.
     */
    public MockVlmClient register(String imagePath, String description) {
        fixtures.put(Objects.requireNonNull(imagePath, "imagePath"),
                Objects.requireNonNull(description, "description"));
        return this;
    }

    /** Register a deterministic description for the given video path. {@link #understandVideo} on
     * the same path returns this string. */
    public MockVlmClient registerVideo(String videoPath, String description) {
        videoFixtures.put(Objects.requireNonNull(videoPath, "videoPath"),
                Objects.requireNonNull(description, "description"));
        return this;
    }

    /** Register a deterministic description for the given audio path. {@link #understandAudio} on
     * the same path returns this string. */
    public MockVlmClient registerAudio(String audioPath, String description) {
        audioFixtures.put(Objects.requireNonNull(audioPath, "audioPath"),
                Objects.requireNonNull(description, "description"));
        return this;
    }

    /** Read-only access to the call log, in chronological order. */
    public java.util.List<CallRecord> calls() {
        return java.util.List.copyOf(calls);
    }

    public java.util.List<VideoCallRecord> videoCalls() {
        return java.util.List.copyOf(videoCalls);
    }

    public java.util.List<AudioCallRecord> audioCalls() {
        return java.util.List.copyOf(audioCalls);
    }

    public long totalCalls() { return totalCalls.get(); }

    @Override
    public String understand(String imagePath, String prompt, Options opts) throws Exception {
        Objects.requireNonNull(imagePath, "imagePath");
        Objects.requireNonNull(prompt, "prompt");
        calls.add(new CallRecord(imagePath, prompt, opts));
        totalCalls.incrementAndGet();
        // 1. Registered fixture takes priority.
        Optional<String> fixture = Optional.ofNullable(fixtures.get(imagePath));
        if (fixture.isPresent()) {
            LOG.debug("mock VLM: fixture hit for {}", imagePath);
            return fixture.get();
        }
        // 2. Verify the file is readable. We do not parse the bytes (no model to call), but a
        // missing file must surface as an explicit error so callers can distinguish "no key" from
        // "bad path".
        Path p = Path.of(imagePath);
        if (!Files.exists(p)) {
            throw new IOException("image not found: " + imagePath);
        }
        // 3. Fixed template plus a hash of the first 64 bytes, so two distinct images do not end
        // up with identical descriptions (downstream caching and dedup rely on this).
        byte[] head = new byte[64];
        try (var in = Files.newInputStream(p)) {
            int n = in.read(head);
            if (n > 0) {
                byte[] trimmed = new byte[n];
                System.arraycopy(head, 0, trimmed, 0, n);
                return "mock VLM description of " + imagePath
                        + " (prompt=\"" + prompt + "\", size="
                        + Files.size(p) + "B, head=" + bytesToHex(trimmed).substring(0, Math.min(16, n * 2)) + ")";
            }
        }
        return "mock VLM description of " + imagePath
                + " (prompt=\"" + prompt + "\", size=" + Files.size(p) + "B)";
    }

    @Override
    public String understand(String imagePath, String prompt) throws Exception {
        return understand(imagePath, prompt, Options.defaultOptions());
    }

    @Override
    public String understandVideo(String videoPath, String prompt) throws Exception {
        return understandVideo(videoPath, prompt, Options.defaultOptions());
    }

    @Override
    public String understandVideo(String videoPath, String prompt, Options opts) throws Exception {
        Objects.requireNonNull(videoPath, "videoPath");
        Objects.requireNonNull(prompt, "prompt");
        videoCalls.add(new VideoCallRecord(videoPath, prompt, opts));
        totalCalls.incrementAndGet();
        Optional<String> fixture = Optional.ofNullable(videoFixtures.get(videoPath));
        if (fixture.isPresent()) {
            return fixture.get();
        }
        Path p = Path.of(videoPath);
        if (!Files.exists(p)) {
            throw new IOException("video not found: " + videoPath);
        }
        return "mock VLM video description of " + videoPath
                + " (prompt=\"" + prompt + "\", size=" + Files.size(p) + "B)";
    }

    @Override
    public String understandAudio(String audioPath, String prompt) throws Exception {
        return understandAudio(audioPath, prompt, Options.defaultOptions());
    }

    @Override
    public String understandAudio(String audioPath, String prompt, Options opts) throws Exception {
        Objects.requireNonNull(audioPath, "audioPath");
        Objects.requireNonNull(prompt, "prompt");
        audioCalls.add(new AudioCallRecord(audioPath, prompt, opts));
        totalCalls.incrementAndGet();
        Optional<String> fixture = Optional.ofNullable(audioFixtures.get(audioPath));
        if (fixture.isPresent()) {
            return fixture.get();
        }
        Path p = Path.of(audioPath);
        if (!Files.exists(p)) {
            throw new IOException("audio not found: " + audioPath);
        }
        return "mock VLM audio transcript of " + audioPath
                + " (prompt=\"" + prompt + "\", size=" + Files.size(p) + "B)";
    }

    private static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }
}
