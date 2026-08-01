package com.shifa.oms.courier;

import com.shifa.oms.order.Actor;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderWorkflowService;
import com.shifa.oms.order.domain.Money;
import com.shifa.oms.reconciliation.ReceivableEntity;
import com.shifa.oms.reconciliation.ReceivableRepository;
import com.shifa.oms.reconciliation.domain.OrderSettlementView;
import com.shifa.oms.reconciliation.domain.ReceivableType;
import com.shifa.oms.reconciliation.domain.SettlementProcessor;
import com.shifa.oms.reconciliation.domain.SettlementResult;
import com.shifa.oms.notification.NotificationContext;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import com.shifa.oms.statemachine.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Applies a mapped courier status to an order, enforcing transition legality and
 * wiring the settlement side effects for terminal delivery outcomes (Req 13.1,
 * 13.2, 16.*, 17.*). Reused by both the tracking webhook (task 14.3) and the
 * scheduled poll fallback.
 *
 * <p>The mapped status is applied <em>only when the transition is legal from the
 * current status</em> (Property 7); otherwise the update is ignored — this makes
 * duplicate and out-of-order courier updates harmless. Delivery/RTO/loss carry
 * their settlement effects:
 * <ul>
 *   <li><b>Delivered</b>: {@code →Delivered}, then {@code →Closed} (prepaid) or
 *       {@code →COD_Collected} with a COD receivable (Req 16.1, 16.2);</li>
 *   <li><b>RTO</b>: {@code →RTO}, COD cancelled, outstanding 0 (Req 16.3);</li>
 *   <li><b>Redispatch</b>: {@code →Redispatch}, a claim receivable for the
 *       net amount, outstanding 0, and a claim-filing admin notification
 *       (Req 17.2, 17.3, 17.4).</li>
 * </ul>
 */
@Service
public class CourierStatusApplier {

    private static final Logger log = LoggerFactory.getLogger(CourierStatusApplier.class);
    private static final String ACTOR = "COURIER_API";
    private static final String SOURCE = "COURIER";

    private final OrderRepository orderRepository;
    private final CourierRecordRepository courierRecordRepository;
    private final CourierCompanyRepository courierCompanyRepository;
    private final ReceivableRepository receivableRepository;
    private final OutboxEventPublisher outboxEventPublisher;
    private final OrderWorkflowService orderWorkflowService;
    private final SettlementProcessor settlementProcessor = new SettlementProcessor();

    public CourierStatusApplier(OrderRepository orderRepository,
                                CourierRecordRepository courierRecordRepository,
                                CourierCompanyRepository courierCompanyRepository,
                                ReceivableRepository receivableRepository,
                                OutboxEventPublisher outboxEventPublisher,
                                OrderWorkflowService orderWorkflowService) {
        this.orderRepository = orderRepository;
        this.courierRecordRepository = courierRecordRepository;
        this.courierCompanyRepository = courierCompanyRepository;
        this.receivableRepository = receivableRepository;
        this.outboxEventPublisher = outboxEventPublisher;
        this.orderWorkflowService = orderWorkflowService;
    }

    /**
     * Applies a courier tracking update identified by AWB.
     *
     * @param awb       the AWB the update concerns
     * @param rawStatus the courier's raw status token
     * @return the order's final status if a change was applied, else empty
     */
    @Transactional
    public Optional<OrderStatus> applyByAwb(String awb, String rawStatus) {
        Optional<CourierRecord> recordOpt = courierRecordRepository.findByAwb(awb);
        if (recordOpt.isEmpty()) {
            log.debug("No courier record for AWB {} — ignoring update {}", awb, rawStatus);
            return Optional.empty();
        }
        CourierRecord record = recordOpt.get();
        // Always record the latest raw status seen (display + idempotency aid).
        record.setLastCourierStatus(rawStatus);
        courierRecordRepository.save(record);

        Optional<OrderStatus> mapped = CourierStatusMapper.toInternal(rawStatus);
        if (mapped.isEmpty()) {
            log.debug("Unrecognised courier status '{}' for AWB {} — ignored", rawStatus, awb);
            return Optional.empty();
        }

        OrderEntity order = orderRepository.findById(record.getOrderId()).orElse(null);
        if (order == null) {
            return Optional.empty();
        }
        return applyMapped(order, record, mapped.get());
    }

    private Optional<OrderStatus> applyMapped(OrderEntity order, CourierRecord record, OrderStatus target) {
        OrderStatus current = order.getOrderStatus();
        if (!current.canTransitionTo(target)) {
            // Duplicate / out-of-order / not-yet-legal update: ignore (Property 7).
            log.debug("Courier status {} not legal from {} for order {} — ignored",
                    target, current, order.getOrderCode());
            return Optional.empty();
        }

        // Build the courier-enriched WhatsApp context once (courier company, AWB,
        // tracking link, ETA) and hand it to the workflow so the matrix's customer
        // WhatsApp notification carries the full dispatch tracking payload. The
        // matrix (wired into OrderWorkflowService) is now the single source that
        // enqueues customer/staff notifications — this applier no longer enqueues
        // WhatsApp directly, avoiding a double-enqueue (Req 14.1, 14.2).
        NotificationContext courierContext = buildContext(order, record);

        switch (target) {
            case DELIVERED -> applyDelivered(order, record, courierContext);
            case RTO -> applyRto(order, courierContext);
            case REDISPATCH -> applyRedispatch(order, record, courierContext);
            case CUSTOMER_REJECTED, DELIVERY_FAILED -> applyFailedOutcome(order, target, courierContext);
            // Dispatched / In_Transit / Out_For_Delivery
            default -> transition(order, target, courierContext);
        }

        OrderEntity saved = orderRepository.save(order);
        outboxEventPublisher.publishOrderStatusChanged(
                saved.getId(), saved.getOrderCode(), saved.getOrderStatus().name());
        return Optional.of(saved.getOrderStatus());
    }

    /** Resolves the courier-tracking WhatsApp context for the customer notifications. */
    private NotificationContext buildContext(OrderEntity order, CourierRecord record) {
        String courierName = null;
        String trackingUrl = null;
        if (record.getCourierCompanyId() != null) {
            CourierCompany company = courierCompanyRepository.findById(record.getCourierCompanyId())
                    .orElse(null);
            if (company != null) {
                courierName = company.getName();
                trackingUrl = company.trackingUrl(record.getAwb());
            }
        }
        return new NotificationContext(
                order.getOrderCode(),
                order.getCustomerMobile(),
                courierName,
                record.getAwb(),
                trackingUrl,
                record.getEstimatedDelivery(),
                order.getPaymentStatus(),
                order.getCodAmount());
    }

    private void applyDelivered(OrderEntity order, CourierRecord record, NotificationContext ctx) {
        transition(order, OrderStatus.DELIVERED, ctx);
        SettlementResult result = settlementProcessor.onDelivered(view(order, record));
        transition(order, result.newStatus(), ctx);
        order.setCustomerOutstanding(BigDecimal.ZERO);
        result.receivable().ifPresent(r -> recordReceivable(
                order, record, ReceivableType.COD_RECEIVABLE, order.getCodAmount()));
    }

    private void applyRto(OrderEntity order, NotificationContext ctx) {
        transition(order, OrderStatus.RTO, ctx);
        // Cancel the COD amount and clear the customer outstanding (Req 16.3).
        order.applyAmounts(order.getTotalAmount(), order.getAmountReceived(),
                order.getRemainingAmount(), BigDecimal.ZERO, order.getPaymentStatus());
        order.setCustomerOutstanding(BigDecimal.ZERO);
    }

    /**
     * Applies a terminal delivery-failure outcome — {@code CUSTOMER_REJECTED}
     * (customer refused at the door, Req 11.1) or {@code DELIVERY_FAILED} (a
     * failed attempt, Req 11.2). Unlike {@code Delivered}/{@code Redispatch}
     * these carry no settlement receivable: nothing was delivered or collected,
     * so — consistent with the RTO handling — the customer outstanding is cleared
     * to zero. The transition itself fires the matrix notification for the entered
     * status (ADMIN/salesperson/accountant in-app) through
     * {@link OrderWorkflowService}.
     */
    private void applyFailedOutcome(OrderEntity order, OrderStatus target, NotificationContext ctx) {
        transition(order, target, ctx);
        order.setCustomerOutstanding(BigDecimal.ZERO);
    }

    private void applyRedispatch(OrderEntity order, CourierRecord record, NotificationContext ctx) {
        transition(order, OrderStatus.REDISPATCH, ctx);
        order.setCustomerOutstanding(BigDecimal.ZERO);
        ReceivableEntity claim = recordReceivable(
                order, record, ReceivableType.CLAIM_RECEIVABLE, order.getTotalAmount());
        if (claim != null) {
            // Notify the admin that a claim needs to be filed for the AWB (Req 17.4).
            outboxEventPublisher.publishClaimFiledRequired(
                    order.getId(), order.getOrderCode(),
                    record.getAwb(), order.getTotalAmount().toPlainString());
        }
    }

    private ReceivableEntity recordReceivable(OrderEntity order, CourierRecord record,
                                              ReceivableType type, BigDecimal amount) {
        // Idempotent: never create a second receivable of the same type for an order.
        if (!receivableRepository.findByOrderIdAndType(order.getId(), type).isEmpty()) {
            return null;
        }
        return receivableRepository.save(new ReceivableEntity(
                order.getId(), record.getCourierCompanyId(), type, amount));
    }

    private OrderSettlementView view(OrderEntity order, CourierRecord record) {
        long courierId = record.getCourierCompanyId() == null ? 0L : record.getCourierCompanyId();
        return new OrderSettlementView(
                order.getId(),
                courierId,
                order.getPaymentStatus(),
                Money.of(order.getTotalAmount()),
                Money.of(order.getCodAmount()));
    }

    private void transition(OrderEntity order, OrderStatus target, NotificationContext ctx) {
        // Centralized courier-driven transition as the automatic SYSTEM actor
        // (Req 15.5). Legality is pre-checked in applyMapped, so this only ever
        // applies legal, SYSTEM-authorized edges. The matrix notifications for the
        // entered status are enqueued by OrderWorkflowService using the supplied
        // courier context for the WhatsApp customer message.
        orderWorkflowService.applyTransition(order, target, Actor.system(ACTOR, SOURCE), ctx);
    }
}
