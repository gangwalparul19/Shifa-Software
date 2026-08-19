package com.shifa.oms.invoice;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Pure, render-agnostic model of a single per-order invoice. This is the
 * invoice's <em>content</em> — the fields to be laid out — deliberately
 * separated from PDF/byte production ({@link InvoicePdfRenderer}) so
 * completeness can be unit-tested without parsing any bytes, mirroring the label
 * module's {@link com.shifa.oms.label.InternalLabelContent} pattern.
 *
 * <p>It carries the invoice identity (invoice number = order code, invoice date),
 * the bill-to customer block, order meta (status / payment status / source), the
 * priced line items, and the totals block. The {@link #codApplicable()} flag is
 * {@code true} exactly when the order is COD or Partially_Paid; in that case
 * {@link #amountDueOnDelivery()} is the amount the customer must pay on delivery.
 *
 * <p>Monetary fields are raw {@link BigDecimal} (scale 2); currency symbol and
 * grouping are applied by the renderer so this model stays presentation-neutral
 * and easy to assert on.
 *
 * @param invoiceNumber      the invoice number (equals the order code)
 * @param invoiceDate        the pre-formatted invoice date (order createdAt), never {@code null}
 * @param customerName       the bill-to customer name
 * @param customerMobile     the bill-to customer 10-digit mobile
 * @param addressLine        the shipping address line
 * @param city               the shipping city
 * @param state              the shipping state
 * @param postalCode         the shipping postal code
 * @param orderStatus        the human-readable order status
 * @param paymentStatus      the human-readable payment status
 * @param orderSource        the human-readable order source
 * @param lineItems          the priced line items (position, name, qty, rate, amount)
 * @param subtotal           the gross order subtotal (Σ line amounts, before discount)
 * @param discountAmount     the coupon discount applied (0 when none), Phase D
 * @param couponCode         the applied coupon code, or {@code null} when none, Phase D
 * @param netTotal           the net payable after discount (equals Total_Amount)
 * @param amountReceived     the amount already received at entry
 * @param balanceDue         the remaining balance (Net_Total − Amount_Received)
 * @param codAmount          the COD amount recorded on the order
 * @param codApplicable      whether COD applies (COD / Partially_Paid)
 * @param amountDueOnDelivery the amount to collect on delivery when COD applies, else {@code null}
 * @param gst                the GST tax-invoice block, or {@code null} for a plain invoice
 */
public record InvoiceContent(
        String invoiceNumber,
        String invoiceDate,
        String customerName,
        String customerMobile,
        String addressLine,
        String city,
        String state,
        String postalCode,
        String orderStatus,
        String paymentStatus,
        String orderSource,
        List<InvoiceLineItem> lineItems,
        BigDecimal subtotal,
        BigDecimal discountAmount,
        String couponCode,
        BigDecimal netTotal,
        BigDecimal amountReceived,
        BigDecimal balanceDue,
        BigDecimal codAmount,
        boolean codApplicable,
        BigDecimal amountDueOnDelivery,
        InvoiceGstDetails gst,
        String invoiceTerms,
        BankDetails bankDetails) {

    public InvoiceContent {
        Objects.requireNonNull(invoiceNumber, "invoiceNumber");
        Objects.requireNonNull(invoiceDate, "invoiceDate");
        lineItems = List.copyOf(Objects.requireNonNull(lineItems, "lineItems"));
    }

    /** Whether multi-line terms &amp; conditions should be rendered. */
    public boolean hasTerms() {
        return invoiceTerms != null && !invoiceTerms.isBlank();
    }

    /** Whether a bank-details block should be rendered. */
    public boolean hasBankDetails() {
        return bankDetails != null && bankDetails.hasAny();
    }

    /**
     * The bank-details block rendered on the invoice when configured (Wave 3,
     * Feature 1). All fields optional; the block is shown when any is present.
     *
     * @param bankName      the bank name
     * @param accountName   the account holder name
     * @param accountNumber the account number
     * @param ifsc          the IFSC code
     * @param branch        the branch
     */
    public record BankDetails(
            String bankName,
            String accountName,
            String accountNumber,
            String ifsc,
            String branch) {

        /** Whether at least one bank field is present (non-blank). */
        public boolean hasAny() {
            return notBlank(bankName) || notBlank(accountName) || notBlank(accountNumber)
                    || notBlank(ifsc) || notBlank(branch);
        }

        private static boolean notBlank(String value) {
            return value != null && !value.isBlank();
        }
    }

    /** Whether this invoice should render as a GST "TAX INVOICE". */
    public boolean isTaxInvoice() {
        return gst != null;
    }

    /** Whether a coupon discount was applied and should be shown (discount &gt; 0). */
    public boolean hasDiscount() {
        return discountAmount != null && discountAmount.signum() > 0;
    }

    /**
     * A single priced line on the invoice.
     *
     * @param position   the 1-based row number as shown in the "#" column
     * @param productName the product name (snapshot from the order)
     * @param quantity   the ordered quantity
     * @param rate       the applied unit rate
     * @param amount     the line total (rate × quantity)
     * @param hsnCode    the product HSN code for GST invoices, or {@code null}/blank when unknown
     */
    public record InvoiceLineItem(
            int position,
            String productName,
            int quantity,
            BigDecimal rate,
            BigDecimal amount,
            String hsnCode,
            BigDecimal discount,
            BigDecimal gstRatePercent) {

        public InvoiceLineItem {
            Objects.requireNonNull(productName, "productName");
            Objects.requireNonNull(rate, "rate");
            Objects.requireNonNull(amount, "amount");
            discount = discount == null ? BigDecimal.ZERO.setScale(2) : discount;
        }

        /** Backward-compatible factory without per-line discount/GST rate (plain invoice). */
        public InvoiceLineItem(int position, String productName, int quantity,
                               BigDecimal rate, BigDecimal amount, String hsnCode) {
            this(position, productName, quantity, rate, amount, hsnCode, BigDecimal.ZERO.setScale(2), null);
        }
    }

    /** The full one-line shipping address assembled for the Bill-To block. */
    public String fullAddress() {
        return String.join(", ", addressLine, city, state, postalCode);
    }
}
