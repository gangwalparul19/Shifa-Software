package com.shifa.oms.gst.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Assembles a period's classified outward orders and derived credit/debit notes into a portal-ready
 * {@link Gstr1Return} (GST filing compliance, Tier 1 — composes Reqs 1–6).
 *
 * <p>The builder walks the classified orders once, routing each into its GSTR-1 section by
 * {@link DocumentCategory}:
 * <ul>
 *   <li><strong>B2B</strong> → one invoice-level {@link B2bRow} per distinct GST rate on the order;</li>
 *   <li><strong>B2CL</strong> → one invoice-level {@link B2clRow} per rate (IGST only — B2CL is always
 *       inter-state);</li>
 *   <li><strong>B2CS</strong> → aggregated {@link B2csRow}s keyed by (place-of-supply, supply type,
 *       rate).</li>
 * </ul>
 * Credit notes become {@link CdnrRow}/{@link CdnurRow} by their {@link NoteRegistration}. The
 * Table-12 HSN summary is grouped by (HSN code, GST rate) with UQC ({@link Uqc#resolve}), rate,
 * quantity, taxable, and CGST/SGST/IGST plus an HSN-length compliance flag
 * ({@link HsnCompliance#isCompliant}). The Table-13 docs are attached as supplied. Every
 * place-of-supply is resolved to its 2-digit code via the {@link StateCodeMaster}, and any name that
 * fails to resolve is recorded as an {@link UnresolvedStateFlag} (Req 6.2, 6.3).
 *
 * <p><strong>Reconciliation spine (Req 5.5):</strong> all tax is extracted with the <em>same</em>
 * {@link GstEngine#splitLine} the CA dashboard uses, and each order's supply type is (re)derived with
 * {@link GstEngine#classify}, so the summed section figures reconcile by construction to
 * {@link GstEngine#compute} for the same orders. That period total is attached as the return's
 * {@link Gstr1Return#reconciliation()} summary.
 *
 * <p>Pure and Spring-free — fully unit- and property-testable.
 */
public final class Gstr1Builder {

    private static final int SCALE = 2;
    private static final RoundingMode ROUND = RoundingMode.HALF_UP;
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, ROUND);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** The B2CS GST Offline Tool "type" code — "OE" (over-the-counter / others). */
    private static final String B2CS_TYPE = "OE";

    private Gstr1Builder() {
    }

    /**
     * Assemble the GSTR-1 return for a reporting period.
     *
     * @param orders       the period's classified outward orders (excludes cancelled/rejected)
     * @param notes        the period's derived credit/debit notes
     * @param productUqc   HSN code → the product's UQC (resolved to {@code NOS} when blank/unknown)
     * @param docs         the Table-13 documents-issued rows (built separately by the service)
     * @param sellerGstin  the seller's GSTIN (Req 5.4)
     * @param sellerState  the seller's home state, for intra/inter classification (reused from GstEngine)
     * @param stateCodes   the state-code master used to resolve every place-of-supply (Req 6)
     * @param hsnMinLength the enforced minimum HSN length for compliance flagging (Req 3.3, 3.4)
     * @param month        the return-period month (1–12) (Req 5.4)
     * @param year         the return-period year (Req 5.4)
     * @return the assembled {@link Gstr1Return}; never {@code null}
     */
    public static Gstr1Return build(
            List<ClassifiedOrder> orders,
            List<CreditNote> notes,
            Map<String, String> productUqc,
            List<DocRow> docs,
            String sellerGstin,
            String sellerState,
            StateCodeMaster stateCodes,
            int hsnMinLength,
            int month,
            int year) {

        List<ClassifiedOrder> safeOrders = orders == null ? List.of() : orders;
        List<CreditNote> safeNotes = notes == null ? List.of() : notes;
        Map<String, String> uqcByHsn = productUqc == null ? Map.of() : productUqc;

        List<B2bRow> b2b = new ArrayList<>();
        List<B2clRow> b2cl = new ArrayList<>();
        Map<String, B2csAcc> b2csByKey = new LinkedHashMap<>();
        Map<String, HsnAcc> hsnByKey = new LinkedHashMap<>();

        // Unresolved place-of-supply flags, de-duplicated by normalized state name (first context kept).
        List<UnresolvedStateFlag> unresolved = new ArrayList<>();
        Set<String> unresolvedSeen = new LinkedHashSet<>();

        for (ClassifiedOrder co : safeOrders) {
            GstEngine.GstOrder order = co.order();
            SupplyType type = GstEngine.classify(order.state(), sellerState);
            String stateName = order.state() == null ? "" : order.state().trim();
            String stateCode = resolveCode(stateCodes, stateName,
                    "order " + co.orderCode(), unresolved, unresolvedSeen);

            // Group this order's lines by GST rate and accumulate the tax split at the order's type.
            Map<String, RateAcc> byRate = new LinkedHashMap<>();
            for (GstEngine.GstLine line : safeLines(order)) {
                BigDecimal rate = normalizeRate(line.gstRate());
                GstEngine.TaxSplit split = GstEngine.splitLine(line, type);
                byRate.computeIfAbsent(rate.toPlainString(), k -> new RateAcc(rate)).add(split);

                // HSN Table-12 grouping is across ALL sections (b2b + b2cl + b2cs).
                String hsnKey = normalizeHsn(line.hsn());
                String rateKey = rate.toPlainString();
                hsnByKey.computeIfAbsent(hsnKey + "|" + rateKey,
                                k -> new HsnAcc(hsnKey, rate, line.productName()))
                        .add(split, line.quantity());
            }

            List<RateAcc> rateAccs = new ArrayList<>(byRate.values());
            rateAccs.sort(Comparator.comparing(a -> a.rate));

            switch (co.category()) {
                case B2B -> {
                    for (RateAcc a : rateAccs) {
                        b2b.add(new B2bRow(co.buyerGstin(), co.orderCode(), order.date(),
                                scale(co.invoiceValue()), stateName, stateCode,
                                a.rate, a.taxable, a.cgst, a.sgst, a.igst));
                    }
                }
                case B2CL -> {
                    for (RateAcc a : rateAccs) {
                        // B2CL is always inter-state → report IGST only.
                        b2cl.add(new B2clRow(co.orderCode(), order.date(), scale(co.invoiceValue()),
                                stateName, stateCode, a.rate, a.taxable, a.igst));
                    }
                }
                case B2CS -> {
                    for (RateAcc a : rateAccs) {
                        String key = normalizeState(stateName) + "|" + type + "|" + a.rate.toPlainString();
                        b2csByKey.computeIfAbsent(key,
                                        k -> new B2csAcc(stateName, stateCode, type, a.rate))
                                .add(a);
                    }
                }
                default -> {
                    // DocumentCategory is exhaustive; no other value is possible.
                }
            }
        }

        // B2CS aggregated rows.
        List<B2csRow> b2cs = new ArrayList<>();
        for (B2csAcc a : b2csByKey.values()) {
            b2cs.add(new B2csRow(B2CS_TYPE, a.placeOfSupply, a.stateCode, a.supplyType,
                    a.rate, a.taxable, a.cgst, a.sgst, a.igst));
        }

        // Credit / debit notes → CDNR / CDNUR rows.
        List<CdnrRow> cdnr = new ArrayList<>();
        List<CdnurRow> cdnur = new ArrayList<>();
        for (CreditNote note : safeNotes) {
            String noteState = note.placeOfSupplyState() == null ? "" : note.placeOfSupplyState().trim();
            // Prefer the state code carried on the note (resolved consistently by the service); fall
            // back to resolving here so an unresolved place-of-supply is still flagged.
            String noteCode = note.stateCode();
            if (noteCode == null || noteCode.isBlank()) {
                noteCode = resolveCode(stateCodes, noteState,
                        "note for order " + note.originalOrderCode(), unresolved, unresolvedSeen);
            }
            BigDecimal rate = blendedRate(note.taxable(), note.totalTax());
            String noteNumber = "CN-" + note.originalOrderCode();
            if (note.registration() == NoteRegistration.CDNR) {
                // The buyer GSTIN is not carried on the aggregated CreditNote projection; it is
                // unknown at note level, so the CDNR row leaves it null.
                cdnr.add(new CdnrRow(null,
                        noteNumber, note.noteDate(), note.originalOrderCode(), noteState, noteCode,
                        scale(note.noteValue()), rate, scale(note.taxable()),
                        scale(note.cgst()), scale(note.sgst()), scale(note.igst())));
            } else {
                cdnur.add(new CdnurRow(noteNumber, note.noteDate(), note.originalOrderCode(),
                        noteState, noteCode, scale(note.noteValue()), rate, scale(note.taxable()),
                        scale(note.cgst()), scale(note.sgst()), scale(note.igst())));
            }
        }

        // HSN Table-12 rows, sorted by (HSN, rate) for determinism, with UQC + compliance flag.
        List<HsnRow> hsn = new ArrayList<>();
        for (HsnAcc a : hsnByKey.values()) {
            String uqc = Uqc.resolve(uqcByHsn.get(a.hsn));
            boolean compliant = HsnCompliance.isCompliant(a.hsn, hsnMinLength);
            String note = compliant ? null
                    : "HSN '" + a.hsn + "'" + (a.productName == null || a.productName.isBlank()
                            ? "" : " for product '" + a.productName + "'")
                        + " is shorter than the required " + hsnMinLength + " digits";
            hsn.add(new HsnRow(a.hsn, uqc, a.rate, a.quantity, a.taxable, a.cgst, a.sgst, a.igst,
                    compliant, note));
        }
        hsn.sort(Comparator.<HsnRow, String>comparing(HsnRow::hsn)
                .thenComparing(HsnRow::rate));

        // Reconciliation spine: the same GstEngine period totals the dashboard shows (Req 5.5).
        List<GstEngine.GstOrder> gstOrders = new ArrayList<>();
        for (ClassifiedOrder co : safeOrders) {
            gstOrders.add(co.order());
        }
        GstEngine.Gstr3bSummary reconciliation = GstEngine.compute(gstOrders, sellerState).summary();

        List<DocRow> safeDocs = docs == null ? List.of() : docs;

        return new Gstr1Return(sellerGstin, month, year, b2b, b2cl, b2cs, cdnr, cdnur, hsn,
                safeDocs, unresolved, reconciliation);
    }

    // --- Helpers -------------------------------------------------------------

    private static List<GstEngine.GstLine> safeLines(GstEngine.GstOrder order) {
        return order.lines() == null ? List.of() : order.lines();
    }

    private static BigDecimal normalizeRate(BigDecimal rate) {
        return rate == null ? ZERO : rate.setScale(SCALE, ROUND);
    }

    private static String normalizeHsn(String hsn) {
        return hsn == null || hsn.isBlank() ? "(none)" : hsn.trim();
    }

    private static String normalizeState(String state) {
        return state == null ? "" : state.trim().replaceAll("\\s+", " ").toLowerCase();
    }

    private static BigDecimal scale(BigDecimal v) {
        return v == null ? ZERO : v.setScale(SCALE, ROUND);
    }

    /**
     * Blended effective GST rate of an aggregated note = totalTax / taxable * 100, at 2 decimals.
     * For a single-rate note this recovers the exact rate; for a mixed-rate note it is the weighted
     * average. Zero when there is no taxable value.
     */
    private static BigDecimal blendedRate(BigDecimal taxable, BigDecimal totalTax) {
        if (taxable == null || taxable.signum() <= 0 || totalTax == null) {
            return ZERO;
        }
        return totalTax.multiply(HUNDRED).divide(taxable, SCALE, ROUND);
    }

    /**
     * Resolve a place-of-supply name to its 2-digit code, recording an {@link UnresolvedStateFlag}
     * (de-duplicated by normalized name) when it does not resolve (Req 6.2, 6.3).
     */
    private static String resolveCode(StateCodeMaster stateCodes, String stateName, String context,
                                      List<UnresolvedStateFlag> unresolved, Set<String> seen) {
        String code = stateCodes == null ? null : stateCodes.resolve(stateName).orElse(null);
        if (code != null) {
            return code;
        }
        String key = normalizeState(stateName);
        if (seen.add(key)) {
            unresolved.add(new UnresolvedStateFlag(stateName, context));
        }
        return "";
    }

    // --- Accumulators --------------------------------------------------------

    /** Per-rate tax accumulator for one order's lines. */
    private static final class RateAcc {
        final BigDecimal rate;
        BigDecimal taxable = ZERO;
        BigDecimal cgst = ZERO;
        BigDecimal sgst = ZERO;
        BigDecimal igst = ZERO;

        RateAcc(BigDecimal rate) {
            this.rate = rate;
        }

        void add(GstEngine.TaxSplit s) {
            taxable = taxable.add(s.taxable());
            cgst = cgst.add(s.cgst());
            sgst = sgst.add(s.sgst());
            igst = igst.add(s.igst());
        }
    }

    /** Aggregates B2CS across orders keyed by (place-of-supply, supply type, rate). */
    private static final class B2csAcc {
        final String placeOfSupply;
        final String stateCode;
        final SupplyType supplyType;
        final BigDecimal rate;
        BigDecimal taxable = ZERO;
        BigDecimal cgst = ZERO;
        BigDecimal sgst = ZERO;
        BigDecimal igst = ZERO;

        B2csAcc(String placeOfSupply, String stateCode, SupplyType supplyType, BigDecimal rate) {
            this.placeOfSupply = placeOfSupply;
            this.stateCode = stateCode;
            this.supplyType = supplyType;
            this.rate = rate;
        }

        void add(RateAcc a) {
            taxable = taxable.add(a.taxable);
            cgst = cgst.add(a.cgst);
            sgst = sgst.add(a.sgst);
            igst = igst.add(a.igst);
        }
    }

    /** Aggregates the Table-12 HSN summary across orders keyed by (HSN, rate). */
    private static final class HsnAcc {
        final String hsn;
        final BigDecimal rate;
        final String productName;
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal taxable = ZERO;
        BigDecimal cgst = ZERO;
        BigDecimal sgst = ZERO;
        BigDecimal igst = ZERO;

        HsnAcc(String hsn, BigDecimal rate, String productName) {
            this.hsn = hsn;
            this.rate = rate;
            this.productName = productName;
        }

        void add(GstEngine.TaxSplit s, int qty) {
            quantity = quantity.add(BigDecimal.valueOf(qty));
            taxable = taxable.add(s.taxable());
            cgst = cgst.add(s.cgst());
            sgst = sgst.add(s.sgst());
            igst = igst.add(s.igst());
        }
    }
}
