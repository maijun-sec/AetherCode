package org.aethercode.partner.quickjs;

/**
 * REPL state persistence mode. 1:1 port of the Python
 * {@code PersistenceMode = Literal["thread", "turn", "call"]}.
 *
 * <ul>
 *   <li>{@link #THREAD} &mdash; state persists across calls and across turns.</li>
 *   <li>{@link #TURN} &mdash; state persists across calls within a turn only.</li>
 *   <li>{@link #CALL} &mdash; each eval call runs in a fresh REPL.</li>
 * </ul>
 */
public enum PersistenceMode {
    THREAD("thread"),
    TURN("turn"),
    CALL("call");

    private final String wire;

    PersistenceMode(String wire) {
        this.wire = wire;
    }

    public String wireName() {
        return wire;
    }

    public static PersistenceMode fromWire(String name) {
        if (name == null) return THREAD;
        return switch (name) {
            case "thread" -> THREAD;
            case "turn" -> TURN;
            case "call" -> CALL;
            default -> throw new IllegalArgumentException(
                    "`mode` must be one of 'thread', 'turn', or 'call'.");
        };
    }
}
