package com.shifa.oms.finance;

import com.shifa.oms.audit.AuditEvent;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.common.PageResponse;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.finance.dto.ExpenseRequest;
import com.shifa.oms.finance.dto.ExpenseResponse;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import com.shifa.oms.platform.outbox.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Example-based unit tests for {@link ExpenseService} (Feature C3). The
 * repository is a Mockito mock; the concrete {@link AuditService} is a
 * hand-written no-op fake, and a real {@link CurrentUserService} is used.
 */
@ExtendWith(MockitoExtension.class)
class ExpenseServiceTest {

    @Mock
    private ExpenseRepository expenseRepository;

    private ExpenseService service;

    @BeforeEach
    void setUp() {
        service = new ExpenseService(expenseRepository, new NoopAuditService(), new CurrentUserService(),
                new OutboxEventPublisher(mock(OutboxEventRepository.class)));
        lenient().when(expenseRepository.save(any(Expense.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createStoresExpense() {
        ExpenseResponse response = service.create(new ExpenseRequest(
                "RENT", "Warehouse rent", new BigDecimal("15000.00"), LocalDate.of(2024, 1, 5)));

        assertThat(response.category()).isEqualTo("RENT");
        assertThat(response.amount()).isEqualByComparingTo("15000.00");
        assertThat(response.incurredOn()).isEqualTo(LocalDate.of(2024, 1, 5));
    }

    @Test
    void deleteMissingExpenseIsNotFound() {
        when(expenseRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(9L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listReturnsPagedEnvelopeUsingFilter() {
        Expense expense = new Expense("MARKETING", null, new BigDecimal("500.00"),
                LocalDate.of(2024, 1, 10), null);
        org.springframework.data.domain.Pageable pageable =
                org.springframework.data.domain.PageRequest.of(0, 20);
        when(expenseRepository.search(any(), any(), any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        List.of(expense), pageable, 1));

        PageResponse<ExpenseResponse> page = service.list("MARKETING",
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31), pageable);

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content()).singleElement()
                .satisfies(e -> assertThat(e.category()).isEqualTo("MARKETING"));
    }

    /** A no-op audit service so best-effort auditing never interferes with the test. */
    private static final class NoopAuditService extends AuditService {
        NoopAuditService() {
            super(null, null);
        }

        @Override
        public AuditEvent record(String action, String entityType, String entityId, String summary) {
            return null;
        }
    }
}
