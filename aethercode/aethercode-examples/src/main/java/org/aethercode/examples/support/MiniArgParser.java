package org.aethercode.examples.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny argparse-style subcommand parser used by the better-harness
 * CLI. Mirrors the surface the Python port uses through
 * {@code argparse} but does not depend on a third-party library.
 *
 * <p>Subcommands declare their positionals and options up front; the
 * resulting {@link Parsed} object exposes them as
 * {@link Parsed#positional(int) positional(...)} and
 * {@link Parsed#option(String) option(...)} / {@link Parsed#flag(String)
 * flag(...)}.</p>
 */
public final class MiniArgParser {
    private final String description;
    private final Map<String, SubCommand> subcommands = new LinkedHashMap<>();

    public MiniArgParser(String description) {
        this.description = description;
    }

    public SubCommand addSubcommand(String name, String help) {
        SubCommand sc = new SubCommand(name, help);
        subcommands.put(name, sc);
        return sc;
    }

    public Parsed parse(String[] argv) {
        if (argv.length == 0) {
            throw new IllegalArgumentException(
                    "missing subcommand. Available: " + subcommands.keySet());
        }
        String commandName = argv[0];
        SubCommand sc = subcommands.get(commandName);
        if (sc == null) {
            throw new IllegalArgumentException("unknown subcommand: " + commandName);
        }
        return sc.parse(java.util.Arrays.copyOfRange(argv, 1, argv.length));
    }

    public String description() { return description; }

    /** A single subcommand's declaration. */
    public static final class SubCommand {
        private final String name;
        private final String help;
        private final List<String> positionalNames = new ArrayList<>();
        private final Map<String, String> optionDefaults = new LinkedHashMap<>();
        private final Map<String, Boolean> optionFlags = new LinkedHashMap<>();
        private final Map<String, Boolean> flagOptions = new LinkedHashMap<>();

        SubCommand(String name, String help) {
            this.name = name;
            this.help = help;
        }

        public SubCommand addPositional(String name, String help) {
            positionalNames.add(name);
            return this;
        }

        public SubCommand addOption(String name) {
            optionDefaults.put(name, null);
            return this;
        }

        public SubCommand addOption(String name, String defaultValue) {
            optionDefaults.put(name, defaultValue);
            return this;
        }

        public SubCommand addFlag(String name) {
            flagOptions.put(name, false);
            return this;
        }

        public String name() { return name; }
        public String help() { return help; }

        Parsed parse(String[] args) {
            Map<String, String> options = new LinkedHashMap<>(optionDefaults);
            Map<String, Boolean> flags = new LinkedHashMap<>(flagOptions);
            List<String> positional = new ArrayList<>();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (flagOptions.containsKey(arg)) {
                    flags.put(arg, true);
                    continue;
                }
                if (optionDefaults.containsKey(arg)) {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException(arg + " requires a value");
                    }
                    options.put(arg, args[++i]);
                    continue;
                }
                positional.add(arg);
            }
            if (positional.size() < positionalNames.size()) {
                throw new IllegalArgumentException(
                        name + " requires " + positionalNames
                                + " but got " + positional);
            }
            return new Parsed(name, positional, options, flags);
        }
    }

    /** A parsed subcommand invocation. */
    public static final class Parsed {
        private final String command;
        private final List<String> positional;
        private final Map<String, String> options;
        private final Map<String, Boolean> flags;

        Parsed(String command, List<String> positional,
               Map<String, String> options, Map<String, Boolean> flags) {
            this.command = command;
            this.positional = List.copyOf(positional);
            this.options = Map.copyOf(options);
            this.flags = Map.copyOf(flags);
        }

        public String command() { return command; }
        public String positional(int i) { return positional.get(i); }
        public List<String> positionals() { return positional; }
        public String option(String name) { return options.get(name); }
        public String optionOrDefault(String name, String fallback) {
            String v = options.get(name);
            return v == null ? fallback : v;
        }
        public boolean flag(String name) {
            return flags.getOrDefault(name, false);
        }
    }
}
