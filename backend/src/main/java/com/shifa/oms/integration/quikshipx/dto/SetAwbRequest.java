package com.shifa.oms.integration.quikshipx.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Admin-supplied AWB for an order, read off the QuikShipX portal
 * ({@code POST /api/admin/orders/{id}/quikshipx-awb}).
 *
 * <p>The QuikShipX create-order response is undocumented and often carries no AWB, so
 * the admin enters it manually; the server then tracks the shipment by AWB, which is the
 * tracking key QuikShipX confirmed works.
 */
public record SetAwbRequest(
        @NotBlank(message = "awb is required")
        @Size(max = 60, message = "awb must be at most 60 characters")
        String awb) {
}
