package com.shifa.oms.adminnotification;

import com.shifa.oms.auth.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A persisted admin notification, mapped to the {@code admin_notifications}
 * table ("operations depth" Feature 2).
 *
 * <p>Admin alerts were previously ephemeral (relayed over SSE only). This entity
 * records one durable row per admin-facing event as it is published, giving the
 * console a history with read/unread state independent of whether an admin was
 * connected at the time. {@code sourceEventId} references the originating outbox
 * row so writes can be de-duplicated and traced; it is null for manually-created
 * notifications.
 */
@Entity
@Table(name = "admin_notifications")
public class AdminNotification {

    /** Severities used by the console to colour the alert. */
    public static final String SEVERITY_INFO = "info";
    public static final String SEVERITY_SUCCESS = "success";
    public static final String SEVERITY_WARNING = "warning";
    public static final String SEVERITY_DANGER = "danger";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "type", nullable = false, length = 40)
    private String type;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "detail", columnDefinition = "text")
    private String detail;

    @Column(name = "severity", nullable = false, length = 20)
    private String severity = SEVERITY_INFO;

    /**
     * The staff role this notification is addressed to (Req 13.4). When set and
     * {@link #recipientUserId} is null, the notification is available to every
     * active user holding that role. Persisted as the {@link Role} name on
     * {@code admin_notifications.recipient_role} (V24), mirroring {@code User.role}.
     * {@code recipientRole} and {@code recipientUserId} both null = legacy admin broadcast.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "recipient_role", length = 20)
    private Role recipientRole;

    /**
     * The specific user this notification is addressed to (e.g. the creating
     * salesperson, Req 7.3). Mapped to {@code admin_notifications.recipient_user_id}
     * (V24). Null (with {@link #recipientRole} also null) = legacy admin broadcast.
     */
    @Column(name = "recipient_user_id")
    private Long recipientUserId;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "order_code", length = 30)
    private String orderCode;

    @Column(name = "source_event_id")
    private Long sourceEventId;

    @Column(name = "read_flag", nullable = false)
    private boolean read = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    protected AdminNotification() {
        // Required by JPA.
    }

    public AdminNotification(String type, String title, String detail, String severity,
                             Long orderId, String orderCode, Long sourceEventId) {
        this.type = type;
        this.title = title;
        this.detail = detail;
        this.severity = severity != null ? severity : SEVERITY_INFO;
        this.orderId = orderId;
        this.orderCode = orderCode;
        this.sourceEventId = sourceEventId;
    }

    /** Fills the creation timestamp before the first insert so the DB never receives a null. */
    @PrePersist
    void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    /** Marks this notification read, stamping {@code read_at} the first time only (idempotent). */
    public void markRead() {
        if (!this.read) {
            this.read = true;
            this.readAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public String getDetail() {
        return detail;
    }

    public String getSeverity() {
        return severity;
    }

    public Role getRecipientRole() {
        return recipientRole;
    }

    /** Addresses this notification to every active user of a role (Req 13.4). */
    public void setRecipientRole(Role recipientRole) {
        this.recipientRole = recipientRole;
    }

    public Long getRecipientUserId() {
        return recipientUserId;
    }

    /** Addresses this notification to a specific user, e.g. the creating salesperson (Req 7.3). */
    public void setRecipientUserId(Long recipientUserId) {
        this.recipientUserId = recipientUserId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getOrderCode() {
        return orderCode;
    }

    public Long getSourceEventId() {
        return sourceEventId;
    }

    public boolean isRead() {
        return read;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getReadAt() {
        return readAt;
    }
}
