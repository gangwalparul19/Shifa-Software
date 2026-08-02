package com.shifa.oms.integration.quikshipx;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * A QuikShipX shipment status event, as a pure model.
 *
 * <p>SPECULATIVE shape: QuikShipX documents no status feed, so the field set is what
 * the requirements need rather than a transcription of a real payload. It is modelled
 * now so that enabling status mirroring later is a configuration change plus a parser,
 * not a redesign of the sync path.
 *
 * <p>Both identifiers are optional and at least one must be present for the event to be
 * resolvable: {@link #shipmentId()} is QuikShipX's own id, and
 * {@link #orderReference()} is the {@code customer_order_id} we sent. The reference is
 * the fallback precisely because the create response may never have given us an id
 * (Req 6.15).
 *
 * @param shipmentId     QuikShipX's shipment identifier, when the event carries one
 * @param orderReference the order reference we originally sent, when the event carries it
 * @param statusToken    the raw status token, e.g. {@code "Ready for Pickup"}
 * @param statusAt       when the status occurred; drives the monotonicity guard
 * @param eventId        the provider's event identifier, for duplicate detection
 * @param rawPayload     the event body exactly as received
 */
public record QuikShipXStatusEvent(
        String shipmentId,
        String orderReference,
        String statusToken,
        LocalDateTime statusAt,
        String eventId,
        String rawPayload) {

    public Optional<String> shipmentIdValue() {
        return blankToEmpty(shipmentId);
    }

    public Optional<String> orderReferenceValue() {
        return blankToEmpty(orderReference);
    }

    /**
     * Whether the event can be tied to a shipment at all. An event with neither
     * identifier is recorded as {@code UNKNOWN_SHIPMENT} rather than guessed at.
     */
    public boolean isResolvable() {
        return shipmentIdValue().isPresent() || orderReferenceValue().isPresent();
    }

    private static Optional<String> blankToEmpty(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }
}
