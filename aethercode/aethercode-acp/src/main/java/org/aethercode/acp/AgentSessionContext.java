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

package org.aethercode.acp;

import java.util.Optional;

/**
 * Per-session context passed to an agent factory.
 *
 * <p>Mirror of the Python {@code AgentSessionContext} dataclass
 * ({@code cwd}, {@code mode}, optional {@code model}). The
 * Java port returns {@code cwd} and {@code mode} as required
 * fields; {@code model} is optional.</p>
 */
public record AgentSessionContext(
        String cwd,
        String mode,
        Optional<String> model) {

    public AgentSessionContext {
        if (cwd == null) cwd = "";
        if (mode == null) mode = "auto";
        model = model == null ? Optional.empty() : model;
    }

    public AgentSessionContext(String cwd, String mode) {
        this(cwd, mode, Optional.<String>empty());
    }

    public AgentSessionContext(String cwd, String mode, String model) {
        this(cwd, mode, Optional.ofNullable(model));
    }
}
