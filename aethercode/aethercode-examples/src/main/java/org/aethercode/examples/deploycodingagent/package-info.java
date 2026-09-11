/**
 * Java port of {@code deepagents-main/examples/deploy-coding-agent/skills/code-review/lint_check.py}.
 *
 * <p>Single file:
 * <ul>
 *   <li>{@link org.aethercode.examples.deploycodingagent.LintCheck}
 *       &mdash; static analyzer for Python source files that flags
 *       missing module docstrings, oversized functions, and bare
 *       {@code except:} clauses. The port is a self-contained Java
 *       implementation of the same checks &mdash; the target language
 *       being analyzed stays Python; the analyzer is written in Java.</li>
 * </ul>
 */
package org.aethercode.examples.deploycodingagent;
