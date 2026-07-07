package com.shifa.oms.order;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.order.dto.BulkActionResult;
import com.shifa.oms.packing.PackingService;
import com.shifa.oms.statemachine.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Bulk admin order actions with per-id partial-success semantics
 * (ROADMAP 2.2 "Wave 2").
 *
 * <p>Each action iterates the requested ids and delegates the actual state
 * change to the EXISTING single-order service that owns the transition, so the
 * order state machine is never bypassed:
 * <ul>
 *   <li><strong>bulk-approve</strong> reuses {@link AdminOrderService#approve}
 *       ({@code Pending_Admin_Approval → Approved → Label_Generated});</li>
 *   <li><strong>bulk-mark-packed</strong> reuses {@link PackingService#scan}
 *       ({@code Label_Generated → Packed}, keyed by the order's barcode/code).</li>
 * </ul>
 *
 * <p>Both {@link AdminOrderService} and {@link PackingService} are separate
 * Spring beans whose per-order methods are {@code @Transactional}; because this
 * service invokes them through their proxies, <em>each order is processed in its
 * own transaction</em>. A single ineligible/failed order is therefore skipped
 * with a reason and never rolls back the orders that did succeed. Ids that are
 * unknown, or not in the required state, are collected into the
 * {@link BulkActionResult#skipped() skipped} list rather than failing the whole
 * request.
 */
@Service
public class BulkOrderService {

    private static final Logger log = LoggerFactory.getLogger(BulkOrderService.class);

    private final AdminOrderService adminOrderService;
    private final PackingService packingService;
    private final OrderRepository orderRepository;

    public BulkOrderService(AdminOrderService adminOrderService,
                            PackingService packingService,
                            OrderRepository orderRepository) {
        this.adminOrderService = adminOrderService;
        this.packingService = packingService;
        this.orderRepository = orderRepository;
    }

    /**
     * Approves every eligible order in {@code ids}. An order is eligible only
     * when it is currently {@code Pending_Admin_Approval}; anything else (unknown
     * id, already approved, rejected, cancelled, ...) is skipped with a reason.
     * Approval goes through {@link AdminOrderService#approve}, so it also
     * auto-generates the internal label exactly like the single-order path.
     */
    public BulkActionResult bulkApprove(List<Long> ids, AuthPrincipal admin) {
        BulkActionResult.Builder result = new BulkActionResult.Builder();
        for (Long id : distinct(ids)) {
            OrderEntity order = orderRepository.findById(id).orElse(null);
            if (order == null) {
                result.skipped(id, "Order not found.");
                continue;
            }
            if (order.getOrderStatus() != OrderStatus.PENDING_ADMIN_APPROVAL) {
                result.skipped(id, "Order is not awaiting approval (status: "
                        + order.getOrderStatus() + ").");
                continue;
            }
            try {
                adminOrderService.approve(id, admin);
                result.succeeded(id);
            } catch (RuntimeException ex) {
                log.debug("Bulk approve skipped order {}: {}", id, ex.getMessage());
                result.skipped(id, reasonOf(ex));
            }
        }
        return result.build();
    }

    /**
     * Marks every eligible order in {@code ids} as packed. An order is eligible
     * only when it is currently {@code Label_Generated}; anything else is skipped
     * with a reason. The transition reuses {@link PackingService#scan} (keyed by
     * the order's {@code order_code}), so it emits the same packed / courier-assign
     * events as a barcode scan.
     */
    public BulkActionResult bulkMarkPacked(List<Long> ids, AuthPrincipal actor) {
        BulkActionResult.Builder result = new BulkActionResult.Builder();
        for (Long id : distinct(ids)) {
            OrderEntity order = orderRepository.findById(id).orElse(null);
            if (order == null) {
                result.skipped(id, "Order not found.");
                continue;
            }
            if (order.getOrderStatus() != OrderStatus.LABEL_GENERATED) {
                result.skipped(id, "Order is not ready to pack (status: "
                        + order.getOrderStatus() + ").");
                continue;
            }
            try {
                packingService.scan(order.getOrderCode(), actor.username());
                result.succeeded(id);
            } catch (RuntimeException ex) {
                log.debug("Bulk mark-packed skipped order {}: {}", id, ex.getMessage());
                result.skipped(id, reasonOf(ex));
            }
        }
        return result.build();
    }

    /** De-duplicates the requested ids while preserving request order. */
    private static List<Long> distinct(List<Long> ids) {
        return List.copyOf(new LinkedHashSet<>(ids));
    }

    private static String reasonOf(RuntimeException ex) {
        String message = ex.getMessage();
        return (message == null || message.isBlank()) ? "Could not be processed." : message;
    }
}
