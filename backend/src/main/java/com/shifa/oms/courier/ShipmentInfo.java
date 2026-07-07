package com.shifa.oms.courier;

import java.time.LocalDate;

/**
 * Immutable shipment projection for an order, built from its {@link CourierRecord}
 * and {@link CourierCompany}. Exposes the AWB, courier display name, the
 * customer-facing tracking link (built from the company's
 * {@code tracking_url_template}, Req 13.4), and the estimated delivery date
 * (Req 14.1). Shared by the public {@code /api/track} view and the admin
 * order-detail response so tracking-link building is not duplicated.
 */
public record ShipmentInfo(
        String awb,
        String courierName,
        String trackingUrl,
        LocalDate estimatedDelivery
) {
}
