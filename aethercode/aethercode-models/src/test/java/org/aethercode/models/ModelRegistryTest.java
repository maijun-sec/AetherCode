package org.aethercode.models;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T-2-14 acceptance tests for {@link ModelRegistry}. The four
 * tests cover the four behaviours the spec (design.md §3.7)
 * promises:
 *
 * <ol>
 *   <li>shipped baseline + per-layer merge with project-precedence;</li>
 *   <li>lookup by name and by provider;</li>
 *   <li>{@code active()} resolves the first declared active name and
 *       falls back to the first model in the catalogue;</li>
 *   <li>{@link ModelRegistry#setActive(String)} writes the active
 *       field to the project-layer file (or user-layer fallback).</li>
 * </ol>
 */
class ModelRegistryTest {

    /** Build a registry pointed at a test-only baseline + the given
     *  user / project layer files. The default shipped resource on
     *  the production classpath is bypassed so the assertions don't
     *  depend on its exact content. */
    private ModelRegistry newRegistry(Path userHome, Path cwd) {
        return new ModelRegistry(userHome, cwd)
                .withShippedResource("/test-baseline.yaml")
                .reload();
    }

    @Test
    void shippedBaselinePlusUserAndProjectLayersProjectWins(@TempDir Path tmp) throws Exception {
        Path userHome = tmp.resolve("userHome");
        Path cwd = tmp.resolve("project");
        Files.createDirectories(userHome.resolve(ModelRegistry.USER_DIR_NAME));
        Files.createDirectories(cwd.resolve(ModelRegistry.USER_DIR_NAME));

        // User layer adds a new model and overrides an existing one.
        Files.writeString(userHome.resolve(ModelRegistry.USER_DIR_NAME)
                        .resolve(ModelRegistry.PROVIDERS_FILE),
                """
                providers:
                  - name: anthropic
                    models:
                      - name: claude-test-small
                        contextWindow: 200000
                        maxOutput: 16000
                        pricing:
                          inputPerMTokensUsd: 9.9
                          outputPerMTokensUsd: 9.9
                          cachedPerMTokensUsd: 0.0
                      - name: user-only
                        contextWindow: 4096
                        maxOutput: 1024
                """, StandardCharsets.UTF_8);

        // Project layer adds another new model and overrides the
        // user-layer override of claude-test-small (project wins).
        Files.writeString(cwd.resolve(ModelRegistry.USER_DIR_NAME)
                        .resolve(ModelRegistry.PROVIDERS_FILE),
                """
                providers:
                  - name: anthropic
                    models:
                      - name: claude-test-small
                        contextWindow: 1000000
                        maxOutput: 64000
                        pricing:
                          inputPerMTokensUsd: 0.1
                          outputPerMTokensUsd: 0.2
                          cachedPerMTokensUsd: 0.01
                      - name: project-only
                        contextWindow: 8192
                        maxOutput: 2048
                """, StandardCharsets.UTF_8);

        ModelRegistry registry = newRegistry(userHome, cwd);

        // 4 distinct models visible:
        //   - claude-test-small (project-overridden)
        //   - stub-only        (shipped baseline)
        //   - user-only        (user layer)
        //   - project-only     (project layer)
        assertThat(registry.size()).isEqualTo(4);
        List<String> names = registry.list().stream().map(ModelProfile::name).toList();
        assertThat(names).containsExactlyInAnyOrder(
                "claude-test-small", "stub-only", "user-only", "project-only");

        // Project layer wins for the overlapping model.
        ModelProfile claude = registry.get("claude-test-small").orElseThrow();
        assertThat(claude.contextWindow()).isEqualTo(1_000_000);
        assertThat(claude.maxOutput()).isEqualTo(64_000);
        assertThat(claude.pricing().inputPerMTokensUsd()).isEqualTo(0.1);

        // Unknown name returns empty, not a throw.
        assertThat(registry.get("does-not-exist")).isEqualTo(Optional.empty());

        // list() is sorted by provider then name.
        List<String> sortedNames = registry.list().stream().map(ModelProfile::name).toList();
        assertThat(sortedNames.indexOf("claude-test-small"))
                .isLessThan(sortedNames.indexOf("user-only"));
    }

    @Test
    void listByProviderFiltersAndGetByName(@TempDir Path tmp) {
        ModelRegistry registry = newRegistry(tmp.resolve("u"), tmp.resolve("p")).reload();

        // The test baseline has 1 anthropic + 1 stub.
        assertThat(registry.listByProvider("anthropic"))
                .extracting(ModelProfile::name)
                .containsExactly("claude-test-small");
        assertThat(registry.listByProvider("stub"))
                .extracting(ModelProfile::name)
                .containsExactly("stub-only");
        assertThat(registry.listByProvider("openai")).isEmpty();
        // null / blank provider => all models (matches the spec's
        // `model/list [provider?]` semantics).
        assertThat(registry.listByProvider(null)).hasSize(2);
        assertThat(registry.listByProvider("")).hasSize(2);

        // get() is case-sensitive and exact-match.
        assertThat(registry.get("CLAUDE-TEST-SMALL")).isEqualTo(Optional.empty());
        assertThat(registry.get("claude-test-small")).isPresent();

        // grouped() buckets by provider, sorted alphabetically.
        var grouped = registry.grouped();
        assertThat(grouped.keySet()).containsExactly("anthropic", "stub");
        assertThat(grouped.get("anthropic")).hasSize(1);
    }

    @Test
    void activeResolvesFirstDeclaredNameThenFallsBack(@TempDir Path tmp) throws Exception {
        Path userHome = tmp.resolve("userHome");
        Path cwd = tmp.resolve("project");
        Files.createDirectories(userHome.resolve(ModelRegistry.USER_DIR_NAME));
        Files.createDirectories(cwd.resolve(ModelRegistry.USER_DIR_NAME));

        // User file declares an active model that's in the baseline.
        Files.writeString(userHome.resolve(ModelRegistry.USER_DIR_NAME)
                        .resolve(ModelRegistry.PROVIDERS_FILE),
                """
                active: stub-only
                """, StandardCharsets.UTF_8);

        // Project file declares a different active model that also
        // exists. The project layer's active field wins because it
        // is loaded last.
        Files.writeString(cwd.resolve(ModelRegistry.USER_DIR_NAME)
                        .resolve(ModelRegistry.PROVIDERS_FILE),
                """
                active: claude-test-small
                """, StandardCharsets.UTF_8);

        ModelRegistry registry = newRegistry(userHome, cwd);

        // The first *valid* active name wins. The project file's
        // active is loaded after the user file's, so the project
        // value is in activeNames first.
        ModelProfile active = registry.active().orElseThrow();
        assertThat(active.name()).isEqualTo("claude-test-small");

        // Project declares a name that doesn't exist anywhere: the
        // loader should fall through to the user-declared one.
        Files.writeString(cwd.resolve(ModelRegistry.USER_DIR_NAME)
                        .resolve(ModelRegistry.PROVIDERS_FILE),
                """
                active: ghost
                """, StandardCharsets.UTF_8);
        ModelRegistry registry2 = newRegistry(userHome, cwd);
        assertThat(registry2.active().orElseThrow().name()).isEqualTo("stub-only");

        // Neither file declares an active: fall back to the first
        // model in the sorted catalogue ("claude-test-small" comes
        // before "stub-only" alphabetically within their providers,
        // and anthropic < stub alphabetically).
        ModelRegistry fallback = newRegistry(null, tmp.resolve("empty")).reload();
        assertThat(fallback.active().orElseThrow().name()).isEqualTo("claude-test-small");

        // No models at all: active() is empty, not an error.
        ModelRegistry empty = new ModelRegistry(null, tmp.resolve("empty2"))
                .withShippedResource("/does-not-exist.yaml")
                .reload();
        assertThat(empty.active()).isEqualTo(Optional.empty());
    }

    @Test
    void setActiveWritesToProjectFileAndFallsBackToUser(@TempDir Path tmp) throws Exception {
        Path userHome = tmp.resolve("userHome");
        Path cwd = tmp.resolve("project");
        Files.createDirectories(userHome.resolve(ModelRegistry.USER_DIR_NAME));
        Files.createDirectories(cwd.resolve(ModelRegistry.USER_DIR_NAME));

        // Project file pre-exists (so we can assert it gets
        // modified, not created from scratch).
        Path projectFile = cwd.resolve(ModelRegistry.USER_DIR_NAME)
                .resolve(ModelRegistry.PROVIDERS_FILE);
        Files.writeString(projectFile, """
                providers:
                  - name: stub
                    models:
                      - name: stub-only
                """, StandardCharsets.UTF_8);

        ModelRegistry registry = newRegistry(userHome, cwd);
        ModelProfile p = registry.setActive("claude-test-small");
        assertThat(p.name()).isEqualTo("claude-test-small");

        // The project file now has an `active:` field at the top,
        // and the original `providers:` block is preserved (the
        // registry's write path is a merge, not a clobber). The
        // YAML emitter may or may not quote the string; we just
        // check that the active key + name appear in the document.
        String written = Files.readString(projectFile, StandardCharsets.UTF_8);
        assertThat(written).contains("active:").contains("claude-test-small");
        assertThat(written).contains("providers:");

        // The active name is now in the registry's activeNames.
        assertThat(registry.activeNames()).contains("claude-test-small");

        // setActive on a non-existent model throws NOT_FOUND.
        assertThatThrownBy(() -> registry.setActive("does-not-exist"))
                .isInstanceOf(ModelRegistryException.class)
                .extracting("kind").isEqualTo(ModelRegistryException.Kind.NOT_FOUND);

        // Fallback path: a project cwd where the .aethercode
        // directory cannot be written. We simulate this by giving
        // a cwd under a read-only file (an existing regular file,
        // not a directory). The registry should fall back to the
        // user layer.
        Path readOnlyCwd = tmp.resolve("readOnlyProject");
        Files.writeString(readOnlyCwd, "not a directory\n");
        // userHome already exists with a writable .aethercode.
        Path userFile = userHome.resolve(ModelRegistry.USER_DIR_NAME)
                .resolve(ModelRegistry.PROVIDERS_FILE);
        Files.deleteIfExists(userFile);
        ModelRegistry fallback = newRegistry(userHome, readOnlyCwd).reload();
        // The shipped baseline + cwd override gives us the same 2
        // models; the test then sets active which must land on the
        // user layer because the project path is a file.
        ModelProfile p2 = fallback.setActive("stub-only");
        assertThat(p2.name()).isEqualTo("stub-only");
        // The user file now exists with the active field.
        assertThat(Files.exists(userFile)).isTrue();
        String userWritten = Files.readString(userFile, StandardCharsets.UTF_8);
        assertThat(userWritten).contains("active:").contains("stub-only");
    }
}
