package com.shifa.oms.product;

/**
 * Catalog visibility flag controlling catalog exposure (Requirement 6.4).
 * HIDDEN products are excluded from order-entry pickers.
 *
 * <ul>
 *   <li>{@link #PUBLISHED} — visible in the catalog, search and order-entry pickers.</li>
 *   <li>{@link #HIDDEN} — excluded from catalog/search/detail responses and from
 *       order-entry pickers; visible only to admins.</li>
 * </ul>
 */
public enum ProductVisibility {
    PUBLISHED,
    HIDDEN
}
