package com.shifa.oms.insights.domain;

import com.shifa.oms.lead.LeadReportAggregator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The pure statistical insight engine (design &sect;Pure domain, &sect;Scoring
 * detail, &sect;Reorder detail) — no persistence, no web, no Spring, so the
 * jqwik property tests exercise it directly over generated inputs (design
 * &sect;Correctness Properties). It holds no state; every method is deterministic
 * and side-effect-free, given the same inputs it returns an equal
 * {@link Insight} list in the same order (Property 7), which is what underpins
 * idempotent persistence.
 *
 * <p>Each insight family has one public method taking its projection(s), the
 * {@link InsightThresholds}, and the {@code today} date; {@link #compute} runs
 * them all in a fixed order (sales, reorder, RTO risk, courier, return-rate, COD,
 * lead-source), sorting collection inputs by their id so the output ordering is
 * stable. GLOBAL-scope insights carry the sentinel {@code scopeRefId = 0}.
 */
public class InsightEngine {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    /** The {@code scopeRefId} sentinel for GLOBAL-scope insights (keeps the natural key unique). */
    public static final long GLOBAL_REF = 0L;

    /** RTO scoring weights (sum to 1) and the COD normalization cap (design &sect;Scoring detail). */
    private static final double W_STATE = 0.5;
    private static final double W_COD = 0.3;
    private static final double W_PRIOR = 0.2;
    private static final double COD_CAP = 3000.0;
    private static final double PRIOR_CAP = 3.0;

    /** The RTO-risk severity boundary: at or above this score an order is DANGER (else WARNING). */
    private static final int RTO_DANGER_SCORE = 80;

    // --- Sales anomaly (Req 3.1–3.4) ----------------------------------------

    /**
     * A {@code SALES_ANOMALY} (GLOBAL) when the current window's revenue deviates
     * from the previous equal-length window by more than
     * {@link InsightThresholds#salesAnomalyPct()} (design &sect;Scoring detail):
     * a dip beyond threshold is WARNING, a dip beyond twice the threshold DANGER,
     * a spike INFO. When the previous window had zero revenue and the current is
     * positive it is treated as a spike (no divide-by-zero); an empty or
     * within-threshold window yields nothing.
     */
    public List<Insight> salesAnomaly(SalesWindow sales, InsightThresholds t, LocalDate today) {
        if (sales == null) {
            return List.of();
        }
        BigDecimal current = sales.currentTotal() == null ? BigDecimal.ZERO : sales.currentTotal();
        BigDecimal previous = sales.previousTotal() == null ? BigDecimal.ZERO : sales.previousTotal();

        boolean spikeFromZero = previous.signum() == 0 && current.signum() > 0;
        BigDecimal pctChange = null;
        boolean dip;
        if (spikeFromZero) {
            dip = false;
        } else if (previous.signum() == 0) {
            return List.of();               // no prior and no current sales — nothing to flag
        } else {
            pctChange = current.subtract(previous)
                    .multiply(HUNDRED)
                    .divide(previous, 2, RoundingMode.HALF_UP);
            if (pctChange.abs().compareTo(t.salesAnomalyPct()) <= 0) {
                return List.of();           // within threshold
            }
            dip = pctChange.signum() < 0;
        }

        InsightSeverity severity;
        String direction;
        if (dip) {
            BigDecimal twice = t.salesAnomalyPct().multiply(TWO);
            severity = pctChange.abs().compareTo(twice) > 0
                    ? InsightSeverity.DANGER : InsightSeverity.WARNING;
            direction = "dipped";
        } else {
            severity = InsightSeverity.INFO;
            direction = "spiked";
        }

        String pctText = pctChange == null ? "from zero" : pctChange.abs().toPlainString() + "%";
        String title = "Sales " + direction + " " + pctText;
        String detail = "Current-window revenue " + current.toPlainString()
                + " vs previous-window revenue " + previous.toPlainString()
                + " (" + direction + " " + pctText + ").";
        return List.of(new Insight(InsightType.SALES_ANOMALY, InsightScope.GLOBAL, GLOBAL_REF,
                "All sales", severity, title, detail, pctChange, today));
    }

    // --- Low-stock reorder (Req 4.1–4.4) ------------------------------------

    /**
     * A {@code LOW_STOCK_REORDER} (PRODUCT) per product whose projected
     * days-of-cover ({@code onHand / avgDaily}) is below
     * {@link InsightThresholds#reorderCoverDays()} (design &sect;Reorder detail),
     * carrying a non-negative suggested reorder quantity and the stock-out ETA in
     * days. Products with no consumption ({@code avgDaily == 0}) are skipped (no
     * divide-by-zero, no false alarm). Products are processed in ascending
     * {@code productId} order for deterministic output.
     */
    public List<Insight> reorder(List<ProductConsumption> products, InsightThresholds t, LocalDate today) {
        List<ProductConsumption> sorted = new ArrayList<>(products == null ? List.of() : products);
        sorted.sort(Comparator.comparing(ProductConsumption::productId,
                Comparator.nullsLast(Comparator.naturalOrder())));

        List<Insight> out = new ArrayList<>();
        for (ProductConsumption p : sorted) {
            if (p.lookbackDays() <= 0) {
                continue;                    // cannot compute a daily rate — skip
            }
            double avgDaily = (double) p.unitsSoldInWindow() / p.lookbackDays();
            if (avgDaily <= 0) {
                continue;                    // no consumption — no reorder insight (Req 4.3)
            }
            double coverDays = p.onHand() / avgDaily;
            if (coverDays >= t.reorderCoverDays()) {
                continue;                    // enough cover
            }
            long target = (long) Math.ceil(avgDaily * t.reorderCoverDays());
            long suggestedQty = Math.max(0L, target - p.onHand());
            long etaDays = (long) Math.floor(coverDays);

            InsightSeverity severity = (coverDays < t.reorderCoverDays() / 2.0 || p.onHand() == 0)
                    ? InsightSeverity.DANGER : InsightSeverity.WARNING;
            String title = "Reorder " + p.productName() + " (~" + etaDays + "d cover left)";
            String detail = "On-hand " + p.onHand() + ", avg " + round2(avgDaily) + "/day over "
                    + p.lookbackDays() + "d; ~" + etaDays + " days of cover left. Suggested reorder qty "
                    + suggestedQty + ".";
            out.add(new Insight(InsightType.LOW_STOCK_REORDER, InsightScope.PRODUCT, p.productId(),
                    p.productName(), severity, title, detail, BigDecimal.valueOf(suggestedQty), today));
        }
        return out;
    }

    // --- RTO / delivery-failure risk (Req 5.1–5.3) --------------------------

    /**
     * An {@code RTO_RISK} (ORDER) per open order whose risk score is at or above
     * {@link InsightThresholds#rtoRiskThreshold()} (design &sect;Scoring detail).
     * Callers pass only open, non-terminal, non-delivered orders; the engine
     * scores whatever it is given. Orders are processed in ascending
     * {@code orderId} order for deterministic output.
     */
    public List<Insight> rtoRisk(List<OpenOrderRisk> orders, InsightThresholds t, LocalDate today) {
        List<OpenOrderRisk> sorted = new ArrayList<>(orders == null ? List.of() : orders);
        sorted.sort(Comparator.comparing(OpenOrderRisk::orderId,
                Comparator.nullsLast(Comparator.naturalOrder())));

        List<Insight> out = new ArrayList<>();
        for (OpenOrderRisk o : sorted) {
            double score = rtoRiskScore(o);
            if (score < t.rtoRiskThreshold()) {
                continue;
            }
            InsightSeverity severity = score >= RTO_DANGER_SCORE
                    ? InsightSeverity.DANGER : InsightSeverity.WARNING;
            BigDecimal metric = BigDecimal.valueOf(score).setScale(0, RoundingMode.HALF_UP);
            String title = "RTO risk " + metric.toPlainString() + " for order " + o.orderCode();
            String detail = "Risk score " + metric.toPlainString() + "/100 — state "
                    + (o.state() == null ? "?" : o.state()) + " (failure rate " + round2(o.stateFailureRate())
                    + "), COD " + (o.codAmount() == null ? "0" : o.codAmount().toPlainString())
                    + ", prior failed attempts " + o.priorFailedForCustomer() + ".";
            out.add(new Insight(InsightType.RTO_RISK, InsightScope.ORDER, o.orderId(),
                    o.orderCode(), severity, title, detail, metric, today));
        }
        return out;
    }

    /**
     * The RTO risk score in {@code [0, 100]} for one order (design &sect;Scoring
     * detail): {@code clamp( (0.5·stateFailureRate + 0.3·codFactor +
     * 0.2·priorFactor) · 100 )} where {@code codFactor = min(cod / 3000, 1)}
     * (0 for a null COD) and {@code priorFactor = min(priorFailed, 3) / 3}. It
     * rises monotonically with every factor. Exposed so the property test can
     * assert the exact formula and its monotonicity.
     */
    public double rtoRiskScore(OpenOrderRisk order) {
        double stateFactor = clamp01(order.stateFailureRate());
        double codFactor = 0.0;
        if (order.codAmount() != null) {
            codFactor = clamp01(order.codAmount().doubleValue() / COD_CAP);
        }
        double priorFactor = Math.min(Math.max(order.priorFailedForCustomer(), 0), PRIOR_CAP) / PRIOR_CAP;
        double raw = (W_STATE * stateFactor + W_COD * codFactor + W_PRIOR * priorFactor) * 100.0;
        return Math.max(0.0, Math.min(100.0, raw));
    }

    // --- Courier scorecard (Req 6.1–6.3) ------------------------------------

    /**
     * A {@code COURIER_SCORECARD} (COURIER) per courier that has terminal
     * shipments in the window (design &sect;Pure domain): delivery success %,
     * RTO %, and average transit days. The severity is WARNING when
     * {@code rtoPct > }{@link InsightThresholds#courierRtoWarnPct()}, else INFO.
     * Couriers are processed in ascending {@code courierCompanyId} order.
     */
    public List<Insight> courierScorecards(List<CourierOutcome> couriers, InsightThresholds t,
                                            LocalDate today) {
        List<CourierOutcome> sorted = new ArrayList<>(couriers == null ? List.of() : couriers);
        sorted.sort(Comparator.comparing(CourierOutcome::courierCompanyId,
                Comparator.nullsLast(Comparator.naturalOrder())));

        List<Insight> out = new ArrayList<>();
        for (CourierOutcome c : sorted) {
            long terminal = c.delivered() + c.rto() + c.failed() + c.otherTerminal();
            if (terminal <= 0) {
                continue;
            }
            BigDecimal deliveryPct = pct(c.delivered(), terminal);
            BigDecimal rtoPct = pct(c.rto(), terminal);
            InsightSeverity severity = rtoPct.compareTo(t.courierRtoWarnPct()) > 0
                    ? InsightSeverity.WARNING : InsightSeverity.INFO;
            String title = c.courierName() + ": " + deliveryPct.toPlainString() + "% delivered, "
                    + rtoPct.toPlainString() + "% RTO";
            String detail = "Over " + terminal + " terminal shipments — delivered "
                    + deliveryPct.toPlainString() + "%, RTO " + rtoPct.toPlainString()
                    + "%, avg transit " + round2(c.avgTransitDays()) + " days.";
            out.add(new Insight(InsightType.COURIER_SCORECARD, InsightScope.COURIER, c.courierCompanyId(),
                    c.courierName(), severity, title, detail, rtoPct, today));
        }
        return out;
    }

    // --- Return-rate anomaly (Req 7.1) --------------------------------------

    /**
     * A {@code RETURN_RATE_ANOMALY} (GLOBAL) when the window's return rate
     * ({@code returns / delivered · 100}) exceeds
     * {@link InsightThresholds#returnRateWarnPct()}; the rate is 0 (never flagged)
     * when nothing was delivered. A rate beyond twice the threshold is DANGER,
     * else WARNING.
     */
    public List<Insight> returnRate(ReturnStats returns, InsightThresholds t, LocalDate today) {
        if (returns == null || returns.delivered() <= 0) {
            return List.of();               // rate = 0, never flagged (Req 7.1)
        }
        BigDecimal rate = pct(returns.returns(), returns.delivered());
        if (rate.compareTo(t.returnRateWarnPct()) <= 0) {
            return List.of();
        }
        BigDecimal twice = t.returnRateWarnPct().multiply(TWO);
        InsightSeverity severity = rate.compareTo(twice) > 0
                ? InsightSeverity.DANGER : InsightSeverity.WARNING;
        String title = "Return rate " + rate.toPlainString() + "%";
        String detail = returns.returns() + " returns over " + returns.delivered()
                + " delivered — return rate " + rate.toPlainString() + "%.";
        return List.of(new Insight(InsightType.RETURN_RATE_ANOMALY, InsightScope.GLOBAL, GLOBAL_REF,
                "All deliveries", severity, title, detail, rate, today));
    }

    // --- COD-outstanding build-up (Req 7.2) ---------------------------------

    /**
     * A {@code COD_OUTSTANDING_BUILDUP} (GLOBAL, WARNING) when the total unsettled
     * COD receivable exceeds {@link InsightThresholds#codOutstandingWarn()}.
     */
    public List<Insight> codBuildup(CodOutstanding cod, InsightThresholds t, LocalDate today) {
        if (cod == null || cod.unsettledTotal() == null) {
            return List.of();
        }
        BigDecimal total = cod.unsettledTotal();
        if (total.compareTo(t.codOutstandingWarn()) <= 0) {
            return List.of();
        }
        String title = "COD outstanding " + total.toPlainString();
        String detail = "Unsettled COD receivables total " + total.toPlainString()
                + ", above the " + t.codOutstandingWarn().toPlainString() + " threshold.";
        return List.of(new Insight(InsightType.COD_OUTSTANDING_BUILDUP, InsightScope.GLOBAL, GLOBAL_REF,
                "All receivables", InsightSeverity.WARNING, title, detail, total, today));
    }

    // --- Lead-source conversion (Req 8.1, 8.2) ------------------------------

    /**
     * One {@code LEAD_SOURCE_CONVERSION} (GLOBAL, INFO) naming the best- and
     * worst-converting lead channels by {@code won / leads}
     * ({@link LeadReportAggregator#conversionRate(long, long)}), with a
     * deterministic tie-break by source name. No insight when there are no lead
     * sources at all or every source has zero leads (Req 8.2). The metric is the
     * best rate.
     */
    public List<Insight> leadSourceConversion(List<LeadSourceConversion> leadSources,
                                              InsightThresholds t, LocalDate today) {
        if (leadSources == null || leadSources.isEmpty()) {
            return List.of();
        }
        boolean anyLeads = false;
        LeadSourceConversion best = null;
        LeadSourceConversion worst = null;
        BigDecimal bestRate = null;
        BigDecimal worstRate = null;
        for (LeadSourceConversion ls : leadSources) {
            if (ls.leads() > 0) {
                anyLeads = true;
            }
            BigDecimal rate = LeadReportAggregator.conversionRate(ls.won(), ls.leads());
            if (best == null || betterThan(rate, ls, bestRate, best)) {
                best = ls;
                bestRate = rate;
            }
            if (worst == null || worseThan(rate, ls, worstRate, worst)) {
                worst = ls;
                worstRate = rate;
            }
        }
        if (!anyLeads) {
            return List.of();               // no leads on any channel — nothing to compare (Req 8.2)
        }
        String title = "Best channel " + best.source().name() + ", worst " + worst.source().name();
        String detail = "Best-converting: " + best.source().name() + " at " + bestRate.toPlainString()
                + " (" + best.won() + "/" + best.leads() + "); worst-converting: " + worst.source().name()
                + " at " + worstRate.toPlainString() + " (" + worst.won() + "/" + worst.leads() + ").";
        return List.of(new Insight(InsightType.LEAD_SOURCE_CONVERSION, InsightScope.GLOBAL, GLOBAL_REF,
                "Lead channels", InsightSeverity.INFO, title, detail, bestRate, today));
    }

    // --- Top-level composition ----------------------------------------------

    /**
     * Runs every insight family over {@code inputs} in a fixed order — sales,
     * reorder, RTO risk, courier scorecard, return-rate, COD, lead-source — and
     * concatenates the results (design &sect;Pure domain). Deterministic: equal
     * inputs yield an equal, equally-ordered list (Property 7).
     */
    public List<Insight> compute(InsightInputs inputs, InsightThresholds t, LocalDate today) {
        List<Insight> out = new ArrayList<>();
        out.addAll(salesAnomaly(inputs.sales(), t, today));
        out.addAll(reorder(inputs.products(), t, today));
        out.addAll(rtoRisk(inputs.openOrders(), t, today));
        out.addAll(courierScorecards(inputs.couriers(), t, today));
        out.addAll(returnRate(inputs.returns(), t, today));
        out.addAll(codBuildup(inputs.cod(), t, today));
        out.addAll(leadSourceConversion(inputs.leadSources(), t, today));
        return out;
    }

    // --- Helpers ------------------------------------------------------------

    /** Whether {@code (rate, candidate)} out-ranks the current best: higher rate, tie → lower name. */
    private static boolean betterThan(BigDecimal rate, LeadSourceConversion candidate,
                                      BigDecimal bestRate, LeadSourceConversion best) {
        int cmp = rate.compareTo(bestRate);
        if (cmp != 0) {
            return cmp > 0;
        }
        return candidate.source().name().compareTo(best.source().name()) < 0;
    }

    /** Whether {@code (rate, candidate)} out-ranks the current worst: lower rate, tie → lower name. */
    private static boolean worseThan(BigDecimal rate, LeadSourceConversion candidate,
                                     BigDecimal worstRate, LeadSourceConversion worst) {
        int cmp = rate.compareTo(worstRate);
        if (cmp != 0) {
            return cmp < 0;
        }
        return candidate.source().name().compareTo(worst.source().name()) < 0;
    }

    /** {@code numerator / denominator · 100} as a percentage in 2dp (denominator &gt; 0). */
    private static BigDecimal pct(long numerator, long denominator) {
        return BigDecimal.valueOf(numerator)
                .multiply(HUNDRED)
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    /** Clamps a double into {@code [0, 1]}. */
    private static double clamp01(double v) {
        if (v < 0.0) {
            return 0.0;
        }
        return Math.min(v, 1.0);
    }

    /** Rounds a double to 2dp for human-readable detail text. */
    private static BigDecimal round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }
}
