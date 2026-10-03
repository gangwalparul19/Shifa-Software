package com.shifa.oms.packing.dto;

import java.util.List;

/**
 * The Packaging "Print Labels" section ({@code GET /api/packing/print-labels}):
 * Shopify orders that reached QuikShipX "Tracking ID Assigned", split into those
 * whose QuikShipX label still needs printing and those already printed.
 *
 * @param toPrint orders whose QuikShipX label has NOT been printed yet (work list)
 * @param printed orders whose QuikShipX label has already been printed
 */
public record PrintLabelQueueResponse(
        List<PrintLabelRow> toPrint,
        List<PrintLabelRow> printed) {
}
