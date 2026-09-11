package org.aethercode.core.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecretScannerTest {

    private final SecretScanner scanner = new SecretScanner();

    @Test
    void awsAccessKeyDetected() {
        List<SecretScanner.Match> m = scanner.scan("the key is AKIAIOSFODNN7EXAMPLE end");
        assertThat(m).isNotEmpty();
        assertThat(m.get(0).kind()).isEqualTo(SecretScanner.Kind.AWS_ACCESS_KEY);
    }

    @Test
    void openaiKeyDetected() {
        List<SecretScanner.Match> m = scanner.scan("export OPENAI_KEY=sk-abcdefghijklmnopqrstuvwxyz1234567890");
        assertThat(m).extracting(SecretScanner.Match::kind).contains(SecretScanner.Kind.OPENAI_KEY);
    }

    @Test
    void anthropicKeyDetected() {
        List<SecretScanner.Match> m = scanner.scan("key: sk-ant-api03-abcdefghijklmnopqrstuvwxyz");
        assertThat(m).extracting(SecretScanner.Match::kind).contains(SecretScanner.Kind.ANTHROPIC_KEY);
    }

    @Test
    void githubPatDetected() {
        List<SecretScanner.Match> m = scanner.scan("Authorization: Bearer ghp_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        assertThat(m).extracting(SecretScanner.Match::kind).contains(SecretScanner.Kind.GITHUB_PAT);
    }

    @Test
    void githubOauthDetected() {
        List<SecretScanner.Match> m = scanner.scan("token: gho_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        assertThat(m).extracting(SecretScanner.Match::kind).contains(SecretScanner.Kind.GITHUB_OAUTH);
    }

    @Test
    void slackTokenDetected() {
        List<SecretScanner.Match> m = scanner.scan("slack: xoxb-1234567890-12345-abcdefghijkl");
        assertThat(m).extracting(SecretScanner.Match::kind).contains(SecretScanner.Kind.SLACK_TOKEN);
    }

    @Test
    void pemBlockDetected() {
        String pem = "config:\n-----BEGIN RSA PRIVATE KEY-----\nMIIEpAIBAAKCAQEA...\n-----END RSA PRIVATE KEY-----\nrest";
        List<SecretScanner.Match> m = scanner.scan(pem);
        assertThat(m).extracting(SecretScanner.Match::kind).contains(SecretScanner.Kind.PEM_PRIVATE_KEY);
    }

    @Test
    void cleanTextHasNoMatches() {
        List<SecretScanner.Match> m = scanner.scan("just some normal text without secrets");
        assertThat(m).isEmpty();
    }

    @Test
    void redactReplacesMatches() {
        String input = "aws key: AKIAIOSFODNN7EXAMPLE end";
        String out = scanner.redact(input);
        assertThat(out).contains("[REDACTED:aws-access-key]");
        assertThat(out).doesNotContain("AKIAIOSFODNN7EXAMPLE");
    }

    @Test
    void redactMultipleMatches() {
        String input = "ak1=AKIAIOSFODNN7EXAMPLE ak2=AKIA1234567890ABCDEF";
        String out = scanner.redact(input);
        assertThat(out).doesNotContain("AKIA");
        assertThat(out.split("REDACTED").length).isEqualTo(3); // two markers + 1
    }

    @Test
    void containsSecretTrue() {
        assertThat(scanner.containsSecret("AKIAIOSFODNN7EXAMPLE")).isTrue();
        assertThat(scanner.containsSecret("clean text")).isFalse();
    }

    @Test
    void customPatternWorks() {
        SecretScanner custom = new SecretScanner()
                .register(SecretScanner.Kind.GENERIC_KEY_VALUE, "TOPSECRET_[A-Z0-9]{8}");
        List<SecretScanner.Match> m = custom.scan("here is TOPSECRET_ABCDEFGH end");
        assertThat(m).isNotEmpty();
    }

    @Test
    void emptyTextIsEmpty() {
        assertThat(scanner.scan(null)).isEmpty();
        assertThat(scanner.scan("")).isEmpty();
        assertThat(scanner.containsSecret(null)).isFalse();
    }

    @Test
    void matchOffsetsAreValid() {
        String text = "prefix AKIAIOSFODNN7EXAMPLE suffix";
        List<SecretScanner.Match> m = scanner.scan(text);
        assertThat(m).hasSize(1);
        SecretScanner.Match match = m.get(0);
        assertThat(text.substring(match.start(), match.end())).isEqualTo("AKIAIOSFODNN7EXAMPLE");
    }
}
