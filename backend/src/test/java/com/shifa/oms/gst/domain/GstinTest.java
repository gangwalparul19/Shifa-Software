package com.shifa.oms.gst.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link Gstin} format validation and state-code extraction
 * (GST filing compliance, Reqs 1.1, 1.3).
 */
class GstinTest {

    // A real-shaped 15-char GSTIN: 27 (state) AAPFU (PAN letters) 0939 (PAN digits)
    // F (PAN letter) 1 (entity) Z (fixed) V (checksum).
    private static final String VALID_GSTIN = "27AAPFU0939F1ZV";

    @Test
    void acceptsRealSampleGstin() {
        assertThat(Gstin.isValid(VALID_GSTIN)).isTrue();
        // A second valid example with a digit checksum character.
        assertThat(Gstin.isValid("29ABCDE1234F1Z5")).isTrue();
    }

    @Test
    void acceptsValidGstinWithSurroundingWhitespace() {
        assertThat(Gstin.isValid("  " + VALID_GSTIN + "  ")).isTrue();
    }

    @Test
    void rejectsNullBlankAndEmpty() {
        assertThat(Gstin.isValid(null)).isFalse();
        assertThat(Gstin.isValid("")).isFalse();
        assertThat(Gstin.isValid("   ")).isFalse();
    }

    @Test
    void rejectsWrongLength() {
        // 14 chars (one short) and 16 chars (one long).
        assertThat(Gstin.isValid("27AAPFU0939F1Z")).isFalse();
        assertThat(Gstin.isValid("27AAPFU0939F1ZVX")).isFalse();
    }

    @Test
    void rejectsWrongSegments() {
        // State code not digits.
        assertThat(Gstin.isValid("AB AAPFU0939F1ZV".replace(" ", ""))).isFalse();
        assertThat(Gstin.isValid("2AAAPFU0939F1ZV")).isFalse();
        // PAN letters slot contains a digit.
        assertThat(Gstin.isValid("27AAP1U0939F1ZV")).isFalse();
        // PAN digit slot contains a letter.
        assertThat(Gstin.isValid("27AAPFU093AF1ZV")).isFalse();
        // 14th char must be the fixed letter 'Z'.
        assertThat(Gstin.isValid("27AAPFU0939F1XV")).isFalse();
        // Lowercase letters are not accepted.
        assertThat(Gstin.isValid("27aapfu0939f1zv")).isFalse();
    }

    @Test
    void stateCodeReturnsFirstTwoDigitsOfValidGstin() {
        assertThat(Gstin.stateCode(VALID_GSTIN)).isEqualTo("27");
        assertThat(Gstin.stateCode("29ABCDE1234F1Z5")).isEqualTo("29");
        // Whitespace is trimmed before extracting the state code.
        assertThat(Gstin.stateCode("  " + VALID_GSTIN + "  ")).isEqualTo("27");
    }

    @Test
    void stateCodeReturnsNullForInvalidInput() {
        assertThat(Gstin.stateCode(null)).isNull();
        assertThat(Gstin.stateCode("")).isNull();
        assertThat(Gstin.stateCode("not-a-gstin")).isNull();
        assertThat(Gstin.stateCode("27AAPFU0939F1Z")).isNull();
    }
}
