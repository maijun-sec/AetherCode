package org.aethercode.sdk;

import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * contract test for {@link
 * AetherCodeEngine#setSessionManager}. The setter
 * was added so the daemon can install a manager
 * post-construction (relaxed the field from
 * {@code final} to {@code volatile}).
 */
class AetherCodeEngineSessionManagerTest {

    private static AetherCodeEngine engineFor(Path cwd) {
        return new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .build();
    }

    @Test
    void sessionManagerIsNullByDefault(@TempDir Path cwd) {
        AetherCodeEngine engine = engineFor(cwd);
        assertThat(engine.sessionManager())
                .as("engines built without a sessionManager must return null")
                .isNull();
    }

    @Test
    void setSessionManagerInstallsManager(@TempDir Path cwd) {
        AetherCodeEngine engine = engineFor(cwd);
        SessionManager.EngineFactory factory = sessionId -> engineFor(cwd);
        SessionManager sm = new SessionManager(factory);

        engine.setSessionManager(sm);
        assertThat(engine.sessionManager())
                .as("setSessionManager must persist via the accessor")
                .isSameAs(sm);
    }

    @Test
    void setSessionManagerReplacesExistingManager(@TempDir Path cwd) {
        AetherCodeEngine engine = engineFor(cwd);
        SessionManager.EngineFactory factory = sessionId -> engineFor(cwd);
        SessionManager first = new SessionManager(factory);
        SessionManager second = new SessionManager(factory);

        engine.setSessionManager(first);
        assertThat(engine.sessionManager()).isSameAs(first);
        engine.setSessionManager(second);
        assertThat(engine.sessionManager())
                .as("the setter replaces the prior binding")
                .isSameAs(second);
    }

    @Test
    void setSessionManagerNullClears(@TempDir Path cwd) {
        AetherCodeEngine engine = engineFor(cwd);
        SessionManager.EngineFactory factory = sessionId -> engineFor(cwd);
        SessionManager sm = new SessionManager(factory);

        engine.setSessionManager(sm);
        engine.setSessionManager(null);
        assertThat(engine.sessionManager())
                .as("setSessionManager(null) clears the binding")
                .isNull();
    }

    @Test
    void sessionManagerAccessorSurvivesAcrossReads(@TempDir Path cwd) {
        // Volatile reads must be consistent
        // (happens-before) so a reader that picks
        // up a value sees all the writes that
        // preceded it. Verify by reading twice
        // after a single setter call.
        AetherCodeEngine engine = engineFor(cwd);
        SessionManager.EngineFactory factory = sessionId -> engineFor(cwd);
        SessionManager sm = new SessionManager(factory);

        engine.setSessionManager(sm);
        SessionManager r1 = engine.sessionManager();
        SessionManager r2 = engine.sessionManager();
        assertThat(r1).isSameAs(sm);
        assertThat(r2).isSameAs(sm);
    }
}
