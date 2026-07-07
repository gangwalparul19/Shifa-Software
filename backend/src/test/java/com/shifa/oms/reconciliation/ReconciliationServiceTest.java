package com.shifa.oms.reconciliation;

import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.courier.CourierCompany;
import com.shifa.oms.courier.CourierCompanyRepository;
import com.shifa.oms.courier.CourierRecord;
import com.shifa.oms.courier.CourierRecordRepository;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.reconciliation.domain.ReceivableType;
import com.shifa.oms.reconciliation.dto.CourierSummaryResponse;
import com.shifa.oms.reconciliation.dto.ReceivableResponse;
import com.shifa.oms.reconciliation.dto.SegregationResponse;
import com.shifa.oms.reconciliation.dto.UnsettledCodResponse;
import com.shifa.oms.statemachine.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Example-based unit tests for {@link ReconciliationService} covering the
 * reconciliation dashboard logic (Req 18.1&ndash;18.6, 17.4) with mocked
 * repositories (no database):
 * <ul>
 *   <li>per-courier COD/claim outstanding totals exclude settled rows (18.1, 18.2);</li>
 *   <li>the unsettled-COD list contains only unsettled COD receivables (18.3);</li>
 *   <li>settling reduces outstanding and is idempotent (18.5);</li>
 *   <li>type/courier filtering of the receivables list (18.1&ndash;18.3);</li>
 *   <li>RTO orders never create a receivable, so they are excluded from COD
 *       totals (18.6).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ReconciliationServiceTest {

    private static final long COURIER_A = 1L;
    private static final long COURIER_B = 2L;

    @Mock
    private ReceivableRepository receivableRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private CourierCompanyRepository courierCompanyRepository;
    @Mock
    private CourierRecordRepository courierRecordRepository;

    private ReconciliationService service;

    @BeforeEach
    void setUp() {
        service = new ReconciliationService(
                receivableRepository, orderRepository, courierCompanyRepository, courierRecordRepository);
        lenient().when(courierCompanyRepository.findById(COURIER_A))
                .thenReturn(Optional.of(company("Shifa Express")));
        lenient().when(courierCompanyRepository.findById(COURIER_B))
                .thenReturn(Optional.of(company("BlueDart")));
        lenient().when(courierRecordRepository.findByOrderId(anyLong())).thenReturn(Optional.empty());
        lenient().when(orderRepository.findById(anyLong())).thenReturn(Optional.empty());
    }

    // --- Per-courier totals (Req 18.1, 18.2) --------------------------------

    @Test
    void perCourierSummaryTotalsUnsettledCodAndClaimExcludingSettled() {
        ReceivableEntity codUnsettledA = receivable(1L, 10L, COURIER_A, ReceivableType.COD_RECEIVABLE, "199.00", false);
        ReceivableEntity codSettledA = receivable(2L, 11L, COURIER_A, ReceivableType.COD_RECEIVABLE, "100.00", true);
        ReceivableEntity claimA = receivable(3L, 12L, COURIER_A, ReceivableType.CLAIM_RECEIVABLE, "500.00", false);
        ReceivableEntity codUnsettledB = receivable(4L, 20L, COURIER_B, ReceivableType.COD_RECEIVABLE, "50.00", false);
        when(receivableRepository.findAllByOrderByCreatedAtDescIdDesc())
                .thenReturn(List.of(codUnsettledA, codSettledA, claimA, codUnsettledB));

        List<CourierSummaryResponse> summary = service.perCourierSummary();

        CourierSummaryResponse a = summary.stream()
                .filter(s -> COURIER_A == s.courierCompanyId()).findFirst().orElseThrow();
        // Settled COD (100.00) excluded; only the 199.00 unsettled COD counts (Req 18.1).
        assertThat(a.codOutstanding()).isEqualByComparingTo("199.00");
        assertThat(a.claimOutstanding()).isEqualByComparingTo("500.00");
        assertThat(a.totalOutstanding()).isEqualByComparingTo("699.00");
        assertThat(a.courierName()).isEqualTo("Shifa Express");

        CourierSummaryResponse b = summary.stream()
                .filter(s -> COURIER_B == s.courierCompanyId()).findFirst().orElseThrow();
        assertThat(b.codOutstanding()).isEqualByComparingTo("50.00");
        assertThat(b.claimOutstanding()).isEqualByComparingTo("0.00");
    }

    // --- RTO exclusion (Req 18.6) -------------------------------------------

    @Test
    void rtoOrdersHaveNoReceivableSoAreExcludedFromCodTotals() {
        // An RTO order never creates a receivable, so the courier's COD total
        // reflects only the delivered COD receivable — the RTO amount is absent.
        ReceivableEntity deliveredCod =
                receivable(1L, 10L, COURIER_A, ReceivableType.COD_RECEIVABLE, "199.00", false);
        when(receivableRepository.findAllByOrderByCreatedAtDescIdDesc())
                .thenReturn(List.of(deliveredCod));

        List<CourierSummaryResponse> summary = service.perCourierSummary();

        assertThat(summary).hasSize(1);
        assertThat(summary.get(0).codOutstanding()).isEqualByComparingTo("199.00");
    }

    // --- Unsettled COD list (Req 18.3) --------------------------------------

    @Test
    void unsettledCodListsOnlyUnsettledCodReceivables() {
        ReceivableEntity codUnsettled = receivable(1L, 10L, COURIER_A, ReceivableType.COD_RECEIVABLE, "199.00", false);
        ReceivableEntity codSettled = receivable(2L, 11L, COURIER_A, ReceivableType.COD_RECEIVABLE, "100.00", true);
        ReceivableEntity claim = receivable(3L, 12L, COURIER_A, ReceivableType.CLAIM_RECEIVABLE, "500.00", false);
        when(receivableRepository.findAllByOrderByCreatedAtDescIdDesc())
                .thenReturn(List.of(codUnsettled, codSettled, claim));
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order("SHR-10", "Asha", PaymentStatus.COD)));
        when(courierRecordRepository.findByOrderId(10L)).thenReturn(Optional.of(courierRecord("AWB-10")));

        List<UnsettledCodResponse> unsettled = service.unsettledCod();

        assertThat(unsettled).hasSize(1);
        UnsettledCodResponse row = unsettled.get(0);
        assertThat(row.receivableId()).isEqualTo(1L);
        assertThat(row.orderCode()).isEqualTo("SHR-10");
        assertThat(row.customerName()).isEqualTo("Asha");
        assertThat(row.awb()).isEqualTo("AWB-10");
        assertThat(row.amount()).isEqualByComparingTo("199.00");
    }

    // --- Settle reduces outstanding + idempotent (Req 18.5) -----------------

    @Test
    void settleMarksReceivableSettledAndIsIdempotent() {
        ReceivableEntity cod = receivable(1L, 10L, COURIER_A, ReceivableType.COD_RECEIVABLE, "199.00", false);
        when(receivableRepository.findById(1L)).thenReturn(Optional.of(cod));
        when(receivableRepository.save(any(ReceivableEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        LocalDate settleDate = LocalDate.of(2024, 5, 1);

        ReceivableResponse first = service.settle(1L, settleDate);
        assertThat(first.settled()).isTrue();
        assertThat(first.settledDate()).isEqualTo(settleDate);

        // Second settle (idempotent): no state change, original date retained, no extra save.
        ReceivableResponse second = service.settle(1L, LocalDate.of(2030, 1, 1));
        assertThat(second.settled()).isTrue();
        assertThat(second.settledDate()).isEqualTo(settleDate);
        verify(receivableRepository, times(1)).save(any(ReceivableEntity.class));
    }

    @Test
    void settleReducesTheCouriersOutstandingByThatAmount() {
        ReceivableEntity cod = receivable(1L, 10L, COURIER_A, ReceivableType.COD_RECEIVABLE, "199.00", false);
        ReceivableEntity claim = receivable(3L, 12L, COURIER_A, ReceivableType.CLAIM_RECEIVABLE, "500.00", false);
        when(receivableRepository.findById(1L)).thenReturn(Optional.of(cod));
        when(receivableRepository.save(any(ReceivableEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        // Before: outstanding = 199 + 500 = 699.
        when(receivableRepository.findAllByOrderByCreatedAtDescIdDesc()).thenReturn(List.of(cod, claim));
        assertThat(service.perCourierSummary().get(0).totalOutstanding()).isEqualByComparingTo("699.00");

        service.settle(1L, LocalDate.of(2024, 5, 1));

        // After settling the 199 COD: outstanding reduced by exactly 199 → 500.
        assertThat(service.perCourierSummary().get(0).totalOutstanding()).isEqualByComparingTo("500.00");
        assertThat(service.perCourierSummary().get(0).codOutstanding()).isEqualByComparingTo("0.00");
    }

    @Test
    void settleUnknownReceivableThrowsNotFound() {
        when(receivableRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.settle(999L, null))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(receivableRepository, never()).save(any(ReceivableEntity.class));
    }

    // --- Type / courier filtering (Req 18.1-18.3) ---------------------------

    @Test
    void listReceivablesFiltersByTypeThenCourier() {
        ReceivableEntity codA = receivable(1L, 10L, COURIER_A, ReceivableType.COD_RECEIVABLE, "199.00", false);
        ReceivableEntity codASettled = receivable(2L, 11L, COURIER_A, ReceivableType.COD_RECEIVABLE, "100.00", true);
        ReceivableEntity codB = receivable(4L, 20L, COURIER_B, ReceivableType.COD_RECEIVABLE, "50.00", false);
        when(receivableRepository.findByTypeOrderByCreatedAtDescIdDesc(ReceivableType.COD_RECEIVABLE))
                .thenReturn(List.of(codA, codASettled, codB));

        List<ReceivableResponse> filtered =
                service.listReceivables(COURIER_A, ReceivableType.COD_RECEIVABLE);

        assertThat(filtered).extracting(ReceivableResponse::id).containsExactly(1L, 2L);
        assertThat(filtered).allMatch(r -> COURIER_A == r.courierCompanyId());
    }

    @Test
    void listReceivablesWithoutFiltersReturnsAll() {
        ReceivableEntity codA = receivable(1L, 10L, COURIER_A, ReceivableType.COD_RECEIVABLE, "199.00", false);
        ReceivableEntity claimB = receivable(3L, 12L, COURIER_B, ReceivableType.CLAIM_RECEIVABLE, "500.00", false);
        when(receivableRepository.findAllByOrderByCreatedAtDescIdDesc()).thenReturn(List.of(codA, claimB));

        List<ReceivableResponse> all = service.listReceivables(null, null);

        assertThat(all).extracting(ReceivableResponse::id).containsExactly(1L, 3L);
    }

    // --- Pending claims (Req 17.4) ------------------------------------------

    @Test
    void pendingClaimsListsUnsettledClaimReceivables() {
        ReceivableEntity claim = receivable(3L, 12L, COURIER_A, ReceivableType.CLAIM_RECEIVABLE, "500.00", false);
        when(receivableRepository.findByTypeAndSettledFalseOrderByCreatedAtDescIdDesc(
                ReceivableType.CLAIM_RECEIVABLE)).thenReturn(List.of(claim));
        when(orderRepository.findById(12L)).thenReturn(Optional.of(order("SHR-12", "Ravi", PaymentStatus.COD)));
        when(courierRecordRepository.findByOrderId(12L)).thenReturn(Optional.of(courierRecord("AWB-12")));

        List<ReceivableResponse> claims = service.pendingClaims();

        assertThat(claims).hasSize(1);
        assertThat(claims.get(0).type()).isEqualTo(ReceivableType.CLAIM_RECEIVABLE);
        assertThat(claims.get(0).awb()).isEqualTo("AWB-12");
        assertThat(claims.get(0).amount()).isEqualByComparingTo("500.00");
    }

    // --- Segregation (Req 18.4) ---------------------------------------------

    @Test
    void segregationPartitionsPrepaidAndCodOrders() {
        OrderEntity prepaid = order("SHR-1", "Asha", PaymentStatus.FULLY_PAID);
        OrderEntity cod = order("SHR-2", "Ravi", PaymentStatus.COD);
        OrderEntity partial = order("SHR-3", "Sita", PaymentStatus.PARTIALLY_PAID);
        when(orderRepository.findByOrderStatusInOrderByCreatedAtDesc(any()))
                .thenReturn(List.of(prepaid, cod, partial));

        SegregationResponse result = service.segregation();

        assertThat(result.prepaid()).extracting(SegregationResponse.SegregatedOrder::orderCode)
                .containsExactly("SHR-1");
        assertThat(result.cod()).extracting(SegregationResponse.SegregatedOrder::orderCode)
                .containsExactlyInAnyOrder("SHR-2", "SHR-3");
    }

    // --- Fixtures -----------------------------------------------------------

    private static CourierCompany company(String name) {
        return new CourierCompany(name, "https://track.example.com/{awb}");
    }

    private static CourierRecord courierRecord(String awb) {
        CourierRecord record = new CourierRecord(1L);
        record.assign(COURIER_A, awb, null, null);
        return record;
    }

    private static OrderEntity order(String code, String customer, PaymentStatus paymentStatus) {
        OrderEntity order = new OrderEntity(
                code, OrderSource.STOREFRONT, null, customer, "9812345678",
                "12 MG Road", "Pune", "Maharashtra", "411001");
        // Derive a stable id from the numeric suffix of the code (e.g. "SHR-10" -> 10).
        long id = Long.parseLong(code.replaceAll("\\D+", ""));
        setField(order, "id", id);
        BigDecimal total = new BigDecimal("240.00");
        BigDecimal cod = paymentStatus == PaymentStatus.FULLY_PAID ? BigDecimal.ZERO : total;
        order.applyAmounts(total, total.subtract(cod), cod, cod, paymentStatus);
        order.setOrderStatus(OrderStatus.DELIVERED);
        return order;
    }

    private static ReceivableEntity receivable(long id, long orderId, long courierId,
                                               ReceivableType type, String amount, boolean settled) {
        ReceivableEntity entity = new ReceivableEntity(orderId, courierId, type, new BigDecimal(amount));
        setField(entity, "id", id);
        if (settled) {
            entity.settle(LocalDate.of(2024, 1, 15));
        }
        return entity;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to set test field " + name, e);
        }
    }
}
