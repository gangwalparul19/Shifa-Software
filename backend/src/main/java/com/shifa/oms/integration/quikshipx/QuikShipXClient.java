package com.shifa.oms.integration.quikshipx;

/**
 * The single client contract for QuikShipX (Req 15.9), with a mock and an HTTP
 * implementation selected by {@code app.quikshipx.mode}. Mirrors the existing
 * {@code CourierClient} / WhatsApp client pattern.
 *
 * <p>Only one operation is confirmed by the contract
 * ({@code docs/QUIKSHIPX-API-V1.md}): create-order. {@link #fetchStatus} is declared
 * because the requirements specify status mirroring end to end, but no status endpoint
 * is documented, so it is only reachable when
 * {@code app.quikshipx.status-feed-available=true} and the HTTP implementation refuses
 * it until QuikShipX publishes one.
 *
 * <p>There is deliberately <b>no label operation</b>: QuikShipX documents none, so the
 * packing team downloads labels from the QuikShipX portal and Shifa surfaces the
 * label URL only if a create response happens to return one.
 */
public interface QuikShipXClient {

    /**
     * Creates a shipment ({@code POST /api/create-order-v1}).
     *
     * <p>Idempotency rests on {@code customer_order_id} carrying the same
     * {@link OrderReference} on the first attempt and every retry, so a retry after an
     * ambiguous timeout should not create a second shipment.
     *
     * @throws QuikShipXClientException on transport failure, timeout, or a non-2xx
     *         response; the exception says whether retrying could help
     */
    ShipmentAcceptance createShipment(ShipmentSubmission submission) throws QuikShipXClientException;

    /**
     * The current status of a shipment via the QuikShipX track-order API
     * ({@code POST /api/track-order-v1}).
     *
     * @param trackingNo   the AWB (when {@code trackingType} is {@code awb}) or the
     *                     order id (when {@code order_id})
     * @param trackingType {@code "awb"} or {@code "order_id"}
     * @throws QuikShipXClientException on transport failure, timeout, or a non-2xx
     *         response; the exception says whether retrying could help
     */
    QuikShipXStatusEvent fetchStatus(String trackingNo, String trackingType)
            throws QuikShipXClientException;
}
