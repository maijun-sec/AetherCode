package org.aethercode.tools;

import org.aethercode.core.tool.Tool;
import org.aethercode.tools.edit.NotebookEditTool;
import org.aethercode.tools.file.FileEditTool;
import org.aethercode.tools.file.FileReadTool;
import org.aethercode.tools.file.FileWriteTool;
import org.aethercode.tools.file.GlobTool;
import org.aethercode.tools.file.GrepTool;
import org.aethercode.tools.interactive.AskUserQuestionTool;
import org.aethercode.tools.net.ArxivFetchTool;
import org.aethercode.tools.net.ScholarSearchTool;
import org.aethercode.tools.net.WebFetchTool;
import org.aethercode.tools.net.WebSearchTool;
import org.aethercode.tools.shell.BashTool;
import org.aethercode.tools.task.AgentTool;
import org.aethercode.tools.task.CreateAgentTool;
import org.aethercode.tools.task.DeleteAgentTool;
import org.aethercode.tools.task.ListAgentsTool;
import org.aethercode.tools.task.SubagentCancelTool;
import org.aethercode.tools.task.SubagentListTool;
import org.aethercode.tools.task.SubagentRetryTool;
import org.aethercode.tools.task.SubagentStatusTool;
import org.aethercode.tools.task.SubTodoWriteTool;
import org.aethercode.tools.task.TodoWriteTool;
import org.aethercode.tools.task.UpdateAgentTool;
import org.aethercode.tools.task.SubTodoWriteTool;
import org.aethercode.tools.task.SubagentListTool;
import org.aethercode.tools.task.SubagentStatusTool;
import org.aethercode.tools.task.TodoWriteTool;

import java.util.List;

/**
 * The default tool pool. Modelled after the TS {@code getAllBaseTools()} — every tool ships
 * with the engine; callers can disable or add via the registry.
 *
 * <h2>Paper-compat tools</h2>
 * The 8 paper-compat tools (architecture / saturation / redflag /
 * byzantine / voting / plan) live in {@code aethercode-orchestration}
 * and are NOT exposed here — that would create a module cycle
 * (tools → orchestration → protocol → sdk → tools). Callers that want
 * the full set (e.g. the daemon) build the pool themselves: start
 * from {@link #all()}, then add {@code new PaperCompatTools().buildAll()}.
 * See {@code org.aethercode.cli.Main.buildEngineForSession}.
 */
public final class StandardTools {

    private StandardTools() {}

    public static List<Tool> all() {
        return List.of(
                FileReadTool.build(),
                FileWriteTool.build(),
                FileEditTool.build(),
                GlobTool.build(),
                GrepTool.build(),
                BashTool.build(),
                TodoWriteTool.build(),
                SubTodoWriteTool.build(),
                AgentTool.build(),
                // R362: let the primary list available agents
                // so it can discover agents beyond the bounded
                // <available_agents> system-prompt block. The
                // AgentTool's agent_name parameter takes a name
                // from this list (or from SubagentRole fallback).
                ListAgentsTool.build(),
                // R362 Round 2: agent CRUD trio. These
                // tools let the LLM write to the same
                // ~/.aethercode/agents/ directory the
                // desktop's AgentManager modal manages.
                // Default mode is permissive; flip
                // AETHERCODE_CREATE_AGENT_REQUIRE_CONFIRM=1
                // for strict (must-ask-user-first) mode.
                CreateAgentTool.build(),
                UpdateAgentTool.build(),
                DeleteAgentTool.build(),
                // background subagent introspection. The
                // model uses subagent_status(job_id) to poll
                // a backgrounded spawn_agent, and subagent_list
                // to see all known jobs. Without these, the
                // background mode is a write-only black box.
                SubagentStatusTool.build(),
                SubagentListTool.build(),
                // R362 Round 3: lifecycle control. The
                // model uses subagent_retry(job_id) to
                // re-run a FAILED or CANCELLED background
                // job (the registry resets state +
                // restarts a fresh worker thread) and
                // subagent_cancel(job_id, reason?) to
                // interrupt a still-running job. Both
                // complement the JSON-RPC handlers the
                // desktop SubagentPanel uses.
                SubagentRetryTool.build(),
                SubagentCancelTool.build(),
                WebFetchTool.build(),
                WebSearchTool.build(),
                ScholarSearchTool.build(),
                ArxivFetchTool.build(),
                NotebookEditTool.build(),
                AskUserQuestionTool.build()
        );
    }
}
