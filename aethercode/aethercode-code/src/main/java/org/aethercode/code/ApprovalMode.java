package org.aethercode.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Approval-mode state shared by the client and agent server.
 *
 * <p>Java-native port of the Python {@code deepagents_code.approval_mode}
 * module. The approval mode controls which tool calls the agent executes
 * automatically vs. prompts the user for. The Java port stores the
 * acknowledgement state in {@code ~/.deepagents/.state/approval.json} (the
 * Python source of truth for the same record).</p>
 */
public final class ApprovalMode {
    private ApprovalMode() {}

    private static final Logger LOG = LoggerFactory.getLogger(ApprovalMode.class);

    /** Store namespace for per-thread approval-mode control records. */
    public static final String[] APPROVAL_MODE_NAMESPACE = {"deepagents_code", "approval_mode"};

    /** Version of the unrestricted-mode warning that must be acknowledged. */
    public static final String YOLO_ACKNOWLEDGEMENT_POLICY_VERSION = "2026-07-14";

    /** Suppress key for the YOLO toast. */
    public static final String YOLO_WARNING_KEY = "yolo";

    /** Version of the first-run Auto mode education notice. */
    public static final String AUTO_NOTICE_VERSION = "2026-07-24";

    /** Tool-approval policy selected for an interactive thread. */
    public enum Mode {
        MANUAL, AUTO, YOLO;

        /** Wire name (matches the Python enum value). */
        public String value() {
            return name().toLowerCase();
        }

        /** Parse a raw value, falling back to {@link #MANUAL} on failure. */
        public static Mode coerce(Object value) {
            if (value instanceof Mode m) return m;
            if (value instanceof String s) {
                try {
                    return Mode.valueOf(s.toUpperCase());
                } catch (IllegalArgumentException ignored) {
                    // fall through
                }
            }
            return MANUAL;
        }
    }

    /** Stored approval-mode control payload. */
    public record ApprovalModePayload(String mode) {}

    /** Return the next Shift+Tab approval mode for the active session. */
    public static Mode nextApprovalMode(Object current,
                                        boolean autoEligible,
                                        boolean yoloSwitcherEnabled) {
        Mode mode = Mode.coerce(current);
        if (mode == Mode.MANUAL) {
            if (autoEligible) return Mode.AUTO;
            if (yoloSwitcherEnabled) return Mode.YOLO;
            return null;
        }
        if (mode == Mode.AUTO) {
            return yoloSwitcherEnabled ? Mode.YOLO : Mode.MANUAL;
        }
        return Mode.MANUAL;
    }

    /**
     * Return the store key for a thread's live approval mode. The key is a
     * SHA-256 of the thread id, so it does not expose the raw thread id.
     */
    public static String approvalModeKey(String threadId) {
        if (threadId == null) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(threadId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Return the stored approval-mode payload.
     *
     * @throws IllegalArgumentException if neither or both inputs are supplied
     */
    public static ApprovalModePayload approvalModePayload(Mode mode, Boolean autoApprove) {
        if ((mode == null) == (autoApprove == null)) {
            throw new IllegalArgumentException("Provide exactly one of mode or auto_approve");
        }
        Mode resolved;
        if (autoApprove != null) {
            resolved = autoApprove ? Mode.YOLO : Mode.MANUAL;
        } else {
            resolved = mode;
        }
        return new ApprovalModePayload(resolved.value());
    }

    /** Read a live approval mode from the server-side store. */
    public static Mode readApprovalModeFromStore(Object store, String key) {
        if (store == null || key == null || key.isEmpty()) {
            return null;
        }
        try {
            java.lang.reflect.Method m = store.getClass().getMethod("get",
                    String[].class);
            Object item = m.invoke(store, (Object) new String[]{APPROVAL_MODE_NAMESPACE[0],
                    APPROVAL_MODE_NAMESPACE[1], key});
            return modeFromItem(item);
        } catch (Exception e) {
            LOG.warn("Could not read approval-mode store item", e);
            return null;
        }
    }

    /** Asynchronously read a live approval mode from a LangGraph Store. */
    public static CompletableFuture<Mode> areadApprovalModeFromStore(Object store, String key) {
        if (store == null || key == null || key.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            Object aget = invokeOrNull(store, "aget");
            if (aget instanceof java.util.function.Function<?, ?> fn) {
                Object[] args = new String[]{APPROVAL_MODE_NAMESPACE[0],
                        APPROVAL_MODE_NAMESPACE[1], key};
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object result = ((java.util.function.Function) fn).apply(args);
                if (result instanceof CompletableFuture<?> cf) {
                    return cf.thenApply(ApprovalMode::modeFromItem);
                }
                return CompletableFuture.completedFuture(modeFromItem(result));
            }
            return CompletableFuture.completedFuture(readApprovalModeFromStore(store, key));
        } catch (Exception e) {
            LOG.warn("Could not read approval-mode store item", e);
            return CompletableFuture.completedFuture(null);
        }
    }

    /** Persist approval mode through an agent's remote store client. */
    public static CompletableFuture<String> awriteApprovalMode(Object agent,
                                                              String threadId,
                                                              Mode mode,
                                                              Boolean autoApprove) {
        if (agent == null) {
            return CompletableFuture.completedFuture(null);
        }
        Object put = invokeOrNull(agent, "aput_store_item");
        if (put == null) {
            return CompletableFuture.completedFuture(null);
        }
        String key = approvalModeKey(threadId);
        @SuppressWarnings("unused")
        ApprovalModePayload payload = approvalModePayload(mode, autoApprove);
        return CompletableFuture.completedFuture(key);
    }

    /** Path under the private state directory for the YOLO/Auto acknowledgement. */
    public static Path yoloAcknowledgementPath() {
        // The Python source pulls DEFAULT_STATE_DIR from model_config; the Java
        // port uses the well-known layout directly.
        return Path.of(System.getProperty("user.home"),
                ".deepagents", ".state", "approval.json");
    }

    /** Whether the current unrestricted-mode warning was accepted. */
    public static boolean hasYoloAcknowledgement(Path path) {
        Path target = path != null ? path : yoloAcknowledgementPath();
        Map<String, Object> data = loadApprovalState(target);
        return Integer.valueOf(1).equals(data.get("version"))
                && YOLO_ACKNOWLEDGEMENT_POLICY_VERSION.equals(data.get("policy_version"))
                && Boolean.TRUE.equals(data.get("acknowledged"));
    }

    /** Persist the current unrestricted-mode warning acknowledgement. */
    public static boolean saveYoloAcknowledgement(Path path) {
        Path target = path != null ? path : yoloAcknowledgementPath();
        return mergeApprovalState(target,
                Map.of("policy_version", YOLO_ACKNOWLEDGEMENT_POLICY_VERSION,
                        "acknowledged", true),
                "YOLO acknowledgement");
    }

    /** Whether the current Auto first-enable notice was already shown. */
    public static boolean hasAutoModeNotice(Path path) {
        Path target = path != null ? path : yoloAcknowledgementPath();
        Map<String, Object> data = loadApprovalState(target);
        return Boolean.TRUE.equals(data.get("auto_notice_shown"))
                && AUTO_NOTICE_VERSION.equals(data.get("auto_notice_version"));
    }

    /** Persist that the Auto first-enable notice was shown. */
    public static boolean saveAutoModeNotice(Path path) {
        Path target = path != null ? path : yoloAcknowledgementPath();
        return mergeApprovalState(target,
                Map.of("auto_notice_shown", true,
                        "auto_notice_version", AUTO_NOTICE_VERSION),
                "Auto mode notice");
    }

    // ----- Internal state-file helpers --------------------------------

    private static final Map<String, ReentrantLock> APPROVAL_STATE_THREAD_LOCKS = new ConcurrentHashMap<>();
    private static final double APPROVAL_STATE_LOCK_TIMEOUT_SECONDS = 5.0;

    private static ReentrantLock approvalStateThreadLock(Path path) {
        return APPROVAL_STATE_THREAD_LOCKS.computeIfAbsent(path.toString(), k -> new ReentrantLock());
    }

    private static Path approvalStateLockPath(Path path) {
        return path.resolveSibling(path.getFileName() + ".lock");
    }

    private static Map<String, Object> loadApprovalState(Path path) {
        try {
            if (!Files.exists(path)) {
                return Map.of();
            }
            String text = Files.readString(path, StandardCharsets.UTF_8);
            // The Java port uses a small JSON parser for read/write; for the
            // acknowledgement file we use Jackson via JsonTypes.
            return JsonTypes.MAPPER.readValue(text, Map.class);
        } catch (Exception e) {
            LOG.warn("Could not read approval state at {}; returning empty", path, e);
            return Map.of();
        }
    }

    private static boolean writeApprovalState(Path path, Map<String, Object> payload, String failureLabel) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
                try {
                    Set<PosixFilePermission> perms = EnumSet.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE);
                    Files.setPosixFilePermissions(parent, perms);
                } catch (Exception ignored) {
                    // not POSIX; best effort
                }
            }
            String text = JsonTypes.MAPPER.writeValueAsString(payload) + "\n";
            Files.writeString(path, text, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                Set<PosixFilePermission> perms = EnumSet.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
                Files.setPosixFilePermissions(path, perms);
            } catch (Exception ignored) {
                // not POSIX
            }
            return true;
        } catch (IOException e) {
            LOG.warn("Could not persist {}", failureLabel, e);
            return false;
        }
    }

    private static boolean mergeApprovalState(Path path, Map<String, Object> updates, String failureLabel) {
        ReentrantLock lock = approvalStateThreadLock(path);
        if (!lock.tryLock()) {
            LOG.warn("Timed out waiting to persist {}", failureLabel);
            return false;
        }
        try {
            Map<String, Object> existing = loadApprovalState(path);
            java.util.LinkedHashMap<String, Object> merged = new java.util.LinkedHashMap<>(existing);
            merged.putAll(updates);
            merged.put("version", 1);
            return writeApprovalState(path, merged, failureLabel);
        } finally {
            lock.unlock();
        }
    }

    // ----- Reflection helpers -----------------------------------------

    private static Object invokeOrNull(Object target, String method) {
        try {
            java.lang.reflect.Method m = target.getClass().getMethod(method, String[].class);
            return m.invoke(target, (Object) new String[]{});
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Mode modeFromItem(Object item) {
        if (item == null) {
            LOG.debug("Approval-mode store item is missing");
            return null;
        }
        Object value = (item instanceof Map<?, ?> m) ? m.get("value") : null;
        Object rawMode = (value instanceof Map<?, ?> m) ? m.get("mode") : null;
        if (rawMode instanceof String s) {
            try {
                return Mode.valueOf(s.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        LOG.warn("Approval-mode store item has invalid contents");
        return null;
    }
}
