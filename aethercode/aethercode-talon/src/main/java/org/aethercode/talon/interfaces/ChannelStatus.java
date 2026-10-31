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

package org.aethercode.talon.interfaces;

import java.util.Optional;

/**
 * Connection status reported by a channel adapter.
 *
 * <p>Java-native port of {@code deepagents_talon.interfaces.ChannelStatus}.</p>
 */
public record ChannelStatus(
        String provider,
        boolean connected,
        Optional<String> detail) {

    public ChannelStatus {
        if (provider == null) {
            throw new IllegalArgumentException("provider must not be null");
        }
        detail = detail == null ? Optional.empty() : detail;
    }

    public ChannelStatus(String provider, boolean connected) {
        this(provider, connected, Optional.empty());
    }

    public ChannelStatus(String provider, boolean connected, String detail) {
        this(provider, connected, Optional.ofNullable(detail));
    }
}
