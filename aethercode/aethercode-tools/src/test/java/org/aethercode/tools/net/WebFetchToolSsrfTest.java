package org.aethercode.tools.net;

import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * tests for the SSRF guard. The guard is
 * implemented as a pre-flight check that resolves the
 * host and refuses private / loopback / link-local /
 * unique-local addresses. Tests cover the helper
 * directly (no network) so they don't need
 * network access.
 */
class WebFetchToolSsrfTest {

    @Test
    void publicHostPasses() {
        // example.com resolves to a public IP.
        // We can't always guarantee the network is up, so
        // we use a public IP literal to be deterministic.
        assertThat(WebFetchTool.checkSsrf(URI.create("https://1.1.1.1/"))).isNull();
    }

    @Test
    void localhostBlocked() {
        assertThat(WebFetchTool.checkSsrf(URI.create("http://localhost/admin")))
                .isNotNull().contains("localhost");
    }

    @Test
    void loopbackIpBlocked() {
        assertThat(WebFetchTool.checkSsrf(URI.create("http://127.0.0.1:8080/")))
                .isNotNull().contains("loopback");
    }

    @Test
    void private10Blocked() {
        assertThat(WebFetchTool.checkSsrf(URI.create("http://10.0.0.1/")))
                .isNotNull().contains("site-local");
    }

    @Test
    void private172Blocked() {
        // Java's isSiteLocalAddress() covers 172.16/12
        // (and 10/8, 192.168/16 — i.e. the RFC1918 ranges).
        // Our guard reports "site-local" for these.
        assertThat(WebFetchTool.checkSsrf(URI.create("http://172.16.5.5/")))
                .isNotNull().contains("site-local");
    }

    @Test
    void private192Blocked() {
        assertThat(WebFetchTool.checkSsrf(URI.create("http://192.168.1.1/")))
                .isNotNull().contains("site-local");
    }

    @Test
    void linkLocalBlocked() {
        // AWS / Azure metadata endpoint
        assertThat(WebFetchTool.checkSsrf(URI.create("http://169.254.169.254/latest/meta-data/")))
                .isNotNull().contains("link-local");
    }

    @Test
    void ipv6LoopbackBlocked() {
        assertThat(WebFetchTool.checkSsrf(URI.create("http://[::1]/")))
                .isNotNull().contains("loopback");
    }

    @Test
    void ipv6UniqueLocalBlocked() {
        // fc00::/7 isn't classified as site-local by Java's
        // isSiteLocalAddress; we use the explicit byte test.
        assertThat(WebFetchTool.checkSsrf(URI.create("http://[fc00::1]/")))
                .isNotNull();
    }

    @Test
    void ipv6LinkLocalBlocked() {
        assertThat(WebFetchTool.checkSsrf(URI.create("http://[fe80::1]/")))
                .isNotNull();
    }

    @Test
    void unknownHostRefused() {
        // A bogus TLD that shouldn't resolve. The guard
        // refuses unresolvable hosts because a real URL
        // from a real fetch should always resolve.
        assertThat(WebFetchTool.checkSsrf(URI.create("http://nonexistent-host-9d8f7d6f.example/")))
                .isNotNull();
    }

    @Test
    void missingHostRefused() {
        // URI with empty host. The guard's host check fires.
        assertThat(WebFetchTool.checkSsrf(URI.create("http:///path")))
                .isNotNull();
    }

    @Test
    void callToolReturnsErrorForLocalhost() {
        // The end-to-end path: calling the tool with a
        // localhost URL returns an SSRF error.
        Tool.ToolResult r = WebFetchTool.call(
                Map.of("url", "http://127.0.0.1:9999/secret"),
                Tool.CallContext.of("test"));
        assertThat(r.isError()).isTrue();
        assertThat(r.output().toString()).contains("SSRF");
    }
}
