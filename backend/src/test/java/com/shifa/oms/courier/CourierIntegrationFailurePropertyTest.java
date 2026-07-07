package com.shifa.oms.courier;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.platform.outbox.OutboxEvent;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import com.shifa.oms.platform.outbox.OutboxEventRepository;
import com.shifa.oms.statemachine.OrderStatus;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for courier integration failure handling.
 *
 * Feature: shifa-herbal-remedies, Property 21: For any courier assignment
 * failure/timeout, the order retains Packed and an admin failure notification is
 * produced.
 *
 * **Validates: Requirements 12.4, 14.4**
 *
 * <p>For any handed-over order, when the courier client fails (error or timeout),
 * the outbox drainer must (a) leave the order in {@code Handed_To_Delivery} and (b) persist a
 * {@code COURIER_ASSIGN_FAILED} admin notification with the error recorded. The
 * assignment call fails before any order mutation, so the order is never lost.
 * Each property runs the jqwik default of 1000 tries (≥ 100).
 */
class CourierIntegrationFailurePropertyTest {

    // Feature: shifa-herbal-remedies, Property 21: Integration failures are recorded without losing state
    // **Validates: Requirements 12.4, 14.4**
    @Property
    void courierFailureRetainsPackedAndNotifiesAdmin(
            @ForAll("orderCodes") String orderCode,
            @ForAll @IntRange(min = 0, max = 5000) int codRupees,
            @ForAll("timeouts") boolean timeout) {

        OrderEntity order = handedOverOrder(orderCode, codRupees);
        long orderId = 42L;

        OrderRepository orderRepository = mock(OrderRepository.class);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(OrderEntity.class))).thenAnswer(i -> i.getArgument(0));

        OutboxEventRepository outboxRepository = mock(OutboxEventRepository.class);
        when(outboxRepository.save(any(OutboxEvent.class))).thenAnswer(i -> i.getArgument(0));
        OutboxEventPublisher publisher = new OutboxEventPublisher(outboxRepository);

        // A courier client that always fails (error or timeout).
        CourierClient failingClient = new CourierClient() {
            @Override
            public CourierAssignmentResult assign(CourierAssignmentRequest request) {
                throw new CourierClientException(timeout
                        ? "Courier API timed out after 10s"
                        : "Courier API returned HTTP 503");
            }

            @Override
            public Optional<CourierTrackingEvent> pollLatest(String awb) {
                return Optional.empty();
            }
        };

        CourierProperties properties = new CourierProperties(
                "MOCK", null, null, null, Duration.ofSeconds(10),
                1, Duration.ofSeconds(30), "Shifa Express");

        CourierRecordRepository courierRecordRepository = mock(CourierRecordRepository.class);
        CourierCompanyRepository courierCompanyRepository = mock(CourierCompanyRepository.class);
        // A real ShippingLabelService (built from mocked interface repos); it is
        // never invoked because the courier call fails first.
        ShippingLabelService shippingLabelService = new ShippingLabelService(
                orderRepository, courierRecordRepository, courierCompanyRepository);

        com.shifa.oms.order.OrderWorkflowService workflowService =
                new com.shifa.oms.order.OrderWorkflowService(new com.shifa.oms.audit.AuditService(
                        mock(com.shifa.oms.audit.AuditEventRepository.class),
                        new com.shifa.oms.auth.CurrentUserService()));

        CourierAssignmentService assignmentService = new CourierAssignmentService(
                orderRepository,
                courierRecordRepository,
                courierCompanyRepository,
                failingClient,
                shippingLabelService,
                mock(com.shifa.oms.platform.storage.StorageService.class),
                properties,
                workflowService);

        OutboxCourierDrainer drainer = new OutboxCourierDrainer(
                outboxRepository, publisher, assignmentService, properties);

        // A single PENDING courier-assign event is due.
        OutboxEvent assignEvent = courierAssignEvent(orderId, orderCode);
        when(outboxRepository.findDue(any(), any(), any())).thenReturn(List.of(assignEvent));

        drainer.drainCourierAssignments();

        // (a) The order is not lost: it retains Handed_To_Delivery (Req 10.4),
        // the status courier assignment now runs from (design §4.1).
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.HANDED_TO_DELIVERY);

        // The assign event is marked FAILED with the error recorded on the row.
        assertThat(assignEvent.getStatus()).isEqualTo(OutboxEvent.STATUS_FAILED);
        assertThat(assignEvent.getLastError()).isNotBlank();

        // (b) An admin failure notification is produced (Req 12.4, 14.4).
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository, atLeastOnce()).save(captor.capture());
        List<OutboxEvent> saved = captor.getAllValues();
        OutboxEvent failedNotice = saved.stream()
                .filter(e -> OutboxEvent.EVENT_COURIER_ASSIGN_FAILED.equals(e.getEventType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected a COURIER_ASSIGN_FAILED notification"));
        assertThat(failedNotice.getAggregateId()).isEqualTo(orderId);
        assertThat(failedNotice.getPayload()).containsKey("error");
    }

    private OrderEntity handedOverOrder(String orderCode, int codRupees) {
        OrderEntity order = new OrderEntity(
                orderCode, OrderSource.STOREFRONT, null,
                "Asha", "9812345678", "12 MG Road", "Pune", "Maharashtra", "411001");
        BigDecimal cod = new BigDecimal(codRupees).setScale(2);
        order.applyAmounts(cod, BigDecimal.ZERO.setScale(2), cod, cod, PaymentStatus.COD);
        // Courier assignment now runs from Handed_To_Delivery (design §4.1).
        order.setOrderStatus(OrderStatus.HANDED_TO_DELIVERY);
        return order;
    }

    private OutboxEvent courierAssignEvent(long orderId, String orderCode) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId);
        payload.put("orderCode", orderCode);
        return new OutboxEvent(OutboxEvent.AGGREGATE_ORDER, orderId,
                OutboxEvent.EVENT_COURIER_ASSIGN, payload);
    }

    @Provide
    Arbitrary<String> orderCodes() {
        return Arbitraries.strings().withCharRange('A', 'Z').ofMinLength(3).ofMaxLength(8)
                .map(s -> "SHR-" + s);
    }

    @Provide
    Arbitrary<Boolean> timeouts() {
        return Arbitraries.of(true, false);
    }
}
