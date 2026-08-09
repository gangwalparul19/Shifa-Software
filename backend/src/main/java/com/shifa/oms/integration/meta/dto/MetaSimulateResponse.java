package com.shifa.oms.integration.meta.dto;

/**
 * Result of the admin "simulate a Meta lead" action (spec {@code meta-lead-sync}),
 * used to demonstrate the ingest pipeline end-to-end locally without a live Meta
 * webhook.
 *
 * @param leadgenId the synthetic lead id that was pushed through the pipeline
 * @param queued    number of ingest events enqueued (1 on success)
 * @param processed number of ingest events drained this call
 * @param mockMode  whether the offline mock Graph client produced the lead data
 * @param message   a human-readable summary
 */
public record MetaSimulateResponse(String leadgenId, int queued, int processed,
                                   boolean mockMode, String message) {
}
