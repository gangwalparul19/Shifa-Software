package com.shifa.oms.order.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * A single line in an in-shop (POS / counter) store order
 * ({@code POST /api/orders/store}, store-order feature).
 *
 * <p>Unlike {@link LineItemRequest} (salesperson entry), {@code productId} is
 * <strong>optional</strong>: a store line may be a catalogue product OR an
 * ad-hoc item that has no catalogue entry (e.g. a "Consultation fee" or a
 * one-off charge). For a catalogue line, supply {@code productId}; for an ad-hoc
 * line, leave {@code productId} null and supply {@code name} (and optionally a
 * GST rate / HSN).
 *
 * <p>{@code rate} is required for an ad-hoc line (there is no product to default
 * from); for a catalogue line it may be omitted to use the product's sale price
 * or supplied to override it at the counter (the admin can discount/negotiate —
 * the salesperson price-band is <em>not</em> enforced for store sales).
 *
 * @param productId catalogue product id, or {@code null} for an ad-hoc item
 * @param name      display name — required for an ad-hoc line; ignored for a
 *                  catalogue line (the product name is snapshotted instead)
 * @param quantity  1..999
 * @param rate      unit price; required for ad-hoc, optional override for catalogue
 * @param gstRate   GST percent for an ad-hoc line (e.g. 0 for an exempt
 *                  consultation fee); ignored for a catalogue line (snapshotted
 *                  from the product). Null defaults to 0 for an ad-hoc line.
 * @param hsnCode   optional HSN/SAC code for an ad-hoc line
 */
public record StoreLineItemRequest(
        Long productId,

        @Size(max = 200, message = "name must be at most 200 characters")
        String name,

        @Min(value = 1, message = "quantity must be at least 1")
        @Max(value = 999, message = "quantity must be at most 999")
        int quantity,

        @DecimalMin(value = "0.00", message = "rate must not be negative")
        @Digits(integer = 10, fraction = 2, message = "rate must be a DECIMAL(12,2) value")
        BigDecimal rate,

        @DecimalMin(value = "0.00", message = "gstRate must not be negative")
        @Digits(integer = 3, fraction = 2, message = "gstRate must be a percentage")
        BigDecimal gstRate,

        @Size(max = 20, message = "hsnCode must be at most 20 characters")
        String hsnCode
) {

    /** True when this line is an ad-hoc (non-catalogue) item. */
    public boolean isAdHoc() {
        return productId == null;
    }
}
