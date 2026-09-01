package com.shifa.oms.gst.filing.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * The pure result of a {@link FilingStatusMachine} transition attempt (GST returns &amp; filing,
 * Reqs 1.3, 1.4, 1.7, 1.8, 2.3, 2.7).
 *
 * <p>A transition is either <strong>accepted</strong> — carrying the {@link FilingStatus new status}
 * the return should move to — or <strong>rejected</strong> — carrying a {@link Reason} explaining why
 * the transition is not legal. A rejection intentionally carries <em>no</em> new status, so the
 * caller leaves the current status unchanged. No exceptions are ever thrown, which keeps the state
 * machine trivially property-testable.
 *
 * <p>Pure and Spring-free; no JPA.
 *
 * @param newStatus the status to move to when {@link #accepted() accepted}; {@code null} on rejection
 * @param reason    the reason when {@link #rejected() rejected}; {@code null} on acceptance
 */
public record FilingTransition(FilingStatus newStatus, Reason reason) {

    /**
     * Why a transition was rejected. Every rejection leaves the current filing status unchanged.
     *
     * <ul>
     *   <li>{@link #INVALID_TRANSITION} — the requested move is not legal from the current status
     *       (e.g. preparing an already-prepared return, or filing one that was never prepared).</li>
     *   <li>{@link #PERIOD_LOCKED} — the return has already been filed and its period is locked;
     *       re-filing is refused (Reqs 1.8, 2.3).</li>
     *   <li>{@link #NOT_FILED} — a reopen was requested for a return that is not currently filed
     *       (Req 2.7).</li>
     * </ul>
     */
    public enum Reason {
        INVALID_TRANSITION,
        PERIOD_LOCKED,
        NOT_FILED
    }

    /**
     * Validates the invariant that a transition is exactly one of accepted (a new status, no reason)
     * or rejected (a reason, no new status).
     */
    public FilingTransition {
        if ((newStatus == null) == (reason == null)) {
            throw new IllegalArgumentException(
                    "FilingTransition must carry exactly one of newStatus or reason");
        }
    }

    /**
     * @param newStatus the status the return moves to
     * @return an accepted transition carrying {@code newStatus}
     */
    public static FilingTransition accept(FilingStatus newStatus) {
        return new FilingTransition(Objects.requireNonNull(newStatus, "newStatus"), null);
    }

    /**
     * @param reason why the transition is refused
     * @return a rejected transition carrying {@code reason} and no new status
     */
    public static FilingTransition reject(Reason reason) {
        return new FilingTransition(null, Objects.requireNonNull(reason, "reason"));
    }

    /** @return {@code true} when the transition was accepted (carries a new status). */
    public boolean accepted() {
        return newStatus != null;
    }

    /** @return {@code true} when the transition was rejected (carries a reason). */
    public boolean rejected() {
        return reason != null;
    }

    /** @return the accepted new status, if any. */
    public Optional<FilingStatus> newStatusIfAccepted() {
        return Optional.ofNullable(newStatus);
    }

    /** @return the rejection reason, if any. */
    public Optional<Reason> reasonIfRejected() {
        return Optional.ofNullable(reason);
    }
}
