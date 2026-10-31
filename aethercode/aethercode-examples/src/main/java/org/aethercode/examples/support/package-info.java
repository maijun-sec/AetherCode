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

/**
 * Support utilities shared by the example code.
 *
 * <p>Contains:
 * <ul>
 *   <li>{@link org.aethercode.examples.support.MiniJson} &mdash; a
 *       small JSON read / write helper used by the example code in
 *       place of Jackson for tiny payloads.</li>
 *   <li>{@link org.aethercode.examples.support.TinyToml} &mdash; a
 *       minimal TOML reader used by the better-harness config
 *       loader.</li>
 *   <li>{@link org.aethercode.examples.support.MiniArgParser} &mdash;
 *       a tiny subcommand parser used by the better-harness CLI.</li>
 * </ul>
 *
 * <p>These classes are illustrative. They cover the small subset of
 * features the example code needs, not the full surface of the
 * libraries they mirror.</p>
 */
package org.aethercode.examples.support;
