package com.shifa.oms.gst;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shifa.oms.gst.domain.B2bRow;
import com.shifa.oms.gst.domain.B2clRow;
import com.shifa.oms.gst.domain.B2csRow;
import com.shifa.oms.gst.domain.CdnrRow;
import com.shifa.oms.gst.domain.CdnurRow;
import com.shifa.oms.gst.domain.DocRow;
import com.shifa.oms.gst.domain.Gstr1Return;
import com.shifa.oms.gst.domain.HsnRow;
import com.shifa.oms.gst.domain.SupplyType;
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
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for {@link Gstr1Exporter} (GST filing compliance, design Correctness
 * Property 13).
 *
 * <p><b>Property 13 — Portal JSON export fidelity.</b> <em>For any</em> {@link Gstr1Return}, parsing
 * the exported portal JSON back reproduces the same per-section taxable and tax totals as the
 * in-memory return, and an empty section is omitted from the JSON without failing the export
 * (Req 5.3, 5.6).
 *
 * <p>The exporter is a pure renderer, so the test drives it directly with a freshly generated
 * {@link Gstr1Return} whose seven sections are each generated independently (lists may be empty, so
 * empty-section handling is exercised naturally). Every monetary figure is generated at 2-decimal
 * (paise) precision, matching the exporter's {@code num()} rounding, so the round-trip is exact.
 * The exporter is instantiated directly with a plain Jackson {@link ObjectMapper} (no Spring).
 *
 * <p>Feature: gst-filing-compliance, Property 13.
 */
class Gstr1ExporterPropertyTest {

    private static final int SCALE = 2;
    private static final RoundingMode ROUND = RoundingMode.HALF_UP;

    private static final String SELLER_GSTIN = "23AABCS1234F1Z5";
    private static final int MONTH = 4;
    private static final int YEAR = 2026;

    private final Gstr1Exporter exporter = new Gstr1Exporter(new ObjectMapper());
    private final ObjectMapper mapper = new ObjectMapper();

    // ---------------------------------------------------------------------------------------------
    // Feature: gst-filing-compliance, Property 13: Portal JSON export fidelity
    // **Validates: Requirements 5.3, 5.6**
    // Parsing the exported portal JSON reproduces the same per-section taxable and tax totals as the
    // in-memory Gstr1Return; empty sections are omitted from the JSON and the export never fails.
    // ---------------------------------------------------------------------------------------------
    @Property(tries = 200)
    void portalJsonRoundTripsPerSectionTotalsAndOmitsEmptySections(
            @ForAll("b2bRows") List<B2bRow> b2b,
            @ForAll("b2clRows") List<B2clRow> b2cl,
            @ForAll("b2csRows") List<B2csRow> b2cs,
            @ForAll("cdnrRows") List<CdnrRow> cdnr,
            @ForAll("cdnurRows") List<CdnurRow> cdnur,
            @ForAll("hsnRows") List<HsnRow> hsn,
            @ForAll("docRows") List<DocRow> docs) throws Exception {

        Gstr1Return ret = new Gstr1Return(
                SELLER_GSTIN, MONTH, YEAR, b2b, b2cl, b2cs, cdnr, cdnur, hsn, docs,
                List.of(), null);

        String json = exporter.toPortalJson(ret);
        JsonNode root = mapper.readTree(json);

        // Header fields are always present (Req 5.4).
        assertThat(root.path("gstin").asText()).isEqualTo(SELLER_GSTIN);
        assertThat(root.path("fp").asText()).isEqualTo("042026");

        // b2b: taxable = Σ txval, tax = Σ (camt + samt + iamt).
        assertSection(root, "b2b", b2b,
                sum(b2b, B2bRow::taxable),
                sum(b2b, r -> r.cgst().add(r.sgst()).add(r.igst())),
                "txval", "camt", "samt", "iamt");

        // b2cl: inter-state only → tax = Σ iamt.
        assertSection(root, "b2cl", b2cl,
                sum(b2cl, B2clRow::taxable),
                sum(b2cl, B2clRow::igst),
                "txval", "iamt");

        // b2cs: taxable = Σ txval, tax = Σ (camt + samt + iamt).
        assertSection(root, "b2cs", b2cs,
                sum(b2cs, B2csRow::taxable),
                sum(b2cs, r -> r.cgst().add(r.sgst()).add(r.igst())),
                "txval", "camt", "samt", "iamt");

        // cdnr: taxable = Σ txval, tax = Σ (camt + samt + iamt).
        assertSection(root, "cdnr", cdnr,
                sum(cdnr, CdnrRow::taxable),
                sum(cdnr, r -> r.cgst().add(r.sgst()).add(r.igst())),
                "txval", "camt", "samt", "iamt");

        // cdnur: taxable = Σ txval, tax = Σ (camt + samt + iamt).
        assertSection(root, "cdnur", cdnur,
                sum(cdnur, CdnurRow::taxable),
                sum(cdnur, r -> r.cgst().add(r.sgst()).add(r.igst())),
                "txval", "camt", "samt", "iamt");

        // hsn: taxable = Σ txval, tax = Σ (iamt + camt + samt).
        assertSection(root, "hsn", hsn,
                sum(hsn, HsnRow::taxable),
                sum(hsn, r -> r.cgst().add(r.sgst()).add(r.igst())),
                "txval", "iamt", "camt", "samt");

        // doc_issue carries no tax, but the issued/cancelled counts must round-trip (Req 5.3).
        if (docs.isEmpty()) {
            assertThat(root.has("doc_issue")).as("empty docs omitted from JSON").isFalse();
        } else {
            JsonNode arr = root.get("doc_issue");
            assertThat(arr).isNotNull();
            assertThat(arr.size()).isEqualTo(docs.size());
            long expectedTotal = docs.stream().mapToLong(DocRow::totalCount).sum();
            long expectedCancel = docs.stream().mapToLong(DocRow::cancelledCount).sum();
            long actualTotal = 0;
            long actualCancel = 0;
            for (JsonNode n : arr) {
                actualTotal += n.path("totnum").asLong();
                actualCancel += n.path("cancel").asLong();
            }
            assertThat(actualTotal).isEqualTo(expectedTotal);
            assertThat(actualCancel).isEqualTo(expectedCancel);
        }
    }

    @Example
    void allEmptySectionsProduceHeaderOnlyJsonWithoutFailing() throws Exception {
        Gstr1Return empty = new Gstr1Return(
                SELLER_GSTIN, 12, 2025,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), null);

        String json = exporter.toPortalJson(empty);
        JsonNode root = mapper.readTree(json);

        assertThat(root.path("gstin").asText()).isEqualTo(SELLER_GSTIN);
        assertThat(root.path("fp").asText()).isEqualTo("122025");
        for (String key : List.of("b2b", "b2cl", "b2cs", "cdnr", "cdnur", "hsn", "doc_issue")) {
            assertThat(root.has(key)).as("empty section %s omitted", key).isFalse();
        }
    }

    // --- Assertions ------------------------------------------------------------------------------

    /**
     * Asserts empty→omitted / non-empty→present with matching row count, and that the JSON per-row
     * {@code txval} and the given tax fields sum back to the in-memory taxable/tax totals.
     */
    private void assertSection(JsonNode root, String key, List<?> rows,
                               BigDecimal expectedTaxable, BigDecimal expectedTax,
                               String taxableField, String... taxFields) {
        if (rows.isEmpty()) {
            assertThat(root.has(key)).as("empty section %s must be omitted from JSON", key).isFalse();
            return;
        }
        JsonNode arr = root.get(key);
        assertThat(arr).as("non-empty section %s must be present", key).isNotNull();
        assertThat(arr.size()).as("row count for %s", key).isEqualTo(rows.size());

        BigDecimal actualTaxable = BigDecimal.ZERO;
        BigDecimal actualTax = BigDecimal.ZERO;
        for (JsonNode n : arr) {
            actualTaxable = actualTaxable.add(BigDecimal.valueOf(n.path(taxableField).asDouble()));
            for (String f : taxFields) {
                actualTax = actualTax.add(BigDecimal.valueOf(n.path(f).asDouble()));
            }
        }
        assertThat(actualTaxable.setScale(SCALE, ROUND))
                .as("section %s taxable must round-trip", key)
                .isEqualByComparingTo(expectedTaxable.setScale(SCALE, ROUND));
        assertThat(actualTax.setScale(SCALE, ROUND))
                .as("section %s tax must round-trip", key)
                .isEqualByComparingTo(expectedTax.setScale(SCALE, ROUND));
    }

    private static <T> BigDecimal sum(List<T> rows, Function<T, BigDecimal> f) {
        BigDecimal acc = BigDecimal.ZERO;
        for (T r : rows) {
            BigDecimal v = f.apply(r);
            acc = acc.add(v == null ? BigDecimal.ZERO : v.setScale(SCALE, ROUND));
        }
        return acc;
    }

    // --- Generators ------------------------------------------------------------------------------

    /** GST-inclusive money at exact 2-decimal (paise) precision, matching the exporter's rounding. */
    private static Arbitrary<BigDecimal> money() {
        return Arbitraries.integers().between(0, 5_000_000)
                .map(paise -> new BigDecimal(paise).movePointLeft(2));
    }

    private static Arbitrary<BigDecimal> rate() {
        return Arbitraries.of("0", "5", "12", "18").map(BigDecimal::new);
    }

    private static Arbitrary<BigDecimal> quantity() {
        return Arbitraries.integers().between(1, 50).map(BigDecimal::valueOf);
    }

    @Provide
    Arbitrary<List<B2bRow>> b2bRows() {
        Arbitrary<B2bRow> row = Combinators.combine(rate(), money(), money(), money(), money())
                .as((rt, taxable, cgst, sgst, igst) -> new B2bRow(
                        "27AAECS9876Q1Z3", "SHR-B2B", LocalDate.of(YEAR, MONTH, 12),
                        money().sample(), "Maharashtra", "27", rt, taxable, cgst, sgst, igst));
        return row.list().ofMinSize(0).ofMaxSize(6);
    }

    @Provide
    Arbitrary<List<B2clRow>> b2clRows() {
        Arbitrary<B2clRow> row = Combinators.combine(rate(), money(), money())
                .as((rt, taxable, igst) -> new B2clRow(
                        "SHR-B2CL", LocalDate.of(YEAR, MONTH, 12), money().sample(),
                        "Karnataka", "29", rt, taxable, igst));
        return row.list().ofMinSize(0).ofMaxSize(6);
    }

    @Provide
    Arbitrary<List<B2csRow>> b2csRows() {
        Arbitrary<B2csRow> row = Combinators.combine(
                        Arbitraries.of(SupplyType.INTRA, SupplyType.INTER),
                        rate(), money(), money(), money(), money())
                .as((st, rt, taxable, cgst, sgst, igst) -> new B2csRow(
                        "OE", "Gujarat", "24", st, rt, taxable, cgst, sgst, igst));
        return row.list().ofMinSize(0).ofMaxSize(6);
    }

    @Provide
    Arbitrary<List<CdnrRow>> cdnrRows() {
        Arbitrary<CdnrRow> row = Combinators.combine(rate(), money(), money(), money(), money())
                .as((rt, taxable, cgst, sgst, igst) -> new CdnrRow(
                        "27AAECS9876Q1Z3", "CN-1", LocalDate.of(YEAR, MONTH, 15), "SHR-B2B",
                        "Maharashtra", "27", money().sample(), rt, taxable, cgst, sgst, igst));
        return row.list().ofMinSize(0).ofMaxSize(6);
    }

    @Provide
    Arbitrary<List<CdnurRow>> cdnurRows() {
        Arbitrary<CdnurRow> row = Combinators.combine(rate(), money(), money(), money(), money())
                .as((rt, taxable, cgst, sgst, igst) -> new CdnurRow(
                        "CN-2", LocalDate.of(YEAR, MONTH, 15), "SHR-B2CL",
                        "Karnataka", "29", money().sample(), rt, taxable, cgst, sgst, igst));
        return row.list().ofMinSize(0).ofMaxSize(6);
    }

    @Provide
    Arbitrary<List<HsnRow>> hsnRows() {
        Arbitrary<HsnRow> row = Combinators.combine(rate(), quantity(), money(), money(), money(), money())
                .as((rt, qty, taxable, cgst, sgst, igst) -> new HsnRow(
                        "30049011", "NOS", rt, qty, taxable, cgst, sgst, igst, true, null));
        return row.list().ofMinSize(0).ofMaxSize(6);
    }

    @Provide
    Arbitrary<List<DocRow>> docRows() {
        Arbitrary<DocRow> row = Combinators.combine(
                        Arbitraries.integers().between(1, 500),
                        Arbitraries.integers().between(0, 200),
                        Arbitraries.integers().between(0, 50))
                .as((from, span, cancelled) -> new DocRow(
                        "Invoices for outward supply", "INV-" + from, "INV-" + (from + span),
                        span + 1, Math.min(cancelled, span + 1)));
        return row.list().ofMinSize(0).ofMaxSize(5);
    }
}
