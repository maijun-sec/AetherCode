package org.aethercode.core.scope;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ R416: THE BOUNDARY MUST SEE THE PATHS THE SHELL WILL SEE ★
 *
 * <p>★ WHAT THIS IS FOR ★ the shell's scope check matches the paths in the
 * command TEXT. The shell, however, expands the environment references in
 * that text before it runs anything — so a command that names
 * {@code %SCENARIOS_ROOT%\L1\escape.txt} writes to an absolute path OUTSIDE
 * the tree while the text the checker sees carries no absolute path at all.
 *
 * <p>★ MEASURED, NOT ASSUMED ★ cmd.exe leaves an UNDEFINED variable's text
 * alone ({@code echo %UNDEF_XYZ%} prints {@code %UNDEF_XYZ%} — measured on
 * this machine), so an undefined reference is a literal file name INSIDE the
 * tree and needs no refusal: the two forms are not equally dangerous, and a
 * rule that treats them as equally dangerous refuses harmless commands.
 * /bin/sh expands an undefined variable to nothing, which can leave a
 * root-relative path — and the absolute-path rule already catches that.
 *
 * <p>★ AND A VARIABLE SET IN THE SAME COMMAND ★ cmd.exe expands the whole
 * line BEFORE it runs any part of it, so {@code set X=1 & echo %X%} prints
 * {@code %X%} (measured) ⇒ a same-line assignment cannot smuggle a path
 * past the boundary on Windows, and the Windows path needs no overlay.
 * /bin/sh DOES expand it, so the POSIX path collects the assignments.
 *
 * <p>★ WHAT IS NOT HERE ★ the shell still has no READ boundary (a read-only
 * command may look wherever it likes — that is R415's policy, and closing it
 * needs an OS-level sandbox). This class exists so that the WRITE boundary
 * sees the same paths the shell will act on.
 */
public final class ScopeEnvExpansion {

    /** A Windows environment reference: {@code %NAME%}. */
    private static final Pattern WIN_VAR =
            Pattern.compile("%([A-Za-z_][A-Za-z0-9_]*)%");

    /**
     * A POSIX environment reference: {@code $NAME}, {@code ${NAME}}, or
     * {@code ${NAME:-default}}.
     *
     * <p>The third form is there because {@code ${NAME:-default}} is a
     * PATH in the shell's eyes: sh resolves it to the default, and the
     * boundary must not see the raw text instead.
     */
    private static final Pattern POSIX_VAR =
            Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*):-([^}]*)\\}"
                    + "|\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}"
                    + "|\\$([A-Za-z_][A-Za-z0-9_]*)");

    /**
     * A POSIX assignment inside the command itself.
     *
     * <p>group(1) = the name, group(2) = the value. The prefix is
     * deliberately narrow: an assignment at the start of a statement
     * ({@code FOO=1 cmd}), after a separator ({@code ; FOO=1}), or after
     * {@code export}. A word like {@code -Dfoo=bar} or a query string
     * {@code ?a=b} is not an assignment and must not be read as one.
     */
    private static final Pattern POSIX_ASSIGN =
            Pattern.compile("(?m)(?:^|[;&|]\\s*|\\bexport\\s+)([A-Za-z_][A-Za-z0-9_]*)=([^\\s;&|]+)");

    /** A leading {@code ~} — the home directory, on POSIX only. */
    private static final Pattern TILDE =
            Pattern.compile("(?<![\\w~])~(?=[/\\\\]|$)");

    private ScopeEnvExpansion() {
    }

    /**
     * The result of an expansion.
     *
     * @param text     the command with the environment references resolved
     * @param expanded the variables that were resolved, name → value, so a
     *                 refusal can say WHICH variable produced the path
     */
    public record Result(String text, Map<String, String> expanded) {
    }

    /**
     * Expand the environment references in a command.
     *
     * @param command the command as the model wrote it
     * @param env     the environment the shell will run in
     * @param windows whether the shell is cmd.exe (Windows) or /bin/sh
     */
    public static Result expand(String command, Map<String, String> env, boolean windows) {
        if (command == null || command.isEmpty()) {
            return new Result(command == null ? "" : command, Map.of());
        }
        Map<String, String> effective = new LinkedHashMap<>();
        if (env != null) effective.putAll(env);
        Map<String, String> sources = new LinkedHashMap<>();

        String text = command;
        if (!windows) {
            // ★ THE COMMAND'S OWN ASSIGNMENTS ★ on POSIX an assignment
            // applies to the rest of the command line, so the boundary has
            // to see it the way sh does.
            Matcher asg = POSIX_ASSIGN.matcher(text);
            while (asg.find()) {
                effective.put(asg.group(1), unquote(asg.group(2)));
            }
            String home = effective.get("HOME");
            if (home != null && !home.isBlank()) {
                text = TILDE.matcher(text)
                        .replaceAll(Matcher.quoteReplacement(home));
            }
        }

        if (windows) {
            Matcher m = WIN_VAR.matcher(text);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String name = m.group(1);
                String value = effective.get(name);
                // ★ AN UNDEFINED VARIABLE IS LEFT ALONE ★ cmd.exe keeps the
                // text, so the shell acts on a literal name inside the tree.
                String replacement = (value == null)
                        ? Matcher.quoteReplacement(m.group(0))
                        : Matcher.quoteReplacement(value);
                if (value != null) sources.put(name, value);
                m.appendReplacement(sb, replacement);
            }
            m.appendTail(sb);
            return new Result(sb.toString(), sources);
        }

        Matcher m = POSIX_VAR.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String name = m.group(1) != null ? m.group(1)
                    : (m.group(3) != null ? m.group(3) : m.group(4));
            String value = effective.get(name);
            String replacement;
            if (value == null && m.group(2) != null) {
                // ${NAME:-default} — the default stands in for an unset NAME
                value = m.group(2);
            }
            if (value == null) {
                // ★ sh EXPANDS AN UNSET VARIABLE TO NOTHING ★ and what is
                // left may be a root-relative path, which the absolute-path
                // rule then sees.
                replacement = "";
            } else {
                sources.put(name, value);
                replacement = Matcher.quoteReplacement(value);
            }
            m.appendReplacement(sb, replacement);
        }
        m.appendTail(sb);
        return new Result(sb.toString(), sources);
    }

    /** Strip the quotes a POSIX assignment may carry. */
    private static String unquote(String value) {
        if (value == null) return null;
        String v = value;
        if (v.length() >= 2
                && ((v.startsWith("\"") && v.endsWith("\""))
                        || (v.startsWith("'") && v.endsWith("'")))) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }
}
