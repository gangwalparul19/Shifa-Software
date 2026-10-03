package com.shifa.oms.packing.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A single row in the Packaging "Print Labels" section
 * ({@code GET /api/packing/print-labels}): a Shopify order that reached QuikShipX
 * "Tracking ID Assigned", carrying the QuikShipX shipping-label URL so the packer
 * can open/print it and whether it has already been printed.
 *
 * @param id             order id
 * @param orderCode      order code
 * @param customerName   customer name
 * @param totalAmount    order total
 * @param createdAt      when the order was created
 * @param awb            the allotted QuikShipX tracking id (AWB)
 * @param quikShipXOrderId  QuikShipX's own order id (shipper_order_id)
 * @param quikShipXLabelUrl the QuikShipX-hosted shipping-label PDF URL (open to print)
 * @param labelPrinted   whether the label has already been printed by the packing team
 * @param labelPrintedAt when the label was printed (null when not yet printed)
 */
public record PrintLabelRow(
        Long id,
        String orderCode,
        String customerName,
        BigDecimal totalAmount,
        LocalDateTime createdAt,
        String awb,
        String quikShipXOrderId,
        String quikShipXLabelUrl,
        boolean labelPrinted,
        LocalDateTime labelPrintedAt) {
}
