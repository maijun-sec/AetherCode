package org.aethercode.tools.net;

import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Fetch a URL and return its body. Mirrors the TS {@code WebFetchTool}.
 *
 * <p>prior round of this tool uses OkHttp + Jsoup to strip HTML. For very large pages the body is
 * truncated to 30 000 chars to keep the model's context window in check.
 *
 * <p>prior round: hardening pass. Adds:
 * <ul>
 *   <li>A User-Agent header (some sites return 403 to OkHttp's
 *       default UA). Identify as AetherCode/0.1.</li>
 *   <li>Accept header so we get HTML when both are offered</li>
 *   <li>Timeout parameter so the caller can opt out of the
 *       default 60s read timeout (e.g. for a quick health check)</li>
 *   <li>Better error messages that include the URL and status
 *       code on non-2xx responses</li>
 * </ul>
 */
public class WebFetchTool {

    public static final String NAME = "web_fetch";
    private static final Logger LOG = LoggerFactory.getLogger(WebFetchTool.class);
    private static final int MAX_BODY_CHARS = 30_000;
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(15))
            .readTimeout(Duration.ofSeconds(60))
            .build();
    /** identify ourselves so servers that block default
     *  OkHttp UAs (or worse, default Java UAs) accept us. Format
     *  matches the convention Claude Code uses. */
    private static final String USER_AGENT = "AetherCode/0.1 (https://github.com/aethercode; agent)";

    public static Tool build() {
        var props = new LinkedHashMap<String, Map<String, Object>>();
        props.put("url", Tools.stringProp("HTTP/HTTPS URL to fetch."));
        props.put("max_chars", Tools.intProp("Optional truncation cap. Default 30 000."));
        props.put("timeout_s", Tools.intProp("Optional read timeout in seconds. Default 60."));
        Map<String, Object> schema = Tools.objectSchema(props, "url");
        return Tools.build(new ToolDef(
                NAME,
                "Fetch a URL and return its text content. HTML is stripped via Jsoup; " +
                        "non-HTML bodies are returned as-is.",
                schema,
                (input, ctx) -> CompletableFuture.completedFuture(call(input, ctx))
        ));
    }

    public static Tool.ToolResult call(Map<String, Object> input, Tool.CallContext ctx) {
        String url = (String) input.get("url");
        if (url == null || url.isBlank()) {
            return Tool.ToolResult.error("url is required");
        }
        int cap = input.get("max_chars") instanceof Number n ? n.intValue() : MAX_BODY_CHARS;
        int timeoutS = input.get("timeout_s") instanceof Number n ? n.intValue() : 60;
        URI uri;
        try { uri = URI.create(url); }
        catch (Exception e) { return Tool.ToolResult.error("invalid url: " + e.getMessage()); }
        if (uri.getScheme() == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
            return Tool.ToolResult.error("url scheme must be http or https");
        }
        // SSRF guard. Refuse requests to loopback,
        // private, link-local, or otherwise non-routable
        // hosts. The model could otherwise point the tool
        // at http://localhost:8080/admin or
        // http://169.254.169.254/ to leak local services /
        // cloud metadata. The check is a name lookup +
        // address-class filter on every resolved address
        // (defence in depth against DNS-rebinding
        // surprises). Opt out via
        // AETHERCODE_ALLOW_LOCAL_FETCH=1 (test/CI case).
        if (!"1".equals(System.getenv("AETHERCODE_ALLOW_LOCAL_FETCH"))) {
            String ssrfReason = checkSsrf(uri);
            if (ssrfReason != null) {
                return Tool.ToolResult.error("fetch refused by SSRF guard: " + ssrfReason
                        + " (set AETHERCODE_ALLOW_LOCAL_FETCH=1 to bypass)");
            }
        }
        // build a per-call client when a custom timeout is
        // requested, so we don't pay a 60s wait for a 5s health
        // check. Sharing the default HTTP client for the common
        // case keeps connection pooling warm.
        OkHttpClient client = (timeoutS == 60) ? HTTP : HTTP.newBuilder()
                .readTimeout(Duration.ofSeconds(timeoutS))
                .build();
        try (Response resp = client.newCall(new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/json,text/plain;q=0.9,*/*;q=0.5")
                .get()
                .build()).execute()) {
            if (!resp.isSuccessful()) {
                return Tool.ToolResult.error("HTTP " + resp.code() + " " + resp.message() + " for " + url);
            }
            String contentType = resp.header("Content-Type", "");
            String body = resp.body() == null ? "" : resp.body().string();
            String text;
            if (contentType != null && contentType.toLowerCase().contains("html")) {
                Document doc = Jsoup.parse(body);
                doc.select("script,style,noscript").remove();
                text = doc.body() == null ? doc.text() : doc.body().text();
            } else {
                text = body;
            }
            if (text.length() > cap) {
                text = text.substring(0, cap) + "\n… (truncated)";
            }
            return Tool.ToolResult.of("URL: " + url + "\nContent-Type: " + contentType + "\n\n" + text);
        } catch (Exception e) {
            LOG.warn("web_fetch failed for {}: {}", url, e.getMessage());
            return Tool.ToolResult.error("fetch failed for " + url + ": " + e.getMessage());
        }
    }

    public static boolean isReadOnly(Map<String, Object> input) { return true; }

    /**
     * returns a non-null reason string when the URL
     * resolves to a host we should not contact. We block:
     * <ul>
     *   <li>loopback (127.0.0.0/8, ::1, localhost),</li>
     *   <li>private RFC1918 ranges (10.0.0.0/8, 172.16/12,
     *       192.168/16),</li>
     *   <li>link-local (169.254/16, fe80::/10),</li>
     *   <li>unique-local IPv6 (fc00::/7),</li>
     *   <li>site-local (deprecated fec0::/10 — still seen on
     *       some internal nets),</li>
     *   <li>any address that fails to resolve at all (a
     *       missing A/AAAA record is suspicious on a fetch
     *       that came from a real URL).</li>
     * </ul>
     * Multi-resolution: we resolve the host up-front and
     * check every returned address. OkHttp's DNS resolver
     * re-validates, so a DNS-rebinding attack between our
     * check and the actual request is still possible
     * (a future R-round can use a {@code Dns} override to
     * pin the address); for the immediate SSRF threat —
     * "the model says fetch this localhost URL" — the
     * pre-check is sufficient.
     */
    static String checkSsrf(URI uri) {
        if (uri == null) return "missing uri";
        String host = uri.getHost();
        if (host == null || host.isBlank()) return "missing host";
        // Reject literal "localhost" before DNS to avoid
        // making a lookup on user-supplied input.
        if (host.equalsIgnoreCase("localhost") || host.equalsIgnoreCase("localhost.localdomain")) {
            return "host is localhost";
        }
        try {
            java.net.InetAddress[] addrs = java.net.InetAddress.getAllByName(host);
            for (java.net.InetAddress addr : addrs) {
                if (addr.isLoopbackAddress()) return "host resolves to loopback: " + addr.getHostAddress();
                if (addr.isAnyLocalAddress()) return "host resolves to wildcard: " + addr.getHostAddress();
                if (addr.isLinkLocalAddress()) return "host resolves to link-local: " + addr.getHostAddress();
                if (addr.isSiteLocalAddress()) return "host resolves to site-local: " + addr.getHostAddress();
                if (!addr.isSiteLocalAddress() && isPrivate(addr.getAddress())) {
                    return "host resolves to private: " + addr.getHostAddress();
                }
            }
        } catch (java.net.UnknownHostException e) {
            return "host did not resolve: " + host;
        } catch (Exception e) {
            return "DNS lookup failed: " + e.getMessage();
        }
        return null;
    }

    private static boolean isPrivate(byte[] addr) {
        if (addr == null) return false;
        if (addr.length == 4) {
            int b0 = addr[0] & 0xFF;
            // 10.0.0.0/8
            if (b0 == 10) return true;
            // 172.16.0.0/12
            if (b0 == 172 && (addr[1] & 0xF0) == 16) return true;
            // 192.168.0.0/16
            if (b0 == 192 && (addr[1] & 0xFF) == 168) return true;
            return false;
        }
        if (addr.length == 16) {
            int b0 = addr[0] & 0xFF;
            int b1 = addr[1] & 0xFF;
            // fc00::/7 unique local
            if ((b0 & 0xFE) == 0xFC) return true;
            // fe80::/10 link-local
            if (b0 == 0xFE && (b1 & 0xC0) == 0x80) return true;
            return false;
        }
        return false;
    }
}
