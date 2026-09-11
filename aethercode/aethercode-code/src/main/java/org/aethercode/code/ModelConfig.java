package org.aethercode.code;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Model configuration: providers, auth, and credentials.
 *
 * <p>Java-native port of the Python {@code deepagents_code.model_config}
 * module. The Java port carries the public surface used by the CLI arg
 * parser, the TUI's {@code /model} screen, and the {@code /auth} screen.
 * The full provider-profile loading logic is left to a follow-up
 * port; the public types are stable.</p>
 */
public final class ModelConfig {
    private ModelConfig() {}

    private static final Logger LOG = LoggerFactory.getLogger(ModelConfig.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Env-var consulted for the default config directory. */
    public static final String CONFIG_DIR_ENV = "DEEPAGENTS_CODE_CONFIG_DIR";

    /** Default user-facing config dir. */
    public static final Path DEFAULT_CONFIG_DIR = Path.of(
            System.getProperty("user.home"), ".deepagents");

    /** Default state dir (config dir's child). */
    public static final Path DEFAULT_STATE_DIR = DEFAULT_CONFIG_DIR.resolve(".state");

    /** Provider name for the ChatGPT-OAuth codex provider. */
    public static final String CODEX_PROVIDER = "codex";

    /** Provider auth state. */
    public enum ProviderAuthState {
        CONFIGURED, MISSING, NOT_REQUIRED, IMPLICIT, MANAGED, UNKNOWN;
    }

    /** Provider auth source. */
    public enum ProviderAuthSource {
        STORED, ENV, NONE;
    }

    /** Provider auth status. */
    public record ProviderAuthStatus(
            String provider,
            ProviderAuthState state,
            ProviderAuthSource source,
            String envVar,
            String detail) {
    }

    /** Available model for a provider. */
    public record ModelSpec(String provider, String model) {
        public String spec() {
            return provider + ":" + model;
        }
    }

    /** Resolved env-var name. */
    public static String resolvedEnvVarName(String canonical) {
        if (canonical == null) return null;
        String up = canonical.toUpperCase(Locale.ROOT);
        return up.startsWith("DEEPAGENTS_CODE_") ? up : "DEEPAGENTS_CODE_" + up;
    }

    /** Build a status for a provider. */
    public static ProviderAuthStatus status(String provider, ProviderAuthState state) {
        return new ProviderAuthStatus(provider, state, ProviderAuthSource.NONE, null, null);
    }

    /** Whether a value is a plausible HTTP URL. */
    public static boolean isHttpUrl(String value) {
        if (value == null) return false;
        return value.startsWith("http://") || value.startsWith("https://");
    }

    /** A simple URL validator pattern. */
    private static final Pattern URL_RE = Pattern.compile("^https?://[^\\s]+$");

    /** A more thorough check that the URL parses. */
    public static boolean isLikelyUrl(String value) {
        if (value == null || !isHttpUrl(value)) return false;
        try {
            java.net.URI uri = java.net.URI.create(value);
            return uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Read the user config TOML (no implementation; returns empty map). */
    public static Map<String, Object> loadUserConfig() {
        Path p = DEFAULT_CONFIG_DIR.resolve("config.toml");
        if (!Files.exists(p)) return new LinkedHashMap<>();
        try {
            String text = Files.readString(p, StandardCharsets.UTF_8);
            return MAPPER.readValue(text, Map.class);
        } catch (IOException e) {
            LOG.warn("Could not read user config: {}", e.toString());
            return new LinkedHashMap<>();
        } catch (Exception e) {
            LOG.warn("User config is not JSON-shaped (no TOML parser wired in the stub): {}",
                    e.toString());
            return new LinkedHashMap<>();
        }
    }

    /** Write the user config TOML (no-op stub; full port uses TomlParser). */
    public static boolean writeUserConfig(Map<String, Object> data) {
        Path p = DEFAULT_CONFIG_DIR.resolve("config.toml");
        try {
            Files.createDirectories(p.getParent());
            String text = MAPPER.writeValueAsString(data) + "\n";
            Files.writeString(p, text, StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            LOG.warn("Could not write user config: {}", e.toString());
            return false;
        }
    }

    /** All known provider names. */
    public static final List<String> KNOWN_PROVIDERS = List.of(
            "anthropic", "openai", "google_genai", "google_vertexai",
            "aws_bedrock", "azure_openai", "cohere", "fireworks",
            "groq", "huggingface", "mistral", "ollama", "openrouter",
            "together", "xai", "deepseek", "perplexity", CODEX_PROVIDER);

    /** All providers that need an API key. */
    public static final Set<String> KEYED_PROVIDERS = Set.of(
            "anthropic", "openai", "google_genai", "google_vertexai",
            "aws_bedrock", "azure_openai", "cohere", "fireworks",
            "groq", "huggingface", "mistral", "openrouter",
            "together", "xai", "deepseek", "perplexity");
}
