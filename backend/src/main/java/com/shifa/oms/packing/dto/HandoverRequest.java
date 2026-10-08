package com.shifa.oms.packing.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Optional details captured when a packed order is handed over for delivery
 * (product-audit §4.3): who it was handed to, an optional phone, and — for an
 * in-house delivery — an optional vehicle / transport reference.
 *
 * <p>All fields are optional so handover still works when the body is absent or
 * blank; {@code handoverName} (when present) is capped at 120 chars and
 * {@code handoverPhone} (when present) must be 10 digits. {@code @Pattern} treats
 * null as valid and the pattern also allows an empty string, keeping the field
 * genuinely optional.
 *
 * <p>{@code vehicleNumber} (in-house delivery feature) records the bus operator's
 * vehicle number, train number, taxi registration, own van, etc. for the leg
 * Shifa's own team arranges. An in-house order has no courier AWB, so this plus
 * {@code handoverName} is what identifies the shipment in the real world.
 *
 * @param handoverName  who the parcel was physically handed to
 * @param handoverPhone their contact number (10 digits)
 * @param vehicleNumber the in-house vehicle / transport reference
 */
public record HandoverRequest(
        @Size(max = 120, message = "handoverName must be at most 120 characters")
        String handoverName,

        @Pattern(regexp = "(\\d{10})?", message = "handoverPhone must be exactly 10 digits")
        String handoverPhone,

        @Size(max = 40, message = "vehicleNumber must be at most 40 characters")
        String vehicleNumber
) {

    /** Backward-compatible constructor for callers that capture no vehicle reference. */
    public HandoverRequest(String handoverName, String handoverPhone) {
        this(handoverName, handoverPhone, null);
    }
}
