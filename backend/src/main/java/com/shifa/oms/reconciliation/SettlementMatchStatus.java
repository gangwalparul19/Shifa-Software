package com.shifa.oms.reconciliation;

/** Review status of one imported settlement line. */
public enum SettlementMatchStatus {
    MATCHED,
    MISMATCH,
    UNMATCHED,
    AMBIGUOUS,
    DUPLICATE_IN_FILE,
    INVALID,
    RESOLVED
}
