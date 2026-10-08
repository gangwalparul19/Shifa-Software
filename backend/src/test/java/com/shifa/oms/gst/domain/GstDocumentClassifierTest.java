package com.shifa.oms.gst.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Truth-table unit tests for {@link GstDocumentClassifier#classify} (GST filing compliance,
 * Req 1.4–1.6). Worked examples pin the three supply categories:
 *
 * <ul>
 *   <li>B2B — a valid buyer GSTIN is present (Req 1.4);</li>
 *   <li>B2CL — no GSTIN, inter-state, invoice value strictly &gt; ₹2,50,000 (Req 1.5);</li>
 *   <li>B2CS — everything else: intra-state, low-value inter-state, exactly ₹2,50,000 (Req 1.6).</li>
 * </ul>
 */
class GstDocumentClassifierTest {

    /** A well-formed sample GSTIN (27 = Maharashtra, valid 15-char format). */
    private static final String VALID_GSTIN = "27AABCU9603R1ZM";

    private static BigDecimal rs(String amount) {
        return new BigDecimal(amount);
    }

    // ---- B2B: a valid buyer GSTIN wins regardless of supply type / value (Req 1.4) ----

    @Test
    void validGstinIntraStateIsB2b() {
        assertThat(GstDocumentClassifier.classify(VALID_GSTIN, SupplyType.INTRA, rs("1000.00")))
                .isEqualTo(DocumentCategory.B2B);
    }

    @Test
    void validGstinInterStateHighValueIsB2b() {
        // Even a high-value inter-state supply is B2B when the buyer is registered.
        assertThat(GstDocumentClassifier.classify(VALID_GSTIN, SupplyType.INTER, rs("500000.00")))
                .isEqualTo(DocumentCategory.B2B);
    }

    // ---- B2CL: no GSTIN + INTER + value strictly greater than ₹2,50,000 (Req 1.5) ----

    @Test
    void noGstinInterStateAboveThresholdIsB2cl() {
        assertThat(GstDocumentClassifier.classify(null, SupplyType.INTER, rs("250001.00")))
                .isEqualTo(DocumentCategory.B2CL);
    }

    @Test
    void noGstinInterStateJustAboveThresholdIsB2cl() {
        // ₹2,50,000.01 crosses the strict threshold.
        assertThat(GstDocumentClassifier.classify(null, SupplyType.INTER, rs("250000.01")))
                .isEqualTo(DocumentCategory.B2CL);
    }

    @Test
    void blankGstinInterStateAboveThresholdIsB2cl() {
        // A blank GSTIN is treated as unregistered.
        assertThat(GstDocumentClassifier.classify("   ", SupplyType.INTER, rs("300000.00")))
                .isEqualTo(DocumentCategory.B2CL);
    }

    // ---- B2CS: all other cases (Req 1.6) ----

    @Test
    void noGstinIntraStateIsB2cs() {
        // Intra-state is never B2CL no matter how large the value.
        assertThat(GstDocumentClassifier.classify(null, SupplyType.INTRA, rs("300000.00")))
                .isEqualTo(DocumentCategory.B2CS);
    }

    @Test
    void noGstinInterStateExactlyAtThresholdIsB2cs() {
        // Exactly ₹2,50,000 is NOT above the strict threshold, so it stays B2CS.
        assertThat(GstDocumentClassifier.classify(null, SupplyType.INTER, rs("250000.00")))
                .isEqualTo(DocumentCategory.B2CS);
    }

    @Test
    void noGstinInterStateLowValueIsB2cs() {
        assertThat(GstDocumentClassifier.classify(null, SupplyType.INTER, rs("1000.00")))
                .isEqualTo(DocumentCategory.B2CS);
    }

    @Test
    void invalidGstinFallsThroughToRuleBasedClassification() {
        // An invalid GSTIN is not B2B; a low-value inter-state supply then classifies as B2CS.
        assertThat(GstDocumentClassifier.classify("NOTAGSTIN", SupplyType.INTER, rs("1000.00")))
                .isEqualTo(DocumentCategory.B2CS);
    }
}
