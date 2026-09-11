package org.aethercode.tasks.engine.checkpoint;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * T-1-10: zstd-compress state blobs when they exceed
 * {@link #compressionThresholdBytes()} (256 KB); fall back to
 * {@link java.util.zip.Deflater} when zstd is not on the classpath.
 *
 * <p>The codec prepends a 1-byte {@link CompressionAlgo} tag so
 * {@link #decompress(byte[])} can route to the right backend
 * without an out-of-band hint.
 *
 * <h2>zstd discovery</h2>
 * <p>zstd is loaded by reflection ({@code com.github.luben.zstd.Zstd})
 * so the dependency is optional. The aethercode-cli module pulls
 * in {@code com.github.luben:zstd-jni}; we don't add it to
 * deepagents-tasks directly. If the class is present we use it;
 * otherwise we silently fall back to {@link Deflater}.
 *
 * <p>Deflate is fine for blobs in the 256 KB – 8 MB range, which
 * covers the typical session state. The compression ratio is
 * slightly worse than zstd (~25 % vs ~40 %), but Deflate is part
 * of the JDK and never refuses to load.
 */
public final class CompressionUtil {

    public static final int COMPRESSION_THRESHOLD_BYTES = 256 * 1024;

    /** Compression algorithm tag at the head of the compressed blob. */
    public enum CompressionAlgo {
        NONE((byte) 0),
        DEFLATE((byte) 1),
        ZSTD((byte) 2);

        private final byte tag;
        CompressionAlgo(byte tag) { this.tag = tag; }
        public byte tag() { return tag; }

        public static CompressionAlgo fromTag(byte tag) {
            for (CompressionAlgo a : values()) if (a.tag == tag) return a;
            throw new IllegalArgumentException("unknown compression tag: " + tag);
        }
    }

    private static final Class<?> ZSTD_CLASS;
    private static final Method ZSTD_COMPRESS;
    private static final Method ZSTD_DECOMPRESS;

    static {
        Class<?> cls = null;
        Method compress = null;
        Method decompress = null;
        try {
            cls = Class.forName("com.github.luben.zstd.Zstd");
            compress  = cls.getMethod("compress", byte[].class, int.class);
            // Older API: byte[] Zstd.decompress(byte[] src) — heap-return.
            // Newer API: byte[] Zstd.decompress(byte[] src, int originalSize).
            try {
                decompress = cls.getMethod("decompress", byte[].class, int.class);
            } catch (NoSuchMethodException nsme) {
                try {
                    decompress = cls.getMethod("decompress", byte[].class);
                } catch (NoSuchMethodException nsme2) {
                    decompress = null;
                }
            }
        } catch (Throwable t) {
            // zstd is optional; not on the classpath is the common case.
            cls = null; compress = null; decompress = null;
        }
        ZSTD_CLASS = cls;
        ZSTD_COMPRESS = compress;
        ZSTD_DECOMPRESS = decompress;
    }

    private CompressionUtil() { }

    /** True iff zstd is on the classpath and usable. */
    public static boolean isZstdAvailable() { return ZSTD_CLASS != null; }

    /** Return the default compression threshold (256 KB). */
    public static int compressionThresholdBytes() { return COMPRESSION_THRESHOLD_BYTES; }

    /**
     * Compress {@code raw} if its size exceeds the threshold; else
     * return it verbatim with a {@link CompressionAlgo#NONE} tag.
     * For blobs &gt; 256 KB the choice between zstd and Deflate is
     * made at class load (see {@link #isZstdAvailable()}).
     */
    public static byte[] compress(byte[] raw) {
        if (raw == null) throw new IllegalArgumentException("raw == null");
        if (raw.length < COMPRESSION_THRESHOLD_BYTES) {
            return withTag(CompressionAlgo.NONE, raw);
        }
        CompressionAlgo algo = isZstdAvailable() ? CompressionAlgo.ZSTD : CompressionAlgo.DEFLATE;
        return withTag(algo, doCompress(raw, algo));
    }

    /**
     * Always compress, ignoring the threshold. Used by tests that
     * want to force a compressed path with a small payload.
     */
    public static byte[] forceCompress(byte[] raw, CompressionAlgo algo) {
        if (raw == null) throw new IllegalArgumentException("raw == null");
        return withTag(algo == CompressionAlgo.NONE ? CompressionAlgo.DEFLATE : algo, doCompress(raw, algo));
    }

    /**
     * Decompress a tagged blob. Returns the original bytes regardless
     * of which algorithm produced the payload.
     */
    public static byte[] decompress(byte[] tagged) {
        if (tagged == null || tagged.length == 0) {
            throw new IllegalArgumentException("tagged blob is empty");
        }
        CompressionAlgo algo = CompressionAlgo.fromTag(tagged[0]);
        byte[] body = Arrays.copyOfRange(tagged, 1, tagged.length);
        return switch (algo) {
            case NONE   -> body;
            case DEFLATE -> inflate(body);
            case ZSTD    -> zstdDecompress(body);
        };
    }

    /**
     * Returns {@code true} iff this blob is tagged as compressed
     * (i.e. {@code tagged[0] != 0}).
     */
    public static boolean isCompressed(byte[] tagged) {
        return tagged != null && tagged.length > 0 && tagged[0] != CompressionAlgo.NONE.tag();
    }

    // --- internals -----------------------------------------------------

    private static byte[] withTag(CompressionAlgo algo, byte[] body) {
        byte[] out = new byte[body.length + 1];
        out[0] = algo.tag();
        System.arraycopy(body, 0, out, 1, body.length);
        return out;
    }

    private static byte[] doCompress(byte[] raw, CompressionAlgo algo) {
        return switch (algo) {
            case DEFLATE -> deflate(raw);
            case ZSTD    -> zstdCompress(raw);
            case NONE    -> throw new IllegalStateException("NONE has no compress path");
        };
    }

    private static byte[] deflate(byte[] raw) {
        // Best-speed is fine for checkpoint snapshots; the workload
        // is "small text + binary state, take ~25 % off". The
        // difference between best-speed and best-compression on a
        // 1 MB blob is ~50 ms vs ~150 ms.
        Deflater def = new Deflater(Deflater.BEST_SPEED);
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(raw.length / 2);
             DeflaterOutputStream dos = new DeflaterOutputStream(baos, def)) {
            dos.write(raw);
            dos.finish();
            return baos.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        } finally {
            def.end();
        }
    }

    private static byte[] inflate(byte[] body) {
        Inflater inf = new Inflater();
        try (InflaterInputStream iis = new InflaterInputStream(
                new ByteArrayInputStream(body), inf);
             ByteArrayOutputStream out = new ByteArrayOutputStream(body.length * 3)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = iis.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (IOException e) {
            // The InflaterInputStream surfaces DataFormatException
            // as a ZipException (subclass of IOException), so we
            // can just catch IOException here.
            throw new IllegalStateException("inflate failed: " + e.getMessage(), e);
        } finally {
            inf.end();
        }
    }

    private static byte[] zstdCompress(byte[] raw) {
        try {
            // The reflective method is: byte[] Zstd.compress(byte[] src, int level)
            // level=3 is the default (balanced).
            Object out = ZSTD_COMPRESS.invoke(null, raw, 3);
            return (byte[]) out;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("zstd compress failed: " + e.getMessage(), e);
        }
    }

    private static byte[] zstdDecompress(byte[] body) {
        try {
            // The reflective call signature varies by zstd-jni version.
            //   - older: byte[] Zstd.decompress(byte[] src)
            //   - newer: byte[] Zstd.decompress(byte[] src, int originalSize)
            // We dispatch on parameter count, both return a fresh heap array.
            if (ZSTD_DECOMPRESS.getParameterCount() == 1) {
                return (byte[]) ZSTD_DECOMPRESS.invoke(null, body);
            } else {
                int cap = Math.max(body.length * 4, 1 << 20);
                return (byte[]) ZSTD_DECOMPRESS.invoke(null, body, cap);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("zstd decompress failed: " + e.getMessage(), e);
        }
    }
}
