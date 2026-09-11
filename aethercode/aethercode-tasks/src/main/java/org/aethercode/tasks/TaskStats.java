package org.aethercode.tasks;

import java.util.Collection;

/**
 * aggregate statistics over a {@link TaskRegistry}'s tasks.
 * Useful for long-running task execution — the user can see how
 * many tasks are pending, running, or have failed.
 *
 * <p>Symmetric with {@code PlanStats} in the SDK. Pure data record;
 * compute via {@link #from(Collection)}, {@link #from(TaskRegistry)},
 * or {@link #merge(java.util.List)}.
 */
public record TaskStats(
        int total,
        int pending,
        int running,
        int completed,
        int failed,
        int killed,
        int rootTasks,
        long oldestPendingAgeMs
) {

    public static TaskStats from(TaskRegistry registry) {
        if (registry == null) return new TaskStats(0, 0, 0, 0, 0, 0, 0, 0L);
        return from(registry.list());
    }

    public static TaskStats from(Collection<Task> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return new TaskStats(0, 0, 0, 0, 0, 0, 0, 0L);
        }
        int total = 0, pending = 0, running = 0, completed = 0, failed = 0, killed = 0, roots = 0;
        long now = System.currentTimeMillis();
        long oldestPendingAge = 0L;
        for (Task t : tasks) {
            total++;
            switch (t.status()) {
                case PENDING   -> {
                    pending++;
                    long age = now - t.createdAtMs();
                    if (age > oldestPendingAge) oldestPendingAge = age;
                }
                case RUNNING   -> running++;
                case COMPLETED -> completed++;
                case FAILED    -> failed++;
                case KILLED    -> killed++;
            }
            if (t.isRoot()) roots++;
        }
        return new TaskStats(total, pending, running, completed, failed, killed, roots, oldestPendingAge);
    }

    /** merge multiple TaskStats into one. Useful for
     *  aggregating across subagent pools or plan boundaries.
     *  {@code rootTasks} is summed; {@code oldestPendingAgeMs}
     *  is the max across inputs (oldest wins). */
    public static TaskStats merge(java.util.List<TaskStats> stats) {
        if (stats == null || stats.isEmpty()) {
            return new TaskStats(0, 0, 0, 0, 0, 0, 0, 0L);
        }
        int total = 0, pending = 0, running = 0, completed = 0, failed = 0, killed = 0, roots = 0;
        long oldest = 0L;
        for (TaskStats s : stats) {
            if (s == null) continue;
            total += s.total();
            pending += s.pending();
            running += s.running();
            completed += s.completed();
            failed += s.failed();
            killed += s.killed();
            roots += s.rootTasks();
            if (s.oldestPendingAgeMs() > oldest) oldest = s.oldestPendingAgeMs();
        }
        return new TaskStats(total, pending, running, completed, failed, killed, roots, oldest);
    }

    /** Format a one-line human-readable summary, e.g.
     *  "12 tasks (3 running, 5 pending, 4 done, 1 failed, 1 killed)". */
    public String summary() {
        if (total == 0) return "no tasks";
        StringBuilder sb = new StringBuilder();
        sb.append(total).append(" task");
        if (total != 1) sb.append('s');
        sb.append(" (");
        boolean first = true;
        if (running > 0)   { sb.append(running).append(" running"); first = false; }
        if (pending > 0)   { appendSep(sb, first); sb.append(pending).append(" pending"); first = false; }
        if (completed > 0) { appendSep(sb, first); sb.append(completed).append(" done"); first = false; }
        if (failed > 0)    { appendSep(sb, first); sb.append(failed).append(" failed"); first = false; }
        if (killed > 0)    { appendSep(sb, first); sb.append(killed).append(" killed"); first = false; }
        if (rootTasks > 0) { appendSep(sb, first); sb.append(rootTasks).append(" root"); first = false; }
        sb.append(')');
        if (oldestPendingAgeMs > 5_000) {
            sb.append(", oldest pending ").append(formatAge(oldestPendingAgeMs));
        }
        return sb.toString();
    }

    private static void appendSep(StringBuilder sb, boolean first) {
        if (!first) sb.append(", ");
    }

    private static String formatAge(long ms) {
        if (ms < 1000) return ms + "ms";
        if (ms < 60_000) return String.format("%.1fs", ms / 1000.0);
        long m = ms / 60_000;
        long s = (ms % 60_000) / 1000;
        return m + "m" + s + "s";
    }
}
