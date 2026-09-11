package org.aethercode.core.middleware;

/**
 * Constants and prompts for the skills middleware.
 *
 * <p>Java-native port of the constants and prompt templates at the
 * top of {@code deepagents.middleware.skills}. The strings mirror
 * the Python port one-for-one so the model receives the same
 * guidance.</p>
 */
public final class SkillsPrompts {
    private SkillsPrompts() {}

    /** Maximum size for SKILL.md files to prevent DoS attacks. */
    public static final int MAX_SKILL_FILE_SIZE = 10 * 1024 * 1024;

    /** Cap on the number of skill load warnings surfaced to the model. */
    public static final int MAX_SKILLS_LOAD_WARNINGS = 20;

    /** Maximum length of any single skill load warning. */
    public static final int MAX_SKILL_LOAD_WARNING_LENGTH = 1000;

    public static final String SKILL_LOAD_WARNING_TRUNCATION_SUFFIX = "... [truncated]";

    /** Agent Skills spec constraint: skill name max length. */
    public static final int MAX_SKILL_NAME_LENGTH = 64;
    /** Agent Skills spec constraint: skill description max length. */
    public static final int MAX_SKILL_DESCRIPTION_LENGTH = 1024;
    /** Agent Skills spec constraint: skill compatibility max length. */
    public static final int MAX_SKILL_COMPATIBILITY_LENGTH = 500;

    /** State-extension keys the middleware reads/writes. */
    public static final String SKILLS_METADATA_KEY = "skills_metadata";
    public static final String SKILLS_LOAD_ERRORS_KEY = "skills_load_errors";

    /** Default system-prompt template. */
    public static final String SKILLS_SYSTEM_PROMPT = """
            ## Skills System

            You have access to a skills library that provides specialized capabilities and domain knowledge.

            {skills_locations}{skills_load_warnings}

            Sources labeled "Deepagents" are specific to this agent tool; sources labeled "Agents" are shared across all agent tools on this machine.

            **Available Skills:**

            {skills_list}

            **How to Use Skills (Progressive Disclosure):**

            Skills follow a **progressive disclosure** pattern - you see their name and description above, but only read full instructions when needed:

            1. **Recognize when a skill applies**: Check if the user's task matches a skill's description
            2. **Read the skill's full instructions**: Use `read_file` on the path shown in the skill list above.
                Pass `limit=1000` since the default of 100 lines is too small for most skill files.
            3. **Follow the skill's instructions**: SKILL.md contains step-by-step workflows, best practices, and examples
            4. **Access supporting files**: Skills may include helper scripts, configs, or reference docs - use absolute paths

            **When to Use Skills:**

            - User's request matches a skill's domain (e.g., "research X" -> web-research skill)
            - You need specialized knowledge or structured workflows
            - A skill provides proven patterns for complex tasks

            **Executing Skill Scripts:**
            Skills may contain Python scripts or other executable files. Always use absolute paths from the skill list.

            **Example Workflow:**

            User: "Can you research the latest developments in quantum computing?"

            1. Check available skills -> See "web-research" skill with its path
            2. Read the full skill file: `read_file(file_path="...", limit=1000)`
            3. Follow the skill's research workflow (search -> organize -> synthesize)
            4. Use any helper scripts with absolute paths

            Remember: Skills make you more capable and consistent. When in doubt, check if a skill exists for the task!""";
}
