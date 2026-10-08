package com.shifa.oms.gst.filing.domain;

import java.util.Map;
import java.util.Optional;

/**
 * Attributes a detected post-filing GSTR-1 correction to the return period it must be reported in
 * (GST returns &amp; filing, Reqs 3.3, 3.6).
 *
 * <p>A correction is never written back into an already-{@link FilingStatus#FILED filed} period —
 * filed figures are locked and served from an immutable snapshot. Instead, GST amendments are
 * reported in the <strong>next open (not-yet-filed) return</strong>. This class encodes just that
 * attribution rule so it can be property-tested in isolation:
 *
 * <ul>
 *   <li>{@link #targetPeriod(ReturnPeriod, Map)} — the <em>earliest</em> known {@link ReturnPeriod}
 *       on or after the change's period whose GSTR-1 {@link FilingStatus} is not {@code FILED}
 *       (Req 3.3);</li>
 *   <li>{@link Optional#empty() empty} when no such open period exists yet — the correction is then
 *       held pending and attributed later, once an open period appears, without altering any filed
 *       period (Req 3.6).</li>
 * </ul>
 *
 * <p>Periods are totally ordered by {@code (year, month)}: a period is "on or after" another when
 * its {@code year} is greater, or its {@code year} is equal and its {@code month} is greater or
 * equal. Because {@link ReturnPeriod} validates {@code 1 ≤ month ≤ 12} on construction, this
 * ordering is well-defined for every input.
 *
 * <p>Only the periods <strong>present</strong> in {@code gstr1StatusByPeriod} are considered
 * candidates: the map is the set of known/tracked periods and their GSTR-1 statuses. A period
 * absent from the map is implicitly {@link FilingStatus#NOT_STARTED} but is not conjured as a
 * target here — so when every known period on or after the change is {@code FILED} (or none exist),
 * attribution yields {@link Optional#empty()} and the caller holds the correction pending
 * (Req 3.6). A {@code null} status value is treated as not-{@code FILED} (an open period).
 *
 * <p>Pure and Spring-free; no JPA. Total over all inputs — {@code null} maps/keys are treated as
 * absent, so this never throws for a {@code null} or empty map.
 */
public final class AmendmentAttribution {

    private AmendmentAttribution() {
        // Utility holder; not instantiable.
    }

    /**
     * The target period for a correction whose underlying document falls in {@code changeMonth}: the
     * earliest known return period on or after {@code changeMonth} whose GSTR-1 status is not
     * {@link FilingStatus#FILED} (Req 3.3).
     *
     * @param changeMonth         the period of the changed document (must not be {@code null})
     * @param gstr1StatusByPeriod the known periods mapped to their current GSTR-1 filing status; a
     *                            {@code null} or empty map means no open period is known yet
     * @return the earliest open period on or after {@code changeMonth}, or {@link Optional#empty()}
     *     when none exists yet (the correction is held pending and attributed later — Req 3.6)
     * @throws NullPointerException if {@code changeMonth} is {@code null}
     */
    public static Optional<ReturnPeriod> targetPeriod(
            ReturnPeriod changeMonth, Map<ReturnPeriod, FilingStatus> gstr1StatusByPeriod) {
        if (changeMonth == null) {
            throw new NullPointerException("changeMonth must not be null");
        }
        if (gstr1StatusByPeriod == null || gstr1StatusByPeriod.isEmpty()) {
            return Optional.empty();
        }
        long changeOrdinal = ordinal(changeMonth);
        ReturnPeriod best = null;
        long bestOrdinal = Long.MAX_VALUE;
        for (Map.Entry<ReturnPeriod, FilingStatus> entry : gstr1StatusByPeriod.entrySet()) {
            ReturnPeriod period = entry.getKey();
            if (period == null || entry.getValue() == FilingStatus.FILED) {
                continue; // absent key or a locked (filed) period is not a candidate
            }
            long periodOrdinal = ordinal(period);
            if (periodOrdinal < changeOrdinal) {
                continue; // strictly before the change month
            }
            if (best == null || periodOrdinal < bestOrdinal) {
                best = period;
                bestOrdinal = periodOrdinal;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * A monotonic ordinal giving the total ordering over {@code (year, month)}: two periods compare
     * equal iff their ordinals are equal, and the earlier period has the smaller ordinal. Safe from
     * overflow for any realistic calendar year.
     *
     * @param period the period to rank
     * @return {@code year * 12 + (month - 1)}
     */
    private static long ordinal(ReturnPeriod period) {
        return (long) period.year() * 12L + (period.month() - 1);
    }
}
