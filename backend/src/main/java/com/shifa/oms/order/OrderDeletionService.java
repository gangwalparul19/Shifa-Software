package com.shifa.oms.order;

import com.shifa.oms.adminnotification.AdminNotificationRepository;
import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.courier.CourierRecordRepository;
import com.shifa.oms.lead.LeadRepository;
import com.shifa.oms.ledger.VoucherRepository;
import com.shifa.oms.ledger.autopost.SourceType;
import com.shifa.oms.platform.outbox.OutboxEventRepository;
import com.shifa.oms.quikshipx.OrderShipmentRepository;
import com.shifa.oms.reconciliation.ReceivableRepository;
import com.shifa.oms.returns.OrderReturnRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin "delete order" (delete-order feature): a reversible <strong>soft
 * delete</strong>. "Deleting" an order sets {@code orders.active = false} rather
 * than removing the row, so the order (and all its children + audit trail) stays
 * intact but disappears everywhere in the app — the orders list, dashboards,
 * sales reports and the Profit &amp; Loss all exclude inactive orders (via the
 * {@code @SQLRestriction} on {@link OrderEntity} for entity reads, and an
 * explicit {@code AND o.active = 1} on the native aggregate queries).
 *
 * <p>This replaced an earlier hard delete that failed on foreign-key
 * constraints (e.g. the QuikShipX {@code order_shipments} FK) and surfaced as a
 * generic 500. The soft delete has no such fragility: it touches only the one
 * boolean on the order row, so FK children are never a problem.
 *
 * <p><strong>Why a delete is still sometimes refused.</strong> An order that was
 * approved into the General Ledger has a posted Sales voucher (and possibly a
 * delivery-receipt voucher). Hiding it would retroactively drop it from a filed
 * GST period and the P&amp;L, so a delete is <em>refused</em> (400
 * {@link ValidationException}) when a ledger voucher exists for the order — the
 * admin should <em>cancel</em> such an order instead (which keeps the GL + GST
 * intact, and a cancelled order is already excluded from every sales figure). In
 * practice this means pending / rejected / payment-rejected / never-approved
 * cancelled orders can be deleted, while a delivered/approved order cannot.
 */
@Service
public class OrderDeletionService {

    private final OrderRepository orderRepository;
    private final ReceivableRepository receivableRepository;
    private final CourierRecordRepository courierRecordRepository;
    private final OrderReturnRepository orderReturnRepository;
    private final OrderShipmentRepository orderShipmentRepository;
    private final AdminNotificationRepository adminNotificationRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final LeadRepository leadRepository;
    private final VoucherRepository voucherRepository;
    private final AuditService auditService;

    @Autowired
    public OrderDeletionService(OrderRepository orderRepository,
                                ReceivableRepository receivableRepository,
                                CourierRecordRepository courierRecordRepository,
                                OrderReturnRepository orderReturnRepository,
                                OrderShipmentRepository orderShipmentRepository,
                                AdminNotificationRepository adminNotificationRepository,
                                OutboxEventRepository outboxEventRepository,
                                LeadRepository leadRepository,
                                VoucherRepository voucherRepository,
                                AuditService auditService) {
        this.orderRepository = orderRepository;
        this.receivableRepository = receivableRepository;
        this.courierRecordRepository = courierRecordRepository;
        this.orderReturnRepository = orderReturnRepository;
        this.orderShipmentRepository = orderShipmentRepository;
        this.adminNotificationRepository = adminNotificationRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.leadRepository = leadRepository;
        this.voucherRepository = voucherRepository;
        this.auditService = auditService;
    }

    /**
     * Soft-deletes the order {@code id} by setting {@code active = false} (ADMIN
     * only; the controller enforces the role). Throws 404 if the order does not
     * exist (or is already inactive — the {@code @SQLRestriction} hides it) and
     * 400 ({@link ValidationException}) if a ledger voucher was already posted
     * for it (hiding it would corrupt the GL / a filed GST period — cancel it
     * instead). The order row, its children and its audit trail are preserved;
     * the order simply disappears from every list, dashboard, report and the
     * P&amp;L. Idempotent: deleting an already-active order flips it once.
     *
     * @return the code of the deleted order (for the confirmation message)
     */
    @Transactional
    public String delete(Long id, AuthPrincipal admin) {
        OrderEntity order = orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + id + " does not exist."));
        requireNoLedgerVoucher(order);

        String code = order.getOrderCode();
        order.setActive(false);
        orderRepository.save(order);

        if (auditService != null) {
            auditService.record(AuditActions.ORDER_DELETED, AuditActions.ENTITY_ORDER,
                    String.valueOf(id), "Deleted (deactivated) order " + code
                            + " — hidden from all lists, reports and P&L.");
        }
        return code;
    }

    /**
     * The soft-deleted (inactive) orders, newest-deleted first — backs the admin
     * "Deleted orders" view. Reads the inactive rows directly (bypassing the
     * {@code @SQLRestriction}) and maps them to the compact list shape.
     */
    @Transactional(readOnly = true)
    public java.util.List<com.shifa.oms.order.dto.OrderSummaryResponse> listDeleted() {
        return orderRepository.findDeleted().stream()
                .map(com.shifa.oms.order.dto.OrderSummaryResponse::from)
                .toList();
    }

    /**
     * Restores a previously soft-deleted order by setting {@code active = true}
     * again (ADMIN only; the controller enforces the role). The order reappears
     * everywhere — lists, dashboards, reports and the P&amp;L. Throws 404 if no
     * order with that id exists; a no-op flip if it was already active. Loads the
     * order via the inactive-inclusive finder (the {@code @SQLRestriction} would
     * otherwise hide a deleted order).
     *
     * @return the code of the restored order
     */
    @Transactional
    public String restore(Long id, AuthPrincipal admin) {
        OrderEntity order = orderRepository.findByIdIncludingInactive(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + id + " does not exist."));
        String code = order.getOrderCode();
        order.setActive(true);
        orderRepository.save(order);

        if (auditService != null) {
            auditService.record(AuditActions.ORDER_RESTORED, AuditActions.ENTITY_ORDER,
                    String.valueOf(id), "Restored order " + code + " — visible again in all lists, reports and P&L.");
        }
        return code;
    }

    /**
     * Refuses the delete when a ledger voucher (Sales at approval, or the COD
     * delivery receipt) was posted for this order — destroying it would unbalance
     * the General Ledger. Such an order must be cancelled, not deleted.
     */
    private void requireNoLedgerVoucher(OrderEntity order) {
        Long id = order.getId();
        boolean posted = voucherRepository.findBySourceTypeAndSourceId(SourceType.ORDER.name(), id).isPresent()
                || voucherRepository.findBySourceTypeAndSourceId(SourceType.ORDER_DELIVERY.name(), id).isPresent();
        if (posted) {
            throw new ValidationException("Order " + order.getOrderCode()
                    + " has been approved into the accounts ledger and cannot be deleted. "
                    + "Cancel it instead — a cancelled order is excluded from all sales figures.");
        }
    }
}
