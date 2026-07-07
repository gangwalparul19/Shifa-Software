package com.shifa.oms.packing;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.packing.dto.PackingScanResponse;
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

    private static final String PACKER = "packer";

    @BeforeEach
    void setUp() {
        OutboxEventPublisher publisher = new OutboxEventPublisher(outboxEventRepository);
        service = new PackingService(orderRepository, publisher);
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
        assertThat(order.getStatusHistory().get(0).getActor()).isEqualTo(PACKER);
        assertThat(order.getStatusHistory().get(0).getSource()).isEqualTo("PACKING");

        verify(orderRepository).save(order);
        // Two events persisted in the same transaction: the packed notification
        // (Req 11.2) and the courier-assign event that the drainer picks up (Req 12.1).
        ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository, times(2)).save(eventCaptor.capture());
        List<OutboxEvent> events = eventCaptor.getAllValues();

        OutboxEvent packed = events.stream()
                .filter(e -> OutboxEvent.EVENT_ORDER_PACKED.equals(e.getEventType()))
                .findFirst().orElseThrow();
        assertThat(packed.getAggregateType()).isEqualTo(OutboxEvent.AGGREGATE_ORDER);
        assertThat(packed.getStatus()).isEqualTo(OutboxEvent.STATUS_PENDING);
        assertThat(packed.getPayload()).containsEntry("orderCode", "SHR-000123");
        assertThat(packed.getPayload()).containsEntry("packedBy", PACKER);

        OutboxEvent courierAssign = events.stream()
                .filter(e -> OutboxEvent.EVENT_COURIER_ASSIGN.equals(e.getEventType()))
                .findFirst().orElseThrow();
        assertThat(courierAssign.getAggregateType()).isEqualTo(OutboxEvent.AGGREGATE_ORDER);
        assertThat(courierAssign.getStatus()).isEqualTo(OutboxEvent.STATUS_PENDING);
        assertThat(courierAssign.getPayload()).containsEntry("orderCode", "SHR-000123");
    }
}
