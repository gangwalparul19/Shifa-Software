package com.shifa.oms.integration.quikshipx;

import java.util.Optional;

/**
 * What QuikShipX gave back for a create-order submission.
 *
 * <p><b>Every identifier is optional.</b> The supplied specification documents the
 * request in full but shows no response body, so we do not know which fields come back
 * or what they are called. Treating any of them as required would mean the first live
 * submission fails on a guessed field name — and worse, an order would sit unpublished
 * even though QuikShipX had actually accepted it.
 *
 * <p>So the contract here is: a 2xx means accepted. {@link #orderReference()} is always
 * present because it is the value <i>we</i> sent, which is exactly why the reference,
 * not the shipment id, is the reliable correlation key.
 *
 * <p>{@link #rawResponse()} is retained and persisted so the real field names can be
 * pinned from production traffic and configured afterwards, without a redeploy.
 */
public record ShipmentAcceptance(
        String orderReference,
        String shipmentId,
        String quikshipxOrderId,
        String awb,
        String courierName,
        String trackingUrl,
        String labelUrl,
        boolean test,
        String rawResponse) {

    /** An acceptance carrying nothing but the reference we sent. */
    public static ShipmentAcceptance referenceOnly(String orderReference, boolean test, String rawResponse) {
        return new ShipmentAcceptance(orderReference, null, null, null, null, null, null, test, rawResponse);
    }

    public Optional<String> shipmentIdValue() {
        return blankToEmpty(shipmentId);
    }

    /**
     * QuikShipX's own order id ({@code order_id} in the create-order response, e.g.
     * {@code 177286}). Distinct from {@link #shipmentId()} ({@code id}) and from our
     * {@link #orderReference()}: this is the number to quote when tracking the order in the
     * QuikShipX portal or against their support.
     */
    public Optional<String> quikshipxOrderIdValue() {
        return blankToEmpty(quikshipxOrderId);
    }

    public Optional<String> awbValue() {
        return blankToEmpty(awb);
    }

    public Optional<String> courierNameValue() {
        return blankToEmpty(courierName);
    }

    public Optional<String> trackingUrlValue() {
        return blankToEmpty(trackingUrl);
    }

    public Optional<String> labelUrlValue() {
        return blankToEmpty(labelUrl);
    }

    /**
     * Whether QuikShipX returned any identifier at all. False is a valid, expected
     * state — the shipment exists, we just cannot name it yet — and the admin UI says
     * so rather than showing a blank tracking panel as if something had failed.
     */
    public boolean hasAnyIdentifier() {
        return shipmentIdValue().isPresent() || quikshipxOrderIdValue().isPresent()
                || awbValue().isPresent();
    }

    private static Optional<String> blankToEmpty(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }
}
