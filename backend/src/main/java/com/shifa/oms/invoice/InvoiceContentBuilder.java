package com.shifa.oms.invoice;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.settings.AppSettings;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Pure content builder that assembles an {@link InvoiceContent} model from a
 * persisted {@link OrderEntity}, with no PDF/byte production. Keeping content
 * assembly separate from rendering ({@link InvoicePdfRenderer}) lets the invoice
 * content be unit-tested directly against the model, mirroring the label
 * module's {@link com.shifa.oms.label.LabelContentBuilder} pattern.
 *
 * <p>The invoice number is the order code; the invoice date is the order's
 * {@code createdAt}. The COD block is populated exactly when the order's payment
 * status is {@code COD} or {@code Partially_Paid} (in which case
 * {@link InvoiceContent#amountDueOnDelivery()} equals the order's COD amount);
 * for a {@code Fully_Paid} order the COD block is omitted.
 *
 * <p><strong>GST.</strong> {@link #build(OrderEntity)} produces the plain
 * (pre-GST) invoice (no tax block). {@link #build(OrderEntity, AppSettings, Map)}
 * additionally computes the GST tax block via the pure {@link GstCalculator}
 * when {@link AppSettings#isGstEnabled()}; intra-state vs inter-state is decided
 * by comparing the order's shipping state to the seller's configured state
 * (case-insensitive). Per-line HSN codes are looked up from the supplied
 * product-id → HSN map (blank when unknown).
 *
 * <p>This class is stateless and has no Spring or persistence dependencies.
 */
public class InvoiceContentBuilder {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.ENGLISH);

    private final GstCalculator gstCalculator = new GstCalculator();

    /**
     * Builds the plain invoice content model (no GST tax block) for a single
     * order.
     *
     * @param order the source order aggregate (never {@code null})
     * @return the assembled, render-agnostic invoice content
     */
    public InvoiceContent build(OrderEntity order) {
        Objects.requireNonNull(order, "order");
        return assemble(order, order.getOrderCode(), Map.of(), null, null, null, false);
    }

    /**
     * Builds the invoice content model, adding a GST tax block when GST is
     * enabled in {@code settings}. When GST is disabled (or {@code settings} is
     * {@code null}) this is equivalent to {@link #build(OrderEntity)}.
     *
     * @param order            the source order aggregate (never {@code null})
     * @param settings         the company + GST settings (may be {@code null})
     * @param hsnByProductId   per-product HSN codes keyed by product id (may be empty)
     * @return the assembled, render-agnostic invoice content
     */
    public InvoiceContent build(OrderEntity order, AppSettings settings,
                                Map<Long, String> hsnByProductId) {
        return build(order, settings, hsnByProductId, Map.of());
    }

    /**
     * Builds the invoice content model, adding a GST tax block when GST is
     * enabled, and using each line product's own GST rate when supplied in
     * {@code gstRateByProductId} (falling back to the settings-level default rate
     * when absent). This is backward compatible: an empty map yields the previous
     * settings-default behaviour.
     *
     * @param order              the source order aggregate (never {@code null})
     * @param settings           the company + GST settings (may be {@code null})
     * @param hsnByProductId     per-product HSN codes keyed by product id (may be empty)
     * @param gstRateByProductId per-product GST rate percent keyed by product id (may be empty)
     * @return the assembled, render-agnostic invoice content
     */
    public InvoiceContent build(OrderEntity order, AppSettings settings,
                                Map<Long, String> hsnByProductId,
                                Map<Long, BigDecimal> gstRateByProductId) {
        return build(order, settings, order != null ? order.getOrderCode() : null,
                hsnByProductId, gstRateByProductId);
    }

    /**
     * Primary build used by {@link InvoiceService}: adds the GST tax block when
     * enabled, threads the allocated {@code invoiceNumber} (Wave 3, Feature 1),
     * and renders the settings-level terms &amp; conditions and bank details on
     * both plain and tax invoices. Per-line HSN + GST rate prefer the order line's
     * own snapshot (Wave 3, Feature 2), falling back to the supplied maps (current
     * product values) for legacy lines with no snapshot.
     *
     * @param order              the source order aggregate (never {@code null})
     * @param settings           the company + GST settings (may be {@code null})
     * @param invoiceNumber      the allocated invoice number (falls back to the order code)
     * @param hsnByProductId     per-product HSN codes keyed by product id (may be empty)
     * @param gstRateByProductId per-product GST rate percent keyed by product id (may be empty)
     * @return the assembled, render-agnostic invoice content
     */
    public InvoiceContent build(OrderEntity order, AppSettings settings, String invoiceNumber,
                                Map<Long, String> hsnByProductId,
                                Map<Long, BigDecimal> gstRateByProductId) {
        Objects.requireNonNull(order, "order");
        InvoiceGstDetails gst = (settings != null && settings.isGstEnabled())
                ? buildGst(order, settings, gstRateByProductId)
                : null;
        // HSN codes are only surfaced on GST tax invoices; the plain invoice never shows them.
        Map<Long, String> hsn = (gst != null && hsnByProductId != null) ? hsnByProductId : Map.of();
        String number = (invoiceNumber != null && !invoiceNumber.isBlank())
                ? invoiceNumber : order.getOrderCode();
        String terms = settings != null ? settings.getInvoiceTerms() : null;
        InvoiceContent.BankDetails bank = bankDetailsOf(settings);
        // On a GST tax invoice HSN can come from the line snapshot too, even when
        // the caller passed no product map.
        boolean taxInvoice = gst != null;
        return assemble(order, number, hsn, gst, terms, bank, taxInvoice);
    }

    private InvoiceContent assemble(OrderEntity order, String invoiceNumber,
                                    Map<Long, String> hsnByProductId, InvoiceGstDetails gst,
                                    String invoiceTerms, InvoiceContent.BankDetails bankDetails,
                                    boolean taxInvoice) {
        List<OrderLineItem> orderLines = order.getLineItems();
        List<BigDecimal> shares = discountShares(order);
        List<InvoiceContent.InvoiceLineItem> items = new ArrayList<>();
        for (int i = 0; i < orderLines.size(); i++) {
            OrderLineItem line = orderLines.get(i);
            // Prefer the per-line HSN snapshot (Feature 2); fall back to the current
            // product map for legacy lines. HSN is only surfaced on tax invoices.
            String hsn = null;
            if (taxInvoice) {
                hsn = line.getHsnCode();
                if ((hsn == null || hsn.isBlank()) && line.getProductId() != null) {
                    hsn = hsnByProductId.get(line.getProductId());
                }
            }
            items.add(new InvoiceContent.InvoiceLineItem(
                    i + 1,
                    line.getProductName(),
                    line.getQuantity(),
                    line.getRate(),
                    line.getLineTotal(),
                    hsn,
                    shares.get(i),
                    line.getGstRate()));
        }

        boolean codApplicable = isCodApplicable(order.getPaymentStatus());
        BigDecimal cod = order.getCodAmount() != null ? order.getCodAmount() : BigDecimal.ZERO;

        // Net payable is stored on the order's Total_Amount; the gross subtotal is
        // the sum of line amounts (== net when no coupon was applied). The discount
        // row is only shown when discount_amount > 0 (Phase D).
        BigDecimal netTotal = order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO;
        BigDecimal discount = order.getDiscountAmount() != null ? order.getDiscountAmount() : BigDecimal.ZERO;
        BigDecimal gross = grossSubtotal(items, netTotal, discount);

        return new InvoiceContent(
                invoiceNumber,
                formatDate(order.getCreatedAt()),
                order.getCustomerName(),
                order.getCustomerMobile(),
                order.getAddressLine(),
                order.getCity(),
                order.getState(),
                order.getPostalCode(),
                prettify(order.getOrderStatus() != null ? order.getOrderStatus().name() : null),
                prettify(order.getPaymentStatus() != null ? order.getPaymentStatus().name() : null),
                prettifySource(order.getSource()),
                items,
                gross,
                discount,
                order.getCouponCode(),
                netTotal,
                order.getAmountReceived(),
                order.getRemainingAmount(),
                cod,
                codApplicable,
                codApplicable ? cod : null,
                gst,
                invoiceTerms,
                bankDetails);
    }

    /** Builds the bank-details block from settings, or {@code null} when none configured. */
    private InvoiceContent.BankDetails bankDetailsOf(AppSettings settings) {
        if (settings == null) {
            return null;
        }
        InvoiceContent.BankDetails bank = new InvoiceContent.BankDetails(
                settings.getBankName(),
                settings.getBankAccountName(),
                settings.getBankAccountNumber(),
                settings.getBankIfsc(),
                settings.getBankBranch());
        return bank.hasAny() ? bank : null;
    }

    /**
     * The gross subtotal (before discount): the sum of line amounts. Falls back
     * to {@code net + discount} when there are no line amounts, so the printed
     * subtotal is always consistent with the discount + net rows.
     */
    private BigDecimal grossSubtotal(List<InvoiceContent.InvoiceLineItem> items,
                                     BigDecimal netTotal, BigDecimal discount) {
        BigDecimal sum = BigDecimal.ZERO;
        for (InvoiceContent.InvoiceLineItem item : items) {
            if (item.amount() != null) {
                sum = sum.add(item.amount());
            }
        }
        if (sum.signum() == 0 && items.isEmpty()) {
            return netTotal.add(discount);
        }
        return sum;
    }

    /**
     * Computes the GST tax block: the order-level discount is apportioned across
     * lines FIRST, lines are grouped by their GST rate, and each rate group's
     * discounted net is passed to {@link GstCalculator} (inclusive → extract,
     * exclusive → add on top). The per-rate rows are the CA's breakup; the
     * aggregate {@link GstComputation} sums them (single-rate baskets keep the
     * exact previous values). Intra-state → CGST+SGST, inter-state → IGST.
     */
    private InvoiceGstDetails buildGst(OrderEntity order, AppSettings settings,
                                       Map<Long, BigDecimal> gstRateByProductId) {
        boolean intraState = isIntraState(order.getState(), settings.getState());
        boolean inclusive = settings.isPricesIncludeGst();
        BigDecimal defaultRate = settings.getGstRatePercent();

        List<OrderLineItem> lines = order.getLineItems();
        List<BigDecimal> shares = discountShares(order);

        // Group the discounted net by GST rate, preserving first-seen order.
        java.util.LinkedHashMap<BigDecimal, BigDecimal> netByRate = new java.util.LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            BigDecimal rate = resolveLineRate(lines.get(i), gstRateByProductId, defaultRate);
            BigDecimal base = lines.get(i).getLineTotal() != null
                    ? lines.get(i).getLineTotal() : BigDecimal.ZERO;
            BigDecimal net = base.subtract(shares.get(i));
            netByRate.merge(rate, net, BigDecimal::add);
        }

        List<GstRateLine> breakup = new ArrayList<>();
        BigDecimal taxable = BigDecimal.ZERO.setScale(2);
        BigDecimal cgst = BigDecimal.ZERO.setScale(2);
        BigDecimal sgst = BigDecimal.ZERO.setScale(2);
        BigDecimal igst = BigDecimal.ZERO.setScale(2);
        BigDecimal tax = BigDecimal.ZERO.setScale(2);
        BigDecimal grand = BigDecimal.ZERO.setScale(2);
        for (Map.Entry<BigDecimal, BigDecimal> e : netByRate.entrySet()) {
            GstComputation g = gstCalculator.calculate(e.getValue(), e.getKey(), inclusive, intraState);
            breakup.add(new GstRateLine(e.getKey().setScale(2), g.taxableValue(),
                    g.cgstAmount(), g.sgstAmount(), g.igstAmount(), g.totalTax()));
            taxable = taxable.add(g.taxableValue());
            cgst = cgst.add(g.cgstAmount());
            sgst = sgst.add(g.sgstAmount());
            igst = igst.add(g.igstAmount());
            tax = tax.add(g.totalTax());
            grand = grand.add(g.grandTotal());
        }

        // Aggregate: a single rate keeps its exact rates; a mixed basket reports 0
        // at the aggregate level (the per-rate breakup carries the detail).
        BigDecimal aggRate = netByRate.size() == 1
                ? netByRate.keySet().iterator().next().setScale(2) : BigDecimal.ZERO.setScale(2);
        BigDecimal half = aggRate.divide(new BigDecimal("2"), 2, java.math.RoundingMode.HALF_UP);
        GstComputation aggregate = intraState
                ? new GstComputation(true, aggRate, taxable, half, cgst, half, sgst,
                        BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), tax, grand)
                : new GstComputation(false, aggRate, taxable,
                        BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                        BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                        aggRate, igst, tax, grand);

        return new InvoiceGstDetails(
                settings.getLegalName(),
                settings.getGstin(),
                settings.getAddressLine(),
                settings.getCity(),
                settings.getState(),
                settings.getStateCode(),
                settings.getContactPhone(),
                settings.getContactEmail(),
                settings.getInvoiceFooterNote(),
                aggregate,
                breakup,
                inclusive);
    }

    /**
     * Apportions the order-level discount across lines proportionally to each
     * line total, using largest-remainder so the shares sum EXACTLY to the
     * discount (no rupee created/lost). Zero discount / zero subtotal → all zero.
     */
    private List<BigDecimal> discountShares(OrderEntity order) {
        List<OrderLineItem> lines = order.getLineItems();
        int n = lines.size();
        BigDecimal zero = BigDecimal.ZERO.setScale(2);
        List<BigDecimal> shares = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            shares.add(zero);
        }
        BigDecimal discount = order.getDiscountAmount() != null ? order.getDiscountAmount() : zero;
        BigDecimal subtotal = BigDecimal.ZERO;
        for (OrderLineItem line : lines) {
            subtotal = subtotal.add(line.getLineTotal() != null ? line.getLineTotal() : BigDecimal.ZERO);
        }
        if (discount.signum() <= 0 || subtotal.signum() <= 0 || n == 0) {
            return shares;
        }
        BigDecimal allocated = BigDecimal.ZERO;
        List<BigDecimal> remainders = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            BigDecimal base = lines.get(i).getLineTotal() != null ? lines.get(i).getLineTotal() : BigDecimal.ZERO;
            BigDecimal exact = discount.multiply(base).divide(subtotal, 6, java.math.RoundingMode.HALF_UP);
            BigDecimal floor = exact.setScale(2, java.math.RoundingMode.DOWN);
            shares.set(i, floor);
            remainders.add(exact.subtract(floor));
            allocated = allocated.add(floor);
        }
        int leftoverPaise = discount.subtract(allocated).movePointRight(2)
                .setScale(0, java.math.RoundingMode.HALF_UP).intValue();
        List<Integer> order2 = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            order2.add(i);
        }
        order2.sort((a, b) -> remainders.get(b).compareTo(remainders.get(a)));
        BigDecimal onePaise = new BigDecimal("0.01");
        for (int k = 0; k < leftoverPaise && k < n * 1000; k++) {
            int idx = order2.get(k % n);
            shares.set(idx, shares.get(idx).add(onePaise));
        }
        return shares;
    }

    /**
     * Resolves a single line's GST rate: the line's own snapshot, else the
     * current product map, else the settings-level default.
     */
    private BigDecimal resolveLineRate(OrderLineItem line, Map<Long, BigDecimal> gstRateByProductId,
                                       BigDecimal defaultRate) {
        BigDecimal fallback = defaultRate != null ? defaultRate : BigDecimal.ZERO;
        if (line.getGstRate() != null) {
            return line.getGstRate();
        }
        Map<Long, BigDecimal> rateMap = gstRateByProductId != null ? gstRateByProductId : Map.of();
        if (line.getProductId() != null) {
            BigDecimal productRate = rateMap.get(line.getProductId());
            if (productRate != null) {
                return productRate;
            }
        }
        return fallback;
    }

    /**
     * Intra-state when the order's shipping state matches the seller's configured
     * state (case-insensitive, trimmed). A blank seller state is treated as
     * inter-state so IGST is used rather than an incorrect CGST/SGST split.
     */
    private boolean isIntraState(String orderState, String sellerState) {
        if (orderState == null || sellerState == null) {
            return false;
        }
        return orderState.trim().equalsIgnoreCase(sellerState.trim())
                && !sellerState.trim().isEmpty();
    }

    /** COD is shown on the invoice for COD and Partially_Paid orders only. */
    private boolean isCodApplicable(PaymentStatus status) {
        return status == PaymentStatus.COD || status == PaymentStatus.PARTIALLY_PAID;
    }

    private String formatDate(LocalDateTime createdAt) {
        return createdAt != null ? DATE_FORMAT.format(createdAt) : "-";
    }

    private String prettifySource(OrderSource source) {
        return source != null ? prettify(source.name()) : "-";
    }

    /** Turns an enum constant name such as {@code PENDING_ADMIN_APPROVAL} into {@code "Pending Admin Approval"}. */
    private String prettify(String enumName) {
        if (enumName == null || enumName.isBlank()) {
            return "-";
        }
        String[] parts = enumName.toLowerCase(Locale.ENGLISH).split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}
