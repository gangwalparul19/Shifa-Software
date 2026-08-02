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
    /** Payment authenticity verification decisions (product-audit §4.4). */
    public static final String PAYMENT_VERIFIED = "PAYMENT_VERIFIED";
    public static final String PAYMENT_REJECTED = "PAYMENT_REJECTED";
    /** Generic per-transition audit written by the central OrderWorkflowService (Req 15.1, 15.5). */
    public static final String ORDER_STATUS_CHANGED = "ORDER_STATUS_CHANGED";

    public static final String USER_CREATED = "USER_CREATED";
    public static final String USER_UPDATED = "USER_UPDATED";
    public static final String USER_PASSWORD_RESET = "USER_PASSWORD_RESET";
    public static final String USER_ACTIVATED = "USER_ACTIVATED";
    public static final String USER_DEACTIVATED = "USER_DEACTIVATED";

    // Staff onboarding profiles + ID verification.
    public static final String STAFF_PROFILE_UPDATED = "STAFF_PROFILE_UPDATED";
    public static final String STAFF_ID_PROOF_UPLOADED = "STAFF_ID_PROOF_UPLOADED";
    public static final String STAFF_PROFILE_IMAGE_UPLOADED = "STAFF_PROFILE_IMAGE_UPLOADED";
    public static final String STAFF_PROFILE_CHANGE_REQUESTED = "STAFF_PROFILE_CHANGE_REQUESTED";
    public static final String STAFF_PROFILE_CHANGE_APPROVED = "STAFF_PROFILE_CHANGE_APPROVED";
    public static final String STAFF_PROFILE_CHANGE_REJECTED = "STAFF_PROFILE_CHANGE_REJECTED";
    public static final String STAFF_VERIFIED = "STAFF_VERIFIED";
    public static final String STAFF_VERIFICATION_REJECTED = "STAFF_VERIFICATION_REJECTED";

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

    /** A nightly/on-demand statistical-insights computation run (statistical-insights-engine). */
    public static final String INSIGHTS_COMPUTED = "INSIGHTS_COMPUTED";

    // Customer records & internal CRM depth (FEATURE-ROADMAP §1).
    public static final String CUSTOMER_NOTE_ADDED = "CUSTOMER_NOTE_ADDED";
    public static final String CUSTOMER_TAG_ADDED = "CUSTOMER_TAG_ADDED";
    public static final String CUSTOMER_TAG_REMOVED = "CUSTOMER_TAG_REMOVED";

    // Staff announcement banners (FEATURE-ROADMAP §8.4).
    public static final String ANNOUNCEMENT_CREATED = "ANNOUNCEMENT_CREATED";
    public static final String ANNOUNCEMENT_UPDATED = "ANNOUNCEMENT_UPDATED";
    public static final String ANNOUNCEMENT_DELETED = "ANNOUNCEMENT_DELETED";

    // Sales targets & incentives (FEATURE-ROADMAP §6.1).
    public static final String SALES_TARGET_SET = "SALES_TARGET_SET";

    // Customizable WhatsApp message templates (V44).
    public static final String WHATSAPP_TEMPLATE_CREATED = "WHATSAPP_TEMPLATE_CREATED";
    public static final String WHATSAPP_TEMPLATE_UPDATED = "WHATSAPP_TEMPLATE_UPDATED";
    public static final String WHATSAPP_TEMPLATE_DELETED = "WHATSAPP_TEMPLATE_DELETED";

    // Shopify ingestion + QuikShipX fulfilment (V49).

    /** An order was submitted to QuikShipX and a shipment record persisted (Req 15.4). */
    public static final String QUIKSHIPX_PUBLISHED = "QUIKSHIPX_PUBLISHED";

    /** A QuikShipX status update was mirrored onto an order's shipment. */
    public static final String QUIKSHIPX_STATUS_MIRRORED = "QUIKSHIPX_STATUS_MIRRORED";

    /** An order was ingested from a Shopify order webhook (Req 15.3). */
    public static final String SHOPIFY_ORDER_INGESTED = "SHOPIFY_ORDER_INGESTED";

    /** An ADMIN returned fulfilment authority for one order to Shifa OMS (Req 13.5). */
    public static final String ORDER_FALLBACK_MODE_ENABLED = "ORDER_FALLBACK_MODE_ENABLED";

    /** An ADMIN replayed a stored integration event (Req 14.4). */
    public static final String INTEGRATION_EVENT_REPLAYED = "INTEGRATION_EVENT_REPLAYED";

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
    public static final String ENTITY_INSIGHT = "INSIGHT";
    public static final String ENTITY_CUSTOMER = "CUSTOMER";
    public static final String ENTITY_ANNOUNCEMENT = "ANNOUNCEMENT";
    public static final String ENTITY_SALES_TARGET = "SALES_TARGET";
    public static final String ENTITY_WHATSAPP_TEMPLATE = "WHATSAPP_TEMPLATE";
}
