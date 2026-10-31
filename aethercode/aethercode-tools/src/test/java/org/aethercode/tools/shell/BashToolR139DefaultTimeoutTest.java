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

package org.aethercode.tools.shell;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BashTool default timeout bumped from 30s to
 * 180s (3 minutes) so a fresh `mvn -q -B test` doesn't
 * always trip the timeout loop.
 */
class BashToolR139DefaultTimeoutTest {

    @Test
    void defaultTimeoutIs180Seconds() throws Exception {
        // The default is hard-coded in call(...): 180_000.
        // Verify by reading the source. We can't easily
        // exercise the call() path without a real shell,
        // so this is a reflective source check.
        String src = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get(
                        "src/main/java/org/aethercode/tools/shell/BashTool.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        // R139 marker.
        assertEquals(true, src.contains("180_000"),
                "R139: BashTool.call() default timeout should be 180_000; not found in source");
        // The old default should be gone.
        assertEquals(false, src.contains(": 30_000") && !src.contains("30_000ms"),
                "R139: 30_000 default should be replaced by 180_000");
        // The schema doc should say 180 000.
        assertEquals(true, src.contains("180 000 (3 min)"),
                "R139: schema doc should mention 180 000 (3 min)");
    }
}
