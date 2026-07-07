package com.shifa.oms.invoice;

import com.lowagie.text.pdf.BaseFont;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the ₹ (U+20B9) solution: a Unicode TrueType font is bundled on the
 * classpath at {@code /fonts/InvoiceUnicode.ttf} and, when loaded as an embedded
 * IDENTITY_H font, contains the rupee glyph. This guards the embedded-font code
 * path used by {@link InvoicePdfRenderer} so ₹ renders correctly in invoices.
 *
 * <p>If a bundled font were ever removed, the renderer falls back to the "Rs."
 * prefix; this test ensures the preferred ₹ path is actually available.
 */
class InvoiceRupeeFontTest {

    private static final char RUPEE = '\u20B9';

    @Test
    void bundledUnicodeFontIsPresentOnClasspath() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/fonts/InvoiceUnicode.ttf")) {
            assertThat(in).as("bundled invoice Unicode font must be on the classpath").isNotNull();
            assertThat(in.readAllBytes().length).isGreaterThan(0);
        }
    }

    @Test
    void bundledFontContainsRupeeGlyph() throws Exception {
        byte[] bytes;
        try (InputStream in = getClass().getResourceAsStream("/fonts/InvoiceUnicode.ttf")) {
            assertThat(in).isNotNull();
            bytes = in.readAllBytes();
        }
        BaseFont baseFont = BaseFont.createFont("InvoiceUnicode.ttf", BaseFont.IDENTITY_H,
                BaseFont.EMBEDDED, BaseFont.CACHED, bytes, null);
        assertThat(baseFont.charExists(RUPEE))
                .as("bundled font must include the ₹ glyph (U+20B9)")
                .isTrue();
    }
}
