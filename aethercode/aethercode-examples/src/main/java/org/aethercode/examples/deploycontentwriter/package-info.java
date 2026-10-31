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
 * Java port of {@code deepagents-main/examples/deploy-content-writer/test_user_memory.py}.
 *
 * <p>Single file:
 * <ul>
 *   <li>{@link org.aethercode.examples.deploycontentwriter.UserMemoryTest}
 *       &mdash; a JUnit 5 test that exercises the user-memory
 *       persistence path of a deployed deep agent. The Java port is
 *       illustrative: it shows the same four-thread script (set
 *       preference, verify same user, verify different user, verify no
 *       user) using JUnit's {@code @Test} methods against a stub
 *       server, not the real deployed LangGraph endpoint.</li>
 * </ul>
 */
package org.aethercode.examples.deploycontentwriter;
