package com.shifa.oms.courier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shifa.oms.audit.AuditEventRepository;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.common.ApiException;
import com.shifa.oms.courier.dto.CourierWebhookResponse;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.OrderWorkflowService;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.platform.outbox.OutboxEvent;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import com.shifa.oms.platform.outbox.OutboxEventRepository;
import com.shifa.oms.reconciliation.ReceivableEntity;
import com.shifa.oms.reconciliation.ReceivableRepository;
import com.shifa.oms.statemachine.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Wiring / integration test for the courier webhook synchronization path
 * (task 11.1, design §10.3 "Webhook → transition sync"; Req 10.3, 10.5).
 *
 * <p>Unlike {@link CourierWebhookIntegrationTest} (which drives the applier
 * directly), this test posts <strong>HMAC-signed</strong> payloads through the
 * real {@link CourierWebhookController} → {@link HmacSignatureVerifier} →
 * {@link CourierStatusApplier} → {@link OrderWorkflowService} chain, so it
 * verifies the end-to-end wiring and signature check, not input-varying logic.
 * It uses mocked repositories (interfaces only — no Mockito mock of a concrete
 * class, per the Java 25 runtime gotcha) so no database is required, mirroring
 * the existing courier/notification integration tests.
 *
 * <p>Representative payloads (1–3, per design §10.3) cover: the two <em>new</em>
 * downstream outcomes reached from {@code OUT_FOR_DELIVERY}
 * ({@code customer_rejected → CUSTOMER_REJECTED}, {@code delivery_failed →
 * DELIVERY_FAILED}); an illegal/duplicate update that leaves the order
 * unchanged; and an invalid signature that is rejected with 401.
 */
class CourierWebhookSyncIntegrationTest {

    /** A non-blank secret so the HMAC path is actually exercised (not the dev skip). */
    private static final String SECRET = "test-courier-webhook-secret";

    private OrderRepository orderRepository;
    private CourierRecordRepository courierRecordRepository;
    private ReceivableRepository receivableRepository;
    private List<OutboxEvent> savedEvents;
    private ObjectMapper objectMapper;
    private CourierWebhookController controller;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        courierRecordRepository = mock(CourierRecordRepository.class);
        receivableRepository = mock(ReceivableRepository.class);
        savedEvents = new ArrayList<>();

        when(orderRepository.save(any(OrderEntity.class))).thenAnswer(i -> i.getArgument(0));
        when(courierRecordRepository.save(any(CourierRecord.class))).thenAnswer(i -> i.getArgument(0));
        when(receivableRepository.findByOrderIdAndType(any(), any())).thenReturn(List.of());
        when(receivableRepository.save(any(ReceivableEntity.class))).thenAnswer(i -> i.getArgument(0));

        OutboxEventRepository outboxRepository = mock(OutboxEventRepository.class);
        when(outboxRepository.save(any(OutboxEvent.class))).thenAnswer(i -> {
            savedEvents.add(i.getArgument(0));
            return i.getArgument(0);
        });
        OutboxEventPublisher publisher = new OutboxEventPublisher(outboxRepository);

        CourierCompanyRepository courierCompanyRepository = mock(CourierCompanyRepository.class);

        // Real central workflow service (no NotificationDispatcher → matrix fan-out is a no-op);
        // audit is best-effort against a mocked (interface) repository.
        OrderWorkflowService workflowService = new OrderWorkflowService(
                new AuditService(mock(AuditEventRepository.class), new CurrentUserService()));

        CourierStatusApplier applier = new CourierStatusApplier(
                orderRepository, courierRecordRepository, courierCompanyRepository,
                receivableRepository, publisher, workflowService);

        HmacSignatureVerifier verifier = new HmacSignatureVerifier(new CourierProperties(
                "MOCK", null, null, SECRET, Duration.ofSeconds(10), 3, Duration.ofSeconds(30), "Shifa Express"));

        objectMapper = new ObjectMapper();
        controller = new CourierWebhookController(verifier, applier, objectMapper);
    }

    @Test
    void customerRejectedTokenAdvancesOutForDeliveryToCustomerRejected() {
        OrderEntity order = order(OrderStatus.OUT_FOR_DELIVERY);
        stubAwb("AWB-CR", order, 101L);

        CourierWebhookResponse response = postSigned("AWB-CR", "customer_rejected");

        assertThat(response.applied()).isTrue();
        assertThat(response.newStatus()).isEqualTo(OrderStatus.CUSTOMER_REJECTED.name());
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CUSTOMER_REJECTED);
        // The status change was published to the outbox (real-time relay / notifications).
        assertThat(savedEvents).anyMatch(e ->
                OutboxEvent.EVENT_ORDER_STATUS_CHANGED.equals(e.getEventType()));
    }

    @Test
    void deliveryFailedTokenAdvancesOutForDeliveryToDeliveryFailed() {
        OrderEntity order = order(OrderStatus.OUT_FOR_DELIVERY);
        stubAwb("AWB-DF", order, 102L);

        CourierWebhookResponse response = postSigned("AWB-DF", "delivery_failed");

        assertThat(response.applied()).isTrue();
        assertThat(response.newStatus()).isEqualTo(OrderStatus.DELIVERY_FAILED.name());
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.DELIVERY_FAILED);
    }

    @Test
    void illegalOrDuplicateUpdateLeavesOrderUnchanged() {
        // A terminal DELIVERED order cannot be moved by a late "pickup" callback.
        OrderEntity order = order(OrderStatus.DELIVERED);
        stubAwb("AWB-DUP", order, 103L);

        CourierWebhookResponse response = postSigned("AWB-DUP", "pickup");

        assertThat(response.applied()).isFalse();
        assertThat(response.newStatus()).isNull();
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(savedEvents).noneMatch(e ->
                OutboxEvent.EVENT_ORDER_STATUS_CHANGED.equals(e.getEventType()));
    }

    @Test
    void invalidSignatureIsRejectedWithUnauthorized() {
        OrderEntity order = order(OrderStatus.OUT_FOR_DELIVERY);
        stubAwb("AWB-SIG", order, 104L);

        byte[] body = json("AWB-SIG", "delivered");

        assertThatThrownBy(() -> controller.receive(body, "deadbeef-not-a-valid-signature"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVALID_SIGNATURE"));
        // The order must not have advanced when the signature check fails.
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.OUT_FOR_DELIVERY);
    }

    // --- helpers ------------------------------------------------------------

    /** Signs the payload with the shared secret and posts it through the controller. */
    private CourierWebhookResponse postSigned(String awb, String status) {
        byte[] body = json(awb, status);
        String signature = HmacSignatureVerifier.sign(body, SECRET);
        return controller.receive(body, signature);
    }

    private byte[] json(String awb, String status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", "evt-" + awb);
        payload.put("awb", awb);
        payload.put("status", status);
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void stubAwb(String awb, OrderEntity order, long orderId) {
        setId(order, orderId);
        CourierRecord record = new CourierRecord(orderId);
        record.assign(1L, awb, "labels/shipping/x.pdf", null);
        when(courierRecordRepository.findByAwb(awb)).thenReturn(Optional.of(record));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
    }

    private OrderEntity order(OrderStatus status) {
        OrderEntity order = new OrderEntity(
                "SHR-" + status.name(), OrderSource.STOREFRONT, null,
                "Asha", "9812345678", "12 MG Road", "Pune", "Maharashtra", "411001");
        BigDecimal total = new BigDecimal("240.00");
        BigDecimal cod = new BigDecimal("240.00");
        order.applyAmounts(total, total.subtract(cod), cod, cod, PaymentStatus.COD);
        order.setCustomerOutstanding(cod);
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
