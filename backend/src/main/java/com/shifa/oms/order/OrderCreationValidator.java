package com.shifa.oms.order;

import com.shifa.oms.common.ValidationException;

/**
 * Pure, dependency-free validation for the lead-source fields captured on
 * salesperson order entry (design §3.1, §6.1; Req 4.1, 4.2, 4.5).
 *
 * <p>Kept as a small pure helper (no Spring, no persistence) so the rules can be
 * exercised in isolation by property-based tests and reused by
 * {@link OrderService#createSalespersonOrder}. Two failure modes are surfaced,
 * both mapped to HTTP 400 via {@link ValidationException}:
 *
 * <ul>
 *   <li><strong>Missing / invalid lead source</strong> — a new salesperson order
 *       must carry a lead source drawn from the defined {@link LeadSource} set
 *       (Req 4.1, 4.2).</li>
 *   <li><strong>Over-long note</strong> — the optional {@code leadSourceNote}
 *       (only meaningful for {@link LeadSource#OTHER}, Req 4.5) is bounded to
 *       {@value #MAX_LEAD_SOURCE_NOTE_LENGTH} characters.</li>
 * </ul>
 */
public final class OrderCreationValidator {

    /** Maximum length of the optional {@code leadSourceNote} (Req 4.5, design §3.1). */
    public static final int MAX_LEAD_SOURCE_NOTE_LENGTH = 200;

    private OrderCreationValidator() {
        // Utility class.
    }

    /**
     * Asserts a typed lead source is present (Req 4.1, 4.2). Membership is
     * guaranteed by the Java type once a value is bound; the only failure here is
     * a missing ({@code null}) value.
     *
     * @throws ValidationException (HTTP 400) when {@code leadSource} is {@code null}
     */
    public static void requireLeadSource(LeadSource leadSource) {
        if (leadSource == null) {
            throw new ValidationException(
                    "leadSource is required and must be one of the defined lead sources.");
        }
    }

    /**
     * Resolves a raw lead-source name against the defined {@link LeadSource} set,
     * enforcing both presence and membership (Req 4.1, 4.2).
     *
     * @param rawName the raw lead-source name (e.g. from an untyped payload)
     * @return the matching {@link LeadSource}
     * @throws ValidationException (HTTP 400) when blank/{@code null} or not a
     *                             recognised lead source
     */
    public static LeadSource requireLeadSource(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new ValidationException(
                    "leadSource is required and must be one of the defined lead sources.");
        }
        for (LeadSource candidate : LeadSource.values()) {
            if (candidate.name().equals(rawName)) {
                return candidate;
            }
        }
        throw new ValidationException(
                "'" + rawName + "' is not a recognised lead source.");
    }

    /**
     * Enforces the optional-note length bound (Req 4.5). A {@code null} note is
     * always valid; a present note must be at most
     * {@value #MAX_LEAD_SOURCE_NOTE_LENGTH} characters.
     *
     * @throws ValidationException (HTTP 400) when the note exceeds the bound
     */
    public static void validateLeadSourceNote(String note) {
        if (note != null && note.length() > MAX_LEAD_SOURCE_NOTE_LENGTH) {
            throw new ValidationException(
                    "leadSourceNote must be at most " + MAX_LEAD_SOURCE_NOTE_LENGTH + " characters.");
        }
    }
}
