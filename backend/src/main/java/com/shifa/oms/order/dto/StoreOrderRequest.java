package com.shifa.oms.order.dto;

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
 * In-shop (POS / counter) order payload for {@code POST /api/orders/store}
 * (store-order feature). Usable by ADMIN, SALESPERSON and TEAM_LEAD.
 *
 * <p>A walk-in customer pays at the counter and leaves with the goods, so a
 * store order deliberately differs from a salesperson order
 * ({@link CreateOrderRequest}):
 * <ul>
 *   <li>a payment screenshot IS required whenever any amount is received (proof
 *       of the counter payment), mirroring the salesperson order;</li>
 *   <li>NO delivery partner — it is always a counter sale (in-house), no courier;</li>
 *   <li>the address is OPTIONAL (a walk-in may give only a name + phone);</li>
 *   <li>line items may be ad-hoc (a consultation fee, a one-off charge) as well
 *       as catalogue products ({@link StoreLineItemRequest});</li>
 *   <li>the salesperson min-upfront, same-day-duplicate and price-band guards do
 *       NOT apply — the counter price is set in-shop and any part payment is allowed.</li>
 * </ul>
 *
 * <p>A fully-paid store order is auto-approved and closed immediately; a partial
 * payment leaves it open with the balance tracked as outstanding.
 */
public record StoreOrderRequest(
        @NotBlank(message = "customerName is required")
        @Size(max = 100, message = "customerName must be at most 100 characters")
        String customerName,

        @NotBlank(message = "customerMobile is required")
        @Pattern(regexp = "\\d{10}", message = "customerMobile must be exactly 10 digits")
        String customerMobile,

        // Address is OPTIONAL for a counter sale (no delivery). Bounded when supplied.
        @Size(max = 250, message = "addressLine must be at most 250 characters")
        String addressLine,

        @Size(max = 100, message = "city must be at most 100 characters")
        String city,

        @Size(max = 100, message = "state must be at most 100 characters")
        String state,

        @Pattern(regexp = "(\\d{6})?", message = "postalCode must be exactly 6 digits")
        String postalCode,

        @NotEmpty(message = "at least one line item is required")
        @Valid
        List<StoreLineItemRequest> items,

        @NotNull(message = "amountReceived is required")
        @DecimalMin(value = "0.00", message = "amountReceived must not be negative")
        @Digits(integer = 10, fraction = 2, message = "amountReceived must be a DECIMAL(12,2) value")
        BigDecimal amountReceived,

        @Email(message = "customerEmail must be a valid email address")
        @Size(max = 150, message = "customerEmail must be at most 150 characters")
        String customerEmail,

        @Size(max = 1000, message = "notes must be at most 1000 characters")
        String notes,

        @Pattern(regexp = "(\\d{10})?", message = "alternateMobile must be exactly 10 digits")
        String alternateMobile,

        // Optional order-level discount (same semantics as a salesperson order).
        @Pattern(regexp = "(?i)(FLAT|PERCENT)?", message = "discountType must be FLAT or PERCENT")
        String discountType,

        @DecimalMin(value = "0.00", message = "discountValue must not be negative")
        @Digits(integer = 10, fraction = 2, message = "discountValue must be a DECIMAL(12,2) value")
        BigDecimal discountValue,

        @Size(max = 15, message = "buyerGstin must be at most 15 characters")
        String buyerGstin,

        // --- Payment proof (store-order screenshot mandate) ---------------------
        // The primary payment screenshot storage key (uploaded via
        // POST /api/orders/payment-screenshots). Required when amountReceived > 0.
        @Size(max = 512, message = "paymentScreenshotKey must be at most 512 characters")
        String paymentScreenshotKey,

        // Additional payment-proof keys (a counter payment may need several
        // screenshots); each attached alongside the primary. Appended last to keep
        // the record constructor backward-compatible.
        @Size(max = 10, message = "at most 10 payment screenshots are allowed")
        List<@Size(max = 512) String> paymentScreenshotKeys
) {
}
