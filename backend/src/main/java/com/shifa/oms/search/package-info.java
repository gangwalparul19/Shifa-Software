/**
 * Admin global-search module (ROADMAP 2.2 "Wave 2").
 *
 * <p>Exposes a single read-only endpoint, {@code GET /api/admin/search?q=}, that
 * returns a unified, capped set of matching orders, products, and customers for
 * the admin omni-search. It owns no persistence of its own: the
 * {@link com.shifa.oms.search.GlobalSearchService} composes existing repository
 * finders across the order, product, and auth modules and caps each group.
 */
package com.shifa.oms.search;
