package com.shifa.oms.label;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Pure, render-agnostic model of a single internal company label (Req 10.1,
 * 10.2). This is the label's <em>content</em> — the fields to be rendered —
 * deliberately separated from PDF/byte production ({@link LabelPdfRenderer}) so
 * completeness can be property-tested without generating any bytes
 * (design "PDF / label / barcode generation"; Property 18).
 *
 * <p>The label carries TWO scannable Code128 barcodes (label redesign feature):
 * <ol>
 *   <li>the <strong>courier barcode</strong> — the courier partner's display
 *       name printed above a barcode of the allotted AWB, so the courier team
 *       scans the parcel straight into their own system at pickup. Absent
 *       ({@link #courierName()}/{@link #courierBarcodeValue()} both
 *       {@code null}) until a courier + AWB have been allotted (e.g. an
 *       in-house order, or a QuikShipX order awaiting allotment) — the courier
 *       section is then omitted from the rendered label;</li>
 *   <li>the <strong>order barcode</strong> — always present, encoding our own
 *       {@link #orderCode()} so the godown team can scan a returned parcel
 *       (RTO) straight back to the order in our system regardless of whether a
 *       courier/AWB was ever allotted.</li>
 * </ol>
 *
 * <p>It also carries the customer details and the ordered line items. The COD
 * amount is present <em>if and only if</em> the order is COD or Partially_Paid
 * (Req 10.2): for a Fully_Paid order {@link #codApplicable()} is {@code false}
 * and {@link #codAmount()} is {@code null}.
 *
 * @param orderCode           the human/scannable order identifier (never {@code null})
 * @param courierName         the courier partner's display name, or {@code null} when no courier/AWB is allotted yet
 * @param courierBarcodeValue the AWB encoded by the courier Code128 barcode, or {@code null} when absent
 * @param customerName        the customer's name
 * @param customerMobile      the customer's 10-digit mobile number
 * @param addressLine         the shipping address line
 * @param city                the shipping city
 * @param state               the shipping state
 * @param postalCode          the shipping postal code
 * @param lineItems           the ordered line items (name + quantity), never {@code null}
 * @param codApplicable       whether a COD amount applies (COD / Partially_Paid)
 * @param codAmount           the COD amount when applicable, else {@code null} (Req 10.2)
 */
public record InternalLabelContent(
        String orderCode,
        String courierName,
        String courierBarcodeValue,
        String customerName,
        String customerMobile,
        String addressLine,
        String city,
        String state,
        String postalCode,
        List<LabelLineItem> lineItems,
        boolean codApplicable,
        BigDecimal codAmount,
        String orderedOn,
        BigDecimal totalAmount,
        String paymentLabel,
        String sellerName,
        String pickupReturnAddress) {

    public InternalLabelContent {
        Objects.requireNonNull(orderCode, "orderCode");
        lineItems = List.copyOf(Objects.requireNonNull(lineItems, "lineItems"));
    }

    /** Whether a courier + AWB have been allotted, so the courier barcode section should render. */
    public boolean hasCourierBarcode() {
        return courierBarcodeValue != null && !courierBarcodeValue.isBlank();
    }

    /** A compact one-line summary of the ordered items, e.g. "Ashwagandha x 2, Triphala x 1". */
    public String itemSummary() {
        return lineItems.stream()
                .map(li -> li.productName() + " x " + li.quantity())
                .collect(Collectors.joining(", "));
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
