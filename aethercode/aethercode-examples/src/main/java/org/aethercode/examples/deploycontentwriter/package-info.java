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
