package com.shifa.oms.gst.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Projects a refunded order return into a {@link CreditNote} for the GSTR-1
 * CDNR/CDNUR sections (GST filing compliance, Req 2.1–2.5, 14.1).
 *
 * <p>An {@link OrderReturn} records only an order-level {@code refundAmount} (no line
 * detail). To keep the <em>same</em> GST-inclusive extraction as outward supplies and
 * preserve the original order's rate mix, the refund is apportioned across the original
 * order's lines <strong>pro-rata to each line's {@code lineTotal}</strong> using a
 * largest-remainder split (so the per-line shares sum <strong>exactly</strong> to the
 * refund), and each share is run through {@link GstEngine#splitLine} at that line's GST
 * rate and the order's {@link SupplyType}. The per-line splits are summed into the note.
 * This reconciles by construction and needs no schema change (Req 2.1, 14.1).
 *
 * <p>The note's {@link NoteRegistration} follows the <em>original</em> order's
 * {@link DocumentCategory}: {@code B2B → CDNR}, otherwise {@code CDNUR} (Req 2.2). The note
 * carries the original order's code, place-of-supply state, and 2-digit state code (Req 2.5),
 * and its {@code noteDate} is the return's refund date for period attribution (Req 2.3).
 *
 * <p>Pure and side-effect free — no Spring, no persistence. Callers (the read-only
 * {@code Gstr1ReturnService}) are responsible for excluding returns against cancelled or
 * rejected orders before projection (Req 2.6).
 */
public final class CreditNoteProjection {

    private static final int SCALE = 2;
    private static final RoundingMode ROUND = RoundingMode.HALF_UP;
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, ROUND);

    private CreditNoteProjection() {
    }

    /**
     * A pure, persistence-free view of the return being projected — the minimal fields the
     * projection needs, resolved by the application service from the {@code order_returns}
     * row and its original order.
     *
     * @param returnId          the source order-return id
     * @param originalOrderCode the human-readable code of the original order (document reference)
     * @param noteDate          the note (refund) date, used for period attribution (Req 2.3)
     * @param refundAmount      the GST-inclusive refund amount (may be {@code null} → treated as zero)
     */
    public record OrderReturnView(Long returnId, String originalOrderCode, LocalDate noteDate,
                                  BigDecimal refundAmount) {
    }

    /**
     * Derive a credit note from a refunded return against its original order (Req 2.1–2.5).
     *
     * @param ret              the return being projected
     * @param originalOrder    the original order's GST view (place of supply, date, line snapshots)
     * @param originalCategory the original order's document category (drives CDNR vs CDNUR)
     * @param stateCode        the 2-digit GST state code of the place of supply (Req 2.5)
     * @param sellerState      the seller's home state, for intra/inter classification (reused from GstEngine)
     * @return the derived {@link CreditNote}; never {@code null}
     */
    public static CreditNote fromReturn(OrderReturnView ret, GstEngine.GstOrder originalOrder,
                                        DocumentCategory originalCategory, String stateCode,
                                        String sellerState) {
        SupplyType supplyType = GstEngine.classify(originalOrder.state(), sellerState);
        NoteRegistration registration = originalCategory == DocumentCategory.B2B
                ? NoteRegistration.CDNR : NoteRegistration.CDNUR;

        BigDecimal refund = ret.refundAmount() == null ? ZERO : ret.refundAmount().setScale(SCALE, ROUND);

        BigDecimal taxable = ZERO;
        BigDecimal cgst = ZERO;
        BigDecimal sgst = ZERO;
        BigDecimal igst = ZERO;

        List<GstEngine.GstLine> lines = originalOrder.lines() == null
                ? List.of() : originalOrder.lines();
        List<BigDecimal> shares = apportion(refund, lines);

        // Only apportion per-line when there is something to apportion against. When apportion
        // returns no shares (no lines, or their totals sum to zero), the size guard also prevents
        // indexing an empty shares list against a non-empty lines list. In that case the whole
        // refund is treated as taxable value with no tax split.
        if (!shares.isEmpty() && shares.size() == lines.size()) {
            for (int i = 0; i < lines.size(); i++) {
                GstEngine.GstLine src = lines.get(i);
                GstEngine.GstLine shareLine = new GstEngine.GstLine(
                        src.hsn(), src.productName(), src.gstRate(), src.quantity(), shares.get(i));
                GstEngine.TaxSplit split = GstEngine.splitLine(shareLine, supplyType);
                taxable = taxable.add(split.taxable());
                cgst = cgst.add(split.cgst());
                sgst = sgst.add(split.sgst());
                igst = igst.add(split.igst());
            }
        } else {
            // No usable lines to apportion against: the refund is all taxable value, no tax split.
            taxable = refund;
        }

        return new CreditNote(
                ret.returnId(),
                originalOrder.orderId(),
                ret.originalOrderCode(),
                ret.noteDate(),
                registration,
                originalOrder.state(),
                stateCode,
                supplyType,
                refund,
                taxable.setScale(SCALE, ROUND),
                cgst.setScale(SCALE, ROUND),
                sgst.setScale(SCALE, ROUND),
                igst.setScale(SCALE, ROUND));
    }

    /**
     * Apportion {@code refund} across the given lines pro-rata to each line's
     * {@code lineTotal} using a largest-remainder split so the returned shares sum
     * <strong>exactly</strong> to {@code refund} (in 2-decimal rupees). When there are no
     * lines, or their totals sum to zero, an empty list is returned (the caller treats the
     * whole refund as taxable value with no tax).
     */
    private static List<BigDecimal> apportion(BigDecimal refund, List<GstEngine.GstLine> lines) {
        if (lines.isEmpty()) {
            return List.of();
        }
        // Work in integer "cents" (2-decimal rupees) to keep the split exact.
        BigInteger refundCents = refund.movePointRight(SCALE).setScale(0, ROUND).toBigIntegerExact();
        BigInteger[] weights = new BigInteger[lines.size()];
        BigInteger totalWeight = BigInteger.ZERO;
        for (int i = 0; i < lines.size(); i++) {
            BigDecimal lt = lines.get(i).lineTotal();
            BigInteger w = lt == null ? BigInteger.ZERO
                    : lt.setScale(SCALE, ROUND).movePointRight(SCALE).setScale(0, ROUND).toBigIntegerExact();
            if (w.signum() < 0) {
                w = BigInteger.ZERO;
            }
            weights[i] = w;
            totalWeight = totalWeight.add(w);
        }
        if (totalWeight.signum() <= 0) {
            return List.of();
        }

        // Floor share + fractional remainder per line (largest-remainder method).
        BigInteger[] base = new BigInteger[lines.size()];
        BigInteger[] remainder = new BigInteger[lines.size()];
        BigInteger assigned = BigInteger.ZERO;
        for (int i = 0; i < lines.size(); i++) {
            BigInteger product = refundCents.multiply(weights[i]);
            base[i] = product.divide(totalWeight);
            remainder[i] = product.mod(totalWeight);
            assigned = assigned.add(base[i]);
        }

        // Distribute the leftover cents to the lines with the largest fractional remainders,
        // breaking ties by ascending index for determinism.
        long leftover = refundCents.subtract(assigned).longValueExact();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            order.add(i);
        }
        final BigInteger[] rem = remainder;
        order.sort(Comparator.<Integer, BigInteger>comparing(idx -> rem[idx]).reversed()
                .thenComparing(Comparator.naturalOrder()));
        for (int k = 0; k < leftover; k++) {
            int idx = order.get(k % order.size());
            base[idx] = base[idx].add(BigInteger.ONE);
        }

        List<BigDecimal> shares = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            shares.add(new BigDecimal(base[i]).movePointLeft(SCALE).setScale(SCALE, ROUND));
        }
        return shares;
    }
}
