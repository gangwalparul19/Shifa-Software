package com.shifa.oms.order;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.SalespersonScopeResolver;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.courier.TrackingService;
import com.shifa.oms.crm.domain.CustomerRiskCalculator;
import com.shifa.oms.crm.domain.CustomerRiskLevel;
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
import com.shifa.oms.order.dto.StoreLineItemRequest;
import com.shifa.oms.order.dto.StoreOrderRequest;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private static final String SOURCE_SALESPERSON = "SALESPERSON";
    /** Actor/source label recorded on an in-shop (POS / store) order's history rows. */
    private static final String SOURCE_STORE = "STORE";

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
    /**
     * Company/GST settings (nullable): read at order creation to decide whether
     * config-driven auto-approval (V73) applies. Null under the legacy test
     * constructor — auto-approval is then skipped (orders stay pending), so unit
     * tests see the unchanged manual-approval behaviour.
     */
    private final com.shifa.oms.settings.SettingsService settingsService;
    /**
     * Internal label generation (nullable): used by the auto-approval path to
     * generate the shipping label on approval, exactly as the admin approve does.
     * Null under the legacy test constructor.
     */
    private final com.shifa.oms.label.LabelService labelService;

    /** {@code vouchers.source_type} for a finalised sales order (matches AdminOrderService). */
    private static final String LEDGER_SOURCE_ORDER = "ORDER";

    /** Actor label recorded on an auto-approval status-history / audit row. */
    private static final String SOURCE_AUTO_APPROVAL = "AUTO_APPROVAL";

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

    /**
     * Pre-auto-approval constructor (kept for existing 14-arg test call sites):
     * delegates to the full constructor with no SettingsService/LabelService, so
     * auto-approval is a no-op and those unit tests keep the unchanged
     * manual-approval behaviour.
     */
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
        this(orderRepository, productRepository, orderCodeGenerator, storageService, scopeResolver,
                trackingService, stockService, productImageRepository, outboxEventPublisher,
                quikShipXProperties, orderShipmentRepository, userRepository, orderWorkflowService,
                auditService, null, null);
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
                        AuditService auditService,
                        com.shifa.oms.settings.SettingsService settingsService,
                        com.shifa.oms.label.LabelService labelService) {
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
        this.settingsService = settingsService;
        this.labelService = labelService;
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

        // Same-day duplicate guard: a customer can reach two salespeople the same
        // day and get the SAME items punched twice. Reject a second active order for
        // the same mobile on the same calendar day (IST) ONLY when it repeats at
        // least one product from an existing order today — a customer may legitimately
        // place several orders the same day for DIFFERENT items. The message names the
        // existing order + who placed it. Authoritative block; the server 400 also
        // surfaces on the New Order form at submit.
        java.util.Set<Long> newProductIds = priced.stream()
                .map(pl -> pl.product().getId())
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        requireNoSameDayDuplicate(request.customerMobile(), actor, newProductIds);

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

        // "Place on behalf of" (admin only): when an ADMIN punches the order for a
        // salesperson/team lead, attribute the order to that user (created_by +
        // creation-history actor + stock-movement credit) so it shows in their
        // scoped lists and counts toward their performance. A self order (or any
        // non-admin) resolves to the acting user.
        EffectiveCreator creator = resolveEffectiveCreator(request.onBehalfOfUserId(), actor);

        // India vs Outside India (V67): a domestic order keeps the structured
        // city/state/6-digit pincode; an international order captures a single
        // free-text address (in addressLine) with the destination country and
        // leaves the structured parts empty. This resolves + validates the address
        // for the chosen destination and yields the values to persist.
        ResolvedAddress addr = resolveAddress(request);

        OrderEntity order = new OrderEntity(
                orderCodeGenerator.generate(orderRepository::existsByOrderCode),
                OrderSource.SALESPERSON,
                creator.userId(),
                request.customerName(),
                request.customerMobile(),
                addr.addressLine(),
                addr.city(),
                addr.state(),
                addr.postalCode());
        order.setCountry(addr.country());

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
        // courier integration entirely). Blank/null → QUIKSHIPX. A Counter Sale
        // lead source (walk-in shop sale) always forces IN_HOUSE — no delivery
        // partner is ever involved, so neither QuikShipX nor courier assignment
        // is triggered for it (reuses every existing isInHouseDelivery() gate).
        order.setDeliveryMethod(request.leadSource() == LeadSource.COUNTER_SALE
                ? DeliveryMethod.IN_HOUSE
                : parseDeliveryMethod(request.deliveryMethod()));

        // Prepaid / partially-paid orders carry a payment to verify for authenticity
        // (product-audit §4.4). Pure COD orders have nothing to verify.
        if (calc.paymentStatus() != PaymentStatus.COD) {
            order.markPaymentPendingVerification();
        }

        populateAggregate(order, priced, calc, screenshotKeys,
                creator.username(), SOURCE_SALESPERSON);

        // Snapshot the order-level discount (type + raw value + resolved amount)
        // so history and the response reflect it (Req 6.4). No-op amount when none.
        DiscountType discountType = discountSpec.type();
        order.applyOrderDiscount(
                discountType == DiscountType.NONE ? null : discountType.name(),
                discountType == DiscountType.NONE ? null : discountSpec.value(),
                pricedOrder.discount());

        // Reserve stock for tracked products within this transaction (Feature 1):
        // decrements on_hand + records a SALE movement, rejecting insufficient stock.
        reserveStock(priced, creator.userId(), order.getOrderCode());

        OrderEntity saved = orderRepository.save(order);
        // Config-driven auto-approval (V73, DEFAULT OFF): when the admin has
        // enabled it, a low-value, fully-prepaid order from a low-risk customer is
        // approved immediately via the same central workflow the admin approve uses
        // (authority + single history row + audit + notification fan-out + label +
        // ledger post). Anything with a COD balance, above the threshold, or from a
        // medium/high-risk customer is left PENDING for manual approval. No-op in
        // unit tests (settingsService/labelService null) so existing behaviour holds.
        maybeAutoApprove(saved, calc);
        // Real-time admin nudge: a freshly punched order lands in the approval
        // queue, so enqueue an ORDER_AWAITING_APPROVAL event in this same
        // transaction. The SSE relay surfaces it to connected admins; the row is
        // persisted regardless, so nothing is lost when no admin is online.
        // (No-op once the order was auto-approved — it guards on PENDING status.)
        publishAwaitingApproval(saved);
        // QuikShipX (create-on-punch): enqueue the shipment publication in this same
        // transaction so the order appears in QuikShipX's Pending section once the
        // drainer delivers it. Off unless the integration is enabled; the outbox row
        // commits atomically with the order, so a slow/unavailable QuikShipX never
        // blocks or fails the punch.
        publishToQuikShipX(saved);
        return OrderResponse.from(saved);
    }

    /**
     * Creates an in-shop (POS / counter) order for a walk-in customer
     * (store-order feature, ADMIN only). A store order deliberately differs from a
     * salesperson order:
     * <ul>
     *   <li>it is always a {@code COUNTER_SALE} → {@code IN_HOUSE}: no QuikShipX,
     *       no courier assignment, no delivery partner;</li>
     *   <li>NO payment screenshot is required (cash/UPI taken at the counter), the
     *       same-day-duplicate guard does not apply (a walk-in may buy the same item
     *       again), there is no ₹100 minimum-upfront rule, and the salesperson price
     *       band is not enforced (the admin sets the counter price);</li>
     *   <li>line items may be ad-hoc (a consultation fee, a one-off charge) as well
     *       as catalogue products;</li>
     *   <li>the address is optional (a walk-in may give only name + phone);</li>
     *   <li>it is tagged {@link OrderSource#STORE} so it shows as its own channel on
     *       the dashboard.</li>
     * </ul>
     * A fully-paid store order is auto-approved and walked to {@code CLOSED}
     * immediately (the customer paid and left with the goods); a partial payment
     * leaves it {@code APPROVED} with the balance tracked as the customer
     * outstanding.
     *
     * @param request the POS order payload
     * @param admin   the acting ADMIN (the create endpoint is ADMIN-only)
     * @return the created (and possibly auto-closed) order
     */
    @Transactional
    public OrderResponse createStoreOrder(StoreOrderRequest request, AuthPrincipal admin) {
        List<PricedLine> priced = priceStoreLines(request.items());

        OrderPricing.DiscountSpec discountSpec = OrderPricing.DiscountSpec.of(
                DiscountType.from(request.discountType()), request.discountValue());
        OrderPricing.PricedOrder pricedOrder = OrderPricing.compute(toPricingLines(priced), discountSpec);
        Money total = Money.of(pricedOrder.total());
        requirePositiveTotal(total);

        // Payment: full or partial, taken at the counter. NO screenshot, NO minimum
        // upfront, NO same-day-duplicate check (all salesperson-flow guards that make
        // no sense for a walk-in sale). A sub-rupee overage from rounding is absorbed,
        // exactly as for a salesperson order; a genuine over-payment is still rejected
        // by classify().
        Money received = Money.of(request.amountReceived());
        Money overage = received.subtract(total);
        if (overage.compareTo(Money.ZERO) > 0 && overage.compareTo(Money.of(1L)) < 0) {
            received = total;
        }
        PaymentCalculation calc = PaymentCalculator.classify(total, received);

        // Address is optional for a counter sale (no delivery) — store whatever was
        // given, defaulting blanks so the NOT-NULL columns are satisfied.
        OrderEntity order = new OrderEntity(
                orderCodeGenerator.generate(orderRepository::existsByOrderCode),
                OrderSource.STORE,
                admin.userId(),
                request.customerName(),
                request.customerMobile(),
                blankToDash(request.addressLine()),
                trimToEmpty(request.city()),
                trimToEmpty(request.state()),
                trimToEmpty(request.postalCode()));

        // Always a counter sale → in-house (no courier partner ever).
        order.setLeadSource(LeadSource.COUNTER_SALE);
        order.setDeliveryMethod(DeliveryMethod.IN_HOUSE);
        order.setCustomerEmail(request.customerEmail());
        order.setNotes(trimToNull(request.notes()));
        order.setAlternateMobile(trimToNull(request.alternateMobile()));

        String buyerGstin = trimToNull(request.buyerGstin());
        if (buyerGstin != null && !Gstin.isValid(buyerGstin)) {
            throw new ValidationException(
                    "buyerGstin must be a valid 15-character GSTIN.");
        }
        order.setBuyerGstin(buyerGstin);

        // A counter sale has no payment to verify online (the money is in hand), so
        // it never enters the payment-verification queue — do NOT mark it pending.

        populateAggregate(order, priced, calc, java.util.List.of(), admin.username(), SOURCE_STORE);

        DiscountType discountType = discountSpec.type();
        order.applyOrderDiscount(
                discountType == DiscountType.NONE ? null : discountType.name(),
                discountType == DiscountType.NONE ? null : discountSpec.value(),
                pricedOrder.discount());

        reserveStock(priced, admin.userId(), order.getOrderCode());

        OrderEntity saved = orderRepository.save(order);
        // Fast-forward past the approval queue: a counter sale is already decided.
        // Fully paid → walk all the way to CLOSED; partial → leave at APPROVED with
        // the balance tracked. Best-effort; never fails the sale.
        fastForwardStoreOrder(saved, calc);
        return OrderResponse.from(orderRepository.save(saved));
    }

    /**
     * Fast-forwards a just-created store order past the approval queue (store-order
     * feature). Walks the ADMIN-authorized in-house lifecycle via the central
     * {@link OrderWorkflowService}, so each hop records a history row:
     * <ul>
     *   <li>always: {@code PENDING_ADMIN_APPROVAL → APPROVED} (+ ledger post), so a
     *       counter sale never waits for manual approval;</li>
     *   <li>fully paid (no balance to collect): continue
     *       {@code APPROVED → LABEL_GENERATED → PACKED → HANDED_TO_DELIVERY →
     *       DELIVERED → CLOSED}, landing as a completed sale with nothing
     *       outstanding;</li>
     *   <li>partial: stop at {@code APPROVED} — the balance stays as the customer
     *       outstanding so it is tracked as money owed.</li>
     * </ul>
     * Requires the workflow + outbox collaborators (present under the Spring
     * constructor; null under the legacy test constructor, in which case this is a
     * no-op and the order stays {@code PENDING_ADMIN_APPROVAL}). Best-effort: a
     * hiccup leaves the order at the furthest legal state reached and never fails
     * the counter sale.
     */
    private void fastForwardStoreOrder(OrderEntity order, PaymentCalculation calc) {
        if (orderWorkflowService == null) {
            return; // not wired (unit tests) → leave pending
        }
        Actor actor = Actor.user(SOURCE_STORE, Role.ADMIN, SOURCE_STORE);
        try {
            orderWorkflowService.applyTransition(order, OrderStatus.APPROVED, actor);
            if (outboxEventPublisher != null) {
                outboxEventPublisher.publishLedgerPost(LEDGER_SOURCE_ORDER, order.getId());
            }
            boolean fullyPaid = calc.paymentStatus() != PaymentStatus.COD && calc.codAmount().isZero();
            if (fullyPaid) {
                // Walk straight to a completed, closed sale (prepaid → CLOSED).
                orderWorkflowService.applyTransition(order, OrderStatus.LABEL_GENERATED, actor);
                orderWorkflowService.applyTransition(order, OrderStatus.PACKED, actor);
                orderWorkflowService.applyTransition(order, OrderStatus.HANDED_TO_DELIVERY, actor);
                orderWorkflowService.applyTransition(order, OrderStatus.DELIVERED, actor);
                orderWorkflowService.applyTransition(order, OrderStatus.CLOSED, actor);
                order.setCustomerOutstanding(java.math.BigDecimal.ZERO);
                // Book the counter cash to the ledger dated now (same source type as a
                // normal delivery receipt would use for an in-house COD, but here it is
                // a fully-paid sale so the sales voucher already covers it).
            }
        } catch (RuntimeException e) {
            // A counter sale must never fail because a fast-forward hop was blocked;
            // the order simply stays at the furthest legal state reached.
            log.warn("Store-order fast-forward stopped early for {}: {}",
                    order.getOrderCode(), e.getMessage());
        }
    }

    /** Trims to empty string (for optional NOT-NULL address columns on a counter sale). */
    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    /** Address line for a counter sale: the given value, or a dash when none was captured. */
    private static String blankToDash(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() ? "-" : trimmed;
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
     * Config-driven auto-approval (V73). Approves the just-saved order in-place
     * (same transaction) when ALL of the following hold:
     * <ul>
     *   <li>the feature is enabled in Settings and a positive max-amount is set;</li>
     *   <li>the order is still {@code PENDING_ADMIN_APPROVAL} (defensive);</li>
     *   <li>it is fully prepaid — no COD balance to collect on delivery;</li>
     *   <li>the order total is at or below the configured threshold;</li>
     *   <li>the customer's delivery-risk is {@link CustomerRiskLevel#LOW}.</li>
     * </ul>
     * Any other case leaves the order pending for manual approval. The required
     * collaborators ({@code settingsService}, {@code labelService},
     * {@code orderWorkflowService}, {@code outboxEventPublisher}) must all be
     * present — under the legacy test constructors they are null and this is a
     * no-op, preserving the unchanged manual-approval behaviour. Best-effort:
     * never throws out (a config/label hiccup must not fail the order punch).
     */
    private void maybeAutoApprove(OrderEntity order, PaymentCalculation calc) {
        if (settingsService == null || labelService == null
                || orderWorkflowService == null || outboxEventPublisher == null) {
            return; // auto-approval not wired (unit tests) → leave pending
        }
        if (order.getOrderStatus() != OrderStatus.PENDING_ADMIN_APPROVAL) {
            return;
        }
        try {
            com.shifa.oms.settings.AppSettings settings = settingsService.getSettings();
            if (!settings.isAutoApproveEnabled()) {
                return;
            }
            java.math.BigDecimal maxAmount = settings.getAutoApproveMaxAmount();
            if (maxAmount == null || maxAmount.signum() <= 0) {
                return; // no threshold configured → nothing qualifies
            }
            // Fully prepaid only: a COD balance (remaining to collect on delivery)
            // always routes to manual approval.
            if (calc.paymentStatus() == PaymentStatus.COD || !calc.codAmount().isZero()) {
                return;
            }
            java.math.BigDecimal total =
                    order.getTotalAmount() == null ? java.math.BigDecimal.ZERO : order.getTotalAmount();
            if (total.compareTo(maxAmount) > 0) {
                return; // above the auto-approve ceiling
            }
            if (customerRisk(order.getCustomerMobile()) != CustomerRiskLevel.LOW) {
                return; // medium/high-risk customer → manual review
            }
            // Approve via the central workflow (authority + one history row + audit +
            // notification fan-out), then generate the internal label and enqueue the
            // ledger-post event — mirroring the admin/Shopify approve chain exactly.
            orderWorkflowService.applyTransition(order, OrderStatus.APPROVED,
                    Actor.user(SOURCE_AUTO_APPROVAL, Role.ADMIN, SOURCE_AUTO_APPROVAL));
            labelService.generateInternalLabelOnApproval(order, SOURCE_AUTO_APPROVAL);
            orderRepository.save(order);
            outboxEventPublisher.publishLedgerPost(LEDGER_SOURCE_ORDER, order.getId());
        } catch (RuntimeException e) {
            // Defensive: a failed auto-approval must never fail the order punch. The
            // order simply stays PENDING for manual approval.
            log.warn("Auto-approval skipped for order {}: {}", order.getOrderCode(), e.getMessage());
        }
    }

    /**
     * The customer's delivery-reliability risk from their order history, computed
     * with the pure {@link CustomerRiskCalculator} over all of their past orders
     * (unscoped — risk is a property of the customer, not of a salesperson). A
     * brand-new customer (no concluded deliveries) is {@link CustomerRiskLevel#LOW}.
     */
    private CustomerRiskLevel customerRisk(String mobile) {
        if (mobile == null || mobile.isBlank()) {
            return CustomerRiskLevel.LOW;
        }
        long failed = 0;
        long delivered = 0;
        for (OrderEntity past : orderRepository.findByCustomerMobileOrderByCreatedAtDesc(mobile)) {
            OrderStatus status = past.getOrderStatus();
            if (AUTO_APPROVE_FAILED_STATUSES.contains(status)) {
                failed++;
            } else if (AUTO_APPROVE_DELIVERED_STATUSES.contains(status)) {
                delivered++;
            }
        }
        return CustomerRiskCalculator.assess(failed, delivered);
    }

    /** Concluded-delivery success statuses for the auto-approval risk gate. */
    private static final java.util.Set<OrderStatus> AUTO_APPROVE_DELIVERED_STATUSES =
            java.util.EnumSet.of(OrderStatus.DELIVERED, OrderStatus.COD_COLLECTED, OrderStatus.CLOSED);

    /** Concluded-delivery failure statuses for the auto-approval risk gate. */
    private static final java.util.Set<OrderStatus> AUTO_APPROVE_FAILED_STATUSES =
            java.util.EnumSet.of(OrderStatus.CUSTOMER_REJECTED, OrderStatus.DELIVERY_FAILED,
                    OrderStatus.RTO, OrderStatus.REDISPATCH);

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
        requireNotShopifyManaged(order);
        if (!EDITABLE_STATUSES.contains(order.getOrderStatus())) {
            throw new OrderNotEditableException(order.getOrderCode(), order.getOrderStatus());
        }

        String diff = applyEditedFields(order, request, admin.userId());

        OrderEntity saved = orderRepository.save(order);
        auditOrderEdit(saved, diff, admin, "Edited");
        return OrderResponse.from(saved);
    }

    /**
     * Guards against ANY manual edit of a Shopify-imported order (Shopify
     * integration): a Shopify order's details + workflow are driven automatically
     * by the store's webhooks, so letting staff change the amount/items here would
     * desync it from the source of truth (and could set a wrong order amount).
     * Applies to old and new Shopify orders alike since it keys off the persisted
     * {@code source} column. Rejected with a 409 {@link OrderNotEditableException}
     * (a distinct, clear message) leaving the order unchanged.
     */
    private void requireNotShopifyManaged(OrderEntity order) {
        if (order.getSource() == OrderSource.SHOPIFY) {
            throw new OrderNotEditableException(order.getOrderCode(),
                    "It is a Shopify order — its details and status are managed automatically "
                            + "from Shopify and cannot be edited here.");
        }
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
        requireNotShopifyManaged(order);
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
        requireNotShopifyManaged(order);
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

        // Payment: use the corrected amount received when the request supplies one
        // (a rework may fix the amount), else keep what was already received. Then
        // re-classify against the (possibly re-priced) total so remaining/COD stay
        // correct. A sub-rupee overage from rounding is absorbed, as at creation.
        Money received = request.amountReceived() != null
                ? Money.of(request.amountReceived())
                : Money.of(order.getAmountReceived());
        Money overage = received.subtract(total);
        if (overage.compareTo(Money.ZERO) > 0 && overage.compareTo(Money.of(1L)) < 0) {
            received = total;
        }
        PaymentCalculation calc = PaymentCalculator.classify(total, received);

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

        // Attach a NEW payment proof when the rework supplied one (fix for the
        // bug where a screenshot added on a rejected-order resubmit was dropped):
        // the newest proof becomes the primary (so the re-review + legacy viewers
        // surface the fresh one), while older proofs stay in the list as history.
        // A new primary proof is also recorded on the payment row for consistency.
        List<String> newScreenshotKeys = editScreenshotKeys(request);
        if (!newScreenshotKeys.isEmpty()) {
            for (int i = 0; i < newScreenshotKeys.size(); i++) {
                String key = newScreenshotKeys.get(i);
                if (i == 0) {
                    order.addPaymentScreenshotAsPrimary(key, screenshotHash(key));
                } else {
                    order.addPaymentScreenshot(key, null, null, null, screenshotHash(key));
                }
            }
            if (!calc.amountReceived().isZero()) {
                order.addPayment(new OrderPayment(
                        calc.amountReceived().toBigDecimal(), newScreenshotKeys.get(0)));
            }
        }

        // Build the field-level diff from the before/after snapshots (audit trail).
        return diffSummary(before, fieldSnapshot(order));
    }

    /**
     * The ordered set of NEW payment-proof keys supplied on an edit/resubmit:
     * the primary {@code paymentScreenshotKey} followed by any extras, de-duped.
     * Empty when the request carries no new proof (a plain field edit).
     */
    private static List<String> editScreenshotKeys(UpdateOrderRequest request) {
        List<String> keys = new ArrayList<>();
        addKey(keys, request.paymentScreenshotKey());
        if (request.paymentScreenshotKeys() != null) {
            for (String k : request.paymentScreenshotKeys()) {
                addKey(keys, k);
            }
        }
        return keys;
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

    /**
     * SHA-256 (hex) of the stored payment proof's bytes, for duplicate detection
     * (V72). Best-effort: returns {@code null} when storage is unavailable, the
     * object can't be loaded, or hashing fails — a missing hash simply means the
     * proof is never flagged as a duplicate.
     */
    private String screenshotHash(String storageKey) {
        if (storageService == null || storageKey == null || storageKey.isBlank()) {
            return null;
        }
        try {
            return storageService.load(storageKey)
                    .map(obj -> sha256Hex(obj.content()))
                    .orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String sha256Hex(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return null; // SHA-256 is always available on a standard JVM.
        }
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
     * Active salespeople + team leads an ADMIN may place an order on behalf of
     * (the "place on behalf of" picker on the New Order form). Team leads are
     * listed first, then salespeople, each alphabetical by display name; inactive
     * users are omitted. Returns empty when the staff directory is unavailable.
     */
    @Transactional(readOnly = true)
    public List<com.shifa.oms.order.dto.AssignableCreatorResponse> assignableCreators() {
        if (userRepository == null) {
            return List.of();
        }
        List<com.shifa.oms.auth.User> users = new ArrayList<>();
        users.addAll(userRepository.findByRoleOrderByCreatedAtDescIdDesc(
                com.shifa.oms.auth.Role.TEAM_LEAD));
        users.addAll(userRepository.findByRoleOrderByCreatedAtDescIdDesc(
                com.shifa.oms.auth.Role.SALESPERSON));
        return users.stream()
                .filter(com.shifa.oms.auth.User::isActive)
                .map(com.shifa.oms.order.dto.AssignableCreatorResponse::from)
                .toList();
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

    /**
     * A line with its applied rate and computed total, snapshotting the per-line
     * name / HSN / GST at order time (Feature 2) so a historical invoice is stable
     * even if the product later changes.
     *
     * <p>{@code product} is the resolved catalogue product for a normal line (used
     * for stock reservation + the salesperson price band). It is {@code null} for
     * an <strong>ad-hoc</strong> store line (e.g. a consultation fee) that has no
     * catalogue entry — such a line carries its own {@code productId==null},
     * {@code productName}, {@code hsnCode} and {@code gstRate}, and is never
     * stock-reserved.
     */
    private record PricedLine(Product product, Long productId, String productName,
                              String hsnCode, BigDecimal gstRate, int quantity, Money rate) {

        /** A normal catalogue line: snapshot id/name/HSN/GST from the product. */
        static PricedLine ofProduct(Product product, String productName, int quantity, Money rate) {
            return new PricedLine(product, product.getId(), productName,
                    product.getHsnCode(), product.getGstRate(), quantity, rate);
        }

        /** An ad-hoc (non-catalogue) line: no product, explicit name/HSN/GST. */
        static PricedLine adHoc(String name, String hsnCode, BigDecimal gstRate, int quantity, Money rate) {
            return new PricedLine(null, null, name, hsnCode, gstRate, quantity, rate);
        }

        OrderLineItem toEntity() {
            Money lineTotal = rate.multiply(quantity);
            return new OrderLineItem(productId, productName, hsnCode, gstRate,
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
            priced.add(PricedLine.ofProduct(product, product.getName(), item.quantity(), Money.of(rate)));
        }
        return priced;
    }

    /**
     * Prices in-shop (POS / store) lines (store-order feature). A line may be a
     * catalogue product OR an ad-hoc item (consultation fee, one-off charge):
     * <ul>
     *   <li>catalogue line ({@code productId} set): rate = override when supplied,
     *       else the product sale price. The salesperson price band is NOT enforced
     *       — an admin at the counter may discount/negotiate.</li>
     *   <li>ad-hoc line ({@code productId} null): a {@code name} and a {@code rate}
     *       are required; GST defaults to 0 (exempt) unless supplied; no stock.</li>
     * </ul>
     */
    private List<PricedLine> priceStoreLines(List<StoreLineItemRequest> items) {
        List<PricedLine> priced = new ArrayList<>(items.size());
        for (StoreLineItemRequest item : items) {
            if (item.isAdHoc()) {
                String name = trimToNull(item.name());
                if (name == null) {
                    throw new ValidationException(
                            "A custom item needs a name (e.g. \"Consultation fee\").");
                }
                if (item.rate() == null) {
                    throw new ValidationException("A custom item needs a price.");
                }
                BigDecimal gstRate = item.gstRate() != null ? item.gstRate() : BigDecimal.ZERO;
                priced.add(PricedLine.adHoc(
                        name, trimToNull(item.hsnCode()), gstRate, item.quantity(), Money.of(item.rate())));
            } else {
                Product product = requireProduct(item.productId());
                BigDecimal rate = item.rate() != null ? item.rate() : product.getSalePrice();
                priced.add(PricedLine.ofProduct(product, product.getName(), item.quantity(), Money.of(rate)));
            }
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
                    line.quantity(), line.rate().toBigDecimal(), line.gstRate()));
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
            // Ad-hoc store lines (consultation fee, one-off charge) have no
            // catalogue product, so there is no stock to reserve for them.
            if (line.product() != null) {
                stockService.recordSale(line.product(), line.quantity(), reason, userId);
            }
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
    private void requireNoSameDayDuplicate(String mobile, AuthPrincipal actor,
                                           java.util.Set<Long> newProductIds) {
        if (mobile == null || mobile.isBlank() || newProductIds == null || newProductIds.isEmpty()) {
            return;
        }
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        LocalDateTime from = today.atStartOfDay();
        LocalDateTime to = today.plusDays(1).atStartOfDay();
        // Scan every active order the customer already has today; block only when
        // one of them contains a product also in this new order (same mobile + same
        // item = a real duplicate). Different-item orders the same day are allowed.
        for (OrderEntity existing : orderRepository.findActiveByCustomerMobileInWindow(
                mobile.trim(), from, to)) {
            String repeated = firstRepeatedProductName(existing, newProductIds);
            if (repeated == null) {
                continue;
            }
            boolean mine = actor != null && actor.userId() != null
                    && actor.userId().equals(existing.getCreatedBy());
            String placedBy = resolveSalespersonName(existing.getCreatedBy());
            String who = mine ? "you" : (placedBy != null ? placedBy : "another salesperson");
            throw new ValidationException(
                    "This customer already has an order today (" + existing.getOrderCode()
                            + ", by " + who + ") that includes \"" + repeated
                            + "\". A repeat order for the same item on the same day isn't allowed — "
                            + "please check the existing order, or change the items before creating another.");
        }
    }

    /**
     * The name of the first line item in {@code existing} whose product id is also
     * in {@code newProductIds}, or null when there is no product overlap.
     */
    private static String firstRepeatedProductName(OrderEntity existing, java.util.Set<Long> newProductIds) {
        for (OrderLineItem li : existing.getLineItems()) {
            if (li.getProductId() != null && newProductIds.contains(li.getProductId())) {
                return li.getProductName();
            }
        }
        return null;
    }

    /** The user an order is attributed to (created_by + history actor + stock credit). */
    private record EffectiveCreator(Long userId, String username) {
    }

    /**
     * Resolves who a new order is attributed to. Normally this is the acting user
     * (a salesperson/team lead/admin punching their own order). When an ADMIN uses
     * "place on behalf of" with {@code onBehalfOfUserId}, the order is attributed
     * to that chosen user instead — validated to be an ACTIVE {@code SALESPERSON}
     * or {@code TEAM_LEAD}. Only an admin may set the field; a non-admin sending it,
     * an unknown/inactive user, or an ineligible role is rejected with a 400.
     */
    private EffectiveCreator resolveEffectiveCreator(Long onBehalfOfUserId, AuthPrincipal actor) {
        if (onBehalfOfUserId == null) {
            return new EffectiveCreator(actor.userId(), actor.username());
        }
        // Only an admin may attribute an order to someone else.
        if (actor.role() != com.shifa.oms.auth.Role.ADMIN) {
            throw new ValidationException(
                    "Only an admin can place an order on behalf of another user.");
        }
        // Attributing to yourself is fine (no-op); skip the directory lookup.
        if (onBehalfOfUserId.equals(actor.userId())) {
            return new EffectiveCreator(actor.userId(), actor.username());
        }
        if (userRepository == null) {
            throw new ValidationException(
                    "Placing an order on behalf of another user is not available in this context.");
        }
        com.shifa.oms.auth.User target = userRepository.findById(onBehalfOfUserId)
                .orElseThrow(() -> new ValidationException(
                        "The selected user does not exist."));
        com.shifa.oms.auth.Role role = target.getRole();
        if (role != com.shifa.oms.auth.Role.SALESPERSON
                && role != com.shifa.oms.auth.Role.TEAM_LEAD) {
            throw new ValidationException(
                    "An order can only be placed on behalf of a salesperson or team lead.");
        }
        if (!target.isActive()) {
            throw new ValidationException(
                    "The selected user is inactive and cannot be assigned new orders.");
        }
        return new EffectiveCreator(target.getId(), target.getUsername());
    }

    /** The resolved shipping address to persist (domestic or international). */
    private record ResolvedAddress(String addressLine, String city, String state,
                                   String postalCode, String country) {
    }

    /**
     * Resolves and validates the shipping address for the order's destination
     * (India/Outside India, V67). {@code addressLine} is always required. For a
     * DOMESTIC order (blank country, or "India") the structured city + state +
     * 6-digit postal code are required (mirroring the historical rule, enforced
     * here so the DTO can accept a blank address for international orders). For an
     * INTERNATIONAL order the country is recorded and city/state/postalCode are
     * stored empty (the full address lives in {@code addressLine}).
     */
    private ResolvedAddress resolveAddress(CreateOrderRequest request) {
        String addressLine = trimToNull(request.addressLine());
        if (addressLine == null) {
            throw new ValidationException("A delivery address is required.");
        }
        String country = trimToNull(request.country());
        boolean international = country != null && !country.equalsIgnoreCase("India");
        if (international) {
            // Structured parts are meaningless for an international address; store
            // them empty (columns are NOT NULL) and keep the free-text in addressLine.
            return new ResolvedAddress(addressLine, "", "", "", country);
        }
        // Domestic (India): the structured address is required.
        String city = trimToNull(request.city());
        String state = trimToNull(request.state());
        String postalCode = trimToNull(request.postalCode());
        if (city == null || state == null || postalCode == null) {
            throw new ValidationException(
                    "City, state and a 6-digit pincode are required for an order within India.");
        }
        if (!postalCode.matches("\\d{6}")) {
            throw new ValidationException("postalCode must be exactly 6 digits.");
        }
        // country stays null for a domestic order.
        return new ResolvedAddress(addressLine, city, state, postalCode, null);
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
            // Compute a SHA-256 of the stored proof so the payment verification queue
            // can flag the same image reused across orders (dup-detection, V72).
            // Best-effort: a storage read failure just leaves the hash null (no flag).
            order.addPaymentScreenshot(key, null, null, null, screenshotHash(key));
        }

        if (!calc.amountReceived().isZero()) {
            order.addPayment(
                    new OrderPayment(calc.amountReceived().toBigDecimal(), primaryKey(screenshotKeys)));
        }

        // Creation history row: null from-status → initial status (Req 8.2, 8.4).
        order.addStatusHistory(new OrderStatusHistory(null, OrderStatus.INITIAL, actor, source));
    }
}
