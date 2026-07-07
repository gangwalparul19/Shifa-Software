package com.shifa.oms.returns;

import com.shifa.oms.common.PageRequests;
import com.shifa.oms.common.PageResponse;
import com.shifa.oms.returns.dto.ApproveReturnRequest;
import com.shifa.oms.returns.dto.CreateReturnRequest;
import com.shifa.oms.returns.dto.RefundReturnRequest;
import com.shifa.oms.returns.dto.RejectReturnRequest;
import com.shifa.oms.returns.dto.ReturnResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * Admin returns / refunds / RTO workflow endpoints ({@code /api/admin/returns},
 * "operations depth" Feature 1).
 *
 * <p>Reads and the refund-marking action are open to {@code ADMIN} and
 * {@code ACCOUNTANT} (the accountant owns settlement/refund tracking); the
 * create / approve / reject workflow actions are {@code ADMIN}-only. Requests use
 * the standard error envelope; illegal lifecycle transitions are rejected with a
 * 400 validation error.
 */
@RestController
@RequestMapping("/api/admin/returns")
@PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
public class AdminReturnController {

    /** Whitelist of API sort fields → JPA properties for the returns table. */
    private static final Map<String, String> SORT_WHITELIST = Map.of(
            "createdAt", "createdAt",
            "updatedAt", "updatedAt",
            "status", "status",
            "refundAmount", "refundAmount");

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    private final ReturnService returnService;

    public AdminReturnController(ReturnService returnService) {
        this.returnService = returnService;
    }

    /**
     * Filtered, paged, newest-first return listing (ADMIN or ACCOUNTANT).
     *
     * @param status exact return status filter (optional)
     * @param q      case-insensitive substring over reason / notes (optional)
     * @param from   inclusive lower-bound date, ISO {@code yyyy-MM-dd} (optional)
     * @param to     inclusive upper-bound date, ISO {@code yyyy-MM-dd} (optional)
     * @param page   zero-based page index (default 0)
     * @param size   page size (default 20, capped at 100)
     * @param sort   {@code field,dir} — one of createdAt/updatedAt/status/refundAmount
     */
    @GetMapping
    public PageResponse<ReturnResponse> list(
            @RequestParam(required = false) ReturnStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = PageRequests.of(page, size, sort, SORT_WHITELIST, DEFAULT_SORT);
        LocalDateTime fromTs = from != null ? from.atStartOfDay() : null;
        LocalDateTime toTs = to != null ? to.atTime(LocalTime.MAX) : null;
        return returnService.list(status, q, fromTs, toTs, pageable);
    }

    /** A single return by id (ADMIN or ACCOUNTANT). */
    @GetMapping("/{id}")
    public ReturnResponse get(@PathVariable Long id) {
        return returnService.get(id);
    }

    /** All returns for a given order, newest first (ADMIN or ACCOUNTANT). */
    @GetMapping("/by-order/{orderId}")
    public List<ReturnResponse> byOrder(@PathVariable Long orderId) {
        return returnService.getByOrder(orderId);
    }

    /** Creates a new return for a returnable order (ADMIN only). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ReturnResponse create(@Valid @RequestBody CreateReturnRequest request) {
        return returnService.create(request.orderId(), request.reason(), request.notes());
    }

    /** Approves a requested return, optionally restocking + recording a refund (ADMIN only). */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ReturnResponse approve(@PathVariable Long id, @Valid @RequestBody ApproveReturnRequest request) {
        return returnService.approve(id, request.restock(), request.refundAmount());
    }

    /** Rejects a requested return (ADMIN only). */
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ReturnResponse reject(@PathVariable Long id, @Valid @RequestBody RejectReturnRequest request) {
        return returnService.reject(id, request.notes());
    }

    /** Marks an approved return as refunded (ADMIN or ACCOUNTANT). */
    @PostMapping("/{id}/refund")
    public ReturnResponse refund(@PathVariable Long id, @Valid @RequestBody RefundReturnRequest request) {
        return returnService.markRefunded(id, request.refundAmount());
    }
}
