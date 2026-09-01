package com.shifa.oms.quikshipx;

import com.shifa.oms.quikshipx.QuikShipXModels.AllotResult;
import com.shifa.oms.quikshipx.QuikShipXModels.CreatePayload;
import com.shifa.oms.quikshipx.QuikShipXModels.CreateResult;
import com.shifa.oms.quikshipx.QuikShipXModels.TrackResult;

/**
 * The client contract for the three QuikShipX operations, with a
 * {@link MockQuikShipXClient} (deterministic, no network) and an
 * {@link HttpQuikShipXClient} selected by {@code app.quikshipx.mode}. Mirrors the
 * existing {@code CourierClient} pattern. The client injects the shipper
 * credentials from {@link QuikShipXProperties}, so callers never handle the
 * secret.
 */
public interface QuikShipXClient {

    /**
     * Creates a shipment ({@code POST /api/create-order-v1}). The order appears in
     * QuikShipX's Pending (LIVE secret) or Test (TEST secret) section.
     *
     * @param payload the customer/shipment/product sections; the client adds
     *                {@code shipper_details}
     * @return the acceptance, carrying QuikShipX's order id when parseable
     * @throws QuikShipXException on transport failure, timeout, or rejection
     */
    CreateResult createOrder(CreatePayload payload) throws QuikShipXException;

    /**
     * Allots a tracking id (AWB) for a previously-created shipment
     * ({@code POST /api/allot-tracking-id-v1}).
     *
     * @param shipperOrderId QuikShipX's order id from {@link #createOrder}
     * @return the AWB, courier, and label URL
     * @throws QuikShipXException on transport failure, timeout, or a not-ready /
     *         rejection response
     */
    AllotResult allotTrackingId(String shipperOrderId) throws QuikShipXException;

    /**
     * The current status of a shipment by AWB ({@code POST /api/track-order-v1}).
     *
     * @param awb the AWB to track
     * @return the current QuikShipX status
     * @throws QuikShipXException on transport failure, timeout, or a not-trackable
     *         / rejection response
     */
    TrackResult trackOrder(String awb) throws QuikShipXException;
}
