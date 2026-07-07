package com.shifa.oms.finance;

import com.shifa.oms.common.PageRequests;
import com.shifa.oms.common.PageResponse;
import com.shifa.oms.finance.dto.ExpenseRequest;
import com.shifa.oms.finance.dto.ExpenseResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * Admin expense endpoints ({@code /api/admin/expenses}, Feature C3). Open to
 * ADMIN and ACCOUNTANT (the accountant owns finance).
 */
@RestController
@RequestMapping("/api/admin/expenses")
@PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
public class ExpenseController {

    /** Whitelist of API sort fields → JPA properties for the expenses table. */
    private static final Map<String, String> SORT_WHITELIST = Map.of(
            "incurredOn", "incurredOn",
            "createdAt", "createdAt",
            "amount", "amount",
            "category", "category");

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "incurredOn");

    private final ExpenseService expenseService;

    public ExpenseController(ExpenseService expenseService) {
        this.expenseService = expenseService;
    }

    /**
     * Filtered, paged expense listing (ADMIN or ACCOUNTANT).
     *
     * @param category exact category filter (optional)
     * @param from     inclusive lower-bound incurred date, ISO {@code yyyy-MM-dd} (optional)
     * @param to       inclusive upper-bound incurred date, ISO {@code yyyy-MM-dd} (optional)
     * @param page     zero-based page index (default 0)
     * @param size     page size (default 20, capped at 100)
     * @param sort     {@code field,dir} — incurredOn/createdAt/amount/category
     */
    @GetMapping
    public PageResponse<ExpenseResponse> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = PageRequests.of(page, size, sort, SORT_WHITELIST, DEFAULT_SORT);
        return expenseService.list(category, from, to, pageable);
    }

    /** A single expense by id (ADMIN or ACCOUNTANT). */
    @GetMapping("/{id}")
    public ExpenseResponse get(@PathVariable Long id) {
        return expenseService.get(id);
    }

    /** Records a new expense (ADMIN or ACCOUNTANT). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ExpenseResponse create(@Valid @RequestBody ExpenseRequest request) {
        return expenseService.create(request);
    }

    /** Deletes an expense (ADMIN or ACCOUNTANT). */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        expenseService.delete(id);
    }
}
