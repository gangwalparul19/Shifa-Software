package com.shifa.oms.gst.domain;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for {@link GstDocumentClassifier} (GST filing compliance,
 * design Correctness Properties 2–4).
 *
 * <p>Feature: gst-filing-compliance, Property 2 (classification determinism and totality),
 * Property 3 (B2CL threshold boundary), Property 4 (classification preserves order snapshots).
 */
class GstDocumentClassifierPropertyTest {

    private static final String[] RATES = {"0", "5", "18"};

    // ---------------------------------------------------------------------------------------------
    // Feature: gst-filing-compliance, Property 2: Classification determinism and totality
    // **Validates: Requirements 1.4, 1.6, 1.7**
    // For any order (including a null buyer GSTIN), classify returns exactly one of
    // {B2B, B2CL, B2CS}, never throws, and is deterministic: B2B when the GSTIN is valid;
    // otherwise B2CL when inter-state and invoice value > 2,50,000; otherwise B2CS.
    // ---------------------------------------------------------------------------------------------
    @Property(tries = 400)
    void classificationIsTotalDeterministicAndFollowsTheRule(
            @ForAll("anyGstin") String buyerGstin,
            @ForAll boolean inter,
            @ForAll("anyInvoiceValue") BigDecimal invoiceValue) {
        SupplyType supplyType = inter ? SupplyType.INTER : SupplyType.INTRA;

        DocumentCategory result = GstDocumentClassifier.classify(buyerGstin, supplyType, invoiceValue);

        // Totality: never null, always exactly one of the three categories.
        assertThat(result).isNotNull();
        assertThat(EnumSet.allOf(DocumentCategory.class)).contains(result);

        // Determinism: same inputs -> same output.
        assertThat(GstDocumentClassifier.classify(buyerGstin, supplyType, invoiceValue))
                .isEqualTo(result);

        // Matches the specified rule.
        DocumentCategory expected;
        if (Gstin.isValid(buyerGstin)) {
            expected = DocumentCategory.B2B;
        } else if (supplyType == SupplyType.INTER
                && invoiceValue != null
                && invoiceValue.compareTo(GstDocumentClassifier.B2CL_THRESHOLD) > 0) {
            expected = DocumentCategory.B2CL;
        } else {
            expected = DocumentCategory.B2CS;
        }
        assertThat(result).isEqualTo(expected);
    }

    // ---------------------------------------------------------------------------------------------
    // Feature: gst-filing-compliance, Property 3: B2CL threshold boundary
    // **Validates: Requirements 1.5**
    // For an inter-state, unregistered-buyer supply: value strictly > 2,50,000 -> B2CL;
    // value == or < 2,50,000 -> B2CS. Values straddle 250000 (paise range around 250000.00).
    // ---------------------------------------------------------------------------------------------
    @Property(tries = 400)
    void b2clOnlyWhenInterStateUnregisteredValueStrictlyAboveThreshold(
            @ForAll("absentGstin") String absentGstin,
            @ForAll @IntRange(min = 24_990_000, max = 25_010_000) int paise) {
        BigDecimal invoiceValue = new BigDecimal(paise).movePointLeft(2);

        DocumentCategory result =
                GstDocumentClassifier.classify(absentGstin, SupplyType.INTER, invoiceValue);

        boolean aboveThreshold = invoiceValue.compareTo(GstDocumentClassifier.B2CL_THRESHOLD) > 0;
        if (aboveThreshold) {
            assertThat(result).isEqualTo(DocumentCategory.B2CL);
        } else {
            // exactly 2,50,000 or below is B2CS, never B2CL.
            assertThat(result).isEqualTo(DocumentCategory.B2CS);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Feature: gst-filing-compliance, Property 4: Classification preserves order snapshots
    // **Validates: Requirements 1.8**
    // classify is a pure function of (buyerGstin, SupplyType, invoiceValue) and does not mutate
    // any input/snapshot: after classifying, every stored line tax snapshot
    // (hsn, gstRate, quantity, lineTotal) is unchanged.
    // ---------------------------------------------------------------------------------------------
    @Property(tries = 300)
    void classificationDoesNotMutateOrderSnapshots(
            @ForAll("anyGstin") String buyerGstin,
            @ForAll boolean inter,
            @ForAll @Size(min = 1, max = 6) List<@IntRange(min = 1, max = 500_000) Integer> lineTotals) {
        // Build an order's immutable line snapshots.
        List<GstEngine.GstLine> lines = new ArrayList<>();
        for (int i = 0; i < lineTotals.size(); i++) {
            BigDecimal lineTotal = new BigDecimal(lineTotals.get(i)).movePointLeft(2);
            BigDecimal rate = new BigDecimal(RATES[i % RATES.length]);
            lines.add(new GstEngine.GstLine("3004", "P" + i, rate, 1 + (i % 3), lineTotal));
        }

        // Capture the snapshot values before classification.
        List<String> hsnBefore = new ArrayList<>();
        List<BigDecimal> rateBefore = new ArrayList<>();
        List<Integer> qtyBefore = new ArrayList<>();
        List<BigDecimal> totalBefore = new ArrayList<>();
        BigDecimal invoiceValue = BigDecimal.ZERO;
        for (GstEngine.GstLine line : lines) {
            hsnBefore.add(line.hsn());
            rateBefore.add(line.gstRate());
            qtyBefore.add(line.quantity());
            totalBefore.add(line.lineTotal());
            invoiceValue = invoiceValue.add(line.lineTotal());
        }

        SupplyType supplyType = inter ? SupplyType.INTER : SupplyType.INTRA;

        DocumentCategory first = GstDocumentClassifier.classify(buyerGstin, supplyType, invoiceValue);
        // A second call with the same inputs must yield the same category (purity, no side effects).
        DocumentCategory second = GstDocumentClassifier.classify(buyerGstin, supplyType, invoiceValue);
        assertThat(second).isEqualTo(first);

        // Every stored line tax snapshot is unchanged after classification.
        for (int i = 0; i < lines.size(); i++) {
            GstEngine.GstLine line = lines.get(i);
            assertThat(line.hsn()).isEqualTo(hsnBefore.get(i));
            assertThat(line.gstRate()).isEqualByComparingTo(rateBefore.get(i));
            assertThat(line.quantity()).isEqualTo(qtyBefore.get(i));
            assertThat(line.lineTotal()).isEqualByComparingTo(totalBefore.get(i));
        }
    }

    // --- Generators ------------------------------------------------------------------------------

    /** Canonical 15-char GSTINs: 2 digits, 5 letters, 4 digits, 1 letter, 1 alnum, 'Z', 1 alnum. */
    @Provide
    Arbitrary<String> validGstin() {
        Arbitrary<String> twoDigits = Arbitraries.strings().numeric().ofLength(2);
        Arbitrary<String> fiveLetters = Arbitraries.strings().withCharRange('A', 'Z').ofLength(5);
        Arbitrary<String> fourDigits = Arbitraries.strings().numeric().ofLength(4);
        Arbitrary<String> oneLetter = Arbitraries.strings().withCharRange('A', 'Z').ofLength(1);
        Arbitrary<String> entity = alnum();
        Arbitrary<String> checksum = alnum();
        return Combinators.combine(twoDigits, fiveLetters, fourDigits, oneLetter, entity, checksum)
                .as((sc, pan1, pan2, pan3, ent, chk) -> sc + pan1 + pan2 + pan3 + ent + "Z" + chk);
    }

    private static Arbitrary<String> alnum() {
        return Arbitraries.strings().withCharRange('A', 'Z').withCharRange('0', '9').ofLength(1);
    }

    /** Absent/blank/malformed GSTINs (never match the canonical format); includes null. */
    @Provide
    Arbitrary<String> absentGstin() {
        return Arbitraries.of("", "   ", "abc", "12ABC", "NOTGSTIN", "1234567890", "ZZ")
                .injectNull(0.4);
    }

    /** A mix of valid and absent/malformed GSTINs for the general totality property. */
    @Provide
    Arbitrary<String> anyGstin() {
        return Arbitraries.oneOf(validGstin(), absentGstin());
    }

    /** Invoice values across the whole range, including at/around the B2CL threshold and null. */
    @Provide
    Arbitrary<BigDecimal> anyInvoiceValue() {
        Arbitrary<BigDecimal> values = Arbitraries.integers().between(0, 60_000_000)
                .map(paise -> new BigDecimal(paise).movePointLeft(2));
        return values.injectNull(0.1);
    }
}
