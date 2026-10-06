package com.shifa.oms.label;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the long-order label bug: an order with many line items
 * (7-8+) rendered with a BLANK item box because the label block was wrapped in a
 * {@code setFixedHeight} quadrant that HARD-CLIPS overflow — so a tall item table
 * was clipped away entirely.
 *
 * <p>The fix makes the quadrant use a MINIMUM height (grow instead of clip) and
 * lets the outer grid split an oversized row across pages, so the full item list
 * always renders. This test proves it objectively by rendering the PDF and
 * extracting its text: every product name (and the Sub-total/Total) must be
 * present in the output.
 */
class LabelLongOrderRenderTest {

    private final LabelPdfRenderer renderer = new LabelPdfRenderer(new BarcodeGenerator());

    @Test
    void eightItemOrderRendersEveryProductNameAndTotals() throws Exception {
        List<String> names = List.of(
                "Semen Booster", "Power Gold tablets", "Immuno booster",
                "Maqwi Mumsik Majun", "Shahi Tila Oil", "Hakimi Sir Kit",
                "Immuno booster", "Ashwagandha Churna");

        InternalLabelContent content = longOrderContent("SHR-20261005-LXM3", names,
                // Partially paid → COD applies, mirroring the reported order.
                true, new BigDecimal("8590.00"), new BigDecimal("21290.00"));

        byte[] pdf = renderer.render(content, null);
        String text = extractText(pdf);

        // Every product name must appear in the rendered label (not clipped away).
        for (String name : names) {
            assertThat(text)
                    .as("product '%s' must be present on the label", name)
                    .contains(name);
        }
        // The totals must render too.
        assertThat(text).contains("Sub-total");
        assertThat(text).contains("TOTAL");
        // And the order code / COD still render.
        assertThat(text).contains("SHR-20261005-LXM3");
    }

    @Test
    void veryLongOrderStillRendersAllItems() throws Exception {
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= 18; i++) {
            names.add("Product Number " + i);
        }
        InternalLabelContent content = longOrderContent("SHR-LONG-0001", names,
                false, null, new BigDecimal("50000.00"));

        byte[] pdf = renderer.render(content, null);
        String text = extractText(pdf);

        for (String name : names) {
            assertThat(text)
                    .as("product '%s' must be present on the 18-item label", name)
                    .contains(name);
        }
    }

    // --- helpers ------------------------------------------------------------

    private InternalLabelContent longOrderContent(String orderCode, List<String> names,
                                                  boolean codApplicable, BigDecimal cod,
                                                  BigDecimal total) {
        List<InternalLabelContent.LabelLineItem> items = new ArrayList<>();
        for (String n : names) {
            items.add(new InternalLabelContent.LabelLineItem(n, 2, new BigDecimal("2679.99")));
        }
        return new InternalLabelContent(
                orderCode,
                "QUIKSHIPX",
                "20736022116333",
                "VISHNU MULVI",
                "9765662211",
                "Azara beach house Candolim house no/449E/ pin code/403515/ Casablanca beach road /noth goa",
                "Goa",
                "Goa",
                "403515",
                items,
                codApplicable,
                cod,
                "05/10/2026",
                total,
                codApplicable ? "PARTIALLY PAID" : "PREPAID",
                "Shifa Herbal Remedies Pvt Ltd.",
                "193, opp. Sai Mandir 452014, Veer Sawarkar Nagar, Khatiwala Tank, Indore, Madhya Pradesh",
                "23ABCDE1234F1Z5",
                "193, opp. Sai Mandir 452014, Veer Sawarkar Nagar, Khatiwala Tank, Indore, Madhya Pradesh",
                null);
    }

    private String extractText(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        try {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            StringBuilder sb = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                sb.append(extractor.getTextFromPage(page)).append('\n');
            }
            return sb.toString();
        } finally {
            reader.close();
        }
    }
}
