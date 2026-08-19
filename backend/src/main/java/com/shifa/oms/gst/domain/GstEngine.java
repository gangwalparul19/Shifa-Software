package com.shifa.oms.gst.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure, side-effect-free GST computation engine for outward supplies (sales)
 * (CA GST dashboard, Reqs 3, 4, 5).
 *
 * <p>Prices are GST-INCLUSIVE ([D1]): the taxable value is extracted from the
 * line total (`taxable = lineTotal / (1 + rate/100)`) and the GST is the
 * remainder. Each supply is classified as INTRA-state (CGST + SGST, equal
 * halves) or INTER-state (IGST) by comparing the order's place-of-supply state
 * to the seller's state. The engine aggregates the period's orders into
 * rate-wise, HSN-wise, and state-wise summaries plus a GSTR-3B-style total.
 *
 * <p>No Spring, no persistence — fully unit-testable.
 */
public final class GstEngine {

    private static final int SCALE = 2;
    private static final RoundingMode ROUND = RoundingMode.HALF_UP;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TWO = new BigDecimal("2");
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, ROUND);

    private GstEngine() {
    }

    // --- Inputs --------------------------------------------------------------

    /** One outward line: GST-inclusive line total, its GST rate percent, HSN, product name, quantity. */
    public record GstLine(String hsn, String productName, BigDecimal gstRate, int quantity,
                          BigDecimal lineTotal) {
    }

    /** One outward order in the period: place-of-supply state, order date, and its lines. */
    public record GstOrder(Long orderId, String state, LocalDate date, List<GstLine> lines) {
    }

    // --- Outputs -------------------------------------------------------------

    /** The tax split of a line/aggregate: taxable value + CGST/SGST/IGST (GST-inclusive derived). */
    public record TaxSplit(BigDecimal taxable, BigDecimal cgst, BigDecimal sgst, BigDecimal igst) {
        public BigDecimal totalTax() {
            return cgst.add(sgst).add(igst).setScale(SCALE, ROUND);
        }

        public BigDecimal invoiceValue() {
            return taxable.add(totalTax()).setScale(SCALE, ROUND);
        }
    }

    public record RateWiseRow(BigDecimal rate, BigDecimal taxable, BigDecimal cgst, BigDecimal sgst,
                              BigDecimal igst, BigDecimal invoiceValue) {
    }

    public record HsnRow(String hsn, String description, BigDecimal quantity, BigDecimal taxable,
                         BigDecimal cgst, BigDecimal sgst, BigDecimal igst) {
    }

    public record StateWiseRow(String state, SupplyType type, BigDecimal taxable,
                               BigDecimal cgst, BigDecimal sgst, BigDecimal igst) {
    }

    public record Gstr3bSummary(BigDecimal taxableOutward, BigDecimal outputCgst, BigDecimal outputSgst,
                                BigDecimal outputIgst, BigDecimal outputTotal, BigDecimal invoiceValue) {
    }

    public record GstComputation(List<RateWiseRow> rateWise, List<HsnRow> hsn,
                                 List<StateWiseRow> stateWise, Gstr3bSummary summary) {
    }

    // --- Core ----------------------------------------------------------------

    /**
     * Splits one GST-inclusive line into taxable + CGST/SGST or IGST. Intra-state
     * splits into equal halves whose sum defines the line tax (keeps cgst == sgst
     * exactly); a zero/blank rate yields zero tax.
     */
    public static TaxSplit splitLine(GstLine line, SupplyType type) {
        BigDecimal total = line.lineTotal() == null ? ZERO : line.lineTotal().setScale(SCALE, ROUND);
        BigDecimal rate = line.gstRate();
        if (rate == null || rate.signum() <= 0 || total.signum() <= 0) {
            return new TaxSplit(total, ZERO, ZERO, ZERO);
        }
        BigDecimal divisor = BigDecimal.ONE.add(rate.divide(HUNDRED, 6, ROUND));
        BigDecimal taxable0 = total.divide(divisor, SCALE, ROUND);
        BigDecimal tax0 = total.subtract(taxable0).setScale(SCALE, ROUND);
        if (type == SupplyType.INTRA) {
            BigDecimal half = tax0.divide(TWO, SCALE, ROUND);
            BigDecimal tax = half.add(half);            // defines the line tax so cgst == sgst exactly
            BigDecimal taxable = total.subtract(tax).setScale(SCALE, ROUND);
            return new TaxSplit(taxable, half, half, ZERO);
        }
        return new TaxSplit(taxable0, ZERO, ZERO, tax0);
    }

    /** Classifies an order by comparing its state to the seller state (blank seller → INTER, Req 2.3). */
    public static SupplyType classify(String orderState, String sellerState) {
        if (sellerState == null || sellerState.isBlank() || orderState == null || orderState.isBlank()) {
            return SupplyType.INTER;
        }
        return orderState.trim().equalsIgnoreCase(sellerState.trim())
                ? SupplyType.INTRA : SupplyType.INTER;
    }

    /**
     * Aggregates the period's outward orders into rate-wise / HSN-wise /
     * state-wise summaries and a GSTR-3B total. The three summaries and the total
     * reconcile to the same taxable/tax figures (Property 3).
     */
    public static GstComputation compute(List<GstOrder> orders, String sellerState) {
        Map<String, RateAcc> byRate = new LinkedHashMap<>();
        Map<String, HsnAcc> byHsn = new LinkedHashMap<>();
        Map<String, StateAcc> byState = new LinkedHashMap<>();
        BigDecimal totTaxable = ZERO;
        BigDecimal totCgst = ZERO;
        BigDecimal totSgst = ZERO;
        BigDecimal totIgst = ZERO;
        BigDecimal totInvoice = ZERO;

        for (GstOrder order : orders) {
            SupplyType type = classify(order.state(), sellerState);
            String stateKey = order.state() == null || order.state().isBlank()
                    ? "(unknown)" : order.state().trim();
            for (GstLine line : order.lines()) {
                TaxSplit s = splitLine(line, type);
                BigDecimal rate = line.gstRate() == null ? ZERO : line.gstRate().setScale(SCALE, ROUND);

                RateAcc ra = byRate.computeIfAbsent(rate.toPlainString(), k -> new RateAcc(rate));
                ra.add(s);

                String hsnKey = line.hsn() == null || line.hsn().isBlank() ? "(none)" : line.hsn().trim();
                HsnAcc ha = byHsn.computeIfAbsent(hsnKey,
                        k -> new HsnAcc(hsnKey, line.productName()));
                ha.add(s, line.quantity());

                StateAcc sa = byState.computeIfAbsent(stateKey, k -> new StateAcc(stateKey, type));
                sa.add(s);

                totTaxable = totTaxable.add(s.taxable());
                totCgst = totCgst.add(s.cgst());
                totSgst = totSgst.add(s.sgst());
                totIgst = totIgst.add(s.igst());
                totInvoice = totInvoice.add(s.invoiceValue());
            }
        }

        List<RateWiseRow> rateRows = new ArrayList<>();
        for (RateAcc a : byRate.values()) {
            rateRows.add(new RateWiseRow(a.rate, a.taxable, a.cgst, a.sgst, a.igst,
                    a.taxable.add(a.cgst).add(a.sgst).add(a.igst)));
        }
        rateRows.sort(Comparator.comparing(RateWiseRow::rate));

        List<HsnRow> hsnRows = new ArrayList<>();
        for (HsnAcc a : byHsn.values()) {
            hsnRows.add(new HsnRow(a.hsn, a.description, a.quantity, a.taxable, a.cgst, a.sgst, a.igst));
        }
        hsnRows.sort(Comparator.comparing(HsnRow::hsn));

        List<StateWiseRow> stateRows = new ArrayList<>();
        for (StateAcc a : byState.values()) {
            stateRows.add(new StateWiseRow(a.state, a.type, a.taxable, a.cgst, a.sgst, a.igst));
        }
        stateRows.sort(Comparator.comparing(StateWiseRow::taxable).reversed());

        Gstr3bSummary summary = new Gstr3bSummary(
                totTaxable.setScale(SCALE, ROUND),
                totCgst.setScale(SCALE, ROUND),
                totSgst.setScale(SCALE, ROUND),
                totIgst.setScale(SCALE, ROUND),
                totCgst.add(totSgst).add(totIgst).setScale(SCALE, ROUND),
                totInvoice.setScale(SCALE, ROUND));

        return new GstComputation(rateRows, hsnRows, stateRows, summary);
    }

    // --- Accumulators --------------------------------------------------------

    private static final class RateAcc {
        final BigDecimal rate;
        BigDecimal taxable = ZERO;
        BigDecimal cgst = ZERO;
        BigDecimal sgst = ZERO;
        BigDecimal igst = ZERO;

        RateAcc(BigDecimal rate) {
            this.rate = rate;
        }

        void add(TaxSplit s) {
            taxable = taxable.add(s.taxable());
            cgst = cgst.add(s.cgst());
            sgst = sgst.add(s.sgst());
            igst = igst.add(s.igst());
        }
    }

    private static final class HsnAcc {
        final String hsn;
        final String description;
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal taxable = ZERO;
        BigDecimal cgst = ZERO;
        BigDecimal sgst = ZERO;
        BigDecimal igst = ZERO;

        HsnAcc(String hsn, String description) {
            this.hsn = hsn;
            this.description = description == null ? "" : description;
        }

        void add(TaxSplit s, int qty) {
            quantity = quantity.add(BigDecimal.valueOf(qty));
            taxable = taxable.add(s.taxable());
            cgst = cgst.add(s.cgst());
            sgst = sgst.add(s.sgst());
            igst = igst.add(s.igst());
        }
    }

    private static final class StateAcc {
        final String state;
        final SupplyType type;
        BigDecimal taxable = ZERO;
        BigDecimal cgst = ZERO;
        BigDecimal sgst = ZERO;
        BigDecimal igst = ZERO;

        StateAcc(String state, SupplyType type) {
            this.state = state;
            this.type = type;
        }

        void add(TaxSplit s) {
            taxable = taxable.add(s.taxable());
            cgst = cgst.add(s.cgst());
            sgst = sgst.add(s.sgst());
            igst = igst.add(s.igst());
        }
    }
}
