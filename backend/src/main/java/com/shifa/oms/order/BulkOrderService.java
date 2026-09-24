package com.shifa.oms.order;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.order.dto.BulkActionResult;
import com.shifa.oms.order.dto.BulkPreviewResponse;
import com.shifa.oms.order.dto.UpdateDeliveryStatusRequest;
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
    /**
     * Per-order in-house delivery-status primitive (nullable): backs the bulk
     * in-house dispatch status update. Null under the legacy 3-arg constructor
     * (existing tests) — {@link #bulkUpdateInHouseDeliveryStatus} then skips.
     */
    private final ManualDeliveryService manualDeliveryService;

    /** Legacy constructor (tests): no bulk delivery-status update. */
    public BulkOrderService(AdminOrderService adminOrderService,
                            PackingService packingService,
                            OrderRepository orderRepository) {
        this(adminOrderService, packingService, orderRepository, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public BulkOrderService(AdminOrderService adminOrderService,
                            PackingService packingService,
                            OrderRepository orderRepository,
                            ManualDeliveryService manualDeliveryService) {
        this.adminOrderService = adminOrderService;
        this.packingService = packingService;
        this.orderRepository = orderRepository;
        this.manualDeliveryService = manualDeliveryService;
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
                packingService.scan(order.getOrderCode(), actor);
                result.succeeded(id);
            } catch (RuntimeException ex) {
                log.debug("Bulk mark-packed skipped order {}: {}", id, ex.getMessage());
                result.skipped(id, reasonOf(ex));
            }
        }
        return result.build();
    }

    /**
     * Sets the delivery status of every eligible IN-HOUSE order in {@code ids} to
     * {@code target} (Out_For_Delivery / Delivered / Dispatched / In_Transit /
     * Customer_Rejected / Delivery_Failed), for the dispatch queue's multi-select.
     *
     * <p>Each order is delegated to {@link ManualDeliveryService#updateDeliveryStatus}
     * in its own transaction, so it reuses exactly the same rules as the
     * order-detail "Update status" action: the in-house-only gate (courier orders
     * are tracked by the partner and are skipped with a reason), the state-machine
     * legality check, and — on {@code Delivered} — the settlement hop to
     * {@code Closed}/{@code COD_Collected}. A failure/skip on one order never rolls
     * back the others (partial success, mirroring {@link #bulkApprove}).
     *
     * @param ids    the order ids to update
     * @param target the delivery status to set on each
     * @param note   optional note (required by the per-order primitive for the
     *               failure outcomes Customer_Rejected / Delivery_Failed)
     * @param actor  the acting principal (ADMIN / PACKING_USER; a salesperson may
     *               only affect their own orders, enforced per-order)
     */
    public BulkActionResult bulkUpdateInHouseDeliveryStatus(List<Long> ids, OrderStatus target,
                                                            String note, AuthPrincipal actor) {
        BulkActionResult.Builder result = new BulkActionResult.Builder();
        if (manualDeliveryService == null) {
            // No primitive wired (legacy construction) — nothing can be updated.
            for (Long id : distinct(ids)) {
                result.skipped(id, "Bulk delivery-status update is unavailable.");
            }
            return result.build();
        }
        UpdateDeliveryStatusRequest request = new UpdateDeliveryStatusRequest(target, null, note);
        for (Long id : distinct(ids)) {
            try {
                manualDeliveryService.updateDeliveryStatus(id, actor, request);
                result.succeeded(id);
            } catch (RuntimeException ex) {
                log.debug("Bulk delivery-status update skipped order {}: {}", id, ex.getMessage());
                result.skipped(id, reasonOf(ex));
            }
        }
        return result.build();
    }

    /**
     * Read-only eligibility preview. This is advisory only: every mutation
     * still re-reads the order and re-checks the workflow in its own transaction.
     */
    public BulkPreviewResponse preview(String action, List<Long> ids) {
        String normalized = action == null ? "" : action.trim().toUpperCase();
        if (!List.of("APPROVE", "MARK_PACKED", "LABELS").contains(normalized)) {
            throw new com.shifa.oms.common.ValidationException(
                    "Unsupported bulk action. Use APPROVE, MARK_PACKED, or LABELS.");
        }
        List<BulkPreviewResponse.EligibleItem> eligible = new java.util.ArrayList<>();
        List<BulkPreviewResponse.IneligibleItem> ineligible = new java.util.ArrayList<>();
        for (Long id : distinct(ids)) {
            OrderEntity order = orderRepository.findById(id).orElse(null);
            if (order == null) {
                ineligible.add(new BulkPreviewResponse.IneligibleItem(id, null, null, "Order not found."));
                continue;
            }
            String reason = switch (normalized) {
                case "APPROVE" -> order.getOrderStatus() == OrderStatus.PENDING_ADMIN_APPROVAL
                        ? null : "Order is not awaiting approval.";
                case "MARK_PACKED" -> order.getOrderStatus() == OrderStatus.LABEL_GENERATED
                        ? null : "Order is not ready to pack.";
                case "LABELS" -> null;
                default -> "Unsupported bulk action.";
            };
            if (reason == null) {
                eligible.add(new BulkPreviewResponse.EligibleItem(
                        order.getId(), order.getOrderCode(), order.getOrderStatus()));
            } else {
                ineligible.add(new BulkPreviewResponse.IneligibleItem(
                        order.getId(), order.getOrderCode(), order.getOrderStatus(), reason));
            }
        }
        return new BulkPreviewResponse(normalized, distinct(ids).size(),
                List.copyOf(eligible), List.copyOf(ineligible));
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
