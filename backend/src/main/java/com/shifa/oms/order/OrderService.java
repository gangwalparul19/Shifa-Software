package com.shifa.oms.order;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.SalespersonScopeResolver;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.courier.TrackingService;
import com.shifa.oms.gst.domain.Gstin;
import com.shifa.oms.inventory.StockMovementType;
import com.shifa.oms.inventory.StockService;
import com.shifa.oms.order.domain.DiscountType;
import com.shifa.oms.order.domain.Money;
import com.shifa.oms.order.domain.OrderPricing;
import com.shifa.oms.order.domain.PaymentCalculation;
import com.shifa.oms.order.domain.PaymentCalculator;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.order.dto.CreateOrderRequest;
import com.shifa.oms.order.dto.CustomerPrefillResponse;
import com.shifa.oms.order.dto.DuplicateCheckResponse;
import com.shifa.oms.order.dto.LineItemRequest;
import com.shifa.oms.order.dto.OrderResponse;
import com.shifa.oms.order.dto.OrderSummaryResponse;
import com.shifa.oms.order.dto.PaymentScreenshotResponse;
import com.shifa.oms.order.dto.UpdateOrderRequest;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import com.shifa.oms.platform.storage.StorageService;
import com.shifa.oms.product.Product;
import com.shifa.oms.quikshipx.QuikShipXProperties;
import com.shifa.oms.product.ProductImage;
import com.shifa.oms.product.ProductImageRepository;
import com.shifa.oms.product.ProductRepository;
import com.shifa.oms.quikshipx.OrderShipmentRepository;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Order / OMS application service (design: "Order / OMS Module").
 *
 * <p>Owns creation of the order aggregate for both entry paths — salesperson
 * order entry ({@code POST /api/orders}, Req 7) and public storefront checkout
 * ({@code POST /api/checkout}, Req 3) — plus role-scoped search / detail
 * (Req 22.1, 5.5), duplicate detection (Req 22.2), and payment-screenshot
 * retrieval (Req 21.2). All money math is delegated to the pure
 * {@link PaymentCalculator}; the initial status is {@link OrderStatus#INITIAL}
 * ({@code Pending_Admin_Approval}) with a creation status-history row (Req 8.2,
 * 8.4, 7.11, 3.6). Line items, payment capture, and history persist atomically
 * with the order.
 *
 * <p><strong>Zero-total guard</strong>: order creation is rejected when
 * {@code Total_Amount <= 0} — an order must have a positive total — applied to
 * both entry paths.
 */
@Service
public class OrderService {

    private static final String SOURCE_SALESPERSON = "SALESPERSON";

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final OrderCodeGenerator orderCodeGenerator;
    private final StorageService storageService;
    private final SalespersonScopeResolver scopeResolver;
    private final TrackingService trackingService;
    private final StockService stockService;
    private final ProductImageRepository productImageRepository;
    /**
     * QuikShipX punch hook (nullable): when the integration is enabled, a
     * {@code QUIKSHIPX_CREATE} outbox event is enqueued on order creation so the
     * shipment is published to QuikShipX out-of-band. Null in unit tests that use
     * the legacy constructor — the hook is then skipped.
     */
    private final OutboxEventPublisher outboxEventPublisher;
    private final QuikShipXProperties quikShipXProperties;
    /** QuikShipX shipment mirror (nullable): enriches order-detail reads. */
    private final OrderShipmentRepository orderShipmentRepository;
    /**
     * Staff directory (nullable): resolves the order's {@code created_by} to the
     * salesperson's display name for the order-detail drawer. Null under the
     * legacy test constructor — the name is then omitted.
     */
    private final com.shifa.oms.auth.UserRepository userRepository;
    /**
     * Central workflow (nullable): used by {@link #resubmit} to transition a
     * reworked REJECTED/PAYMENT_REJECTED order back to PENDING_ADMIN_APPROVAL via
     * the authority + history + audit pipeline. Null under the legacy test
     * constructor — resubmit then requires the full constructor.
     */
    private final OrderWorkflowService orderWorkflowService;
    /**
     * Audit trail (nullable): records a field-level "what changed" ORDER_UPDATED
     * entry (who/what/when) whenever an order's details are edited or resubmitted.
     * Null under the legacy test constructor — the audit is then skipped.
     */
    private final AuditService auditService;

    /** Legacy constructor (unit tests): no QuikShipX punch hook / enrichment / workflow / audit. */
    public OrderService(OrderRepository orderRepository,
                        ProductRepository productRepository,
                        OrderCodeGenerator orderCodeGenerator,
                        StorageService storageService,
                        SalespersonScopeResolver scopeResolver,
                        TrackingService trackingService,
                        StockService stockService,
                        ProductImageRepository productImageRepository) {
        this(orderRepository, productRepository, orderCodeGenerator, storageService, scopeResolver,
                trackingService, stockService, productImageRepository, null, null, null, null, null, null);
    }

    @Autowired
    public OrderService(OrderRepository orderRepository,
                        ProductRepository productRepository,
                        OrderCodeGenerator orderCodeGenerator,
                        StorageService storageService,
                        SalespersonScopeResolver scopeResolver,
                        TrackingService trackingService,
                        StockService stockService,
                        ProductImageRepository productImageRepository,
                        OutboxEventPublisher outboxEventPublisher,
                        QuikShipXProperties quikShipXProperties,
                        OrderShipmentRepository orderShipmentRepository,
                        com.shifa.oms.auth.UserRepository userRepository,
                        OrderWorkflowService orderWorkflowService,
                        AuditService auditService) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.orderCodeGenerator = orderCodeGenerator;
        this.storageService = storageService;
        this.scopeResolver = scopeResolver;
        this.trackingService = trackingService;
        this.stockService = stockService;
        this.productImageRepository = productImageRepository;
        this.outboxEventPublisher = outboxEventPublisher;
        this.quikShipXProperties = quikShipXProperties;
        this.orderShipmentRepository = orderShipmentRepository;
        this.userRepository = userRepository;
        this.orderWorkflowService = orderWorkflowService;
        this.auditService = auditService;
    }

    // --- Creation: salesperson order entry (Req 7) --------------------------

    /**
     * Creates a salesperson-punched order. Rates pre-fill from the product sale
     * price and are overridable per line (Req 7.2, 7.3); totals and payment
     * classification come from {@link PaymentCalculator} (Req 7.4-7.10); a
     * payment screenshot is mandatory when money was received (Req 7.6). The
     * order starts in {@code Pending_Admin_Approval} recorded as {@code createdBy}
     * the acting user (Req 7.11, 5.5).
     */
    @Transactional
    public OrderResponse createSalespersonOrder(CreateOrderRequest request, AuthPrincipal actor) {
        // Lead-source capture (Req 4.1, 4.2, 4.5): presence + membership + note length,
        // rejected as HTTP 400 before anything is priced or persisted. Persisted
        // distinctly from Order_Source (the order-record provenance).
        OrderCreationValidator.requireLeadSource(request.leadSource());
        OrderCreationValidator.validateLeadSourceNote(request.leadSourceNote());

        List<PricedLine> priced = priceLines(request.items());
        // Price the order through the pure engine (product-catalog-pricing-gst
        // Req 6-8): resolve the order-level discount (FLAT/PERCENT), apportion it,
        // extract per-line GST from the GST-inclusive amounts, and round the
        // payable total to the nearest whole rupee. The total is the NET payable
        // (subtotal - discount), so COD/remaining below already reflect the
        // discount and the invoice grand total stays reconciled.
        OrderPricing.DiscountSpec discountSpec = OrderPricing.DiscountSpec.of(
                DiscountType.from(request.discountType()), request.discountValue());
        OrderPricing.PricedOrder pricedOrder = OrderPricing.compute(toPricingLines(priced), discountSpec);
        Money total = Money.of(pricedOrder.total());
        requirePositiveTotal(total);

        // Same-day duplicate guard (client): a customer can reach two salespeople
        // the same day and get the same order punched twice. Reject a second active
        // order for the same mobile on the same calendar day (IST), naming the
        // existing order + who placed it so the salesperson understands why. This is
        // the authoritative block; the New Order form also warns before submit.
        requireNoSameDayDuplicate(request.customerMobile(), actor);

        Money received = Money.of(request.amountReceived());
        // Minimum-upfront-payment policy (client: no COD/₹0 orders — only Full or
        // Partial payment, with at least ₹100 collected before the order proceeds).
        // Enforced at the order-entry boundary (the pure PaymentCalculator/COD model
        // is intentionally left intact for historical orders + derived reporting).
        requireMinimumUpfront(received, total);
        // Resolve the full ordered set of payment proofs (V65): the legacy single key
        // followed by any additional keys, de-duplicated. The first is the primary
        // proof. Sending only paymentScreenshotKey behaves exactly as before.
        List<String> screenshotKeys = effectiveScreenshotKeys(request);
        // Enforce screenshot-required rule before computing/persisting (Req 7.6). A
        // screenshot is now ALWAYS required because a payment (≥ ₹100 / full) is
        // always collected upfront; the rule below reads naturally from any proof.
        PaymentCalculator.requireScreenshotWhenPaid(received, primaryKey(screenshotKeys));
        // A fully-paid amount may have carried paise before the total was rounded
        // down; absorb ONLY that sub-rupee overage so a valid full payment isn't
        // rejected. A genuine over-payment (>= ₹1 above the total) still falls
        // through to classify() and is rejected (Req 7.10).
        Money overage = received.subtract(total);
        if (overage.compareTo(Money.ZERO) > 0 && overage.compareTo(Money.of(1L)) < 0) {
            received = total;
        }
        // Classify (also rejects amountReceived > total, Req 7.10).
        PaymentCalculation calc = PaymentCalculator.classify(total, received);

        OrderEntity order = new OrderEntity(
                orderCodeGenerator.generate(orderRepository::existsByOrderCode),
                OrderSource.SALESPERSON,
                actor.userId(),
                request.customerName(),
                request.customerMobile(),
                request.addressLine(),
                request.city(),
                request.state(),
                request.postalCode());

        // Lead-source fields persist on the order aggregate, distinct from Order_Source.
        order.setLeadSource(request.leadSource());
        order.setLeadSourceNote(request.leadSourceNote());
        order.setCustomerEmail(request.customerEmail());
        order.setNotes(trimToNull(request.notes()));
        order.setAlternateMobile(trimToNull(request.alternateMobile()));
        // Optional buyer GSTIN for GSTR-1 classification (gst-filing-compliance
        // Req 1.1). When non-blank it must match the standard 15-character GSTIN
        // format; a blank/null value is allowed (unregistered buyer). An invalid
        // GSTIN is rejected with a 400 via the global handler (Req 1.3).
        String buyerGstin = trimToNull(request.buyerGstin());
        if (buyerGstin != null && !Gstin.isValid(buyerGstin)) {
            throw new ValidationException(
                    "buyerGstin must be a valid 15-character GSTIN "
                            + "(2-digit state code, 10-character PAN, 1 entity digit, the letter Z, "
                            + "and 1 checksum character).");
        }
        order.setBuyerGstin(buyerGstin);

        // Per-order delivery method (QUIKSHIPX default, or IN_HOUSE to skip the
        // courier integration entirely). Blank/null → QUIKSHIPX.
        order.setDeliveryMethod(parseDeliveryMethod(request.deliveryMethod()));

        // Prepaid / partially-paid orders carry a payment to verify for authenticity
        // (product-audit §4.4). Pure COD orders have nothing to verify.
        if (calc.paymentStatus() != PaymentStatus.COD) {
            order.markPaymentPendingVerification();
        }

        populateAggregate(order, priced, calc, screenshotKeys,
                actor.username(), SOURCE_SALESPERSON);

        // Snapshot the order-level discount (type + raw value + resolved amount)
        // so history and the response reflect it (Req 6.4). No-op amount when none.
        DiscountType discountType = discountSpec.type();
        order.applyOrderDiscount(
                discountType == DiscountType.NONE ? null : discountType.name(),
                discountType == DiscountType.NONE ? null : discountSpec.value(),
                pricedOrder.discount());

        // Reserve stock for tracked products within this transaction (Feature 1):
        // decrements on_hand + records a SALE movement, rejecting insufficient stock.
        reserveStock(priced, actor.userId(), order.getOrderCode());

        OrderEntity saved = orderRepository.save(order);
        // Real-time admin nudge: a freshly punched order lands in the approval
        // queue, so enqueue an ORDER_AWAITING_APPROVAL event in this same
        // transaction. The SSE relay surfaces it to connected admins; the row is
        // persisted regardless, so nothing is lost when no admin is online.
        publishAwaitingApproval(saved);
        // QuikShipX (create-on-punch): enqueue the shipment publication in this same
        // transaction so the order appears in QuikShipX's Pending section once the
        // drainer delivers it. Off unless the integration is enabled; the outbox row
        // commits atomically with the order, so a slow/unavailable QuikShipX never
        // blocks or fails the punch.
        publishToQuikShipX(saved);
        return OrderResponse.from(saved);
    }

    /** Enqueues the admin "order needs approval" nudge for a newly punched pending order. */
    private void publishAwaitingApproval(OrderEntity order) {
        if (outboxEventPublisher != null
                && order.getOrderStatus() == OrderStatus.PENDING_ADMIN_APPROVAL) {
            outboxEventPublisher.publishOrderAwaitingApproval(
                    order.getId(), order.getOrderCode(), order.getCustomerName(),
                    order.getTotalAmount() == null ? "" : order.getTotalAmount().toPlainString());
        }
    }

    /**
     * Enqueues the QuikShipX create-order event for a new order when the
     * integration is enabled AND the order is not flagged for in-house delivery
     * (no-op otherwise). An {@code IN_HOUSE} order never gets an
     * {@code OrderShipment} row and is invisible to the whole QuikShipX pipeline.
     */
    private void publishToQuikShipX(OrderEntity order) {
        if (outboxEventPublisher != null && quikShipXProperties != null
                && quikShipXProperties.isEnabled() && !order.isInHouseDelivery()) {
            outboxEventPublisher.publishQuikShipXCreate(order.getId(), order.getOrderCode());
        }
    }

    /** Parses the optional delivery-method request field; blank/null defaults to QUIKSHIPX. */
    private static DeliveryMethod parseDeliveryMethod(String raw) {
        if (raw == null || raw.isBlank()) {
            return DeliveryMethod.QUIKSHIPX;
        }
        return DeliveryMethod.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
    }

    // --- Admin edit-order (correct salesperson-entered details) ------------

    /**
     * Statuses in which an order may still be edited by an admin: only before
     * physical fulfilment begins — a printed label / QuikShipX payload / packed
     * box already reflects the original details from {@link OrderStatus#LABEL_GENERATED}
     * onward, so editing is restricted to the two earliest statuses (edit-order
     * feature).
     */
    private static final java.util.Set<OrderStatus> EDITABLE_STATUSES =
            java.util.EnumSet.of(OrderStatus.PENDING_ADMIN_APPROVAL, OrderStatus.APPROVED);

    /**
     * Admin edit-order (Req: "As an Admin I should be able to update the order
     * details ... to correct what salesperson has added"). Re-prices the edited
     * line items through the same {@link OrderPricing} engine used at creation,
     * reconciles tracked-product stock against the quantity delta per product,
     * and overwrites the customer/shipping/lead-source/note/GSTIN/discount
     * fields. Rejected with {@link OrderNotEditableException} (409) once the
     * order has moved past {@link #EDITABLE_STATUSES} (label generated / packed /
     * dispatched, etc.) — the printed label, courier payload, and stock have
     * already been committed against the original details by then.
     *
     * <p>No status-history row is added: a field edit is orthogonal to a status
     * transition. The caller (controller) records an audit entry.
     */
    @Transactional
    public OrderResponse updateOrder(Long id, UpdateOrderRequest request, AuthPrincipal admin) {
        OrderEntity order = orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + id + " does not exist."));
        if (!EDITABLE_STATUSES.contains(order.getOrderStatus())) {
            throw new OrderNotEditableException(order.getOrderCode(), order.getOrderStatus());
        }

        String diff = applyEditedFields(order, request, admin.userId());

        OrderEntity saved = orderRepository.save(order);
        auditOrderEdit(saved, diff, admin, "Edited");
        return OrderResponse.from(saved);
    }

    /**
     * Statuses in which the creating salesperson / team lead may edit their OWN
     * order (own-pending-edit feature). Restricted to before approval: once an
     * admin has approved it, only an admin may edit (via {@link #updateOrder}),
     * so a salesperson can't silently change an approved order.
     */
    private static final java.util.Set<OrderStatus> OWN_EDITABLE_STATUSES =
            java.util.EnumSet.of(OrderStatus.PENDING_ADMIN_APPROVAL);

    /**
     * Edit of an order by the person who punched it (own-pending-edit feature):
     * a salesperson / team lead corrects the customer / shipping / line-item /
     * lead-source / note / GSTIN / discount details of their OWN order while it is
     * still {@code Pending_Admin_Approval} (a change may come from the customer or
     * the agent before an admin reviews it). Payment capture is not editable here.
     *
     * <p>Own-order scoped via {@link #loadScoped} (a salesperson sees only their
     * own order, a team lead their team's — anything else is a 404). Rejected with
     * a {@link ValidationException} (400) once the order has left
     * {@code Pending_Admin_Approval} (approved / in fulfilment), directing the user
     * to an admin. Records a field-level ORDER_UPDATED audit entry (who/what/when).
     */
    @Transactional
    public OrderResponse updateOwnOrder(Long id, UpdateOrderRequest request, AuthPrincipal actor) {
        OrderEntity order = loadScoped(id, actor);
        if (!OWN_EDITABLE_STATUSES.contains(order.getOrderStatus())) {
            throw new ValidationException("Order " + order.getOrderCode()
                    + " can no longer be edited because it is no longer awaiting approval."
                    + " Ask an admin to make changes.");
        }

        String diff = applyEditedFields(order, request, actor.userId());

        OrderEntity saved = orderRepository.save(order);
        auditOrderEdit(saved, diff, actor, "Edited");
        return OrderResponse.from(saved);
    }

    /**
     * Statuses from which a salesperson (or admin) may rework a REJECTED order
     * back into the approval queue (rejection-status rework feature).
     */
    private static final java.util.Set<OrderStatus> RESUBMITTABLE_STATUSES =
            java.util.EnumSet.of(OrderStatus.REJECTED, OrderStatus.PAYMENT_REJECTED);

    /** Source recorded on the resubmit status-history row. */
    private static final String SOURCE_RESUBMIT = "SALESPERSON";

    /**
     * Reworks a rejected order back into the approval queue (rejection-status
     * rework feature): the creating salesperson (or an admin) fixes the flagged
     * details and resubmits, moving the order
     * {@code REJECTED | PAYMENT_REJECTED → PENDING_ADMIN_APPROVAL}.
     *
     * <p>Own-order scoped via {@link #loadScoped} (a salesperson can only resubmit
     * an order they created; anything else is a 404). Rejected with a
     * {@link ValidationException} (400) when the order is not in a resubmittable
     * status. On success it: re-applies the edited customer/shipping/line/discount
     * details (re-priced + stock-reconciled exactly like an edit), clears the
     * rejection category + note, resets a prepaid order's payment verification to
     * PENDING (so the payment is re-checked), transitions to
     * {@code PENDING_ADMIN_APPROVAL} through the central workflow (authority +
     * one history row + audit + notification fan-out), and re-fires the admin
     * "awaiting approval" nudge.
     */
    @Transactional
    public OrderResponse resubmit(Long id, UpdateOrderRequest request, AuthPrincipal actor) {
        OrderEntity order = loadScoped(id, actor);
        if (!RESUBMITTABLE_STATUSES.contains(order.getOrderStatus())) {
            throw new ValidationException("Order " + order.getOrderCode()
                    + " is not rejected, so it cannot be resubmitted for approval.");
        }
        if (orderWorkflowService == null) {
            // Defensive: the full (Spring) constructor always supplies the workflow.
            throw new IllegalStateException("Workflow service is required to resubmit an order.");
        }

        boolean wasPaymentRejected = order.getOrderStatus() == OrderStatus.PAYMENT_REJECTED;

        // Re-apply the corrected details (same re-price + stock reconcile as an edit).
        String diff = applyEditedFields(order, request, actor.userId());

        // Clear the rejection so the reworked order carries no stale reason.
        order.setRejectReason(null, null);

        // A payment-rejected order's proof was disputed — send it back for a fresh
        // authenticity check when it still carries a payment (prepaid/partial).
        if (wasPaymentRejected && order.getPaymentStatus() != PaymentStatus.COD) {
            order.markPaymentPendingVerification();
        }

        // Transition back to the approval queue through the central workflow so the
        // authority check, single history row, audit, and notification fan-out all
        // apply. The acting salesperson/admin is recorded as the actor.
        orderWorkflowService.applyTransition(
                order, OrderStatus.PENDING_ADMIN_APPROVAL, Actor.user(actor, SOURCE_RESUBMIT));

        OrderEntity saved = orderRepository.save(order);
        // Record what the salesperson changed while reworking (audit trail). The
        // workflow already audited the status transition; this adds the field diff.
        auditOrderEdit(saved, diff, actor, "Resubmitted");
        // Re-nudge admins that an order is (again) awaiting approval.
        publishAwaitingApproval(saved);
        return OrderResponse.from(saved);
    }

    /**
     * Shared field-apply used by both {@link #updateOrder} and {@link #resubmit}:
     * validates + re-prices the edited line items through {@link OrderPricing},
     * reconciles tracked-product stock against the previously-committed quantities,
     * and overwrites the customer / shipping / lead-source / note / GSTIN /
     * discount fields and amounts on the order. Does NOT save or change status.
     */
    private String applyEditedFields(OrderEntity order, UpdateOrderRequest request, Long actorUserId) {
        OrderCreationValidator.validateLeadSourceNote(request.leadSourceNote());

        // Snapshot the old field values BEFORE mutating so we can build a concise
        // "what changed" diff for the audit trail (who/what/when).
        Map<String, String> before = fieldSnapshot(order);

        // Snapshot the quantity previously committed per tracked product so the
        // stock ledger can be reconciled to the new lines below (stock was
        // reserved against the ORIGINAL items when the order was first created).
        Map<Long, Integer> previousQuantities = new LinkedHashMap<>();
        for (OrderLineItem existing : order.getLineItems()) {
            if (existing.getProductId() != null) {
                previousQuantities.merge(existing.getProductId(), existing.getQuantity(), Integer::sum);
            }
        }

        List<PricedLine> priced = priceLines(request.items());

        OrderPricing.DiscountSpec discountSpec = OrderPricing.DiscountSpec.of(
                DiscountType.from(request.discountType()), request.discountValue());
        OrderPricing.PricedOrder pricedOrder = OrderPricing.compute(toPricingLines(priced), discountSpec);
        Money total = Money.of(pricedOrder.total());
        requirePositiveTotal(total);

        // Re-run the payment classification against the ALREADY-received amount
        // (edit never touches payment capture) so remaining/COD stay correct
        // if the re-priced total differs from the original.
        PaymentCalculation calc = PaymentCalculator.classify(total, Money.of(order.getAmountReceived()));

        String buyerGstin = trimToNull(request.buyerGstin());
        if (buyerGstin != null && !Gstin.isValid(buyerGstin)) {
            throw new ValidationException(
                    "buyerGstin must be a valid 15-character GSTIN "
                            + "(2-digit state code, 10-character PAN, 1 entity digit, the letter Z, "
                            + "and 1 checksum character).");
        }

        // Reconcile tracked-product stock: for each product touched by either the
        // old or new lines, apply the signed delta (old qty − new qty) so on-hand
        // reflects exactly the new lines, rejecting when a tracked product lacks
        // enough stock to cover an increase.
        reconcileStockForEdit(previousQuantities, priced, order.getOrderCode(), actorUserId);

        order.setCustomerName(request.customerName());
        order.setCustomerMobile(request.customerMobile());
        order.setAlternateMobile(trimToNull(request.alternateMobile()));
        order.setCustomerEmail(request.customerEmail());
        order.setAddressLine(request.addressLine());
        order.setCity(request.city());
        order.setState(request.state());
        order.setPostalCode(request.postalCode());
        order.setLeadSource(request.leadSource());
        order.setLeadSourceNote(request.leadSourceNote());
        order.setNotes(trimToNull(request.notes()));
        order.setBuyerGstin(buyerGstin);

        order.replaceLineItems(priced.stream().map(PricedLine::toEntity).toList());

        order.applyAmounts(
                calc.totalAmount().toBigDecimal(),
                calc.amountReceived().toBigDecimal(),
                calc.remainingAmount().toBigDecimal(),
                calc.codAmount().toBigDecimal(),
                calc.paymentStatus());
        order.setCustomerOutstanding(calc.codAmount().toBigDecimal());

        DiscountType discountType = discountSpec.type();
        order.applyOrderDiscount(
                discountType == DiscountType.NONE ? null : discountType.name(),
                discountType == DiscountType.NONE ? null : discountSpec.value(),
                pricedOrder.discount());

        // Build the field-level diff from the before/after snapshots (audit trail).
        return diffSummary(before, fieldSnapshot(order));
    }

    /**
     * Captures the audited scalar fields of an order into an ordered label→value
     * map, so an edit can be diffed old→new for the audit trail. Amounts/status/
     * payment-verification are intentionally excluded (payment capture is not
     * editable here); the line-item set is summarised as a single "Items" entry.
     */
    private static Map<String, String> fieldSnapshot(OrderEntity order) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("Customer", nz(order.getCustomerName()));
        m.put("Mobile", nz(order.getCustomerMobile()));
        m.put("Alt mobile", nz(order.getAlternateMobile()));
        m.put("Email", nz(order.getCustomerEmail()));
        m.put("Address", nz(order.getAddressLine()));
        m.put("City", nz(order.getCity()));
        m.put("State", nz(order.getState()));
        m.put("Pincode", nz(order.getPostalCode()));
        m.put("Lead source", order.getLeadSource() == null ? "" : order.getLeadSource().name());
        m.put("Lead note", nz(order.getLeadSourceNote()));
        m.put("Notes", nz(order.getNotes()));
        m.put("GSTIN", nz(order.getBuyerGstin()));
        m.put("Discount", order.getDiscountAmount() == null ? "0" : order.getDiscountAmount().toPlainString());
        m.put("Total", order.getTotalAmount() == null ? "0" : order.getTotalAmount().toPlainString());
        m.put("Items", itemsSummary(order.getLineItems()));
        return m;
    }

    /** A compact "product×qty@rate; …" summary of the order lines for diffing. */
    private static String itemsSummary(List<OrderLineItem> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        return lines.stream()
                .map(li -> nz(li.getProductName()) + "×" + li.getQuantity()
                        + "@" + (li.getRate() == null ? "0" : li.getRate().toPlainString()))
                .collect(java.util.stream.Collectors.joining("; "));
    }

    /**
     * Builds a concise human-readable diff of the changed fields (label:
     * old→new), or an empty string when nothing changed. Used as the audit
     * summary so the trail shows exactly what an edit changed.
     */
    private static String diffSummary(Map<String, String> before, Map<String, String> after) {
        List<String> changes = new ArrayList<>();
        for (Map.Entry<String, String> e : after.entrySet()) {
            String old = before.getOrDefault(e.getKey(), "");
            String now = e.getValue();
            if (!java.util.Objects.equals(old, now)) {
                changes.add(e.getKey() + ": '" + old + "' → '" + now + "'");
            }
        }
        return String.join("; ", changes);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    /**
     * Records the field-level ORDER_UPDATED audit entry for an edit/resubmit,
     * naming who changed what. No-op when the audit service is absent (legacy
     * tests) or nothing actually changed. Best-effort — never blocks the edit.
     */
    private void auditOrderEdit(OrderEntity order, String diff, AuthPrincipal actor, String context) {
        if (auditService == null || diff == null || diff.isBlank()) {
            return;
        }
        String summary = context + " order " + order.getOrderCode() + " — " + diff;
        auditService.record(
                actor == null ? null : actor.userId(),
                actor == null ? null : actor.username(),
                AuditActions.ORDER_UPDATED, AuditActions.ENTITY_ORDER,
                String.valueOf(order.getId()), summary);
    }

    /**
     * Applies the signed per-product stock delta needed to move from the
     * previously-committed quantities to the newly-priced lines (edit-order):
     * a product ordered MORE now records an additional SALE decrement; a
     * product ordered LESS (or removed) returns the difference via
     * {@link StockService#adjust}. Untracked products are skipped (mirrors
     * {@link StockService#recordSale}). Only tracked products actually change
     * on-hand quantity; the ledger reason names the order code.
     */
    private void reconcileStockForEdit(Map<Long, Integer> previousQuantities,
                                       List<PricedLine> priced, String orderCode, Long userId) {
        Map<Long, Integer> newQuantities = new LinkedHashMap<>();
        Map<Long, Product> productsById = new LinkedHashMap<>();
        for (PricedLine line : priced) {
            Long productId = line.product().getId();
            newQuantities.merge(productId, line.quantity(), Integer::sum);
            productsById.put(productId, line.product());
        }
        for (Long productId : previousQuantities.keySet()) {
            productsById.computeIfAbsent(productId, this::requireProduct);
        }

        String reason = "Order " + orderCode + " (edited)";
        for (Map.Entry<Long, Product> entry : productsById.entrySet()) {
            Product product = entry.getValue();
            if (!product.isTrackInventory()) {
                continue;
            }
            int before = previousQuantities.getOrDefault(entry.getKey(), 0);
            int after = newQuantities.getOrDefault(entry.getKey(), 0);
            int delta = before - after; // +delta returns stock, -delta consumes more stock
            if (delta == 0) {
                continue;
            }
            StockMovementType type = delta > 0 ? StockMovementType.RETURN : StockMovementType.ADJUSTMENT;
            stockService.adjust(entry.getKey(), delta, type, reason, userId);
        }
    }

    // --- Payment screenshot upload (two-step) -------------------------------

    /**
     * Stores an uploaded payment screenshot and returns its storage key, to be
     * referenced by a subsequent {@code POST /api/orders} (Req 7.6, 7.11).
     */
    public String storePaymentScreenshot(String originalFilename, String contentType, byte[] content) {
        if (content == null || content.length == 0) {
            throw new ValidationException("The payment screenshot file is empty.");
        }
        return storageService.store("payments", originalFilename, contentType, content).key();
    }

    // --- Search and duplicate detection (Req 22) ----------------------------

    /**
     * Role-scoped order search over name / mobile / order code / id / AWB
     * (Req 22.1, 5.5). A salesperson sees only their own orders; admins and
     * accountants see all.
     */
    @Transactional(readOnly = true)
    public List<OrderSummaryResponse> search(String term, AuthPrincipal actor) {
        String trimmed = (term == null || term.isBlank()) ? null : term.trim();
        Optional<List<Long>> scope = scopeResolver.creatorScope(actor);
        List<OrderEntity> results;
        if (scope.isEmpty()) {
            // Unscoped (admin / accountant): all orders, optionally searched.
            results = trimmed == null
                    ? orderRepository.findAllScoped(null)
                    : orderRepository.search(trimmed, null);
        } else {
            List<Long> ids = scope.get();
            if (ids.isEmpty()) {
                // Scoped to nothing (e.g. a team lead with no assigned salespeople).
                results = List.of();
            } else if (ids.size() == 1) {
                // Single creator (a salesperson) — reuse the single-id query.
                Long only = ids.get(0);
                results = trimmed == null
                        ? orderRepository.findAllScoped(only)
                        : orderRepository.search(trimmed, only);
            } else {
                // Multiple creators (a team lead's team) — scope by the id set.
                results = trimmed == null
                        ? orderRepository.findAllScopedIn(ids)
                        : orderRepository.searchIn(trimmed, ids);
            }
        }
        return results.stream().map(OrderSummaryResponse::from).toList();
    }

    /**
     * IST is the business day boundary used for same-day duplicate detection, so
     * "today" matches how the team reads dates (the app renders all timestamps in
     * IST). The DB stores {@code created_at} in the JVM/DB zone; converting the
     * IST calendar day to a timestamp window is a close enough approximation for
     * this warning/guard (the authoritative block on submit uses the same window).
     */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    /**
     * Duplicate detection for order entry (Req 22.2): whether prior orders exist
     * for a mobile number, and how many (all-time, across all salespeople so a
     * repeat customer is recognised regardless of who entered the earlier order).
     *
     * <p>Also flags a SAME-DAY duplicate: an active (not rejected/cancelled) order
     * already placed TODAY for this mobile — the case a customer reaching two
     * salespeople the same day would create. The response names who placed it and
     * whether that was the current user, so the form can warn before submit. The
     * hard block still lives in {@link #createSalespersonOrder}.
     */
    @Transactional(readOnly = true)
    public DuplicateCheckResponse duplicateCheck(String mobile, AuthPrincipal actor) {
        if (mobile == null || mobile.isBlank()) {
            throw new ValidationException("A mobile number is required for duplicate detection.");
        }
        String trimmed = mobile.trim();
        long count = orderRepository.countByCustomerMobile(trimmed);

        Optional<OrderEntity> todayOrder = latestActiveTodayOrder(trimmed);
        if (todayOrder.isEmpty()) {
            return new DuplicateCheckResponse(trimmed, count > 0, count, false, null, null, false);
        }
        OrderEntity existing = todayOrder.get();
        boolean mine = actor != null && actor.userId() != null
                && actor.userId().equals(existing.getCreatedBy());
        String placedBy = resolveSalespersonName(existing.getCreatedBy());
        return new DuplicateCheckResponse(
                trimmed, count > 0, count, true, existing.getOrderCode(), placedBy, mine);
    }

    /** Backward-compatible overload (no actor) used by non-salesperson callers/tests. */
    @Transactional(readOnly = true)
    public DuplicateCheckResponse duplicateCheck(String mobile) {
        return duplicateCheck(mobile, null);
    }

    /**
     * The most recent ACTIVE (not rejected/cancelled) order placed TODAY (IST) for
     * a mobile, or empty when none. Backs both the pre-submit warning and the
     * authoritative same-day duplicate guard so they agree on what "today" means.
     */
    private Optional<OrderEntity> latestActiveTodayOrder(String mobile) {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        LocalDateTime from = today.atStartOfDay();
        LocalDateTime to = today.plusDays(1).atStartOfDay();
        List<OrderEntity> todays =
                orderRepository.findActiveByCustomerMobileInWindow(mobile, from, to);
        return todays.isEmpty() ? Optional.empty() : Optional.of(todays.get(0));
    }

    /**
     * Customer + shipping details from the customer's MOST RECENT order, to
     * pre-fill the New Order form when a known mobile is entered (so a repeat
     * customer's details aren't re-typed). Looks across all salespeople like the
     * duplicate check; returns an empty (found=false) result when there is no
     * prior order. Order-specific data (items, payment, notes) is never returned.
     */
    @Transactional(readOnly = true)
    public CustomerPrefillResponse lastCustomerByMobile(String mobile) {
        if (mobile == null || mobile.isBlank()) {
            throw new ValidationException("A mobile number is required to look up a customer.");
        }
        return orderRepository.findFirstByCustomerMobileOrderByCreatedAtDescIdDesc(mobile.trim())
                .map(CustomerPrefillResponse::from)
                .orElseGet(CustomerPrefillResponse::empty);
    }

    // --- Payment tracking views (Req 21) ------------------------------------

    /**
     * Role-scoped order detail exposing payment tracking fields (Req 21.1, 5.5)
     * and, when a courier record exists, the shipment fields (AWB, courier name,
     * tracking link, estimated delivery) built via {@link TrackingService}
     * reusing the {@code /api/track} tracking-link logic (Req 13.4, 14.1).
     */
    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long id, AuthPrincipal actor) {
        OrderEntity order = loadScoped(id, actor);
        // Item 1: resolve each line product's primary image in a single batch
        // query (avoids an N+1 across the order's lines) so the detail view can
        // render a per-item thumbnail.
        OrderResponse base = OrderResponse.from(order, primaryImageKeys(order));
        OrderResponse response = trackingService.shipmentFor(order.getId())
                .map(s -> base.withShipment(
                        s.awb(), s.courierName(), s.trackingUrl(), s.estimatedDelivery()))
                // No AWB (in-house delivery, or a partner recorded without a
                // tracking number) — still surface who has the parcel, if known.
                .orElseGet(() -> trackingService.courierNameFor(order.getId())
                        .map(base::withCourierName)
                        .orElse(base));
        // Enrich with the QuikShipX mirror (status label, hosted label URL, their
        // order id) when a shipment has been published for this order.
        if (orderShipmentRepository != null) {
            OrderResponse withCourier = response;
            response = orderShipmentRepository.findByOrderId(order.getId())
                    .map(s -> withCourier.withQuikShip(
                            s.getQuikShipXStatus(), s.getLabelUrl(), s.getShipperOrderId(), s.isTest()))
                    .orElse(withCourier);
        }
        // Surface who punched the order (created_by → display name), so the
        // order-detail drawer shows the salesperson. Best-effort: omitted when the
        // staff directory is unavailable or the creator no longer exists.
        String salespersonName = resolveSalespersonName(order.getCreatedBy());
        if (salespersonName != null) {
            response = response.withSalesperson(salespersonName);
        }
        return response;
    }

    /** The display name (full name, else username) of the given user id, or null. */
    private String resolveSalespersonName(Long userId) {
        if (userRepository == null || userId == null) {
            return null;
        }
        return userRepository.findById(userId)
                .map(u -> (u.getFullName() != null && !u.getFullName().isBlank())
                        ? u.getFullName() : u.getUsername())
                .orElse(null);
    }

    /**
     * Maps each line product's id to its primary (first PUBLISHED, lowest
     * sort-order) image key, batch-loading all line products' images in one
     * query (item 1). Returns an empty map when the order has no product-backed
     * lines. Products without a published image are simply absent from the map.
     */
    private Map<Long, String> primaryImageKeys(OrderEntity order) {
        List<Long> productIds = order.getLineItems().stream()
                .map(OrderLineItem::getProductId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> byProduct = new LinkedHashMap<>();
        // Ordered by (productId, sortOrder): the first row seen per product is
        // its primary image, so keep only the first.
        for (ProductImage image : productImageRepository.findPublishedByProductIds(productIds)) {
            byProduct.putIfAbsent(image.getProductId(), image.getObjectKey());
        }
        return byProduct;
    }

    /**
     * Loads the payment screenshot for an order (Req 21.2). The controller gates
     * this to ACCOUNTANT/ADMIN; here we resolve the stored object by the order's
     * screenshot key.
     */
    @Transactional(readOnly = true)
    public StorageService.StoredObject getPaymentScreenshot(Long id) {
        OrderEntity order = orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + id + " does not exist."));
        String key = order.getPaymentScreenshotKey();
        if (key == null || key.isBlank()) {
            throw new ResourceNotFoundException("Order " + id + " has no payment screenshot.");
        }
        return storageService.load(key)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "The payment screenshot for order " + id + " could not be found."));
    }

    /**
     * Lists every payment proof attached to an order, in upload order (V65).
     *
     * <p>Returns metadata only; the bytes are streamed per proof by
     * {@link #getPaymentScreenshot(Long, Long)}. Orders that predate V65 have their
     * single legacy proof backfilled as the primary one, so this never regresses a
     * historical order to "no proof". An order with no proof yields an empty list
     * rather than a 404 — the absence of proofs is a normal state (pure COD).
     */
    @Transactional(readOnly = true)
    public List<PaymentScreenshotResponse> listPaymentScreenshots(Long id) {
        OrderEntity order = orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + id + " does not exist."));
        return order.getPaymentScreenshots().stream()
                .map(PaymentScreenshotResponse::from)
                .toList();
    }

    /**
     * Loads one specific payment proof of an order by its id (V65).
     *
     * <p>The proof must belong to the given order; a proof id from another order
     * yields a 404 rather than leaking it, so the order id in the path is an
     * enforced part of the lookup and not merely decorative.
     */
    @Transactional(readOnly = true)
    public StorageService.StoredObject getPaymentScreenshot(Long id, Long screenshotId) {
        OrderEntity order = orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + id + " does not exist."));
        OrderPaymentScreenshot screenshot = order.getPaymentScreenshots().stream()
                .filter(s -> s.getId() != null && s.getId().equals(screenshotId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Payment screenshot " + screenshotId + " does not exist for order " + id + "."));
        return storageService.load(screenshot.getStorageKey())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "The payment screenshot for order " + id + " could not be found."));
    }

    // --- Internal helpers ---------------------------------------------------

    /**
     * Loads an order, enforcing scoping (Req 5.5) with a 404 when out of scope: a
     * salesperson sees only their own order; a team lead sees an order created by
     * any of their assigned salespeople; admin/accountant see any order.
     */
    private OrderEntity loadScoped(Long id, AuthPrincipal actor) {
        Optional<List<Long>> scope = scopeResolver.creatorScope(actor);
        if (scope.isPresent()) {
            List<Long> ids = scope.get();
            if (ids.isEmpty()) {
                throw new ResourceNotFoundException("Order " + id + " does not exist.");
            }
            Optional<OrderEntity> found = ids.size() == 1
                    ? orderRepository.findByIdAndCreatedBy(id, ids.get(0))
                    : orderRepository.findByIdAndCreatedByIn(id, ids);
            return found.orElseThrow(
                    () -> new ResourceNotFoundException("Order " + id + " does not exist."));
        }
        return orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + id + " does not exist."));
    }

    /** A line with its resolved product, applied rate, and computed total. */
    private record PricedLine(Product product, String productName, int quantity, Money rate) {
        OrderLineItem toEntity() {
            Money lineTotal = rate.multiply(quantity);
            // Snapshot the product's HSN + GST rate at order time (Feature 2) so a
            // historical invoice shows the correct per-line HSN/tax even if the
            // product's HSN/rate later changes.
            return new OrderLineItem(product.getId(), productName,
                    product.getHsnCode(), product.getGstRate(),
                    quantity, rate.toBigDecimal(), lineTotal.toBigDecimal());
        }
    }

    /**
     * Prices salesperson lines: rate = override when supplied, else product sale
     * (auto-fetch) price (Req 7.2, 7.3), then enforces the per-line price band
     * {@code [minimum_rate, mrp]} (product-catalog-pricing-gst Req 5.2). Legacy
     * products with a null minimum use the sale price as the floor.
     */
    private List<PricedLine> priceLines(List<LineItemRequest> items) {
        List<PricedLine> priced = new ArrayList<>(items.size());
        for (LineItemRequest item : items) {
            Product product = requireProduct(item.productId());
            BigDecimal rate = item.rate() != null ? item.rate() : product.getSalePrice();
            requireRateWithinBand(product, rate);
            priced.add(new PricedLine(product, product.getName(), item.quantity(), Money.of(rate)));
        }
        return priced;
    }

    /**
     * Rejects a per-line selling rate outside the product's price band
     * {@code [minimum_rate, mrp]} with a message naming the allowed range
     * (Req 5.2). Floor falls back to the sale price when no minimum is set.
     */
    private void requireRateWithinBand(Product product, BigDecimal rate) {
        BigDecimal floor = product.getMinimumRate() != null
                ? product.getMinimumRate() : product.getSalePrice();
        BigDecimal ceiling = product.getMrp();
        if (floor != null && rate.compareTo(floor) < 0) {
            throw new ValidationException("The price for '" + product.getName()
                    + "' must be at least " + floor.toPlainString()
                    + " (allowed range " + floor.toPlainString() + "–"
                    + (ceiling != null ? ceiling.toPlainString() : "") + ").");
        }
        if (ceiling != null && rate.compareTo(ceiling) > 0) {
            throw new ValidationException("The price for '" + product.getName()
                    + "' must not exceed the MRP " + ceiling.toPlainString()
                    + " (allowed range " + (floor != null ? floor.toPlainString() : "0") + "–"
                    + ceiling.toPlainString() + ").");
        }
    }

    /** Maps priced lines to the pure pricing engine's inputs (quantity, rate, product GST rate). */
    private static List<OrderPricing.LineInput> toPricingLines(List<PricedLine> priced) {
        List<OrderPricing.LineInput> inputs = new ArrayList<>(priced.size());
        for (PricedLine line : priced) {
            inputs.add(new OrderPricing.LineInput(
                    line.quantity(), line.rate().toBigDecimal(), line.product().getGstRate()));
        }
        return inputs;
    }

    /**
     * Reserves stock for every tracked line product within the current
     * (order-creation) transaction: decrements on-hand and records a SALE
     * movement, rejecting the order with a {@link ValidationException} if a
     * tracked product lacks sufficient stock (Feature 1). Non-tracked products
     * are ignored.
     */
    private void reserveStock(List<PricedLine> priced, Long userId, String orderCode) {
        String reason = "Order " + orderCode;
        for (PricedLine line : priced) {
            stockService.recordSale(line.product(), line.quantity(), reason, userId);
        }
    }

    private Product requireProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ValidationException("Product " + productId + " does not exist."));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void requirePositiveTotal(Money total) {
        if (total.isZero() || total.isNegative()) {
            throw new ValidationException("An order must have a positive Total_Amount.");
        }
    }

    /**
     * The minimum amount that must be collected upfront on a new order (client
     * policy: no COD/₹0 orders — Full or Partial payment only, with at least ₹100
     * paid before the order proceeds).
     */
    private static final Money MIN_UPFRONT_PAYMENT = Money.of(100L);

    /**
     * Enforces the minimum-upfront-payment policy at order entry: the amount
     * received must be at least ₹100, or the full total when the total is under
     * ₹100 (a small order can't require more than it costs). A ₹0 order is no
     * longer permitted. Rejected with a 400 before anything is persisted.
     */
    private void requireMinimumUpfront(Money received, Money total) {
        Money floor = total.compareTo(MIN_UPFRONT_PAYMENT) < 0 ? total : MIN_UPFRONT_PAYMENT;
        if (received.compareTo(floor) < 0) {
            throw new ValidationException(
                    "At least ₹" + floor.toBigDecimal().toPlainString()
                            + " must be collected upfront (full or partial payment) — a ₹0 order is not allowed.");
        }
    }

    /**
     * Rejects a same-day duplicate order (client): if an active (not
     * rejected/cancelled) order already exists TODAY for this customer mobile,
     * creation is blocked with a 400 that names the existing order and who placed
     * it — so a salesperson learns another salesperson already punched the same
     * customer today, rather than silently creating a duplicate. A blank mobile is
     * left to the existing field validation.
     */
    private void requireNoSameDayDuplicate(String mobile, AuthPrincipal actor) {
        if (mobile == null || mobile.isBlank()) {
            return;
        }
        Optional<OrderEntity> existing = latestActiveTodayOrder(mobile.trim());
        if (existing.isEmpty()) {
            return;
        }
        OrderEntity order = existing.get();
        boolean mine = actor != null && actor.userId() != null
                && actor.userId().equals(order.getCreatedBy());
        String placedBy = resolveSalespersonName(order.getCreatedBy());
        String who = mine
                ? "you"
                : (placedBy != null ? placedBy : "another salesperson");
        throw new ValidationException(
                "A duplicate order for this customer was already placed today ("
                        + order.getOrderCode() + ", by " + who
                        + "). Only one order per customer per day is allowed — please check "
                        + "the existing order before creating another.");
    }

    /**
     * The ordered, de-duplicated set of payment proofs for a new order (V65): the
     * legacy single {@code paymentScreenshotKey} first (so it stays the primary
     * proof), then any additional {@code paymentScreenshotKeys}. Null/blank entries
     * are dropped. An order with no proofs yields an empty list.
     */
    private static List<String> effectiveScreenshotKeys(CreateOrderRequest request) {
        List<String> keys = new ArrayList<>();
        addKey(keys, request.paymentScreenshotKey());
        if (request.paymentScreenshotKeys() != null) {
            for (String extra : request.paymentScreenshotKeys()) {
                addKey(keys, extra);
            }
        }
        return keys;
    }

    /** Appends a trimmed, non-blank, not-already-present key. */
    private static void addKey(List<String> keys, String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        String trimmed = key.trim();
        if (!keys.contains(trimmed)) {
            keys.add(trimmed);
        }
    }

    /** The primary (first) proof of an ordered proof set, or null when there are none. */
    private static String primaryKey(List<String> screenshotKeys) {
        return screenshotKeys.isEmpty() ? null : screenshotKeys.get(0);
    }

    /** Fills line items, amounts, initial status, payment proofs, and the creation history row. */
    private void populateAggregate(OrderEntity order, List<PricedLine> priced,
                                   PaymentCalculation calc, List<String> screenshotKeys,
                                   String actor, String source) {
        for (PricedLine line : priced) {
            order.addLineItem(line.toEntity());
        }
        order.applyAmounts(
                calc.totalAmount().toBigDecimal(),
                calc.amountReceived().toBigDecimal(),
                calc.remainingAmount().toBigDecimal(),
                calc.codAmount().toBigDecimal(),
                calc.paymentStatus());
        // Amount the customer still owes at creation equals the COD amount.
        order.setCustomerOutstanding(calc.codAmount().toBigDecimal());
        order.setOrderStatus(OrderStatus.INITIAL);
        // Attach every payment proof in upload order (V65). The first one is mirrored
        // onto the legacy orders.payment_screenshot_key column by the aggregate, so
        // the screenshot-required rule and the paymentScreenshotAvailable projections
        // keep working unchanged. Filename / MIME type / size are not known here (the
        // upload was staged earlier and yields only an opaque key), so they stay null
        // and the read path takes them from the storage layer.
        for (String key : screenshotKeys) {
            order.addPaymentScreenshot(key, null, null, null);
        }

        if (!calc.amountReceived().isZero()) {
            order.addPayment(
                    new OrderPayment(calc.amountReceived().toBigDecimal(), primaryKey(screenshotKeys)));
        }

        // Creation history row: null from-status → initial status (Req 8.2, 8.4).
        order.addStatusHistory(new OrderStatusHistory(null, OrderStatus.INITIAL, actor, source));
    }
}
