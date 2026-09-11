package org.aethercode.code.skills;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Trust store for skill directories.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.skills.trust} module. Persists in-the-moment
 * approvals that extend the declarative
 * {@code skills.extra_allowed_dirs} list. The Java port is a thin
 * façade; the canonical state lives on disk.</p>
 */
public final class SkillTrust {
    private static final Logger LOGGER = Logger.getLogger(SkillTrust.class.getName());

    private SkillTrust() {}

    /** Default trust-store file name. */
    public static final String TRUST_FILE = "trusted_skill_dirs.json";

    /**
     * Return the set of approved skill directories.
     */
    public static List<Path> loadTrustedSkillDirs() {
        Path file = trustFile();
        if (!Files.isRegularFile(file)) return List.of();
        try {
            String text = Files.readString(file);
            Object decoded = org.aethercode.code.plugins.PluginMiniJson.parse(text);
            if (!(decoded instanceof List<?> list)) return List.of();
            return list.stream()
                    .filter(o -> o instanceof String)
                    .map(o -> Path.of((String) o))
                    .toList();
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not load trusted skill dirs", e);
            return List.of();
        }
    }

    /**
     * Persist a new set of approved skill directories.
     */
    public static void saveTrustedSkillDirs(List<Path> dirs) {
        Path file = trustFile();
        try {
            Files.createDirectories(file.getParent());
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < dirs.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(dirs.get(i).toString().replace("\\", "\\\\"))
                        .append('"');
            }
            sb.append(']');
            Files.writeString(file, sb.toString());
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not save trusted skill dirs", e);
        }
    }

    /** Add a directory to the trust list. */
    public static void trust(Path dir) {
        List<Path> current = loadTrustedSkillDirs();
        if (current.contains(dir)) return;
        java.util.List<Path> next = new java.util.ArrayList<>(current);
        next.add(dir);
        saveTrustedSkillDirs(next);
    }

    /** Remove a directory from the trust list. */
    public static void untrust(Path dir) {
        List<Path> current = loadTrustedSkillDirs();
        if (!current.contains(dir)) return;
        java.util.List<Path> next = new java.util.ArrayList<>(current);
        next.remove(dir);
        saveTrustedSkillDirs(next);
    }

    private static Path trustFile() {
        String home = System.getProperty("user.home");
        return Path.of(home, ".deepagents", TRUST_FILE);
    }
}
