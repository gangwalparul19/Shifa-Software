package com.shifa.oms.reporting.domain;

/**
 * The report views the {@code Reporting_Service} can generate (Req 20.1, 20.3).
 *
 * <ul>
 *   <li>{@link #DAILY} — one row per order date, with order count and total sales.</li>
 *   <li>{@link #MONTHLY} — one row per calendar month.</li>
 *   <li>{@link #PRODUCT} — one row per product, with quantity sold and sales.</li>
 *   <li>{@link #STATE} — one row per destination state.</li>
 *   <li>{@link #SALESPERSON} — the detailed per-order report (Req 20.3), one row per
 *       order carrying every required column.</li>
 *   <li>{@link #ORDERS_BY_LEAD_SOURCE} — order counts grouped by {@code LeadSource}
 *       over the range, NULL bucketed as {@code UNSPECIFIED} (Req 16.1).</li>
 *   <li>{@link #ORDERS_BY_STATUS} — order counts grouped by {@code OrderStatus} (Req 16.2).</li>
 *   <li>{@link #ORDERS_BY_SALESPERSON} — order counts grouped by {@code createdBy} (Req 16.3).</li>
 *   <li>{@link #DELIVERY_OUTCOME} — delivered / customer-rejected / delivery-failed /
 *       cancelled counts plus the delivery success rate over the range (Req 16.4).</li>
 * </ul>
 */
public enum ReportType {
    DAILY,
    MONTHLY,
    PRODUCT,
    STATE,
    CUSTOMER,
    SALESPERSON,
    ORDERS_BY_LEAD_SOURCE,
    ORDERS_BY_STATUS,
    ORDERS_BY_SALESPERSON,
    DELIVERY_OUTCOME,
    /**
     * Order counts grouped by order channel — Shopify versus Shifa Admin (spec
     * {@code shopify-quikshipx-order-sync}, Req 12.1). Legacy source values fold onto
     * {@code SHIFA_ADMIN}, so the buckets partition the window.
     */
    ORDERS_BY_CHANNEL,

    // --- Money / receivables (accountant) ---
    /** Daily money view: orders, total sales, amount received, COD, and outstanding. */
    PAYMENTS,
    /**
     * Per-order outstanding balances (total − received) still collectible, oldest
     * first — the accountant's "who owes how much, and how long" chase list.
     */
    OUTSTANDING,
    /**
     * COD amounts pending remittance FROM the delivery partner (COD receivable not
     * yet settled), oldest first — what to chase the courier for.
     */
    COD_REMITTANCE,

    // --- Per-module operational reports (ADMIN / ACCOUNTANT only) ---
    /** Business expenses in the window: date, category, amount, description. */
    EXPENSES,
    /** Purchase orders in the window: PO number, supplier, status, total, dates. */
    PURCHASE_ORDERS,
    /** Order returns/refunds in the window: order, reason, status, refund, restocked. */
    RETURNS,
    /** Stock ledger movements in the window: date, product, type, change, balance, reason. */
    STOCK;

    /**
     * Whether this is a per-module operational report (expenses / procurement /
     * returns / inventory). These are business-wide and NOT salesperson-scoped,
     * so the service restricts them to ADMIN / ACCOUNTANT.
     */
    public boolean isModuleReport() {
        return this == EXPENSES || this == PURCHASE_ORDERS || this == RETURNS || this == STOCK;
    }

    /**
     * Whether this is a money/receivables report (payments, outstanding dues, COD
     * remittance). These are restricted to ADMIN / ACCOUNTANT — a salesperson gets
     * only the sales/orders reports (scoped to their own orders).
     */
    public boolean isMoneyReport() {
        return this == PAYMENTS || this == OUTSTANDING || this == COD_REMITTANCE;
    }

    /** Case-insensitive parse of a report type from a request path/param value. */
    public static ReportType from(String value) {
        if (value == null) {
            throw new IllegalArgumentException("report type is required");
        }
        return switch (value.trim().toLowerCase()) {
            case "daily" -> DAILY;
            case "monthly" -> MONTHLY;
            case "product", "product-wise", "productwise" -> PRODUCT;
            case "state", "state-wise", "statewise" -> STATE;
            case "customer", "customer-wise", "customerwise" -> CUSTOMER;
            case "salesperson", "salesperson-wise" -> SALESPERSON;
            case "orders-by-lead-source", "orders_by_lead_source", "lead-source", "leadsource" ->
                    ORDERS_BY_LEAD_SOURCE;
            case "orders-by-status", "orders_by_status", "by-status" -> ORDERS_BY_STATUS;
            case "orders-by-salesperson", "orders_by_salesperson", "by-salesperson" ->
                    ORDERS_BY_SALESPERSON;
            case "delivery-outcome", "delivery_outcome", "outcome" -> DELIVERY_OUTCOME;
            case "orders-by-channel", "orders_by_channel", "by-channel", "channel" ->
                    ORDERS_BY_CHANNEL;
            case "payments", "payment", "money" -> PAYMENTS;
            case "outstanding", "dues", "pending" -> OUTSTANDING;
            case "cod-remittance", "cod_remittance", "cod-pending", "cod" -> COD_REMITTANCE;
            case "expenses", "expense" -> EXPENSES;
            case "purchase-orders", "purchase_orders", "po", "procurement" -> PURCHASE_ORDERS;
            case "returns", "return" -> RETURNS;
            case "stock", "inventory", "stock-movements" -> STOCK;
            default -> throw new IllegalArgumentException("unknown report type: " + value);
        };
    }
}
