package com.shifa.oms.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A single audit-trail row, mapped to the {@code audit_events} table
 * ("operations depth" Feature 3): the central record of who did what, when.
 *
 * <p>Each row captures the resolved actor (denormalised {@code actor_user_id} /
 * {@code actor_username} snapshots so the trail stays readable if the user is
 * later renamed/removed), the {@code action} verb (e.g. {@code ORDER_APPROVED}),
 * the target entity ({@code entity_type} + optional {@code entity_id}), and a
 * short human-readable {@code summary}. The actor fields are nullable because a
 * record may be produced from a context without an authenticated principal
 * (e.g. a scheduled task); auditing is always best-effort and never blocks the
 * business operation it describes.
 */
@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "actor_username", length = 100)
    private String actorUsername;

    @Column(name = "action", nullable = false, length = 60)
    private String action;

    @Column(name = "entity_type", nullable = false, length = 60)
    private String entityType;

    @Column(name = "entity_id", length = 60)
    private String entityId;

    @Column(name = "summary", length = 1000)
    private String summary;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected AuditEvent() {
        // Required by JPA.
    }

    public AuditEvent(Long actorUserId, String actorUsername, String action,
                      String entityType, String entityId, String summary) {
        this.actorUserId = actorUserId;
        this.actorUsername = actorUsername;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.summary = summary;
    }

    /** Fills the creation timestamp before the first insert so the DB never receives a null. */
    @PrePersist
    void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getActorUserId() {
        return actorUserId;
    }

    public String getActorUsername() {
        return actorUsername;
    }

    public String getAction() {
        return action;
    }

    public String getEntityType() {
        return entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public String getSummary() {
        return summary;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
