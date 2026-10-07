package com.shifa.oms.insights.domain;

import java.util.List;

/**
 * The bundle of all projection inputs the pure {@code InsightEngine} consumes in
 * one {@code compute(...)} call (design &sect;Pure domain). Bundling them keeps
 * the engine signature stable as inputs grow.
 *
 * <p>The list-valued members are treated null-safely: a {@code null} list is
 * read as empty via the accessors below, so callers and generators need not
 * defend against nulls.
 *
 * @param sales       the current/previous sales window (may be null)
 * @param locations   per-state sales + delivery-outcome projections
 * @param couriers    per-courier outcome projections
 * @param openOrders  open-order risk feature projections
 * @param cod         the unsettled COD total (may be null)
 * @param leadSources per-channel lead conversion projections
 */
public record InsightInputs(
        SalesWindow sales,
        List<LocationPerformance> locations,
        List<CourierOutcome> couriers,
        List<OpenOrderRisk> openOrders,
        CodOutstanding cod,
        List<LeadSourceConversion> leadSources) {

    /** The per-state location projections, never null. */
    public List<LocationPerformance> locations() {
        return locations == null ? List.of() : locations;
    }

    /** The courier projections, never null. */
    public List<CourierOutcome> couriers() {
        return couriers == null ? List.of() : couriers;
    }

    /** The open-order projections, never null. */
    public List<OpenOrderRisk> openOrders() {
        return openOrders == null ? List.of() : openOrders;
    }

    /** The lead-source projections, never null. */
    public List<LeadSourceConversion> leadSources() {
        return leadSources == null ? List.of() : leadSources;
    }
}
