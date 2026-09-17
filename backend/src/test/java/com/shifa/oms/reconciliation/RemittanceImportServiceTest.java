package com.shifa.oms.reconciliation;

import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.courier.CourierRecord;
import com.shifa.oms.courier.CourierRecordRepository;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.reconciliation.domain.ReceivableType;
import com.shifa.oms.reconciliation.dto.RemittanceImportResponse;
import com.shifa.oms.reconciliation.dto.RemittanceRowResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RemittanceImportService} (enhancement: "courier
 * remittance import & auto-match") — matching, tolerance, dry-run vs commit,
 * and malformed-row handling, all against mocked repositories (no database).
 */
@ExtendWith(MockitoExtension.class)
class RemittanceImportServiceTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private CourierRecordRepository courierRecordRepository;
    @Mock
    private ReceivableRepository receivableRepository;

    private RemittanceImportService service;

    @BeforeEach
    void setUp() {
        AuditService auditService = new NoopAuditService();
        service = new RemittanceImportService(
                orderRepository, courierRecordRepository, receivableRepository, auditService);
        lenient().when(receivableRepository.save(any(ReceivableEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private OrderEntity order(long id, String code) {
        OrderEntity o = new OrderEntity(
                code, OrderSource.STOREFRONT, null,
                "Asha", "9812345678", "12 MG Road", "Pune", "Maharashtra", "411001");
        ReflectionTestUtils.setField(o, "id", id);
        return o;
    }

    private ReceivableEntity codReceivable(long id, long orderId, String amount) {
        ReceivableEntity e = new ReceivableEntity(orderId, 1L, ReceivableType.COD_RECEIVABLE, new BigDecimal(amount));
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }

    private byte[] csv(String... lines) {
        return String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
    }

    // --- Matching by AWB, settling on agreement -----------------------------

    @Test
    void settlesRowThatMatchesByAwbWithAgreeingAmount() {
        OrderEntity order = order(10L, "SHR-1001");
        CourierRecord record = new CourierRecord(10L);
        record.assign(1L, "AWB123", null, null);
        when(courierRecordRepository.findByAwb("AWB123")).thenReturn(Optional.of(record));
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));
        ReceivableEntity receivable = codReceivable(1L, 10L, "500.00");
        when(receivableRepository.findByOrderIdAndType(10L, ReceivableType.COD_RECEIVABLE))
                .thenReturn(List.of(receivable));

        RemittanceImportResponse response = service.importCsv(
                csv("awb,orderCode,amount", "AWB123,,500.00"), false);

        assertThat(response.settled()).isEqualTo(1);
        assertThat(response.rows().get(0).status()).isEqualTo(RemittanceRowResult.Status.SETTLED);
        assertThat(receivable.isSettled()).isTrue();
        verify(receivableRepository).save(receivable);
    }

    @Test
    void dryRunMatchesButDoesNotSettle() {
        OrderEntity order = order(10L, "SHR-1001");
        CourierRecord record = new CourierRecord(10L);
        record.assign(1L, "AWB123", null, null);
        when(courierRecordRepository.findByAwb("AWB123")).thenReturn(Optional.of(record));
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));
        ReceivableEntity receivable = codReceivable(1L, 10L, "500.00");
        when(receivableRepository.findByOrderIdAndType(10L, ReceivableType.COD_RECEIVABLE))
                .thenReturn(List.of(receivable));

        RemittanceImportResponse response = service.importCsv(
                csv("awb,orderCode,amount", "AWB123,,500.00"), true);

        assertThat(response.dryRun()).isTrue();
        assertThat(response.settled()).isEqualTo(1); // reported as "would settle"
        assertThat(receivable.isSettled()).isFalse(); // but nothing actually changed
        verify(receivableRepository, never()).save(any());
    }

    // --- Matching by order code fallback -------------------------------------

    @Test
    void fallsBackToOrderCodeWhenNoAwbMatch() {
        OrderEntity order = order(11L, "SHR-2002");
        when(courierRecordRepository.findByAwb(any())).thenReturn(Optional.empty());
        when(orderRepository.findByOrderCode("SHR-2002")).thenReturn(Optional.of(order));
        ReceivableEntity receivable = codReceivable(2L, 11L, "300.00");
        when(receivableRepository.findByOrderIdAndType(11L, ReceivableType.COD_RECEIVABLE))
                .thenReturn(List.of(receivable));

        RemittanceImportResponse response = service.importCsv(
                csv("awb,orderCode,amount", "UNKNOWN-AWB,SHR-2002,300.00"), false);

        assertThat(response.rows().get(0).status()).isEqualTo(RemittanceRowResult.Status.SETTLED);
        assertThat(receivable.isSettled()).isTrue();
    }

    // --- Amount mismatch: reported, never auto-settled ----------------------

    @Test
    void reportsMismatchWithoutSettlingWhenAmountDiffersBeyondTolerance() {
        OrderEntity order = order(10L, "SHR-1001");
        when(courierRecordRepository.findByAwb("AWB123")).thenReturn(Optional.empty());
        when(orderRepository.findByOrderCode("SHR-1001")).thenReturn(Optional.of(order));
        ReceivableEntity receivable = codReceivable(1L, 10L, "500.00");
        when(receivableRepository.findByOrderIdAndType(10L, ReceivableType.COD_RECEIVABLE))
                .thenReturn(List.of(receivable));

        RemittanceImportResponse response = service.importCsv(
                csv("awb,orderCode,amount", "AWB123,SHR-1001,450.00"), false);

        assertThat(response.mismatched()).isEqualTo(1);
        assertThat(response.rows().get(0).status()).isEqualTo(RemittanceRowResult.Status.MISMATCH);
        assertThat(receivable.isSettled()).isFalse();
        verify(receivableRepository, never()).save(any());
    }

    @Test
    void toleratesASmallRoundingDifference() {
        OrderEntity order = order(10L, "SHR-1001");
        when(courierRecordRepository.findByAwb("AWB123")).thenReturn(Optional.empty());
        when(orderRepository.findByOrderCode("SHR-1001")).thenReturn(Optional.of(order));
        ReceivableEntity receivable = codReceivable(1L, 10L, "500.00");
        when(receivableRepository.findByOrderIdAndType(10L, ReceivableType.COD_RECEIVABLE))
                .thenReturn(List.of(receivable));

        // 0.50 off — within the 1.00 tolerance — still settles.
        RemittanceImportResponse response = service.importCsv(
                csv("awb,orderCode,amount", "AWB123,SHR-1001,499.50"), false);

        assertThat(response.settled()).isEqualTo(1);
        assertThat(receivable.isSettled()).isTrue();
    }

    // --- No matching order / no receivable -----------------------------------

    @Test
    void reportsOrderNotFoundWhenNothingMatches() {
        when(courierRecordRepository.findByAwb(any())).thenReturn(Optional.empty());
        when(orderRepository.findByOrderCode(any())).thenReturn(Optional.empty());

        RemittanceImportResponse response = service.importCsv(
                csv("awb,orderCode,amount", "GHOST,GHOST-CODE,100.00"), false);

        assertThat(response.notFound()).isEqualTo(1);
        assertThat(response.rows().get(0).status()).isEqualTo(RemittanceRowResult.Status.ORDER_NOT_FOUND);
    }

    @Test
    void reportsNoReceivableWhenOrderHasNoneUnsettled() {
        OrderEntity order = order(10L, "SHR-1001");
        when(orderRepository.findByOrderCode("SHR-1001")).thenReturn(Optional.of(order));
        when(receivableRepository.findByOrderIdAndType(10L, ReceivableType.COD_RECEIVABLE))
                .thenReturn(List.of()); // no receivable at all (e.g. prepaid order)

        RemittanceImportResponse response = service.importCsv(
                csv("awb,orderCode,amount", ",SHR-1001,100.00"), false);

        assertThat(response.noReceivable()).isEqualTo(1);
    }

    // --- Malformed rows -------------------------------------------------------

    @Test
    void reportsErrorForUnparsableAmount() {
        RemittanceImportResponse response = service.importCsv(
                csv("awb,orderCode,amount", "AWB123,,not-a-number"), false);

        assertThat(response.errors()).isEqualTo(1);
        assertThat(response.rows().get(0).status()).isEqualTo(RemittanceRowResult.Status.ERROR);
    }

    @Test
    void reportsErrorForRowWithNeitherAwbNorOrderCode() {
        RemittanceImportResponse response = service.importCsv(
                csv("awb,orderCode,amount", ",,100.00"), false);

        assertThat(response.errors()).isEqualTo(1);
    }

    // --- Header / input validation --------------------------------------------

    @Test
    void rejectsEmptyCsv() {
        assertThatThrownBy(() -> service.importCsv(new byte[0], true))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsCsvMissingRequiredColumns() {
        assertThatThrownBy(() -> service.importCsv(csv("foo,bar", "1,2"), true))
                .isInstanceOf(ValidationException.class);
    }

    /** A no-op audit service so best-effort auditing never interferes with the test. */
    private static final class NoopAuditService extends AuditService {
        NoopAuditService() {
            super(null, new CurrentUserService());
        }

        @Override
        public com.shifa.oms.audit.AuditEvent record(String action, String entityType,
                                                     String entityId, String summary) {
            return null;
        }
    }
}
