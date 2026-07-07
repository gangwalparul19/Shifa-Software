package com.shifa.oms.courier;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Pure, render-agnostic model of a courier shipping label (Req 12.3). Mirrors the
 * internal-label content/renderer split used by the label module: content is
 * assembled here and turned into PDF bytes by {@link ShippingLabelRenderer}, so
 * the AWB/COD rules can be reasoned about without producing bytes.
 *
 * <p>The COD amount is present <em>if and only if</em> the order is COD or
 * Partially_Paid (Req 12.3), matching the internal-label rule.
 *
 * @param orderCode      the order's code
 * @param awb            the assigned AWB, encoded in the label barcode (Req 12.3)
 * @param courierName    the courier company name
 * @param trackingUrl    the customer tracking link, or {@code null}
 * @param customerName   the recipient name
 * @param customerMobile the recipient mobile
 * @param addressLine    the shipping address line
 * @param city           the shipping city
 * @param state          the shipping state
 * @param postalCode     the shipping postal code
 * @param lineItems      the ordered line items (name + quantity)
 * @param codApplicable  whether a COD amount applies (COD / Partially_Paid)
 * @param codAmount      the COD amount when applicable, else {@code null}
 */
public record ShippingLabelContent(
        String orderCode,
        String awb,
        String courierName,
        String trackingUrl,
        String customerName,
        String customerMobile,
        String addressLine,
        String city,
        String state,
        String postalCode,
        List<LabelLineItem> lineItems,
        boolean codApplicable,
        BigDecimal codAmount) {

    public ShippingLabelContent {
        Objects.requireNonNull(orderCode, "orderCode");
        Objects.requireNonNull(awb, "awb");
        lineItems = List.copyOf(Objects.requireNonNull(lineItems, "lineItems"));
    }

    /** A single ordered product on the label: its name and quantity. */
    public record LabelLineItem(String productName, int quantity) {
        public LabelLineItem {
            Objects.requireNonNull(productName, "productName");
        }
    }

    /** The full one-line shipping address assembled for rendering. */
    public String fullAddress() {
        return String.join(", ", addressLine, city, state, postalCode);
    }
}
