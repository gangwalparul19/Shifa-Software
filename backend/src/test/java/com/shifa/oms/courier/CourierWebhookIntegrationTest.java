package com.shifa.oms.courier;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.platform.outbox.OutboxEvent;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import com.shifa.oms.platform.outbox.OutboxEventRepository;
import com.shifa.oms.reconciliation.ReceivableEntity;
import com.shifa.oms.reconciliation.ReceivableRepository;
import com.shifa.oms.reconciliation.domain.ReceivableType;
import com.shifa.oms.statemachine.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration-style test for the courier tracking webhook path: mapping a courier
 * update to an internal status change and wiring the settlement side effects
 * (Req 13.2, 16.2, 17.*). Uses mocked repositories, so no database is required.
 */
class CourierWebhookIntegrationTest {

    private OrderRepository orderRepository;
    private CourierRecordRepository courierRecordRepository;
    private ReceivableRepository receivableRepository;
    private List<ReceivableEntity> savedReceivables;
    private List<OutboxEvent> savedEvents;
    private CourierStatusApplier applier;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        courierRecordRepository = mock(CourierRecordRepository.class);
        receivableRepository = mock(ReceivableRepository.class);
        savedReceivables = new ArrayList<>();
        savedEvents = new ArrayList<>();

        when(orderRepository.save(any(OrderEntity.class))).thenAnswer(i -> i.getArgument(0));
        when(courierRecordRepository.save(any(CourierRecord.class))).thenAnswer(i -> i.getArgument(0));
        when(receivableRepository.findByOrderIdAndType(any(), any())).thenReturn(List.of());
        when(receivableRepository.save(any(ReceivableEntity.class))).thenAnswer(i -> {
            savedReceivables.add(i.getArgument(0));
            return i.getArgument(0);
        });

        OutboxEventRepository outboxRepository = mock(OutboxEventRepository.class);
        when(outboxRepository.save(any(OutboxEvent.class))).thenAnswer(i -> {
            savedEvents.add(i.getArgument(0));
            return i.getArgument(0);
        });
        OutboxEventPublisher publisher = new OutboxEventPublisher(outboxRepository);

        CourierCompanyRepository courierCompanyRepository = mock(CourierCompanyRepository.class);

        // Real central workflow service; audit is best-effort against a mock repo
        // (no Mockito mock of a concrete class — Java 25). No NotificationDispatcher
        // is wired here, so the matrix fan-out is a no-op — this test asserts the
        // courier status/settlement side effects, not notifications.
        com.shifa.oms.order.OrderWorkflowService workflowService =
                new com.shifa.oms.order.OrderWorkflowService(new com.shifa.oms.audit.AuditService(
                        mock(com.shifa.oms.audit.AuditEventRepository.class),
                        new com.shifa.oms.auth.CurrentUserService()));

        applier = new CourierStatusApplier(
                orderRepository, courierRecordRepository, courierCompanyRepository,
                receivableRepository, publisher, workflowService);
    }

    @Test
    void pickupMovesCourierAssignedToDispatched() {
        OrderEntity order = order(OrderStatus.COURIER_ASSIGNED, PaymentStatus.COD, "240.00");
        stubAwb("AWB-1", order, 5L);

        Optional<OrderStatus> result = applier.applyByAwb("AWB-1", "pickup");

        assertThat(result).contains(OrderStatus.DISPATCHED);
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.DISPATCHED);
        assertThat(savedEvents).anyMatch(e ->
                OutboxEvent.EVENT_ORDER_STATUS_CHANGED.equals(e.getEventType()));
    }

    @Test
    void deliveredCodOrderSettlesToCodCollectedAndRecordsReceivable() {
        OrderEntity order = order(OrderStatus.OUT_FOR_DELIVERY, PaymentStatus.COD, "240.00");
        stubAwb("AWB-2", order, 6L);

        Optional<OrderStatus> result = applier.applyByAwb("AWB-2", "delivered");

        assertThat(result).contains(OrderStatus.COD_COLLECTED);
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.COD_COLLECTED);
        assertThat(order.getCustomerOutstanding()).isEqualByComparingTo("0.00");
        assertThat(savedReceivables).hasSize(1);
        assertThat(savedReceivables.get(0).getType()).isEqualTo(ReceivableType.COD_RECEIVABLE);
        assertThat(savedReceivables.get(0).getAmount()).isEqualByComparingTo("240.00");
    }

    @Test
    void deliveredPrepaidOrderClosesWithoutReceivable() {
        OrderEntity order = order(OrderStatus.OUT_FOR_DELIVERY, PaymentStatus.FULLY_PAID, "0.00");
        stubAwb("AWB-3", order, 7L);

        Optional<OrderStatus> result = applier.applyByAwb("AWB-3", "delivered");

        assertThat(result).contains(OrderStatus.CLOSED);
        assertThat(savedReceivables).isEmpty();
    }

    @Test
    void redispatchShipmentRecordsClaimAndNotifiesAdmin() {
        OrderEntity order = order(OrderStatus.IN_TRANSIT, PaymentStatus.FULLY_PAID, "0.00");
        stubAwb("AWB-4", order, 8L);

        Optional<OrderStatus> result = applier.applyByAwb("AWB-4", "lost");

        assertThat(result).contains(OrderStatus.REDISPATCH);
        assertThat(savedReceivables).hasSize(1);
        assertThat(savedReceivables.get(0).getType()).isEqualTo(ReceivableType.CLAIM_RECEIVABLE);
        assertThat(savedEvents).anyMatch(e ->
                OutboxEvent.EVENT_CLAIM_FILED_REQUIRED.equals(e.getEventType()));
    }

    @Test
    void rtoCancelsCodAndClearsOutstanding() {
        OrderEntity order = order(OrderStatus.OUT_FOR_DELIVERY, PaymentStatus.COD, "240.00");
        stubAwb("AWB-5", order, 9L);

        Optional<OrderStatus> result = applier.applyByAwb("AWB-5", "rto");

        assertThat(result).contains(OrderStatus.RTO);
        assertThat(order.getCodAmount()).isEqualByComparingTo("0.00");
        assertThat(order.getCustomerOutstanding()).isEqualByComparingTo("0.00");
        assertThat(savedReceivables).isEmpty();
    }

    @Test
    void duplicateOrOutOfOrderUpdateIsIgnored() {
        OrderEntity order = order(OrderStatus.DELIVERED, PaymentStatus.COD, "240.00");
        stubAwb("AWB-6", order, 11L);

        // A late "pickup" cannot move a delivered order backwards.
        Optional<OrderStatus> result = applier.applyByAwb("AWB-6", "pickup");

        assertThat(result).isEmpty();
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.DELIVERED);
    }

    private void stubAwb(String awb, OrderEntity order, long orderId) {
        setId(order, orderId);
        CourierRecord record = new CourierRecord(orderId);
        record.assign(1L, awb, "labels/shipping/x.pdf", null);
        when(courierRecordRepository.findByAwb(awb)).thenReturn(Optional.of(record));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
    }

    private OrderEntity order(OrderStatus status, PaymentStatus paymentStatus, String cod) {
        OrderEntity order = new OrderEntity(
                "SHR-000" + status.name(), OrderSource.STOREFRONT, null,
                "Asha", "9812345678", "12 MG Road", "Pune", "Maharashtra", "411001");
        BigDecimal total = new BigDecimal("240.00");
        BigDecimal codAmount = new BigDecimal(cod);
        BigDecimal received = total.subtract(codAmount);
        order.applyAmounts(total, received, codAmount, codAmount, paymentStatus);
        order.setCustomerOutstanding(codAmount);
        order.setOrderStatus(status);
        return order;
    }

    private static void setId(OrderEntity order, long id) {
        try {
            Field field = OrderEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(order, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
