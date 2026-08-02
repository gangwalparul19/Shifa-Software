package com.shifa.oms.integration.quikshipx;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.label.LabelService;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.statemachine.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Set;

/**
 * Returns fulfilment authority for a single order to Shifa OMS (Req 13.3–13.5, 13.8–13.10).
 *
 * <p>The operational escape hatch. If QuikShipX is unreachable, or a shipment is stuck in
 * their portal, an admin flips one order back onto the internal path: the internal Code128
 * label becomes printable again, the order rejoins the packing queue, and QuikShipX status
 * events for it are ignored. No redeploy, no flag flip, no downtime for other orders.
 *
 * <p>Deliberately one-way. There is no "hand it back to QuikShipX" operation, because the
 * packer may already have stuck an internal label on the parcel — reverting would leave two
 * labels and two systems each believing they own the shipment.
 */
@Service
public class FallbackModeService {

    private static final Logger log = LoggerFactory.getLogger(FallbackModeService.class);

    /**
     * Statuses past the point where taking an order back means anything: the parcel is
     * delivered, settled, or was never going to ship (Req 13.10).
     */
    private static final Set<OrderStatus> INELIGIBLE = EnumSet.of(
            OrderStatus.DELIVERED,
            OrderStatus.COD_COLLECTED,
            OrderStatus.CLOSED,
            OrderStatus.REJECTED,
            OrderStatus.CANCELLED,
            OrderStatus.RTO,
            OrderStatus.REDISPATCH);

    private final OrderRepository orderRepository;
    private final LabelService labelService;
    private final AuditService auditService;

    public FallbackModeService(OrderRepository orderRepository,
                               LabelService labelService,
                               AuditService auditService) {
        this.orderRepository = orderRepository;
        this.labelService = labelService;
        this.auditService = auditService;
    }

    /** The outcome of enabling fallback mode. */
    public record Result(Long orderId, String orderCode, boolean fallbackMode,
                         OrderStatus status, boolean alreadyEnabled) {
    }

    /**
     * Enables fallback mode for one order.
     *
     * @param actor the admin's username, recorded on the audit event and the label
     * @throws ResourceNotFoundException when the order does not exist
     * @throws ValidationException       when the order's status makes it ineligible, or the
     *                                   internal label cannot be rendered
     */
    @Transactional
    public Result enable(Long orderId, String actor) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + orderId + " does not exist."));

        if (order.isFallbackMode()) {
            // Idempotent: a double-click must not re-audit or re-render (Req 13.3).
            return new Result(order.getId(), order.getOrderCode(), true, order.getOrderStatus(), true);
        }
        if (INELIGIBLE.contains(order.getOrderStatus())) {
            throw new ValidationException("Order " + order.getOrderCode() + " is "
                    + order.getOrderStatus() + ", so returning it to internal fulfilment would "
                    + "have no effect.");
        }

        order.setFallbackMode(true);

        // Make the internal label reachable again. An order still at APPROVED has never
        // had one, so generate it and advance to LABEL_GENERATED, which also places the
        // order in the packing queue (Req 13.4, 13.8). Further along, the label is
        // rendered on demand from current state, so we only prove it renders.
        try {
            if (order.getOrderStatus() == OrderStatus.APPROVED) {
                labelService.generateInternalLabelOnApproval(order, actor);
            }
        } catch (RuntimeException e) {
            // Req 13.9: keep fallback mode ON and the status unchanged, but say so — the
            // packer can retry the print rather than being silently left with no label.
            log.warn("Internal label generation failed while enabling fallback mode for order {}: {}",
                    order.getOrderCode(), e.getMessage());
            orderRepository.save(order);
            auditFallback(order, actor, " (label generation failed: " + e.getMessage() + ")");
            throw new ValidationException("Order " + order.getOrderCode()
                    + " was returned to internal fulfilment, but the label could not be generated ("
                    + e.getMessage() + "). Use Print label to retry.");
        }

        orderRepository.save(order);
        auditFallback(order, actor, "");

        log.info("Order {} returned to internal fulfilment by {} (status {})",
                order.getOrderCode(), actor, order.getOrderStatus());

        return new Result(order.getId(), order.getOrderCode(), true, order.getOrderStatus(), false);
    }

    /** Exactly one audit event naming the admin and the order (Req 13.5). */
    private void auditFallback(OrderEntity order, String actor, String suffix) {
        auditService.record(AuditActions.ORDER_FALLBACK_MODE_ENABLED, AuditActions.ENTITY_ORDER,
                String.valueOf(order.getId()),
                "Returned order " + order.getOrderCode() + " to internal fulfilment"
                        + " (status " + order.getOrderStatus() + ")" + suffix);
    }
}
