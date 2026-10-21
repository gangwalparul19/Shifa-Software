package com.shifa.oms.reconciliation;

/** Lifecycle of an imported settlement batch. */
public enum SettlementBatchStatus {
    PROCESSING,
    POSTED,
    POSTED_WITH_EXCEPTIONS,
    VOID
}
