package com.shifa.oms.courier;

import java.math.BigDecimal;

/**
 * The details sent to the courier when requesting an AWB and shipping label for
 * a packed order (Req 12.1). Carries the order code, the delivery address, and
 * the {@code codAmount} to collect (zero for prepaid orders).
 *
 * @param orderCode      the order's human/barcode code
 * @param customerName   the recipient name
 * @param customerMobile the recipient 10-digit mobile
 * @param addressLine    the shipping address line
 * @param city           the shipping city
 * @param state          the shipping state
 * @param postalCode     the 6-digit postal code
 * @param codAmount      the amount to collect on delivery (0 when prepaid)
 */
public record CourierAssignmentRequest(
        String orderCode,
        String customerName,
        String customerMobile,
        String addressLine,
        String city,
        String state,
        String postalCode,
        BigDecimal codAmount) {
}
