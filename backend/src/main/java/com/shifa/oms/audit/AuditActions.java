package com.shifa.oms.audit;

/**
 * The canonical set of audit action verbs and entity-type discriminators used
 * across the instrumented mutating operations ("operations depth" Feature 3).
 *
 * <p>Centralising the string constants keeps the trail's vocabulary consistent
 * (so the admin can reliably filter by {@code action=ORDER_APPROVED}) and avoids
 * typos scattered across the calling services.
 */
public final class AuditActions {

    private AuditActions() {
    }

    // --- Action verbs -------------------------------------------------------

    public static final String ORDER_APPROVED = "ORDER_APPROVED";
    public static final String ORDER_REJECTED = "ORDER_REJECTED";
    /** Generic per-transition audit written by the central OrderWorkflowService (Req 15.1, 15.5). */
    public static final String ORDER_STATUS_CHANGED = "ORDER_STATUS_CHANGED";

    public static final String USER_CREATED = "USER_CREATED";
    public static final String USER_UPDATED = "USER_UPDATED";
    public static final String USER_PASSWORD_RESET = "USER_PASSWORD_RESET";
    public static final String USER_ACTIVATED = "USER_ACTIVATED";
    public static final String USER_DEACTIVATED = "USER_DEACTIVATED";

    public static final String SETTINGS_UPDATED = "SETTINGS_UPDATED";

    public static final String STOCK_RESTOCKED = "STOCK_RESTOCKED";
    public static final String STOCK_ADJUSTED = "STOCK_ADJUSTED";

    public static final String COUPON_CREATED = "COUPON_CREATED";
    public static final String COUPON_UPDATED = "COUPON_UPDATED";
    public static final String COUPON_DEACTIVATED = "COUPON_DEACTIVATED";

    // Returns / refunds / RTO workflow ("operations depth" Feature 1).
    public static final String RETURN_CREATED = "RETURN_CREATED";
    public static final String RETURN_APPROVED = "RETURN_APPROVED";
    public static final String RETURN_REJECTED = "RETURN_REJECTED";
    public static final String RETURN_REFUNDED = "RETURN_REFUNDED";

    // Bulk product CSV import ("operations depth" Feature 2).
    public static final String PRODUCTS_IMPORTED = "PRODUCTS_IMPORTED";

    // Procurement: suppliers + purchase orders (Feature C2).
    public static final String SUPPLIER_CREATED = "SUPPLIER_CREATED";
    public static final String PO_CREATED = "PO_CREATED";
    public static final String PO_RECEIVED = "PO_RECEIVED";
    public static final String PO_CANCELLED = "PO_CANCELLED";

    // Finance: expenses (Feature C3).
    public static final String EXPENSE_ADDED = "EXPENSE_ADDED";
    public static final String EXPENSE_DELETED = "EXPENSE_DELETED";

    // Lead management & sales pipeline (Lead Management feature).
    public static final String LEAD_CAPTURED = "LEAD_CAPTURED";
    public static final String LEAD_STATUS_CHANGED = "LEAD_STATUS_CHANGED";
    public static final String LEAD_FOLLOW_UP_SET = "LEAD_FOLLOW_UP_SET";
    public static final String LEAD_CONVERTED = "LEAD_CONVERTED";

    // --- Entity types -------------------------------------------------------

    public static final String ENTITY_ORDER = "ORDER";
    public static final String ENTITY_USER = "USER";
    public static final String ENTITY_SETTINGS = "SETTINGS";
    public static final String ENTITY_PRODUCT = "PRODUCT";
    public static final String ENTITY_COUPON = "COUPON";
    public static final String ENTITY_RETURN = "RETURN";
    public static final String ENTITY_SUPPLIER = "SUPPLIER";
    public static final String ENTITY_PURCHASE_ORDER = "PURCHASE_ORDER";
    public static final String ENTITY_EXPENSE = "EXPENSE";
    public static final String ENTITY_LEAD = "LEAD";
}
