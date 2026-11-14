package com.hrznstudio.emojiful.render;

import com.hrznstudio.emojiful.api.Emoji;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds emoji codes in text in a single left-to-right pass.
 * <p>
 * The original implementation ran every emoji's regular expression (~2300 with the built-in set)
 * over the text, and since 5.2.3 did so again for every emoji found, on every frame. Here
 * {@code :name:} codes are found with one hash lookup per colon, and the few aliases of other
 * shapes are checked only at positions starting with their first character.
 * <p>
 * Semantics match the regex version: case-insensitive, leftmost match first, the longest match wins
 * at the same position, and an earlier emoji in the list wins over a later one with the same alias.
 * Aliases starting or ending with an ASCII letter need a word boundary there.
 */
public final class EmojiMatcher {

    public record Match(int start, int end, Emoji emoji) {
    }

    private record Entry(Emoji emoji, int priority) {
    }

    private record Alias(String text, Emoji emoji, int priority, boolean boundaryStart, boolean boundaryEnd) {
    }

    public static final EmojiMatcher EMPTY = new EmojiMatcher(Map.of(), Map.of(), 0);

    private final Map<String, Entry> colonAliases;
    private final Map<Character, List<Alias>> otherAliases;
    private final int maxColonLength;

    private EmojiMatcher(Map<String, Entry> colonAliases, Map<Character, List<Alias>> otherAliases, int maxColonLength) {
        this.colonAliases = colonAliases;
        this.otherAliases = otherAliases;
        this.maxColonLength = maxColonLength;
    }

    public static EmojiMatcher build(List<Emoji> emojis) {
        Map<String, Entry> colon = new HashMap<>();
        Map<Character, List<Alias>> other = new HashMap<>();
        int maxColon = 0;
        int priority = 0;
        for (Emoji emoji : emojis) {
            if (emoji == null || emoji.strings == null) continue;
            for (String alias : emoji.strings) {
                if (alias == null || alias.isEmpty()) continue;
                String lower = alias.toLowerCase(Locale.ROOT);
                if (isColonCode(lower)) {
                    colon.putIfAbsent(lower, new Entry(emoji, priority));
                    maxColon = Math.max(maxColon, lower.length());
                } else {
                    char first = Character.toLowerCase(alias.charAt(0));
                    char last = Character.toLowerCase(alias.charAt(alias.length() - 1));
                    other.computeIfAbsent(first, c -> new ArrayList<>()).add(new Alias(alias, emoji, priority,
                            first >= 'a' && first <= 'z', last >= 'a' && last <= 'z'));
                }
            }
            priority++;
        }
        for (List<Alias> list : other.values()) {
            // longest first, then by list priority; drop exact duplicates of a higher-priority alias
            list.sort(Comparator.comparingInt((Alias a) -> -a.text().length()).thenComparingInt(Alias::priority));
        }
        return new EmojiMatcher(colon, other, maxColon);
    }

    private static boolean isColonCode(String alias) {
        return alias.length() >= 3 && alias.charAt(0) == ':' && alias.charAt(alias.length() - 1) == ':'
                && alias.indexOf(':', 1) == alias.length() - 1;
    }

    public boolean isEmpty() {
        return colonAliases.isEmpty() && otherAliases.isEmpty();
    }

    public List<Match> findAll(String text) {
        if (text == null || text.isEmpty() || isEmpty()) return Collections.emptyList();
        List<Match> matches = null;
        int length = text.length();
        int i = 0;
        while (i < length) {
            char c = text.charAt(i);
            int bestEnd = -1;
            int bestPriority = Integer.MAX_VALUE;
            Emoji best = null;

            if (c == ':' && maxColonLength > 0) {
                int close = text.indexOf(':', i + 1);
                if (close > i + 1 && close - i + 1 <= maxColonLength) {
                    Entry entry = colonAliases.get(text.substring(i, close + 1).toLowerCase(Locale.ROOT));
                    if (entry != null) {
                        best = entry.emoji();
                        bestEnd = close + 1;
                        bestPriority = entry.priority();
                    }
                }
            }

            List<Alias> candidates = otherAliases.isEmpty() ? null : otherAliases.get(Character.toLowerCase(c));
            if (candidates != null) {
                for (Alias alias : candidates) {
                    int end = i + alias.text().length();
                    if (end > length) continue;
                    if (best != null && (end < bestEnd || end == bestEnd && alias.priority() >= bestPriority)) continue;
                    if (!text.regionMatches(true, i, alias.text(), 0, alias.text().length())) continue;
                    if (alias.boundaryStart() && i > 0 && isWordChar(text.charAt(i - 1))) continue;
                    if (alias.boundaryEnd() && end < length && isWordChar(text.charAt(end))) continue;
                    best = alias.emoji();
                    bestEnd = end;
                    bestPriority = alias.priority();
                }
            }

            if (best != null) {
                if (matches == null) matches = new ArrayList<>(4);
                matches.add(new Match(i, bestEnd, best));
                i = bestEnd;
            } else {
                i++;
            }
        }
        return matches == null ? Collections.emptyList() : matches;
    }

    /** Same notion of a word character as {@code \b} in java.util.regex without UNICODE_CHARACTER_CLASS. */
    private static boolean isWordChar(char c) {
        return c == '_' || Character.isLetterOrDigit(c);
    }
}
