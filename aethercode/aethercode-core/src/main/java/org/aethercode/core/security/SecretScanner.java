package org.aethercode.core.security;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * scan a string of text for well-known secret patterns. Modelled on the
 * TS {@code services/extractMemories/secretScanner.ts}.
 *
 * <p>Patterns covered (defaults):
 * <ul>
 *   <li>AWS access key — {@code AKIA[0-9A-Z]{16}}</li>
 *   <li>AWS secret key — 40-char base64-ish, 1+ lower/upper/digit/special</li>
 *   <li>OpenAI / Anthropic API key — {@code sk-(ant-)?[A-Za-z0-9_-]{20,}}</li>
 *   <li>GitHub PAT — {@code ghp_[A-Za-z0-9]{36,}}</li>
 *   <li>GitHub OAuth — {@code gho_[A-Za-z0-9]{36,}}</li>
 *   <li>Slack — {@code xox[abpr]-[A-Za-z0-9-]{10,}}</li>
 *   <li>PEM private key block — {@code -----BEGIN (RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----}</li>
 *   <li>Generic key=value pair — {@code (api_key|secret|token|password)=("?)([^"&\\s]+)}</li>
 * </ul>
 *
 * <p>Each match is a {@link Match} with the type, the start/end offset in the
 * input, and the raw value (so the caller can choose to redact or block).
 */
public final class SecretScanner {

    public enum Kind {
        AWS_ACCESS_KEY("aws-access-key"),
        AWS_SECRET_KEY("aws-secret-key"),
        OPENAI_KEY("openai-api-key"),
        ANTHROPIC_KEY("anthropic-api-key"),
        GITHUB_PAT("github-pat"),
        GITHUB_OAUTH("github-oauth"),
        SLACK_TOKEN("slack-token"),
        PEM_PRIVATE_KEY("pem-private-key"),
        GENERIC_KEY_VALUE("generic-key-value");

        public final String label;
        Kind(String label) { this.label = label; }
    }

    public record Match(Kind kind, int start, int end, String value) {
        public int length() { return end - start; }
    }

    public record Rule(Kind kind, Pattern pattern) {}

    private final List<Rule> rules = new ArrayList<>();

    public SecretScanner() { defaults(); }

    /** scan {@code text} for all known secret kinds. Returns matches in offset order. */
    public List<Match> scan(String text) {
        List<Match> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        for (Rule r : rules) {
            Matcher m = r.pattern.matcher(text);
            while (m.find()) {
                String value = m.group();
                out.add(new Match(r.kind, m.start(), m.end(), value));
            }
        }
        out.sort((a, b) -> Integer.compare(a.start, b.start));
        return out;
    }

    /** Convenience: redact all matches in {@code text} with {@code [REDACTED:<kind>]} markers. */
    public String redact(String text) {
        List<Match> ms = scan(text);
        if (ms.isEmpty()) return text;
        StringBuilder sb = new StringBuilder(text);
        // walk from end to start so offsets stay valid
        for (int i = ms.size() - 1; i >= 0; i--) {
            Match m = ms.get(i);
            sb.replace(m.start, m.end, "[REDACTED:" + m.kind.label + "]");
        }
        return sb.toString();
    }

    /** has-any check, faster than {@link #scan} for pre-output gating. */
    public boolean containsSecret(String text) {
        if (text == null || text.isEmpty()) return false;
        for (Rule r : rules) {
            if (r.pattern.matcher(text).find()) return true;
        }
        return false;
    }

    /** register a custom pattern. Patterns are checked in insertion order. */
    public SecretScanner register(Kind kind, String regex) {
        rules.add(new Rule(kind, Pattern.compile(regex)));
        return this;
    }

    private void defaults() {
        register(Kind.AWS_ACCESS_KEY, "\\bAKIA[0-9A-Z]{16}\\b");
        register(Kind.AWS_SECRET_KEY, "(?i)aws.{0,20}(?:secret|sk).{0,5}['\"][A-Za-z0-9/+=]{40}['\"]");
        register(Kind.OPENAI_KEY, "\\bsk-[A-Za-z0-9_-]{20,}\\b");
        register(Kind.ANTHROPIC_KEY, "\\bsk-ant-[A-Za-z0-9_-]{20,}\\b");
        register(Kind.GITHUB_PAT, "\\bghp_[A-Za-z0-9]{36,}\\b");
        register(Kind.GITHUB_OAUTH, "\\bgho_[A-Za-z0-9]{36,}\\b");
        register(Kind.SLACK_TOKEN, "\\bxox[abpr]-[A-Za-z0-9-]{10,}\\b");
        register(Kind.PEM_PRIVATE_KEY, "-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----");
        register(Kind.GENERIC_KEY_VALUE, "(?i)(api[_-]?key|secret|token|password)\\s*[=:]\\s*[\"']?([^\\s\"',;]{8,})");
    }
}
