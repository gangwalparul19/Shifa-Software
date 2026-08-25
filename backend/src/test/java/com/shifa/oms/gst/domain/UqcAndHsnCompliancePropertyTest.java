package com.shifa.oms.gst.domain;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property tests for the GST Filing Compliance UQC resolver and HSN-length compliance rules.
 *
 * <p>Feature: gst-filing-compliance.
 *
 * <ul>
 *   <li><b>Property 10 — UQC resolution</b> (Validates: Requirements 3.2): {@link Uqc#resolve}
 *       returns a non-blank standard UQC — the canonical code when the input is a recognised UQC
 *       (case-insensitively), and {@code NOS} when the input is null, blank, or unknown.</li>
 *   <li><b>Property 11 — HSN length enforcement and flagging</b> (Validates: Requirements 3.3, 3.4):
 *       {@link HsnCompliance#minLength} is 6 when turnover exceeds ₹5 crore and 4 otherwise (null →
 *       4), and {@link HsnCompliance#isCompliant} is true iff the HSN has at least {@code minLength}
 *       digits.</li>
 * </ul>
 */
class UqcAndHsnCompliancePropertyTest {

    // ---------------------------------------------------------------------------------------------
    // Property 10: UQC resolution (Validates: Requirements 3.2)
    // ---------------------------------------------------------------------------------------------

    /**
     * A recognised UQC code (in any letter case) resolves to its canonical form from
     * {@link Uqc#STANDARD}, and the result is always a non-blank recognised code.
     */
    @Property(tries = 200)
    void recognisedCodeResolvesToCanonicalFormRegardlessOfCase(
            @ForAll("standardCodes") String canonical,
            @ForAll @IntRange(min = 0, max = 2) int caseMode,
            @ForAll("surroundingSpace") String pad) {
        String input = pad + applyCase(canonical, caseMode) + pad;

        String resolved = Uqc.resolve(input);

        assertThat(resolved).isEqualTo(canonical);
        assertThat(resolved).isNotBlank();
        assertThat(Uqc.STANDARD).contains(resolved);
    }

    /**
     * A null, blank, or unrecognised UQC input resolves to the GST-standard default {@code NOS},
     * which is itself a non-blank recognised code.
     */
    @Property(tries = 200)
    void nullBlankOrUnknownResolvesToDefaultNos(@ForAll("nonStandardOrBlank") String input) {
        String resolved = Uqc.resolve(input);

        assertThat(resolved).isEqualTo(Uqc.DEFAULT);
        assertThat(resolved).isEqualTo("NOS");
        assertThat(resolved).isNotBlank();
        assertThat(Uqc.STANDARD).contains(resolved);
    }

    /**
     * Totality: for any string whatsoever, {@code resolve} returns a non-blank recognised UQC and
     * never throws.
     */
    @Property(tries = 200)
    void resolveAlwaysReturnsNonBlankStandardCode(@ForAll("anyUqcInput") String input) {
        String resolved = Uqc.resolve(input);

        assertThat(resolved).isNotBlank();
        assertThat(Uqc.STANDARD).contains(resolved);
    }

    // ---------------------------------------------------------------------------------------------
    // Property 11: HSN length enforcement and flagging (Validates: Requirements 3.3, 3.4)
    // ---------------------------------------------------------------------------------------------

    /**
     * The enforced minimum HSN length is 6 iff the aggregate turnover is strictly greater than
     * ₹5 crore, else 4.
     */
    @Property(tries = 300)
    void minLengthIsSixOnlyAboveFiveCroreTurnover(@ForAll("turnovers") BigDecimal turnover) {
        int min = HsnCompliance.minLength(turnover);

        boolean aboveThreshold =
                turnover != null && turnover.compareTo(HsnCompliance.SIX_DIGIT_TURNOVER) > 0;
        assertThat(min).isEqualTo(aboveThreshold ? 6 : 4);
    }

    /** A null turnover defaults to the 4-digit rule, and exactly ₹5 crore is not "above". */
    @Property(tries = 1)
    void minLengthBoundaryAndNull() {
        assertThat(HsnCompliance.minLength(null)).isEqualTo(4);
        assertThat(HsnCompliance.minLength(HsnCompliance.SIX_DIGIT_TURNOVER)).isEqualTo(4);
        assertThat(HsnCompliance.minLength(
                HsnCompliance.SIX_DIGIT_TURNOVER.add(new BigDecimal("0.01")))).isEqualTo(6);
    }

    /**
     * An HSN code is compliant iff its digit count is at least the enforced minimum length.
     */
    @Property(tries = 300)
    void isCompliantIffDigitCountMeetsMinimum(
            @ForAll("hsnCodes") String hsn,
            @ForAll @IntRange(min = 1, max = 8) int minLength) {
        boolean compliant = HsnCompliance.isCompliant(hsn, minLength);

        assertThat(compliant).isEqualTo(digitCount(hsn) >= minLength);
    }

    /** A null HSN is never compliant with any positive minimum length. */
    @Property(tries = 50)
    void nullHsnIsNeverCompliant(@ForAll @IntRange(min = 1, max = 8) int minLength) {
        assertThat(HsnCompliance.isCompliant(null, minLength)).isFalse();
    }

    // ---------------------------------------------------------------------------------------------
    // Generators
    // ---------------------------------------------------------------------------------------------

    @Provide
    Arbitrary<String> standardCodes() {
        return Arbitraries.of(List.copyOf(Uqc.STANDARD));
    }

    @Provide
    Arbitrary<String> surroundingSpace() {
        return Arbitraries.of("", " ", "  ", "\t");
    }

    /** Strings that are null, blank, or not a recognised UQC (case-insensitively). */
    @Provide
    Arbitrary<String> nonStandardOrBlank() {
        Arbitrary<String> blanks = Arbitraries.of(null, "", " ", "   ", "\t", "\n");
        Arbitrary<String> unknown = Arbitraries.strings()
                .withCharRange('a', 'z').ofMinLength(1).ofMaxLength(12)
                .filter(s -> !isRecognised(s));
        Arbitrary<String> unknownUpper = Arbitraries.strings()
                .withCharRange('A', 'Z').ofMinLength(1).ofMaxLength(12)
                .filter(s -> !isRecognised(s));
        Arbitrary<String> mixed = Arbitraries.strings()
                .ofMinLength(1).ofMaxLength(6)
                .filter(s -> !isRecognised(s));
        return Arbitraries.oneOf(blanks, unknown, unknownUpper, mixed);
    }

    /** Any input at all: recognised codes (any case, padded), blanks, and arbitrary junk. */
    @Provide
    Arbitrary<String> anyUqcInput() {
        Arbitrary<String> recognised = Combinators.combine(
                        standardCodes(),
                        Arbitraries.integers().between(0, 2),
                        surroundingSpace())
                .as((code, mode, pad) -> pad + applyCase(code, mode) + pad);
        return Arbitraries.oneOf(recognised, nonStandardOrBlank(), anyString());
    }

    private Arbitrary<String> anyString() {
        return Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.strings().ofMaxLength(15));
    }

    /**
     * HSN codes across a range of digit counts, including non-digit characters and separators so the
     * digit-count semantics are exercised (e.g. "12-34" has 4 digits).
     */
    @Provide
    Arbitrary<String> hsnCodes() {
        Arbitrary<String> pureDigits = Arbitraries.strings()
                .withCharRange('0', '9').ofMinLength(0).ofMaxLength(10);
        Arbitrary<String> withSeparators = Arbitraries.strings()
                .withChars('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '-', ' ', '.', 'A')
                .ofMinLength(0).ofMaxLength(12);
        Arbitrary<String> noDigits = Arbitraries.strings()
                .withCharRange('A', 'Z').ofMinLength(0).ofMaxLength(6);
        return Arbitraries.oneOf(pureDigits, withSeparators, noDigits);
    }

    /** Turnovers straddling the ₹5 crore threshold, including null and the exact boundary. */
    @Provide
    Arbitrary<BigDecimal> turnovers() {
        Arbitrary<BigDecimal> around = Arbitraries.longs().between(0L, 200_000_000L)
                .map(BigDecimal::valueOf);
        Arbitrary<BigDecimal> boundary = Arbitraries.of(
                HsnCompliance.SIX_DIGIT_TURNOVER,
                HsnCompliance.SIX_DIGIT_TURNOVER.subtract(BigDecimal.ONE),
                HsnCompliance.SIX_DIGIT_TURNOVER.add(BigDecimal.ONE),
                HsnCompliance.SIX_DIGIT_TURNOVER.add(new BigDecimal("0.01")),
                BigDecimal.ZERO);
        Arbitrary<BigDecimal> nulls = Arbitraries.just(null);
        return Arbitraries.oneOf(around, boundary, nulls);
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private static String applyCase(String code, int mode) {
        return switch (mode) {
            case 0 -> code.toUpperCase(Locale.ROOT);
            case 1 -> code.toLowerCase(Locale.ROOT);
            default -> code; // canonical (mixed) form as stored
        };
    }

    private static boolean isRecognised(String s) {
        String trimmed = s == null ? "" : s.trim();
        for (String code : Uqc.STANDARD) {
            if (code.equalsIgnoreCase(trimmed)) {
                return true;
            }
        }
        return false;
    }

    private static int digitCount(String s) {
        if (s == null) {
            return 0;
        }
        int digits = 0;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) {
                digits++;
            }
        }
        return digits;
    }
}
