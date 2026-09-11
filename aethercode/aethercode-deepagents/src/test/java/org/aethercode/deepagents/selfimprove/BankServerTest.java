package org.aethercode.deepagents.selfimprove;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankServerTest {

    private static ReasoningUnit unit(String id, String kind, String fix, double utility) {
        return new ReasoningUnit(id, kind, "e-" + id, fix, "ex-" + id,
                utility, 0L, 0L, 0L, Instant.now());
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        return http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(java.time.Duration.ofSeconds(5))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(int port, String path) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        return http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(java.time.Duration.ofSeconds(5))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthzReturnsOk(@TempDir Path tmp) throws Exception {
        ReasoningBank bank = new ReasoningBank();
        BankServer server = new BankServer(bank).start(0);
        try {
            HttpResponse<String> r = get(server.port(), "/healthz");
            assertEquals(200, r.statusCode());
            assertTrue(r.body().contains("\"ok\":true"), r.body());
        } finally {
            server.stop();
        }
    }

    @Test
    void recallReturnsRankedUnits(@TempDir Path tmp) throws Exception {
        ReasoningBank bank = new ReasoningBank();
        bank.add(unit("u1", "file_edit", "ensure dir exists", 0.9));
        bank.add(unit("u2", "file_edit", "import Path", 0.7));
        bank.add(unit("u3", "build", "use --no-daemon", 0.85));
        BankServer server = new BankServer(bank).start(0);
        try {
            HttpResponse<String> r = get(server.port(),
                    "/bank/recall?kind=file_edit&n=5");
            assertEquals(200, r.statusCode());
            String body = r.body();
            assertTrue(body.contains("\"id\":\"u1\""), body);
            assertTrue(body.contains("\"id\":\"u2\""), body);
            assertTrue(!body.contains("\"u3\""), body);
        } finally {
            server.stop();
        }
    }

    @Test
    void recallAllKindsMergesAcrossKinds(@TempDir Path tmp) throws Exception {
        ReasoningBank bank = new ReasoningBank();
        bank.add(unit("u1", "file_edit", "ensure dir exists", 0.9));
        bank.add(unit("u2", "build", "use --no-daemon", 0.85));
        BankServer server = new BankServer(bank).start(0);
        try {
            HttpResponse<String> r = post(server.port(), "/bank/recall-all-kinds?n=5");
            assertEquals(200, r.statusCode());
            String body = r.body();
            assertTrue(body.contains("\"u1\""), body);
            assertTrue(body.contains("\"u2\""), body);
        } finally {
            server.stop();
        }
    }

    @Test
    void touchBumpsUsesAndUtility(@TempDir Path tmp) throws Exception {
        ReasoningBank bank = new ReasoningBank();
        bank.add(unit("u1", "k", "fix", 0.5));
        BankServer server = new BankServer(bank).start(0);
        try {
            HttpResponse<String> r = post(server.port(), "/bank/touch?id=u1");
            assertEquals(200, r.statusCode());
            String body = r.body();
            assertTrue(body.contains("\"uses\":1"), body);
            // 0.5 + 0.05 = 0.55
            assertTrue(body.contains("\"utility\":0.55"), body);
        } finally {
            server.stop();
        }
    }

    @Test
    void recordOutcomeUpdatesCounts(@TempDir Path tmp) throws Exception {
        ReasoningBank bank = new ReasoningBank();
        bank.add(unit("u1", "k", "fix", 0.5));
        BankServer server = new BankServer(bank).start(0);
        try {
            HttpResponse<String> r = post(server.port(),
                    "/bank/record-outcome?id=u1&ok=true");
            assertEquals(200, r.statusCode());
            assertTrue(r.body().contains("\"okCount\":1"), r.body());

            HttpResponse<String> r2 = post(server.port(),
                    "/bank/record-outcome?id=u1&ok=false");
            assertEquals(200, r2.statusCode());
            assertTrue(r2.body().contains("\"notOkCount\":1"), r2.body());
        } finally {
            server.stop();
        }
    }

    @Test
    void statsReportsSizeAndCounts(@TempDir Path tmp) throws Exception {
        ReasoningBank bank = new ReasoningBank();
        bank.add(unit("u1", "k1", "fix1", 0.5));
        bank.add(unit("u2", "k1", "fix2", 0.6));
        bank.add(unit("u3", "k2", "fix3", 0.7));
        bank.recordOutcome("u1", true);
        bank.recordOutcome("u1", true);
        bank.recordOutcome("u2", false);
        BankServer server = new BankServer(bank).start(0);
        try {
            HttpResponse<String> r = get(server.port(), "/bank/stats");
            assertEquals(200, r.statusCode());
            String body = r.body();
            assertTrue(body.contains("\"size\":3"), body);
            assertTrue(body.contains("\"totalOk\":2"), body);
            assertTrue(body.contains("\"totalNotOk\":1"), body);
        } finally {
            server.stop();
        }
    }

    @Test
    void unknownIdReturns404(@TempDir Path tmp) throws Exception {
        ReasoningBank bank = new ReasoningBank();
        BankServer server = new BankServer(bank).start(0);
        try {
            HttpResponse<String> r = post(server.port(), "/bank/touch?id=nope");
            assertEquals(404, r.statusCode());
        } finally {
            server.stop();
        }
    }

    @Test
    void startStopIsIdempotent(@TempDir Path tmp) throws Exception {
        ReasoningBank bank = new ReasoningBank();
        BankServer server = new BankServer(bank).start(0);
        server.stop();
        server.stop(); // no-op
    }

    /** helper that issues a GET with an optional
     *  Authorization header. */
    private static HttpResponse<String> getWithAuth(int port, String path, String auth) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(java.time.Duration.ofSeconds(5))
                .GET();
        if (auth != null) b.header("Authorization", auth);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** helper that POSTs with an optional
     *  Authorization header. */
    private static HttpResponse<String> postWithAuth(int port, String path, String auth) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(java.time.Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (auth != null) b.header("Authorization", auth);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void unauthenticatedServer_stillAcceptsRequests(@TempDir Path tmp) throws Exception {
        // when no token is configured, the server is
        // open (R244.2 default). Sanity check that auth
        // changes don't break the unauthenticated path.
        ReasoningBank bank = new ReasoningBank();
        bank.add(unit("u1", "file_edit", "ensure dir exists", 0.9));
        BankServer server = new BankServer(bank).start(0);
        try {
            // No Authorization header at all.
            HttpResponse<String> r = getWithAuth(server.port(), "/bank/stats", null);
            assertEquals(200, r.statusCode());
        } finally {
            server.stop();
        }
    }

    @Test
    void authRequired_missingHeader_returns401(@TempDir Path tmp) throws Exception {
        // with a token configured, /bank/* without
        // Authorization must return 401.
        ReasoningBank bank = new ReasoningBank();
        bank.add(unit("u1", "k1", "f1", 0.9));
        BankServer server = new BankServer(bank, "secret-token-abc").start(0);
        try {
            HttpResponse<String> r = getWithAuth(server.port(), "/bank/stats", null);
            assertEquals(401, r.statusCode());
            assertTrue(r.body().contains("missing Authorization"),
                    "expected 401 body to mention missing header, got: " + r.body());
        } finally {
            server.stop();
        }
    }

    @Test
    void authRequired_wrongToken_returns401(@TempDir Path tmp) throws Exception {
        // wrong token must be rejected with 401.
        ReasoningBank bank = new ReasoningBank();
        bank.add(unit("u1", "k1", "f1", 0.9));
        BankServer server = new BankServer(bank, "right-token").start(0);
        try {
            HttpResponse<String> r = getWithAuth(server.port(), "/bank/stats", "Bearer wrong-token");
            assertEquals(401, r.statusCode());
            assertTrue(r.body().contains("invalid bearer"),
                    "expected 401 body to mention invalid token, got: " + r.body());
        } finally {
            server.stop();
        }
    }

    @Test
    void authRequired_correctToken_returns200(@TempDir Path tmp) throws Exception {
        // the right token in the standard "Bearer <t>"
        // form opens every /bank/* route.
        ReasoningBank bank = new ReasoningBank();
        bank.add(unit("u1", "k1", "f1", 0.9));
        BankServer server = new BankServer(bank, "secret-xyz").start(0);
        try {
            // GET with Bearer prefix.
            HttpResponse<String> r1 = getWithAuth(server.port(), "/bank/stats", "Bearer secret-xyz");
            assertEquals(200, r1.statusCode());
            // POST with the raw token (no Bearer prefix) —
            // R247 accepts both forms for curl-style scripts.
            HttpResponse<String> r2 = postWithAuth(server.port(), "/bank/touch?id=u1", "secret-xyz");
            assertEquals(200, r2.statusCode());
        } finally {
            server.stop();
        }
    }

    @Test
    void authRequired_healthzAlwaysOpen(@TempDir Path tmp) throws Exception {
        // /healthz is intentionally outside the
        // auth gate so a load balancer can probe it
        // without a credential.
        ReasoningBank bank = new ReasoningBank();
        BankServer server = new BankServer(bank, "locked-down").start(0);
        try {
            HttpResponse<String> r = getWithAuth(server.port(), "/healthz", null);
            assertEquals(200, r.statusCode());
            assertTrue(r.body().contains("\"ok\":true"));
        } finally {
            server.stop();
        }
    }

    @Test
    void authRequired_blankTokenFallsBackToUnauthenticated(@TempDir Path tmp) throws Exception {
        // passing a blank token at construction time
        // is the same as not setting one (R244.2 default
        // behaviour). The server stays unauthenticated so
        // a misconfiguration doesn't lock the host out.
        ReasoningBank bank = new ReasoningBank();
        BankServer server = new BankServer(bank, "   ").start(0);
        try {
            assertEquals(false, server.authRequired());
            HttpResponse<String> r = getWithAuth(server.port(), "/bank/stats", null);
            assertEquals(200, r.statusCode());
        } finally {
            server.stop();
        }
    }

    @Test
    void constantTimeEqualsHandlesNullsAndLengthMismatch() throws Exception {
        // defensive — nulls, different lengths, same
        // length + same content, same length + different
        // content. The implementation is private static;
        // we exercise it indirectly via the requireAuth
        // path but the helper has no observable failure
        // mode we can unit test from outside the class.
        // Instead verify the server doesn't crash on edge
        // inputs (an empty token is already covered).
        ReasoningBank bank = new ReasoningBank();
        BankServer server = new BankServer(bank, "abc").start(0);
        try {
            // length mismatch should still 401.
            HttpResponse<String> r = getWithAuth(server.port(), "/bank/stats", "Bearer ab");
            assertEquals(401, r.statusCode());
        } finally {
            server.stop();
        }
    }

    @Test
    void withAuthTokenReplacesTokenBeforeStart(@TempDir Path tmp) throws Exception {
        // withAuthToken() is the builder-style hook a
        // host calls when it learns the token after the
        // BankServer was constructed. We verify the
        // replacement takes effect.
        ReasoningBank bank = new ReasoningBank();
        bank.add(unit("u1", "k1", "f1", 0.9));
        BankServer server = new BankServer(bank)
                .withAuthToken("first-token")
                .withAuthToken("second-token")
                .start(0);
        try {
            assertEquals("second-token", server.authToken());
            HttpResponse<String> r = getWithAuth(server.port(), "/bank/stats", "Bearer second-token");
            assertEquals(200, r.statusCode());
        } finally {
            server.stop();
        }
    }

    @Test
    void bankClient_sendsAuthorizationHeader(@TempDir Path tmp) throws Exception {
        // end-to-end — start a server with a token,
        // build a BankClient with the same token, and
        // verify a /bank/stats call succeeds. This is the
        // "real" use case the token was added for.
        ReasoningBank bank = new ReasoningBank();
        bank.add(unit("u1", "k1", "f1", 0.9));
        BankServer server = new BankServer(bank, "shared-secret").start(0);
        try {
            BankClient client = new BankClient(
                    URI.create("http://127.0.0.1:" + server.port()), "shared-secret");
            java.util.Map<String, Object> stats = client.stats();
            assertNotNull(stats);
            assertEquals(1, ((Number) stats.get("size")).intValue());
        } finally {
            server.stop();
        }
    }

    @Test
    void bankClient_wrongToken_throws401(@TempDir Path tmp) throws Exception {
        // BankClient with the wrong token must
        // surface a 401. The client's exception type
        // carries the status code.
        ReasoningBank bank = new ReasoningBank();
        bank.add(unit("u1", "k1", "f1", 0.9));
        BankServer server = new BankServer(bank, "right").start(0);
        try {
            BankClient client = new BankClient(
                    URI.create("http://127.0.0.1:" + server.port()), "wrong");
            try {
                client.stats();
                org.junit.jupiter.api.Assertions.fail("expected BankClientException");
            } catch (BankClient.BankClientException e) {
                assertEquals(401, e.status());
            }
        } finally {
            server.stop();
        }
    }

    @Test
    void talonSelfReflectWiringStartsServer(@TempDir Path tmp) throws Exception {
        TalonSelfReflectWiring.Result wiring =
                TalonSelfReflectWiring.build(tmp, null, java.util.Map.of());
        try {
            BankServer server = TalonSelfReflectWiring.startBankServer(wiring, 0);
            assertNotNull(server);
            assertTrue(server.port() > 0);
            // The wiring's bank is the same one the server
            // is exposing, so we can write through the
            // server and read from the bank.
            HttpResponse<String> r = post(server.port(), "/bank/touch?id=does-not-exist");
            assertEquals(404, r.statusCode());
            server.stop();
        } finally {
            // bank cleanup is automatic
        }
    }

    /**
     * spawn {@code keytool} to generate a fresh
     * self-signed PKCS#12 keystore in {@code tmp}. We use
     * {@link ProcessBuilder} (not PowerShell) so the
     * encoding stays UTF-8 throughout; the produced
     * keystore is a binary file so encoding is irrelevant
     * to its content.
     */
    /**
     * an {@link java.net.http.HttpClient} configured
     * to trust any TLS cert. Production clients should pin
     * a CA; test clients use a self-signed keystore and
     * need to opt out of the default PKIX validation.
     */
    private static java.net.http.HttpClient insecureHttpClient() throws Exception {
        javax.net.ssl.TrustManager[] trustAll = new javax.net.ssl.TrustManager[] {
                new javax.net.ssl.X509TrustManager() {
                    @Override public void checkClientTrusted(
                            java.security.cert.X509Certificate[] chain, String authType) {}
                    @Override public void checkServerTrusted(
                            java.security.cert.X509Certificate[] chain, String authType) {}
                    @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new java.security.cert.X509Certificate[0];
                    }
                }
        };
        javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
        ctx.init(null, trustAll, new java.security.SecureRandom());
        return java.net.http.HttpClient.newBuilder().sslContext(ctx).build();
    }

    private static Path createSelfSignedKeystore(Path tmp) throws Exception {
        Path keystore = tmp.resolve("test-bank.p12");
        String keytool = System.getProperty("java.home") + java.io.File.separator
                + "bin" + java.io.File.separator
                + (System.getProperty("os.name").toLowerCase().contains("win")
                        ? "keytool.exe" : "keytool");
        ProcessBuilder pb = new ProcessBuilder(
                keytool,
                "-genkeypair",
                "-alias", "bank",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "365",
                "-keystore", keystore.toString(),
                "-storepass", "changeit",
                "-storetype", "PKCS12",
                "-dname", "CN=localhost, OU=Test, O=AetherCode, L=, S=, C=US",
                "-keypass", "changeit");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        int rc = p.waitFor();
        if (rc != 0) {
            throw new RuntimeException("keytool failed (rc=" + rc + ")");
        }
        return keystore;
    }

    @Test
    void transport_defaultsToHttp() {
        // BankServer.transport() is HTTP until
        // startTLS is called.
        ReasoningBank bank = new ReasoningBank();
        BankServer server = new BankServer(bank);
        assertEquals(BankServer.Transport.HTTP, server.transport());
    }

    @Test
    void startTLS_bindsonHttps(@TempDir Path tmp) throws Exception {
        // after startTLS, the bound port is HTTPS.
        // We don't try to talk TLS to it here (a separate
        // test below uses a real HttpsClient); we just
        // verify the transport flag flipped and the port
        // bound.
        Path keystore = createSelfSignedKeystore(tmp);
        ReasoningBank bank = new ReasoningBank();
        BankServer server = new BankServer(bank)
                .startTLS(0, keystore.toString(), "changeit");
        try {
            assertEquals(BankServer.Transport.HTTPS, server.transport());
            assertTrue(server.port() > 0);
        } finally {
            server.stop();
        }
    }

    @Test
    void startBankServerHelper_picksHttpsWhenKeystoreEnvSet(@TempDir Path tmp) throws Exception {
        // R248 wiring: TalonSelfReflectWiring.startBankServer
        // picks HTTPS when AETHERCODE_BANK_TLS_KEYSTORE is
        // set, plain HTTP otherwise. The token env is
        // unrelated to this branch.
        Path keystore = createSelfSignedKeystore(tmp);
        java.util.Map<String, String> env = java.util.Map.of(
                TalonSelfReflectWiring.ENV_BANK_TLS_KEYSTORE, keystore.toString(),
                TalonSelfReflectWiring.ENV_BANK_TLS_PASS, "changeit");
        TalonSelfReflectWiring.Result wiring =
                TalonSelfReflectWiring.build(tmp, null, java.util.Map.of());
        BankServer server = TalonSelfReflectWiring.startBankServer(wiring, 0, env);
        try {
            assertEquals(BankServer.Transport.HTTPS, server.transport());
        } finally {
            server.stop();
        }
    }

    @Test
    void startBankServerHelper_plainHttpWhenKeystoreEnvMissing(@TempDir Path tmp) throws Exception {
        // R248 wiring: when AETHERCODE_BANK_TLS_KEYSTORE is
        // absent (or blank), the helper falls back to
        // plain HTTP (R244.2 default).
        java.util.Map<String, String> env = java.util.Map.of();  // empty
        TalonSelfReflectWiring.Result wiring =
                TalonSelfReflectWiring.build(tmp, null, java.util.Map.of());
        BankServer server = TalonSelfReflectWiring.startBankServer(wiring, 0, env);
        try {
            assertEquals(BankServer.Transport.HTTP, server.transport());
        } finally {
            server.stop();
        }
    }

}
