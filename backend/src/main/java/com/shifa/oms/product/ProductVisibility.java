package com.shifa.oms.product;

/**
 * Storefront visibility flag controlling catalog exposure (Requirement 6.4).
 *
 * <ul>
 *   <li>{@link #PUBLISHED} — visible in the public catalog and search.</li>
 *   <li>{@link #HIDDEN} — excluded from all public catalog/search/detail
 *       responses; visible only to admins.</li>
 * </ul>
 */
public enum ProductVisibility {
    PUBLISHED,
    HIDDEN
}
