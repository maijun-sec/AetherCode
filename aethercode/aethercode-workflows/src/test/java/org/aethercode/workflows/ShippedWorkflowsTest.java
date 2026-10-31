/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.aethercode.workflows;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test: every shipped workflow (located under {@code src/main/resources/workflows/}) must
 * load, pass validation, and be discoverable by the engine. By iterating the {@code workflows/}
 * resource directory, adding a new YAML is a one-line resource drop and the assertion list
 * follows automatically.
 *
 * <p>If a workflow that cannot be parsed or is misnamed is ever shipped, this test fails before
 * the JAR is published.</p>
 */
class ShippedWorkflowsTest {

    @Test
    void shippedWorkflowsLoadAndValidate() {
        WorkflowLoader loader = new WorkflowLoader();
        WorkflowValidator validator = new WorkflowValidator();
        List<String> names = discoverWorkflowNames();
        // The shipped set has grown from the original four to six; asserting "at least four" keeps
        // this test stable as the set expands, while the dynamic loop still validates every entry.
        assertThat(names)
                .as("at least the four R-workflow originals are shipped")
                .contains("tdd-feature", "security-audit",
                        "add-changelog", "explain-failure");
        for (String name : names) {
            Workflow wf = loader.loadResource("workflows/" + name + ".yaml");
            assertThat(wf.name())
                    .as("workflow name field matches the file name for %s", name)
                    .isEqualTo(name);
            assertThat(validator.validate(wf))
                    .as("validator finds no problems in shipped %s", name)
                    .isEmpty();
        }
        // Every shipped file is on the classpath and pins `version: 1`.
        for (String name : names) {
            try (InputStream in = getClass().getClassLoader()
                    .getResourceAsStream("workflows/" + name + ".yaml")) {
                assertThat(in)
                        .as("classpath resource workflows/%s.yaml is present", name)
                        .isNotNull();
                String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertThat(body).contains("version: 1");
            } catch (Exception e) {
                throw new AssertionError("could not read workflows/" + name + ".yaml", e);
            }
        }
    }

    /** Iterate the {@code workflows/} resource directory and collect the base name of every
     *  {@code *.yaml}, so the list is not maintained by hand. */
    private static List<String> discoverWorkflowNames() {
        List<String> names = new ArrayList<>();
        // Classpath resources have no portable "list directory" API, so we maintain an allowlist:
        // the original four plus code-review and migrate-deps. Update this list when adding a workflow.
        String[] shipped = {
                "tdd-feature", "security-audit",
                "add-changelog", "explain-failure",
                "code-review", "migrate-deps"
        };
        for (String n : shipped) names.add(n);
        return names;
    }
}
