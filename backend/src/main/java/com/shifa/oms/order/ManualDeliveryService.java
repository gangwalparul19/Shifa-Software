package com.shifa.oms.order;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.Role;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.order.domain.Money;
import com.shifa.oms.order.dto.MarkDeliveredRequest;
import com.shifa.oms.order.dto.OrderResponse;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import com.shifa.oms.reconciliation.ReceivableEntity;
import com.shifa.oms.reconciliation.ReceivableRepository;
import com.shifa.oms.reconciliation.domain.OrderSettlementView;
import com.shifa.oms.reconciliation.domain.ReceivableType;
import com.shifa.oms.reconciliation.domain.SettlementProcessor;
import com.shifa.oms.reconciliation.domain.SettlementResult;
import com.shifa.oms.statemachine.OrderStatus;
import com.shifa.oms.statemachine.UnauthorizedTransitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Manual one-shot "Mark Delivered" action for in-house (non-QuikShipX)
 * deliveries (in-house-delivery feature).
 *
 * <p>An {@code IN_HOUSE} order never enters the QuikShipX courier pipeline, so
 * it never receives the automatic courier-driven {@code -&gt;Delivered}
 * progression that {@link com.shifa.oms.courier.CourierStatusApplier} applies
 * for QuikShipX orders. This service gives {@code ADMIN}, {@code PACKING_USER},
 * and the order's own {@code SALESPERSON} a single action that jumps the order
 * straight from {@code Handed_To_Delivery} to {@code Delivered} and, in the same
 * action, settles it to {@code Closed} (prepaid) or {@code COD_Collected}
 * (recording the COD receivable) — mirroring exactly the settlement logic
 * {@link com.shifa.oms.courier.CourierStatusApplier} applies for QuikShipX orders
 * ({@link SettlementProcessor#onDelivered}), just triggered by a human instead
 * of a courier webhook.
 *
 * <p>Both hops route through {@link OrderWorkflowService#applyTransition}, so
 * authorization, legality, history, and audit stay centralized; the first hop
 * (-&gt;Delivered) fires the existing {@code DELIVERED} notification matrix
 * (customer WhatsApp/email + staff in-app) exactly as a QuikShipX delivery does.
 */
@Service
public class ManualDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(ManualDeliveryService.class);
    private static final String SOURCE = "MANUAL_DELIVERY";

    private final OrderRepository orderRepository;
    private final OrderWorkflowService orderWorkflowService;
    private final ReceivableRepository receivableRepository;
    private final OutboxEventPublisher outboxEventPublisher;
    private final SettlementProcessor settlementProcessor = new SettlementProcessor();

    public ManualDeliveryService(OrderRepository orderRepository,
                                 OrderWorkflowService orderWorkflowService,
                                 ReceivableRepository receivableRepository,
                                 OutboxEventPublisher outboxEventPublisher) {
        this.orderRepository = orderRepository;
        this.orderWorkflowService = orderWorkflowService;
        this.receivableRepository = receivableRepository;
        this.outboxEventPublisher = outboxEventPublisher;
    }

    /**
     * Marks an in-house order delivered and settles it in the same action.
     *
     * @param orderId the order to mark delivered
     * @param actor   the acting principal (ADMIN, PACKING_USER, or the order's
     *                own SALESPERSON)
     * @param request optional delivery note (nullable)
     * @return the updated order projection (now {@code Closed} or
     *         {@code COD_Collected})
     * @throws ResourceNotFoundException          when no order matches (404)
     * @throws ValidationException                 when the order is not flagged
     *                                              for in-house delivery (400)
     * @throws UnauthorizedTransitionException     when a SALESPERSON caller does
     *                                              not own the order (403)
     * @throws com.shifa.oms.statemachine.IllegalStatusTransitionException
     *                                              when the order is not in a
     *                                              status this action can apply
     *                                              from (409)
     */
    @Transactional
    public OrderResponse markDelivered(Long orderId, AuthPrincipal actor, MarkDeliveredRequest request) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + orderId + " does not exist."));

        if (!order.isInHouseDelivery()) {
            throw new ValidationException(
                    "Order " + order.getOrderCode() + " is not an in-house delivery order.");
        }
        if (actor.role() == Role.SALESPERSON && !actor.userId().equals(order.getCreatedBy())) {
            throw new UnauthorizedTransitionException(
                    "Salesperson " + actor.username() + " does not own order " + order.getOrderCode() + ".");
        }

        Actor workflowActor = Actor.user(actor, SOURCE);

        // Hop 1: -> Delivered. Illegal from the current status (409) surfaces
        // naturally from the state machine — e.g. an order still awaiting
        // handover cannot be marked delivered.
        orderWorkflowService.applyTransition(order, OrderStatus.DELIVERED, workflowActor);

        // Hop 2: settle exactly like a QuikShipX delivery would (Closed if
        // prepaid, else COD_Collected + a COD receivable), reusing the same pure
        // settlement logic as CourierStatusApplier. No courier company for an
        // in-house order — pass 0L, matching the existing convention for an
        // unassigned courier (CourierStatusApplier#view).
        SettlementResult result = settlementProcessor.onDelivered(view(order));
        orderWorkflowService.applyTransition(order, result.newStatus(), workflowActor);
        order.setCustomerOutstanding(BigDecimal.ZERO);
        result.receivable().ifPresent(r -> recordReceivable(order));

        if (request != null && request.note() != null && !request.note().isBlank()) {
            log.debug("Order {} manually marked delivered by {} — note: {}",
                    order.getOrderCode(), actor.username(), request.note().trim());
        }

        OrderEntity saved = orderRepository.save(order);
        outboxEventPublisher.publishOrderStatusChanged(
                saved.getId(), saved.getOrderCode(), saved.getOrderStatus().name());
        return OrderResponse.from(saved);
    }

    /** Idempotent: never create a second COD receivable for the same order. */
    private void recordReceivable(OrderEntity order) {
        if (!receivableRepository.findByOrderIdAndType(order.getId(), ReceivableType.COD_RECEIVABLE).isEmpty()) {
            return;
        }
        receivableRepository.save(new ReceivableEntity(
                order.getId(), 0L, ReceivableType.COD_RECEIVABLE, order.getCodAmount()));
    }

    private OrderSettlementView view(OrderEntity order) {
        return new OrderSettlementView(
                order.getId(),
                0L,
                order.getPaymentStatus(),
                Money.of(order.getTotalAmount()),
                Money.of(order.getCodAmount()));
    }
}
