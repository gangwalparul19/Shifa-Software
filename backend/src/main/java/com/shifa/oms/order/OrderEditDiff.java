package com.shifa.oms.order;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Builds the "what changed" text that goes into the audit trail when an order
 * is edited or resubmitted: a before/after snapshot of the audited fields and a
 * human-readable diff of the two.
 *
 * <p>Pure functions over plain values, so the audit wording can be read and
 * tested without {@link OrderService}.
 */
final class OrderEditDiff {

    private OrderEditDiff() {
    }

    /**
     * Captures the audited scalar fields of an order into an ordered label→value
     * map, so an edit can be diffed old→new. Amounts/status/payment-verification
     * are intentionally excluded (payment capture is not editable here); the
     * line-item set is summarised as a single "Items" entry.
     */
    static Map<String, String> snapshot(OrderEntity order) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("Customer", nz(order.getCustomerName()));
        m.put("Mobile", nz(order.getCustomerMobile()));
        m.put("Alt mobile", nz(order.getAlternateMobile()));
        m.put("Email", nz(order.getCustomerEmail()));
        m.put("Address", nz(order.getAddressLine()));
        m.put("City", nz(order.getCity()));
        m.put("State", nz(order.getState()));
        m.put("Pincode", nz(order.getPostalCode()));
        m.put("Lead source", order.getLeadSource() == null ? "" : order.getLeadSource().name());
        m.put("Lead note", nz(order.getLeadSourceNote()));
        m.put("Notes", nz(order.getNotes()));
        m.put("GSTIN", nz(order.getBuyerGstin()));
        m.put("Discount", order.getDiscountAmount() == null ? "0" : order.getDiscountAmount().toPlainString());
        m.put("Total", order.getTotalAmount() == null ? "0" : order.getTotalAmount().toPlainString());
        m.put("Items", itemsSummary(order.getLineItems()));
        return m;
    }

    /** A compact "product×qty@rate; …" summary of the order lines for diffing. */
    static String itemsSummary(List<OrderLineItem> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        return lines.stream()
                .map(li -> nz(li.getProductName()) + "×" + li.getQuantity()
                        + "@" + (li.getRate() == null ? "0" : li.getRate().toPlainString()))
                .collect(Collectors.joining("; "));
    }

    /**
     * A concise human-readable diff of the changed fields (label: old→new), or an
     * empty string when nothing changed. Used as the audit summary so the trail
     * shows exactly what an edit changed.
     */
    static String diff(Map<String, String> before, Map<String, String> after) {
        List<String> changes = new ArrayList<>();
        for (Map.Entry<String, String> e : after.entrySet()) {
            String old = before.getOrDefault(e.getKey(), "");
            String now = e.getValue();
            if (!Objects.equals(old, now)) {
                changes.add(e.getKey() + ": '" + old + "' → '" + now + "'");
            }
        }
        return String.join("; ", changes);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
