package com.shifa.oms.crm;

import com.shifa.oms.common.PageRequests;
import com.shifa.oms.common.PageResponse;
import com.shifa.oms.crm.dto.CustomerDetailResponse;
import com.shifa.oms.crm.dto.CustomerSummaryResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Customer CRM read endpoints ({@code /api/admin/customers}, "operations depth"
 * Feature 1).
 *
 * <p>Restricted to {@code ADMIN} and {@code ACCOUNTANT} via method security;
 * unauthenticated callers get 401 and other roles 403 (standard error envelope).
 * A customer is keyed by mobile and aggregated across the orders table — no new
 * table is introduced.
 */
@RestController
@RequestMapping("/api/admin/customers")
@PreAuthorize("hasAnyRole('ADMIN', 'ACCOUNTANT')")
public class CustomerController {

    /** Whitelist of API sort fields → native SELECT aliases for the customers table. */
    private static final Map<String, String> SORT_WHITELIST = Map.of(
            "totalSpent", "totalSpent",
            "orderCount", "orderCount",
            "lastOrderAt", "lastOrderAt",
            "firstOrderAt", "firstOrderAt",
            "mobile", "mobile");

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "lastOrderAt");

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    /**
     * Paged customer summaries aggregated across orders.
     *
     * @param q    name / mobile substring filter (optional)
     * @param page zero-based page index (default 0)
     * @param size page size (default 20, capped at 100)
     * @param sort {@code field,dir} — one of totalSpent/orderCount/lastOrderAt/firstOrderAt/mobile
     *             (default {@code lastOrderAt,desc})
     */
    @GetMapping
    public PageResponse<CustomerSummaryResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = PageRequests.of(page, size, sort, SORT_WHITELIST, DEFAULT_SORT);
        return customerService.list(q, pageable);
    }

    /**
     * A single customer's detail by mobile: the summary plus their order history
     * (newest first). 404 when no order exists for the mobile.
     */
    @GetMapping("/{mobile}")
    public CustomerDetailResponse get(@PathVariable String mobile) {
        return customerService.get(mobile);
    }
}
