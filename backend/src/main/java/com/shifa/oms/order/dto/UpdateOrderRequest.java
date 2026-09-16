package com.shifa.oms.order.dto;

import com.shifa.oms.order.LeadSource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Admin edit-order payload for {@code PUT /api/admin/orders/{id}} (edit-order
 * feature): lets an admin correct the details a salesperson entered — customer
 * identity, shipping address, line items, lead source, note, buyer GSTIN, and
 * the order-level discount. Mirrors {@link CreateOrderRequest} minus the
 * payment-capture fields ({@code amountReceived}/{@code paymentScreenshotKey}),
 * which are not part of a field correction.
 *
 * <p>Re-priced through the same {@code OrderPricing} engine used at creation,
 * so totals/GST/discount stay reconciled with the edited items. Only allowed
 * while the order is still early in its lifecycle (before a label has been
 * generated) — see {@code OrderService.updateOrder}.
 */
public record UpdateOrderRequest(
        @NotBlank(message = "customerName is required")
        @Size(max = 100, message = "customerName must be at most 100 characters")
        String customerName,

        @NotBlank(message = "customerMobile is required")
        @Pattern(regexp = "\\d{10}", message = "customerMobile must be exactly 10 digits")
        String customerMobile,

        @Pattern(regexp = "(\\d{10})?", message = "alternateMobile must be exactly 10 digits")
        String alternateMobile,

        @Email(message = "customerEmail must be a valid email address")
        @Size(max = 150, message = "customerEmail must be at most 150 characters")
        String customerEmail,

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

        @NotNull(message = "leadSource is required")
        LeadSource leadSource,

        @Size(max = 200, message = "leadSourceNote must be at most 200 characters")
        String leadSourceNote,

        @Size(max = 1000, message = "notes must be at most 1000 characters")
        String notes,

        @Size(max = 15, message = "buyerGstin must be at most 15 characters")
        String buyerGstin,

        @Pattern(regexp = "(?i)(FLAT|PERCENT)?", message = "discountType must be FLAT or PERCENT")
        String discountType,

        @DecimalMin(value = "0.00", message = "discountValue must not be negative")
        @Digits(integer = 10, fraction = 2, message = "discountValue must be a DECIMAL(12,2) value")
        BigDecimal discountValue
) {
}
