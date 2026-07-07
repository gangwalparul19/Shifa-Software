package com.shifa.oms.courier;

import java.time.LocalDate;

/**
 * The courier's response to an AWB request (Req 12.2): the assigned air-waybill
 * number, the courier company name (resolved to a {@link CourierCompany} row),
 * and the estimated delivery date used in dispatch notifications (Req 14.1).
 *
 * <p>The shipping-label PDF is rendered locally by
 * {@link ShippingLabelService} from the order + AWB rather than returned here, so
 * the label always reflects the current COD amount and internal formatting.
 *
 * @param awb               the assigned AWB (never blank on success)
 * @param courierName       the courier company name
 * @param estimatedDelivery the estimated delivery date, or {@code null}
 */
public record CourierAssignmentResult(
        String awb,
        String courierName,
        LocalDate estimatedDelivery) {
}
