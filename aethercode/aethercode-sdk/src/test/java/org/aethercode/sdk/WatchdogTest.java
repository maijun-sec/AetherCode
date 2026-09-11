package org.aethercode.sdk;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * unit tests for {@link Watchdog} — the silent-run
 * detector that fires after a configurable timeout.
 */
class WatchdogTest {

    @Test
    void construct_rejectsNullSupplier() {
        assertThrows(IllegalArgumentException.class,
                () -> new Watchdog(null, silenceMs -> {}));
    }

    @Test
    void construct_rejectsNullHandler() {
        assertThrows(IllegalArgumentException.class,
                () -> new Watchdog(System::currentTimeMillis, null));
    }

    @Test
    void construct_rejectsPollLessThan100() {
        assertThrows(IllegalArgumentException.class,
                () -> new Watchdog(System::currentTimeMillis, silenceMs -> {}, 50, 1000));
    }

    @Test
    void construct_rejectsTimeoutLessThanPoll() {
        assertThrows(IllegalArgumentException.class,
                () -> new Watchdog(System::currentTimeMillis, silenceMs -> {}, 1000, 500));
    }

    @Test
    void isTripped_startsFalse() {
        try (Watchdog wd = new Watchdog(System::currentTimeMillis, silenceMs -> {},
                200L, 1_000L)) {
            assertFalse(wd.isTripped());
        }
    }

    @Test
    void kick_doesNotThrow() {
        try (Watchdog wd = new Watchdog(System::currentTimeMillis, silenceMs -> {},
                200L, 1_000L)) {
            assertDoesNotThrow(wd::kick);
        }
    }

    @Test
    void close_isIdempotent() {
        Watchdog wd = new Watchdog(System::currentTimeMillis, silenceMs -> {},
                200L, 1_000L);
        wd.close();
        assertDoesNotThrow(wd::close);
    }

    @Test
    void start_isIdempotent() {
        try (Watchdog wd = new Watchdog(System::currentTimeMillis, silenceMs -> {},
                200L, 1_000L)) {
            wd.start();
            assertDoesNotThrow(wd::start); // second start is a no-op
        }
    }

    @Test
    void stop_beforeStart_isNoOp() {
        try (Watchdog wd = new Watchdog(System::currentTimeMillis, silenceMs -> {},
                200L, 1_000L)) {
            assertDoesNotThrow(wd::stop);
        }
    }

    @Test
    void defaultConstants_areReasonable() {
        assertEquals(5_000L, Watchdog.DEFAULT_POLL_MS);
        assertEquals(60_000L, Watchdog.DEFAULT_TIMEOUT_MS);
    }
}
