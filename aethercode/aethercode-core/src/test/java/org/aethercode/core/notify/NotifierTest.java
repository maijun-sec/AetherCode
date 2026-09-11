package org.aethercode.core.notify;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotifierTest {

    @Test
    void noOpReturnsFalse() {
        Notifier n = new Notifier.NoOp();
        assertThat(n.notify("t", "b", Notifier.Level.INFO)).isFalse();
    }

    @Test
    void recordingCapturesAll() {
        Notifier.Recording r = new Notifier.Recording();
        r.notify("a", "1", Notifier.Level.INFO);
        r.notify("b", "2", Notifier.Level.WARNING);
        assertThat(r.entries()).hasSize(2);
        assertThat(r.entries().get(0).title()).isEqualTo("a");
        assertThat(r.entries().get(1).level()).isEqualTo(Notifier.Level.WARNING);
    }

    @Test
    void recordingIsImmutableSnapshot() {
        Notifier.Recording r = new Notifier.Recording();
        r.notify("x", "y", Notifier.Level.ERROR);
        var snap1 = r.entries();
        r.notify("z", "w", Notifier.Level.SUCCESS);
        // snapshot taken before second call still shows just one entry
        assertThat(snap1).hasSize(1);
        assertThat(r.entries()).hasSize(2);
    }
}
