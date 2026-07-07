package com.shifa.oms.statemachine;

import java.time.Instant;
import java.util.Objects;

/**
 * An immutable audit record of a single accepted status transition
 * (Requirement 8.4; design table {@code status_history}).
 *
 * <p>Exactly one entry is produced per accepted transition, capturing where the
 * order moved from, where it moved to, who/what caused the change, and when.
 *
 * @param fromStatus the status before the transition; {@code null} for the
 *                   synthetic creation entry that records the initial status
 * @param toStatus   the status after the transition (never {@code null})
 * @param actor      the user or system principal that caused the change
 *                   (e.g. a username, {@code "COURIER_API"}, {@code "SYSTEM"})
 * @param source     the origin of the change (e.g. {@code ADMIN}, {@code PACKING},
 *                   {@code COURIER}, {@code SYSTEM})
 * @param changedAt  the instant the transition was applied (never {@code null})
 */
public record StatusHistoryEntry(
        OrderStatus fromStatus,
        OrderStatus toStatus,
        String actor,
        String source,
        Instant changedAt) {

    public StatusHistoryEntry {
        Objects.requireNonNull(toStatus, "toStatus");
        Objects.requireNonNull(changedAt, "changedAt");
    }
}
