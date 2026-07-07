package com.shifa.oms.order;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.SalespersonScopeResolver;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.coupon.CouponApplication;
import com.shifa.oms.coupon.CouponService;
import com.shifa.oms.courier.TrackingService;
import com.shifa.oms.inventory.StockService;
import com.shifa.oms.notification.OrderConfirmationNotifier;
import com.shifa.oms.order.domain.LineItem;
import com.shifa.oms.order.domain.Money;
import com.shifa.oms.order.domain.PaymentCalculation;
import com.shifa.oms.order.domain.PaymentCalculator;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.order.dto.CheckoutRequest;
import com.shifa.oms.order.dto.CreateOrderRequest;
import com.shifa.oms.order.dto.DuplicateCheckResponse;
import com.shifa.oms.order.dto.LineItemRequest;
import com.shifa.oms.order.dto.OrderResponse;
import com.shifa.oms.order.dto.OrderSummaryResponse;
import com.shifa.oms.platform.storage.StorageService;
import com.shifa.oms.product.Product;
import com.shifa.oms.product.ProductRepository;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
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
    private static final String SOURCE_STOREFRONT = "STOREFRONT";

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final OrderCodeGenerator orderCodeGenerator;
    private final StorageService storageService;
    private final SalespersonScopeResolver scopeResolver;
    private final TrackingService trackingService;
    private final CouponService couponService;
    private final OrderConfirmationNotifier orderConfirmationNotifier;
    private final StockService stockService;

    public OrderService(OrderRepository orderRepository,
                        ProductRepository productRepository,
                        OrderCodeGenerator orderCodeGenerator,
                        StorageService storageService,
                        SalespersonScopeResolver scopeResolver,
                        TrackingService trackingService,
                        CouponService couponService,
                        OrderConfirmationNotifier orderConfirmationNotifier,
                        StockService stockService) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.orderCodeGenerator = orderCodeGenerator;
        this.storageService = storageService;
        this.scopeResolver = scopeResolver;
        this.trackingService = trackingService;
        this.couponService = couponService;
        this.orderConfirmationNotifier = orderConfirmationNotifier;
        this.stockService = stockService;
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
        List<PricedLine> priced = priceLines(request.items());
        Money total = totalOf(priced);
        requirePositiveTotal(total);

        Money received = Money.of(request.amountReceived());
        // Enforce screenshot-required rule before computing/persisting (Req 7.6).
        PaymentCalculator.requireScreenshotWhenPaid(received, request.paymentScreenshotKey());
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

        populateAggregate(order, priced, calc, request.paymentScreenshotKey(),
                actor.username(), SOURCE_SALESPERSON);

        // Reserve stock for tracked products within this transaction (Feature 1):
        // decrements on_hand + records a SALE movement, rejecting insufficient stock.
        reserveStock(priced, actor.userId(), order.getOrderCode());

        return OrderResponse.from(orderRepository.save(order));
    }

    // --- Creation: public storefront checkout (Req 3) -----------------------

    /**
     * Creates a storefront order from a customer cart. Each line is priced from
     * the product's current sale price (customers cannot set prices); the order
     * is COD/unpaid at placement ({@code amountReceived = 0}), source
     * {@code STOREFRONT}, {@code createdBy = null}, and starts in
     * {@code Pending_Admin_Approval} (Req 3.6). Returns the created aggregate so
     * the confirmation can show the order code (Req 3.7).
     */
    @Transactional
    public OrderEntity createStorefrontOrder(CheckoutRequest request) {
        return createStorefrontOrder(request, null);
    }

    /**
     * Creates a storefront order, optionally associating it to a logged-in
     * customer (Phase B). When {@code customerUserId} is non-null the order is
     * stamped with it so it appears in that customer's order history; guest
     * checkout passes {@code null} and continues to work unchanged. Pricing, COD
     * classification, and the initial status are identical for both.
     */
    @Transactional
    public OrderEntity createStorefrontOrder(CheckoutRequest request, Long customerUserId) {
        List<PricedLine> priced = priceCheckoutLines(request.items());
        Money subtotal = totalOf(priced);
        requirePositiveTotal(subtotal);

        // Phase D: apply a coupon when one is supplied. The coupon is re-validated
        // server-side (authoritative) and its discount reduces the net payable, so
        // the derived COD / remaining amounts already reflect the discount.
        Money discount = Money.ZERO;
        String appliedCoupon = null;
        String rawCoupon = request.couponCode();
        if (rawCoupon != null && !rawCoupon.isBlank()) {
            CouponApplication application =
                    couponService.applyToCheckout(rawCoupon, subtotal, request.customerMobile());
            discount = application.discountAmount();
            appliedCoupon = application.couponCode();
        }
        Money netTotal = subtotal.subtract(discount);

        // Storefront orders are unpaid at placement → COD for the (net) total.
        PaymentCalculation calc = PaymentCalculator.classify(netTotal, Money.ZERO);

        OrderEntity order = new OrderEntity(
                orderCodeGenerator.generate(orderRepository::existsByOrderCode),
                OrderSource.STOREFRONT,
                null,
                request.customerName(),
                request.customerMobile(),
                request.addressLine(),
                request.city(),
                request.state(),
                request.postalCode());
        order.setCustomerUserId(customerUserId);
        order.applyDiscount(appliedCoupon, discount.toBigDecimal());

        populateAggregate(order, priced, calc, null, "CUSTOMER", SOURCE_STOREFRONT);

        // Reserve stock for tracked products within the checkout transaction
        // (Feature 1): decrements on_hand + records a SALE movement, and guards
        // server-side against ordering more than is in stock.
        reserveStock(priced, customerUserId, order.getOrderCode());

        OrderEntity saved = orderRepository.save(order);
        // Acknowledge the placed order to the customer via WhatsApp, enqueued once
        // on the transactional outbox so it is sent async + retryable by the
        // drainer — never inline on the checkout request path (ROADMAP 1.2). The
        // event row commits atomically with the order.
        orderConfirmationNotifier.notifyOrderPlaced(saved);
        return saved;
    }

    // --- Online payment: mark an order paid (Phase E) -----------------------

    /**
     * Marks an order fully paid after a verified online payment (Phase E). Sets
     * {@code amount_received = total_amount}, {@code remaining_amount = 0},
     * {@code cod_amount = 0}, {@code customer_outstanding = 0} and
     * {@code payment_status = FULLY_PAID}, reusing {@link PaymentCalculator} for
     * the money math. The lifecycle {@code order_status} is deliberately left
     * unchanged so the order stays in the normal approval pipeline; because it is
     * now {@code FULLY_PAID}, settlement treats it as prepaid and closes it on
     * delivery (see {@code com.shifa.oms.reconciliation}).
     *
     * <p><strong>Idempotent</strong>: if the order is already {@code FULLY_PAID}
     * the fields are not re-applied and the current order is returned unchanged,
     * so a duplicate confirm cannot double-apply.
     *
     * @param orderId the order to mark paid
     * @return the (managed) order after the operation
     * @throws ResourceNotFoundException if no order has the given id
     */
    @Transactional
    public OrderEntity markPaidOnline(Long orderId) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + orderId + " does not exist."));
        if (order.getPaymentStatus() == PaymentStatus.FULLY_PAID) {
            return order;
        }
        Money total = Money.of(order.getTotalAmount());
        // Received == total → FULLY_PAID, remaining 0, cod 0 (Req 7.9 semantics).
        PaymentCalculation calc = PaymentCalculator.classify(total, total);
        order.applyAmounts(
                calc.totalAmount().toBigDecimal(),
                calc.amountReceived().toBigDecimal(),
                calc.remainingAmount().toBigDecimal(),
                calc.codAmount().toBigDecimal(),
                calc.paymentStatus());
        order.setCustomerOutstanding(calc.codAmount().toBigDecimal());
        return orderRepository.save(order);
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
        Long createdBy = scopeResolver.creatorConstraint(actor).orElse(null);
        List<OrderEntity> results = (term == null || term.isBlank())
                ? orderRepository.findAllScoped(createdBy)
                : orderRepository.search(term.trim(), createdBy);
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
        OrderResponse response = OrderResponse.from(order);
        return trackingService.shipmentFor(order.getId())
                .map(s -> response.withShipment(
                        s.awb(), s.courierName(), s.trackingUrl(), s.estimatedDelivery()))
                .orElse(response);
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

    /** Loads an order, enforcing salesperson scoping (Req 5.5) with a 404 when out of scope. */
    private OrderEntity loadScoped(Long id, AuthPrincipal actor) {
        Optional<Long> constraint = scopeResolver.creatorConstraint(actor);
        if (constraint.isPresent()) {
            return orderRepository.findByIdAndCreatedBy(id, constraint.get())
                    .orElseThrow(() -> new ResourceNotFoundException("Order " + id + " does not exist."));
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

    /** Prices storefront lines strictly from the product sale price (customers cannot set prices). */
    private List<PricedLine> priceCheckoutLines(List<CheckoutRequest.CheckoutItemRequest> items) {
        List<PricedLine> priced = new ArrayList<>(items.size());
        for (CheckoutRequest.CheckoutItemRequest item : items) {
            Product product = requireProduct(item.productId());
            priced.add(new PricedLine(product, product.getName(), item.quantity(),
                    Money.of(product.getSalePrice())));
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
