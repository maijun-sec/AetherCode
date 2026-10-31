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

package org.aethercode.talon.channels;

import org.aethercode.talon.interfaces.ChannelMessage;

import java.util.Collections;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Inbound exposure policy shared by channel adapters.
 *
 * <p>Java-native port of
 * {@code deepagents_talon.channels.base.ChannelExposure}.</p>
 */
public record ChannelExposure(
        ExposureMode mode,
        Set<String> conversations,
        java.util.List<String> mentionPatterns,
        Set<String> operatorIds) {

    public ChannelExposure {
        mode = mode == null ? ExposureMode.SELF : mode;
        conversations = conversations == null ? Set.of() : Set.copyOf(conversations);
        mentionPatterns = mentionPatterns == null ? java.util.List.of()
                : java.util.List.copyOf(mentionPatterns);
        operatorIds = operatorIds == null ? Set.of() : Set.copyOf(operatorIds);
    }

    public ChannelExposure(ExposureMode mode) {
        this(mode, Set.of(), java.util.List.of(), Set.of());
    }

    /**
     * Return whether an inbound message may trigger the agent.
     */
    public boolean allows(ChannelMessage message) {
        if (mode == ExposureMode.OPEN) {
            return true;
        }
        if (mode == ExposureMode.SELF) {
            return isSelfMessage(message, operatorIds);
        }
        if (conversations.contains(message.conversationId())) {
            return true;
        }
        for (String pattern : mentionPatterns) {
            if (matchesText(message.text(), pattern)) {
                return true;
            }
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static boolean isSelfMessage(ChannelMessage message, Set<String> operatorIds) {
        Object fromSelf = message.metadata().get("from_self");
        if (Boolean.TRUE.equals(fromSelf)) {
            return true;
        }
        return message.senderId().isPresent() && operatorIds.contains(message.senderId().get());
    }

    private static boolean matchesText(String text, String pattern) {
        // fnmatch-style glob; case-sensitive.
        String regex = toRegex(pattern);
        return java.util.regex.Pattern.compile(regex).matcher(text).matches();
    }

    /**
     * Convert a glob-style pattern (the Python {@code fnmatch} semantics)
     * to a regex. Mirrors {@code fnmatch.translate} closely enough for the
     * patterns the env exposes: literal chars plus {@code *}, {@code ?},
     * {@code [seq]}, and {@code [!seq]}.
     */
    static String toRegex(String pattern) {
        StringBuilder out = new StringBuilder(pattern.length() * 2 + 4);
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*' -> out.append(".*");
                case '?' -> out.append('.');
                case '[' -> {
                    int j = i + 1;
                    boolean negate = false;
                    if (j < pattern.length() && pattern.charAt(j) == '!') {
                        negate = true;
                        j++;
                    }
                    int end = pattern.indexOf(']', j);
                    if (end < 0) {
                        out.append("\\[").append(pattern, j, pattern.length());
                        i = pattern.length();
                    } else {
                        out.append(negate ? "[^" : "[");
                        out.append(pattern, j, end).append(']');
                        i = end + 1;
                    }
                }
                case '\\' -> {
                    if (i + 1 < pattern.length()) {
                        i++;
                        out.append(java.util.regex.Pattern.quote(
                                String.valueOf(pattern.charAt(i))));
                    } else {
                        out.append("\\\\");
                    }
                }
                default -> out.append(java.util.regex.Pattern.quote(String.valueOf(c)));
            }
            i++;
        }
        return out.toString();
    }

    public Set<String> conversationsView() {
        return Collections.unmodifiableSet(conversations);
    }

    public Set<String> operatorIdsView() {
        return Collections.unmodifiableSet(operatorIds);
    }

    public Predicate<ChannelMessage> allowsPredicate() {
        return this::allows;
    }
}
