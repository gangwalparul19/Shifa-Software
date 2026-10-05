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
import com.shifa.oms.platform.outbox.OutboxEvent;
import com.shifa.oms.platform.outbox.OutboxEventRepository;
import com.shifa.oms.quikshipx.OrderShipmentRepository;
import com.shifa.oms.reconciliation.ReceivableRepository;
import com.shifa.oms.returns.OrderReturnRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin "delete order" (delete-order feature): permanently removes an order and
 * every child row that references it, in one transaction, so a mistaken /
 * spam / abandoned order can be wiped entirely rather than left lingering as a
 * rejected or cancelled record.
 *
 * <p><strong>Why a delete is sometimes refused.</strong> An order that was
 * approved into the General Ledger has a posted Sales voucher (and possibly a
 * delivery-receipt voucher). Hard-deleting it would silently unbalance the
 * ledger and corrupt already-filed GST periods, so a delete is <em>refused</em>
 * (409 {@link OrderNotEditableException}) when a ledger voucher exists for the
 * order — the admin should <em>cancel</em> such an order instead (which keeps
 * the audit trail and the GL intact, and the cancelled order is already excluded
 * from every sales figure). In practice this means pending / rejected /
 * payment-rejected orders (and cancelled orders that never reached approval)
 * delete cleanly, while a delivered/approved order cannot be destroyed.
 *
 * <p><strong>What is removed.</strong> The non-cascading child rows are deleted
 * children-first (receivables, courier record, returns, QuikShipX shipment,
 * order-scoped admin notifications and outbox events), the lead link is nulled
 * (the lead survives), and finally {@code orderRepository.delete(order)} removes
 * the order plus its JPA-cascaded children (line items, payments, status
 * history, payment screenshots). Mirrors the children-first order used by the
 * V51 reset migration, extended to the tables added since (V65 screenshots, the
 * QuikShipX shipment, notifications, outbox).
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
     * Permanently deletes the order {@code id} and all its child rows (ADMIN
     * only; the controller enforces the role). Throws 404 if the order does not
     * exist and 409 ({@link OrderNotEditableException}) if a ledger voucher was
     * already posted for it (delete would corrupt the GL — cancel it instead).
     *
     * @return the code of the deleted order (for the confirmation message)
     */
    @Transactional
    public String delete(Long id, AuthPrincipal admin) {
        OrderEntity order = orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + id + " does not exist."));
        requireNoLedgerVoucher(order);

        String code = order.getOrderCode();

        // Unlink any lead that converted into this order (keep the lead).
        leadRepository.clearConvertedOrder(id);

        // Delete the non-cascading child rows (children first).
        receivableRepository.deleteByOrderId(id);
        courierRecordRepository.deleteByOrderId(id);
        orderReturnRepository.deleteByOrderId(id);
        orderShipmentRepository.deleteByOrderId(id);
        adminNotificationRepository.deleteByOrderId(id);
        outboxEventRepository.deleteByAggregateTypeAndAggregateId(OutboxEvent.AGGREGATE_ORDER, id);

        // Delete the order — JPA cascades line items, payments, status history
        // and payment screenshots (orphanRemoval on the OneToMany mappings).
        orderRepository.delete(order);

        if (auditService != null) {
            auditService.record(AuditActions.ORDER_DELETED, AuditActions.ENTITY_ORDER,
                    String.valueOf(id), "Deleted order " + code + " and all its records.");
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
