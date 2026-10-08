package com.shifa.oms.gst.domain;

/**
 * A flag recording a place-of-supply state name that did not resolve to a 2-digit GST state code via
 * the {@link StateCodeMaster} (GST filing compliance, Req 6.3).
 *
 * <p>The builder collects these so the CA can be shown exactly which rows need a state-code mapping,
 * identified by the unresolved state name and the context in which it was encountered (e.g. the
 * section or order code).
 *
 * @param stateName the place-of-supply state name that could not be resolved
 * @param context   a description of where the unresolved name was encountered (section/order)
 */
public record UnresolvedStateFlag(
        String stateName,
        String context) {
}
