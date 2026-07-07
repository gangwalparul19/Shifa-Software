package com.shifa.oms.audit.dto;

import com.shifa.oms.audit.AuditEvent;

import java.time.LocalDateTime;

/**
 * Read projection for a single audit-trail row
 * ({@code GET /api/admin/audit}, "operations depth" Feature 3).
 *
 * <p>Flat and stable for the admin table: the actor (id + username snapshot),
 * the action verb, the target entity, the summary, and when it happened.
 */
public record AuditEventResponse(
        Long id,
        Long actorUserId,
        String actorUsername,
        String action,
        String entityType,
        String entityId,
        String summary,
        LocalDateTime createdAt
) {

    public static AuditEventResponse from(AuditEvent event) {
        return new AuditEventResponse(
                event.getId(),
                event.getActorUserId(),
                event.getActorUsername(),
                event.getAction(),
                event.getEntityType(),
                event.getEntityId(),
                event.getSummary(),
                event.getCreatedAt());
    }
}
