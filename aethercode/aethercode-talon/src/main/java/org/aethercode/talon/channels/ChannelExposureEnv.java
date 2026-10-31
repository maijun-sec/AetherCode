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

package org.aethercode.talon.channels;

/**
 * Environment variable prefix and options for channel exposure policy.
 *
 * <p>Java-native port of
 * {@code deepagents_talon.channels.base.ChannelExposureEnv}.</p>
 */
public record ChannelExposureEnv(
        String provider,
        String envPrefix,
        String openAck,
        String openAckValue,
        boolean requireSelfOperator) {

    public ChannelExposureEnv {
        if (provider == null) {
            throw new IllegalArgumentException("provider must not be null");
        }
        if (envPrefix == null) {
            throw new IllegalArgumentException("envPrefix must not be null");
        }
        if (openAck == null) {
            throw new IllegalArgumentException("openAck must not be null");
        }
        if (openAckValue == null) {
            throw new IllegalArgumentException("openAckValue must not be null");
        }
    }

    public ChannelExposureEnv(String provider, String envPrefix, String openAck,
                              boolean requireSelfOperator) {
        this(provider, envPrefix, openAck, "allow-arbitrary-senders", requireSelfOperator);
    }
}
