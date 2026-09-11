/**
 * Prompt / rendering helpers for the REPL and PTC system prompts. 1:1
 * port of the Python
 * <code>langchain_quickjs._prompt</code> module. The single
 * {@link org.aethercode.partner.quickjs.prompt.ReplPrompt} class
 * exposes the {@code render_repl_system_prompt},
 * {@code render_subagent_system_prompt},
 * {@code render_eval_tool_code_doc},
 * {@code render_eval_tool_description}, and {@code render_ptc_prompt}
 * functions, plus the supporting case-conversion and JSON-Schema
 * &rarr; TS type renderers.
 */
package org.aethercode.partner.quickjs.prompt;
