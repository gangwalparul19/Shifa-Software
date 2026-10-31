package com.shifa.oms.order;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UtrNormalizerTest {

    @Test
    void removesAllJavaWhitespaceAndUppercasesProviderReference() {
        assertThat(UtrNormalizer.normalize("  ab 12\tcd\n ")).isEqualTo("AB12CD");
    }

    @Test
    void mapsNullAndWhitespaceOnlyValuesToNull() {
        assertThat(UtrNormalizer.normalize(null)).isNull();
        assertThat(UtrNormalizer.normalize(" \t\n ")).isNull();
    }
}
