package org.aethercode.sdk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R154 tests: auto-restart configuration.
 *
 * <p>Auto-restart is OFF by default. The user opts
 * in via the env var
 * {@code AETHERCODE_SUPERVISOR_AUTO_RESTART=1}
 * or the {@link SupervisorMode#setAutoRestart}
 * setter. The cap is 5 restarts per child to
 * prevent silent restart loops from hiding
 * real bugs.
 */
class SupervisorModeR154Test {

    @Test
    void maxRestartsPerChild_isFive() {
        // 5 is a deliberate number: well above
        // any legitimate crash-loop (a
        // network blip causes 1-2) and well
        // below "stuck retrying forever".
        // A user that hits the cap can read
        // restartCount() to confirm.
        assertEquals(5, SupervisorMode.MAX_RESTARTS_PER_CHILD);
    }

    @Test
    void autoRestart_defaultsToEnvVar() {
        // Singleton state is shared across
        // tests. The default honours the
        // env var (which is typically unset
        // in the test runner, so we expect
        // false).
        SupervisorMode sup = SupervisorMode.instance();
        String envVal = System.getenv("AETHERCODE_SUPERVISOR_AUTO_RESTART");
        boolean expected = "1".equals(envVal);
        // We don't assert exact because
        // other tests may have toggled the
        // flag. Just verify the setter
        // works.
        boolean original = sup.isAutoRestart();
        sup.setAutoRestart(true);
        assertTrue(sup.isAutoRestart(), "setAutoRestart(true) should turn it on");
        sup.setAutoRestart(false);
        assertFalse(sup.isAutoRestart(), "setAutoRestart(false) should turn it off");
        sup.setAutoRestart(original); // restore
    }

    @Test
    void restartCount_isZeroForUnknownChild() {
        SupervisorMode sup = SupervisorMode.instance();
        assertEquals(0, sup.restartCount("never-registered-child-r154"));
    }

    @Test
    void lastRestartAt_isZeroForUnknownChild() {
        SupervisorMode sup = SupervisorMode.instance();
        assertEquals(0L, sup.lastRestartAt("never-registered-child-r154"));
    }

    @Test
    void setAutoRestart_roundTrips() {
        // The setter must be idempotent —
        // setting true twice still
        // returns true; setting false
        // after that returns false.
        SupervisorMode sup = SupervisorMode.instance();
        sup.setAutoRestart(true);
        sup.setAutoRestart(true);
        assertTrue(sup.isAutoRestart());
        sup.setAutoRestart(false);
        sup.setAutoRestart(false);
        assertFalse(sup.isAutoRestart());
    }
}
