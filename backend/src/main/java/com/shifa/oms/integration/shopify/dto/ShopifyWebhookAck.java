package com.shifa.oms.integration.shopify.dto;

/**
 * The 200 response body for an accepted Shopify order webhook delivery.
 *
 * <p>Shopify only inspects the status code, so this exists for operators reading logs and
 * for the integration tests: it says whether the delivery was newly stored, whether it was
 * a repeat, and whether asynchronous ingestion was queued.
 *
 * @param accepted   always true when returned; a rejected delivery never gets a body
 * @param duplicate  true when this event identifier was already in the event store, in
 *                   which case nothing further happened (Req 2.6)
 * @param queued     true when ingestion was enqueued for asynchronous processing (Req 2.5)
 * @param eventId    the stored {@code integration_events} row id, or null for a duplicate
 * @param detail     a short human explanation, e.g. why ingestion was not queued
 */
public record ShopifyWebhookAck(
        boolean accepted,
        boolean duplicate,
        boolean queued,
        Long eventId,
        String detail) {

    // Factory names are prefixed "of" because a record's component accessors already
    // occupy the bare names — a static duplicate() would clash with duplicate().

    public static ShopifyWebhookAck ofQueued(Long eventId) {
        return new ShopifyWebhookAck(true, false, true, eventId, "Queued for ingestion.");
    }

    public static ShopifyWebhookAck ofDuplicate() {
        return new ShopifyWebhookAck(true, true, false, null,
                "This delivery was already received; no further processing.");
    }

    public static ShopifyWebhookAck ofStoredOnly(Long eventId, String detail) {
        return new ShopifyWebhookAck(true, false, false, eventId, detail);
    }
}
