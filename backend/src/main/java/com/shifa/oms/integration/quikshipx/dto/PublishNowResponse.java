package com.shifa.oms.integration.quikshipx.dto;

import com.shifa.oms.integration.quikshipx.OrderShipment;
import com.shifa.oms.integration.quikshipx.QuikShipXPublisher;

/**
 * The result of an admin's manual "Send to QuikShipX now" request (spec
 * {@code shopify-quikshipx-order-sync}).
 *
 * <p>Deliberately verbose about <em>why</em> nothing happened. A manual trigger is used
 * precisely when an order did not publish on approval, so "it was skipped" is useless
 * without the reason — the admin needs to see "credentials missing" or "already published"
 * to know whether to fix configuration, wait, or do nothing.
 *
 * @param published     true only when this call created a new shipment
 * @param outcome       {@code PUBLISHED}, a skip reason (e.g. {@code CREDENTIALS_MISSING}),
 *                      or {@code PUBLICATION_FAILED} when QuikShipX itself rejected the call
 * @param detail        a human-readable explanation, safe to show and free of the secret
 * @param orderReference the QuikShipX order reference sent as {@code customer_order_id}
 * @param awb           the returned AWB, when the response carried one
 * @param shipmentId    the QuikShipX shipment id, when the response carried one
 * @param test          whether the order was booked under QuikShipX's Test section
 */
public record PublishNowResponse(
        boolean published,
        String outcome,
        String detail,
        String orderReference,
        String awb,
        String shipmentId,
        boolean test) {

    /** A skip: the publisher declined before calling QuikShipX. */
    public static PublishNowResponse skipped(QuikShipXPublisher.Result result) {
        return new PublishNowResponse(false, result.skip().name(), result.detail(),
                null, null, null, false);
    }

    /** A success: the shipment was created and persisted. */
    public static PublishNowResponse published(OrderShipment shipment) {
        return new PublishNowResponse(
                true, "PUBLISHED",
                "Sent to QuikShipX as " + shipment.getOrderReference()
                        + (shipment.getAwb() == null ? "" : " (AWB " + shipment.getAwb() + ")"),
                shipment.getOrderReference(),
                shipment.getAwb(),
                shipment.getQuikshipxShipmentId(),
                shipment.isTest());
    }

    /** A failure: QuikShipX rejected the call or was unreachable. */
    public static PublishNowResponse failed(String detail) {
        return new PublishNowResponse(false, "PUBLICATION_FAILED", detail,
                null, null, null, false);
    }
}
