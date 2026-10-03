package com.shifa.oms.packing;

import com.shifa.oms.audit.AuditEventRepository;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.auth.UserRepository;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.OrderWorkflowService;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.packing.dto.PrintLabelQueueResponse;
import com.shifa.oms.platform.outbox.OutboxEvent;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import com.shifa.oms.platform.outbox.OutboxEventRepository;
import com.shifa.oms.quikshipx.OrderShipment;
import com.shifa.oms.quikshipx.OrderShipmentRepository;
import com.shifa.oms.statemachine.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Packaging "Print Labels" section on {@link PackingService}:
 * the print-labels queue (Shopify Tracking-ID-Assigned orders split into
 * to-print vs printed, carrying the QuikShipX label URL) and marking labels
 * printed (idempotent, additive — never changes the order status).
 */
@ExtendWith(MockitoExtension.class)
class PackingPrintLabelsTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private OrderShipmentRepository shipmentRepository;

    private PackingService service;

    @BeforeEach
    void setUp() {
        OutboxEventPublisher publisher = new OutboxEventPublisher(outboxEventRepository);
        AuditService auditService = new AuditService(mock(AuditEventRepository.class), new CurrentUserService());
        OrderWorkflowService workflowService = new OrderWorkflowService(auditService);
        service = new PackingService(orderRepository, publisher, workflowService,
                mock(UserRepository.class), shipmentRepository);
        lenient().when(shipmentRepository.save(any(OrderShipment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void printLabelQueueSplitsShopifyTrackingIdAssignedByPrinted() {
        OrderEntity notPrinted = shopifyOrder(1L, "SHR-A");
        OrderEntity alreadyPrinted = shopifyOrder(2L, "SHR-B");
        // Packing redesign: the print-labels queue now lists courier (non-in-house)
        // orders still at LABEL_GENERATED, split by whether the label was printed.
        when(orderRepository.findByOrderStatusOrderByCreatedAtDesc(OrderStatus.LABEL_GENERATED))
                .thenReturn(List.of(notPrinted, alreadyPrinted));

        OrderShipment s1 = shipment(1L, "SHR-A", "https://qsx/label/1.pdf", null);
        OrderShipment s2 = shipment(2L, "SHR-B", "https://qsx/label/2.pdf", LocalDateTime.now());
        when(shipmentRepository.findByOrderIdIn(any())).thenReturn(List.of(s1, s2));

        PrintLabelQueueResponse res = service.printLabelQueue();

        assertThat(res.toPrint()).hasSize(1);
        assertThat(res.toPrint().get(0).id()).isEqualTo(1L);
        assertThat(res.toPrint().get(0).quikShipXLabelUrl()).isEqualTo("https://qsx/label/1.pdf");
        assertThat(res.toPrint().get(0).labelPrinted()).isFalse();

        assertThat(res.printed()).hasSize(1);
        assertThat(res.printed().get(0).id()).isEqualTo(2L);
        assertThat(res.printed().get(0).labelPrinted()).isTrue();
    }

    @Test
    void markLabelsPrintedStampsUnprintedShipmentsIdempotently() {
        OrderShipment fresh = shipment(1L, "SHR-A", "https://qsx/label/1.pdf", null);
        OrderShipment already = shipment(2L, "SHR-B", "https://qsx/label/2.pdf", LocalDateTime.now().minusDays(1));
        when(shipmentRepository.findByOrderIdIn(any())).thenReturn(List.of(fresh, already));

        int marked = service.markLabelsPrinted(List.of(1L, 2L));

        assertThat(marked).isEqualTo(1);          // only the fresh one newly marked
        assertThat(fresh.isLabelPrinted()).isTrue();
        verify(shipmentRepository).save(fresh);    // the already-printed one is not re-saved
    }

    @Test
    void markLabelsPrintedIsNoOpForEmptyInput() {
        assertThat(service.markLabelsPrinted(List.of())).isZero();
    }

    private static OrderEntity shopifyOrder(long id, String code) {
        OrderEntity order = new OrderEntity(
                code, OrderSource.SHOPIFY, null, "Asha", "9812345678",
                "12 MG Road", "Pune", "Maharashtra", "411001");
        order.applyAmounts(new BigDecimal("240.00"), new BigDecimal("240.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, PaymentStatus.FULLY_PAID);
        order.setOrderStatus(OrderStatus.LABEL_GENERATED);
        ReflectionTestUtils.setField(order, "id", id);
        ReflectionTestUtils.setField(order, "createdAt", LocalDateTime.now());
        return order;
    }

    private static OrderShipment shipment(long orderId, String code, String labelUrl, LocalDateTime printedAt) {
        OrderShipment s = new OrderShipment(orderId, code);
        s.recordCreated("SHIP-" + orderId, false);
        s.recordTrackingId("AWB-" + orderId, "1", "Delhivery", labelUrl);
        if (printedAt != null) {
            s.markLabelPrinted(printedAt);
        }
        return s;
    }
}
