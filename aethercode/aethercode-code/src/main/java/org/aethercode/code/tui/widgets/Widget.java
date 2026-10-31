/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.aethercode.code.tui.widgets;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Base class for all Java port widgets.
 *
 * <p>Each ported widget extends this class so a host TUI can hold a uniform
 * collection and call {@link #render()} on any of them. The {@code render}
 * method returns a {@link WidgetNode} tree — a structured description of the
 * widget's contents, not a live terminal frame.</p>
 *
 * <p>The class carries the few fields the Python widgets share by convention
 * ({@code id}, {@code classes}, and the {@code future} that the host uses to
 * surface a dismissal). Subclasses add their own state.</p>
 */
public abstract class Widget {

    private final String id;
    private final String classes;

    /** The dismiss future — set by the host when pushing the screen. */
    private final CompletableFuture<Object> dismissFuture = new CompletableFuture<>();

    protected Widget(String id, String classes) {
        this.id = id == null ? "" : id;
        this.classes = classes == null ? "" : classes;
    }

    public String id() { return id; }
    public String classes() { return classes; }

    /** Render the widget as a structured tree. */
    public abstract WidgetNode render();

    /** Optional key-binding list, surfaced for hosts that want to advertise them. */
    public List<KeyBinding> bindings() {
        return List.of();
    }

    /** Dismiss the widget with a value; resolves {@link #dismissFuture}. */
    public void dismiss(Object value) {
        dismissFuture.complete(value);
    }

    /** The dismiss future the host awaits on screen teardown. */
    public CompletableFuture<Object> dismissFuture() {
        return dismissFuture;
    }

    /** Immutable key-binding record. */
    public record KeyBinding(String key, String action, String description, boolean priority) {
        public static KeyBinding of(String key, String action, String description) {
            return new KeyBinding(key, action, description, false);
        }
        public static KeyBinding priority(String key, String action, String description) {
            return new KeyBinding(key, action, description, true);
        }
    }

    /** Theme colors stub. Hosts inject the real theme at render time. */
    public record ThemeColors(
            String primary, String muted, String warning, String error,
            String success, String accent, String tool, String text,
            String background, String surface) {
        public static ThemeColors defaultPalette() {
            return new ThemeColors(
                    "cyan", "gray", "yellow", "red",
                    "green", "magenta", "blue", "white",
                    "black", "black");
        }
        public static ThemeColors fromMap(Map<String, String> map) {
            return new ThemeColors(
                    map.getOrDefault("primary", "cyan"),
                    map.getOrDefault("muted", "gray"),
                    map.getOrDefault("warning", "yellow"),
                    map.getOrDefault("error", "red"),
                    map.getOrDefault("success", "green"),
                    map.getOrDefault("accent", "magenta"),
                    map.getOrDefault("tool", "blue"),
                    map.getOrDefault("text", "white"),
                    map.getOrDefault("background", "black"),
                    map.getOrDefault("surface", "black"));
        }
    }
}
