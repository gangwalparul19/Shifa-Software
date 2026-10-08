package com.shifa.oms.procurement;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.common.PageResponse;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.inventory.StockService;
import com.shifa.oms.procurement.dto.CreatePurchaseOrderRequest;
import com.shifa.oms.procurement.dto.PurchaseOrderItemRequest;
import com.shifa.oms.procurement.dto.PurchaseOrderResponse;
import com.shifa.oms.procurement.dto.PurchaseOrderSummaryResponse;
import com.shifa.oms.procurement.dto.ReceiveLineRequest;
import com.shifa.oms.procurement.dto.ReceivePurchaseOrderRequest;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import com.shifa.oms.product.ProductRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Purchase-order application service (Feature C2).
 *
 * <p>Owns the PO lifecycle:
 * <ul>
 *   <li><strong>create</strong> — validates the supplier and each product,
 *       allocates a stable {@code po_number} (PO-0001, ...) from the row-locked
 *       {@link PurchaseOrderSequence}, snapshots the line items and computes
 *       {@code total_amount}. New POs start {@link PurchaseOrderStatus#ORDERED}.</li>
 *   <li><strong>receive</strong> — the receiving flow that feeds inventory: for
 *       each received quantity it calls {@link StockService#restock} so on-hand
 *       stock increases (a RESTOCK movement), bumps each line's
 *       {@code received_quantity}, and flips the PO to
 *       {@link PurchaseOrderStatus#RECEIVED} when every line is fully received
 *       (else {@link PurchaseOrderStatus#PARTIALLY_RECEIVED}), stamping
 *       {@code received_at} on full receipt. Transactional.</li>
 *   <li><strong>cancel</strong> — allowed only for a DRAFT/ORDERED PO.</li>
 * </ul>
 *
 * <p>Every mutating operation records a best-effort audit event.
 */
@Service
public class PurchaseOrderService {

    /** PO numbers are zero-padded to at least this many digits (PO-0001). */
    static final int MIN_DIGITS = 4;

    /** The prefix applied to every allocated PO number. */
    static final String PO_PREFIX = "PO-";

    /** {@code vouchers.source_type} for a recorded purchase bill (matches {@code SourceType.PURCHASE_ORDER}). */
    private static final String LEDGER_SOURCE_PURCHASE_ORDER = "PURCHASE_ORDER";

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderSequenceRepository sequenceRepository;
    private final SupplierRepository supplierRepository;
    private final ProductRepository productRepository;
    private final StockService stockService;
    private final AuditService auditService;
    private final CurrentUserService currentUserService;
    private final OutboxEventPublisher outboxEventPublisher;

    public PurchaseOrderService(PurchaseOrderRepository purchaseOrderRepository,
                                PurchaseOrderSequenceRepository sequenceRepository,
                                SupplierRepository supplierRepository,
                                ProductRepository productRepository,
                                StockService stockService,
                                AuditService auditService,
                                CurrentUserService currentUserService,
                                OutboxEventPublisher outboxEventPublisher) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.sequenceRepository = sequenceRepository;
        this.supplierRepository = supplierRepository;
        this.productRepository = productRepository;
        this.stockService = stockService;
        this.auditService = auditService;
        this.currentUserService = currentUserService;
        this.outboxEventPublisher = outboxEventPublisher;
    }

    /**
     * Creates a purchase order from a supplier and its line items, allocating a
     * PO number and computing the total. The supplier must exist; every line's
     * product must exist.
     *
     * @throws ResourceNotFoundException when the supplier or a product is unknown
     * @throws ValidationException       when no line items are supplied
     */
    @Transactional
    public PurchaseOrderResponse create(CreatePurchaseOrderRequest request) {
        if (request.items() == null || request.items().isEmpty()) {
            throw new ValidationException("A purchase order requires at least one line item.");
        }
        if (!supplierRepository.existsById(request.supplierId())) {
            throw new ResourceNotFoundException(
                    "Supplier " + request.supplierId() + " does not exist.");
        }

        PurchaseOrder po = new PurchaseOrder(
                allocatePoNumber(), request.supplierId(), request.notes(), currentUserId());
        for (PurchaseOrderItemRequest line : request.items()) {
            if (!productRepository.existsById(line.productId())) {
                throw new ResourceNotFoundException(
                        "Product " + line.productId() + " does not exist.");
            }
            po.addItem(new PurchaseOrderItem(line.productId(), line.quantity(), line.unitCost()));
        }
        po.recomputeTotal();
        PurchaseOrder saved = purchaseOrderRepository.save(po);
        auditService.record(AuditActions.PO_CREATED, AuditActions.ENTITY_PURCHASE_ORDER,
                String.valueOf(saved.getId()),
                "PO " + saved.getPoNumber() + " created for supplier " + saved.getSupplierId()
                        + " (total " + saved.getTotalAmount() + ")");
        // Auto-posting (Reqs 9.1, 17.3, 17.4): enqueue a ledger-post event in this same
        // transaction so the General Ledger derives the balanced Purchase voucher out-of-band. The
        // event row commits atomically with the PO; a downstream posting failure can never roll
        // back or alter this purchase order (additive — no change to existing behaviour/return value).
        outboxEventPublisher.publishLedgerPost(LEDGER_SOURCE_PURCHASE_ORDER, saved.getId());
        return PurchaseOrderResponse.from(saved);
    }

    /**
     * Receives goods against a PO: for each line, adds the received quantity to
     * inventory (a RESTOCK movement) and bumps the line's received quantity, then
     * recomputes the PO status. Receiving is only allowed for an ORDERED or
     * PARTIALLY_RECEIVED PO.
     *
     * @throws ResourceNotFoundException when the PO or a referenced line is unknown
     * @throws ValidationException       when the PO is not in a receivable state
     */
    @Transactional
    public PurchaseOrderResponse receive(Long poId, ReceivePurchaseOrderRequest request) {
        PurchaseOrder po = requirePurchaseOrder(poId);
        if (!po.getStatus().isReceivable()) {
            throw new ValidationException(
                    "PO " + po.getPoNumber() + " cannot be received (status " + po.getStatus() + ").");
        }
        if (request.lines() == null || request.lines().isEmpty()) {
            throw new ValidationException("At least one receive line is required.");
        }

        Map<Long, PurchaseOrderItem> itemsById = new LinkedHashMap<>();
        for (PurchaseOrderItem item : po.getItems()) {
            itemsById.put(item.getId(), item);
        }

        Long actorId = currentUserId();
        int totalReceived = 0;
        for (ReceiveLineRequest line : request.lines()) {
            PurchaseOrderItem item = itemsById.get(line.itemId());
            if (item == null) {
                throw new ResourceNotFoundException(
                        "Line item " + line.itemId() + " does not belong to PO " + po.getPoNumber() + ".");
            }
            int qty = line.receivedQuantity();
            int outstanding = item.outstandingQuantity();
            if (qty > outstanding) {
                throw new ValidationException(
                        "Received quantity " + qty + " exceeds the outstanding quantity "
                                + outstanding + " for line " + item.getId() + ".");
            }
            // Feed inventory: add stock via the restock method (RESTOCK movement).
            stockService.restock(item.getProductId(), qty,
                    "PO " + po.getPoNumber() + " receipt", actorId);
            item.addReceived(qty);
            totalReceived += qty;
        }

        if (po.isFullyReceived()) {
            po.setStatus(PurchaseOrderStatus.RECEIVED);
            po.setReceivedAt(LocalDateTime.now());
        } else if (po.hasAnyReceipt()) {
            po.setStatus(PurchaseOrderStatus.PARTIALLY_RECEIVED);
        }
        PurchaseOrder saved = purchaseOrderRepository.save(po);
        auditService.record(AuditActions.PO_RECEIVED, AuditActions.ENTITY_PURCHASE_ORDER,
                String.valueOf(saved.getId()),
                "PO " + saved.getPoNumber() + " received +" + totalReceived
                        + " unit(s); status " + saved.getStatus());
        return PurchaseOrderResponse.from(saved);
    }

    /**
     * Cancels a PO. Only a DRAFT or ORDERED PO may be cancelled (never one that
     * has started receiving).
     *
     * @throws ValidationException when the PO is not in a cancellable state
     */
    @Transactional
    public PurchaseOrderResponse cancel(Long poId) {
        PurchaseOrder po = requirePurchaseOrder(poId);
        if (!po.getStatus().isCancellable()) {
            throw new ValidationException(
                    "PO " + po.getPoNumber() + " cannot be cancelled (status " + po.getStatus() + ").");
        }
        po.setStatus(PurchaseOrderStatus.CANCELLED);
        PurchaseOrder saved = purchaseOrderRepository.save(po);
        auditService.record(AuditActions.PO_CANCELLED, AuditActions.ENTITY_PURCHASE_ORDER,
                String.valueOf(saved.getId()), "PO " + saved.getPoNumber() + " cancelled");
        return PurchaseOrderResponse.from(saved);
    }

    /** Filtered, paged PO listing (summary rows). */
    @Transactional(readOnly = true)
    public PageResponse<PurchaseOrderSummaryResponse> list(PurchaseOrderStatus status,
                                                           Long supplierId, String q,
                                                           Pageable pageable) {
        Page<PurchaseOrder> page = purchaseOrderRepository.search(
                status, supplierId, blankToNull(q), pageable);
        return PageResponse.of(page, PurchaseOrderSummaryResponse::from);
    }

    /** A single PO with its line items, or a 404. */
    @Transactional(readOnly = true)
    public PurchaseOrderResponse get(Long poId) {
        return PurchaseOrderResponse.from(requirePurchaseOrder(poId));
    }

    // --- Internal helpers ---------------------------------------------------

    /**
     * Allocates the next PO number atomically under a row lock, seeding the
     * counter row on first use. Never returns the same value to two callers.
     */
    private String allocatePoNumber() {
        PurchaseOrderSequence sequence = sequenceRepository
                .findByIdForUpdate(PurchaseOrderSequence.SINGLETON_ID)
                .orElseGet(() -> sequenceRepository.save(
                        new PurchaseOrderSequence(PurchaseOrderSequence.SINGLETON_ID, 1L)));
        long value = sequence.getNextValue();
        sequence.setNextValue(value + 1L);
        sequenceRepository.save(sequence);
        return PO_PREFIX + String.format("%0" + MIN_DIGITS + "d", value);
    }

    private PurchaseOrder requirePurchaseOrder(Long poId) {
        return purchaseOrderRepository.findById(poId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Purchase order " + poId + " does not exist."));
    }

    private Long currentUserId() {
        AuthPrincipal principal = currentUserService.currentUser().orElse(null);
        return principal != null ? principal.userId() : null;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
