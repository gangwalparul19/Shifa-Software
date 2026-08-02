package com.shifa.oms.integration;

/**
 * The external system an {@link IntegrationEvent} came from or was sent to
 * (spec {@code shopify-quikshipx-order-sync}).
 *
 * <p>This is the {@code source} half of the {@code UNIQUE(source, external_event_id)}
 * key on {@code integration_events}. The uniqueness is deliberately SCOPED to the
 * source rather than global, so the two providers cannot collide on an identifier
 * and one provider reusing another's id format is harmless.
 */
public enum IntegrationSource {

    /** Inbound order webhooks from the Shopify storefront. */
    SHOPIFY,

    /** Outbound publications to, and (once available) status events from, QuikShipX. */
    QUIKSHIPX
}
