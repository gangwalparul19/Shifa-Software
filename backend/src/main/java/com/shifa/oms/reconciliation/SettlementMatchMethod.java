package com.shifa.oms.reconciliation;

/** Deterministic key that produced a settlement-line candidate. */
public enum SettlementMatchMethod {
    UTR,
    SHOPIFY_ORDER_ID,
    ORDER_CODE,
    AWB,
    QUICKSHIP_CLIENT_ORDER_ID
}
