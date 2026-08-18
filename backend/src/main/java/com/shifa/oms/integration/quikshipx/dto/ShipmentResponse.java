package com.shifa.oms.integration.quikshipx.dto;

import com.shifa.oms.integration.quikshipx.OrderShipment;
import com.shifa.oms.integration.quikshipx.QuikShipXTrackDetails;

import java.time.LocalDateTime;

/**
 * Read projection of an order's courier shipment
 * ({@code GET /api/orders/{id}/shipment}).
 *
 * @param orderReference        the value sent as {@code customer_order_id}; shown so a
 *                              packer can locate the shipment in the courier portal (Req 10.6)
 * @param quikshipxShipmentId   the courier's own identifier, absent when its create-order
 *                              response carried none
 * @param awb                   the AWB / tracking number, absent until the courier issues one
 * @param courierName           the delivering carrier
 * @param trackingUrl           a customer-facing tracking link, when supplied
 * @param labelUrl              a label link, only if the create response returned one; the
 *                              courier documents no label endpoint, so this is usually absent
 * @param lastStatusToken       the last mirrored courier status token
 * @param lastStatusAt          when that status occurred
 * @param test                  booked with the TEST secret, so it is not a live shipment (Req 5.12)
 * @param statusMirroringActive whether Shifa mirrors courier status at all. False means the
 *                              fulfilment status is maintained in the courier portal, which the
 *                              UI must say rather than showing a stale status as if it were
 *                              current (Req 6.14)
 * @param labelFromPortal       true when there is no label URL, so the label is downloaded
 *                              from the courier portal (Req 10.5)
 */
public record ShipmentResponse(
        String orderReference,
        String quikshipxShipmentId,
        String quikshipxOrderId,
        String awb,
        String courierName,
        String trackingUrl,
        String labelUrl,
        String lastStatusToken,
        LocalDateTime lastStatusAt,
        boolean test,
        boolean statusMirroringActive,
        boolean labelFromPortal,
        java.util.List<Stage> timeline,
        java.util.List<Scan> scans) {

    /** One QuikShipX lifecycle stage that has occurred (e.g. Confirmed, Tracking ID Assigned). */
    public record Stage(String label, LocalDateTime at) {
    }

    /** One courier scan event (most recent first). */
    public record Scan(LocalDateTime at, String status, String location, String instructions) {
    }

    public static ShipmentResponse from(OrderShipment shipment, boolean statusMirroringActive) {
        QuikShipXTrackDetails details = QuikShipXTrackDetails.parse(shipment.getLastTrackResponse());
        java.util.List<Stage> timeline = details.timeline().stream()
                .map(s -> new Stage(s.label(), s.at()))
                .toList();
        java.util.List<Scan> scans = details.scans().stream()
                .map(s -> new Scan(s.at(), s.status(), s.location(), s.instructions()))
                .toList();
        return new ShipmentResponse(
                shipment.getOrderReference(),
                shipment.getQuikshipxShipmentId(),
                shipment.getQuikshipxOrderId(),
                shipment.getAwb(),
                shipment.getCourierName(),
                shipment.getTrackingUrl(),
                shipment.getLabelUrl(),
                shipment.getLastStatusToken(),
                shipment.getLastStatusAt(),
                shipment.isTest(),
                statusMirroringActive,
                shipment.getLabelUrl() == null || shipment.getLabelUrl().isBlank(),
                timeline,
                scans);
    }
}
