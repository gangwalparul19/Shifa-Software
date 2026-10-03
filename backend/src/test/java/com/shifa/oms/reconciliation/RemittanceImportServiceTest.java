package com.shifa.oms.reconciliation;

import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.courier.CourierRecord;
import com.shifa.oms.courier.CourierRecordRepository;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.OrderWorkflowService;
import com.shifa.oms.quikshipx.OrderShipment;
import com.shifa.oms.quikshipx.OrderShipmentRepository;
import com.shifa.oms.reconciliation.domain.ReceivableType;
import com.shifa.oms.reconciliation.dto.RemittanceImportResponse;
import com.shifa.oms.reconciliation.dto.RemittanceRowResult;
import com.shifa.oms.statemachine.OrderStatus;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RemittanceImportService} (enhancement: "courier
 * remittance import & auto-match") — matching (AWB / order code / QuikShipX
 * client-order-id), tolerance, dry-run vs commit, the delivered-catch-up path
 * for orders that never got a courier webhook, already-settled rows, Excel
 * parsing, and malformed-row handling — all against mocked repositories.
 */
@ExtendWith(MockitoExtension.class)
class RemittanceImportServiceTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private CourierRecordRepository courierRecordRepository;
    @Mock
    private OrderShipmentRepository orderShipmentRepository;
    @Mock
    private ReceivableRepository receivableRepository;

    private RemittanceImportService service;

    @BeforeEach
    void setUp() {
        AuditService auditService = new NoopAuditService();
        OrderWorkflowService workflowService = new OrderWorkflowService(auditService);
        RemittanceRowProcessor rowProcessor = new RemittanceRowProcessor(orderRepository,
                courierRecordRepository, orderShipmentRepository, receivableRepository, workflowService);
        service = new RemittanceImportService(rowProcessor, auditService);
        lenient().when(receivableRepository.save(any(ReceivableEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(orderShipmentRepository.findByAwb(any())).thenReturn(Optional.empty());
        lenient().when(orderShipmentRepository.findByShipperOrderId(any())).thenReturn(Optional.empty());
        lenient().when(courierRecordRepository.findByOrderId(any())).thenReturn(Optional.empty());
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

    /** Builds a minimal single-sheet .xlsx workbook from a small grid of string values. */
    private byte[] xlsx(String[]... rows) {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Remittance");
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    row.createCell(c).setCellValue(rows[r][c]);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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

    // --- QuikShipX header vocabulary + Client Order ID matching --------------

    @Test
    void matchesQuikShipXClientOrderIdByExtractingTheShipperOrderIdSuffix() {
        OrderEntity order = order(12L, "SHR-3003");
        when(courierRecordRepository.findByAwb(any())).thenReturn(Optional.empty());
        OrderShipment shipment = new OrderShipment(12L, "SHR-3003");
        when(orderShipmentRepository.findByShipperOrderId("19823")).thenReturn(Optional.of(shipment));
        when(orderRepository.findById(12L)).thenReturn(Optional.of(order));
        ReceivableEntity receivable = codReceivable(3L, 12L, "760.00");
        when(receivableRepository.findByOrderIdAndType(12L, ReceivableType.COD_RECEIVABLE))
                .thenReturn(List.of(receivable));

        // The real QuikShipX remittance sheet header + one data row from the sample file.
        RemittanceImportResponse response = service.importCsv(
                csv("SNO,Tracking ID,Client Order ID,Delivered On,COD Amount,Remitted Amount,Remitted Date",
                        "1,20736018905842,shr083_19823,19 Apr 2026,760,760,2026-04-20"),
                false);

        assertThat(response.rows()).hasSize(1);
        assertThat(response.rows().get(0).status()).isEqualTo(RemittanceRowResult.Status.SETTLED);
        assertThat(receivable.isSettled()).isTrue();
        assertThat(receivable.getSettledDate()).isEqualTo(LocalDate.of(2026, 4, 20));
    }

    @Test
    void matchesQuikShipXTrackingIdColumnAsAwb() {
        OrderEntity order = order(13L, "SHR-4004");
        CourierRecord record = new CourierRecord(13L);
        record.assign(1L, "20736018905842", null, null);
        when(courierRecordRepository.findByAwb("20736018905842")).thenReturn(Optional.of(record));
        when(orderRepository.findById(13L)).thenReturn(Optional.of(order));
        ReceivableEntity receivable = codReceivable(4L, 13L, "760.00");
        when(receivableRepository.findByOrderIdAndType(13L, ReceivableType.COD_RECEIVABLE))
                .thenReturn(List.of(receivable));

        RemittanceImportResponse response = service.importCsv(
                csv("SNO,Tracking ID,Client Order ID,Delivered On,COD Amount,Remitted Amount,Remitted Date",
                        "1,20736018905842,shr083_99999,19 Apr 2026,760,760,2026-04-20"),
                false);

        assertThat(response.rows().get(0).status()).isEqualTo(RemittanceRowResult.Status.SETTLED);
    }

    // --- Excel (.xlsx) parsing -------------------------------------------------

    @Test
    void parsesAnExcelWorkbookIdenticallyToCsv() {
        OrderEntity order = order(20L, "SHR-9000");
        CourierRecord record = new CourierRecord(20L);
        record.assign(1L, "AWBXL01", null, null);
        when(courierRecordRepository.findByAwb("AWBXL01")).thenReturn(Optional.of(record));
        when(orderRepository.findById(20L)).thenReturn(Optional.of(order));
        ReceivableEntity receivable = codReceivable(5L, 20L, "999.00");
        when(receivableRepository.findByOrderIdAndType(20L, ReceivableType.COD_RECEIVABLE))
                .thenReturn(List.of(receivable));

        byte[] workbook = xlsx(
                new String[] {"awb", "orderCode", "amount"},
                new String[] {"AWBXL01", "", "999.00"});

        RemittanceImportResponse response = service.importFile(workbook, "remittance.xlsx", false);

        assertThat(response.rows()).hasSize(1);
        assertThat(response.rows().get(0).status()).isEqualTo(RemittanceRowResult.Status.SETTLED);
        assertThat(receivable.isSettled()).isTrue();
    }

    // --- Delivered/COD_Collected catch-up when no receivable exists yet -------

    @Test
    void deliversAndSettlesWhenOrderNeverGotACourierWebhookButRemittanceProvesPayout() {
        OrderEntity order = order(30L, "SHR-5005");
        order.setOrderStatus(OrderStatus.OUT_FOR_DELIVERY);
        order.applyAmounts(new BigDecimal("500.00"), BigDecimal.ZERO, new BigDecimal("500.00"),
                new BigDecimal("500.00"), com.shifa.oms.order.domain.PaymentStatus.COD);
        when(orderRepository.findByOrderCode("SHR-5005")).thenReturn(Optional.of(order));
        when(receivableRepository.findByOrderIdAndType(30L, ReceivableType.COD_RECEIVABLE))
                .thenReturn(List.of());

        RemittanceImportResponse response = service.importCsv(
                csv("awb,orderCode,amount", ",SHR-5005,500.00"), false);

        assertThat(response.settled()).isEqualTo(1);
        assertThat(response.rows().get(0).status()).isEqualTo(RemittanceRowResult.Status.SETTLED);
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.COD_COLLECTED);
        assertThat(order.getCustomerOutstanding()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(receivableRepository).save(any(ReceivableEntity.class));
        verify(orderRepository).save(order);
    }

    @Test
    void dryRunNeverAppliesTheDeliveredCatchUpTransition() {
        OrderEntity order = order(31L, "SHR-5006");
        order.setOrderStatus(OrderStatus.HANDED_TO_DELIVERY);
        when(orderRepository.findByOrderCode("SHR-5006")).thenReturn(Optional.of(order));
        when(receivableRepository.findByOrderIdAndType(31L, ReceivableType.COD_RECEIVABLE))
                .thenReturn(List.of());

        RemittanceImportResponse response = service.importCsv(
                csv("awb,orderCode,amount", ",SHR-5006,200.00"), true);

        assertThat(response.settled()).isEqualTo(1); // reported as "would settle"
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.HANDED_TO_DELIVERY); // unchanged
        verify(orderRepository, never()).save(any());
        verify(receivableRepository, never()).save(any());
    }

    @Test
    void reportsNoReceivableWhenOrderHasNoneAndIsNotAwaitingDelivery() {
        // Prepaid/cancelled/etc. — no receivable AND not in a pre-delivery status.
        OrderEntity order = order(10L, "SHR-1001");
        order.setOrderStatus(OrderStatus.CLOSED);
        when(orderRepository.findByOrderCode("SHR-1001")).thenReturn(Optional.of(order));
        when(receivableRepository.findByOrderIdAndType(10L, ReceivableType.COD_RECEIVABLE))
                .thenReturn(List.of());

        RemittanceImportResponse response = service.importCsv(
                csv("awb,orderCode,amount", ",SHR-1001,100.00"), false);

        assertThat(response.noReceivable()).isEqualTo(1);
        assertThat(response.rows().get(0).status()).isEqualTo(RemittanceRowResult.Status.NO_RECEIVABLE);
    }

    // --- Already-settled rows (duplicate/re-sent remittance) ------------------

    @Test
    void reportsAlreadySettledWhenTheOnlyReceivableIsAlreadySettled() {
        OrderEntity order = order(40L, "SHR-6006");
        when(orderRepository.findByOrderCode("SHR-6006")).thenReturn(Optional.of(order));
        ReceivableEntity settled = codReceivable(6L, 40L, "150.00");
        settled.settle(LocalDate.of(2026, 1, 1));
        when(receivableRepository.findByOrderIdAndType(40L, ReceivableType.COD_RECEIVABLE))
                .thenReturn(List.of(settled));

        RemittanceImportResponse response = service.importCsv(
                csv("awb,orderCode,amount", ",SHR-6006,150.00"), false);

        assertThat(response.alreadySettled()).isEqualTo(1);
        assertThat(response.rows().get(0).status()).isEqualTo(RemittanceRowResult.Status.ALREADY_SETTLED);
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

    // --- No matching order ------------------------------------------------------

    @Test
    void reportsOrderNotFoundWhenNothingMatches() {
        when(courierRecordRepository.findByAwb(any())).thenReturn(Optional.empty());
        when(orderRepository.findByOrderCode(any())).thenReturn(Optional.empty());

        RemittanceImportResponse response = service.importCsv(
                csv("awb,orderCode,amount", "GHOST,GHOST-CODE,100.00"), false);

        assertThat(response.notFound()).isEqualTo(1);
        assertThat(response.rows().get(0).status()).isEqualTo(RemittanceRowResult.Status.ORDER_NOT_FOUND);
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
