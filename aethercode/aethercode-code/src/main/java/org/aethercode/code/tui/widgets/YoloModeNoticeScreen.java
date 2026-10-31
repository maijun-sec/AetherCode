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

/**
 * First-enable confirmation modal before Shift+Tab enters YOLO.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.yolo_mode_notice}.
 * The Python module exposes a {@code YoloModeNoticeResult} enum and a
 * {@code YoloModeNoticeScreen} {@code ModalScreen} that is shown when
 * the user cycles into unrestricted YOLO without a persisted
 * acknowledgement.</p>
 */
public class YoloModeNoticeScreen extends ModalScreen<YoloModeNoticeScreen.YoloModeNoticeResult> {

    /** Outcome of the YOLO first-enable notice. */
    public enum YoloModeNoticeResult { ACKNOWLEDGE, MANUAL, CANCEL }

    /** Canonical docs page for Manual / Auto / YOLO behavior. */
    public static final String YOLO_MODE_DOCS_URL =
            "https://docs.langchain.com/oss/python/deepagents/code/approval-modes";

    /** Default Markdown body shown before the first YOLO switcher enable. */
    public static final String YOLO_MODE_NOTICE_BODY =
            "You are about to enable **YOLO mode**. The agent may run shell commands, "
            + "edit files, make network calls, and use other tools on this machine "
            + "**without asking you first**.\n\n"
            + "Only continue if you're comfortable letting it act unsupervised.\n\n"
            + "Leave YOLO any time with **Shift+Tab**.\n\n"
            + "This notice appears **once** on this machine.\n\n"
            + "[Learn more about approval modes](" + YOLO_MODE_DOCS_URL + ")";

    private final String body;

    public YoloModeNoticeScreen() {
        this(null);
    }

    public YoloModeNoticeScreen(String body) {
        super("", "yolo-mode-notice-screen");
        this.body = body == null ? YOLO_MODE_NOTICE_BODY : body;
    }

    public String body() { return body; }

    @Override
    public WidgetNode render() {
        WidgetNode title = new WidgetNode.Static("YOLO mode",
                WidgetNode.Role.ERROR, false, true, false);
        WidgetNode bodyNode = new WidgetNode.Markdown(body, "yolo-mode-notice-body");
        WidgetNode help = new WidgetNode.Static(
                "Enter to enable YOLO · m for Manual · Esc to keep current mode",
                WidgetNode.Role.MUTED, true, false, true);
        return new WidgetNode.Container(WidgetNode.Layout.VERTICAL,
                List.of(title, bodyNode, help),
                "yolo-mode-notice-screen");
    }

    @Override
    public List<KeyBinding> bindings() {
        return List.of(
                KeyBinding.priority("enter", "confirm", "Enable YOLO"),
                KeyBinding.priority("m", "switch_to_manual", "Switch to Manual"),
                KeyBinding.priority("escape", "cancel", "Keep previous"));
    }

    public void actionConfirm() { dismiss(YoloModeNoticeResult.ACKNOWLEDGE); }
    public void actionSwitchToManual() { dismiss(YoloModeNoticeResult.MANUAL); }
    public void actionCancel() { dismiss(YoloModeNoticeResult.CANCEL); }
}
