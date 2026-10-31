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

package org.aethercode.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * First-run onboarding marker.
 *
 * <p>Java-native port of the Python {@code deepagents_code.onboarding}
 * module. The Java port checks for a marker file under the private state
 * directory to decide whether to show the first-run flow.</p>
 */
public final class Onboarding {
    private Onboarding() {}

    private static final Logger LOG = LoggerFactory.getLogger(Onboarding.class);

    /** Marker filename. */
    public static final String ONBOARDING_MARKER_FILENAME = ".onboarded";

    /** Return the path to the onboarding marker. */
    public static Path markerPath() {
        return Path.of(System.getProperty("user.home"),
                ".deepagents", ".state", ONBOARDING_MARKER_FILENAME);
    }

    /** Whether the first-run flow has already completed. */
    public static boolean hasOnboarded() {
        return Files.exists(markerPath());
    }

    /** Mark onboarding as complete. */
    public static boolean markOnboarded() {
        try {
            Path p = markerPath();
            Path parent = p.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(p, "1\n");
            return true;
        } catch (IOException e) {
            LOG.warn("Could not write onboarding marker: {}", e.toString());
            return false;
        }
    }
}
