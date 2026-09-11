package org.aethercode.tasks.supervisor;

import java.util.random.RandomGenerator;

/**
 * prior round (T-301): 8-char base36 child ids with a {@code c-} prefix
 * (c for child). Mirrors {@link org.aethercode.tasks.Task#generateId}
 * so the wire form is consistent: the user can see {@code c-3a7f9b2c}
 * in a log and know which layer owns the row.
 */
final class ChildIds {
    private ChildIds() {}

    private static final char[] ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();

    static String newId() {
        return newId(RandomGenerator.getDefault());
    }

    static String newId(RandomGenerator rng) {
        StringBuilder sb = new StringBuilder(10);
        sb.append("c-");
        for (int i = 0; i < 8; i++) {
            sb.append(ALPHABET[rng.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
