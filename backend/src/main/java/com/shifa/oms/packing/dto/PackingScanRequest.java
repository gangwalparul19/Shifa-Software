package com.shifa.oms.packing.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/packing/scan} (Req 11.1). Carries the
 * scanned barcode value, which is the order's {@code order_code} encoded in the
 * internal-label Code128 barcode. A blank barcode is rejected with 400 at the
 * DTO boundary.
 */
public record PackingScanRequest(
        @NotBlank(message = "A scanned barcode value is required.")
        String barcode
) {
}
