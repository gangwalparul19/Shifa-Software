package com.shifa.oms.order;

import com.shifa.oms.audit.AuditEventRepository;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.auth.Role;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.order.dto.OrderResponse;
import com.shifa.oms.order.dto.UpdateDeliveryStatusRequest;
import com.shifa.oms.platform.outbox.OutboxEvent;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import com.shifa.oms.platform.outbox.OutboxEventRepository;
import com.shifa.oms.reconciliation.ReceivableEntity;
import com.shifa.oms.reconciliation.ReceivableRepository;
import com.shifa.oms.reconciliation.domain.ReceivableType;
import com.shifa.oms.statemachine.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ManualDeliveryService} — the manual status control used
 * for in-house deliveries, which no courier webhook ever advances
 * (in-house-delivery feature). Repositories are mocked interfaces, so no database
 * is required.
 */
@ExtendWith(MockitoExtension.class)
class ManualDeliveryServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ReceivableRepository receivableRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private ManualDeliveryService service;
    private List<ReceivableEntity> savedReceivables;

    private static final AuthPrincipal ADMIN = new AuthPrincipal(1L, "admin", Role.ADMIN);

    @BeforeEach
    void setUp() {
        savedReceivables = new ArrayList<>();
        AuditService auditService = new AuditService(
                mock(AuditEventRepository.class), new CurrentUserService());
        OrderWorkflowService workflowService = new OrderWorkflowService(auditService);
        service = new ManualDeliveryService(
                orderRepository, workflowService, receivableRepository,
                new OutboxEventPublisher(outboxEventRepository));

        lenient().when(orderRepository.save(any(OrderEntity.class)))
                .thenAnswer(i -> i.getArgument(0));
        lenient().when(outboxEventRepository.save(any(OutboxEvent.class)))
                .thenAnswer(i -> i.getArgument(0));
        lenient().when(receivableRepository.findByOrderIdAndType(any(), any())).thenReturn(List.of());
        lenient().when(receivableRepository.save(any(ReceivableEntity.class))).thenAnswer(i -> {
            savedReceivables.add(i.getArgument(0));
            return i.getArgument(0);
        });
    }

    /**
     * Regression: the COD receivable created when an in-house order is marked
     * delivered must carry a {@code null} courier company, not the {@code 0L}
     * sentinel. {@code receivables.courier_company_id} is a nullable FK to
     * {@code courier_companies(id)} and no company has id 0, so {@code 0L} failed
     * the constraint and turned the whole request into a 500.
     */
    @Test
    void deliveringACodOrderRecordsTheCodReceivableWithNoCourierCompany() {
        OrderEntity order = inHouseOrder(OrderStatus.OUT_FOR_DELIVERY, PaymentStatus.COD, "240.00");
        when(orderRepository.findById(8L)).thenReturn(Optional.of(order));

        OrderResponse response = service.updateDeliveryStatus(
                8L, ADMIN, new UpdateDeliveryStatusRequest(OrderStatus.DELIVERED, null, null));

        // Delivered then settled to COD_Collected in the same action.
        assertThat(response.orderStatus()).isEqualTo(OrderStatus.COD_COLLECTED);
        assertThat(order.getCustomerOutstanding()).isEqualByComparingTo("0.00");

        assertThat(savedReceivables).hasSize(1);
        ReceivableEntity receivable = savedReceivables.get(0);
        assertThat(receivable.getType()).isEqualTo(ReceivableType.COD_RECEIVABLE);
        assertThat(receivable.getAmount()).isEqualByComparingTo("240.00");
        // The crux: null, never 0 — 0 violates the courier-company foreign key.
        assertThat(receivable.getCourierCompanyId()).isNull();
    }

    @Test
    void deliveringAPrepaidOrderClosesItWithoutAReceivable() {
        OrderEntity order = inHouseOrder(OrderStatus.OUT_FOR_DELIVERY, PaymentStatus.FULLY_PAID, "0.00");
        when(orderRepository.findById(8L)).thenReturn(Optional.of(order));

        OrderResponse response = service.updateDeliveryStatus(
                8L, ADMIN, new UpdateDeliveryStatusRequest(OrderStatus.DELIVERED, null, null));

        assertThat(response.orderStatus()).isEqualTo(OrderStatus.CLOSED);
        assertThat(savedReceivables).isEmpty();
    }

    /** The warehouse steps apply to any order and record the vehicle reference. */
    @Test
    void handingOverRecordsTheVehicleReference() {
        OrderEntity order = inHouseOrder(OrderStatus.PACKED, PaymentStatus.COD, "240.00");
        when(orderRepository.findById(8L)).thenReturn(Optional.of(order));

        OrderResponse response = service.updateDeliveryStatus(8L, ADMIN,
                new UpdateDeliveryStatusRequest(OrderStatus.HANDED_TO_DELIVERY, "MP09 AB1234", "by bus"));

        assertThat(response.orderStatus()).isEqualTo(OrderStatus.HANDED_TO_DELIVERY);
        assertThat(order.getVehicleNumber()).isEqualTo("MP09 AB1234");
    }

    /** A courier order's in-transit progress is reported by the courier, not by hand. */
    @Test
    void postHandoverStagesAreRejectedForACourierOrder() {
        OrderEntity order = inHouseOrder(OrderStatus.HANDED_TO_DELIVERY, PaymentStatus.COD, "240.00");
        order.setDeliveryMethod(DeliveryMethod.QUIKSHIPX);
        when(orderRepository.findById(8L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.updateDeliveryStatus(
                8L, ADMIN, new UpdateDeliveryStatusRequest(OrderStatus.IN_TRANSIT, null, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("courier");

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.HANDED_TO_DELIVERY);
    }

    /** RTO has its own scan flow with a required reason, so it isn't settable here. */
    @Test
    void aStatusOutsideTheManualWhitelistIsRejected() {
        OrderEntity order = inHouseOrder(OrderStatus.HANDED_TO_DELIVERY, PaymentStatus.COD, "240.00");
        when(orderRepository.findById(8L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.updateDeliveryStatus(
                8L, ADMIN, new UpdateDeliveryStatusRequest(OrderStatus.RTO, null, null)))
                .isInstanceOf(ValidationException.class);
    }

    // --- Helpers ------------------------------------------------------------

    private OrderEntity inHouseOrder(OrderStatus status, PaymentStatus paymentStatus, String cod) {
        OrderEntity order = new OrderEntity(
                "SHR-000008", OrderSource.SALESPERSON, 7L,
                "Parul", "8103276050", "12 MG Road", "Pune", "Maharashtra", "411057");
        BigDecimal total = new BigDecimal("240.00");
        BigDecimal codAmount = new BigDecimal(cod);
        BigDecimal received = total.subtract(codAmount);
        order.applyAmounts(total, received, codAmount, codAmount, paymentStatus);
        order.setCustomerOutstanding(codAmount);
        order.setDeliveryMethod(DeliveryMethod.IN_HOUSE);
        order.setOrderStatus(status);
        setId(order, 8L);
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
