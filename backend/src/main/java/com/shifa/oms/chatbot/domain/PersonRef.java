package com.shifa.oms.chatbot.domain;

/** A staff member the chatbot can resolve by name (ADMIN, TEAM_LEAD or SALESPERSON). */
public record PersonRef(long id, String fullName, boolean teamLead) {
}
