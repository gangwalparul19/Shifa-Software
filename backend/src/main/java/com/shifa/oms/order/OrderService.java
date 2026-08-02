package com.shifa.oms.order;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.SalespersonScopeResolver;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.courier.TrackingService;
import com.shifa.oms.inventory.StockService;
import com.shifa.oms.order.domain.LineItem;
import com.shifa.oms.order.domain.Money;
import com.shifa.oms.order.domain.PaymentCalculation;
import com.shifa.oms.order.domain.PaymentCalculator;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.order.dto.CreateOrderRequest;
import com.shifa.oms.order.dto.CustomerPrefillResponse;
import com.shifa.oms.order.dto.DuplicateCheckResponse;
import com.shifa.oms.order.dto.LineItemRequest;
import com.shifa.oms.order.dto.OrderResponse;
import com.shifa.oms.order.dto.OrderSummaryResponse;
import com.shifa.oms.platform.storage.StorageService;
import com.shifa.oms.product.Product;
import com.shifa.oms.product.ProductImage;
import com.shifa.oms.product.ProductImageRepository;
import com.shifa.oms.product.ProductRepository;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

    public OrderService(OrderRepository orderRepository,
                        ProductRepository productRepository,
                        OrderCodeGenerator orderCodeGenerator,
                        StorageService storageService,
                        SalespersonScopeResolver scopeResolver,
                        TrackingService trackingService,
                        StockService stockService,
                        ProductImageRepository productImageRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.orderCodeGenerator = orderCodeGenerator;
        this.storageService = storageService;
        this.scopeResolver = scopeResolver;
        this.trackingService = trackingService;
        this.stockService = stockService;
        this.productImageRepository = productImageRepository;
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
        // Round the order total to the nearest whole rupee (product-audit §4.6),
        // e.g. 2679.99 -> 2680.00. With GST-inclusive pricing (the default) the
        // invoice grand total equals this total, so the tax breakdown stays
        // reconciled; COD/remaining are derived from the rounded total below.
        Money total = totalOf(priced).roundToWholeRupees();
        requirePositiveTotal(total);

        Money received = Money.of(request.amountReceived());
        // Enforce screenshot-required rule before computing/persisting (Req 7.6).
        PaymentCalculator.requireScreenshotWhenPaid(received, request.paymentScreenshotKey());
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
                // Orders punched in the Shifa Admin Portal (ADMIN / SALESPERSON /
                // TEAM_LEAD) carry the SHIFA_ADMIN channel; the request never
                // supplies it (Req 1.3). Historic rows keep the legacy
                // SALESPERSON value, which OrderSource.canonical() folds to this.
                OrderSource.SHIFA_ADMIN,
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

        // Prepaid / partially-paid orders carry a payment to verify for authenticity
        // (product-audit §4.4). Pure COD orders have nothing to verify.
        if (calc.paymentStatus() != PaymentStatus.COD) {
            order.markPaymentPendingVerification();
        }

        populateAggregate(order, priced, calc, request.paymentScreenshotKey(),
                actor.username(), SOURCE_SALESPERSON);

        // Reserve stock for tracked products within this transaction (Feature 1):
        // decrements on_hand + records a SALE movement, rejecting insufficient stock.
        reserveStock(priced, actor.userId(), order.getOrderCode());

        return OrderResponse.from(orderRepository.save(order));
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
     * Duplicate detection for order entry (Req 22.2): whether prior orders exist
     * for a mobile number, and how many. Counts across all salespeople so a
     * repeat customer is recognised regardless of who entered the earlier order.
     */
    @Transactional(readOnly = true)
    public DuplicateCheckResponse duplicateCheck(String mobile) {
        if (mobile == null || mobile.isBlank()) {
            throw new ValidationException("A mobile number is required for duplicate detection.");
        }
        long count = orderRepository.countByCustomerMobile(mobile.trim());
        return new DuplicateCheckResponse(mobile.trim(), count > 0, count);
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
        OrderResponse response = OrderResponse.from(order, primaryImageKeys(order));
        return trackingService.shipmentFor(order.getId())
                .map(s -> response.withShipment(
                        s.awb(), s.courierName(), s.trackingUrl(), s.estimatedDelivery()))
                .orElse(response);
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
        LineItem toDomain() {
            return new LineItem(productName, quantity, rate);
        }

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

    /** Prices salesperson lines: rate = override when supplied, else product sale price (Req 7.2, 7.3). */
    private List<PricedLine> priceLines(List<LineItemRequest> items) {
        List<PricedLine> priced = new ArrayList<>(items.size());
        for (LineItemRequest item : items) {
            Product product = requireProduct(item.productId());
            BigDecimal rate = item.rate() != null ? item.rate() : product.getSalePrice();
            priced.add(new PricedLine(product, product.getName(), item.quantity(), Money.of(rate)));
        }
        return priced;
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

    private Money totalOf(List<PricedLine> priced) {
        return PaymentCalculator.totalAmount(priced.stream().map(PricedLine::toDomain).toList());
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

    /** Fills line items, amounts, initial status, screenshot key, and the creation history row. */
    private void populateAggregate(OrderEntity order, List<PricedLine> priced,
                                   PaymentCalculation calc, String screenshotKey,
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
        order.setPaymentScreenshotKey(screenshotKey);

        if (!calc.amountReceived().isZero()) {
            order.addPayment(new OrderPayment(calc.amountReceived().toBigDecimal(), screenshotKey));
        }

        // Creation history row: null from-status → initial status (Req 8.2, 8.4).
        order.addStatusHistory(new OrderStatusHistory(null, OrderStatus.INITIAL, actor, source));
    }
}
