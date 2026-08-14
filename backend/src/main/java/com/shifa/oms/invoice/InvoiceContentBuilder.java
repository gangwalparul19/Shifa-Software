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
        return assemble(order, order.getOrderCode(), Map.of(), null, null, null, false, Map.of(), null);
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
        BigDecimal defaultRate = settings != null ? settings.getGstRatePercent() : null;
        Map<Long, BigDecimal> rateMap = gstRateByProductId != null ? gstRateByProductId : Map.of();
        return assemble(order, number, hsn, gst, terms, bank, taxInvoice, rateMap, defaultRate);
    }

    private InvoiceContent assemble(OrderEntity order, String invoiceNumber,
                                    Map<Long, String> hsnByProductId, InvoiceGstDetails gst,
                                    String invoiceTerms, InvoiceContent.BankDetails bankDetails,
                                    boolean taxInvoice, Map<Long, BigDecimal> gstRateByProductId,
                                    BigDecimal defaultRate) {
        List<InvoiceContent.InvoiceLineItem> items = new ArrayList<>();
        int position = 1;
        for (OrderLineItem line : order.getLineItems()) {
            // Prefer the per-line HSN snapshot (Feature 2); fall back to the current
            // product map for legacy lines. HSN is only surfaced on tax invoices.
            String hsn = null;
            if (taxInvoice) {
                hsn = line.getHsnCode();
                if ((hsn == null || hsn.isBlank()) && line.getProductId() != null) {
                    hsn = hsnByProductId.get(line.getProductId());
                }
            }
            // Per-line GST rate (Feature: show GST% per product): prefer the line's
            // own snapshot, then the current product map, then the settings default.
            BigDecimal lineGstRate = null;
            if (taxInvoice) {
                lineGstRate = line.getGstRate();
                if (lineGstRate == null && line.getProductId() != null) {
                    lineGstRate = gstRateByProductId.get(line.getProductId());
                }
                if (lineGstRate == null) {
                    lineGstRate = defaultRate;
                }
            }
            items.add(new InvoiceContent.InvoiceLineItem(
                    position++,
                    line.getProductName(),
                    line.getQuantity(),
                    line.getRate(),
                    line.getLineTotal(),
                    hsn,
                    lineGstRate));
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

    /** Computes the GST tax block from the order total and the seller settings. */
    private InvoiceGstDetails buildGst(OrderEntity order, AppSettings settings,
                                       Map<Long, BigDecimal> gstRateByProductId) {
        boolean intraState = isIntraState(order.getState(), settings.getState());
        BigDecimal rate = resolveGstRate(order, gstRateByProductId, settings.getGstRatePercent());
        // GST is ADDED on top of the discounted base (discount-before-tax): the
        // taxable value is the line subtotal minus the order discount, and the tax
        // is the difference up to the stored grand total, so the invoice always
        // reconciles with the order's Total_Amount (which already includes the
        // added GST, rounded to the nearest rupee at order creation).
        BigDecimal gross = grossLineTotal(order);
        BigDecimal discount = order.getDiscountAmount() != null
                ? order.getDiscountAmount() : BigDecimal.ZERO;
        BigDecimal taxable = gross.subtract(discount);
        BigDecimal grand = order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO;
        GstComputation computation = gstCalculator.ofTaxableAndTotal(taxable, grand, rate, intraState);

        // Per-rate breakdown so a mixed-rate basket (e.g. some 5%, some 18%) shows
        // the tax split by rate. Each group's tax is its taxable × rate; the last
        // group absorbs pro-rata rounding so Σ group taxable == the total taxable.
        BigDecimal defaultRate = settings.getGstRatePercent();
        List<GstRateGroup> rateGroups = buildRateGroups(
                order, gstRateByProductId, defaultRate, gross, taxable, intraState);
        BigDecimal groupTaxSum = BigDecimal.ZERO;
        for (GstRateGroup g : rateGroups) {
            groupTaxSum = groupTaxSum.add(g.totalTax());
        }
        // Rounding adjustment so taxable + tax + roundOff == the stored grand total.
        BigDecimal roundOff = grand.subtract(taxable.add(groupTaxSum))
                .setScale(2, java.math.RoundingMode.HALF_UP);

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
                computation,
                rateGroups,
                roundOff);
    }

    /**
     * Groups the order's lines by GST rate and computes each group's taxable
     * (its pro-rata share of the post-discount taxable base) and tax
     * (taxable × rate). Groups are ordered by rate ascending; the last group
     * absorbs any pro-rata rounding so the group taxables sum exactly to
     * {@code totalTaxable}.
     */
    private List<GstRateGroup> buildRateGroups(OrderEntity order,
                                               Map<Long, BigDecimal> gstRateByProductId,
                                               BigDecimal defaultRate, BigDecimal gross,
                                               BigDecimal totalTaxable, boolean intraState) {
        BigDecimal hundred = new BigDecimal("100");
        BigDecimal two = new BigDecimal("2");
        Map<Long, BigDecimal> rateMap = gstRateByProductId != null ? gstRateByProductId : Map.of();
        // Sum gross line amount per rate, ordered by rate ascending.
        java.util.TreeMap<BigDecimal, BigDecimal> grossByRate = new java.util.TreeMap<>();
        for (OrderLineItem line : order.getLineItems()) {
            BigDecimal r = line.getGstRate();
            if (r == null && line.getProductId() != null) {
                r = rateMap.get(line.getProductId());
            }
            if (r == null) {
                r = defaultRate != null ? defaultRate : BigDecimal.ZERO;
            }
            r = r.setScale(2, java.math.RoundingMode.HALF_UP);
            BigDecimal amount = line.getLineTotal() != null ? line.getLineTotal() : BigDecimal.ZERO;
            grossByRate.merge(r, amount, BigDecimal::add);
        }

        List<GstRateGroup> groups = new ArrayList<>();
        int total = grossByRate.size();
        int index = 0;
        BigDecimal allocated = BigDecimal.ZERO;
        for (Map.Entry<BigDecimal, BigDecimal> entry : grossByRate.entrySet()) {
            index++;
            BigDecimal rate = entry.getKey();
            BigDecimal groupGross = entry.getValue();
            BigDecimal groupTaxable;
            if (index == total) {
                groupTaxable = totalTaxable.subtract(allocated).setScale(2, java.math.RoundingMode.HALF_UP);
            } else {
                groupTaxable = gross.signum() == 0
                        ? BigDecimal.ZERO.setScale(2)
                        : totalTaxable.multiply(groupGross).divide(gross, 2, java.math.RoundingMode.HALF_UP);
                allocated = allocated.add(groupTaxable);
            }
            BigDecimal groupTax = groupTaxable.multiply(rate)
                    .divide(hundred, 2, java.math.RoundingMode.HALF_UP);
            BigDecimal scaledRate = rate.setScale(2, java.math.RoundingMode.HALF_UP);
            BigDecimal zero = BigDecimal.ZERO.setScale(2);
            if (intraState) {
                BigDecimal cgst = groupTax.divide(two, 2, java.math.RoundingMode.HALF_UP);
                BigDecimal sgst = groupTax.subtract(cgst);
                groups.add(new GstRateGroup(scaledRate, true, groupTaxable, cgst, sgst, zero, groupTax));
            } else {
                groups.add(new GstRateGroup(scaledRate, false, groupTaxable, zero, zero, groupTax, groupTax));
            }
        }
        return groups;
    }

    /** The gross line subtotal (sum of line amounts, before discount). */
    private BigDecimal grossLineTotal(OrderEntity order) {
        BigDecimal sum = BigDecimal.ZERO;
        for (OrderLineItem line : order.getLineItems()) {
            if (line.getLineTotal() != null) {
                sum = sum.add(line.getLineTotal());
            }
        }
        return sum;
    }

    /**
     * Resolves the order-level GST rate to apply: each line product's own rate
     * when present, else the settings-level default. When all line products
     * resolve to the same rate it is used; a mixed-rate basket falls back to the
     * settings default so the single-rate GST computation stays consistent.
     */
    private BigDecimal resolveGstRate(OrderEntity order, Map<Long, BigDecimal> gstRateByProductId,
                                      BigDecimal defaultRate) {
        BigDecimal fallback = defaultRate != null ? defaultRate : BigDecimal.ZERO;
        Map<Long, BigDecimal> rateMap = gstRateByProductId != null ? gstRateByProductId : Map.of();
        BigDecimal common = null;
        for (com.shifa.oms.order.OrderLineItem line : order.getLineItems()) {
            BigDecimal lineRate = fallback;
            // Prefer the per-line GST-rate snapshot (Feature 2); fall back to the
            // current product map, then the settings default.
            if (line.getGstRate() != null) {
                lineRate = line.getGstRate();
            } else if (line.getProductId() != null) {
                BigDecimal productRate = rateMap.get(line.getProductId());
                if (productRate != null) {
                    lineRate = productRate;
                }
            }
            if (common == null) {
                common = lineRate;
            } else if (common.compareTo(lineRate) != 0) {
                return fallback; // mixed rates → use the settings default
            }
        }
        return common != null ? common : fallback;
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
