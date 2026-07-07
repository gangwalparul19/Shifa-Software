package com.shifa.oms.platform.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * A transactional-outbox row, mapped to the {@code outbox} table (design:
 * "outbox (integration reliability)").
 *
 * <p>Rows are written in the <em>same</em> transaction as the domain change that
 * produced them, so a persisted event and its triggering state change commit (or
 * roll back) together. A row starts {@code PENDING}; a later drainer/consumer
 * (task 14 for integrations, task 19 for the admin SSE stream) picks it up and
 * marks it {@code SENT}/{@code FAILED}.
 *
 * <p><strong>Packing use (task 12).</strong> When an order becomes {@code Packed}
 * the packing flow writes an {@code ORDER_PACKED} event here (aggregate
 * {@code ORDER} / the order id). Task 19 consumes these rows to raise the
 * real-time admin "order packed" notification (Req 11.2) — and because the row
 * survives even when no admin is connected, nothing is lost.
 *
 * <p>The {@code payload} is a JSON object (MySQL {@code JSON} column) mapped via
 * Hibernate's {@link SqlTypes#JSON} so consumers get structured context without
 * re-loading the aggregate; {@code aggregate_id} still points at the order for a
 * full lookup.
 */
@Entity
@Table(name = "outbox")
public class OutboxEvent {

    /** Aggregate type discriminator for order-scoped events. */
    public static final String AGGREGATE_ORDER = "ORDER";

    /** Event type emitted when an order is scanned to {@code Packed} (Req 11.2). */
    public static final String EVENT_ORDER_PACKED = "ORDER_PACKED";

    /**
     * Event type emitted when an order becomes {@code Packed} so the courier
     * drainer can request an AWB + shipping label out-of-band (Req 12.1). Its
     * payload carries the order id/code; the drainer reloads the aggregate.
     */
    public static final String EVENT_COURIER_ASSIGN = "COURIER_ASSIGN";

    /**
     * Event type emitted when a courier assignment fails/timeouts so the admin is
     * notified while the order retains {@code Packed} (Req 12.4). Consumed by the
     * admin SSE stream (task 19).
     */
    public static final String EVENT_COURIER_ASSIGN_FAILED = "COURIER_ASSIGN_FAILED";

    /**
     * Event type emitted when an order becomes {@code Courier_Lost} and a claim
     * receivable is recorded, so the admin is told to file a claim (Req 17.4).
     */
    public static final String EVENT_CLAIM_FILED_REQUIRED = "CLAIM_FILED_REQUIRED";

    /** Event type emitted whenever a courier update changes an order's status (Req 13.3). */
    public static final String EVENT_ORDER_STATUS_CHANGED = "ORDER_STATUS_CHANGED";

    /**
     * Event type emitted when an order reaches a customer-facing lifecycle state
     * (Dispatched / Out_For_Delivery / Delivered / RTO / Courier_Lost) so the
     * WhatsApp drainer can send the pre-approved template message out-of-band
     * (Req 14.1, 14.2). Its payload carries the resolved template name and
     * ordered parameters, so the drainer never re-loads the aggregate.
     */
    public static final String EVENT_WHATSAPP_NOTIFY = "WHATSAPP_NOTIFY";

    /**
     * Event type emitted when a WhatsApp send fails after its retries are
     * exhausted, so the admin is notified and the order is flagged for review
     * (Req 14.4). Consumed by the admin SSE stream (task 19), mirroring
     * {@code COURIER_ASSIGN_FAILED}.
     */
    public static final String EVENT_WHATSAPP_FAILED = "WHATSAPP_FAILED";

    /**
     * Aggregate type discriminator for platform/system-scoped events that are not
     * tied to an order (e.g. a scheduled backup run).
     */
    public static final String AGGREGATE_SYSTEM = "SYSTEM";

    /** Aggregate type discriminator for product-scoped events (e.g. low stock). */
    public static final String AGGREGATE_PRODUCT = "PRODUCT";

    /**
     * Event type emitted when a stock decrement drives a tracked product into the
     * low-stock (or out-of-stock) band, so the admin is notified to restock.
     * Consumed by the admin SSE stream, mirroring the other admin notifications;
     * its aggregate is {@link #AGGREGATE_PRODUCT} with the product id.
     */
    public static final String EVENT_LOW_STOCK = "LOW_STOCK";

    /**
     * Event type emitted when a scheduled database backup fails (dump, gzip, or
     * upload error), so the admin is notified of the backup failure (Req 24.2).
     * Consumed by the admin SSE stream (task 19), mirroring the other
     * {@code *_FAILED} admin notifications. Its aggregate is
     * {@link #AGGREGATE_SYSTEM} (with the {@code backup_runs} row id as the
     * aggregate id) because a backup is not order-scoped.
     */
    public static final String EVENT_BACKUP_FAILED = "BACKUP_FAILED";

    /** Delivery status for a freshly written, not-yet-consumed event. */
    public static final String STATUS_PENDING = "PENDING";

    /** Delivery status for an event whose side effect completed successfully. */
    public static final String STATUS_SENT = "SENT";

    /** Delivery status for an event whose retries were exhausted. */
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_type", nullable = false, length = 40)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "json")
    private Map<String, Object> payload;

    @Column(name = "status", nullable = false, length = 20)
    private String status = STATUS_PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    protected OutboxEvent() {
        // Required by JPA.
    }

    public OutboxEvent(String aggregateType, Long aggregateId, String eventType, Map<String, Object> payload) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = STATUS_PENDING;
    }

    /**
     * Marks this event delivered: status {@code SENT}, attempt count incremented,
     * error cleared, no further attempt scheduled.
     */
    public void markSent() {
        this.attempts += 1;
        this.status = STATUS_SENT;
        this.lastError = null;
        this.nextAttemptAt = null;
    }

    /**
     * Records a failed delivery attempt while retries remain: status stays
     * {@code PENDING}, attempts is incremented, the error is retained, and the
     * next attempt is scheduled at {@code nextAttemptAt} (backoff).
     */
    public void recordRetry(String error, LocalDateTime nextAttemptAt) {
        this.attempts += 1;
        this.status = STATUS_PENDING;
        this.lastError = truncate(error);
        this.nextAttemptAt = nextAttemptAt;
    }

    /**
     * Marks this event permanently failed after exhausting retries: status
     * {@code FAILED}, attempts incremented, the error retained, no further
     * attempt scheduled (Req 12.4 tracks {@code last_error}).
     */
    public void markFailed(String error) {
        this.attempts += 1;
        this.status = STATUS_FAILED;
        this.lastError = truncate(error);
        this.nextAttemptAt = null;
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 1000 ? error.substring(0, 1000) : error;
    }

    public Long getId() {
        return id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public Long getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public String getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public LocalDateTime getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
