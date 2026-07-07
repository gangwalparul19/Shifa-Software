package com.shifa.oms.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Public storefront checkout payload for {@code POST /api/checkout}
 * (Requirement 3.1, 3.3-3.6).
 *
 * <p>Customers supply only product ids and quantities; the server prices each
 * line from the product's current sale price (customers cannot set prices). The
 * mobile must be exactly 10 digits (Req 3.4) and the postal code exactly 6
 * digits (Req 3.5); all address fields are required (Req 3.3).
 */
public record CheckoutRequest(
        @NotBlank(message = "customerName is required")
        @Size(max = 100, message = "customerName must be at most 100 characters")
        String customerName,

        @NotBlank(message = "customerMobile is required")
        @Pattern(regexp = "\\d{10}", message = "customerMobile must be exactly 10 digits")
        String customerMobile,

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

        @NotEmpty(message = "cart must contain at least one item")
        @Valid
        List<CheckoutItemRequest> items,

        /**
         * Optional coupon code to apply to this order (Phase D). When present it
         * is re-validated server-side (authoritative) and, if applicable, reduces
         * the order total by the computed discount. Null/blank means no coupon.
         */
        @Size(max = 40, message = "couponCode must be at most 40 characters")
        String couponCode
) {

    /**
     * Backward-compatible constructor for callers that do not supply a coupon
     * code (guest/existing flows); delegates with a {@code null} coupon.
     */
    public CheckoutRequest(String customerName, String customerMobile, String addressLine,
                           String city, String state, String postalCode,
                           List<CheckoutItemRequest> items) {
        this(customerName, customerMobile, addressLine, city, state, postalCode, items, null);
    }

    /** A single cart line: a product and a quantity (1..999). */
    public record CheckoutItemRequest(
            @NotNull(message = "productId is required")
            Long productId,

            @Min(value = 1, message = "quantity must be at least 1")
            @Max(value = 999, message = "quantity must be at most 999")
            int quantity
    ) {
    }
}
