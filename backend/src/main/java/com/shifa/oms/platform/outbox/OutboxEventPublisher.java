package com.shifa.oms.platform.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes {@link OutboxEvent} rows within the caller's current transaction
 * (design: "transactional outbox").
 *
 * <p>Callers invoke this from inside an already-open {@code @Transactional}
 * service method so the event row commits atomically with the domain change
 * that produced it. This publisher does no I/O and starts no transaction of its
 * own — it only enqueues the row for a downstream consumer to deliver.
 *
 * <p>Introduced by task 12 for the {@code ORDER_PACKED} event (Req 11.2); it is
 * intentionally generic so later tasks (courier assign failed, WhatsApp failed,
 * claim required, status changed) reuse it.
 */
@Service
public class OutboxEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventPublisher.class);

    private final OutboxEventRepository repository;

    /**
     * Best-effort side-consumers notified after each event is written (e.g. the
     * admin notifications center persisting a durable row). Empty when none are
     * registered; each is isolated in a try/catch so a sink failure never breaks
     * the originating operation.
     */
    private final List<OutboxEventSink> sinks;

    /** Test/convenience constructor with no side sinks. */
    public OutboxEventPublisher(OutboxEventRepository repository) {
        this(repository, List.of());
    }

    @Autowired
    public OutboxEventPublisher(OutboxEventRepository repository, List<OutboxEventSink> sinks) {
        this.repository = repository;
        this.sinks = sinks == null ? List.of() : sinks;
    }

    /**
     * Enqueues a generic outbox event in the current transaction.
     *
     * @param aggregateType the aggregate discriminator (e.g. {@code ORDER})
     * @param aggregateId   the aggregate id the event concerns
     * @param eventType     the event type name (e.g. {@code ORDER_PACKED})
     * @param payload       structured JSON context for consumers (may be empty)
     * @return the persisted event row
     */
    public OutboxEvent publish(String aggregateType, Long aggregateId, String eventType,
                               Map<String, Object> payload) {
        OutboxEvent saved = repository.save(new OutboxEvent(aggregateType, aggregateId, eventType, payload));
        notifySinks(saved);
        return saved;
    }

    /**
     * Notifies each registered {@link OutboxEventSink} of the just-saved event.
     * Best-effort: a sink that throws is logged and skipped so it can never break
     * the originating operation that enqueued the event.
     */
    private void notifySinks(OutboxEvent event) {
        for (OutboxEventSink sink : sinks) {
            try {
                sink.onEventPublished(event);
            } catch (RuntimeException e) {
                log.warn("Outbox event sink {} failed for event {} ({}): {}",
                        sink.getClass().getSimpleName(), event.getId(), event.getEventType(),
                        e.getMessage());
            }
        }
    }

    /**
     * Enqueues an {@code ORDER_PACKED} event for the admin real-time notification
     * (Req 11.2). Task 19's SSE publisher consumes {@code PENDING} rows of this
     * type; because the row is persisted regardless of whether an admin is
     * connected, the notification is never lost.
     *
     * @param orderId      the packed order's id
     * @param orderCode    the packed order's human/barcode code
     * @param customerName the customer name, for display in the notification
     * @param packedBy      the packing user who scanned the order
     * @return the persisted event row
     */
    public OutboxEvent publishOrderPacked(Long orderId, String orderCode,
                                          String customerName, String packedBy) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId);
        payload.put("orderCode", orderCode);
        payload.put("customerName", customerName);
        payload.put("packedBy", packedBy);
        return publish(OutboxEvent.AGGREGATE_ORDER, orderId, OutboxEvent.EVENT_ORDER_PACKED, payload);
    }

    /**
     * Enqueues a {@code COURIER_ASSIGN} event for an order that has just become
     * {@code Packed} (Req 12.1). The courier drainer (task 14) picks this up and
     * requests the AWB + shipping label out-of-band, so a slow or unavailable
     * courier API never blocks the packing scan.
     *
     * @param orderId   the packed order's id
     * @param orderCode the packed order's human/barcode code
     * @return the persisted event row
     */
    public OutboxEvent publishCourierAssign(Long orderId, String orderCode) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId);
        payload.put("orderCode", orderCode);
        return publish(OutboxEvent.AGGREGATE_ORDER, orderId, OutboxEvent.EVENT_COURIER_ASSIGN, payload);
    }

    /**
     * Enqueues a {@code COURIER_ASSIGN_FAILED} admin notification when a courier
     * assignment errors or times out and the order retains {@code Packed}
     * (Req 12.4). Consumed by the admin SSE stream (task 19).
     *
     * @param orderId   the order whose assignment failed
     * @param orderCode the order code, for display
     * @param error     the failure detail
     * @return the persisted event row
     */
    public OutboxEvent publishCourierAssignFailed(Long orderId, String orderCode, String error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId);
        payload.put("orderCode", orderCode);
        payload.put("error", error);
        return publish(OutboxEvent.AGGREGATE_ORDER, orderId,
                OutboxEvent.EVENT_COURIER_ASSIGN_FAILED, payload);
    }

    /**
     * Queues publication of an approved {@code SHIFA_ADMIN} order to QuikShipX
     * (Req 5.1). Written in the same transaction as the approval transition, so an
     * approval cannot commit without its publication being queued.
     *
     * @param orderId   the order to publish
     * @param orderCode the order code, carried so a failure notification can name it
     *                  without reloading the aggregate
     * @return the persisted event row
     */
    public OutboxEvent publishQuikShipXPublish(Long orderId, String orderCode) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId);
        payload.put("orderCode", orderCode);
        return publish(OutboxEvent.AGGREGATE_ORDER, orderId,
                OutboxEvent.EVENT_QUIKSHIPX_PUBLISH, payload);
    }

    /**
     * Records that publication to QuikShipX failed terminally, driving an in-app ADMIN
     * notification (Req 5.7, 5.11). The order retains {@code Approved}.
     *
     * @param error the failure reason, already free of credentials
     * @return the persisted event row
     */
    public OutboxEvent publishQuikShipXPublishFailed(Long orderId, String orderCode, String error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId);
        payload.put("orderCode", orderCode);
        payload.put("error", error);
        return publish(OutboxEvent.AGGREGATE_ORDER, orderId,
                OutboxEvent.EVENT_QUIKSHIPX_PUBLISH_FAILED, payload);
    }

    /**
     * Queues ingestion of a stored Shopify order webhook (Req 2.5).
     *
     * <p>The aggregate is the integration event rather than an order, because at this point
     * no order exists yet — that is what ingestion will decide.
     *
     * @param integrationEventId the stored {@code integration_events} row holding the raw body
     * @param shopifyOrderId     Shopify's order id, carried for log and failure context
     */
    public OutboxEvent publishShopifyOrderIngest(Long integrationEventId, String shopifyOrderId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("integrationEventId", integrationEventId);
        payload.put("shopifyOrderId", shopifyOrderId);
        return publish(OutboxEvent.AGGREGATE_ORDER, integrationEventId,
                OutboxEvent.EVENT_SHOPIFY_ORDER_INGEST, payload);
    }

    /**
     * Records that Shopify ingestion failed terminally, driving an in-app ADMIN
     * notification (Req 2.10). The stored payload remains replayable.
     */
    public OutboxEvent publishShopifyIngestFailed(Long integrationEventId, String shopifyOrderId,
                                                  String error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("integrationEventId", integrationEventId);
        payload.put("shopifyOrderId", shopifyOrderId);
        payload.put("error", error);
        return publish(OutboxEvent.AGGREGATE_ORDER, integrationEventId,
                OutboxEvent.EVENT_SHOPIFY_INGEST_FAILED, payload);
    }

    /**
     * Enqueues a {@code CLAIM_FILED_REQUIRED} admin notification when an order
     * becomes {@code Redispatch} and a claim receivable is recorded (Req 17.4).
     *
     * @param orderId the lost order's id
     * @param orderCode the order code, for display
     * @param awb     the AWB the claim should be filed against
     * @param amount  the claim amount
     * @return the persisted event row
     */
    public OutboxEvent publishClaimFiledRequired(Long orderId, String orderCode, String awb, String amount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId);
        payload.put("orderCode", orderCode);
        payload.put("awb", awb);
        payload.put("amount", amount);
        return publish(OutboxEvent.AGGREGATE_ORDER, orderId,
                OutboxEvent.EVENT_CLAIM_FILED_REQUIRED, payload);
    }

    /**
     * Enqueues an {@code ORDER_STATUS_CHANGED} event so the admin dashboard can
     * reflect a courier-driven status change in real time (Req 13.3).
     *
     * @param orderId   the order id
     * @param orderCode the order code
     * @param newStatus the new internal status name
     * @return the persisted event row
     */
    public OutboxEvent publishOrderStatusChanged(Long orderId, String orderCode, String newStatus) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId);
        payload.put("orderCode", orderCode);
        payload.put("newStatus", newStatus);
        return publish(OutboxEvent.AGGREGATE_ORDER, orderId,
                OutboxEvent.EVENT_ORDER_STATUS_CHANGED, payload);
    }

    /**
     * Enqueues a {@code WHATSAPP_NOTIFY} event carrying a fully-resolved,
     * pre-approved WhatsApp template message (Req 14.1, 14.2, 14.3). The WhatsApp
     * drainer (task 15) consumes {@code PENDING} rows of this type and sends the
     * message via the {@code WhatsAppClient} with bounded retries; because the
     * resolved template name and parameters are stored in the payload, the
     * drainer never needs to re-load the order aggregate.
     *
     * @param orderId         the order the notification concerns
     * @param orderCode       the order code, for display/traceability
     * @param event           the lifecycle event name (e.g. {@code DISPATCHED})
     * @param recipientMobile the customer mobile the message is addressed to
     * @param templateName    the pre-approved Meta template name
     * @param parameters      the ordered template parameters as
     *                        {@code {name, value}} maps
     * @return the persisted event row
     */
    public OutboxEvent publishWhatsAppNotify(Long orderId, String orderCode, String event,
                                             String recipientMobile, String templateName,
                                             List<Map<String, String>> parameters) {
        return publishWhatsAppNotify(orderId, orderCode, event, recipientMobile,
                templateName, parameters, null);
    }

    /**
     * Overload carrying the creating salesperson's user id on the payload
     * ({@code salespersonUserId}, from {@code OrderEntity.createdBy}), so
     * order-scoped notifications can be traced/addressed to the salesperson who
     * created the order (Req 7.3, design §5.2). A {@code null} id is omitted.
     */
    public OutboxEvent publishWhatsAppNotify(Long orderId, String orderCode, String event,
                                             String recipientMobile, String templateName,
                                             List<Map<String, String>> parameters,
                                             Long salespersonUserId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId);
        payload.put("orderCode", orderCode);
        payload.put("event", event);
        payload.put("recipientMobile", recipientMobile);
        payload.put("templateName", templateName);
        payload.put("parameters", new ArrayList<Map<String, String>>(parameters));
        if (salespersonUserId != null) {
            payload.put("salespersonUserId", salespersonUserId);
        }
        return publish(OutboxEvent.AGGREGATE_ORDER, orderId,
                OutboxEvent.EVENT_WHATSAPP_NOTIFY, payload);
    }

    /**
     * Enqueues an {@code EMAIL_NOTIFY} event carrying a fully-resolved customer
     * milestone email (Req 7.2, 10.7, 11.4, 14.1), mirroring
     * {@link #publishWhatsAppNotify}. The {@code EmailOutboxDrainer} consumes
     * {@code PENDING} rows of this type and sends via the {@code MailService} with
     * bounded retries; because the resolved recipient/subject/body are stored on
     * the payload, the drainer never re-loads the order aggregate. The event row
     * commits atomically with the status change.
     *
     * @param orderId           the order the email concerns
     * @param orderCode         the order code, for display/traceability
     * @param event             the lifecycle event name (e.g. {@code DISPATCHED})
     * @param recipientEmail    the customer email the message is addressed to
     * @param subject           the resolved subject line
     * @param body              the resolved plain-text body
     * @param salespersonUserId the creating salesperson's user id (nullable)
     * @return the persisted event row
     */
    public OutboxEvent publishEmailNotify(Long orderId, String orderCode, String event,
                                          String recipientEmail, String subject, String body,
                                          Long salespersonUserId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId);
        payload.put("orderCode", orderCode);
        payload.put("event", event);
        payload.put("recipientEmail", recipientEmail);
        payload.put("subject", subject);
        payload.put("body", body);
        if (salespersonUserId != null) {
            payload.put("salespersonUserId", salespersonUserId);
        }
        return publish(OutboxEvent.AGGREGATE_ORDER, orderId,
                OutboxEvent.EVENT_EMAIL_NOTIFY, payload);
    }

    /**
     * Enqueues an {@code EMAIL_FAILED} admin notification when a customer email
     * send fails after exhausting its retries, flagging the order for admin
     * review (Req 14.5). Consumed by the admin notifications center, mirroring
     * {@link #publishWhatsAppFailed}.
     *
     * @param orderId   the order whose email failed
     * @param orderCode the order code, for display
     * @param subject   the subject that failed to send
     * @param error     the failure detail
     * @return the persisted event row
     */
    public OutboxEvent publishEmailFailed(Long orderId, String orderCode,
                                          String subject, String error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId);
        payload.put("orderCode", orderCode);
        payload.put("subject", subject);
        payload.put("error", error);
        return publish(OutboxEvent.AGGREGATE_ORDER, orderId,
                OutboxEvent.EVENT_EMAIL_FAILED, payload);
    }

    /**
     * Enqueues a {@code WHATSAPP_FAILED} admin notification when a WhatsApp send
     * fails after exhausting its retries, flagging the order for admin review
     * (Req 14.4). Consumed by the admin SSE stream (task 19), mirroring
     * {@code COURIER_ASSIGN_FAILED}.
     *
     * @param orderId      the order whose notification failed
     * @param orderCode    the order code, for display
     * @param templateName the template that failed to send
     * @param error        the failure detail
     * @return the persisted event row
     */
    public OutboxEvent publishWhatsAppFailed(Long orderId, String orderCode,
                                             String templateName, String error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId);
        payload.put("orderCode", orderCode);
        payload.put("templateName", templateName);
        payload.put("error", error);
        return publish(OutboxEvent.AGGREGATE_ORDER, orderId,
                OutboxEvent.EVENT_WHATSAPP_FAILED, payload);
    }

    /**
     * Enqueues a {@code BACKUP_FAILED} admin notification when a scheduled
     * database backup fails (Req 24.2). Consumed by the admin SSE stream
     * (task 19), mirroring {@code COURIER_ASSIGN_FAILED} / {@code WHATSAPP_FAILED}:
     * because the row is persisted regardless of whether an admin is connected,
     * the backup-failure alert is never lost.
     *
     * <p>The event is {@link OutboxEvent#AGGREGATE_SYSTEM system}-scoped rather
     * than order-scoped; {@code backupRunId} is used as the aggregate id so the
     * originating {@code backup_runs} row can be located.
     *
     * @param backupRunId the id of the {@code backup_runs} row recorded as FAILED
     * @param backupDate  the intended backup date (e.g. {@code 2024-05-01}), for display
     * @param error       the failure detail (dump/gzip/upload error text)
     * @return the persisted event row
     */
    public OutboxEvent publishBackupFailed(Long backupRunId, String backupDate, String error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("backupRunId", backupRunId);
        payload.put("backupDate", backupDate);
        payload.put("error", error);
        return publish(OutboxEvent.AGGREGATE_SYSTEM, backupRunId,
                OutboxEvent.EVENT_BACKUP_FAILED, payload);
    }

    /**
     * Enqueues a {@code LOW_STOCK} admin notification when a stock decrement
     * crosses a tracked product into the low-stock (or out-of-stock) band, so the
     * admin is prompted to restock. Consumed by the admin SSE stream, mirroring
     * the other {@code *_FAILED}/admin notifications; because the row is persisted
     * regardless of whether an admin is connected, the alert is never lost.
     *
     * <p>The event is {@link OutboxEvent#AGGREGATE_PRODUCT product}-scoped with
     * the product id as the aggregate id.
     *
     * @param productId    the product that became low/out of stock
     * @param sku          the product SKU, for display
     * @param name         the product name, for display
     * @param stockQuantity the on-hand quantity after the decrement
     * @param threshold    the low-stock threshold that was crossed
     * @param outOfStock   {@code true} when the product is now out of stock (0)
     * @return the persisted event row
     */
    public OutboxEvent publishLowStock(Long productId, String sku, String name,
                                       int stockQuantity, int threshold, boolean outOfStock) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("productId", productId);
        payload.put("sku", sku);
        payload.put("name", name);
        payload.put("stockQuantity", stockQuantity);
        payload.put("threshold", threshold);
        payload.put("outOfStock", outOfStock);
        return publish(OutboxEvent.AGGREGATE_PRODUCT, productId,
                OutboxEvent.EVENT_LOW_STOCK, payload);
    }

    /**
     * Enqueues a {@code LEAD_FOLLOW_UP_DUE} event for a lead whose follow-up date
     * is due, so an in-app reminder is delivered to the lead owner (design
     * &sect;Follow-up Reminders). Lead-scoped ({@link OutboxEvent#AGGREGATE_LEAD})
     * with the lead id as the aggregate id; the returned event's id is used as the
     * de-dup {@code sourceEventId} of the staff notification it drives.
     *
     * @param leadId       the due lead's id
     * @param customerName the lead's customer name, for display
     * @param ownerUserId  the lead owner the reminder is addressed to
     * @param followUpDate the due follow-up date (ISO string), for display
     * @return the persisted event row
     */
    public OutboxEvent publishLeadFollowUpDue(Long leadId, String customerName,
                                              Long ownerUserId, String followUpDate) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("leadId", leadId);
        payload.put("customerName", customerName);
        payload.put("ownerUserId", ownerUserId);
        payload.put("followUpDate", followUpDate);
        return publish(OutboxEvent.AGGREGATE_LEAD, leadId,
                OutboxEvent.EVENT_LEAD_FOLLOW_UP_DUE, payload);
    }
}
