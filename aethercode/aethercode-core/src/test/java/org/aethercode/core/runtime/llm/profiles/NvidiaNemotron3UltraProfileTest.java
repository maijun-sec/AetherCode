package org.aethercode.core.runtime.llm.profiles;

import org.aethercode.core.runtime.llm.HarnessProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NvidiaNemotron3UltraProfileTest {

    @AfterEach
    void cleanup() {
        HarnessProfile.clearRegistry();
    }

    @Test
    void registersAllSpecs() {
        NvidiaNemotron3UltraProfile.register();
        for (String spec : NvidiaNemotron3UltraProfile.NEMOTRON_ULTRA_MODEL_SPECS) {
            HarnessProfile p = HarnessProfile.getHarnessProfile(spec);
            assertThat(p).as("profile for %s", spec).isNotNull();
        }
    }

    @Test
    void promptContainsAllSections() {
        String suffix = NvidiaNemotron3UltraProfile.SYSTEM_PROMPT_SUFFIX;
        assertThat(suffix).contains("<approach>");
        assertThat(suffix).contains("<grounding>");
        assertThat(suffix).contains("<loop_control>");
        assertThat(suffix).contains("<tool_selection>");
        assertThat(suffix).contains("<state_changes>");
        assertThat(suffix).contains("<final_answer_completeness>");
        assertThat(suffix).contains("<followup_defaults>");
        assertThat(suffix).contains("<context_compaction>");
    }

    @Test
    void readFileOverrideIsSet() {
        NvidiaNemotron3UltraProfile.register();
        HarnessProfile p = HarnessProfile.getHarnessProfile("nvidia:nvidia/nemotron-3-ultra-550b-a55b");
        assertThat(p.toolDescriptionOverrides())
                .containsKey("read_file")
                .containsValue(NvidiaNemotron3UltraProfile.READ_FILE_DESCRIPTION_OVERRIDE);
    }

    @Test
    void nemotronSpecsIsComplete() {
        assertThat(NvidiaNemotron3UltraProfile.NEMOTRON_ULTRA_MODEL_SPECS)
                .hasSize(8)
                .contains("NVIDIA:nvidia/nemotron-3-ultra-550b-a55b",
                        "nvidia:nvidia/nemotron-3-ultra-550b-a55b");
    }
}
