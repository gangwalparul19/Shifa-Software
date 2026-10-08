package com.shifa.oms.finance;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.common.PageResponse;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.finance.dto.ExpenseRequest;
import com.shifa.oms.finance.dto.ExpenseResponse;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Expense application service (Feature C3).
 *
 * <p>Owns CRUD for manually recorded business expenses plus the filtered, paged
 * listing that backs {@code GET /api/admin/expenses}. Creation records a
 * best-effort {@code EXPENSE_ADDED} audit event; deletion records
 * {@code EXPENSE_DELETED}.
 */
@Service
public class ExpenseService {

    /** {@code vouchers.source_type} for a recorded expense (matches {@code SourceType.EXPENSE}). */
    private static final String LEDGER_SOURCE_EXPENSE = "EXPENSE";

    private final ExpenseRepository expenseRepository;
    private final AuditService auditService;
    private final CurrentUserService currentUserService;
    private final OutboxEventPublisher outboxEventPublisher;

    public ExpenseService(ExpenseRepository expenseRepository,
                          AuditService auditService,
                          CurrentUserService currentUserService,
                          OutboxEventPublisher outboxEventPublisher) {
        this.expenseRepository = expenseRepository;
        this.auditService = auditService;
        this.currentUserService = currentUserService;
        this.outboxEventPublisher = outboxEventPublisher;
    }

    /** Records a new expense. */
    @Transactional
    public ExpenseResponse create(ExpenseRequest request) {
        Expense expense = new Expense(
                request.category().trim(), request.description(), request.amount(),
                request.incurredOn(), currentUserId());
        Expense saved = expenseRepository.save(expense);
        auditService.record(AuditActions.EXPENSE_ADDED, AuditActions.ENTITY_EXPENSE,
                String.valueOf(saved.getId()),
                "Expense added: " + saved.getCategory() + " " + saved.getAmount()
                        + " on " + saved.getIncurredOn());
        // Auto-posting (Reqs 10.1, 17.3, 17.4): enqueue a ledger-post event in this same
        // transaction so the General Ledger derives the balanced expense voucher out-of-band. The
        // event row commits atomically with the expense; a downstream posting failure can never
        // roll back or alter this expense (additive — no change to existing behaviour/return value).
        outboxEventPublisher.publishLedgerPost(LEDGER_SOURCE_EXPENSE, saved.getId());
        return ExpenseResponse.from(saved);
    }

    /** Deletes an expense by id (or a 404 if it does not exist). */
    @Transactional
    public void delete(Long id) {
        Expense expense = expenseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Expense " + id + " does not exist."));
        expenseRepository.delete(expense);
        auditService.record(AuditActions.EXPENSE_DELETED, AuditActions.ENTITY_EXPENSE,
                String.valueOf(id),
                "Expense deleted: " + expense.getCategory() + " " + expense.getAmount());
    }

    /**
     * Filtered, paged expense listing (by category and/or {@code incurred_on}
     * date range).
     *
     * @param category exact category filter (nullable/blank → no filter)
     * @param from     inclusive lower-bound incurred date (nullable)
     * @param to       inclusive upper-bound incurred date (nullable)
     * @param pageable page / size / sort
     */
    @Transactional(readOnly = true)
    public PageResponse<ExpenseResponse> list(String category, LocalDate from, LocalDate to,
                                              Pageable pageable) {
        Page<Expense> page = expenseRepository.search(blankToNull(category), from, to, pageable);
        return PageResponse.of(page, ExpenseResponse::from);
    }

    /** A single expense by id, or a 404. */
    @Transactional(readOnly = true)
    public ExpenseResponse get(Long id) {
        Expense expense = expenseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Expense " + id + " does not exist."));
        return ExpenseResponse.from(expense);
    }

    private Long currentUserId() {
        AuthPrincipal principal = currentUserService.currentUser().orElse(null);
        return principal != null ? principal.userId() : null;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
