package org.aethercode.permission;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * a small allowlist / blocklist engine for shell commands.
 * Each rule is either an exact-match string, a prefix, or a regex.
 * The {@link #check(String)} method returns a {@link Decision} for
 * a given command.
 *
 * <p>Rule precedence: a block (deny) rule always wins over an allow
 * rule. This is the safe default — a more specific allow can't
 * accidentally expose a more general deny.
 */
public class CommandAllowlist {

    public enum Verdict { ALLOW, DENY }

    public record Decision(Verdict verdict, String reason) {
        public boolean allowed() { return verdict == Verdict.ALLOW; }
        public static Decision allow(String r) { return new Decision(Verdict.ALLOW, r); }
        public static Decision deny(String r)  { return new Decision(Verdict.DENY, r); }
    }

    public enum Kind { EXACT, PREFIX, REGEX }

    public record Rule(Kind kind, String pattern, String reason, boolean deny) {
        public Rule {
            if (kind == null) kind = Kind.EXACT;
            if (pattern == null) pattern = "";
            if (reason == null) reason = "";
        }
        public static Rule exact(String s) { return new Rule(Kind.EXACT, s, "", false); }
        public static Rule prefix(String s) { return new Rule(Kind.PREFIX, s, "", false); }
        public static Rule regex(String s) { return new Rule(Kind.REGEX, s, "", false); }
        public static Rule denyExact(String s) { return new Rule(Kind.EXACT, s, "", true); }
    }

    private final List<Rule> rules = new ArrayList<>();
    /** Default verdict when no rule matches. */
    private Verdict defaultVerdict = Verdict.DENY;

    public CommandAllowlist allow(Rule r) { if (r != null) rules.add(r); return this; }
    public CommandAllowlist deny(Rule r)  { if (r != null) rules.add(new Rule(r.kind(), r.pattern(), r.reason(), true)); return this; }

    public CommandAllowlist allowExact(String s)    { return allow(Rule.exact(s)); }
    public CommandAllowlist allowPrefix(String s)   { return allow(Rule.prefix(s)); }
    public CommandAllowlist allowRegex(String s)    { return allow(Rule.regex(s)); }
    public CommandAllowlist denyExact(String s)     { return deny(Rule.denyExact(s)); }

    public CommandAllowlist defaultVerdict(Verdict v) { this.defaultVerdict = v; return this; }

    public List<Rule> rules() { return List.copyOf(rules); }
    public Verdict defaultVerdict() { return defaultVerdict; }

    public Decision check(String command) {
        if (command == null || command.isEmpty()) {
            return Decision.deny("empty command");
        }
        // Check for any deny match first
        for (Rule r : rules) {
            if (r.deny() && matches(r, command)) {
                return Decision.deny(r.reason().isEmpty() ? "blocked by rule" : r.reason());
            }
        }
        // Then any allow match
        for (Rule r : rules) {
            if (!r.deny() && matches(r, command)) {
                return Decision.allow(r.reason().isEmpty() ? "allowed" : r.reason());
            }
        }
        // Fall back to default
        if (defaultVerdict == Verdict.ALLOW) {
            return Decision.allow("default allow");
        }
        return Decision.deny("default deny");
    }

    private static boolean matches(Rule r, String command) {
        return switch (r.kind()) {
            case EXACT  -> command.equals(r.pattern());
            case PREFIX -> command.startsWith(r.pattern());
            case REGEX  -> {
                try { yield Pattern.compile(r.pattern()).matcher(command).find(); }
                catch (PatternSyntaxException e) { yield false; }
            }
        };
    }

    public boolean isAllowed(String command) { return check(command).allowed(); }

    public int size() { return rules.size(); }

    public void clear() { rules.clear(); }

    /** a pre-baked safe list of common read-only commands. */
    public static CommandAllowlist safeDefaults() {
        return new CommandAllowlist()
                .allowExact("ls").allowExact("pwd").allowExact("echo")
                .allowExact("cat").allowExact("head").allowExact("tail")
                .allowPrefix("git status").allowPrefix("git log")
                .denyExact("rm -rf /").denyExact("shutdown").denyExact("reboot");
    }
}
