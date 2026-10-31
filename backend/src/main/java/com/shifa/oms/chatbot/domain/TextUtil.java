package com.shifa.oms.chatbot.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Small, dependency-free text helpers shared by the chatbot's matchers. */
final class TextUtil {

    private TextUtil() {
    }

    /** Lower-cases, unifies apostrophes and drops possessives ("sameer's" becomes "sameer"). */
    static String prepare(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.toLowerCase(Locale.ROOT).trim();
        s = s.replaceAll("[\\u2018\\u2019`\\u00B4]", "'");
        s = s.replaceAll("'s\\b", "");
        s = s.replace("'", "");
        return s.replaceAll("\\s+", " ");
    }

    /** Keeps only letters and digits, separated by single spaces. */
    static String flatten(String s) {
        return s.replaceAll("[^a-z0-9]+", " ").trim();
    }

    static List<String> tokens(String flat) {
        List<String> out = new ArrayList<>();
        if (flat == null || flat.isBlank()) {
            return out;
        }
        for (String t : flat.trim().split(" ")) {
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** Tokens of a display name such as "Breath Pure (Rogan Hayat) Rollon". */
    static List<String> nameTokens(String name) {
        return tokens(flatten(prepare(name)));
    }

    static String join(List<String> tokens, int from, int toExclusive) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < toExclusive; i++) {
            sb.append(tokens.get(i));
        }
        return sb.toString();
    }

    /** Levenshtein edit distance. */
    static int distance(String a, String b) {
        if (a.equals(b)) {
            return 0;
        }
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        return prev[b.length()];
    }

    /**
     * Whether a typed word is close enough to a real name word to be treated as
     * the same word. Short words must match exactly; longer words tolerate one
     * (5-6 letters) or two (7+ letters) typos, so "zishan" finds "Zeeshan" but
     * "sales" never turns into a name.
     */
    static boolean sameWord(String typed, String name) {
        if (typed.equals(name)) {
            return true;
        }
        int shortest = Math.min(typed.length(), name.length());
        if (shortest < 5 || Math.abs(typed.length() - name.length()) > 2) {
            return false;
        }
        int allowed = Math.max(typed.length(), name.length()) >= 7 ? 2 : 1;
        return distance(typed, name) <= allowed;
    }

    static boolean isAlphabetic(String token) {
        for (int i = 0; i < token.length(); i++) {
            if (!Character.isLetter(token.charAt(i))) {
                return false;
            }
        }
        return !token.isEmpty();
    }
}
