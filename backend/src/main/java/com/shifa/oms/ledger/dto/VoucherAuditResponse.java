package com.shifa.oms.ledger.dto;

import com.shifa.oms.audit.AuditEvent;

import java.time.LocalDateTime;

/**
 * Read view of a single voucher audit-trail event ({@code GET /api/accounting/vouchers/{id}/audit},
 * Reqs 15.1–15.4, 18.3).
 *
 * <p>Exposes the statutory who/what/when for a financial voucher: the action verb (e.g.
 * {@code VOUCHER_POSTED} / {@code VOUCHER_REVERSED}), the acting user (username + id snapshots),
 * the audited voucher id, a human-readable summary, and the timestamp. The controller returns these
 * in chronological order (Req 15.4). The audit trail is append-only — there is no edit/delete view.
 *
 * @param action        the audit action verb
 * @param actorUsername the acting user's username snapshot, or {@code null}
 * @param actorUserId   the acting user's id snapshot, or {@code null}
 * @param entityId      the audited voucher id (as recorded on the audit event)
 * @param summary       a short human-readable description of the event
 * @param createdAt     when the event was recorded
 */
public record VoucherAuditResponse(
        String action,
        String actorUsername,
        Long actorUserId,
        String entityId,
        String summary,
        LocalDateTime createdAt
) {

    /** Maps a persisted {@link AuditEvent} to its voucher-audit response view. */
    public static VoucherAuditResponse from(AuditEvent event) {
        return new VoucherAuditResponse(
                event.getAction(),
                event.getActorUsername(),
                event.getActorUserId(),
                event.getEntityId(),
                event.getSummary(),
                event.getCreatedAt());
    }
}
