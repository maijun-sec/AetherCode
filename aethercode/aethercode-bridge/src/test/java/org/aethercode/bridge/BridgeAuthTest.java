package org.aethercode.bridge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BridgeAuthTest {

    @Test
    void authFlowProceedsThroughExpectedStates() {
        BridgeAuth auth = new BridgeAuth();
        auth.setToken("secret-token");
        assertThat(auth.state()).isEqualTo(BridgeAuth.State.HELLO_SENT);
        auth.onChallenge("nonce-123");
        assertThat(auth.state()).isEqualTo(BridgeAuth.State.CHALLENGE_RECEIVED);
        var resp = auth.authFrame();
        assertThat(auth.state()).isEqualTo(BridgeAuth.State.AUTH_SENT);
        assertThat(resp).containsKey("response");
        assertThat((String) resp.get("response")).isNotBlank();
        auth.onAuthOk();
        assertThat(auth.isAuthed()).isTrue();
    }

    @Test
    void emptyTokenStillProducesValidChallenge() {
        BridgeAuth auth = new BridgeAuth();
        auth.setToken(null);
        auth.onChallenge("n");
        var resp = auth.authFrame();
        assertThat(resp).containsKey("response");
    }

    @Test
    void authFailedResetsAndAllowsRetry() {
        BridgeAuth auth = new BridgeAuth();
        auth.setToken("t");
        auth.onChallenge("n");
        auth.authFrame();
        auth.onAuthFailed();
        assertThat(auth.state()).isEqualTo(BridgeAuth.State.AUTH_FAILED);
        auth.reset();
        assertThat(auth.state()).isEqualTo(BridgeAuth.State.HELLO_SENT);
    }
}
