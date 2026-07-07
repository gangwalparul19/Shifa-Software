package com.shifa.oms.finance;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.common.PageResponse;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.finance.dto.ExpenseRequest;
import com.shifa.oms.finance.dto.ExpenseResponse;
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

    private final ExpenseRepository expenseRepository;
    private final AuditService auditService;
    private final CurrentUserService currentUserService;

    public ExpenseService(ExpenseRepository expenseRepository,
                          AuditService auditService,
                          CurrentUserService currentUserService) {
        this.expenseRepository = expenseRepository;
        this.auditService = auditService;
        this.currentUserService = currentUserService;
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
