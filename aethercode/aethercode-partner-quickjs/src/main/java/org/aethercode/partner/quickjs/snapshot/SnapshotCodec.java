package org.aethercode.partner.quickjs.snapshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Patch-chain delta encoding for QuickJS REPL heap snapshots.
 *
 * <p>1:1 port of <code>langchain_quickjs._snapshot</code>. The REPL
 * snapshot is a full serialization of the heap, rewritten in its
 * entirety on every turn. Persisting it through a plain
 * {@code LastValue} channel would copy the whole payload (~1.4&nbsp;MB)
 * into every checkpoint. Empirically the heap is ~98&ndash;100% byte
 * stable between consecutive turns, so a binary diff between
 * successive snapshots is tiny.</p>
 *
 * <p>This codec stores a <em>patch chain</em> on a delta channel:
 * each turn writes one {@link SnapshotRecord} describing the delta
 * from the previous snapshot, and
 * {@link #replaySnapshotChain(byte[], List)} folds the chain back into
 * the full snapshot bytes on reconstruction.</p>
 */
public final class SnapshotCodec {

    private static final Logger LOGGER = LoggerFactory.getLogger(SnapshotCodec.class);

    /** Wire name for the full-snapshot anchor record. */
    public static final String SNAP = "snap";
    /** Wire name for the binary-patch record. */
    public static final String PATCH = "patch";
    /** Wire name for the clear record. */
    public static final String CLEAR = "clear";

    /**
     * Domain-separation prefix folded into every signed message so a
     * snapshot HMAC can never be confused with an HMAC computed over
     * some other blob using the same key. Bump the version suffix if
     * the signed-message layout ever changes.
     */
    private static final byte[] HMAC_DOMAIN = "langchain-quickjs/snapshot-hmac/v1".getBytes(StandardCharsets.UTF_8);

    private final BinaryDiffer differ;

    public SnapshotCodec() {
        this(UnsupportedBinaryDiffer.INSTANCE);
    }

    public SnapshotCodec(BinaryDiffer differ) {
        this.differ = Objects.requireNonNull(differ, "differ");
    }

    // -----------------------------------------------------------------
    //  Signing key handling
    // -----------------------------------------------------------------

    /**
     * Coerce a user-supplied signing key into raw bytes. {@code str}
     * keys are UTF-8 encoded; {@code bytes} are used verbatim. Empty
     * keys are rejected because an empty HMAC key provides no
     * integrity guarantee.
     */
    public static byte[] normalizeSigningKey(String key) {
        return normalizeSigningKey(key.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] normalizeSigningKey(byte[] key) {
        Objects.requireNonNull(key, "key");
        if (key.length == 0) {
            throw new IllegalArgumentException("`snapshot_signing_key` must be a non-empty str or bytes.");
        }
        return key.clone();
    }

    /**
     * Return the HMAC-SHA256 tag over a fully materialized snapshot.
     * The tag is computed over the <em>completed materialized</em>
     * snapshot bytes bound to {@code threadId}, so a valid snapshot for
     * one thread cannot be replayed into another by a state-store
     * adversary. This is signed before the payload is delta-encoded
     * ({@link #encodeSnapshot(byte[], byte[])}) and flushed onto the
     * patch chain; verification recomputes the tag over the
     * materialized bytes the chain replays back to.
     */
    public static byte[] signSnapshot(byte[] key, byte[] payload, String threadId) {
        return hmacSha256(key, signedMessage(payload, threadId));
    }

    /**
     * Constant-time check that {@code tag} authenticates {@code payload}
     * for {@code threadId}. Returns {@code false} for a missing/short
     * tag or any mismatch. The comparison uses
     * {@link java.security.MessageDigest#isEqual(byte[], byte[])} to
     * avoid leaking timing information about how much of the tag
     * matched.
     */
    public static boolean verifySnapshot(byte[] key, byte[] payload, String threadId, byte[] tag) {
        if (tag == null || tag.length == 0) return false;
        byte[] expected = signSnapshot(key, payload, threadId);
        return java.security.MessageDigest.isEqual(expected, tag.clone());
    }

    /**
     * Build the length-prefixed message HMAC is computed over. The
     * framing (domain, then a length-prefixed {@code threadId}, then
     * the payload) makes it impossible for two distinct
     * {@code (threadId, payload)} pairs to serialize to the same byte
     * string, so an attacker cannot shift bytes across the boundary
     * to forge a collision.
     */
    private static byte[] signedMessage(byte[] payload, String threadId) {
        byte[] tid = threadId.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[HMAC_DOMAIN.length + 8 + tid.length + payload.length];
        int off = 0;
        System.arraycopy(HMAC_DOMAIN, 0, out, off, HMAC_DOMAIN.length);
        off += HMAC_DOMAIN.length;
        long len = tid.length;
        for (int i = 7; i >= 0; i--) {
            out[off + i] = (byte) (len & 0xFF);
            len >>>= 8;
        }
        off += 8;
        System.arraycopy(tid, 0, out, off, tid.length);
        off += tid.length;
        System.arraycopy(payload, 0, out, off, payload.length);
        return out;
    }

    private static byte[] hmacSha256(byte[] key, byte[] msg) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(msg);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is required by the platform but not available", e);
        }
    }

    /** Hex-encode a tag for diagnostic logs. */
    public static String toHex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    // -----------------------------------------------------------------
    //  Channel record coercion
    // -----------------------------------------------------------------

    /**
     * Normalize a single channel write into a {@link SnapshotRecord}.
     * Accepts the canonical {@code (kind, blob)} record forms and
     * {@code null} which clears the chain. Anything else returns
     * {@code null} and is skipped by the reducer.
     */
    public static SnapshotRecord coerceRecord(Object write) {
        if (write == null) {
            return SnapshotRecord.clear();
        }
        if (write instanceof SnapshotRecord r) {
            return r;
        }
        if (write instanceof List<?> list && list.size() == 2) {
            Object kind = list.get(0);
            Object blob = list.get(1);
            if (kind instanceof String s && blob instanceof byte[] b) {
                return new SnapshotRecord(SnapshotRecord.Kind.fromWireName(s), b);
            }
            if (kind instanceof String s && blob instanceof java.io.Serializable) {
                return new SnapshotRecord(SnapshotRecord.Kind.fromWireName(s), new byte[0]);
            }
        }
        return null;
    }

    // -----------------------------------------------------------------
    //  Reducer (assoc / pure)
    // -----------------------------------------------------------------

    /**
     * Bulk reducer that replays a snapshot patch chain.
     *
     * <p>{@code state} is the fully materialized snapshot bytes
     * reconstructed so far (empty for an empty channel);
     * {@code writes} is the ordered sequence of records to fold in.
     * Returns the new materialized full snapshot bytes.</p>
     *
     * <p>Folding is left-to-right and deterministic:</p>
     * <ul>
     *   <li>{@code (SNAP, blob)} &rarr; base becomes {@code blob} (anchor).</li>
     *   <li>{@code (PATCH, blob)} &rarr; base becomes {@code differ.patch(base, blob)}.</li>
     *   <li>{@code (CLEAR, _)} &rarr; base becomes empty.</li>
     * </ul>
     *
     * <p>Associative (re-batching the writes yields the same value)
     * and pure (no I/O, randomness, or clock reads), so it is safe to
     * re-run on every reconstruction or time-travel replay.</p>
     */
    public byte[] replaySnapshotChain(byte[] state, List<Object> writes) {
        byte[] base = state == null ? new byte[0] : state.clone();
        for (Object write : writes) {
            SnapshotRecord record = coerceRecord(write);
            if (record == null) continue;
            switch (record.kind()) {
                case SNAP -> base = record.blob().clone();
                case PATCH -> base = differ.patch(base, record.blob());
                case CLEAR -> base = new byte[0];
            }
        }
        return base;
    }

    // -----------------------------------------------------------------
    //  Encoder
    // -----------------------------------------------------------------

    /**
     * Encode a fresh snapshot {@code payload} as a patch-chain record.
     *
     * <p>{@code prior} is the previous turn's fully materialized
     * snapshot bytes (empty when none exists &mdash; e.g. first turn,
     * a fork from before snapshots, or a fresh process). The delta is
     * computed statelessly against {@code prior}, so no in-process
     * cache is needed and the result is correct across forks,
     * restores, and time travel.</p>
     *
     * <p>Returns:</p>
     * <ul>
     *   <li>{@code (SNAP, payload)} when there is no usable prior, or
     *       when the bsdiff patch would not be smaller than a fresh
     *       anchor;</li>
     *   <li>{@code (PATCH, diff)} otherwise.</li>
     * </ul>
     */
    public SnapshotRecord encodeSnapshot(byte[] payload, byte[] prior) {
        byte[] safePayload = payload == null ? new byte[0] : payload;
        byte[] safePrior = prior == null ? new byte[0] : prior;
        if (safePrior.length == 0) {
            return SnapshotRecord.snap(safePayload);
        }
        byte[] patch;
        try {
            patch = differ.diff(safePrior, safePayload);
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to diff QuickJS snapshot; storing full anchor", e);
            return SnapshotRecord.snap(safePayload);
        }
        if (patch.length >= safePayload.length) {
            return SnapshotRecord.snap(safePayload);
        }
        return SnapshotRecord.patch(patch);
    }
}
