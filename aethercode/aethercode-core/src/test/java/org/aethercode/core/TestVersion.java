package org.aethercode.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 1:1 port of the Java-relevant subset of
 * <code>tests/unit_tests/test_version.py</code>.
 *
 * <p>The Python port's heavy machinery (PEP 610 editable-install
 * detection, distribution walk, path correlation) is a Python
 * packaging concept with no direct Java equivalent. The Java port
 * uses Maven for packaging; the equivalent 1:1 surface is:</p>
 *
 * <ul>
 *   <li>{@link Version#VERSION} is exposed and non-empty.</li>
 *   <li>It follows the upstream Python release (current
 *       <code>0.7.8</code>).</li>
 *   <li>It is a valid semver-ish X.Y.Z (with optional
 *       pre-release / build metadata).</li>
 * </ul>
 *
 * <p>The version-normalization tests
 * ({@code _with_editable_local_version} variants) are documented in
 * the Python port for the editable-install reporting shape; the
 * Java port does not need to mirror them because Maven produces
 * immutable artifacts rather than live editable installs.</p>
 */
@DisplayName("TestVersion — VERSION constant matches the Python upstream (1:1 from test_version.py)")
class TestVersion {

    /** Pattern: major.minor.patch with optional pre-release / build metadata. */
    private static final Pattern SEMVER = Pattern.compile(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
                    + "(?:-[0-9A-Za-z.-]+)?"
                    + "(?:\\+[0-9A-Za-z.-]+)?$");

    @Test
    @DisplayName("VERSION is exposed and non-empty")
    void versionIsExposed() {
        assertThat(Version.VERSION).isNotNull();
        assertThat(Version.VERSION).isNotEmpty();
    }

    @Test
    @DisplayName("VERSION matches the upstream Python release (currently 0.7.8)")
    void versionMatchesUpstreamPython() {
        // Locked by hand to the upstream Python __version__ —
        // mismatches are caught by code review when the Python
        // version is bumped.
        assertThat(Version.VERSION).isEqualTo("0.7.8");
    }

    @Test
    @DisplayName("VERSION is a valid semver X.Y.Z string")
    void versionIsValidSemver() {
        assertThat(Version.VERSION)
                .as("VERSION must follow semver X.Y.Z")
                .matches(SEMVER);
    }
}
