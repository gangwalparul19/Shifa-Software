package com.shifa.oms.integration.shopify;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.order.Actor;
import com.shifa.oms.order.OrderCodeGenerator;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.order.OrderPayment;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.OrderStatusHistory;
import com.shifa.oms.order.OrderWorkflowService;
import com.shifa.oms.order.domain.Money;
import com.shifa.oms.order.domain.PaymentCalculation;
import com.shifa.oms.order.domain.PaymentCalculator;
import com.shifa.oms.product.Product;
import com.shifa.oms.product.ProductRepository;
import com.shifa.oms.statemachine.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The persistence half of Shopify ingestion: creates the order, then approves it, in
 * <b>two separate transactions</b>.
 *
 * <p>The split is the whole reason this class exists apart from {@link ShopifyOrderIngestor}.
 * Requirement 4.6 says that when automatic approval fails the order must remain at
 * {@code PENDING_ADMIN_APPROVAL} with its history intact — which is only possible if the
 * creation has already committed. A single transaction would roll the order away with the
 * failed transition, and an order the store has already taken would vanish. Spring's proxying
 * means self-invocation would not start a new transaction, so the two methods live on a
 * separate bean rather than being called from within the ingestor.
 *
 * <p>Both methods are idempotent, so a retried delivery converges rather than duplicating:
 * {@link #resolveOrCreate} resolves an existing order by Shopify order id, and
 * {@link #autoApprove} is a no-op once the order has left {@code PENDING_ADMIN_APPROVAL}.
 */
@Service
public class ShopifyOrderWriter {

    private static final Logger log = LoggerFactory.getLogger(ShopifyOrderWriter.class);

    /** Actor label recorded on the ingested order's history and audit rows. */
    private static final String SHOPIFY_ACTOR = "SHOPIFY_API";

    /** Transition source recorded on the status-history rows of an ingested order. */
    private static final String SHOPIFY_SOURCE = "SHOPIFY";

    /** Shopify's payment state meaning the buyer has already paid in full. */
    private static final String FINANCIAL_STATUS_PAID = "paid";

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final OrderReviewReasonRepository reviewReasonRepository;
    private final OrderCodeGenerator orderCodeGenerator;
    private final OrderWorkflowService orderWorkflowService;
    private final AuditService auditService;

    public ShopifyOrderWriter(OrderRepository orderRepository,
                              ProductRepository productRepository,
                              OrderReviewReasonRepository reviewReasonRepository,
                              OrderCodeGenerator orderCodeGenerator,
                              OrderWorkflowService orderWorkflowService,
                              AuditService auditService) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.reviewReasonRepository = reviewReasonRepository;
        this.orderCodeGenerator = orderCodeGenerator;
        this.orderWorkflowService = orderWorkflowService;
        this.auditService = auditService;
    }

    /**
     * The outcome of resolving or creating the Shifa order for a Shopify order.
     *
     * @param orderId   the resolved or created order's id
     * @param orderCode the order code, for logs and notifications
     * @param created   false when an order already existed for this Shopify order id
     * @param status    the order's status after this step
     * @param reasons   the review reasons now recorded against the order
     */
    public record CreateResult(Long orderId, String orderCode, boolean created,
                               OrderStatus status, List<ReviewReason> reasons) {

        public CreateResult {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }

        public boolean needsReview() {
            return !reasons.isEmpty();
        }
    }

    /**
     * Creates the Shifa order for a Shopify order, or resolves the one already created
     * for it (Req 3.1, 3.10).
     *
     * <p>Never rejects. A missing contact number, an unmatched SKU, an incomplete address
     * or a total that does not add up all become {@link ReviewReason review reasons} on a
     * created order, because losing an order the store has already taken is far worse than
     * recording an imperfect one (Req 3.4, 3.5, 3.7, 3.11).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CreateResult resolveOrCreate(ShopifyOrderModel model) {
        Optional<OrderEntity> existing = orderRepository.findByShopifyOrderId(model.shopifyOrderId());
        if (existing.isPresent()) {
            OrderEntity order = existing.get();
            // Re-record nothing: the UNIQUE(order_id, reason) constraint plus the
            // exists-check below already make reasons idempotent, and the order's
            // content is deliberately not rewritten from a replayed payload.
            return new CreateResult(order.getId(), order.getOrderCode(), false,
                    order.getOrderStatus(), reasonsOf(order.getId()));
        }

        Map<ReviewReason, String> reasons = new EnumMap<>(ReviewReason.class);

        String mobile = MobileNumberNormalizer.normalizeOrEmpty(model.contactNumber());
        if (mobile.isEmpty()) {
            reasons.put(ReviewReason.MISSING_CONTACT, detailOfContact(model.contactNumber()));
        }

        ShopifyOrderModel.Address address = model.address() == null
                ? new ShopifyOrderModel.Address("", "", "", "", "")
                : model.address();
        if (!address.isComplete()) {
            reasons.put(ReviewReason.INCOMPLETE_ADDRESS, missingAddressParts(address));
        }

        if (!model.totalsAgree()) {
            reasons.put(ReviewReason.TOTAL_MISMATCH,
                    "Shopify total " + model.totalPrice().toPlainString()
                            + " vs line items " + model.lineItemsTotal().toPlainString());
        }

        OrderEntity order = new OrderEntity(
                orderCodeGenerator.generate(orderRepository::existsByOrderCode),
                OrderSource.SHOPIFY_API,
                // No Shifa user punched this order. createdBy stays null, which is what
                // keeps a Shopify order out of every salesperson's creator-scoped view.
                null,
                clamp(nameOrFallback(model.customerName()), 100),
                mobile,
                clamp(address.addressLine(), 250),
                clamp(address.city(), 100),
                clamp(address.state(), 100),
                // postal_code is VARCHAR(6): keep the leading six digits or nothing,
                // rather than letting a "SW1A 1AA"-shaped value fail the insert.
                pincode(address.postalCode()));

        order.setShopifyIdentifiers(model.shopifyOrderId(), model.shopifyOrderNumber());
        order.setCustomerEmail(clamp(model.email(), 150));

        List<OrderLineItem> lines = buildLines(model, reasons);
        for (OrderLineItem line : lines) {
            order.addLineItem(line);
        }

        // The Shopify total is persisted verbatim, never recomputed from the lines
        // (Req 3.6) — Shopify applies discounts and shipping Shifa does not model, and a
        // report that disagrees with the store is worse than one that carries a flagged
        // mismatch.
        Money total = Money.of(model.totalPrice());
        Money received = paidUpFront(model) ? total : Money.ZERO;
        PaymentCalculation payment = PaymentCalculator.classify(total, received);

        order.applyAmounts(
                payment.totalAmount().toBigDecimal(),
                payment.amountReceived().toBigDecimal(),
                payment.remainingAmount().toBigDecimal(),
                payment.codAmount().toBigDecimal(),
                payment.paymentStatus());
        order.setCustomerOutstanding(payment.codAmount().toBigDecimal());
        order.setOrderStatus(OrderStatus.INITIAL);
        if (!payment.amountReceived().isZero()) {
            // Recorded without a screenshot key: Shopify collected the money through its
            // own gateway, so there is no screenshot to verify and the order deliberately
            // does NOT enter the payment-verification queue.
            order.addPayment(new OrderPayment(payment.amountReceived().toBigDecimal(), null));
        }

        // Exactly one creation history row, with no source status (Req 4.1, 4.3).
        order.addStatusHistory(new OrderStatusHistory(
                null, OrderStatus.INITIAL, SHOPIFY_ACTOR, SHOPIFY_SOURCE));

        // Stock is deliberately NOT reserved here. An internal order rejects on
        // insufficient stock, but a Shopify order has already been sold; failing
        // ingestion over a stock shortfall would lose it. The shortfall surfaces
        // through the existing low-stock alerting instead.
        OrderEntity saved = orderRepository.save(order);
        recordReasons(saved.getId(), reasons);

        auditService.record(null, SHOPIFY_ACTOR, AuditActions.SHOPIFY_ORDER_INGESTED,
                AuditActions.ENTITY_ORDER, String.valueOf(saved.getId()),
                "Ingested Shopify order " + describe(model) + " as " + saved.getOrderCode()
                        + (reasons.isEmpty() ? "" : " (review: " + reasons.keySet() + ")"));

        log.info("Created order {} from Shopify order {}{}", saved.getOrderCode(),
                model.shopifyOrderId(), reasons.isEmpty() ? "" : " needing review " + reasons.keySet());

        return new CreateResult(saved.getId(), saved.getOrderCode(), true,
                saved.getOrderStatus(), new ArrayList<>(reasons.keySet()));
    }

    /**
     * Applies the single SYSTEM transition to {@code APPROVED} that lets a Shopify order
     * skip the approval queue (Req 4.5).
     *
     * <p>Runs in its own transaction so a rejected transition rolls back only itself,
     * leaving the created order at {@code PENDING_ADMIN_APPROVAL} with its history intact
     * (Req 4.6). Approval happens whether or not the order needs review — QuikShipX is
     * already moving the parcel, so holding it back would only make Shifa wrong.
     *
     * @return true when this call performed the transition, false when it was already applied
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean autoApprove(Long orderId) {
        OrderEntity order = orderRepository.findById(orderId).orElseThrow(
                () -> new IllegalStateException("Order " + orderId + " disappeared before approval"));
        if (order.getOrderStatus() != OrderStatus.PENDING_ADMIN_APPROVAL) {
            // Already approved by an earlier attempt; converge rather than transition twice.
            return false;
        }
        orderWorkflowService.applyTransition(order, OrderStatus.APPROVED,
                Actor.system(SHOPIFY_ACTOR, SHOPIFY_SOURCE));
        orderRepository.save(order);
        return true;
    }

    /** The review reasons currently recorded against an order (Req 3.9). */
    @Transactional(readOnly = true)
    public List<ReviewReason> reasonsOf(Long orderId) {
        return reviewReasonRepository.findByOrderIdOrderByIdAsc(orderId).stream()
                .map(OrderReviewReason::getReason)
                .toList();
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Records each reason at most once (Req 3.12). The exists-check handles the common
     * case and {@code UNIQUE(order_id, reason)} handles the concurrent one, so a
     * re-processed delivery cannot accumulate duplicates.
     */
    private void recordReasons(Long orderId, Map<ReviewReason, String> reasons) {
        for (Map.Entry<ReviewReason, String> entry : reasons.entrySet()) {
            if (reviewReasonRepository.existsByOrderIdAndReason(orderId, entry.getKey())) {
                continue;
            }
            reviewReasonRepository.save(
                    new OrderReviewReason(orderId, entry.getKey(), entry.getValue()));
        }
    }

    private List<OrderLineItem> buildLines(ShopifyOrderModel model, Map<ReviewReason, String> reasons) {
        List<ShopifyOrderModel.LineItem> items = model.lineItems();
        if (items.isEmpty()) {
            return List.of();
        }

        Map<String, Long> skuIndex = skuIndex();
        Map<Long, Product> byId = new LinkedHashMap<>();
        List<OrderLineItem> lines = new ArrayList<>(items.size());
        List<String> unmatched = new ArrayList<>();

        for (ShopifyOrderModel.LineItem item : items) {
            ShopifySkuMatcher.Match match = ShopifySkuMatcher.match(item.sku(), skuIndex);
            Product product = null;
            if (match.isMatched()) {
                product = byId.computeIfAbsent(match.productId(),
                        id -> productRepository.findById(id).orElse(null));
            } else {
                unmatched.add(describeUnmatched(item, match));
            }

            String name = item.name() == null || item.name().isBlank()
                    ? (product == null ? "Shopify item" : product.getName())
                    : item.name();
            BigDecimal rate = item.unitPrice();
            lines.add(new OrderLineItem(
                    product == null ? null : product.getId(),
                    clamp(name, 200),
                    product == null ? null : product.getHsnCode(),
                    product == null ? null : product.getGstRate(),
                    item.quantity(),
                    rate,
                    item.lineTotal()));
        }

        if (!unmatched.isEmpty()) {
            reasons.put(ReviewReason.UNMAPPED_SKU, String.join("; ", unmatched));
        }
        return lines;
    }

    /**
     * The SKU lookup for this ingestion.
     *
     * <p>Loads the whole catalogue rather than querying per line: the catalogue is small,
     * and building the index in one place is what lets the "exactly one product" rule be a
     * property of the pure {@link ShopifySkuMatcher} instead of an implicit consequence of
     * the database's unique constraint.
     */
    private Map<String, Long> skuIndex() {
        List<ShopifySkuMatcher.CatalogueEntry> catalogue = productRepository.findAll().stream()
                .map(p -> new ShopifySkuMatcher.CatalogueEntry(p.getId(), p.getSku()))
                .toList();
        return ShopifySkuMatcher.index(catalogue);
    }

    private static String describeUnmatched(ShopifyOrderModel.LineItem item,
                                            ShopifySkuMatcher.Match match) {
        String sku = item.sku() == null || item.sku().isBlank() ? "(no SKU)" : item.sku().trim();
        return sku + " -> " + match.outcome();
    }

    private static boolean paidUpFront(ShopifyOrderModel model) {
        return model.financialStatus() != null
                && FINANCIAL_STATUS_PAID.equalsIgnoreCase(model.financialStatus().trim());
    }

    private static String detailOfContact(String raw) {
        return raw == null || raw.isBlank()
                ? "Shopify supplied no contact number."
                : "Could not read 10 digits from \"" + clamp(raw, 60) + "\".";
    }

    private static String missingAddressParts(ShopifyOrderModel.Address address) {
        List<String> missing = new ArrayList<>(4);
        if (blank(address.addressLine())) {
            missing.add("address line");
        }
        if (blank(address.city())) {
            missing.add("city");
        }
        if (blank(address.state())) {
            missing.add("state");
        }
        if (blank(address.postalCode())) {
            missing.add("postal code");
        }
        return "Absent: " + String.join(", ", missing) + ".";
    }

    /** {@code customer_name} is NOT NULL, so an anonymous buyer still needs a label. */
    private static String nameOrFallback(String name) {
        return blank(name) ? "Shopify customer" : name.trim();
    }

    /** Keeps the first six digits, or empty when there are none. */
    private static String pincode(String postalCode) {
        if (postalCode == null) {
            return "";
        }
        StringBuilder digits = new StringBuilder(6);
        for (int i = 0; i < postalCode.length() && digits.length() < 6; i++) {
            char c = postalCode.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        return digits.toString();
    }

    private static String describe(ShopifyOrderModel model) {
        String number = model.shopifyOrderNumber();
        return blank(number) ? model.shopifyOrderId()
                : number + " (" + model.shopifyOrderId() + ")";
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String clamp(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
