package org.aethercode.code;

/**
 * Version information and lightweight constants for the {@code deepagents-code}
 * package.
 *
 * <p>Java-native port of the Python {@code deepagents_code._version} module.
 * The {@link #VERSION} string follows the upstream Python release.</p>
 */
public final class Version {
    private Version() {}

    /** Current release version. Mirrors the Python {@code __version__}. */
    public static final String VERSION = "0.1.60";

    /** URL for the {@code deepagents-code} documentation. */
    public static final String DOCS_URL = "https://docs.langchain.com/oss/python/deepagents/code";

    /** PyPI JSON API endpoint for version checks. */
    public static final String PYPI_URL = "https://pypi.org/pypi/deepagents-code/json";

    /** PyPI JSON API endpoint for reading {@code deepagents} SDK release metadata. */
    public static final String SDK_PYPI_URL = "https://pypi.org/pypi/deepagents/json";

    /** URL for the full changelog. */
    public static final String CHANGELOG_URL =
            "https://github.com/langchain-ai/deepagents/blob/main/libs/code/CHANGELOG.md";

    /** User-Agent header sent with PyPI requests. */
    public static final String USER_AGENT = "deepagents-code/" + VERSION + " update-check";
}
