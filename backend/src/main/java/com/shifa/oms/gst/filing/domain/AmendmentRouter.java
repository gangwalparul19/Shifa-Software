package com.shifa.oms.gst.filing.domain;

import java.util.Optional;

/**
 * Routes a detected GSTR-1 outward-section correction to the GST amendment section it must be
 * reported in (GST returns &amp; filing, Reqs 3.2, 3.7).
 *
 * <p>The GST portal supports amending only three outward sections:
 * <ul>
 *   <li>{@link Gstr1Section#B2B} &rarr; {@link AmendmentTable#B2BA}</li>
 *   <li>{@link Gstr1Section#B2CS} &rarr; {@link AmendmentTable#B2CSA}</li>
 *   <li>{@link Gstr1Section#CDNR} &rarr; {@link AmendmentTable#CDNRA}</li>
 * </ul>
 *
 * <p>Every other section ({@link Gstr1Section#B2CL}, {@link Gstr1Section#CDNUR},
 * {@link Gstr1Section#HSN}, {@link Gstr1Section#DOCS}) has no supported amendment table. A
 * correction to one of those sections is <strong>never discarded</strong>; instead it is held for
 * manual CA review (Req 3.7). This "manual review" outcome is represented here as an
 * {@linkplain Optional#empty() empty} {@link Optional} returned by {@link #route(Gstr1Section)} —
 * i.e. {@code route(section)} yields the target {@link AmendmentTable} when one exists, or an empty
 * {@code Optional} signalling that the caller must route the correction to manual review.
 *
 * <p>Pure and Spring-free; the routing decision is total over all {@link Gstr1Section} values.
 */
public final class AmendmentRouter {

    private AmendmentRouter() {
    }

    /**
     * Determines the {@link AmendmentTable} a correction to the given GSTR-1 section is reported in.
     *
     * @param section the GSTR-1 section whose figure changed after filing (must not be {@code null})
     * @return the target amendment table for {@link Gstr1Section#B2B}, {@link Gstr1Section#B2CS}, and
     *     {@link Gstr1Section#CDNR}; an {@linkplain Optional#empty() empty} {@code Optional} for every
     *     other section, signalling the correction must be held for manual CA review (Req 3.7)
     * @throws NullPointerException if {@code section} is {@code null}
     */
    public static Optional<AmendmentTable> route(Gstr1Section section) {
        if (section == null) {
            throw new NullPointerException("section must not be null");
        }
        return switch (section) {
            case B2B -> Optional.of(AmendmentTable.B2BA);
            case B2CS -> Optional.of(AmendmentTable.B2CSA);
            case CDNR -> Optional.of(AmendmentTable.CDNRA);
            case B2CL, CDNUR, HSN, DOCS -> Optional.empty();
        };
    }

    /**
     * Convenience predicate: {@code true} when a correction to the given section has no supported
     * amendment table and must be flagged for manual CA review rather than routed (Req 3.7).
     *
     * @param section the GSTR-1 section whose figure changed after filing (must not be {@code null})
     * @return {@code true} iff {@link #route(Gstr1Section)} yields an empty {@code Optional}
     * @throws NullPointerException if {@code section} is {@code null}
     */
    public static boolean requiresManualReview(Gstr1Section section) {
        return route(section).isEmpty();
    }
}
