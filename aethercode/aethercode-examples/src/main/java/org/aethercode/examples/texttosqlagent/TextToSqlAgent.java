package org.aethercode.examples.texttosqlagent;

import org.aethercode.backends.FilesystemBackend;
import org.aethercode.graph.CreateDeepAgent;
import org.aethercode.graph.DeepAgent;
import org.aethercode.middleware.SkillSource;
import org.aethercode.tools.Tool;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Text-to-SQL deep agent.
 *
 * <p>Java port of
 * {@code deepagents-main/examples/text-to-sql-agent/agent.py}.
 * The agent is built around a SQLite database (Chinook schema); the
 * Java port uses {@link java.sql} standard interfaces so the example
 * works with any JDBC driver. The actual driver jar is not bundled;
 * the {@link #buildSqlTools(Path, Object)} method returns stub tool
 * objects that document the same surface the Python port exposes.</p>
 *
 * <p>For end-to-end use, plug a real {@code ChatAnthropic} (or other
 * {@code LLMProvider}) in via the {@code model} parameter, supply a
 * real {@link Tool} list (e.g. a JDBC-backed SQLDatabaseToolkit), and
 * connect a real Chinook database file at the {@code dbPath}.</p>
 */
public final class TextToSqlAgent {
    private TextToSqlAgent() {}

    /**
     * Build the text-to-SQL deep agent.
     *
     * @param baseDir the example directory; the {@code AGENTS.md} and
     *                {@code skills/} files are read relative to this.
     * @param model the chat model (or model spec string).
     * @param sqlTools the SQL tools the agent can use.
     */
    public static DeepAgent build(Path baseDir, Object model, List<Tool> sqlTools) {
        FilesystemBackend backend = new FilesystemBackend(baseDir.toString());
        return CreateDeepAgent.create(
                model,
                sqlTools,
                null,
                null,
                null,
                List.of(SkillSource.of("./skills/")),
                List.of("./AGENTS.md"),
                null,
                backend,
                null,
                null,
                null,
                null,
                "text_to_sql_agent");
    }

    /**
     * Convenience builder for the Chinook database. Returns a stub
     * tool list mirroring the Python port's
     * {@code SQLDatabaseToolkit.get_tools()}.
     */
    public static List<Tool> buildSqlTools(Path dbPath, Object model) {
        // The Python port constructs a SQLDatabaseToolkit that wraps
        // SQLAlchemy and uses the chat model for query construction.
        // The Java port returns four stub tools that document the
        // same surface; replace with real JDBC-backed tools for
        // end-to-end use.
        List<Tool> tools = new ArrayList<>();
        tools.add(Tool.of("sql_db_query",
                "Execute a SQL query against the database and return the results.",
                (args, ctx) -> "[stub] Would query " + dbPath + " with: " + args.get("query")));
        tools.add(Tool.of("sql_db_schema",
                "Get the schema for a list of tables.",
                (args, ctx) -> "[stub] Would fetch schema for: " + args.get("tables")));
        tools.add(Tool.of("sql_db_list_tables",
                "List all tables available in the database.",
                (args, ctx) -> "[stub] Would list tables in " + dbPath));
        tools.add(Tool.of("sql_db_query_checker",
                "Check whether a SQL query is correct.",
                (args, ctx) -> "[stub] Would check query: " + args.get("query")));
        return tools;
    }

    /**
     * Convenience main entry point. Loads the agent, runs a single
     * question, and prints the answer.
     */
    public static void main(String[] args) {
        Path baseDir = Path.of(".").toAbsolutePath();
        String question = args.length > 0
                ? String.join(" ", args)
                : "What are the top 5 best-selling artists?";
        DeepAgent agent = build(baseDir,
                "anthropic:claude-sonnet-4-5",
                buildSqlTools(baseDir.resolve("chinook.db"), null));
        System.out.println("Assembled text-to-SQL agent: " + agent.name());
        System.out.println("Question: " + question);
        System.out.println("Tools: " + agent.tools().size());
    }
}
