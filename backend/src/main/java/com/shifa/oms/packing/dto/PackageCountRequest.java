package com.shifa.oms.packing.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Sets how many physical boxes/packages an order ships in (product-audit §4.2 —
 * multi-pack). Drives how many label copies are printed. Bounded to a sensible
 * 1–50 range.
 */
public record PackageCountRequest(
        @Min(value = 1, message = "packageCount must be at least 1")
        @Max(value = 50, message = "packageCount must be at most 50")
        int packageCount
) {
}
