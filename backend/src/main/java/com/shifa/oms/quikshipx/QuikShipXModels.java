package com.shifa.oms.quikshipx;

import java.util.List;
import java.util.Map;

/**
 * Value types exchanged with {@link QuikShipXClient}: the create-order request
 * payload and the three response projections. Kept as plain records so the
 * client stays thin and the payload building / response parsing are pure and
 * testable.
 */
public final class QuikShipXModels {

    private QuikShipXModels() {
    }

    /**
     * The create-order request body sections Shifa builds from an order (the
     * client adds {@code shipper_details} from configured credentials). Each map
     * mirrors the QuikShipX JSON field names; every value is a string per the
     * contract.
     *
     * @param orderCode        the Shifa order code (also sent as {@code customer_order_id})
     * @param customerDetails  the {@code customer_details} object
     * @param shipmentDetails  the {@code shipment_details} object
     * @param productDetails   the {@code product_details} array
     */
    public record CreatePayload(
            String orderCode,
            Map<String, Object> customerDetails,
            Map<String, Object> shipmentDetails,
            List<Map<String, Object>> productDetails) {
    }

    /**
     * The outcome of a create-order call. {@link #shipperOrderId} is QuikShipX's
     * own order id (the number quoted when allotting a tracking id), parsed
     * best-effort from the undocumented response; it may be {@code null} when the
     * response does not carry one (the shipment is still created — Pending — but
     * a tracking id cannot be allotted until the id is known).
     */
    public record CreateResult(String shipperOrderId, String rawResponse) {
    }

    /**
     * The outcome of an allot-tracking-id call (documented response:
     * {@code response[0].tracking_details}).
     *
     * @param awb            {@code tracking_id} — the AWB
     * @param courierId      {@code courier_id}
     * @param subCourierName {@code sub_courier_name} (e.g. {@code Direct_Delhivery})
     * @param labelUrl       {@code pdf_label_url}
     */
    public record AllotResult(String awb, String courierId, String subCourierName, String labelUrl) {
    }

    /**
     * The outcome of a track-order call (documented response:
     * {@code response[0].shipment_details}).
     *
     * @param awb          {@code tracking_no}
     * @param orderStatus  {@code order_status} (e.g. {@code out for delivery})
     * @param orderStatusId {@code order_status_id}
     */
    public record TrackResult(String awb, String orderStatus, String orderStatusId) {
    }
}
