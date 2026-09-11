package org.aethercode.code.tui.widgets;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Loading widget with animated spinner for agent activity.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.loading}. The Python
 * module exposes a {@code Spinner} helper (returns glyphs in rotation)
 * and a {@code LoadingWidget} (Textual {@code Static}) that renders
 * {@code <spinner> Thinking... (3s, esc to interrupt)} and updates itself
 * every 100ms via a Textual timer.</p>
 *
 * <p>The Java port preserves the same data model and exposes the
 * {@code set_status}, {@code pause}, {@code resume}, and {@code stop}
 * transitions. The host TUI wires a {@link ScheduledExecutorService}
 * to drive {@link #tick()} (the equivalent of the Textual 100ms timer).
 * {@link #render()} returns a {@link WidgetNode.StatusLine} that the
 * host updates at the same cadence as the ticker.</p>
 */
public class Loading extends Widget {

    /** A glyph set for the spinner animation. */
    public record Glyphs(String spinner, String checkmark, String pause) {}

    /** Default spinner glyphs (Unicode). Hosts may inject ASCII fall-backs. */
    public static final Glyphs DEFAULT_GLYPHS = new Glyphs("⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏", "✓", "⏸");

    /** Frame interval in milliseconds, matching the Python 0.1s. */
    public static final long FRAME_INTERVAL_MS = 100L;

    private Glyphs glyphs;
    private String status;
    private int spinnerPosition;
    private Instant startTime;
    private double pausedElapsedSeconds;
    private boolean paused;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledFuture<?> animationTask;

    public Loading(String status) {
        super("", "loading-widget");
        this.glyphs = DEFAULT_GLYPHS;
        this.status = status;
        this.spinnerPosition = 0;
        this.startTime = null;
        this.pausedElapsedSeconds = 0.0;
        this.paused = false;
    }

    public Loading() {
        this("Thinking");
    }

    /** Inject a glyph set (e.g. ASCII fall-back). */
    public void setGlyphs(Glyphs g) { this.glyphs = g; }

    public String status() { return status; }

    /** Start the animation timer. Idempotent. */
    public void start(ScheduledExecutorService executor) {
        if (!running.compareAndSet(false, true)) return;
        if (startTime == null) startTime = Instant.now();
        animationTask = executor.scheduleAtFixedRate(this::tick,
                FRAME_INTERVAL_MS, FRAME_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /** Stop the animation timer. */
    public void stop() {
        running.set(false);
        if (animationTask != null) {
            animationTask.cancel(false);
            animationTask = null;
        }
    }

    /** Update the status text (e.g. "Reading file foo.py"). */
    public void setStatus(String status) {
        this.status = status;
    }

    /** Pause the animation. */
    public void pause() { pause("Awaiting decision"); }
    public void pause(String status) {
        this.paused = true;
        if (startTime != null) {
            pausedElapsedSeconds = Duration.between(startTime, Instant.now()).toMillis() / 1000.0;
        }
        this.status = status;
    }

    /**
     * Resume the animation, excluding the paused interval from elapsed time.
     * No-op when not currently paused.
     */
    public void resume() {
        if (!paused) return;
        if (startTime != null) {
            // Rebase the start time forward by the paused duration.
            startTime = Instant.now().minusMillis((long) (pausedElapsedSeconds * 1000.0));
        }
        this.paused = false;
        this.status = "Thinking";
    }

    public boolean isPaused() { return paused; }
    public boolean isRunning() { return running.get(); }

    /** Elapsed seconds since {@link #start(ScheduledExecutorService)}. */
    public long elapsedSeconds() {
        if (startTime == null) return 0L;
        if (paused) return (long) pausedElapsedSeconds;
        return Duration.between(startTime, Instant.now()).toSeconds();
    }

    /** Drive the spinner forward and recompute the elapsed time. */
    public void tick() {
        if (paused) return;
        spinnerPosition = (spinnerPosition + 1) % glyphs.spinner().length();
    }

    /** Current spinner glyph (one cell of the spinner character set). */
    public String currentSpinnerFrame() {
        if (paused) return glyphs.pause();
        if (glyphs.spinner().isEmpty()) return " ";
        char[] chars = glyphs.spinner().toCharArray();
        return String.valueOf(chars[spinnerPosition % chars.length]);
    }

    @Override
    public WidgetNode render() {
        String spinnerGlyph = currentSpinnerFrame();
        String text = " " + spinnerGlyph + " " + status + "... ";
        String hint;
        if (paused) {
            long whole = (long) pausedElapsedSeconds;
            hint = "(paused at " + formatDuration(whole) + ")";
        } else {
            hint = "(" + formatDuration(elapsedSeconds()) + ", esc to interrupt)";
        }
        return new WidgetNode.Container(
                WidgetNode.Layout.HORIZONTAL,
                List.of(
                        new WidgetNode.Static(spinnerGlyph, WidgetNode.Role.PRIMARY),
                        new WidgetNode.Static(" " + status + "... ", WidgetNode.Role.PRIMARY),
                        new WidgetNode.Static(hint, WidgetNode.Role.MUTED)
                ),
                "loading-widget");
    }

    /** Format a duration in whole seconds, e.g. {@code 0 -> "0s"}, {@code 90 -> "1m"}. */
    public static String formatDuration(long seconds) {
        if (seconds < 60) return seconds + "s";
        long m = seconds / 60;
        long s = seconds % 60;
        if (m < 60) return s == 0 ? m + "m" : m + "m" + s + "s";
        long h = m / 60;
        m = m % 60;
        return h + "h" + m + "m";
    }
}
