package com.shifa.oms.geo.dto;

import com.shifa.oms.geo.DeliveryState;

/**
 * Admin projection of a {@link DeliveryState} for the Settings management table.
 * The order-entry typeahead uses the lighter {@code GET /api/states} endpoint
 * which returns just the active names.
 */
public record DeliveryStateResponse(
        Long id,
        String name,
        boolean active,
        int sortOrder
) {
    public static DeliveryStateResponse from(DeliveryState state) {
        return new DeliveryStateResponse(
                state.getId(), state.getName(), state.isActive(), state.getSortOrder());
    }
}
