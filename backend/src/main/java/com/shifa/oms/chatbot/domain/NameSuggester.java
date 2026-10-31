package com.shifa.oms.chatbot.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Offers the nearest real names when a typed name matched nothing, so the user
 * can tap the right one instead of retyping.
 */
public final class NameSuggester {

    private NameSuggester() {
    }

    /** A suggested name and whether it belongs to a person (otherwise a product). */
    public record Suggestion(boolean person, String name) {
    }

    private record Scored(Suggestion suggestion, int distance) {
    }

    /**
     * The closest staff and product names to {@code typed}, best first.
     *
     * <p>A name qualifies when one of its words starts with what was typed
     * (at least three letters) or is within a few typos of it.
     */
    public static List<Suggestion> suggest(String typed, List<PersonRef> people, List<ProductRef> products, int limit) {
        String word = TextUtil.flatten(TextUtil.prepare(typed)).replace(" ", "");
        if (word.length() < 3) {
            return List.of();
        }
        int allowed = Math.max(2, word.length() / 3);
        List<Scored> scored = new ArrayList<>();
        for (PersonRef p : people) {
            addIfClose(scored, true, p.fullName(), word, allowed);
        }
        for (ProductRef p : products) {
            addIfClose(scored, false, p.name(), word, allowed);
        }
        scored.sort(Comparator.comparingInt(Scored::distance).thenComparing(s -> s.suggestion().name()));
        List<Suggestion> out = new ArrayList<>();
        for (Scored s : scored) {
            if (out.stream().noneMatch(o -> o.name().equalsIgnoreCase(s.suggestion().name()))) {
                out.add(s.suggestion());
            }
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    private static void addIfClose(List<Scored> scored, boolean person, String name, String word, int allowed) {
        int best = Integer.MAX_VALUE;
        for (String part : TextUtil.nameTokens(name)) {
            if (part.length() >= word.length() && part.startsWith(word)) {
                best = Math.min(best, 0);
            } else {
                best = Math.min(best, TextUtil.distance(word, part));
            }
        }
        if (best <= allowed) {
            scored.add(new Scored(new Suggestion(person, name), best));
        }
    }
}
