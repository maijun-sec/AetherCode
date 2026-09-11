package org.aethercode.mcp.socket;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;

class SocketMcpClientTest {

    @Test
    void connectFailsCleanlyWhenNoServer() {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        } catch (IOException e) {
            return; // can't even probe
        }
        SocketMcpClient client = new SocketMcpClient("127.0.0.1", port);
        try {
            client.connect();
            assertThat(client.isConnected()).isFalse();
        } catch (Exception expected) {
            // any exception is fine; we just don't want a hang
        }
    }
}
