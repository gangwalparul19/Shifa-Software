package com.shifa.oms.integration.shopify;

import com.shifa.oms.integration.IntegrationEvent;
import com.shifa.oms.integration.IntegrationEventRepository;
import com.shifa.oms.integration.IntegrationEventStore;
import com.shifa.oms.integration.IntegrationOutcome;
import com.shifa.oms.integration.MalformedPayloadException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Turns a stored Shopify delivery into a Shifa order (Req 3.1&ndash;3.12, 4.1, 4.5, 4.6).
 *
 * <p>Deliberately not itself transactional: it sequences two independently-committed steps
 * on {@link ShopifyOrderWriter} — create, then approve — so that a failed approval leaves
 * the created order in place rather than discarding an order the store has already taken
 * (Req 4.6). It owns the decision-making; the writer owns the persistence.
 *
 * <p>Every path ends in exactly one recorded outcome on the integration event, and the
 * distinction that matters to the caller is {@link Result#retryable()}:
 *
 * <ul>
 *   <li>a payload that cannot be read will fail identically forever, so it is settled as
 *       {@code MALFORMED_PAYLOAD} and never retried (Req 8.7);</li>
 *   <li>a failed approval or an unexpected fault may be transient, so it is reported as
 *       retryable and the drainer applies the backoff ladder (Req 2.9).</li>
 * </ul>
 *
 * <p>Idempotent by construction: an already-{@code PROCESSED} event short-circuits, an
 * existing order for the Shopify order id is resolved rather than duplicated, and approval
 * is a no-op once applied. Processing the same event twice therefore leaves the same order
 * state as processing it once (Req 2.7).
 */
@Service
public class ShopifyOrderIngestor {

    private static final Logger log = LoggerFactory.getLogger(ShopifyOrderIngestor.class);

    private final IntegrationEventRepository eventRepository;
    private final IntegrationEventStore eventStore;
    private final ShopifyOrderWriter writer;
    private final ShopifyProperties properties;

    public ShopifyOrderIngestor(IntegrationEventRepository eventRepository,
                                IntegrationEventStore eventStore,
                                ShopifyOrderWriter writer,
                                ShopifyProperties properties) {
        this.eventRepository = eventRepository;
        this.eventStore = eventStore;
        this.writer = writer;
        this.properties = properties;
    }

    /**
     * The outcome of one ingestion attempt.
     *
     * @param outcome   the outcome recorded on the integration event
     * @param orderId   the resolved order, when one exists
     * @param orderCode the resolved order's code, for the failure notification
     * @param detail    a human explanation, safe to log and to show an admin
     * @param retryable whether another attempt could plausibly succeed
     */
    public record Result(IntegrationOutcome outcome, Long orderId, String orderCode,
                         String detail, boolean retryable) {

        public boolean isSuccess() {
            return outcome.isSuccess();
        }
    }

    /**
     * Ingests the Shopify delivery stored as {@code integrationEventId}.
     *
     * <p>Never throws for a business failure: the failure is recorded on the event and
     * returned, so the drainer decides retry versus terminal without needing to interpret
     * exception types.
     */
    public Result ingest(Long integrationEventId) {
        Optional<IntegrationEvent> found = eventRepository.findById(integrationEventId);
        if (found.isEmpty()) {
            // Nothing to record the outcome against; treat as settled so the drainer
            // does not spin on a vanished row.
            return new Result(IntegrationOutcome.MALFORMED_PAYLOAD, null, null,
                    "Integration event " + integrationEventId + " no longer exists.", false);
        }
        IntegrationEvent event = found.get();

        if (event.getOutcome() == IntegrationOutcome.PROCESSED) {
            return new Result(IntegrationOutcome.PROCESSED, event.getOrderId(), null,
                    "Already ingested.", false);
        }

        if (!properties.isEnabled()) {
            // Receipt is always recorded, but with ingestion disabled no order is created.
            // The payload stays replayable, so turning the flag on and replaying is the
            // documented way to backfill (Req 15.7).
            eventStore.markOutcome(integrationEventId, IntegrationOutcome.RECEIVED,
                    "Shopify ingestion is disabled (app.shopify.enabled=false).");
            log.info("Shopify ingestion is disabled; event {} stored without creating an order",
                    integrationEventId);
            return new Result(IntegrationOutcome.RECEIVED, null, null,
                    "Shopify ingestion is disabled.", false);
        }

        ShopifyOrderModel model;
        try {
            model = ShopifyOrderPayloadCodec.parse(event.getRawPayload());
        } catch (MalformedPayloadException malformed) {
            eventStore.markOutcome(integrationEventId, IntegrationOutcome.MALFORMED_PAYLOAD,
                    malformed.getMessage());
            return new Result(IntegrationOutcome.MALFORMED_PAYLOAD, null, null,
                    malformed.getMessage(), false);
        }

        ShopifyOrderWriter.CreateResult created;
        try {
            created = writer.resolveOrCreate(model);
        } catch (RuntimeException e) {
            String detail = reasonOf(e);
            eventStore.markOutcome(integrationEventId, IntegrationOutcome.PROCESSING_FAILED, detail);
            log.warn("Failed to create an order for Shopify order {}: {}",
                    model.shopifyOrderId(), detail);
            return new Result(IntegrationOutcome.PROCESSING_FAILED, null, null, detail, true);
        }

        // Auto-approval is attempted for a newly created order AND for one an earlier
        // attempt created but could not approve, so a transient failure heals on retry
        // (Req 4.5, 4.6).
        try {
            writer.autoApprove(created.orderId());
        } catch (RuntimeException e) {
            String detail = "Automatic approval failed: " + reasonOf(e);
            eventStore.markOutcome(integrationEventId, IntegrationOutcome.PROCESSING_FAILED,
                    detail, created.orderId());
            log.warn("Order {} was created from Shopify order {} but automatic approval failed: {}",
                    created.orderCode(), model.shopifyOrderId(), detail);
            return new Result(IntegrationOutcome.PROCESSING_FAILED, created.orderId(),
                    created.orderCode(), detail, true);
        }

        String detail = created.created()
                ? "Created order " + created.orderCode()
                : "Resolved existing order " + created.orderCode();
        if (created.needsReview()) {
            detail += "; needs review: " + created.reasons();
        }
        eventStore.markOutcome(integrationEventId, IntegrationOutcome.PROCESSED, null,
                created.orderId());
        return new Result(IntegrationOutcome.PROCESSED, created.orderId(), created.orderCode(),
                detail, false);
    }

    private static String reasonOf(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
