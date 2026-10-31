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
 * Ranked configuration provider chain for the {@code dcode} runtime.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.configuration} package. Provides:</p>
 * <ul>
 *   <li>{@link org.aethercode.code.configuration.Provider} — protocol every
 *       configuration source satisfies.</li>
 *   <li>{@link org.aethercode.code.configuration.ConfigTypes} — sealed
 *       {@code ProviderResult} (Found/Unset/Invalid), provider health, and
 *       snapshot types.</li>
 *   <li>{@link org.aethercode.code.configuration.ConfigPaths} — fixed
 *       managed-config path resolution per platform.</li>
 *   <li>{@link org.aethercode.code.configuration.ConfigProviders} — TOML and
 *       environment providers plus coercion helpers.</li>
 *   <li>{@link org.aethercode.code.configuration.ConfigResolver} — rank-based
 *       configuration resolution engine.</li>
 *   <li>{@link org.aethercode.code.configuration.ConfigService} — process
 *       snapshot management and managed-config health.</li>
 *   <li>{@link org.aethercode.code.configuration.ConfigWriter} — atomic
 *       user-config writes.</li>
 *   <li>{@link org.aethercode.code.configuration.ThemeResolution} — theme
 *       preference resolution shared by the config chain and the UI.</li>
 * </ul>
 */
package org.aethercode.code.configuration;
