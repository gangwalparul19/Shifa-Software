package com.shifa.oms.label;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Pure, render-agnostic model of a single internal company label (Req 10.1,
 * 10.2). This is the label's <em>content</em> — the fields to be rendered —
 * deliberately separated from PDF/byte production ({@link LabelPdfRenderer}) so
 * completeness can be property-tested without generating any bytes
 * (design "PDF / label / barcode generation"; Property 18).
 *
 * <p>It carries the order identifier, the value to encode in the scannable
 * Code128 barcode (always equal to the order code), the customer details, and
 * the ordered line items. The COD amount is present <em>if and only if</em> the
 * order is COD or Partially_Paid (Req 10.2): for a Fully_Paid order
 * {@link #codApplicable()} is {@code false} and {@link #codAmount()} is
 * {@code null}.
 *
 * @param orderCode      the human/scannable order identifier (never {@code null})
 * @param barcodeValue   the value encoded by the Code128 barcode; equals {@code orderCode}
 * @param customerName   the customer's name
 * @param customerMobile the customer's 10-digit mobile number
 * @param addressLine    the shipping address line
 * @param city           the shipping city
 * @param state          the shipping state
 * @param postalCode     the shipping postal code
 * @param lineItems      the ordered line items (name + quantity), never {@code null}
 * @param codApplicable  whether a COD amount applies (COD / Partially_Paid)
 * @param codAmount      the COD amount when applicable, else {@code null} (Req 10.2)
 */
public record InternalLabelContent(
        String orderCode,
        String barcodeValue,
        String customerName,
        String customerMobile,
        String addressLine,
        String city,
        String state,
        String postalCode,
        List<LabelLineItem> lineItems,
        boolean codApplicable,
        BigDecimal codAmount) {

    public InternalLabelContent {
        Objects.requireNonNull(orderCode, "orderCode");
        Objects.requireNonNull(barcodeValue, "barcodeValue");
        lineItems = List.copyOf(Objects.requireNonNull(lineItems, "lineItems"));
    }

    /** A single ordered product on the label: its name and quantity (Req 10.1). */
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
