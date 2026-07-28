package com.shifa.oms.order.dto;

import com.shifa.oms.order.OrderEntity;

/**
 * Customer + shipping details pulled from a customer's MOST RECENT order, used to
 * pre-fill the New Order form once the salesperson types a known mobile number
 * ({@code GET /api/orders/last-by-mobile?mobile=}). Everything is a suggestion the
 * salesperson can override; order-specific data (items, payment, notes) is never
 * carried over.
 *
 * <p>{@code found=false} (all other fields null) means there is no prior order for
 * that mobile — the salesperson simply fills the form as usual.
 */
public record CustomerPrefillResponse(
        boolean found,
        String customerName,
        String customerEmail,
        String alternateMobile,
        String addressLine,
        String city,
        String state,
        String postalCode,
        String leadSource,
        String leadSourceNote) {

    /** Empty (no prior order) response for a mobile with no history. */
    public static CustomerPrefillResponse empty() {
        return new CustomerPrefillResponse(false, null, null, null, null, null, null, null, null, null);
    }

    /** Builds a prefill from the customer's most recent order. */
    public static CustomerPrefillResponse from(OrderEntity order) {
        return new CustomerPrefillResponse(
                true,
                order.getCustomerName(),
                order.getCustomerEmail(),
                order.getAlternateMobile(),
                order.getAddressLine(),
                order.getCity(),
                order.getState(),
                order.getPostalCode(),
                order.getLeadSource() == null ? null : order.getLeadSource().name(),
                order.getLeadSourceNote());
    }
}
