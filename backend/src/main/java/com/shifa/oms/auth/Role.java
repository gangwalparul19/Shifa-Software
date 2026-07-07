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

    /** Packing / barcode-scan functions. */
    PACKING_USER,

    /** Storefront customer. */
    CUSTOMER;

    /** The Spring Security authority string for this role ({@code ROLE_<name>}). */
    public String authority() {
        return "ROLE_" + name();
    }
}
