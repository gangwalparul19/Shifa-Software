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
 * them all in a fixed order (sales anomaly, top sales location, underperforming
 * locations, RTO risk, courier, COD, lead-source), sorting collection inputs
 * deterministically so the output ordering is stable. GLOBAL-scope insights
 * carry the sentinel {@code scopeRefId = 0} (except multi-row location warnings,
 * which derive a stable per-state ref to stay unique).
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

    // --- Top sales location (marketing opportunity) ------------------------

    /**
     * A {@code TOP_SALES_LOCATION} (GLOBAL, INFO) naming the single strongest
     * state by window revenue, among states with at least
     * {@link InsightThresholds#locationMinOrders()} orders (so a one-off big
     * order can't crown a location). It is an opportunity cue: this is where
     * marketing is paying off, so consider pushing harder there. No insight when
     * no state clears the minimum-orders bar. Metric = the winning state's revenue.
     */
    public List<Insight> topSalesLocation(List<LocationPerformance> locations, InsightThresholds t,
                                          LocalDate today) {
        List<LocationPerformance> sorted = new ArrayList<>(locations == null ? List.of() : locations);
        // Deterministic: highest revenue first, tie-break by state name.
        sorted.sort(Comparator.comparing((LocationPerformance l) -> nz(l.revenue()))
                .reversed()
                .thenComparing(l -> label(l.state())));
        for (LocationPerformance l : sorted) {
            if (l.orders() < t.locationMinOrders() || nz(l.revenue()).signum() <= 0) {
                continue;
            }
            String state = label(l.state());
            String title = "Top market: " + state;
            String detail = state + " is your strongest market this window — " + l.orders()
                    + " orders for " + nz(l.revenue()).toPlainString()
                    + " in revenue. Consider increasing marketing spend here to grow it further.";
            return List.of(new Insight(InsightType.TOP_SALES_LOCATION, InsightScope.GLOBAL, GLOBAL_REF,
                    state, InsightSeverity.INFO, title, detail, nz(l.revenue()), today));
        }
        return List.of();
    }

    // --- Underperforming location (wasted marketing spend) -----------------

    /**
     * An {@code UNDERPERFORMING_LOCATION} (GLOBAL, WARNING) per state that has at
     * least {@link InsightThresholds#locationMinOrders()} concluded orders but a
     * delivery-failure share (failed / (delivered + failed) · 100) above
     * {@link InsightThresholds#locationFailWarnPct()}. The business has no
     * marketing-spend-by-location data, so this is the grounded proxy for "spend
     * here isn't converting into delivered sales": orders are coming in but not
     * reaching the customer, so fix delivery there or cut the marketing spend. A
     * failure share beyond twice the threshold is DANGER. States processed by
     * name for deterministic output; metric = the failure percentage.
     */
    public List<Insight> underperformingLocations(List<LocationPerformance> locations,
                                                   InsightThresholds t, LocalDate today) {
        List<LocationPerformance> sorted = new ArrayList<>(locations == null ? List.of() : locations);
        sorted.sort(Comparator.comparing(l -> label(l.state())));

        List<Insight> out = new ArrayList<>();
        for (LocationPerformance l : sorted) {
            long concluded = l.delivered() + l.failed();
            // Need enough concluded orders to judge delivery, and enough total volume to matter.
            if (concluded <= 0 || l.orders() < t.locationMinOrders()) {
                continue;
            }
            BigDecimal failPct = pct(l.failed(), concluded);
            if (failPct.compareTo(t.locationFailWarnPct()) <= 0) {
                continue;
            }
            BigDecimal twice = t.locationFailWarnPct().multiply(TWO);
            InsightSeverity severity = failPct.compareTo(twice) > 0
                    ? InsightSeverity.DANGER : InsightSeverity.WARNING;
            String state = label(l.state());
            String title = "Weak market: " + state + " (" + failPct.toPlainString() + "% not delivered)";
            String detail = state + " took " + l.orders() + " orders but " + l.failed() + " of "
                    + concluded + " concluded deliveries failed/returned (" + failPct.toPlainString()
                    + "%). Marketing spend here isn't converting into delivered sales — fix delivery "
                    + "(courier/address quality) or reduce spend in this location.";
            // Several states can underperform at once; give each a stable, distinct
            // scopeRefId (derived from the state name) so they don't collide on the
            // (type, scope, scopeRefId, date) natural key.
            out.add(new Insight(InsightType.UNDERPERFORMING_LOCATION, InsightScope.GLOBAL, stateRef(state),
                    state, severity, title, detail, failPct, today));
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
        String title = "Push " + best.source().name() + " — your best-converting channel";
        String detail = best.source().name() + " converts best at " + bestRate.toPlainString()
                + " (" + best.won() + "/" + best.leads() + " leads won) — lean marketing into it. "
                + worst.source().name() + " converts worst at " + worstRate.toPlainString()
                + " (" + worst.won() + "/" + worst.leads() + "); review or trim spend there.";
        return List.of(new Insight(InsightType.LEAD_SOURCE_CONVERSION, InsightScope.GLOBAL, GLOBAL_REF,
                "Lead channels", InsightSeverity.INFO, title, detail, bestRate, today));
    }

    // --- Top-level composition ----------------------------------------------

    /**
     * Runs every insight family over {@code inputs} in a fixed order — sales
     * anomaly, top sales location, underperforming locations, RTO risk, courier
     * scorecard, COD build-up, lead-source conversion — and concatenates the
     * results (design &sect;Pure domain). Deterministic: equal inputs yield an
     * equal, equally-ordered list.
     */
    public List<Insight> compute(InsightInputs inputs, InsightThresholds t, LocalDate today) {
        List<Insight> out = new ArrayList<>();
        out.addAll(salesAnomaly(inputs.sales(), t, today));
        out.addAll(topSalesLocation(inputs.locations(), t, today));
        out.addAll(underperformingLocations(inputs.locations(), t, today));
        out.addAll(rtoRisk(inputs.openOrders(), t, today));
        out.addAll(courierScorecards(inputs.couriers(), t, today));
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

    /** A display label for a state, grouping blank/null as "Unknown". */
    private static String label(String state) {
        return (state == null || state.isBlank()) ? "Unknown" : state.trim();
    }

    /**
     * A stable, non-negative {@code scopeRefId} for a state name so several
     * underperforming states don't collide on the GLOBAL natural key. Deterministic
     * for a given name (string hash, masked to stay positive).
     */
    private static long stateRef(String state) {
        return label(state).hashCode() & 0x7fffffffL;
    }

    /** {@code numerator / denominator · 100} as a percentage in 2dp (denominator &gt; 0). */
    private static BigDecimal pct(long numerator, long denominator) {
        return BigDecimal.valueOf(numerator)
                .multiply(HUNDRED)
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    /** Null-safe BigDecimal (zero for null). */
    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
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
