package com.shifa.oms.lead;

/**
 * The categorized reason a lead did not convert (Requirement 2.3; design
 * &sect;3.1). Required when a lead is marked {@link LeadStatus#LOST} and
 * persisted on {@code leads.lost_reason} as {@code VARCHAR(20)} via
 * {@code @Enumerated(EnumType.STRING)}.
 *
 * <ul>
 *   <li>{@link #PRICE} — lost on price.</li>
 *   <li>{@link #OUT_OF_STOCK} — product unavailable.</li>
 *   <li>{@link #NO_RESPONSE} — the prospect stopped responding.</li>
 *   <li>{@link #DUPLICATE} — a duplicate of another lead/customer.</li>
 *   <li>{@link #NOT_INTERESTED} — the prospect declined.</li>
 *   <li>{@link #OTHER} — anything else; accepts an optional free-text note
 *       ({@code lost_reason_note}).</li>
 * </ul>
 */
public enum LostReason {
    PRICE,
    OUT_OF_STOCK,
    NO_RESPONSE,
    DUPLICATE,
    NOT_INTERESTED,
    OTHER
}
