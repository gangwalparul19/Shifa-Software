package com.shifa.oms.reconciliation;

import java.util.Locale;

/** Payment-category buckets used by Settlement Center and the Orders drill-down. */
public enum SettlementPaymentCategory {
    CASH,
    NO_RECEIVING_ACCOUNT,
    NAMED_ACCOUNT;

    public static SettlementPaymentCategory from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new com.shifa.oms.common.ValidationException(
                    "Unknown settlement payment category: " + raw);
        }
    }
}
