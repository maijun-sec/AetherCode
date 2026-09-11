package org.aethercode.models;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
/**
 * T-2-15 acceptance tests for {@link ModelRegistryLoader}. The
 * three tests exercise the three input shapes the registry
 * consumes: a classpath resource, an inline YAML stream, and a
 * file on disk. The permissive-parse contract (unknown fields
 * ignored, bad rows skipped) is also pinned down here.
 */
class ModelRegistryLoaderTest {

    @Test
    void loadsShippedProvidersYaml(@TempDir Path tmp) {
        // Sanity: the shipped resource that ships in the jar is
        // present and parses to the 11-model baseline the design.md
        // §3.7 spec calls for.
        try (InputStream in = ModelRegistryLoader.class.getResourceAsStream("/providers.yaml")) {
            assertThat(in).as("shipped providers.yaml is on the classpath").isNotNull();
            List<ModelProfile> all = new ModelRegistryLoader().loadFromStream(in);
            assertThat(all).extracting(ModelProfile::name).contains(
                    "claude-opus-4-1", "claude-sonnet-4", "claude-haiku-4",
                    "gpt-5", "gpt-5-mini", "gpt-4o",
                    "gemini-2.5-pro", "gemini-2.5-flash",
                    "llama3.3-70b", "qwen2.5-coder-32b");

            // Anthropic has a prompt-cache rate (cachedPerMTokensUsd
            // is non-zero); the local ollama models are free.
            ModelProfile opus = all.stream()
                    .filter(p -> p.name().equals("claude-opus-4-1")).findFirst().orElseThrow();
            assertThat(opus.pricing().cachedPerMTokensUsd()).isGreaterThan(0);
            ModelProfile llama = all.stream()
                    .filter(p -> p.name().equals("llama3.3-70b")).findFirst().orElseThrow();
            assertThat(llama.pricing()).isEqualTo(Pricing.free());
        } catch (Exception e) {
            throw new AssertionError("classpath providers.yaml read failed", e);
        }

        // loadFromFile on a non-existent file returns an empty list,
        // not an error (the user hasn't set up a project file yet).
        List<ModelProfile> empty = new ModelRegistryLoader().loadFromFile(tmp.resolve("nope.yaml"));
        assertThat(empty).isEmpty();
    }

    @Test
    void loadsFromInlineYamlStreamAndReadActiveField(@TempDir Path tmp) throws Exception {
        String yaml = """
                providers:
                  - name: stub
                    models:
                      - name: stub-1
                        contextWindow: 8000
                        maxOutput: 1000
                        capabilities: [tools]
                        pricing:
                          inputPerMTokensUsd: 1.0
                          outputPerMTokensUsd: 2.0
                          cachedPerMTokensUsd: 0.1
                active: stub-1
                """;
        Path file = tmp.resolve("providers.yaml");
        Files.writeString(file, yaml, StandardCharsets.UTF_8);

        ModelRegistryLoader loader = new ModelRegistryLoader();
        List<ModelProfile> models = loader.loadFromFile(file);
        assertThat(models).hasSize(1);
        ModelProfile m = models.get(0);
        assertThat(m.name()).isEqualTo("stub-1");
        assertThat(m.provider()).isEqualTo("stub");
        assertThat(m.contextWindow()).isEqualTo(8000);
        assertThat(m.maxOutput()).isEqualTo(1000);
        // The shape of the active field — single string vs list.
        List<String> active = loader.loadActiveNames(file);
        assertThat(active).containsExactly("stub-1");
    }

    @Test
    void malformedRowsAreSkippedAndBadYamlThrows(@TempDir Path tmp) throws Exception {
        // Two valid rows and one row that omits the required 'name'.
        // The loader should return the two valid profiles and skip
        // the third without throwing.
        String yaml = """
                providers:
                  - name: good
                    models:
                      - name: g-1
                        contextWindow: 1
                        maxOutput: 1
                      - name: g-2
                        contextWindow: 2
                        maxOutput: 2
                      - contextWindow: 999
                        maxOutput: 999
                  - models:
                      - name: orphan
                """;
        Path file = tmp.resolve("broken.yaml");
        Files.writeString(file, yaml, StandardCharsets.UTF_8);

        List<ModelProfile> models = new ModelRegistryLoader().loadFromFile(file);
        assertThat(models).extracting(ModelProfile::name)
                .containsExactlyInAnyOrder("g-1", "g-2", "orphan");

        // Truly malformed YAML is a hard error — the caller (the
        // registry) logs and falls back to whatever the layer
        // above had.
        Path junk = tmp.resolve("junk.yaml");
        Files.writeString(junk, "providers: [oops\n", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new ModelRegistryLoader().loadFromFile(junk))
                .isInstanceOf(ModelRegistryException.class)
                .extracting("kind").isEqualTo(ModelRegistryException.Kind.BAD_YAML);

        // loadActiveNames on a list-form `active` returns the list.
        Path listForm = tmp.resolve("list.yaml");
        Files.writeString(listForm, "active: [a, b, c]\n", StandardCharsets.UTF_8);
        assertThat(new ModelRegistryLoader().loadActiveNames(listForm))
                .containsExactly("a", "b", "c");

        // loadActiveNames on a missing file returns an empty list.
        assertThat(new ModelRegistryLoader().loadActiveNames(tmp.resolve("missing.yaml")))
                .isEmpty();
    }
}
