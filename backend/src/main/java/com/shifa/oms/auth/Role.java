package com.shifa.oms.auth;

/**
 * The five platform user roles (Requirement 5.1).
 *
 * <p>Each role maps to a Spring Security authority of the form
 * {@code ROLE_<name>} (see {@link #authority()}), so method-level checks such as
 * {@code @PreAuthorize("hasRole('ADMIN')")} line up with the persisted role.
 */
public enum Role {

    /** Full access to all management, approval, and configuration functions (Req 5.4). */
    ADMIN,

    /** Payment reconciliation and settlement functions. */
    ACCOUNTANT,

    /** Order entry and reports scoped to their own orders (Req 5.5). */
    SALESPERSON,

    /**
     * A sales team lead. Read-only oversight of the salespeople assigned to them
     * (via {@code users.team_lead_id}): sees the orders punched by their team
     * (list / search / detail / invoice) and a team-scoped dashboard. Cannot
     * approve/dispatch/mutate orders or configure the system.
     */
    TEAM_LEAD,

    /** Packing / barcode-scan functions. */
    PACKING_USER,

    /**
     * Verifies the authenticity of customer payments (screenshot vs. amount) and
     * handles customer engagement, via a dedicated Payment dashboard
     * (product-audit §4.4). Cannot approve/dispatch orders.
     */
    PAYMENT_VERIFIER,

    /** Storefront customer. */
    CUSTOMER;

    /** The Spring Security authority string for this role ({@code ROLE_<name>}). */
    public String authority() {
        return "ROLE_" + name();
    }
}
