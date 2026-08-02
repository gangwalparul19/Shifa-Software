package com.shifa.oms.order;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.notification.NotificationContext;
import com.shifa.oms.notification.NotificationDispatcher;
import com.shifa.oms.notification.NotificationTarget;
import com.shifa.oms.statemachine.IllegalStatusTransitionException;
import com.shifa.oms.statemachine.OrderStatus;
import com.shifa.oms.statemachine.OrderStatusLifecycle;
import com.shifa.oms.statemachine.OrderStatusStateMachine;
import com.shifa.oms.statemachine.StatusHistoryEntry;
import com.shifa.oms.statemachine.TransitionAuthority;
import com.shifa.oms.statemachine.TransitionContext;
import com.shifa.oms.statemachine.UnauthorizedTransitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Objects;

/**
 * The single entry point for applying an order status transition (design §2.2,
 * §4.2, §4.3). Every lifecycle move — admin approve/reject, packing, courier
 * assignment, and courier webhook updates — routes through
 * {@link #applyTransition(OrderEntity, OrderStatus, Actor)} so authorization,
 * legality, history, and audit live in exactly one place.
 *
 * <p>For each transition it, in order:
 * <ol>
 *   <li><b>authorizes</b> the actor's role against the
 *       {@link TransitionAuthority} — a legal edge that the actor's role may not
 *       trigger yields an {@link UnauthorizedTransitionException} (HTTP 403) and
 *       leaves the order unchanged; the automatic {@code SYSTEM} actor is
 *       authorized for courier-driven edges (Req 12.5, 1.5, 2.7, 15.5);</li>
 *   <li><b>validates and applies</b> the move through the
 *       {@link OrderStatusStateMachine} — an illegal transition (absent from the
 *       transition table) yields the existing
 *       {@link IllegalStatusTransitionException} (HTTP 409) and leaves the order
 *       unchanged (Req 12.4, §4.3);</li>
 *   <li><b>persists</b> the new status on the aggregate and appends
 *       <em>exactly one</em> {@link OrderStatusHistory} row (Req 12.6, 15.1);</li>
 *   <li><b>audits</b> the transition, attributing automatic transitions to the
 *       {@code SYSTEM} actor (Req 15.5).</li>
 * </ol>
 *
 * <p>Legality is checked <em>before</em> authorization so an illegal move surfaces
 * as a 409 ("not allowed from here") rather than a 403 ("not allowed for you"),
 * preserving the two clean failure modes (§4.2). The aggregate is mutated in
 * place; the calling service persists it within its own transaction (so a single
 * courier update that settles across two transitions still commits atomically).
 */
@Service
public class OrderWorkflowService {

    /** Actor label recorded on audit rows for automatic (SYSTEM) transitions (Req 15.5). */
    private static final String SYSTEM_AUDIT_ACTOR = "SYSTEM";

    private final TransitionAuthority transitionAuthority;
    private final OrderStatusStateMachine stateMachine;
    private final AuditService auditService;

    /**
     * The matrix-driven notification fan-out consulted after every successful
     * transition (design §2.2, §5.2, Req 13.2, 14.1). {@code null} in the pure
     * unit/property tests that construct this service without Spring, in which
     * case no notifications are enqueued (behaviour-preserving for those tests).
     */
    private final NotificationDispatcher notificationDispatcher;

    /**
     * Whether an external courier owns an order's fulfilment (Req 9.1). {@code null}
     * in the pure unit/property tests and whenever the courier integration is absent,
     * in which case no order is managed and authorization is exactly the pre-existing
     * role-based behaviour.
     */
    private final OrderFulfilmentOwnership fulfilmentOwnership;

    /** Test constructor: no notification fan-out, system clock. */
    public OrderWorkflowService(AuditService auditService) {
        this(auditService, Clock.systemUTC(), null, null);
    }

    /**
     * Test constructor with an injected clock (deterministic history/audit
     * timestamps) and no notification fan-out.
     */
    public OrderWorkflowService(AuditService auditService, Clock clock) {
        this(auditService, clock, null, null);
    }

    /**
     * Test / legacy constructor: wires the {@link NotificationDispatcher} but no
     * fulfilment ownership, so no order is courier-managed and authorization behaves
     * exactly as it did before the courier integration.
     */
    public OrderWorkflowService(AuditService auditService, NotificationDispatcher notificationDispatcher) {
        this(auditService, Clock.systemUTC(), notificationDispatcher, null);
    }

    /**
     * Production constructor (Spring): additionally wires
     * {@link OrderFulfilmentOwnership} so a courier-managed order denies human
     * transitions (Req 9.1).
     *
     * <p>This is the ONLY constructor carrying {@code @Autowired}. A service with
     * several constructors and no annotation on the primary one fails the entire
     * application context at startup — exactly how {@code PaymentVerificationService}
     * took production down once.
     */
    @Autowired
    public OrderWorkflowService(AuditService auditService,
                                NotificationDispatcher notificationDispatcher,
                                @Autowired(required = false) OrderFulfilmentOwnership fulfilmentOwnership) {
        this(auditService, Clock.systemUTC(), notificationDispatcher, fulfilmentOwnership);
    }

    private OrderWorkflowService(AuditService auditService, Clock clock,
                                 NotificationDispatcher notificationDispatcher,
                                 OrderFulfilmentOwnership fulfilmentOwnership) {
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.transitionAuthority = new TransitionAuthority();
        this.stateMachine = new OrderStatusStateMachine(Objects.requireNonNull(clock, "clock"));
        this.notificationDispatcher = notificationDispatcher;
        this.fulfilmentOwnership = fulfilmentOwnership;
    }

    /**
     * Applies a transition of {@code order} to {@code target} on behalf of
     * {@code actor}, centralizing authorization, legality, history, and audit.
     *
     * @param order  the order aggregate, mutated in place on success (never {@code null})
     * @param target the requested target status (never {@code null})
     * @param actor  the triggering principal — a staff role or {@code SYSTEM} (never {@code null})
     * @return the single {@link StatusHistoryEntry} recorded for the transition
     * @throws UnauthorizedTransitionException if the (legal) transition is not permitted for the actor (403)
     * @throws IllegalStatusTransitionException if the transition is illegal from the current status (409)
     */
    public StatusHistoryEntry applyTransition(OrderEntity order, OrderStatus target, Actor actor) {
        return applyTransition(order, target, actor, null);
    }

    /**
     * Applies a transition and, after it succeeds, enqueues the matrix
     * notifications for the entered status using {@code whatsAppOverride} as the
     * WhatsApp customer context when provided (carrying courier tracking details
     * for {@code DISPATCHED}). When {@code null}, a basic customer context is
     * built from the order aggregate.
     *
     * @param whatsAppOverride optional pre-resolved WhatsApp context (nullable)
     */
    public StatusHistoryEntry applyTransition(OrderEntity order, OrderStatus target, Actor actor,
                                              NotificationContext whatsAppOverride) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(actor, "actor");

        OrderStatus from = order.getOrderStatus();

        // (a) Authorize the actor — but only when the edge is legal, so an illegal
        // transition still surfaces as the existing 409 below (not a 403).
        if (stateMachine.isLegal(from, target)) {
            TransitionContext context = contextOf(order);
            if (actor.isSystem()) {
                transitionAuthority.assertSystemAuthorized(from, target, context);
            } else {
                transitionAuthority.assertAuthorized(from, target, actor.role(), context);
            }
        }

        // (b) Validate + apply legality via the shared state machine (409 + no-op on illegal).
        OrderStatusLifecycle lifecycle = new OrderStatusLifecycle(from);
        StatusHistoryEntry entry =
                stateMachine.transition(lifecycle, target, actor.name(), actor.source());

        // (c) Persist status + exactly one status_history row on the aggregate.
        order.setOrderStatus(entry.toStatus());
        order.addStatusHistory(new OrderStatusHistory(
                entry.fromStatus(), entry.toStatus(), entry.actor(), entry.source()));

        // (d) Audit the transition (SYSTEM actor for automatic transitions, Req 15.5).
        writeAudit(order, entry, actor);

        // (e) Consult the NotificationMatrix and enqueue exactly its set for the
        // entered status, in this same transaction (Req 13.2, 14.1). No-op in the
        // pure tests that construct this service without a dispatcher.
        if (notificationDispatcher != null) {
            notificationDispatcher.dispatch(targetOf(order), entry.toStatus(), whatsAppOverride);
        }

        return entry;
    }

    /**
     * Assembles the extra facts the authority needs about this order (Req 9).
     *
     * <p>With no {@link OrderFulfilmentOwnership} wired — every pure test, and any
     * deployment without the courier integration — this yields
     * {@link TransitionContext#LEGACY}, under which the authority's decisions are
     * definitionally identical to the pre-existing ones.
     */
    private TransitionContext contextOf(OrderEntity order) {
        boolean managed = fulfilmentOwnership != null && fulfilmentOwnership.isCourierManaged(order);
        OrderSource source = order.getSource();
        TransitionContext.ChannelView channel =
                source != null && source.isShopify()
                        ? TransitionContext.ChannelView.EXTERNAL_STOREFRONT
                        : TransitionContext.ChannelView.INTERNAL;
        return new TransitionContext(channel, managed, order.isFallbackMode());
    }

    /** Assembles the notification facts for an order from the aggregate. */
    private static NotificationTarget targetOf(OrderEntity order) {
        return new NotificationTarget(
                order.getId(),
                order.getOrderCode(),
                order.getCustomerMobile(),
                order.getCustomerEmail(),
                order.getCustomerName(),
                order.getCreatedBy(),
                order.getPaymentStatus(),
                order.getCodAmount());
    }

    private void writeAudit(OrderEntity order, StatusHistoryEntry entry, Actor actor) {
        String entityId = order.getId() == null ? null : String.valueOf(order.getId());
        String summary = "Order " + order.getOrderCode() + " "
                + entry.fromStatus() + " \u2192 " + entry.toStatus()
                + " by " + entry.actor();
        if (actor.isSystem()) {
            // Attribute automatic transitions to SYSTEM rather than the (absent)
            // security-context principal (Req 15.5).
            auditService.record(null, SYSTEM_AUDIT_ACTOR, AuditActions.ORDER_STATUS_CHANGED,
                    AuditActions.ENTITY_ORDER, entityId, summary);
        } else {
            // Human transition: resolve the acting principal from the security context.
            auditService.record(AuditActions.ORDER_STATUS_CHANGED,
                    AuditActions.ENTITY_ORDER, entityId, summary);
        }
    }
}
