package com.shifa.oms.integration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * One inbound integration delivery, or one outbound publication attempt, mapped to
 * the {@code integration_events} table (V49).
 *
 * <p>This is the audit trail and the replay source for the whole feature: the raw
 * payload is stored verbatim so an admin can re-run a failed delivery through the
 * same code path a live one takes (Req 14.4), and so the real QuikShipX response
 * field names can be pinned from production traffic.
 *
 * <p>{@code UNIQUE(source, external_event_id)} is what makes duplicate webhook
 * deliveries a no-op — providers retry aggressively, and Shopify especially so.
 */
@Entity
@Table(name = "integration_events")
public class IntegrationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private IntegrationSource source;

    /**
     * The provider's own identifier for this delivery (Shopify's webhook id, or
     * for an outbound publication the Shifa order code). Unique per source.
     */
    @Column(name = "external_event_id", nullable = false, length = 180)
    private String externalEventId;

    /** The provider's event topic, e.g. {@code orders/create}. */
    @Column(name = "event_topic", length = 80)
    private String eventTopic;

    /** The delivery body exactly as received, as UTF-8 text. Replay re-feeds this. */
    @Column(name = "raw_payload", columnDefinition = "LONGTEXT")
    private String rawPayload;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 30)
    private IntegrationOutcome outcome = IntegrationOutcome.RECEIVED;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    /** The Shifa order this event resolved to, once known. */
    @Column(name = "order_id")
    private Long orderId;

    /** The received status token, retained verbatim for an unmapped status (Req 7.4). */
    @Column(name = "status_token", length = 80)
    private String statusToken;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "replayed_at")
    private LocalDateTime replayedAt;

    @Column(name = "replayed_by", length = 150)
    private String replayedBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    protected IntegrationEvent() {
        // Required by JPA.
    }

    public IntegrationEvent(IntegrationSource source, String externalEventId, String eventTopic,
                            String rawPayload, LocalDateTime receivedAt) {
        this.source = source;
        this.externalEventId = externalEventId;
        this.eventTopic = eventTopic;
        this.rawPayload = rawPayload;
        this.receivedAt = receivedAt;
        this.outcome = IntegrationOutcome.RECEIVED;
    }

    /**
     * Records a terminal outcome. {@code processedAt} is stamped for every terminal
     * verdict, success or failure, so "when did we stop working on this" is always
     * answerable in the health console.
     */
    public void recordOutcome(IntegrationOutcome outcome, String failureReason, LocalDateTime at) {
        this.outcome = outcome;
        this.failureReason = truncate(failureReason, 1000);
        this.processedAt = at;
    }

    /** Notes the replay that produced the current outcome (Req 14.4). */
    public void recordReplay(String replayedBy, LocalDateTime at) {
        this.replayedBy = truncate(replayedBy, 150);
        this.replayedAt = at;
    }

    public void incrementAttempts() {
        this.attemptCount++;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public void setStatusToken(String statusToken) {
        this.statusToken = truncate(statusToken, 80);
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = Math.max(0, attemptCount);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public Long getId() {
        return id;
    }

    public IntegrationSource getSource() {
        return source;
    }

    public String getExternalEventId() {
        return externalEventId;
    }

    public String getEventTopic() {
        return eventTopic;
    }

    public String getRawPayload() {
        return rawPayload;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }

    public IntegrationOutcome getOutcome() {
        return outcome;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getStatusToken() {
        return statusToken;
    }

    public LocalDateTime getProcessedAt() {
        return processedAt;
    }

    public LocalDateTime getReplayedAt() {
        return replayedAt;
    }

    public String getReplayedBy() {
        return replayedBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
