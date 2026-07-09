package com.shifa.oms.lead.dto;

import com.shifa.oms.order.dto.LineItemRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Convert-to-order payload for {@code POST /api/leads/{id}/convert} (Requirement
 * 4, design &sect;Convert Flow, &sect;API).
 *
 * <p>This is the {@code CreateOrderRequest}-shaped slice the client supplies when
 * turning a lead into an order: the shipping address, at least one line item, and
 * the payment fields. The customer identity ({@code customerName} /
 * {@code customerMobile} / {@code customerEmail}) and the {@code leadSource}
 * (+ note) are <strong>not</strong> accepted here — the server forces them from
 * the lead being converted so a converted order always carries the lead's origin
 * channel (Req 4.2, 4.3) and cannot be re-pointed at a different customer.
 *
 * <p>Field-level bean validation mirrors {@code CreateOrderRequest} for the
 * client-supplied fields; the order module re-checks payment rules (screenshot
 * required when money was received, {@code amountReceived <= total}) in
 * {@code OrderService.createSalespersonOrder}.
 */
public record LeadConvertRequest(
        @NotBlank(message = "addressLine is required")
        @Size(max = 250, message = "addressLine must be at most 250 characters")
        String addressLine,

        @NotBlank(message = "city is required")
        @Size(max = 100, message = "city must be at most 100 characters")
        String city,

        @NotBlank(message = "state is required")
        @Size(max = 100, message = "state must be at most 100 characters")
        String state,

        @NotBlank(message = "postalCode is required")
        @Pattern(regexp = "\\d{6}", message = "postalCode must be exactly 6 digits")
        String postalCode,

        @NotEmpty(message = "at least one line item is required")
        @Valid
        List<LineItemRequest> items,

        @NotNull(message = "amountReceived is required")
        @DecimalMin(value = "0.00", message = "amountReceived must not be negative")
        @Digits(integer = 10, fraction = 2, message = "amountReceived must be a DECIMAL(12,2) value")
        BigDecimal amountReceived,

        String paymentScreenshotKey,

        @Size(max = 1000, message = "notes must be at most 1000 characters")
        String notes
) {
}
