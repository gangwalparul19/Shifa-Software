package com.shifa.oms.packing;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.User;
import com.shifa.oms.auth.UserRepository;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.order.Actor;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderWorkflowService;
import com.shifa.oms.order.dto.OrderResponse;
import com.shifa.oms.packing.dto.HandoverRequest;
import com.shifa.oms.packing.dto.PackingQueueResponse;
import com.shifa.oms.packing.dto.PackingQueueRow;
import com.shifa.oms.packing.dto.PackingScanPreviewResponse;
import com.shifa.oms.packing.dto.PackingScanResponse;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import com.shifa.oms.statemachine.OrderStatus;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Packing application service (design: "Order / OMS Module" — packing scan;
 * Req 11).
 *
 * <p>A packing user scans an order's internal-label barcode (whose value is the
 * order's {@code order_code}). {@link #scan(String, String)} resolves that code
 * to an order and applies one of three outcomes:
 * <ul>
 *   <li><strong>Packed</strong> (Req 11.1): the order is in
 *       {@code Label_Generated}, so it transitions to {@code Packed} through the
 *       shared {@link OrderStatusStateMachine} (actor = the packing user, source
 *       {@code PACKING}), writing one {@code status_history} row (Req 8.4). An
 *       {@code ORDER_PACKED} outbox event is persisted in the same transaction so
 *       the admin real-time notification (Req 11.2) is delivered by task 19 and
 *       never lost even if no admin is currently connected.</li>
 *   <li><strong>Not recognized</strong> (Req 11.3): no order matches the barcode
 *       — {@link BarcodeNotRecognizedException} (404).</li>
 *   <li><strong>Wrong status</strong> (Req 11.4): the order exists but is not in
 *       {@code Label_Generated} — {@link OrderNotPackableException} (409) carrying
 *       the current status, and the order is left unchanged.</li>
 * </ul>
 *
 * <p>The wrong-status case is enforced here (rather than relying solely on the
 * state machine) so the rejection can report the current status precisely; the
 * state machine would also reject the transition, but this gives the packer a
 * clearer, status-aware error.
 */
@Service
public class PackingService {

    private static final Logger log = LoggerFactory.getLogger(PackingService.class);

    private static final String SOURCE_PACKING = "PACKING";

    private final OrderRepository orderRepository;
    private final OutboxEventPublisher outboxEventPublisher;
    private final OrderWorkflowService orderWorkflowService;
    private final UserRepository userRepository;

    public PackingService(OrderRepository orderRepository, OutboxEventPublisher outboxEventPublisher,
                          OrderWorkflowService orderWorkflowService, UserRepository userRepository) {
        this.orderRepository = orderRepository;
        this.outboxEventPublisher = outboxEventPublisher;
        this.orderWorkflowService = orderWorkflowService;
        this.userRepository = userRepository;
    }

    /**
     * The packing team's work queues (oldest-first): orders awaiting packing
     * ({@code Label_Generated}), awaiting handover ({@code Packed}), and awaiting
     * dispatch ({@code Handed_To_Delivery}). Read-only; drives the Packing page
     * so the packer can see what to work on and reprint labels.
     */
    @Transactional(readOnly = true)
    public PackingQueueResponse queue() {
        // Courier-managed orders are excluded: their label is printed and their status
        // advanced in the courier's own portal, so leaving them here would have the
        // packer work the same parcel twice (Req 9.6). Orders in fallback mode stay
        // (Req 9.7). With the integration off, order_shipments is empty and this
        // returns exactly what the plain status finder returned.
        List<OrderEntity> pack =
                orderRepository.findPackingQueueByStatus(OrderStatus.LABEL_GENERATED.name());
        List<OrderEntity> handover =
                orderRepository.findPackingQueueByStatus(OrderStatus.PACKED.name());
        List<OrderEntity> dispatch =
                orderRepository.findPackingQueueByStatus(OrderStatus.HANDED_TO_DELIVERY.name());
        // Batch-resolve salesperson (created_by) names once for all three queues,
        // mirroring the reporting module's name resolution (full name, else username).
        Map<Long, String> names = resolveSalespersonNames(pack, handover, dispatch);
        return new PackingQueueResponse(rows(pack, names), rows(handover, names), rows(dispatch, names));
    }

    private Map<Long, String> resolveSalespersonNames(List<OrderEntity> pack,
                                                      List<OrderEntity> handover,
                                                      List<OrderEntity> dispatch) {
        Set<Long> ids = new HashSet<>();
        collectCreators(pack, ids);
        collectCreators(handover, ids);
        collectCreators(dispatch, ids);
        Map<Long, String> names = new HashMap<>();
        if (ids.isEmpty()) {
            return names;
        }
        for (User u : userRepository.findAllById(ids)) {
            String name = (u.getFullName() != null && !u.getFullName().isBlank())
                    ? u.getFullName() : u.getUsername();
            names.put(u.getId(), name);
        }
        return names;
    }

    private static void collectCreators(List<OrderEntity> orders, Set<Long> ids) {
        for (OrderEntity o : orders) {
            if (o.getCreatedBy() != null) {
                ids.add(o.getCreatedBy());
            }
        }
    }

    private static List<PackingQueueRow> rows(List<OrderEntity> orders, Map<Long, String> names) {
        return orders.stream()
                .map(o -> PackingQueueRow.from(
                        o, o.getCreatedBy() == null ? null : names.get(o.getCreatedBy())))
                .toList();
    }

    /**
     * Resolves a scanned internal-label barcode without changing the order. The
     * caller uses the returned next action to present confirmation before making
     * the corresponding authorised workflow request.
     *
     * @throws BarcodeNotRecognizedException when no order matches the barcode
     */
    @Transactional(readOnly = true)
    public PackingScanPreviewResponse preview(String barcode) {
        return PackingScanPreviewResponse.from(resolveBarcode(barcode));
    }

    /**
     * Scans a barcode and, when valid, moves the matching labelled order to
     * {@code Packed}. The final mutation deliberately rechecks the current state
     * because a preview is not a reservation.
     *
     * @param barcode the scanned barcode value (the order's {@code order_code})
     * @param actor   the packing user (or admin) performing the scan (recorded as actor,
     *                and authorized for the transition by role)
     * @return the success response with the packed order summary (Req 11.1)
     * @throws BarcodeNotRecognizedException when no order matches (Req 11.3)
     * @throws OrderNotPackableException when the order is not {@code Label_Generated} (Req 11.4)
     */
    @Transactional
    public PackingScanResponse scan(String barcode, AuthPrincipal actor) {
        OrderEntity order = resolveBarcode(barcode);

        if (order.getOrderStatus() != OrderStatus.LABEL_GENERATED) {
            // Recheck after preview and surface the current status without mutation.
            throw new OrderNotPackableException(order.getOrderCode(), order.getOrderStatus());
        }

        // Centralized transition: authorize (role) → apply (409 on illegal) →
        // status + one history row → audit, all in OrderWorkflowService.
        orderWorkflowService.applyTransition(
                order, OrderStatus.PACKED, Actor.user(actor, SOURCE_PACKING));
        OrderEntity saved = orderRepository.save(order);

        // Persist the packed event for the admin real-time notification (Req 11.2,
        // delivered by task 19). Same transaction → event and status commit together.
        outboxEventPublisher.publishOrderPacked(
                saved.getId(), saved.getOrderCode(), saved.getCustomerName(), actor.username());

        // Courier assignment is no longer enqueued here: under the role-based
        // workflow the COURIER_ASSIGN event is published by the dispatch action
        // (from Handed_To_Delivery), not immediately on pack (design §4, §6.3).
        log.debug("Order {} scanned to PACKED by {}", saved.getOrderCode(), actor.username());
        return PackingScanResponse.packed(saved);
    }

    private OrderEntity resolveBarcode(String barcode) {
        String code = barcode == null ? "" : barcode.trim();
        return orderRepository.findByOrderCode(code)
                .orElseThrow(() -> new BarcodeNotRecognizedException(code));
    }

    /**
     * Hands a packed order over to the delivery courier: transitions
     * {@code PACKED → HANDED_TO_DELIVERY} through the shared
     * {@link OrderWorkflowService} (Req 9.2, 9.3), recording one
     * {@code status_history} row and an audit entry (Req 9.6) and firing the
     * {@code HANDED_TO_DELIVERY} matrix notification (ADMIN in-app, Req 9.7).
     *
     * @param orderId the order to hand over
     * @param actor   the packing user (or admin) performing the handover
     * @return the updated order projection (now {@code HANDED_TO_DELIVERY})
     * @throws ResourceNotFoundException when no order matches (404)
     * @throws OrderNotHandoverableException when the order is not {@code PACKED} (409)
     */
    @Transactional
    public OrderResponse handover(Long orderId, AuthPrincipal actor, HandoverRequest request) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + orderId + " does not exist."));

        if (order.getOrderStatus() != OrderStatus.PACKED) {
            // Surface the current status without mutating the order (Req 9.4).
            throw new OrderNotHandoverableException(order.getOrderCode(), order.getOrderStatus());
        }

        // Capture who the order was handed to (product-audit §4.3), when supplied.
        if (request != null) {
            order.setHandoverDetails(
                    trimToNull(request.handoverName()), trimToNull(request.handoverPhone()));
        }

        // Centralized transition: authorize (role) → apply (409 on illegal) →
        // status + one history row → audit → matrix notification.
        orderWorkflowService.applyTransition(
                order, OrderStatus.HANDED_TO_DELIVERY, Actor.user(actor, SOURCE_PACKING));
        OrderEntity saved = orderRepository.save(order);

        log.debug("Order {} handed to delivery by {}", saved.getOrderCode(), actor.username());
        return OrderResponse.from(saved);
    }

    /**
     * Dispatches a handed-over order by enqueuing the {@code COURIER_ASSIGN}
     * outbox event (Req 10.1). The {@link com.shifa.oms.courier.OutboxCourierDrainer}
     * → {@code CourierAssignmentService.assignForOrder} (gated on
     * {@code HANDED_TO_DELIVERY}) then requests the AWB/label out-of-band and
     * advances {@code HANDED_TO_DELIVERY → COURIER_ASSIGNED}, so a slow courier
     * never blocks the dispatch action. The order itself is left in
     * {@code HANDED_TO_DELIVERY} synchronously.
     *
     * @param orderId the order to dispatch
     * @param actor   the packing user (or admin) performing the dispatch
     * @return the order projection (still {@code HANDED_TO_DELIVERY})
     * @throws ResourceNotFoundException when no order matches (404)
     * @throws OrderNotDispatchableException when the order is not {@code HANDED_TO_DELIVERY} (409)
     */
    @Transactional
    public OrderResponse dispatch(Long orderId, AuthPrincipal actor) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + orderId + " does not exist."));

        if (order.getOrderStatus() != OrderStatus.HANDED_TO_DELIVERY) {
            // Surface the current status without mutating the order.
            throw new OrderNotDispatchableException(order.getOrderCode(), order.getOrderStatus());
        }

        // Enqueue courier AWB assignment out-of-band (Req 10.1): the drainer picks
        // this PENDING event up and calls the Courier API in its own transaction.
        outboxEventPublisher.publishCourierAssign(order.getId(), order.getOrderCode());

        log.debug("Order {} dispatched (courier assignment enqueued) by {}",
                order.getOrderCode(), actor.username());
        return OrderResponse.from(order);
    }

    /**
     * Sets how many boxes an order ships in (product-audit §4.2 — multi-pack).
     * Additive: it only records the count so the label print can produce one
     * copy per box; the order status is unchanged.
     *
     * @param orderId the order to update
     * @param count   the number of boxes (1–50, validated at the DTO layer)
     * @return the updated order projection
     */
    @Transactional
    public OrderResponse setPackageCount(Long orderId, int count) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + orderId + " does not exist."));
        order.setPackageCount(count);
        OrderEntity saved = orderRepository.save(order);
        log.debug("Order {} package count set to {}", saved.getOrderCode(), saved.getPackageCount());
        return OrderResponse.from(saved);
    }

    /** Trims a string and returns null when the result is empty/blank. */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
