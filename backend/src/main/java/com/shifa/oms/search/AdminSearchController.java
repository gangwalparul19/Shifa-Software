package com.shifa.oms.search;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin global-search endpoint (ROADMAP 2.2 "Wave 2").
 *
 * <p>{@code GET /api/admin/search?q=} returns a unified, capped, read-only set
 * of matching orders, products, and customers for the admin omni-search.
 * Restricted to authenticated staff roles (any admin/back-office role, never a
 * storefront {@code CUSTOMER}) via method security.
 */
@RestController
@RequestMapping("/api/admin/search")
@PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','SALESPERSON','PACKING_USER','CA')")
public class AdminSearchController {

    private final GlobalSearchService globalSearchService;

    public AdminSearchController(GlobalSearchService globalSearchService) {
        this.globalSearchService = globalSearchService;
    }

    /**
     * Unified search across orders, products, and customers.
     *
     * @param q the search term; blank returns empty groups
     * @return grouped, capped matches
     */
    @GetMapping
    public GlobalSearchResponse search(@RequestParam(required = false) String q) {
        return globalSearchService.search(q);
    }
}
