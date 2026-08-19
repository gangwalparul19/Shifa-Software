package com.shifa.oms.gst.domain;

import com.shifa.oms.gst.domain.GstEngine.GstComputation;
import com.shifa.oms.gst.domain.GstEngine.GstLine;
import com.shifa.oms.gst.domain.GstEngine.GstOrder;
import com.shifa.oms.gst.domain.GstEngine.TaxSplit;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the pure GST engine (CA GST dashboard, Reqs 3, 4, 5). */
class GstEngineTest {

    private static GstLine line(String hsn, String name, String rate, int qty, String total) {
        return new GstLine(hsn, name, rate == null ? null : new BigDecimal(rate), qty, new BigDecimal(total));
    }

    @Test
    void intraStateSplitsIntoEqualCgstSgst() {
        // 1050 inclusive @5% -> taxable 1000, tax 50 -> cgst 25, sgst 25.
        TaxSplit s = GstEngine.splitLine(line("3004", "P", "5", 1, "1050.00"), SupplyType.INTRA);
        assertThat(s.cgst()).isEqualByComparingTo("25.00");
        assertThat(s.sgst()).isEqualByComparingTo("25.00");
        assertThat(s.igst()).isEqualByComparingTo("0.00");
        assertThat(s.taxable()).isEqualByComparingTo("1000.00");
        assertThat(s.totalTax()).isEqualByComparingTo("50.00");
    }

    @Test
    void interStateRecordsFullIgst() {
        TaxSplit s = GstEngine.splitLine(line("3004", "P", "5", 1, "1050.00"), SupplyType.INTER);
        assertThat(s.igst()).isEqualByComparingTo("50.00");
        assertThat(s.cgst()).isEqualByComparingTo("0.00");
        assertThat(s.sgst()).isEqualByComparingTo("0.00");
        assertThat(s.taxable()).isEqualByComparingTo("1000.00");
    }

    @Test
    void zeroRateHasNoTax() {
        TaxSplit s = GstEngine.splitLine(line("409", "Honey", "0", 1, "600.00"), SupplyType.INTRA);
        assertThat(s.totalTax()).isEqualByComparingTo("0.00");
        assertThat(s.taxable()).isEqualByComparingTo("600.00");
    }

    @Test
    void classifyUsesSellerStateElseInterWhenBlank() {
        assertThat(GstEngine.classify("Madhya Pradesh", "madhya pradesh")).isEqualTo(SupplyType.INTRA);
        assertThat(GstEngine.classify("Maharashtra", "Madhya Pradesh")).isEqualTo(SupplyType.INTER);
        assertThat(GstEngine.classify("Maharashtra", "")).isEqualTo(SupplyType.INTER);
    }

    @Test
    void computeAggregatesAndReconcilesAcrossSummaries() {
        String seller = "Madhya Pradesh";
        List<GstOrder> orders = List.of(
                new GstOrder(1L, "Madhya Pradesh", LocalDate.now(),
                        List.of(line("3004", "PetKam", "5", 2, "1050.00"))),   // intra, 2x -> total 2100? no
                new GstOrder(2L, "Maharashtra", LocalDate.now(),
                        List.of(line("3304", "Face Cream", "18", 1, "1180.00")))); // inter @18 -> igst 180
        GstComputation c = GstEngine.compute(orders, seller);

        BigDecimal rateTaxable = c.rateWise().stream()
                .map(GstEngine.RateWiseRow::taxable).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal hsnTaxable = c.hsn().stream()
                .map(GstEngine.HsnRow::taxable).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal stateTaxable = c.stateWise().stream()
                .map(GstEngine.StateWiseRow::taxable).reduce(BigDecimal.ZERO, BigDecimal::add);

        // Property 3: all summaries reconcile to the same taxable + tax totals.
        assertThat(rateTaxable).isEqualByComparingTo(c.summary().taxableOutward());
        assertThat(hsnTaxable).isEqualByComparingTo(c.summary().taxableOutward());
        assertThat(stateTaxable).isEqualByComparingTo(c.summary().taxableOutward());
        // Intra order tax (CGST+SGST) + inter order IGST present.
        assertThat(c.summary().outputIgst()).isEqualByComparingTo("180.00");
        assertThat(c.summary().outputCgst()).isEqualByComparingTo(c.summary().outputSgst());
        assertThat(c.summary().outputTotal())
                .isEqualByComparingTo(c.summary().outputCgst()
                        .add(c.summary().outputSgst()).add(c.summary().outputIgst()));
    }
}
