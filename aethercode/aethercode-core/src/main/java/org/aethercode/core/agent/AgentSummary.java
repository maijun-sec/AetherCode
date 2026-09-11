package org.aethercode.core.agent;

import org.aethercode.core.app.AppState;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.aethercode.core.tool.Tool;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * long-session summary card. Modelled on the TS
 * {@code AgentSummary/}. After a long conversation, the user (or another
 * agent) wants a one-screen recap: what did the user ask, what tools
 * ran, what files were touched.
 *
 * <p>The implementation is a pure function over the {@link AppState} —
 * no I/O, no LLM call. It walks the transcript, picks the first user
 * message as the "title", lists a few representative bullet points, and
 * greps the messages for likely file paths and tool names.
 */
public final class AgentSummary {

    public record Card(
            String title,
            int userMessages,
            int assistantMessages,
            int toolMessages,
            int totalTokens,
            List<String> fileTouched,
            List<String> toolsUsed,
            String body
    ) {}

    private static final Pattern FILE_PATH = Pattern.compile("(?<![A-Za-z0-9_])([A-Za-z0-9_./\\-]+\\.[A-Za-z0-9]{1,5})(?![A-Za-z0-9_])");

    public AgentSummary() {}

    public Card generate(AppState appState) {
        if (appState == null) return emptyCard();
        List<Message> msgs = appState.transcript();
        int userCount = 0, assistantCount = 0, toolCount = 0;
        String title = null;
        Set<String> files = new LinkedHashSet<>();
        Set<String> tools = new LinkedHashSet<>();
        int tokenEstimate = 0;
        for (Message m : msgs) {
            switch (m.role()) {
                case USER -> {
                    userCount++;
                    if (title == null) title = firstLine(textOf(m));
                }
                case ASSISTANT -> {
                    assistantCount++;
                    tools.addAll(extractToolNames(m));
                }
                case TOOL_RESULT -> toolCount++;
            }
            tokenEstimate += estimateTokens(textOf(m));
            files.addAll(extractFilePaths(textOf(m)));
        }
        // also count tools actually present in the pool
        for (Tool t : appState.toolPool()) tools.add(t.name());

        String body = renderBody(userCount, assistantCount, toolCount, tokenEstimate,
                new ArrayList<>(files), new ArrayList<>(tools));
        return new Card(
                title == null ? "(untitled session)" : title,
                userCount, assistantCount, toolCount, tokenEstimate,
                new ArrayList<>(files), new ArrayList<>(tools), body);
    }

    /** render the card as a multi-line text block. */
    public String render(Card card) {
        return card == null ? "" : card.body();
    }

    private static String renderBody(int user, int assistant, int tools, int tokens,
                                     List<String> files, List<String> usedTools) {
        StringBuilder sb = new StringBuilder();
        sb.append("── AgentSummary ──\n");
        sb.append("messages:    user=").append(user).append("  assistant=").append(assistant)
                .append("  tool_result=").append(tools).append('\n');
        sb.append("tokens:      ~").append(tokens).append(" (rough char/4 estimate)\n");
        if (!usedTools.isEmpty()) {
            sb.append("tools used:  ");
            sb.append(String.join(", ", usedTools.subList(0, Math.min(usedTools.size(), 8))));
            if (usedTools.size() > 8) sb.append("  (+").append(usedTools.size() - 8).append(" more)");
            sb.append('\n');
        }
        if (!files.isEmpty()) {
            sb.append("files:       ");
            sb.append(String.join(", ", files.subList(0, Math.min(files.size(), 8))));
            if (files.size() > 8) sb.append("  (+").append(files.size() - 8).append(" more)");
            sb.append('\n');
        }
        return sb.toString();
    }

    private static Card emptyCard() {
        return new Card("(empty session)", 0, 0, 0, 0, List.of(), List.of(),
                "── AgentSummary ──\n(no messages yet)\n");
    }

    private static String textOf(Message m) {
        if (m == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : m.content()) {
            if (b instanceof ContentBlock.TextBlock tb) sb.append(tb.text());
            else if (b instanceof ContentBlock.ToolUseBlock tu) {
                sb.append(tu.name());
                if (tu.input() != null) sb.append(' ').append(tu.input());
            }
            else if (b instanceof ContentBlock.ToolResultBlock tr) {
                sb.append(' ').append(tr.content());
            }
        }
        return sb.toString();
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int idx = s.indexOf('\n');
        return (idx < 0 ? s : s.substring(0, idx)).trim();
    }

    private static List<String> extractToolNames(Message m) {
        List<String> out = new ArrayList<>();
        for (ContentBlock b : m.content()) {
            if (b instanceof ContentBlock.ToolUseBlock tu) out.add(tu.name());
        }
        return out;
    }

    private static List<String> extractFilePaths(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = FILE_PATH.matcher(text);
        while (m.find()) {
            String path = m.group(1);
            if (path.contains("/") || path.contains("\\")) out.add(path);
        }
        return out;
    }

    private static int estimateTokens(String s) {
        if (s == null || s.isEmpty()) return 0;
        // very rough — ~4 chars per token; OK for a summary card
        return (s.length() + 3) / 4;
    }
}
