package com.shifa.oms.courier.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for manually assigning a courier / delivery partner (and, when
 * there is one, an AWB) to an order (enhancement: "assign courier early", see
 * {@code CourierAssignmentService#manuallyAssign}).
 *
 * <p>{@code awb} is <strong>optional</strong> (in-house-delivery feature): an
 * in-house delivery — or a parcel handed to a local operator / bus / train — has
 * no tracking number at all. When it is omitted the label falls back to printing
 * our own order-code barcode, which the packing/RTO scan flow resolves, so the
 * parcel stays scannable end-to-end without an AWB.
 *
 * @param courierName the delivery partner's display name (matched/created by name)
 * @param awb         the AWB / tracking number, or {@code null}/blank when there is none
 */
public record AssignCourierRequest(
        @NotBlank @Size(max = 150) String courierName,
        @Size(max = 64) String awb) {
}
