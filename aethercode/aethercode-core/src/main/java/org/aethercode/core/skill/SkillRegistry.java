package org.aethercode.core.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * file-system backed registry of AetherCode / project SKILL.md files.
 *
 * <p>Two scan roots are supported:
 * <ol>
 *   <li><b>User skills</b> — {@code ~/.aethercode/skills/<name>/SKILL.md}
 *       (the AetherCode-managed global pool; R210 — was
 *       {@code ~/.minimax/skills/} legacy, renamed to align with
 *       the project-tier {@code <cwd>/.aethercode/skills/} name and
 *       with the existing {@code ~/.aethercode/mcp.json} location).
 *       When present, a user's skill is shadowed by a project-local
 *       copy with the same name.</li>
 *   <li><b>Project skills</b> — {@code <cwd>/.aethercode/skills/<name>/SKILL.md}.
 *       Project skills win on name collisions; the project takes priority
 *       because it's the work the user is doing right now.</li>
 * </ol>
 *
 * <p>The registry caches the parsed metadata + body in memory and
 * re-reads the directories on a configurable interval (10s by
 * default). The {@link #reload()} method is also exposed so the
 * daemon's {@code reloadSkills} RPC can force a re-scan — useful
 * when the user adds a new skill and wants it available
 * immediately.
 *
 * <p>Threading: the registry is safe to call from any thread.
 * Reads are lock-free; {@link #reload()} is the only writer and
 * uses a generation counter so an in-flight {@link #list()} sees
 * a consistent snapshot.
 */
public final class SkillRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(SkillRegistry.class);

    /** One parsed SKILL.md. {@code source} is {@code "project"} or
     *  {@code "user"} so the UI can dim the secondary tier. */
    public record SkillMeta(
            String name,
            String description,
            String descriptionZhHans,
            String displayName,
            String displayNameZhHans,
            String source,
            Path path,
            long lastModifiedMs
    ) {
        /** Best-effort "what to show in the UI" for this skill. */
        public String displayLabel() {
            if (displayNameZhHans != null && !displayNameZhHans.isEmpty()) return displayNameZhHans;
            if (displayName != null && !displayName.isEmpty()) return displayName;
            return name;
        }

        /** Best-effort description for the UI; falls back to the
         *  English description. */
        public String displayDescription() {
            if (descriptionZhHans != null && !descriptionZhHans.isEmpty()) return descriptionZhHans;
            if (description != null && !description.isEmpty()) return description;
            return "(no description)";
        }
    }

    /** Scan roots. Project wins on name collision. */
    private final List<Path> projectRoots;
    private final List<Path> userRoots;
    private final long reloadIntervalMs;
    private final AtomicLong lastReloadAt = new AtomicLong(0);
    private volatile long currentGeneration = 0;

    /** name -> (meta, body). The map is replaced atomically on
     *  {@link #reload()}; readers see either the old or the new
     *  map, never a half-built one. */
    private volatile Map<String, Entry> byName = Map.of();
    /** when true, {@link #reload()} skips the body
     *  parse (saves ~5KB / skill). The body is loaded
     *  on demand by {@link #getBody(String)}. */
    private final boolean lazy;
    /** per-skill body cache. Populated lazily by
     *  {@link #getBody(String)} when {@link #lazy} is true;
     *  also populated eagerly when {@link #lazy} is false
     *  (the body lives both here AND inside the
     *  {@link Entry} for backward compatibility). Cleared
     *  on {@link #reload()} so a re-parsed file's body
     *  replaces the old one. */
    private final java.util.concurrent.ConcurrentHashMap<String, String> bodyCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    public SkillRegistry(List<Path> projectRoots, List<Path> userRoots, Duration reloadInterval) {
        this(projectRoots, userRoots, reloadInterval, false);
    }

    /** lazy-body constructor. When {@code lazy} is
     *  true, {@link #reload()} reads only the metadata
     *  (name + description + path + mtime); the body is
     *  loaded on demand by {@link #getBody(String)}. The
     *  system-prompt injection only needs the metadata so
     *  the 200KB+ of always-loaded body text drops to a
     *  few KB until the model actually picks a skill.
     *  Default false preserves the legacy behaviour
     *  (load body eagerly) for callers that haven't been
     *  updated. */
    public SkillRegistry(List<Path> projectRoots, List<Path> userRoots,
                          Duration reloadInterval, boolean lazy) {
        this.projectRoots = projectRoots == null ? List.of() : List.copyOf(projectRoots);
        this.userRoots = userRoots == null ? List.of() : List.copyOf(userRoots);
        this.reloadIntervalMs = reloadInterval == null ? 10_000L : reloadInterval.toMillis();
        this.lazy = lazy;
        // Best-effort initial load; the first list() will retry.
        try { reload(); } catch (Exception e) { LOG.warn("initial skill load failed: {}", e.getMessage()); }
    }

    /** Skill names, sorted alphabetically. The "project" tier
     *  wins on name collision, so a user who copies a global
     *  skill into {@code .aethercode/skills/} sees their copy. */
    public List<SkillMeta> list() {
        ensureFresh();
        return byName.values().stream()
                .map(e -> e.meta)
                .sorted(Comparator.comparing(SkillMeta::name, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());
    }

    public Optional<String> getBody(String name) {
        ensureFresh();
        Entry e = byName.get(name);
        if (e == null) return Optional.empty();
        // lazy body. If we didn't load the body during
        // reload() (skill registry was constructed with
        // lazy=true), read it from disk now and cache it in
        // the body cache. The cache is separate from the
        // byName map (which is replaced atomically on
        // reload) so a concurrent reload() doesn't lose a
        // freshly-loaded body.
        if (e.body != null) return Optional.of(e.body);
        if (e.meta.path == null) return Optional.empty();
        // Double-checked: another thread may have loaded it
        // while we were parsing.
        String cached = bodyCache.get(name);
        if (cached != null) return Optional.of(cached);
        try {
            String content = Files.readString(e.meta.path);
            Frontmatter.Parsed p = Frontmatter.parse(content);
            bodyCache.put(name, p.body());
            return Optional.of(p.body());
        } catch (IOException ioe) {
            LOG.debug("lazy body read failed for {}: {}", name, ioe.getMessage());
            return Optional.empty();
        }
    }

    /** Force a re-scan. Returns the new skill count. */
    public synchronized int reload() {
        Map<String, Entry> next = new java.util.LinkedHashMap<>();
        // Project tier first so it wins on collision.
        for (Path root : projectRoots) addRoot(root, "project", next);
        for (Path root : userRoots) addRoot(root, "user", next);
        this.byName = Map.copyOf(next);
        // clear the lazy body cache so any re-parsed
        // skill's body is re-read on the next getBody().
        // Eagerly-loaded bodies live in the Entry itself
        // and survive the reload; the bodyCache only
        // holds lazy entries.
        if (lazy) bodyCache.clear();
        long now = System.currentTimeMillis();
        this.lastReloadAt.set(now);
        this.currentGeneration++;
        LOG.info("skill registry reloaded: {} skill(s) from {} project + {} user roots (lazy={})",
                next.size(), projectRoots.size(), userRoots.size(), lazy);
        return next.size();
    }

    /** scope tag for {@link #addSkill(String, Scope, String)}.
     *  The "global" tier writes to the FIRST entry in {@link #userRoots}
     *  (typically {@code ~/.aethercode/skills/<name>/SKILL.md});
     *  "project" writes to the FIRST entry in {@link #projectRoots}
     *  (typically {@code <cwd>/.aethercode/skills/<name>/SKILL.md}).
     *  If the target root list is empty the call returns {@code false}
     *  with no filesystem mutation — the caller (e.g. an RPC
     *  without a configured cwd) can surface the error. */
    public enum Scope { GLOBAL, PROJECT }

    /**
     * install a SKILL.md into a project- or user-tier root,
     * then {@link #reload()}. The body is written as a single
     * {@code <name>/SKILL.md} file with the supplied frontmatter
     * + body. After the write returns, {@link #reload()} re-scans
     * the directories so the new skill is visible to
     * {@link #list()} / {@link #getBody(String)} on the very
     * next call. A watch on the user / project root (set up by
     * {@code DaemonRunner.buildRegistryReloadService}) will
     * also fire, but the explicit reload here closes the gap
     * between the write returning and the watcher's debounce.
     *
     * <p>Security note: the name is constrained to a filesystem-safe
     * character set — anything that would let a name climb out of
     * its target directory (e.g. {@code ../}, {@code C:\foo},
     * leading slashes) is rejected with a {@code false} return. The
     * body is written as UTF-8 with the supplied content verbatim;
     * the caller is responsible for keeping the frontmatter
     * parseable.
     */
    public synchronized boolean addSkill(String name, Scope scope, String body) {
        if (name == null || name.isBlank() || body == null) return false;
        // name sanity. The first character must be a
        // letter or digit (so `.`, `..`, `-.foo`, `_bar` all
        // fail); the rest allows letters / digits / `.` /
        // `_` / `-`. This rejects:
        //   - `..` and `.` (would climb out of the root)
        //   - any leading dot (hidden files / ".." tricks)
        //   - any leading slash or drive letter (absolute paths)
        //   - any whitespace or shell-meta (the second
        //     line of defence; callers are expected to also
        //     validate).
        if (!name.matches("^[A-Za-z0-9][A-Za-z0-9._-]*$")) {
            LOG.warn("addSkill: refusing unsafe name {}", name);
            return false;
        }
        java.util.List<Path> roots = (scope == Scope.GLOBAL ? userRoots : projectRoots);
        if (roots.isEmpty()) {
            LOG.warn("addSkill: no {} root configured (scope={})",
                    scope == Scope.GLOBAL ? "user" : "project", scope);
            return false;
        }
        Path root = roots.get(0);
        Path dir = root.resolve(name);
        Path file = dir.resolve("SKILL.md");
        try {
            Files.createDirectories(dir);
            Files.writeString(file, body);
        } catch (IOException ioe) {
            LOG.warn("addSkill: failed to write {}: {}", file, ioe.getMessage());
            return false;
        }
        reload();
        LOG.info("addSkill: wrote {} (scope={}, total={})",
                file, scope, byName.size());
        return true;
    }

    /** When was the registry last (re)loaded. The daemon's
     *  {@code listSkills} RPC surfaces this so the UI can show
     *  a "stale" badge after a long idle. */
    public long lastReloadMs() { return lastReloadAt.get(); }

    /** Generation counter — increments on every successful
     *  {@link #reload()}. Useful for tests. */
    public long generation() { return currentGeneration; }

    /** Render the {@code <available_skills>} XML block for
     *  system-prompt injection. The shape mirrors Claude Code's
     *  convention (opening tag, one {@code <skill>} per skill,
     *  closing tag) so models trained on that convention parse
     *  it correctly. Returns the empty string when no skills
     *  are available, so callers can {@code prepend} the result
     *  unconditionally. */
    public String renderSystemPromptBlock() {
        List<SkillMeta> all = list();
        if (all.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n<available_skills>\n");
        for (SkillMeta m : all) {
            sb.append("<skill>\n");
            sb.append("<name>").append(escapeXml(m.name())).append("</name>\n");
            String desc = m.displayDescription();
            // Truncate very long descriptions so the system prompt
            // doesn't bloat; the full text is in getBody().
            if (desc.length() > 240) desc = desc.substring(0, 237) + "...";
            sb.append("<description>").append(escapeXml(desc)).append("</description>\n");
            sb.append("</skill>\n");
        }
        sb.append("</available_skills>\n");
        return sb.toString();
    }

    // ---- internals -------------------------------------------------

    private void ensureFresh() {
        long now = System.currentTimeMillis();
        if (now - lastReloadAt.get() > reloadIntervalMs) {
            try { reload(); }
            catch (Exception e) { LOG.debug("background skill reload failed: {}", e.getMessage()); }
        }
    }

    private void addRoot(Path root, String source, Map<String, Entry> out) {
        if (root == null || !Files.isDirectory(root)) return;
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root)) {
            for (Path d : dirs) {
                if (!Files.isDirectory(d)) continue;
                Path skill = d.resolve("SKILL.md");
                if (!Files.isRegularFile(skill)) continue;
                try {
                    String content = Files.readString(skill);
                    Frontmatter.Parsed p = Frontmatter.parse(content);
                    Map<String, Object> f = p.fields();
                    String name = Frontmatter.string(f, "name");
                    if (name == null || name.isBlank()) {
                        // Fall back to the directory name.
                        name = d.getFileName().toString();
                    }
                    if (out.containsKey(name)) {
                        // Earlier tier (project) already won; skip this one.
                        continue;
                    }
                    String desc = Frontmatter.string(f, "description");
                    String descZh = Frontmatter.localized(f, "descriptions", null);
                    String dn = Frontmatter.string(f, "displayName");
                    String dnZh = Frontmatter.localized(f, "displayNames", null);
                    long lm = Files.getLastModifiedTime(skill).toMillis();
                    SkillMeta meta = new SkillMeta(
                            name, desc == null ? "" : desc,
                            descZh == null ? "" : descZh,
                            dn == null ? "" : dn,
                            dnZh == null ? "" : dnZh,
                            source, skill, lm);
                    // when lazy=true, skip the body
                    // parse. getBody() will re-parse the
                    // file on the first request and cache
                    // the result.
                    String body = lazy ? null : p.body();
                    out.put(name, new Entry(meta, body));
                } catch (IOException e) {
                    LOG.warn("skipping skill {}: {}", skill, e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.debug("scan root {} not readable: {}", root, e.getMessage());
        }
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private record Entry(SkillMeta meta, String body) {}
}
