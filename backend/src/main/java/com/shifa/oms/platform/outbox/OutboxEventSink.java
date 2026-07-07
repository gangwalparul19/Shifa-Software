package com.shifa.oms.platform.outbox;

/**
 * A best-effort side-consumer notified synchronously (in the publishing
 * transaction) each time an {@link OutboxEvent} is written by
 * {@link OutboxEventPublisher}.
 *
 * <p>This is the extension point used by the admin notifications center to
 * persist a durable {@code admin_notifications} row when an admin-facing event
 * (order packed, status changed, claim required, courier-assign failed,
 * whatsapp failed, backup failed, low stock) is published — <em>without</em>
 * touching the SSE relay contract, which continues to consume the same outbox
 * rows independently.
 *
 * <p><strong>Contract:</strong> implementations must be best-effort and select
 * only the event types they care about; the publisher isolates each sink in a
 * try/catch so a sink failure never breaks the originating operation. A sink
 * runs inside the caller's transaction, so a persisted side record commits
 * atomically with the event that produced it (mirroring the outbox row itself).
 */
public interface OutboxEventSink {

    /**
     * Called after an outbox event row has been saved, within the publishing
     * transaction. Implementations should return quietly for event types they do
     * not handle and must not assume the outer transaction will commit.
     *
     * @param event the just-persisted outbox event
     */
    void onEventPublished(OutboxEvent event);
}
