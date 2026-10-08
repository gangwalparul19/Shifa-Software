package com.shifa.oms.gst.domain;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for {@link Gstr1Builder} (GST filing compliance, design Correctness
 * Properties 9, 12, 14, 15, 16).
 *
 * <p>The builder is a <em>pure</em> assembly of a period's classified outward orders + derived
 * credit notes into a portal-ready {@link Gstr1Return}. These properties pin its filing-critical
 * guarantees:
 * <ul>
 *   <li><b>Property 9</b> — the Table-12 HSN summary has exactly one row per distinct (HSN, rate)
 *       pair and its quantities/taxable/tax sum to the period totals (Req 3.1, 3.5);</li>
 *   <li><b>Property 12</b> — the Table-13 documents-issued rows are passed through faithfully, and
 *       an empty/absent docs list yields an empty (never null) list (Req 4.1, 4.2, 4.4);</li>
 *   <li><b>Property 14</b> — the summed b2b+b2cl+b2cs section taxable/tax reconcile to
 *       {@link GstEngine#compute} for the same orders — the CA dashboard figures (Req 5.5);</li>
 *   <li><b>Property 15</b> — every emitted place-of-supply row carries the 2-digit code resolved via
 *       {@link StateCodeMaster} (Req 6.1, 6.2, 6.4);</li>
 *   <li><b>Property 16</b> — orders whose place-of-supply state does not resolve are recorded as
 *       {@link UnresolvedStateFlag}s and their rows carry an empty state code (Req 6.3).</li>
 * </ul>
 *
 * <p>Each generated {@link ClassifiedOrder} is built exactly as the read-only service would:
 * {@code supplyType = GstEngine.classify(state, seller)}, {@code invoiceValue = Σ lineTotal}, and
 * {@code category = GstDocumentClassifier.classify(buyerGstin, supplyType, invoiceValue)} — so the
 * inputs honour the classifier's invariant (B2CL is always inter-state) and the reconciliation
 * property compares against the reused {@link GstEngine#compute}.
 *
 * <p>Feature: gst-filing-compliance, Property 9, Property 12, Property 14, Property 15, Property 16.
 */
class Gstr1BuilderPropertyTest {

    private static final int SCALE = 2;
    private static final RoundingMode ROUND = RoundingMode.HALF_UP;
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, ROUND);

    private static final String SELLER_STATE = "Madhya Pradesh";
    private static final String SELLER_GSTIN = "23AABCS1234F1Z5"; // valid 15-char GSTIN
    private static final String VALID_BUYER_GSTIN = "27AAECS9876Q1Z3"; // valid → makes an order B2B

    /** Known, resolvable place-of-supply states (a spread of intra + inter vs the seller). */
    private static final String[] KNOWN_STATES =
            {"Madhya Pradesh", "Maharashtra", "Karnataka", "Delhi", "Tamil Nadu", "Gujarat"};
    /** Fictional names that never resolve in the {@link StateCodeMaster}. */
    private static final String[] UNKNOWN_STATES =
            {"Atlantis", "Narnia", "Gondor", "Westeros", "Wakanda", "El Dorado"};
    private static final String[] HSNS = {"3004", "30049011", "1234", "300490", "1516"};
    private static final String[] RATES = {"0", "5", "12", "18"};

    private final StateCodeMaster master = new StateCodeMaster();

    // ---------------------------------------------------------------------------------------------
    // Feature: gst-filing-compliance, Property 9: HSN summary completeness and grouping
    // **Validates: Requirements 3.1, 3.5**
    // The HSN summary has exactly one row per distinct (HSN code, GST rate) pair, each row carries
    // HSN/UQC/rate/quantity/taxable/CGST/SGST/IGST, and the summed quantity, taxable, and tax across
    // HSN rows equal the period totals.
    // ---------------------------------------------------------------------------------------------
    @Property(tries = 300)
    void hsnSummaryIsCompleteAndGroupedByHsnAndRate(
            @ForAll("resolvableOrders") List<ClassifiedOrder> orders) {

        Gstr1Return ret = Gstr1Builder.build(orders, List.of(), Map.of(), List.of(),
                SELLER_GSTIN, SELLER_STATE, master, 4, 5, 2026);

        // Exactly one row per distinct (normalized HSN, normalized rate) pair.
        Set<String> expectedKeys = new LinkedHashSet<>();
        BigDecimal expectedQty = BigDecimal.ZERO;
        for (ClassifiedOrder co : orders) {
            for (GstEngine.GstLine line : co.order().lines()) {
                expectedKeys.add(normalizeHsn(line.hsn()) + "|" + normalizeRate(line.gstRate()).toPlainString());
                expectedQty = expectedQty.add(BigDecimal.valueOf(line.quantity()));
            }
        }
        assertThat(ret.hsn()).hasSize(expectedKeys.size());

        // Row keys are themselves unique (no duplicate (HSN, rate) rows) and complete.
        Set<String> rowKeys = new LinkedHashSet<>();
        for (HsnRow r : ret.hsn()) {
            assertThat(rowKeys.add(r.hsn() + "|" + r.rate().toPlainString()))
                    .as("duplicate HSN row for %s @ %s", r.hsn(), r.rate())
                    .isTrue();
            assertThat(r.hsn()).isNotBlank();
            assertThat(r.uqc()).isNotBlank(); // Uqc.resolve → NOS when unknown
            assertThat(r.rate()).isNotNull();
            assertThat(r.quantity()).isNotNull();
            assertThat(r.taxable()).isNotNull();
            assertThat(r.cgst()).isNotNull();
            assertThat(r.sgst()).isNotNull();
            assertThat(r.igst()).isNotNull();
        }
        assertThat(rowKeys).isEqualTo(expectedKeys);

        // Quantities and money sum to the period totals (the same figures GstEngine reports).
        BigDecimal sumQty = ret.hsn().stream().map(HsnRow::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sumTaxable = ret.hsn().stream().map(HsnRow::taxable).reduce(ZERO, BigDecimal::add);
        BigDecimal sumTax = ret.hsn().stream()
                .map(r -> r.cgst().add(r.sgst()).add(r.igst())).reduce(ZERO, BigDecimal::add);

        assertThat(sumQty).isEqualByComparingTo(expectedQty);
        assertThat(sumTaxable).isEqualByComparingTo(ret.reconciliation().taxableOutward());
        assertThat(sumTax).isEqualByComparingTo(ret.reconciliation().outputTotal());
    }

    // ---------------------------------------------------------------------------------------------
    // Feature: gst-filing-compliance, Property 12: Documents-issued summary
    // **Validates: Requirements 4.1, 4.2, 4.4**
    // The builder attaches the Table-13 docs it is given faithfully (order + contents preserved), and
    // an empty period yields an empty docs list rather than an error. (The min/max + issued/cancelled
    // derivation itself lives in the read-only service; here we pin the builder's pass-through contract.)
    // ---------------------------------------------------------------------------------------------
    @Property(tries = 200)
    void docsArePassedThroughFaithfully(
            @ForAll("resolvableOrders") List<ClassifiedOrder> orders,
            @ForAll("docRows") List<DocRow> docs) {

        Gstr1Return ret = Gstr1Builder.build(orders, List.of(), Map.of(), docs,
                SELLER_GSTIN, SELLER_STATE, master, 4, 5, 2026);

        assertThat(ret.docs())
                .as("docs must be attached faithfully, in order")
                .containsExactlyElementsOf(docs);
    }

    @Example
    void absentDocsYieldEmptyListNotNull() {
        List<ClassifiedOrder> orders = List.of(
                classify(1L, "SHR-GST-1", "Maharashtra", null,
                        List.of(new GstEngine.GstLine("3004", "P", new BigDecimal("18"), 2,
                                new BigDecimal("1180.00")))));

        Gstr1Return withEmpty = Gstr1Builder.build(orders, List.of(), Map.of(), List.of(),
                SELLER_GSTIN, SELLER_STATE, master, 4, 5, 2026);
        assertThat(withEmpty.docs()).isNotNull().isEmpty();

        Gstr1Return withNull = Gstr1Builder.build(orders, List.of(), Map.of(), null,
                SELLER_GSTIN, SELLER_STATE, master, 4, 5, 2026);
        assertThat(withNull.docs()).isNotNull().isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // Feature: gst-filing-compliance, Property 14: Section reconciliation to the GST engine
    // **Validates: Requirements 5.5**
    // The summed taxable and tax across b2b + b2cl + b2cs reconcile to GstEngine.compute(...) for the
    // same orders — the same figures the CA GST dashboard shows — and the return's reconciliation
    // field equals that engine summary exactly.
    // ---------------------------------------------------------------------------------------------
    @Property(tries = 300)
    void sectionsReconcileToTheGstEngine(
            @ForAll("resolvableOrders") List<ClassifiedOrder> orders) {

        Gstr1Return ret = Gstr1Builder.build(orders, List.of(), Map.of(), List.of(),
                SELLER_GSTIN, SELLER_STATE, master, 4, 5, 2026);

        // The dashboard figures: GstEngine.compute over the SAME orders.
        List<GstEngine.GstOrder> gstOrders = orders.stream().map(ClassifiedOrder::order).toList();
        GstEngine.Gstr3bSummary engine = GstEngine.compute(gstOrders, SELLER_STATE).summary();

        // The return carries that engine summary verbatim as its reconciliation spine.
        assertThat(ret.reconciliation().taxableOutward()).isEqualByComparingTo(engine.taxableOutward());
        assertThat(ret.reconciliation().outputCgst()).isEqualByComparingTo(engine.outputCgst());
        assertThat(ret.reconciliation().outputSgst()).isEqualByComparingTo(engine.outputSgst());
        assertThat(ret.reconciliation().outputIgst()).isEqualByComparingTo(engine.outputIgst());
        assertThat(ret.reconciliation().outputTotal()).isEqualByComparingTo(engine.outputTotal());

        // Summed section figures reconcile to the engine outward totals.
        BigDecimal sectionTaxable = ZERO
                .add(sum(ret.b2b(), B2bRow::taxable))
                .add(sum(ret.b2cl(), B2clRow::taxable))
                .add(sum(ret.b2cs(), B2csRow::taxable));
        BigDecimal sectionTax = ZERO
                .add(sum(ret.b2b(), r -> r.cgst().add(r.sgst()).add(r.igst())))
                .add(sum(ret.b2cl(), B2clRow::igst))
                .add(sum(ret.b2cs(), r -> r.cgst().add(r.sgst()).add(r.igst())));

        assertThat(sectionTaxable)
                .as("b2b+b2cl+b2cs taxable must reconcile to the GST engine outward taxable")
                .isEqualByComparingTo(engine.taxableOutward());
        assertThat(sectionTax)
                .as("b2b+b2cl+b2cs tax must reconcile to the GST engine outward tax")
                .isEqualByComparingTo(engine.outputTotal());
    }

    // ---------------------------------------------------------------------------------------------
    // Feature: gst-filing-compliance, Property 15: State-code resolution and consistency
    // **Validates: Requirements 6.1, 6.2, 6.4**
    // For known place-of-supply names, every emitted section row carries the 2-digit code resolved
    // through the StateCodeMaster, and no state is flagged unresolved.
    // ---------------------------------------------------------------------------------------------
    @Property(tries = 300)
    void everyPlaceOfSupplyRowCarriesTheResolvedStateCode(
            @ForAll("resolvableOrders") List<ClassifiedOrder> orders) {

        Gstr1Return ret = Gstr1Builder.build(orders, List.of(), Map.of(), List.of(),
                SELLER_GSTIN, SELLER_STATE, master, 4, 5, 2026);

        for (B2bRow r : ret.b2b()) {
            assertThat(r.stateCode()).isEqualTo(master.resolve(r.placeOfSupply()).orElseThrow());
            assertThat(r.stateCode()).matches("\\d{2}");
        }
        for (B2clRow r : ret.b2cl()) {
            assertThat(r.stateCode()).isEqualTo(master.resolve(r.placeOfSupply()).orElseThrow());
            assertThat(r.stateCode()).matches("\\d{2}");
        }
        for (B2csRow r : ret.b2cs()) {
            assertThat(r.stateCode()).isEqualTo(master.resolve(r.placeOfSupply()).orElseThrow());
            assertThat(r.stateCode()).matches("\\d{2}");
        }
        // Consistency with GstEngine.classify: intra rows resolve to the seller's own code.
        String sellerCode = master.resolveSellerCode(SELLER_STATE).orElseThrow();
        for (B2csRow r : ret.b2cs()) {
            if (r.supplyType() == SupplyType.INTRA) {
                assertThat(r.stateCode()).isEqualTo(sellerCode);
            }
        }

        // Known states never need a mapping.
        assertThat(ret.unresolvedStates()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // Feature: gst-filing-compliance, Property 16: Unresolved state flagging
    // **Validates: Requirements 6.3**
    // Orders whose place-of-supply state name does not resolve to a code produce one
    // UnresolvedStateFlag per distinct unresolved name, and every row for such an order carries an
    // empty state code.
    // ---------------------------------------------------------------------------------------------
    @Property(tries = 300)
    void unresolvedStatesAreFlaggedAndRowsCarryEmptyCode(
            @ForAll("unresolvableOrders") List<ClassifiedOrder> orders) {

        Gstr1Return ret = Gstr1Builder.build(orders, List.of(), Map.of(), List.of(),
                SELLER_GSTIN, SELLER_STATE, master, 4, 5, 2026);

        Set<String> expected = new LinkedHashSet<>();
        for (ClassifiedOrder co : orders) {
            expected.add(normalizeState(co.order().state()));
        }

        Set<String> flagged = new LinkedHashSet<>();
        for (UnresolvedStateFlag flag : ret.unresolvedStates()) {
            flagged.add(normalizeState(flag.stateName()));
        }

        assertThat(ret.unresolvedStates()).isNotEmpty();
        assertThat(flagged)
                .as("exactly the distinct unresolved state names are flagged")
                .isEqualTo(expected);

        // Every emitted row for an unresolved place-of-supply carries an empty code.
        for (B2bRow r : ret.b2b()) {
            assertThat(r.stateCode()).isEmpty();
        }
        for (B2clRow r : ret.b2cl()) {
            assertThat(r.stateCode()).isEmpty();
        }
        for (B2csRow r : ret.b2cs()) {
            assertThat(r.stateCode()).isEmpty();
        }
    }

    // --- Helpers ---------------------------------------------------------------------------------

    private static <T> BigDecimal sum(List<T> rows, java.util.function.Function<T, BigDecimal> f) {
        BigDecimal acc = ZERO;
        for (T r : rows) {
            acc = acc.add(f.apply(r));
        }
        return acc;
    }

    /** Build a {@link ClassifiedOrder} exactly as the read-only service would (mirrors the classifier). */
    private static ClassifiedOrder classify(long id, String code, String state, String buyerGstin,
                                            List<GstEngine.GstLine> lines) {
        GstEngine.GstOrder order = new GstEngine.GstOrder(id, state, LocalDate.of(2026, 5, 1), lines);
        SupplyType type = GstEngine.classify(state, SELLER_STATE);
        BigDecimal invoiceValue = lines.stream()
                .map(l -> l.lineTotal() == null ? BigDecimal.ZERO : l.lineTotal())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        DocumentCategory category = GstDocumentClassifier.classify(buyerGstin, type, invoiceValue);
        return new ClassifiedOrder(order, code, buyerGstin, type, invoiceValue, category);
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

    // --- Generators ------------------------------------------------------------------------------

    private Arbitrary<GstEngine.GstLine> line() {
        return Combinators.combine(
                        Arbitraries.of(HSNS),
                        Arbitraries.of(RATES).map(BigDecimal::new),
                        Arbitraries.integers().between(1, 5),
                        Arbitraries.integers().between(0, 5_000_000))
                .as((hsn, rate, qty, paise) ->
                        new GstEngine.GstLine(hsn, "P" + hsn, rate, qty,
                                new BigDecimal(paise).movePointLeft(2)));
    }

    private Arbitrary<ClassifiedOrder> classifiedOrder(Arbitrary<String> states, boolean allowB2b) {
        Arbitrary<String> gstinArb = allowB2b
                ? Arbitraries.of(VALID_BUYER_GSTIN).injectNull(0.6)
                : Arbitraries.just((String) null);
        Arbitrary<List<GstEngine.GstLine>> linesArb = line().list().ofMinSize(1).ofMaxSize(5);
        return Combinators.combine(
                        Arbitraries.longs().between(1L, 100_000L),
                        states,
                        gstinArb,
                        linesArb)
                .as((id, state, gstin, lines) -> classify(id, "SHR-GST-" + id, state, gstin, lines));
    }

    @Provide
    Arbitrary<List<ClassifiedOrder>> resolvableOrders() {
        return classifiedOrder(Arbitraries.of(KNOWN_STATES), true)
                .list().ofMinSize(1).ofMaxSize(8);
    }

    @Provide
    Arbitrary<List<ClassifiedOrder>> unresolvableOrders() {
        return classifiedOrder(Arbitraries.of(UNKNOWN_STATES), false)
                .list().ofMinSize(1).ofMaxSize(8);
    }

    @Provide
    Arbitrary<List<DocRow>> docRows() {
        Arbitrary<DocRow> row = Combinators.combine(
                        Arbitraries.of("Invoices for outward supply", "Credit note", "Debit note"),
                        Arbitraries.integers().between(1, 500),
                        Arbitraries.integers().between(0, 500),
                        Arbitraries.integers().between(0, 50))
                .as((nature, from, span, cancelled) -> new DocRow(
                        nature,
                        "INV-" + from,
                        "INV-" + (from + span),
                        span + 1,
                        Math.min(cancelled, span + 1)));
        return row.list().ofMinSize(0).ofMaxSize(5);
    }
}
