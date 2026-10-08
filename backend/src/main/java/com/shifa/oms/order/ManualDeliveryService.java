package com.shifa.oms.order;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.Role;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.order.domain.Money;
import com.shifa.oms.order.dto.MarkDeliveredRequest;
import com.shifa.oms.order.dto.OrderResponse;
import com.shifa.oms.order.dto.UpdateDeliveryStatusRequest;
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

    /**
     * Ledger auto-posting source key for the COD cash collected on delivery
     * (mirrors {@code SourceType.ORDER_DELIVERY}; kept as a literal so the order
     * module does not depend on the ledger module, exactly like
     * {@code AdminOrderService.LEDGER_SOURCE_ORDER}).
     */
    private static final String LEDGER_SOURCE_ORDER_DELIVERY = "ORDER_DELIVERY";

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
    /**
     * Internal warehouse steps that apply to <em>every</em> order regardless of
     * delivery method: marking it packed and handing it over. These are the same
     * two moves the Packing page offers (and are already authorized there for
     * ADMIN/PACKING_USER) — exposing them here just means an admin can advance an
     * order straight from the order-detail view instead of having to go to the
     * Packing page and scan it.
     */
    private static final java.util.Set<OrderStatus> PACKING_STAGES = java.util.EnumSet.of(
            OrderStatus.PACKED, OrderStatus.HANDED_TO_DELIVERY);

    /**
     * Post-handover delivery stages that may only be set by hand on an
     * <strong>in-house</strong> order. For a courier order these are driven by the
     * courier's webhook/tracking poll, so letting a human set them too would
     * compete with the courier's own reporting.
     */
    private static final java.util.Set<OrderStatus> IN_HOUSE_ONLY_STAGES = java.util.EnumSet.of(
            OrderStatus.DISPATCHED, OrderStatus.IN_TRANSIT, OrderStatus.OUT_FOR_DELIVERY,
            OrderStatus.DELIVERED, OrderStatus.CUSTOMER_REJECTED, OrderStatus.DELIVERY_FAILED);

    /**
     * Every status {@link #updateDeliveryStatus} accepts. Deliberately excludes
     * approval/settlement statuses — those have their own dedicated actions — and
     * RTO, which has its own scan flow with a required reason
     * ({@code PackingService#markRto}).
     */
    private static final java.util.Set<OrderStatus> MANUAL_DELIVERY_STAGES = manualStages();

    private static java.util.Set<OrderStatus> manualStages() {
        java.util.Set<OrderStatus> all = java.util.EnumSet.copyOf(PACKING_STAGES);
        all.addAll(IN_HOUSE_ONLY_STAGES);
        return java.util.Collections.unmodifiableSet(all);
    }

    /**
     * Manually advances an order's fulfilment status from the order-detail view
     * (in-house-delivery feature).
     *
     * <p>Two families of move are accepted:
     * <ul>
     *   <li>{@link #PACKING_STAGES} — mark packed / handed over. Internal
     *       warehouse steps that apply to <em>any</em> order, mirroring what the
     *       Packing page already does, so an admin isn't forced to go and scan the
     *       label just to move an order forward.</li>
     *   <li>{@link #IN_HOUSE_ONLY_STAGES} — Dispatched / In_Transit /
     *       Out_For_Delivery as the parcel travels (e.g. handed to a bus
     *       operator, then en route), then Delivered — which also settles the
     *       order exactly as {@link #markDelivered} does — or a failure outcome
     *       (Customer_Rejected / Delivery_Failed), which clears the customer
     *       outstanding, mirroring {@code CourierStatusApplier}'s handling. These
     *       are <strong>in-house only</strong>: a courier order's progress is
     *       reported by the courier's own webhook/poll, and a human setting it
     *       too would compete with that.</li>
     * </ul>
     *
     * <p>The optional {@code vehicleNumber} is recorded/updated in the same call,
     * so the transport reference can be attached at the moment of dispatch.
     *
     * @param orderId the order to advance
     * @param actor   the acting principal (ADMIN, PACKING_USER, or the order's own SALESPERSON)
     * @param request the target stage + optional vehicle reference / note
     * @return the updated order projection
     * @throws ResourceNotFoundException when no order matches (404)
     * @throws ValidationException when the requested status is not a manual stage, or is an
     *                             in-house-only stage on a courier order (400)
     * @throws UnauthorizedTransitionException when a SALESPERSON caller does not own the order,
     *                             or the caller's role may not trigger the move (403)
     * @throws com.shifa.oms.statemachine.IllegalStatusTransitionException
     *                             when the hop is illegal from the current status (409)
     */
    @Transactional
    public OrderResponse updateDeliveryStatus(Long orderId, AuthPrincipal actor,
                                              UpdateDeliveryStatusRequest request) {
        OrderEntity order = loadOrderFor(orderId, actor);
        OrderStatus target = request.status();
        if (!MANUAL_DELIVERY_STAGES.contains(target)) {
            throw new ValidationException(
                    target + " is not a status that can be set manually. Allowed: "
                            + MANUAL_DELIVERY_STAGES + ".");
        }
        if (IN_HOUSE_ONLY_STAGES.contains(target) && !order.isInHouseDelivery()) {
            throw new ValidationException(
                    "Order " + order.getOrderCode() + " is delivered by a courier partner, so "
                            + target + " is reported by the courier — it cannot be set by hand. "
                            + "Switch the order to in-house delivery to manage its status manually.");
        }

        if (target == OrderStatus.CUSTOMER_REJECTED || target == OrderStatus.DELIVERY_FAILED) {
            if (request.note() == null || request.note().isBlank()) {
                throw new ValidationException("Add a short reason before marking an in-house delivery failed or refused.");
            }
        }

        String vehicle = trimToNull(request.vehicleNumber());
        if (vehicle != null) {
            order.setVehicleNumber(vehicle);
        }

        Actor workflowActor = Actor.user(actor, SOURCE);

        if (target == OrderStatus.DELIVERED) {
            // Delivered also settles the order (Closed / COD_Collected + receivable),
            // exactly as the one-shot mark-delivered action does.
            deliverAndSettle(order, workflowActor);
        } else {
            orderWorkflowService.applyTransition(order, target, workflowActor);
            if (target == OrderStatus.CUSTOMER_REJECTED || target == OrderStatus.DELIVERY_FAILED) {
                // Nothing was delivered or collected — clear the customer
                // outstanding, mirroring CourierStatusApplier#applyFailedOutcome.
                order.setCustomerOutstanding(BigDecimal.ZERO);
            }
        }

        if (request.note() != null && !request.note().isBlank()) {
            log.debug("Order {} status manually set to {} by {} — note: {}",
                    order.getOrderCode(), target, actor.username(), request.note().trim());
        }

        OrderEntity saved = orderRepository.save(order);
        outboxEventPublisher.publishOrderStatusChanged(
                saved.getId(), saved.getOrderCode(), saved.getOrderStatus().name());
        log.debug("Order {} status manually advanced to {} by {}",
                saved.getOrderCode(), saved.getOrderStatus(), actor.username());
        return OrderResponse.from(saved);
    }

    /**
     * Loads an order and authorizes the caller against it: a SALESPERSON caller
     * must own the order (403 otherwise). {@code TransitionAuthority} has no
     * per-order ownership concept, so that check has to live here.
     */
    private OrderEntity loadOrderFor(Long orderId, AuthPrincipal actor) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + orderId + " does not exist."));

        if (actor.role() == Role.SALESPERSON && !actor.userId().equals(order.getCreatedBy())) {
            throw new UnauthorizedTransitionException(
                    "Salesperson " + actor.username() + " does not own order " + order.getOrderCode() + ".");
        }
        return order;
    }

    /**
     * As {@link #loadOrderFor} but additionally requires the order to be flagged
     * for in-house delivery (400 otherwise) — used by the one-shot
     * {@link #markDelivered} action, which is in-house only.
     */
    private OrderEntity requireInHouseOrderFor(Long orderId, AuthPrincipal actor) {
        OrderEntity order = loadOrderFor(orderId, actor);
        if (!order.isInHouseDelivery()) {
            throw new ValidationException(
                    "Order " + order.getOrderCode() + " is not an in-house delivery order.");
        }
        return order;
    }

    /**
     * The shared "delivered" path: hop to {@code Delivered}, then settle to
     * {@code Closed} (prepaid) or {@code COD_Collected} (+ a COD receivable) using
     * the same pure {@link SettlementProcessor} logic a QuikShipX delivery uses.
     */
    private void deliverAndSettle(OrderEntity order, Actor workflowActor) {
        orderWorkflowService.applyTransition(order, OrderStatus.DELIVERED, workflowActor);
        SettlementResult result = settlementProcessor.onDelivered(view(order));
        orderWorkflowService.applyTransition(order, result.newStatus(), workflowActor);
        order.setCustomerOutstanding(BigDecimal.ZERO);
        result.receivable().ifPresent(r -> recordReceivable(order));
        publishDeliveryLedgerPost(order);
    }

    /**
     * Enqueues the delivery-receipt ledger posting for a COD order in this same
     * transaction, so the COD cash collected is booked against Cash / Sundry Debtors
     * dated the DELIVERY date.
     *
     * <p>Without this the order's sales voucher leaves Sundry Debtors permanently
     * debited: nothing else ever credits it back for a pure-COD order. No-op for a
     * prepaid order (nothing was collected on delivery). Like every other ledger
     * publish, the outbox row commits atomically with the delivery and a downstream
     * posting failure can never roll back or alter the order.
     */
    private void publishDeliveryLedgerPost(OrderEntity order) {
        if (outboxEventPublisher == null) {
            return;
        }
        BigDecimal cod = order.getCodAmount();
        if (cod == null || cod.signum() <= 0) {
            return;
        }
        outboxEventPublisher.publishLedgerPost(LEDGER_SOURCE_ORDER_DELIVERY, order.getId());
    }

    /** Trims a string and returns null when the result is empty/blank. */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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
        OrderEntity order = requireInHouseOrderFor(orderId, actor);

        // Hop 1: -> Delivered. Illegal from the current status (409) surfaces
        // naturally from the state machine — e.g. an order still awaiting
        // handover cannot be marked delivered.
        // Hop 2: settle exactly like a QuikShipX delivery would (Closed if
        // prepaid, else COD_Collected + a COD receivable), reusing the same pure
        // settlement logic as CourierStatusApplier. No courier company for an
        // in-house order — pass 0L, matching the existing convention for an
        // unassigned courier (CourierStatusApplier#view).
        deliverAndSettle(order, Actor.user(actor, SOURCE));

        if (request != null && request.note() != null && !request.note().isBlank()) {
            log.debug("Order {} manually marked delivered by {} — note: {}",
                    order.getOrderCode(), actor.username(), request.note().trim());
        }

        OrderEntity saved = orderRepository.save(order);
        outboxEventPublisher.publishOrderStatusChanged(
                saved.getId(), saved.getOrderCode(), saved.getOrderStatus().name());
        return OrderResponse.from(saved);
    }

    /**
     * Records the COD receivable for a manually-delivered order. Idempotent: never
     * creates a second COD receivable for the same order.
     *
     * <p>{@code courierCompanyId} is {@code null}, not {@code 0}: an in-house
     * delivery has no courier company, and {@code receivables.courier_company_id}
     * is a nullable FK to {@code courier_companies(id)} — passing {@code 0L} used
     * to violate that constraint and fail the whole request with a 500. (The pure
     * {@link OrderSettlementView} still uses the {@code 0L} sentinel; that value
     * only feeds the settlement math, never the database.)
     */
    private void recordReceivable(OrderEntity order) {
        if (!receivableRepository.findByOrderIdAndType(order.getId(), ReceivableType.COD_RECEIVABLE).isEmpty()) {
            return;
        }
        receivableRepository.save(new ReceivableEntity(
                order.getId(), null, ReceivableType.COD_RECEIVABLE, order.getCodAmount()));
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
