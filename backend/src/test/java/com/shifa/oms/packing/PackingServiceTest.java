package com.shifa.oms.packing;

import com.shifa.oms.audit.AuditEventRepository;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.auth.Role;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.OrderWorkflowService;
import com.shifa.oms.order.RtoReason;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.order.dto.OrderResponse;
import com.shifa.oms.packing.dto.HandoverRequest;
import com.shifa.oms.packing.dto.MarkRtoRequest;
import com.shifa.oms.packing.dto.PackingScanResponse;
import com.shifa.oms.packing.dto.RtoScanPreviewResponse;
import com.shifa.oms.platform.outbox.OutboxEvent;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import com.shifa.oms.platform.outbox.OutboxEventRepository;
import com.shifa.oms.statemachine.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Example-based unit tests for {@link PackingService} covering the packing
 * barcode-scan edge cases (Req 11.1, 11.2, 11.3, 11.4) with the repository and
 * outbox publisher mocked, so they run without a database.
 * <ul>
 *   <li>unknown barcode → {@link BarcodeNotRecognizedException} (Req 11.3);</li>
 *   <li>scan on a non-{@code Label_Generated} order → {@link OrderNotPackableException}
 *       carrying the current status, with the order left unchanged (Req 11.4);</li>
 *   <li>scan on a {@code Label_Generated} order → {@code Packed}, one
 *       {@code status_history} row, and one persisted {@code ORDER_PACKED}
 *       outbox event (Req 11.1, 11.2).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PackingServiceTest {

    @Mock
    private OrderRepository orderRepository;

    // Mock the repository interface (mockable) and use a real publisher, rather
    // than mocking the concrete publisher class.
    @Mock
    private OutboxEventRepository outboxEventRepository;

    private PackingService service;

    private static final AuthPrincipal PACKER =
            new AuthPrincipal(5L, "packer", Role.PACKING_USER);

    @BeforeEach
    void setUp() {
        OutboxEventPublisher publisher = new OutboxEventPublisher(outboxEventRepository);
        // Real central workflow service; audit is best-effort against a mock repo
        // (no Mockito mock of a concrete class — Java 25).
        AuditService auditService = new AuditService(
                mock(AuditEventRepository.class), new CurrentUserService());
        OrderWorkflowService workflowService = new OrderWorkflowService(auditService);
        service = new PackingService(orderRepository, publisher, workflowService,
                mock(com.shifa.oms.auth.UserRepository.class));
        lenient().when(orderRepository.save(any(OrderEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(outboxEventRepository.save(any(OutboxEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private OrderEntity orderIn(OrderStatus status) {
        OrderEntity order = new OrderEntity(
                "SHR-000123", OrderSource.STOREFRONT, null,
                "Asha", "9812345678", "12 MG Road", "Pune", "Maharashtra", "411001");
        order.applyAmounts(new BigDecimal("240.00"), BigDecimal.ZERO,
                new BigDecimal("240.00"), new BigDecimal("240.00"), PaymentStatus.COD);
        order.setOrderStatus(status);
        return order;
    }

    // --- Unknown barcode (Req 11.3) -----------------------------------------

    @Test
    void unknownBarcodeIsNotRecognized() {
        when(orderRepository.findByOrderCode("NOPE-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.scan("NOPE-1", PACKER))
                .isInstanceOf(BarcodeNotRecognizedException.class)
                .hasMessageContaining("not recognized");

        verify(orderRepository, never()).save(any(OrderEntity.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    // --- Wrong status (Req 11.4) --------------------------------------------

    @Test
    void scanOnNonLabelGeneratedOrderIsRejectedWithCurrentStatus() {
        OrderEntity order = orderIn(OrderStatus.PACKED);
        when(orderRepository.findByOrderCode("SHR-000123")).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.scan("SHR-000123", PACKER))
                .isInstanceOf(OrderNotPackableException.class)
                .satisfies(ex -> assertThat(((OrderNotPackableException) ex).getCurrentStatus())
                        .isEqualTo(OrderStatus.PACKED));

        // Order left completely unchanged (Req 11.4, 8.3).
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PACKED);
        assertThat(order.getStatusHistory()).isEmpty();
        verify(orderRepository, never()).save(any(OrderEntity.class));
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    @Test
    void scanOnPendingOrderReportsPendingStatus() {
        OrderEntity order = orderIn(OrderStatus.PENDING_ADMIN_APPROVAL);
        when(orderRepository.findByOrderCode("SHR-000123")).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.scan("  SHR-000123  ", PACKER))
                .isInstanceOf(OrderNotPackableException.class)
                .satisfies(ex -> assertThat(((OrderNotPackableException) ex).getCurrentStatus())
                        .isEqualTo(OrderStatus.PENDING_ADMIN_APPROVAL));

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING_ADMIN_APPROVAL);
    }

    // --- Successful scan (Req 11.1, 11.2) -----------------------------------

    @Test
    void scanOnLabelGeneratedOrderMarksPackedAndRecordsHistoryAndEvent() {
        OrderEntity order = orderIn(OrderStatus.LABEL_GENERATED);
        when(orderRepository.findByOrderCode("SHR-000123")).thenReturn(Optional.of(order));

        PackingScanResponse response = service.scan("SHR-000123", PACKER);

        // Transitioned to PACKED (Req 11.1).
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PACKED);
        assertThat(response.order().orderStatus()).isEqualTo(OrderStatus.PACKED);
        assertThat(response.order().orderCode()).isEqualTo("SHR-000123");
        assertThat(response.message()).contains("SHR-000123").contains("Packed");

        // Exactly one status-history row for the transition (Req 8.4).
        assertThat(order.getStatusHistory()).hasSize(1);
        assertThat(order.getStatusHistory().get(0).getFromStatus())
                .isEqualTo(OrderStatus.LABEL_GENERATED);
        assertThat(order.getStatusHistory().get(0).getToStatus()).isEqualTo(OrderStatus.PACKED);
        assertThat(order.getStatusHistory().get(0).getActor()).isEqualTo(PACKER.username());
        assertThat(order.getStatusHistory().get(0).getSource()).isEqualTo("PACKING");

        verify(orderRepository).save(order);
        // Exactly one event persisted in the same transaction: the packed
        // notification (Req 11.2). Courier assignment is no longer enqueued on
        // pack — it moves to the dispatch action (design §4, §6.3).
        ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository, times(1)).save(eventCaptor.capture());
        List<OutboxEvent> events = eventCaptor.getAllValues();

        OutboxEvent packed = events.stream()
                .filter(e -> OutboxEvent.EVENT_ORDER_PACKED.equals(e.getEventType()))
                .findFirst().orElseThrow();
        assertThat(packed.getAggregateType()).isEqualTo(OutboxEvent.AGGREGATE_ORDER);
        assertThat(packed.getStatus()).isEqualTo(OutboxEvent.STATUS_PENDING);
        assertThat(packed.getPayload()).containsEntry("orderCode", "SHR-000123");
        assertThat(packed.getPayload()).containsEntry("packedBy", PACKER.username());

        // No COURIER_ASSIGN event is enqueued by the scan anymore.
        assertThat(events).noneMatch(e -> OutboxEvent.EVENT_COURIER_ASSIGN.equals(e.getEventType()));
    }

    // --- Handover captures who it was handed to (product-audit §4.3) --------

    @Test
    void handoverCapturesHandoverNameAndPhone() {
        OrderEntity order = orderIn(OrderStatus.PACKED);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        OrderResponse response = service.handover(1L, PACKER,
                new HandoverRequest("Ravi Courier", "9876543210"));

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.HANDED_TO_DELIVERY);
        assertThat(order.getHandoverName()).isEqualTo("Ravi Courier");
        assertThat(order.getHandoverPhone()).isEqualTo("9876543210");
        assertThat(response.handoverName()).isEqualTo("Ravi Courier");
    }

    @Test
    void handoverWithoutDetailsStillTransitions() {
        OrderEntity order = orderIn(OrderStatus.PACKED);
        when(orderRepository.findById(2L)).thenReturn(Optional.of(order));

        service.handover(2L, PACKER, null);

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.HANDED_TO_DELIVERY);
        assertThat(order.getHandoverName()).isNull();
    }

    @Test
    void handoverBlankNameIsStoredAsNull() {
        OrderEntity order = orderIn(OrderStatus.PACKED);
        when(orderRepository.findById(3L)).thenReturn(Optional.of(order));

        service.handover(3L, PACKER, new HandoverRequest("   ", ""));

        assertThat(order.getHandoverName()).isNull();
        assertThat(order.getHandoverPhone()).isNull();
    }

    // --- Multi-pack: package count (product-audit §4.2) ---------------------

    @Test
    void setPackageCountUpdatesOrder() {
        OrderEntity order = orderIn(OrderStatus.LABEL_GENERATED);
        when(orderRepository.findById(9L)).thenReturn(Optional.of(order));

        OrderResponse response = service.setPackageCount(9L, 3);

        assertThat(order.getPackageCount()).isEqualTo(3);
        assertThat(response.packageCount()).isEqualTo(3);
    }

    @Test
    void newOrderDefaultsToSinglePackage() {
        assertThat(orderIn(OrderStatus.LABEL_GENERATED).getPackageCount()).isEqualTo(1);
    }

    // --- Manual RTO marking (label redesign feature) ------------------------

    @Test
    void rtoPreviewReportsEligibleForInTransitOrder() {
        OrderEntity order = orderIn(OrderStatus.COURIER_ASSIGNED);
        when(orderRepository.findByOrderCode("SHR-000123")).thenReturn(Optional.of(order));

        RtoScanPreviewResponse preview = service.rtoPreview("SHR-000123");

        assertThat(preview.eligible()).isTrue();
        assertThat(preview.order().orderCode()).isEqualTo("SHR-000123");
    }

    @Test
    void rtoPreviewReportsIneligibleForPendingOrder() {
        OrderEntity order = orderIn(OrderStatus.PENDING_ADMIN_APPROVAL);
        when(orderRepository.findByOrderCode("SHR-000123")).thenReturn(Optional.of(order));

        RtoScanPreviewResponse preview = service.rtoPreview("SHR-000123");

        assertThat(preview.eligible()).isFalse();
    }

    @Test
    void rtoPreviewUnknownBarcodeIsNotRecognized() {
        when(orderRepository.findByOrderCode("NOPE-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rtoPreview("NOPE-1"))
                .isInstanceOf(BarcodeNotRecognizedException.class);
    }

    @Test
    void markRtoTransitionsAndRecordsReason() {
        OrderEntity order = orderIn(OrderStatus.OUT_FOR_DELIVERY);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        OrderResponse response = service.markRto(
                1L, new MarkRtoRequest(RtoReason.CUSTOMER_UNAVAILABLE, "Called twice, no answer"), PACKER);

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.RTO);
        assertThat(order.getRtoReason()).isEqualTo(RtoReason.CUSTOMER_UNAVAILABLE);
        assertThat(order.getRtoReasonNote()).isEqualTo("Called twice, no answer");
        assertThat(response.orderStatus()).isEqualTo(OrderStatus.RTO);
        assertThat(order.getStatusHistory()).hasSize(1);
        assertThat(order.getStatusHistory().get(0).getToStatus()).isEqualTo(OrderStatus.RTO);
    }

    @Test
    void markRtoRejectedWhenNotInAnEligibleStatus() {
        OrderEntity order = orderIn(OrderStatus.PENDING_ADMIN_APPROVAL);
        when(orderRepository.findById(2L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.markRto(
                2L, new MarkRtoRequest(RtoReason.OTHER, null), PACKER))
                .isInstanceOf(OrderNotRtoEligibleException.class)
                .satisfies(ex -> assertThat(((OrderNotRtoEligibleException) ex).getCurrentStatus())
                        .isEqualTo(OrderStatus.PENDING_ADMIN_APPROVAL));

        // Order left completely unchanged.
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING_ADMIN_APPROVAL);
        assertThat(order.getRtoReason()).isNull();
        assertThat(order.getStatusHistory()).isEmpty();
        verify(orderRepository, never()).save(any(OrderEntity.class));
    }

    // --- Daily pick-list / packing manifest (enhancement) -------------------

    @Test
    void pickListAggregatesQuantityAndOrderCountAcrossAwaitingPackingOrders() {
        OrderEntity orderA = orderIn(OrderStatus.LABEL_GENERATED);
        orderA.addLineItem(new com.shifa.oms.order.OrderLineItem(
                100L, "Neem Capsules", 3, new BigDecimal("50.00"), new BigDecimal("150.00")));
        orderA.addLineItem(new com.shifa.oms.order.OrderLineItem(
                101L, "Ashwagandha", 1, new BigDecimal("90.00"), new BigDecimal("90.00")));

        OrderEntity orderB = orderIn(OrderStatus.LABEL_GENERATED);
        orderB.addLineItem(new com.shifa.oms.order.OrderLineItem(
                100L, "Neem Capsules", 2, new BigDecimal("50.00"), new BigDecimal("100.00")));

        when(orderRepository.findByOrderStatusOrderByCreatedAtDesc(OrderStatus.LABEL_GENERATED))
                .thenReturn(java.util.List.of(orderA, orderB));

        com.shifa.oms.packing.dto.PickListResponse pickList = service.pickList();

        assertThat(pickList.orderCount()).isEqualTo(2);
        assertThat(pickList.lines()).hasSize(2);
        // Sorted by total quantity descending: Neem (3+2=5) before Ashwagandha (1).
        com.shifa.oms.packing.dto.PickListResponse.PickListLine first = pickList.lines().get(0);
        assertThat(first.productId()).isEqualTo(100L);
        assertThat(first.productName()).isEqualTo("Neem Capsules");
        assertThat(first.totalQuantity()).isEqualTo(5);
        assertThat(first.orderCount()).isEqualTo(2); // appears in both orders

        com.shifa.oms.packing.dto.PickListResponse.PickListLine second = pickList.lines().get(1);
        assertThat(second.productId()).isEqualTo(101L);
        assertThat(second.totalQuantity()).isEqualTo(1);
        assertThat(second.orderCount()).isEqualTo(1);
    }

    @Test
    void pickListIsEmptyWhenNoOrdersAwaitingPacking() {
        when(orderRepository.findByOrderStatusOrderByCreatedAtDesc(OrderStatus.LABEL_GENERATED))
                .thenReturn(java.util.List.of());

        com.shifa.oms.packing.dto.PickListResponse pickList = service.pickList();

        assertThat(pickList.orderCount()).isZero();
        assertThat(pickList.lines()).isEmpty();
    }
}
