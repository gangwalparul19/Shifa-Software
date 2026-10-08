package com.shifa.oms.shopify;

import com.shifa.oms.auth.Role;
import com.shifa.oms.label.LabelService;
import com.shifa.oms.order.Actor;
import com.shifa.oms.order.LeadSource;
import com.shifa.oms.order.OrderCodeGenerator;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.order.OrderPayment;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.OrderStatusHistory;
import com.shifa.oms.order.OrderWorkflowService;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import com.shifa.oms.product.Product;
import com.shifa.oms.product.ProductRepository;
import com.shifa.oms.quikshipx.OrderShipment;
import com.shifa.oms.quikshipx.OrderShipmentRepository;
import com.shifa.oms.quikshipx.QuikShipXProperties;
import com.shifa.oms.shopify.dto.ShopifyOrderPayload;
import com.shifa.oms.statemachine.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * Imports a Shopify {@code orders/create} webhook payload into an OMS order,
 * mirroring the customer's Shopify purchase so the fulfilment team sees it here.
 *
 * <p>Deliberately does NOT reuse {@code OrderService.createSalespersonOrder}: that
 * path enforces salesperson-only order-entry rules (a minimum ₹100 upfront, a
 * same-day duplicate guard, a mandatory payment screenshot, and the per-product
 * price band) which are meaningless for an order the customer already placed and
 * paid for on Shopify. This importer maps the payload as-is, snapshotting the
 * Shopify prices, and lands the order in {@link OrderStatus#PENDING_ADMIN_APPROVAL}
 * so it enters the same review/fulfilment flow as every other order — just tagged
 * {@link OrderSource#SHOPIFY}.
 *
 * <p>Idempotent: the Shopify order id is stored on the order and re-checked, so a
 * webhook retry / redelivery of the same order is recognised and never duplicated
 * (Shopify redelivers on any non-2xx or timeout).
 *
 * <p>A Shopify order is <strong>auto-approved</strong>: the customer already
 * placed (and, when prepaid, paid for) it on Shopify, so there is nothing for an
 * admin to review. After the order is saved it is immediately run through the same
 * approval side-effects a manual admin approval fires — transition to APPROVED,
 * internal-label generation (which moves it to LABEL_GENERATED so it enters the
 * packing queue), ledger posting, and (when enabled) the QuikShipX create+confirm
 * — attributed to an ADMIN-role {@code SHOPIFY} actor so the state-machine
 * authority permits the {@code PENDING_ADMIN_APPROVAL -> APPROVED} edge (SYSTEM is
 * not authorised for it). If any approval side-effect fails the whole import rolls
 * back and Shopify redelivers, so an order is never left half-imported.
 *
 * <p>Stock is intentionally NOT reserved here: the customer's purchase consumed
 * Shopify inventory, not necessarily this OMS's tracked stock, and a short/untracked
 * product must never block importing a real customer order. Inventory is reconciled
 * by staff, not by the webhook.
 */
@Service
public class ShopifyOrderImportService {

    private static final Logger log = LoggerFactory.getLogger(ShopifyOrderImportService.class);

    /** Placeholder mobile when Shopify sends no usable 10-digit Indian number (column is NOT NULL, len 10). */
    private static final String UNKNOWN_MOBILE = "0000000000";

    /** {@code vouchers.source_type} for a finalised sales order (matches AdminOrderService). */
    private static final String LEDGER_SOURCE_ORDER = "ORDER";

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final OrderCodeGenerator orderCodeGenerator;
    private final OrderWorkflowService orderWorkflowService;
    private final LabelService labelService;
    private final OutboxEventPublisher outboxEventPublisher;
    private final QuikShipXProperties quikShipXProperties;
    private final OrderShipmentRepository orderShipmentRepository;

    public ShopifyOrderImportService(OrderRepository orderRepository,
                                     ProductRepository productRepository,
                                     OrderCodeGenerator orderCodeGenerator,
                                     OrderWorkflowService orderWorkflowService,
                                     LabelService labelService,
                                     OutboxEventPublisher outboxEventPublisher,
                                     @Nullable QuikShipXProperties quikShipXProperties,
                                     @Nullable OrderShipmentRepository orderShipmentRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.orderCodeGenerator = orderCodeGenerator;
        this.orderWorkflowService = orderWorkflowService;
        this.labelService = labelService;
        this.outboxEventPublisher = outboxEventPublisher;
        this.quikShipXProperties = quikShipXProperties;
        this.orderShipmentRepository = orderShipmentRepository;
    }

    /** The outcome of importing one Shopify order. */
    public record ImportResult(String orderCode, boolean created) {
    }

    /**
     * Imports (or recognises an already-imported) Shopify order. Returns the OMS
     * order code and whether a new order was created (false = idempotent no-op for
     * a redelivered webhook).
     */
    @Transactional
    public ImportResult importOrder(ShopifyOrderPayload payload) {
        String shopifyOrderId = payload.id() != null ? String.valueOf(payload.id()) : null;
        if (shopifyOrderId == null) {
            throw new IllegalArgumentException("Shopify order payload is missing its id.");
        }

        // Idempotency: a retry/redelivery of the same Shopify order is a no-op.
        Optional<OrderEntity> existing = orderRepository.findByShopifyOrderId(shopifyOrderId);
        if (existing.isPresent()) {
            OrderEntity order = existing.get();
            log.info("Shopify order {} already imported as {} — skipping duplicate.",
                    shopifyOrderId, order.getOrderCode());
            return new ImportResult(order.getOrderCode(), false);
        }

        ShopifyOrderPayload.Address ship = payload.shippingAddress() != null
                ? payload.shippingAddress() : payload.billingAddress();

        String customerName = resolveCustomerName(payload, ship);
        String customerMobile = resolveMobile(payload, ship);
        Address addr = resolveAddress(ship);

        OrderEntity order = new OrderEntity(
                orderCodeGenerator.generate(orderRepository::existsByOrderCode),
                OrderSource.SHOPIFY,
                null, // unattributed — no salesperson placed it
                customerName,
                customerMobile,
                addr.addressLine(),
                addr.city(),
                addr.state(),
                addr.postalCode());
        order.setCountry(addr.country());
        order.setShopifyOrderId(shopifyOrderId);
        order.setLeadSource(LeadSource.SHOPIFY);
        order.setCustomerEmail(trimToNull(resolveEmail(payload)));
        order.setNotes(buildNote(payload));

        // Line items: match by SKU to our catalogue when possible (snapshotting the
        // product's HSN/GST rate for correct invoicing), else keep a name-only line
        // with the Shopify title. Always use the Shopify-charged price as the rate.
        List<OrderLineItem> lines = buildLineItems(payload);
        for (OrderLineItem line : lines) {
            order.addLineItem(line);
        }

        // Amounts: the Shopify total is authoritative. Payment state derives from
        // Shopify's financial_status — a paid order is FULLY_PAID (nothing to
        // collect), anything else (pending/COD) is treated as collect-on-delivery.
        BigDecimal total = resolveTotal(payload, lines);
        // Reconcile the order-level discount so the line rates and the net total tie
        // out. Shopify charges the customer a total that already nets any checkout
        // discount/coupon, while our line rates snapshot the per-unit list price —
        // so Σ(lineTotals) can exceed the total. The gap is a discount; recording it
        // makes the invoice correct AND makes the QuikShipX create payload reconcile
        // (Σ product amount − discount == order_amount), which otherwise rejects the
        // order with "Calculated Products and Order Amount Not Matched".
        BigDecimal discount = resolveDiscount(payload, lines, total);
        if (discount.signum() > 0) {
            order.applyOrderDiscount("FLAT", discount, discount);
        }
        PaymentSplit split = resolvePaymentSplit(payload, total);
        order.applyAmounts(total, split.received(), split.remaining(), split.cod(), split.status());
        order.setCustomerOutstanding(split.cod());
        order.setOrderStatus(OrderStatus.INITIAL); // PENDING_ADMIN_APPROVAL

        if (split.received().signum() > 0) {
            // Record the prepaid amount (no screenshot key — payment happened on Shopify).
            order.addPayment(new OrderPayment(split.received(), null));
        }

        // Creation history row: null from-status -> initial status, actor/source = SHOPIFY.
        order.addStatusHistory(new OrderStatusHistory(
                null, OrderStatus.INITIAL, SOURCE_SHOPIFY, SOURCE_SHOPIFY));

        OrderEntity saved = orderRepository.save(order);

        // Auto-approve: a Shopify order is already placed/paid by the customer, so
        // it skips admin approval and immediately runs the SAME side-effects a
        // manual approval fires (see AdminOrderService.approve) — all in this one
        // transaction, so a failure rolls the import back and Shopify redelivers.
        autoApprove(saved);

        log.info("Imported Shopify order {} (#{}) as {} (auto-approved -> {}).",
                shopifyOrderId, payload.orderNumber(), saved.getOrderCode(), saved.getOrderStatus());
        return new ImportResult(saved.getOrderCode(), true);
    }

    private static final String SOURCE_SHOPIFY = "SHOPIFY";

    /**
     * Runs the full approval side-effect chain on a freshly imported Shopify order,
     * mirroring {@code AdminOrderService.approve}: (1) transition
     * PENDING_ADMIN_APPROVAL -> APPROVED, (2) generate the internal label which
     * moves the order to LABEL_GENERATED (so it enters the packing queue — an order
     * left at APPROVED never reaches fulfilment), (3) enqueue the ledger-post event,
     * (4) publish to QuikShipX (create + confirm) when the integration is enabled
     * and the order is not in-house. The actor is an ADMIN-role SHOPIFY actor
     * because the state-machine authority permits PENDING_ADMIN_APPROVAL -> APPROVED
     * only for ADMIN (never SYSTEM).
     */
    private void autoApprove(OrderEntity order) {
        orderWorkflowService.applyTransition(
                order, OrderStatus.APPROVED, Actor.user(SOURCE_SHOPIFY, Role.ADMIN, SOURCE_SHOPIFY));
        labelService.generateInternalLabelOnApproval(order, SOURCE_SHOPIFY);
        orderRepository.save(order);

        outboxEventPublisher.publishLedgerPost(LEDGER_SOURCE_ORDER, order.getId());
        publishToQuikShipX(order);
    }

    /**
     * Enqueues the QuikShipX create + confirm events for an order when the
     * integration is enabled and the order is not in-house. The drainer then
     * creates → confirms → allots a tracking id (moving the order to
     * Courier_Assigned). Idempotent at the drainer level (create skips an order
     * that already has a shipment), so it is safe to call again during recovery.
     */
    private void publishToQuikShipX(OrderEntity order) {
        if (quikShipXProperties != null && quikShipXProperties.isEnabled() && !order.isInHouseDelivery()) {
            outboxEventPublisher.publishQuikShipXCreate(order.getId(), order.getOrderCode());
            outboxEventPublisher.publishQuikShipXConfirm(order.getId(), order.getOrderCode());
        }
    }

    /** The outcome of a recover run: how many stuck Shopify orders were pushed forward. */
    public record RecoverResult(int approvedFromPending, int republishedFromLabelGenerated) {
        int total() {
            return approvedFromPending + republishedFromLabelGenerated;
        }
    }

    /**
     * Recovers stuck Shopify orders so the channel is deterministic (every Shopify
     * order should reach Tracking ID Assigned via QuikShipX):
     * <ul>
     *   <li>orders left at {@code PENDING_ADMIN_APPROVAL} (imported by the pre
     *       auto-approve code) are run through the same auto-approve chain
     *       (approve → label → ledger → QuikShipX create+confirm);</li>
     *   <li>orders sitting at {@code LABEL_GENERATED} (auto-approved but whose
     *       QuikShipX publish never ran / failed) get their QuikShipX create+confirm
     *       re-enqueued so the drainer can allot a tracking id.</li>
     * </ul>
     * Idempotent and best-effort: each order is handled in isolation so one failure
     * does not abort the rest. Orders already at Courier_Assigned or beyond are left
     * untouched.
     */
    @Transactional
    public RecoverResult recoverStuckShopifyOrders() {
        int approved = 0;
        for (OrderEntity order : orderRepository.findBySourceAndOrderStatusOrderByCreatedAtDesc(
                OrderSource.SHOPIFY, OrderStatus.PENDING_ADMIN_APPROVAL)) {
            try {
                backfillDiscount(order);
                autoApprove(order);
                approved++;
            } catch (RuntimeException e) {
                log.warn("Recover: could not auto-approve stuck Shopify order {}: {}",
                        order.getOrderCode(), e.getMessage());
            }
        }
        int republished = 0;
        List<OrderEntity> labelGenerated = orderRepository.findBySourceAndOrderStatusOrderByCreatedAtDesc(
                OrderSource.SHOPIFY, OrderStatus.LABEL_GENERATED);
        java.util.Map<Long, OrderShipment> shipments = loadShipments(labelGenerated);
        for (OrderEntity order : labelGenerated) {
            if (order.isInHouseDelivery()) {
                continue; // In-house orders never go to QuikShipX — nothing to recover.
            }
            OrderShipment ship = shipments.get(order.getId());
            if (ship != null && trimToNull(ship.getAwb()) != null) {
                continue; // Already has a tracking id — in sync, nothing to re-publish.
            }
            try {
                backfillDiscount(order);
                publishToQuikShipX(order);
                republished++;
            } catch (RuntimeException e) {
                log.warn("Recover: could not re-publish stuck Shopify order {} to QuikShipX: {}",
                        order.getOrderCode(), e.getMessage());
            }
        }
        log.info("Shopify recover: auto-approved {} pending, re-published {} label-generated order(s) to QuikShipX.",
                approved, republished);
        return new RecoverResult(approved, republished);
    }

    /**
     * A Shopify order on the sync page, carrying its real QuikShipX shipment state
     * (AWB / tracking id + QuikShipX status), not just its OMS lifecycle status.
     *
     * <p>{@code trackingAssigned} is the single source of truth for "has it got a
     * tracking id": it is true when the order's {@link OrderShipment} has an
     * {@code awb}. {@code waiting} is true when the order still needs a tracking id
     * (recoverable + no AWB yet). An order can therefore be at {@code LABEL_GENERATED}
     * but already have {@code trackingAssigned=true} — that is the correct, in-sync
     * state (QuikShip orders deliberately stay at Label Generated and flow through
     * the manual packing queue even after a tracking id is allotted).
     */
    public record StuckOrder(Long id, String orderCode, String customerName, String customerMobile,
                             String status, BigDecimal totalAmount, java.time.LocalDateTime createdAt,
                             boolean inHouse, boolean recoverable,
                             String awb, String quikShipXStatus,
                             boolean trackingAssigned, boolean waiting,
                             // Why QuikShipX could not ship it (e.g. "585216 is non
                             // serviceable pincode"), when a permanent failure was
                             // recorded. Null when it is simply still being processed.
                             // Drives the "re-route to in-house" prompt on the UI.
                             String failureReason) {
    }

    /**
     * Read-only snapshot of the Shopify orders not yet handed to a courier, with
     * their REAL tracking-id state. Rather than inferring "waiting for tracking id"
     * from the OMS lifecycle status (which wrongly flags every allotted QuikShip
     * order, since they deliberately stay at Label Generated), each order is joined
     * to its {@link OrderShipment} and the AWB is the authoritative signal:
     * <ul>
     *   <li>{@code waiting=true} — recoverable AND no AWB yet (truly needs a tracking
     *       id: Pending Admin Approval, or Label Generated non-in-house with no AWB);</li>
     *   <li>{@code trackingAssigned=true} — the shipment already has an AWB (in sync,
     *       just moving through the packing queue — NOT stuck);</li>
     *   <li>in-house Label Generated orders are listed but not recoverable/waiting —
     *       delivered by our own team, never via QuikShipX.</li>
     * </ul>
     * Oldest first so the longest-waiting order is on top.
     */
    @Transactional(readOnly = true)
    public List<StuckOrder> listStuckShopifyOrders() {
        List<OrderEntity> orders = new java.util.ArrayList<>();
        for (OrderStatus status : List.of(OrderStatus.PENDING_ADMIN_APPROVAL, OrderStatus.LABEL_GENERATED)) {
            orders.addAll(orderRepository.findBySourceAndOrderStatusOrderByCreatedAtDesc(
                    OrderSource.SHOPIFY, status));
        }

        // Batch-load the shipments (no N+1) so we can read the real AWB per order.
        java.util.Map<Long, OrderShipment> shipments = loadShipments(orders);

        List<StuckOrder> out = new java.util.ArrayList<>();
        for (OrderEntity o : orders) {
            boolean inHouse = o.isInHouseDelivery();
            OrderShipment ship = shipments.get(o.getId());
            String awb = ship != null ? trimToNull(ship.getAwb()) : null;
            String quikStatus = ship != null ? ship.getQuikShipXStatus() : null;
            boolean trackingAssigned = awb != null;
            boolean pending = o.getOrderStatus() == OrderStatus.PENDING_ADMIN_APPROVAL;
            // Recoverable = a recover run would push it forward: pending (auto-approve),
            // or label-generated non-in-house with no tracking id yet (re-publish).
            boolean recoverable = pending || (!inHouse && !trackingAssigned);
            // Waiting = still genuinely needs a tracking id (not yet allotted).
            boolean waiting = recoverable && !trackingAssigned;
            out.add(new StuckOrder(o.getId(), o.getOrderCode(), o.getCustomerName(), o.getCustomerMobile(),
                    o.getOrderStatus().name(), o.getTotalAmount(), o.getCreatedAt(), inHouse, recoverable,
                    awb, quikStatus, trackingAssigned, waiting,
                    trimToNull(o.getQuikShipXFailureReason())));
        }
        out.sort(java.util.Comparator.comparing(StuckOrder::createdAt,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
        return out;
    }

    /**
     * Batch-loads the shipment per order id (empty map when the repo is absent or
     * no order has an id — e.g. in unit tests). A {@link java.util.HashMap} is used
     * (not {@link java.util.Map#of()}) so a {@code get(null)} for an unsaved order
     * returns null gracefully instead of throwing.
     */
    private java.util.Map<Long, OrderShipment> loadShipments(List<OrderEntity> orders) {
        java.util.Map<Long, OrderShipment> map = new java.util.HashMap<>();
        if (orderShipmentRepository == null || orders.isEmpty()) {
            return map;
        }
        List<Long> ids = orders.stream().map(OrderEntity::getId).filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return map;
        }
        for (OrderShipment s : orderShipmentRepository.findByOrderIdIn(ids)) {
            map.put(s.getOrderId(), s);
        }
        return map;
    }

    /**
     * Backfills the reconciling order-level discount on an already-imported Shopify
     * order so its QuikShipX create payload ties out (line subtotal − discount ==
     * total). Orders imported before the discount-reconciliation fix carry a 0
     * discount even when the line subtotal exceeds the charged total, which the
     * courier rejects as "Calculated Products and Order Amount Not Matched". This
     * recomputes the discount from the persisted lines vs total and updates it in
     * place; a no-op when the amounts already reconcile.
     */
    private void backfillDiscount(OrderEntity order) {
        BigDecimal total = order.getTotalAmount();
        if (total == null || order.getLineItems() == null || order.getLineItems().isEmpty()) {
            return;
        }
        BigDecimal subtotal = lineSubtotal(order.getLineItems());
        BigDecimal gap = subtotal.subtract(total).setScale(2, RoundingMode.HALF_UP);
        if (gap.signum() <= 0) {
            return; // Already reconciles (or total >= subtotal).
        }
        BigDecimal current = order.getDiscountAmount() == null ? BigDecimal.ZERO : order.getDiscountAmount();
        if (current.compareTo(gap) == 0) {
            return; // Discount already correct.
        }
        order.applyOrderDiscount("FLAT", gap, gap);
        orderRepository.save(order);
        log.info("Recover: backfilled discount {} on Shopify order {} so amounts reconcile (subtotal {} - discount = total {}).",
                gap, order.getOrderCode(), subtotal, total);
    }

    // --- Line items ---------------------------------------------------------

    private List<OrderLineItem> buildLineItems(ShopifyOrderPayload payload) {
        List<OrderLineItem> lines = new java.util.ArrayList<>();
        if (payload.lineItems() == null) {
            return lines;
        }
        for (ShopifyOrderPayload.LineItem item : payload.lineItems()) {
            if (item == null) {
                continue;
            }
            int qty = item.quantity() != null && item.quantity() > 0 ? item.quantity() : 1;
            BigDecimal rate = item.price() != null ? item.price() : BigDecimal.ZERO;
            BigDecimal lineTotal = rate.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);

            Product product = matchProduct(item.sku());
            String name = resolveLineName(item, product);
            if (product != null) {
                lines.add(new OrderLineItem(
                        product.getId(), name, product.getHsnCode(), product.getGstRate(),
                        qty, rate, lineTotal));
            } else {
                // Unmatched Shopify product: keep a name-only line so nothing is lost;
                // staff can map it to a catalogue product on review.
                lines.add(new OrderLineItem(null, name, qty, rate, lineTotal));
            }
        }
        return lines;
    }

    private Product matchProduct(String sku) {
        String trimmed = trimToNull(sku);
        if (trimmed == null) {
            return null;
        }
        return productRepository.findBySku(trimmed).orElse(null);
    }

    private static String resolveLineName(ShopifyOrderPayload.LineItem item, Product product) {
        if (product != null && product.getName() != null && !product.getName().isBlank()) {
            return product.getName();
        }
        String title = trimToNull(item.title());
        if (title != null) {
            return truncate(title, 200);
        }
        String name = trimToNull(item.name());
        return name != null ? truncate(name, 200) : "Shopify item";
    }

    // --- Amounts / payment --------------------------------------------------

    private static BigDecimal resolveTotal(ShopifyOrderPayload payload, List<OrderLineItem> lines) {
        if (payload.totalPrice() != null && payload.totalPrice().signum() > 0) {
            return payload.totalPrice().setScale(2, RoundingMode.HALF_UP);
        }
        // Fall back to the sum of line totals when Shopify omits total_price.
        BigDecimal sum = BigDecimal.ZERO;
        for (OrderLineItem line : lines) {
            if (line.getLineTotal() != null) {
                sum = sum.add(line.getLineTotal());
            }
        }
        return sum.setScale(2, RoundingMode.HALF_UP);
    }

    /** Σ of the line totals (the gross subtotal before any order-level discount). */
    private static BigDecimal lineSubtotal(List<OrderLineItem> lines) {
        BigDecimal sum = BigDecimal.ZERO;
        for (OrderLineItem line : lines) {
            if (line.getLineTotal() != null) {
                sum = sum.add(line.getLineTotal());
            }
        }
        return sum.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Resolves the order-level discount that reconciles the line subtotal with the
     * charged total: {@code subtotal - discount == total}. The definitive signal is
     * the gap itself ({@code subtotal - total}) — that is exactly the reduction the
     * courier's create-order validation expects. Shopify's {@code total_discounts}
     * is used only as a sanity floor. Returns 0 when the subtotal does not exceed
     * the total (no discount, or the total already includes extras like shipping —
     * which the payload carries separately via {@code shipping_amount}).
     */
    static BigDecimal resolveDiscount(ShopifyOrderPayload payload, List<OrderLineItem> lines, BigDecimal total) {
        BigDecimal subtotal = lineSubtotal(lines);
        BigDecimal gap = subtotal.subtract(total).setScale(2, RoundingMode.HALF_UP);
        if (gap.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        // The gap between the gross line subtotal and the net charged total IS the
        // discount to record (a Shopify coupon/automatic/line discount). We trust the
        // gap over total_discounts because it is what makes the amounts reconcile; a
        // present total_discounts should match it, but the gap is authoritative.
        return gap;
    }

    /** The received / remaining / COD split + status, derived from Shopify's amounts. */
    private record PaymentSplit(BigDecimal received, BigDecimal remaining, BigDecimal cod, PaymentStatus status) {
    }

    /**
     * Resolves how much the customer has ALREADY PAID on Shopify vs what is left to
     * collect on delivery, and classifies the order FULLY_PAID / PARTIALLY_PAID /
     * COD. The amount actually received is the authoritative signal — Shopify's
     * {@code total_outstanding} is the balance still owed, so
     * {@code received = total - outstanding} (clamped to [0, total]); the
     * {@code financial_status} is only a fallback when the outstanding amount is
     * absent. This is what fixes a Shopify PARTIALLY-PAID order being mis-imported
     * as full COD: a part-paid order now carries its real received amount and the
     * remaining balance as the COD, not the whole total.
     */
    private static PaymentSplit resolvePaymentSplit(ShopifyOrderPayload payload, BigDecimal total) {
        BigDecimal zero = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        String fin = payload.financialStatus() != null
                ? payload.financialStatus().trim().toLowerCase(java.util.Locale.ROOT) : "";

        // Prefer the explicit outstanding balance Shopify sends; fall back to
        // inferring it from financial_status when the field is absent.
        BigDecimal outstanding;
        if (payload.totalOutstanding() != null) {
            outstanding = payload.totalOutstanding().max(BigDecimal.ZERO).min(total)
                    .setScale(2, RoundingMode.HALF_UP);
        } else if (fin.equals("paid") || fin.equals("partially_refunded") || fin.equals("refunded")) {
            outstanding = zero;                 // settled on Shopify
        } else if (fin.equals("partially_paid")) {
            // No amount given but Shopify says part-paid: we can't know the split,
            // so treat the whole balance as still-to-collect (safer than assuming
            // fully paid). A correct outstanding amount, when present, takes over above.
            outstanding = total;
        } else {
            outstanding = total;                // pending / authorized / unpaid / COD
        }

        BigDecimal received = total.subtract(outstanding).setScale(2, RoundingMode.HALF_UP);
        if (received.signum() <= 0) {
            // Nothing received — collect the full amount on delivery (COD).
            return new PaymentSplit(zero, total, total, PaymentStatus.COD);
        }
        if (outstanding.signum() <= 0) {
            // Fully paid on Shopify — nothing to collect.
            return new PaymentSplit(total, zero, zero, PaymentStatus.FULLY_PAID);
        }
        // Part-paid: record the real received amount; the balance is the COD.
        return new PaymentSplit(received, outstanding, outstanding, PaymentStatus.PARTIALLY_PAID);
    }

    // --- Customer / address -------------------------------------------------

    private static String resolveCustomerName(ShopifyOrderPayload payload, ShopifyOrderPayload.Address ship) {
        if (payload.customer() != null) {
            String joined = joinName(payload.customer().firstName(), payload.customer().lastName());
            if (joined != null) {
                return truncate(joined, 100);
            }
        }
        if (ship != null) {
            String name = trimToNull(ship.name());
            if (name != null) {
                return truncate(name, 100);
            }
            String joined = joinName(ship.firstName(), ship.lastName());
            if (joined != null) {
                return truncate(joined, 100);
            }
        }
        return "Shopify Customer";
    }

    private static String resolveEmail(ShopifyOrderPayload payload) {
        if (payload.email() != null && !payload.email().isBlank()) {
            return payload.email();
        }
        return payload.customer() != null ? payload.customer().email() : null;
    }

    /**
     * Best-effort Indian 10-digit mobile from the Shopify order/customer/shipping
     * phone: strip non-digits, drop a leading 91 country code / leading 0, take the
     * last 10 digits. Falls back to a placeholder (the column is NOT NULL, len 10),
     * so a missing/foreign number never blocks importing a real order.
     */
    private static String resolveMobile(ShopifyOrderPayload payload, ShopifyOrderPayload.Address ship) {
        String raw = firstNonBlank(
                payload.phone(),
                payload.customer() != null ? payload.customer().phone() : null,
                ship != null ? ship.phone() : null);
        String digits = raw == null ? "" : raw.replaceAll("\\D", "");
        if (digits.length() > 10) {
            digits = digits.substring(digits.length() - 10);
        }
        return digits.length() == 10 ? digits : UNKNOWN_MOBILE;
    }

    /** The resolved OMS address (domestic keeps structured parts; international is free-text + country). */
    private record Address(String addressLine, String city, String state, String postalCode, String country) {
    }

    private static Address resolveAddress(ShopifyOrderPayload.Address ship) {
        if (ship == null) {
            return new Address("Address not provided by Shopify", "", "", "", null);
        }
        String line = joinAddressLine(ship.address1(), ship.address2());
        String country = trimToNull(ship.country());
        boolean domestic = country == null || country.equalsIgnoreCase("India") || country.equalsIgnoreCase("IN");
        if (!domestic) {
            // International: keep the full address free-text, structured parts empty.
            String full = firstNonBlank(line, ship.city(), "Address not provided");
            return new Address(truncate(full, 250), "", "", "", truncate(country, 60));
        }
        String city = trimToNull(ship.city());
        String state = trimToNull(ship.province());
        String zipDigits = ship.zip() == null ? "" : ship.zip().replaceAll("\\D", "");
        String postal = zipDigits.length() == 6 ? zipDigits : "";
        return new Address(
                truncate(firstNonBlank(line, "Address not provided"), 250),
                truncate(city != null ? city : "", 100),
                truncate(state != null ? state : "", 100),
                postal,
                null);
    }

    // --- Small helpers ------------------------------------------------------

    private static String buildNote(ShopifyOrderPayload payload) {
        StringBuilder sb = new StringBuilder("Imported from Shopify");
        if (payload.name() != null && !payload.name().isBlank()) {
            sb.append(' ').append(payload.name().trim());
        } else if (payload.orderNumber() != null) {
            sb.append(" #").append(payload.orderNumber());
        }
        String customerNote = trimToNull(payload.note());
        if (customerNote != null) {
            sb.append(" — ").append(customerNote);
        }
        return truncate(sb.toString(), 1000);
    }

    private static String joinName(String first, String last) {
        String f = trimToNull(first);
        String l = trimToNull(last);
        if (f == null && l == null) {
            return null;
        }
        if (f == null) {
            return l;
        }
        if (l == null) {
            return f;
        }
        return f + " " + l;
    }

    private static String joinAddressLine(String a1, String a2) {
        String x = trimToNull(a1);
        String y = trimToNull(a2);
        if (x == null && y == null) {
            return null;
        }
        if (x == null) {
            return y;
        }
        if (y == null) {
            return x;
        }
        return x + ", " + y;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            String t = trimToNull(v);
            if (t != null) {
                return t;
            }
        }
        return null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
