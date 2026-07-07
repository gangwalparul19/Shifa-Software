package com.shifa.oms.packing;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderStatusHistory;
import com.shifa.oms.packing.dto.PackingScanResponse;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import com.shifa.oms.statemachine.OrderStatus;
import com.shifa.oms.statemachine.OrderStatusLifecycle;
import com.shifa.oms.statemachine.OrderStatusStateMachine;
import com.shifa.oms.statemachine.StatusHistoryEntry;
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
    private final OrderStatusStateMachine stateMachine;

    public PackingService(OrderRepository orderRepository, OutboxEventPublisher outboxEventPublisher) {
        this.orderRepository = orderRepository;
        this.outboxEventPublisher = outboxEventPublisher;
        this.stateMachine = new OrderStatusStateMachine();
    }

    /**
     * Scans a barcode and, when valid, moves the matching order to {@code Packed}.
     *
     * @param barcode the scanned barcode value (the order's {@code order_code})
     * @param packedBy the packing user performing the scan (recorded as actor)
     * @return the success response with the packed order summary (Req 11.1)
     * @throws BarcodeNotRecognizedException when no order matches (Req 11.3)
     * @throws OrderNotPackableException when the order is not {@code Label_Generated} (Req 11.4)
     */
    @Transactional
    public PackingScanResponse scan(String barcode, String packedBy) {
        String code = barcode == null ? "" : barcode.trim();
        OrderEntity order = orderRepository.findByOrderCode(code)
                .orElseThrow(() -> new BarcodeNotRecognizedException(code));

        if (order.getOrderStatus() != OrderStatus.LABEL_GENERATED) {
            // Reject and surface the current status without mutating the order (Req 11.4).
            throw new OrderNotPackableException(order.getOrderCode(), order.getOrderStatus());
        }

        applyTransition(order, OrderStatus.PACKED, packedBy);
        OrderEntity saved = orderRepository.save(order);

        // Persist the packed event for the admin real-time notification (Req 11.2,
        // delivered by task 19). Same transaction → event and status commit together.
        outboxEventPublisher.publishOrderPacked(
                saved.getId(), saved.getOrderCode(), saved.getCustomerName(), packedBy);

        // Enqueue courier AWB assignment out-of-band (Req 12.1): the drainer (task 14)
        // calls the Courier API so a slow courier never blocks the packing scan.
        outboxEventPublisher.publishCourierAssign(saved.getId(), saved.getOrderCode());

        log.debug("Order {} scanned to PACKED by {}", saved.getOrderCode(), packedBy);
        return PackingScanResponse.packed(saved);
    }

    /**
     * Applies a transition using the shared state machine and mirrors the result
     * onto the aggregate: the new status plus a single status-history row
     * (Req 8.3, 8.4).
     */
    private void applyTransition(OrderEntity order, OrderStatus target, String actor) {
        OrderStatusLifecycle lifecycle = new OrderStatusLifecycle(order.getOrderStatus());
        StatusHistoryEntry entry = stateMachine.transition(lifecycle, target, actor, SOURCE_PACKING);
        order.setOrderStatus(entry.toStatus());
        order.addStatusHistory(new OrderStatusHistory(
                entry.fromStatus(), entry.toStatus(), entry.actor(), entry.source()));
    }
}
