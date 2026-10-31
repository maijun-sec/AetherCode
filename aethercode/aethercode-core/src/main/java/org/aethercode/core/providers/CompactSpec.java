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

package org.aethercode.core.providers;

/**
 * R283: per-provider compaction configuration. Lifted
 * out of {@link org.aethercode.core.compact.CompactConfig}
 * into a sibling record so a single yaml block can declare
 * the same {@code contextWindow} / {@code compactAt} /
 * {@code preserveTail} / {@code strategy} for every model
 * a provider exposes (when every model shares the same budget,
 * e.g. glm-4-flash / glm-4-plus both with 128k).
 *
 * <p>Per-model {@code compact} blocks (in {@link ModelSpec})
 * override the provider-level defaults. The fallback order is:
 * <ol>
 *   <li>model.compact (highest priority)</li>
 *   <li>provider.compact</li>
 *   <li>{@link org.aethercode.core.compact.CompactConfig#DEFAULT}
 *       (lowest priority)</li>
 * </ol>
 *
 * <p>This record lives next to {@link ProviderSpec} so
 * YAML deserialisation can populate it without forcing
 * the providers module to depend on aethercode-core's
 * compact package.
 */
public record CompactSpec(
        int contextWindow,
        Integer compactAt,
        Integer preserveTail,
        String strategy
) {
    public CompactSpec {
        if (contextWindow <= 0) {
            throw new IllegalArgumentException(
                    "compact.contextWindow must be > 0, got " + contextWindow);
        }
        // guard inverted pair up-front (compactSpec is the
        // YAML entry point — better fail at boot than at the
        // first runPreFlightCompact() call). compactAt is
        // nullable so an unset field means "use the tier
        // default"; only check when both are present.
        if (compactAt != null && compactAt.intValue() > contextWindow) {
            throw new IllegalArgumentException(
                    "compact.compactAt (" + compactAt
                            + ") must not exceed compact.contextWindow ("
                            + contextWindow + ")");
        }
    }

    /** convert to the runtime {@link
     *  org.aethercode.core.compact.CompactConfig}. {@code
     *  compactAt} / {@code preserveTail} / {@code strategy}
     *  are optional in yaml; absent fields fall back to
     *  the contextWindow tier rule from
     *  {@link org.aethercode.core.compact.CompactConfig#forContextWindow}. */
    public org.aethercode.core.compact.CompactConfig toConfig() {
        // build a tier-default with our full config, then
        // override whatever the caller supplied. The
        // local variables use a leading underscore so
        // they don't shadow the record-component fields
        // (which Java treats as both fields AND
        // no-arg accessors, hence the compile error
        // about "int != <null>" on the original code).
        org.aethercode.core.compact.CompactConfig tier =
                org.aethercode.core.compact.CompactConfig.forContextWindow(contextWindow);
        int _compactAt = this.compactAt != null
                ? this.compactAt.intValue()
                : tier.compactAt();
        int _preserveTail = this.preserveTail != null
                ? this.preserveTail.intValue()
                : tier.preserveTail();
        org.aethercode.core.compact.CompactConfig.Strategy _strategy =
                this.strategy != null
                                ? org.aethercode.core.compact.CompactConfig.Strategy.fromWire(this.strategy)
                                : tier.strategy();
        return new org.aethercode.core.compact.CompactConfig(
                contextWindow, _compactAt, _preserveTail, _strategy);
    }
}