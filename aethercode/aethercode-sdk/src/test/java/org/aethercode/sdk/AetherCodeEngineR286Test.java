package org.aethercode.sdk;

import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R286: AETHERCODE_SUBAGENT_VARIANT env override +
 * Builder.subagentVariant(String) explicit setter.
 *
 * <p>The env override seeds
 * {@link AetherCodeEngine#currentVariant()} at boot.
 * The explicit builder setter wins when both are set
 * (tests pin this). Unknown variant names log a
 * warning and fall through to the bundled default
 * (resolved name is {@code null} → engine uses
 * {@code Variant.DEFAULT}).
 *
 * <p>The name resolution is exposed as the static
 * {@link AetherCodeEngine#resolveVariantName(String)}
 * helper, so tests can exercise the alias set without
 * mutating process-level env vars. Java 21 caches
 * {@code System.getenv()} reads via an unmodifiable
 * wrapper around {@code ProcessEnvironment.theEnvironment},
 * which the reflection-mutate trick from prior rounds
 * can't reach without invasive workarounds. The
 * static-helper + builder-setter tests below cover
 * every resolution path; the boot-time wiring
 * (env override → currentVariant) is a 5-line
 * branch in the constructor and is exercised
 * end-to-end by the daemon integration tests in
 * {@code AetherCodeMethodsR286Test} (see
 * {@code aethercode-protocol/src/test}).
 */
class AetherCodeEngineR286Test {

    private static AetherCodeEngine engineFor(Path cwd) {
        return new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .build();
    }

    // ---- resolveVariantName (the static helper) ----

    @Test
    void resolveVariantNameNullReturnsNull() {
        assertThat(AetherCodeEngine.resolveVariantName(null)).isNull();
    }

    @Test
    void resolveVariantNameBlankReturnsNull() {
        assertThat(AetherCodeEngine.resolveVariantName("")).isNull();
        assertThat(AetherCodeEngine.resolveVariantName("   ")).isNull();
    }

    @Test
    void resolveVariantNameBundledPresets() {
        assertThat(AetherCodeEngine.resolveVariantName("low")).isEqualTo("low");
        assertThat(AetherCodeEngine.resolveVariantName("medium")).isEqualTo("medium");
        assertThat(AetherCodeEngine.resolveVariantName("high")).isEqualTo("high");
        assertThat(AetherCodeEngine.resolveVariantName("xhigh")).isEqualTo("xhigh");
    }

    @Test
    void resolveVariantNameOpencodeAliases() {
        // The daemon's Variant.byName() maps
        // "fast" → LOW, "deep" → HIGH, "med"
        // → MEDIUM, etc. The boot path runs
        // every name through resolveVariantName
        // so the env var accepts the alias set
        // without a separate alias table.
        assertThat(AetherCodeEngine.resolveVariantName("fast")).isEqualTo("low");
        assertThat(AetherCodeEngine.resolveVariantName("deep")).isEqualTo("high");
        assertThat(AetherCodeEngine.resolveVariantName("med")).isEqualTo("medium");
        assertThat(AetherCodeEngine.resolveVariantName("default")).isEqualTo("medium");
    }

    @Test
    void resolveVariantNameUnknownReturnsNull() {
        // A typo (e.g. "hihg") must NOT
        // resolve to any bundled preset —
        // the constructor logs a warning
        // and leaves currentVariant at null.
        assertThat(AetherCodeEngine.resolveVariantName("hihg-typo")).isNull();
        assertThat(AetherCodeEngine.resolveVariantName("random-garbage")).isNull();
    }

    @Test
    void resolveVariantNameTrimsWhitespace() {
        // A leading/trailing space (often a
        // shell-quoting artefact) must not
        // fail the lookup. The trim happens
        // before the byName() call so the
        // alias table is the source of
        // truth.
        assertThat(AetherCodeEngine.resolveVariantName("  high  ")).isEqualTo("high");
    }

    @Test
    void resolveVariantNameIsCaseInsensitive() {
        // The alias table is keyed on the
        // lower-cased name, so "HIGH" /
        // "High" / "HIGH" all resolve to
        // the same bundled preset.
        assertThat(AetherCodeEngine.resolveVariantName("HIGH")).isEqualTo("high");
        assertThat(AetherCodeEngine.resolveVariantName("High")).isEqualTo("high");
        assertThat(AetherCodeEngine.resolveVariantName("XHigh")).isEqualTo("xhigh");
    }

    // ---- Builder.subagentVariant(String) — full boot path ----

    @Test
    void builderSubagentVariantSeedsCurrentVariant(@TempDir Path cwd) {
        // The explicit Builder setter seeds
        // currentVariant at boot — same
        // resolution path as the env
        // override, but the explicit
        // setter is the test-friendly path
        // (no process-level env mutation).
        AetherCodeEngine engine = new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .subagentVariant("xhigh")
                .build();
        assertThat(engine.currentVariant()).isEqualTo("xhigh");
    }

    @Test
    void builderSubagentVariantAcceptsOpencodeAliases(@TempDir Path cwd) {
        // The boot path goes through the
        // same resolveVariantName helper,
        // so the builder accepts aliases
        // too — a wrapper that passes
        // "deep" via the builder gets the
        // HIGH preset at engine boot.
        AetherCodeEngine engine = new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .subagentVariant("deep")
                .build();
        assertThat(engine.currentVariant()).isEqualTo("high");
    }

    @Test
    void builderSubagentVariantUnknownFallsThroughToNull(@TempDir Path cwd) {
        // A typo in the builder setter
        // also falls through gracefully —
        // the daemon logs a warning but
        // boots cleanly with the bundled
        // default.
        AetherCodeEngine engine = new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .subagentVariant("hihg-typo")
                .build();
        assertThat(engine.currentVariant()).isNull();
    }

    @Test
    void builderSubagentVariantBlankFallsThroughToNull(@TempDir Path cwd) {
        // A blank/empty setter value
        // means "no override" — the
        // currentVariant stays null and
        // getActiveVariant() returns
        // Variant.DEFAULT. The env
        // override is NOT consulted
        // here (the setter's contract
        // is "blank = nothing"); the
        // AETHERCODE_SUBAGENT_VARIANT
        // path is the dedicated channel
        // for env-driven defaults.
        AetherCodeEngine engine = new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .subagentVariant("   ")
                .build();
        assertThat(engine.currentVariant()).isNull();
    }

    @Test
    void noSubagentVariantDefaultsToNull(@TempDir Path cwd) {
        // The plain builder (no setter,
        // no env mutation) leaves
        // currentVariant null — the
        // engine's bundled default
        // applies.
        AetherCodeEngine engine = engineFor(cwd);
        assertThat(engine.currentVariant()).isNull();
    }

    @Test
    void builderSubagentVariantTrimmedWhitespace(@TempDir Path cwd) {
        // The setter trim is the same as
        // the static helper's trim —
        // leading/trailing space is OK
        // and resolves to the bundled
        // preset.
        AetherCodeEngine engine = new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .subagentVariant("  high  ")
                .build();
        assertThat(engine.currentVariant()).isEqualTo("high");
    }
}