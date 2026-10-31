package com.shifa.oms.chatbot.dto;

import java.util.List;

/**
 * The assistant's reply.
 *
 * @param understood  false when the question could not be turned into a query
 * @param answer      plain text (may contain line breaks); never markup
 * @param table       optional table for rankings and side-by-side comparisons
 * @param suggestions follow-up questions the user can tap
 */
public record ChatbotAnswer(boolean understood, String answer, Table table, List<String> suggestions) {

    /** A simple text table; every cell is already formatted for display. */
    public record Table(List<String> headers, List<List<String>> rows) {
    }
}
