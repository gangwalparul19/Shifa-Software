package com.shifa.oms.gst.domain;

import com.shifa.oms.gst.domain.GstEngine.GstComputation;
import com.shifa.oms.gst.domain.GstEngine.GstLine;
import com.shifa.oms.gst.domain.GstEngine.GstOrder;
import com.shifa.oms.gst.domain.GstEngine.TaxSplit;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property tests for {@link GstEngine} (CA GST dashboard Correctness Properties
 * 1-3): inclusive extraction bound, split integrity, cross-summary
 * reconciliation.
 */
class GstEnginePropertyTest {

    private static final String[] RATES = {"0", "5", "18"};
    private static final String[] STATES = {"Madhya Pradesh", "Maharashtra", "Gujarat", "Delhi"};

    @Property(tries = 400)
    void splitIsBoundedAndConsistent(
            @ForAll @IntRange(min = 1, max = 3) int rateIdx,
            @ForAll @IntRange(min = 1, max = 500000) int paise,
            @ForAll boolean intra) {
        BigDecimal total = new BigDecimal(paise).movePointLeft(2);
        BigDecimal rate = new BigDecimal(RATES[rateIdx % RATES.length]);
        TaxSplit s = GstEngine.splitLine(
                new GstLine("3004", "P", rate, 1, total),
                intra ? SupplyType.INTRA : SupplyType.INTER);

        // Property 1: 0 <= tax <= total and taxable = total - tax.
        assertThat(s.totalTax()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(s.totalTax()).isLessThanOrEqualTo(total);
        assertThat(s.taxable()).isEqualByComparingTo(total.subtract(s.totalTax()));

        // Property 2: split integrity.
        if (intra) {
            assertThat(s.cgst()).isEqualByComparingTo(s.sgst());
            assertThat(s.cgst().add(s.sgst())).isEqualByComparingTo(s.totalTax());
            assertThat(s.igst()).isEqualByComparingTo(BigDecimal.ZERO);
        } else {
            assertThat(s.igst()).isEqualByComparingTo(s.totalTax());
            assertThat(s.cgst()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(s.sgst()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Property(tries = 300)
    void summariesReconcile(
            @ForAll @Size(min = 1, max = 8) List<@IntRange(min = 1, max = 300000) Integer> lineTotals) {
        String seller = "Madhya Pradesh";
        List<GstOrder> orders = new ArrayList<>();
        for (int i = 0; i < lineTotals.size(); i++) {
            BigDecimal total = new BigDecimal(lineTotals.get(i)).movePointLeft(2);
            BigDecimal rate = new BigDecimal(RATES[i % RATES.length]);
            String state = STATES[i % STATES.length];
            orders.add(new GstOrder((long) i, state, LocalDate.now(),
                    List.of(new GstLine("300" + (i % 5), "P" + i, rate, 1 + (i % 3), total))));
        }
        GstComputation c = GstEngine.compute(orders, seller);

        BigDecimal rateTaxable = sum(c.rateWise().stream().map(GstEngine.RateWiseRow::taxable).toList());
        BigDecimal hsnTaxable = sum(c.hsn().stream().map(GstEngine.HsnRow::taxable).toList());
        BigDecimal stateTaxable = sum(c.stateWise().stream().map(GstEngine.StateWiseRow::taxable).toList());

        // Property 3: taxable reconciles across all summaries and the 3B total.
        assertThat(rateTaxable).isEqualByComparingTo(c.summary().taxableOutward());
        assertThat(hsnTaxable).isEqualByComparingTo(c.summary().taxableOutward());
        assertThat(stateTaxable).isEqualByComparingTo(c.summary().taxableOutward());

        BigDecimal rateTax = sum(c.rateWise().stream()
                .map(r -> r.cgst().add(r.sgst()).add(r.igst())).toList());
        assertThat(rateTax).isEqualByComparingTo(c.summary().outputTotal());
    }

    private static BigDecimal sum(List<BigDecimal> xs) {
        return xs.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
