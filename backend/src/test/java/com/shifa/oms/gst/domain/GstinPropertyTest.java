package com.shifa.oms.gst.domain;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for {@link Gstin} format validation (GST filing compliance).
 *
 * <p>Feature: gst-filing-compliance, Property 1: GSTIN format validation.
 *
 * <p>For any string constructed to the canonical GSTIN pattern (2 digits, 5 letters,
 * 4 digits, 1 letter, 1 entity char, the fixed letter {@code Z}, 1 checksum char),
 * {@link Gstin#isValid} returns true and {@link Gstin#stateCode} exposes the first 2
 * chars; for any string that violates the length or any segment rule (including
 * null/blank), {@link Gstin#isValid} returns false.
 */
class GstinPropertyTest {

    private static final String DIGITS = "0123456789";
    private static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String ALNUM = DIGITS + LETTERS;

    // Feature: gst-filing-compliance, Property 1: GSTIN format validation
    // **Validates: Requirements 1.2**
    @Property(tries = 200)
    void canonicalGstinsAreValidAndExposeStateCode(@ForAll("canonicalGstins") String gstin) {
        assertThat(Gstin.isValid(gstin))
                .as("canonical GSTIN %s must be valid", gstin)
                .isTrue();
        // stateCode returns the first 2 chars for a valid GSTIN.
        assertThat(Gstin.stateCode(gstin)).isEqualTo(gstin.substring(0, 2));
    }

    // Feature: gst-filing-compliance, Property 1: GSTIN format validation
    // **Validates: Requirements 1.2**
    @Property(tries = 200)
    void malformedGstinsAreRejected(@ForAll("malformedGstins") String gstin) {
        assertThat(Gstin.isValid(gstin))
                .as("malformed GSTIN [%s] must be rejected", gstin)
                .isFalse();
        // A malformed GSTIN has no derivable state code.
        assertThat(Gstin.stateCode(gstin)).isNull();
    }

    /**
     * Canonical valid GSTINs: 2 digits + 5 letters + 4 digits + 1 letter + 1 alnum
     * entity char + the fixed letter {@code Z} + 1 alnum checksum char (15 chars).
     */
    @Provide
    Arbitrary<String> canonicalGstins() {
        return Combinators.combine(
                        fixedLength(DIGITS, 2),   // state code
                        fixedLength(LETTERS, 5),  // PAN letters
                        fixedLength(DIGITS, 4),   // PAN digits
                        fixedLength(LETTERS, 1),  // PAN last letter
                        fixedLength(ALNUM, 1),    // entity char
                        fixedLength(ALNUM, 1))    // checksum char
                .as((state, panLetters, panDigits, panLast, entity, checksum) ->
                        state + panLetters + panDigits + panLast + entity + "Z" + checksum);
    }

    /**
     * Malformed strings guaranteed to violate the GSTIN format: null, blank, wrong
     * length, and wrong segment types (a letter in a digit slot, a digit in a letter
     * slot, and a non-{@code Z} in the fixed position).
     */
    @Provide
    Arbitrary<String> malformedGstins() {
        return Combinators.combine(canonicalGstins(), Arbitraries.integers().between(0, 7))
                .as(GstinPropertyTest::mutate);
    }

    private static Arbitrary<String> fixedLength(String chars, int length) {
        return Arbitraries.strings().withChars(chars.toCharArray()).ofLength(length);
    }

    private static String mutate(String valid, int kind) {
        return switch (kind) {
            case 0 -> null;                                              // null
            case 1 -> "";                                               // empty
            case 2 -> "   ";                                            // blank
            case 3 -> valid.substring(0, 14);                          // too short (14 chars)
            case 4 -> valid + "9";                                      // too long (16 chars)
            case 5 -> "A" + valid.substring(1);                        // letter in digit slot (pos 0)
            case 6 -> valid.substring(0, 13) + "0" + valid.substring(14); // non-Z in fixed slot (pos 13)
            default -> "5" + valid.substring(1, 2) + "5" + valid.substring(3); // digit in letter slot (pos 2)
        };
    }
}
