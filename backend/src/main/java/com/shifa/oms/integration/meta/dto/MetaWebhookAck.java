package com.shifa.oms.integration.meta.dto;

/**
 * Acknowledgment body returned for a Meta webhook POST (spec {@code meta-lead-sync}).
 * Meta only checks for a 200; the body is for our own logs/observability.
 *
 * @param status   short outcome token
 * @param received number of {@code leadgen} entries in the delivery
 * @param queued   number newly stored and queued for ingestion
 * @param duplicates number that were already received
 */
public record MetaWebhookAck(String status, int received, int queued, int duplicates) {

    public static MetaWebhookAck of(int received, int queued, int duplicates) {
        return new MetaWebhookAck("ok", received, queued, duplicates);
    }
}
