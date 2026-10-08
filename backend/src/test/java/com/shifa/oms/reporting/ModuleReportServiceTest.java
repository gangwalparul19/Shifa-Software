package com.shifa.oms.reporting;

import com.shifa.oms.finance.Expense;
import com.shifa.oms.finance.ExpenseRepository;
import com.shifa.oms.inventory.StockMovementRepository;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.procurement.PurchaseOrderRepository;
import com.shifa.oms.procurement.SupplierRepository;
import com.shifa.oms.product.ProductRepository;
import com.shifa.oms.reporting.domain.ReportType;
import com.shifa.oms.reporting.dto.ReportResponse;
import com.shifa.oms.returns.OrderReturnRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Focused unit tests for {@link ModuleReportService} — the per-module operational
 * reports (expenses / procurement / returns / inventory). Verifies the column
 * contract (headers the exporters reproduce) and, for expenses, the row mapping,
 * date-window filtering, and oldest-first ordering. Repositories are mocked so
 * the tests run without a database.
 */
@ExtendWith(MockitoExtension.class)
class ModuleReportServiceTest {

    @Mock private ExpenseRepository expenseRepository;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private OrderReturnRepository orderReturnRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private StockMovementRepository stockMovementRepository;
    @Mock private ProductRepository productRepository;

    private ModuleReportService service() {
        return new ModuleReportService(expenseRepository, purchaseOrderRepository, supplierRepository,
                orderReturnRepository, orderRepository, stockMovementRepository, productRepository);
    }

    @Test
    void expensesReportMapsRowsWindowedAndOldestFirst() {
        when(expenseRepository.findAll()).thenReturn(List.of(
                new Expense("Marketing", "Ads", new BigDecimal("2000.00"), LocalDate.of(2025, 3, 5), 1L),
                new Expense("Rent", "Office", new BigDecimal("5000.00"), LocalDate.of(2025, 3, 1), 1L),
                // Outside the window — must be excluded.
                new Expense("Misc", "Old", new BigDecimal("100.00"), LocalDate.of(2025, 2, 1), 1L)));

        ReportResponse res = service().generate(
                ReportType.EXPENSES, LocalDate.of(2025, 3, 1), LocalDate.of(2025, 3, 31));

        assertThat(res.headers()).containsExactly("Date", "Category", "Amount", "Description");
        assertThat(res.rows()).hasSize(2);
        // Oldest first: Rent (Mar 1) then Marketing (Mar 5).
        assertThat(res.rows().get(0)).containsExactly("2025-03-01", "Rent", "5000.00", "Office");
        assertThat(res.rows().get(1)).containsExactly("2025-03-05", "Marketing", "2000.00", "Ads");
    }

    @Test
    void purchaseOrdersReportHasContractHeaders() {
        when(purchaseOrderRepository.findAll()).thenReturn(List.of());
        ReportResponse res = service().generate(ReportType.PURCHASE_ORDERS, null, null);
        assertThat(res.headers())
                .containsExactly("PO Number", "Supplier", "Status", "Total", "Ordered On", "Received On");
        assertThat(res.rows()).isEmpty();
    }

    @Test
    void returnsReportHasContractHeaders() {
        when(orderReturnRepository.findAll()).thenReturn(List.of());
        ReportResponse res = service().generate(ReportType.RETURNS, null, null);
        assertThat(res.headers())
                .containsExactly("Order", "Reason", "Status", "Refund", "Restocked", "Created On");
        assertThat(res.rows()).isEmpty();
    }

    @Test
    void stockReportHasContractHeaders() {
        when(stockMovementRepository.findAll()).thenReturn(List.of());
        ReportResponse res = service().generate(ReportType.STOCK, null, null);
        assertThat(res.headers())
                .containsExactly("Date", "Product", "Type", "Change", "Balance", "Reason");
        assertThat(res.rows()).isEmpty();
    }
}
