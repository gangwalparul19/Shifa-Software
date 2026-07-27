package com.shifa.oms.packing.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Optional details captured when a packed order is handed over to the delivery
 * courier (product-audit §4.3): who it was handed to and an optional phone.
 *
 * <p>Both fields are optional so handover still works when the body is absent or
 * blank; {@code handoverName} (when present) is capped at 120 chars and
 * {@code handoverPhone} (when present) must be 10 digits. {@code @Pattern} treats
 * null as valid and the pattern also allows an empty string, keeping the field
 * genuinely optional.
 */
public record HandoverRequest(
        @Size(max = 120, message = "handoverName must be at most 120 characters")
        String handoverName,

        @Pattern(regexp = "(\\d{10})?", message = "handoverPhone must be exactly 10 digits")
        String handoverPhone
) {
}
